package com.example.phonecam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.LifecycleService
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Owns the camera (or screen capture) and the HTTP stream server, so streaming keeps
 * running when the app is minimised. The activity just binds to this service to show
 * a preview and send commands.
 */
class CameraStreamService : LifecycleService() {

    enum class Source { CAMERA, SCREEN }

    inner class LocalBinder : Binder() {
        val service: CameraStreamService get() = this@CameraStreamService
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // ---- state the activity reads (always touched on the main thread) ----
    var streaming = false
        private set
    var source = Source.CAMERA
        private set
    var clientCount = 0
        private set
    var useFront = false
        private set
    var resIndex = 1
        private set
    var zoomRatio = 1f
        private set
    var lastError: String? = null
        private set

    @Volatile
    var mirror = false
        private set

    @Volatile
    var extraRotation = 0
        private set

    /** Called on the main thread whenever any of the state above changes. */
    var stateListener: (() -> Unit)? = null

    /** Called on a background thread with a JPEG of what is being streamed (null = not wanted). */
    @Volatile
    var previewListener: ((ByteArray) -> Unit)? = null

    private val resolutionLabels = listOf("540p", "720p", "1080p")
    private val resolutionSizes = listOf(Size(960, 540), Size(1280, 720), Size(1920, 1080))

    private var server: MjpegServer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var analysis: ImageAnalysis? = null
    private var displayRotation = Surface.ROTATION_90

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenThread: HandlerThread? = null

    private var lastPreviewMs = 0L

    // ------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Streaming", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent == null) {
            if (!streaming) stopSelf()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_START_CAMERA -> {
                displayRotation = intent.getIntExtra(EXTRA_ROTATION, displayRotation)
                if (enterForeground(screen = false)) startCameraMode() else failToStart()
            }
            ACTION_START_SCREEN -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
                if (data != null && enterForeground(screen = true)) {
                    startScreenMode(resultCode, data)
                } else {
                    failToStart()
                }
            }
            ACTION_STOP -> stopStreaming()
            else -> if (!streaming) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        teardown()
        analysisExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun failToStart() {
        notifyState()
        stopSelf()
    }

    // ------------------------------------------------------- public controls

    fun requestStop() = stopStreaming()

    fun switchCamera() {
        useFront = !useFront
        if (streaming && source == Source.CAMERA) bindCamera() else notifyState()
    }

    fun cycleResolution() {
        resIndex = (resIndex + 1) % resolutionSizes.size
        if (streaming && source == Source.CAMERA) bindCamera() else notifyState()
    }

    fun resolutionLabel(): String = resolutionLabels[resIndex]

    fun toggleMirror() {
        mirror = !mirror
        notifyState()
    }

    /** Rotates the streamed picture by a further 90 degrees (0, 90, 180, 270, 0, ...). */
    fun rotateVideo() {
        extraRotation = (extraRotation + 90) % 360
        notifyState()
    }

    fun zoomRange(): Pair<Float, Float> {
        val zs = camera?.cameraInfo?.zoomState?.value
        return Pair(zs?.minZoomRatio ?: 1f, zs?.maxZoomRatio ?: 1f)
    }

    fun setZoom(ratio: Float) {
        val (min, max) = zoomRange()
        val r = ratio.coerceIn(min, maxOf(min, max))
        zoomRatio = r
        camera?.cameraControl?.setZoomRatio(r)
    }

    fun zoomBy(scale: Float) = setZoom(zoomRatio * scale)

    /** Zoom as 0..1 across the camera's zoom range (for the slider). */
    fun zoomFraction(): Float {
        val (min, max) = zoomRange()
        return if (max > min) ((zoomRatio - min) / (max - min)).coerceIn(0f, 1f) else 0f
    }

    fun setZoomFraction(f: Float) {
        val (min, max) = zoomRange()
        setZoom(min + (max - min) * f.coerceIn(0f, 1f))
    }

