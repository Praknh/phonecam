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
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.*
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.LifecycleService
import java.net.Inet4Address
import java.net.NetworkInterface

class CameraStreamService : LifecycleService(), HttpStreamServer.ControlInterface {

    enum class Source { CAMERA, SCREEN }

    inner class LocalBinder : Binder() {
        val service: CameraStreamService get() = this@CameraStreamService
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())

    var streaming = false; private set
    var source = Source.CAMERA; private set
    var clientCount = 0; private set
    var useFront = false; private set
    var isTorchOn = false; private set
    var resIndex = 1; private set
    var zoomRatio = 1f; private set
    var lastError: String? = null; private set

    var stateListener: (() -> Unit)? = null
    var previewBitmapListener: ((Bitmap) -> Unit)? = null

    private val resolutions = listOf(Size(960, 540), Size(1280, 720), Size(1920, 1080))
    private val resLabels = listOf("540p", "720p", "1080p")

    private var server: HttpStreamServer? = null
    private var encoder: H264Encoder? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var previewUseCase: Preview? = null
    private var analysisUseCase: ImageAnalysis? = null
    private var orientationListener: OrientationEventListener? = null
    private var currentDisplayRotation = Surface.ROTATION_0

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var lastPreviewTime = 0L

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "PhoneCam Streaming", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        setupOrientationListener()
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START_CAMERA -> {
                if (enterForeground(false)) startCameraStreaming() else stopStreaming()
            }
            ACTION_START_SCREEN -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
                if (data != null && enterForeground(true)) {
                    startScreenStreaming(resultCode, data)
                } else stopStreaming()
            }
            ACTION_STOP -> stopStreaming()
        }
        return START_NOT_STICKY
    }

    private fun setupOrientationListener() {
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val newRot = when (orientation) {
                    in 45..134 -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                if (newRot != currentDisplayRotation) {
                    currentDisplayRotation = newRot
                    previewUseCase?.targetRotation = newRot
                    analysisUseCase?.targetRotation = newRot
                }
            }
        }.apply { enable() }
    }

    private fun enterForeground(screen: Boolean): Boolean {
        return try {
            val type = if (screen) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
            acquireLocks()
            true
        } catch (e: Exception) {
            lastError = "Foreground failed: ${e.message}"
            false
        }
    }

    private fun startCameraStreaming() {
        source = Source.CAMERA
        val sz = resolutions[resIndex]
        val enc = H264Encoder(sz.width, sz.height, fps = 30) { nal, isKey ->
            server?.broadcastNal(nal, isKey)
        }
        enc.start()
        encoder = enc

        val srv = HttpStreamServer(PORT, this) { count ->
            mainHandler.post { clientCount = count; refreshNotification(); stateListener?.invoke() }
        }
        srv.start()
        server = srv

        streaming = true
        refreshNotification()
        stateListener?.invoke()

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        val enc = encoder ?: return

        provider.unbindAll()
        val selector = if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

        val resSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy(resolutions[resIndex], ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
            .build()

        val preview = Preview.Builder()
            .setResolutionSelector(resSelector)
            .setTargetRotation(currentDisplayRotation)
            .build()

        enc.inputSurface?.let { surface ->
            preview.setSurfaceProvider(ContextCompat.getMainExecutor(this)) { req ->
                req.provideSurface(surface, ContextCompat.getMainExecutor(this)) {}
            }
        }

        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(Size(640, 360), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER))
                .build())
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        analysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { image ->
            val now = SystemClock.elapsedRealtime()
            if (now - lastPreviewTime > 100 && previewBitmapListener != null) {
                lastPreviewTime = now
                val bmp = image.toBitmap()
                previewBitmapListener?.invoke(bmp)
            }
            image.close()
        }

        try {
            val cam = provider.bindToLifecycle(this, selector, preview, analysis)
            camera = cam
            previewUseCase = preview
            analysisUseCase = analysis

            cam.cameraInfo.zoomState.observe(this) { zs ->
                zoomRatio = zs.zoomRatio
                stateListener?.invoke()
            }
        } catch (e: Exception) {
            lastError = "Bind camera failed: ${e.message}"
            stopStreaming()
        }
    }

    private fun startScreenStreaming(resultCode: Int, data: Intent) {
        source = Source.SCREEN
        val metrics = Resources.getSystem().displayMetrics
        val w = (metrics.widthPixels / 16) * 16
        val h = (metrics.heightPixels / 16) * 16

        val enc = H264Encoder(w, h, fps = 30) { nal, isKey -> server?.broadcastNal(nal, isKey) }
        enc.start()
        encoder = enc

        val srv = HttpStreamServer(PORT, this) { count ->
            mainHandler.post { clientCount = count; refreshNotification(); stateListener?.invoke() }
        }
        srv.start()
        server = srv

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = manager.getMediaProjection(resultCode, data)
        projection = mp

        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { mainHandler.post { stopStreaming() } }
        }, mainHandler)

        virtualDisplay = mp.createVirtualDisplay(
            "PhoneCamScreen", w, h, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            enc.inputSurface, null, mainHandler
        )

        streaming = true
        stateListener?.invoke()
    }

    fun stopStreaming() {
        streaming = false
        clientCount = 0
        teardown()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stateListener?.invoke()
        stopSelf()
    }

    private fun teardown() {
        orientationListener?.disable()
        server?.stop()
        server = null
        encoder?.stop()
        encoder = null
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        releaseLocks()
    }

    override fun getStatusJson(): String {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryLevel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return "{\"streaming\":$streaming,\"source\":\"$source\",\"clients\":$clientCount,\"battery\":$batteryLevel,\"torch\":$isTorchOn,\"zoom\":$zoomRatio,\"res\":\"${resLabels[resIndex]}\"}"
    }

    override fun setTorch(enable: Boolean): Boolean {
        if (camera?.cameraInfo?.hasFlashUnit() == true) {
            isTorchOn = enable
            camera?.cameraControl?.enableTorch(enable)
            mainHandler.post { stateListener?.invoke() }
            return true
        }
        return false
    }

    override fun setZoom(ratio: Float) {
        val (min, max) = zoomRange()
        val target = ratio.coerceIn(min, max)
        camera?.cameraControl?.setZoomRatio(target)
    }

    override fun switchCamera() {
        mainHandler.post {
            useFront = !useFront
            if (streaming && source == Source.CAMERA) bindCamera()
            stateListener?.invoke()
        }
    }

    override fun setResolution(res: String) {
        val idx = resLabels.indexOf(res)
        if (idx >= 0 && idx != resIndex) {
            mainHandler.post {
                resIndex = idx
                if (streaming && source == Source.CAMERA) {
                    encoder?.stop()
                    val sz = resolutions[resIndex]
                    encoder = H264Encoder(sz.width, sz.height, fps = 30) { nal, isKey ->
                        server?.broadcastNal(nal, isKey)
                    }.apply { start() }
                    bindCamera()
                }
                stateListener?.invoke()
            }
        }
    }

    override fun toggleAmoled() {
        mainHandler.post { MainActivity.instance?.toggleAmoled() }
    }

    override fun getCachedSpsPps(): ByteArray? = encoder?.spsPpsHeader
    override fun requestKeyFrame() { encoder?.requestKeyFrame() }

    fun zoomRange(): Pair<Float, Float> {
        val zs = camera?.cameraInfo?.zoomState?.value
        return Pair(zs?.minZoomRatio ?: 1f, zs?.maxZoomRatio ?: 1f)
    }

    fun streamUrls(): List<String> {
        val res = mutableListOf<String>()
        for (nif in NetworkInterface.getNetworkInterfaces()) {
            if (!nif.isUp || nif.isLoopback) continue
            for (addr in nif.inetAddresses) {
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    res.add("http://${addr.hostAddress}:$PORT/video.h264")
                }
            }
        }
        return res
    }

    private fun acquireLocks() {
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PhoneCamWifi").apply { acquire() }
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneCamWake").apply { acquire(10 * 3600 * 1000L) }
        } catch (_: Exception) {}
    }

    private fun releaseLocks() {
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Exception) {}
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
    }

    private fun buildNotification(): Notification {
        val intent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("PhoneCam H.264 Active")
            .setContentText("Connected viewers: $clientCount")
            .setContentIntent(intent)
            .setOngoing(true)
            .build()
    }

    private fun refreshNotification() {
        if (streaming) {
            try { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification()) } catch (_: Exception) {}
        }
    }

    companion object {
        const val PORT = 8080
        const val CHANNEL_ID = "phonecam_h264"
        const val NOTIF_ID = 1
        const val ACTION_START_CAMERA = "START_CAMERA"
        const val ACTION_START_SCREEN = "START_SCREEN"
        const val ACTION_STOP = "STOP"
        const val EXTRA_RESULT_CODE = "RESULT_CODE"
        const val EXTRA_RESULT_DATA = "RESULT_DATA"
    }
}