    fun streamUrls(): List<String> {
        val result = mutableListOf<String>()
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        addr.hostAddress?.let { result.add("http://$it:$PORT/video") }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not list addresses", e)
        }
        return result
    }

    // ----------------------------------------------------------- start / stop

    private fun enterForeground(screen: Boolean): Boolean {
        return try {
            val type = if (screen) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            lastError = "Could not start the background service: ${e.message}"
            false
        }
    }

    private fun beginStreaming(newSource: Source) {
        source = newSource
        lastError = null
        clientCount = 0
        val srv = MjpegServer(
            PORT,
            onClientsChanged = { count ->
                mainHandler.post {
                    clientCount = count
                    refreshNotification()
                    notifyState()
                }
            },
            onError = { message ->
                mainHandler.post {
                    lastError = "Server error: $message"
                    stopStreaming()
                }
            },
        )
        srv.start()
        server = srv
        acquireWifiLock()
        streaming = true
        refreshNotification()
        notifyState()
    }

    private fun startCameraMode() {
        if (streaming) return
        beginStreaming(Source.CAMERA)
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                cameraProvider = future.get()
                bindCamera()
            } catch (e: Exception) {
                Log.e(TAG, "Camera provider failed", e)
                lastError = "Camera error: ${e.message}"
                stopStreaming()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startScreenMode(resultCode: Int, data: Intent) {
        if (streaming) return
        beginStreaming(Source.SCREEN)
        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = manager.getMediaProjection(resultCode, data)
            val thread = HandlerThread("screen-capture").also { it.start() }
            val handler = Handler(thread.looper)
            screenThread = thread
            projection = mp

            // Android 14+ requires a callback to be registered before capturing.
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    mainHandler.post { if (streaming) stopStreaming() }
                }
            }, handler)

            val metrics = Resources.getSystem().displayMetrics
            val scale = minOf(1f, 1280f / maxOf(metrics.widthPixels, metrics.heightPixels))
            val w = maxOf(2, (metrics.widthPixels * scale).toInt() / 2 * 2)
            val h = maxOf(2, (metrics.heightPixels * scale).toInt() / 2 * 2)

            val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            reader.setOnImageAvailableListener({ r -> handleScreenFrame(r) }, handler)
            imageReader = reader
            virtualDisplay = mp.createVirtualDisplay(
                "PhoneCamScreen",
                w,
                h,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Screen capture failed", e)
            lastError = "Screen sharing failed: ${e.message}"
            stopStreaming()
        }
    }

    fun stopStreaming() {
        val wasStreaming = streaming
        teardown()
        if (wasStreaming) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        notifyState()
        stopSelf()
    }

    /** Releases the camera, screen capture, server and Wi-Fi lock. Safe to call repeatedly. */
    private fun teardown() {
        streaming = false
        clientCount = 0
        try {
            server?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "server stop", e)
        }
        server = null
        releaseWifiLock()
        try {
            analysis?.clearAnalyzer()
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "camera unbind", e)
        }
        analysis = null
        camera = null
        stopScreenCapture()
    }

    private fun stopScreenCapture() {
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        imageReader = null
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null
        screenThread?.quitSafely()
        screenThread = null
    }

    // ----------------------------------------------------------------- camera

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        if (!streaming || source != Source.CAMERA) return

        val selector = if (useFront) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        if (!provider.hasCamera(selector)) {
            lastError = "That camera is not available"
            notifyState()
            return
        }

        val resolution = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(
                    resolutionSizes[resIndex],
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                )
            )
            .build()

        val newAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolution)
            .setTargetRotation(displayRotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
        newAnalysis.setAnalyzer(analysisExecutor) { image -> handleCameraFrame(image) }

        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(this, selector, newAnalysis)
            analysis = newAnalysis
            zoomRatio = 1f
            lastError = null
        } catch (e: Exception) {
            Log.e(TAG, "Camera bind failed", e)
            lastError = "Camera error: ${e.message}"
        }
        notifyState()
    }

    /** Runs on the analysis thread for every camera frame. */
    private fun handleCameraFrame(image: ImageProxy) {
        try {
            val srv = server
            // Skip all the work when nobody is watching: saves battery and heat.
            if (srv == null || (!srv.hasClients() && previewListener == null)) return

            val bitmap = image.toBitmap()
            val jpeg = encode(bitmap, (image.imageInfo.rotationDegrees + extraRotation) % 360)
            bitmap.recycle()
            publish(srv, jpeg)
        } catch (e: Exception) {
            Log.e(TAG, "Frame error", e)
        } finally {
            image.close()
        }
    }

    // ----------------------------------------------------------------- screen

    /** Runs on the screen-capture thread when the screen content changes. */
    private fun handleScreenFrame(reader: ImageReader) {
        val image: android.media.Image? = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            null
        }
        if (image == null) return
        try {
            val srv = server
            if (srv == null || (!srv.hasClients() && previewListener == null)) return

            val plane = image.planes[0]
            val w = image.width
            val h = image.height
            val rowPadding = plane.rowStride - plane.pixelStride * w
            val padded = Bitmap.createBitmap(
                w + rowPadding / plane.pixelStride,
                h,
                Bitmap.Config.ARGB_8888,
            )
            padded.copyPixelsFromBuffer(plane.buffer)
            val cropped = if (padded.width != w) Bitmap.createBitmap(padded, 0, 0, w, h) else padded
            val jpeg = encode(cropped, extraRotation)
            if (cropped !== padded) cropped.recycle()
            padded.recycle()
            publish(srv, jpeg)
        } catch (e: Exception) {
            Log.e(TAG, "Screen frame error", e)
        } finally {
            image.close()
        }
    }

    // ----------------------------------------------------------------- shared

    /** Rotates / mirrors the bitmap as requested and returns it as a JPEG. Does not recycle [src]. */
    private fun encode(src: Bitmap, rotationDegrees: Int): ByteArray {
        val matrix = Matrix()
        if (rotationDegrees != 0) matrix.postRotate(rotationDegrees.toFloat())
        if (mirror) matrix.postScale(-1f, 1f)

        val oriented = if (matrix.isIdentity) {
            src
        } else {
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        }
        val buffer = ByteArrayOutputStream(64 * 1024)
        oriented.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, buffer)
        if (oriented !== src) oriented.recycle()
        return buffer.toByteArray()
    }

    private fun publish(srv: MjpegServer, jpeg: ByteArray) {
        srv.publish(jpeg)
        val listener = previewListener
        if (listener != null) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastPreviewMs >= PREVIEW_INTERVAL_MS) {
                lastPreviewMs = now
                listener(jpeg)
            }
        }
    }

    private fun notifyState() {
        val listener = stateListener ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) listener() else mainHandler.post { listener() }
    }

    // ----------------------------------------------------------- Wi-Fi lock

    /** Stops the phone's Wi-Fi radio from power-saving while streaming (a big source of jitter). */
    private fun acquireWifiLock() {
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm.createWifiLock(mode, "PhoneCam").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not take Wi-Fi lock", e)
        }
    }

    private fun releaseWifiLock() {
        try {
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.w(TAG, "Could not release Wi-Fi lock", e)
        }
        wifiLock = null
    }

    // ----------------------------------------------------------- notification

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, CameraStreamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val what = if (source == Source.SCREEN) "screen" else "camera"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("PhoneCam is streaming your $what")
            .setContentText("Viewers: $clientCount. Tap to open.")
            .setContentIntent(openApp)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun refreshNotification() {
        if (!streaming) return
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        } catch (e: Exception) {
            Log.w(TAG, "notify failed", e)
        }
    }

    companion object {
        private const val TAG = "PhoneCamService"
        private const val CHANNEL_ID = "phonecam_stream"
        private const val NOTIF_ID = 1
        private const val JPEG_QUALITY = 70
        private const val PREVIEW_INTERVAL_MS = 80L

        const val PORT = 8080
        const val ACTION_START_CAMERA = "com.example.phonecam.START_CAMERA"
        const val ACTION_START_SCREEN = "com.example.phonecam.START_SCREEN"
        const val ACTION_STOP = "com.example.phonecam.STOP"
        const val EXTRA_ROTATION = "rotation"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
    }
}
