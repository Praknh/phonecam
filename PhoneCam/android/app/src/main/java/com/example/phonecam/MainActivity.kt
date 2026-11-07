package com.example.phonecam

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The screen you see. Streaming itself runs in [CameraStreamService], so it keeps going
 * when this app is minimised; this activity binds to the service for the preview and controls.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var previewView: ImageView
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var sourceButton: Button
    private lateinit var rotateButton: Button
    private lateinit var mirrorButton: Button
    private lateinit var switchButton: Button
    private lateinit var resButton: Button
    private lateinit var zoomLabel: TextView
    private lateinit var zoomBar: SeekBar
    private lateinit var cameraControls: View
    private lateinit var scaleDetector: ScaleGestureDetector

    private var service: CameraStreamService? = null
    private var bound = false
    private var selectedScreen = false
    private var updatingZoomBar = false

    private val previewExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val previewBusy = AtomicBoolean(false)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val svc = (binder as CameraStreamService.LocalBinder).service
            service = svc
            svc.stateListener = { refreshUi() }
            svc.previewListener = { jpeg -> showPreview(jpeg) }
            refreshUi()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results[Manifest.permission.CAMERA] == false) {
                Toast.makeText(this, "Camera permission is needed to stream the camera", Toast.LENGTH_LONG).show()
            }
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                val intent = Intent(this, CameraStreamService::class.java)
                    .setAction(CameraStreamService.ACTION_START_SCREEN)
                    .putExtra(CameraStreamService.EXTRA_RESULT_CODE, result.resultCode)
                    .putExtra(CameraStreamService.EXTRA_RESULT_DATA, data)
                ContextCompat.startForegroundService(this, intent)
            } else {
                Toast.makeText(this, "Screen sharing was not allowed", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        statusText = findViewById(R.id.statusText)
        startButton = findViewById(R.id.startButton)
        sourceButton = findViewById(R.id.sourceButton)
        rotateButton = findViewById(R.id.rotateButton)
        mirrorButton = findViewById(R.id.mirrorButton)
        switchButton = findViewById(R.id.switchButton)
        resButton = findViewById(R.id.resButton)
        zoomLabel = findViewById(R.id.zoomLabel)
        zoomBar = findViewById(R.id.zoomBar)
        cameraControls = findViewById(R.id.cameraControls)

        startButton.setOnClickListener {
            val svc = service
            if (svc != null && svc.streaming) svc.requestStop() else beginStream()
        }
        sourceButton.setOnClickListener {
            if (service?.streaming == true) {
                Toast.makeText(this, "Stop streaming first to change the source", Toast.LENGTH_SHORT).show()
            } else {
                selectedScreen = !selectedScreen
                refreshUi()
            }
        }
        rotateButton.setOnClickListener { service?.rotateVideo() }
        mirrorButton.setOnClickListener { service?.toggleMirror() }
        switchButton.setOnClickListener { service?.switchCamera() }
        resButton.setOnClickListener { service?.cycleResolution() }

        zoomBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && !updatingZoomBar) {
                    service?.setZoomFraction(progress / 100f)
                    updateZoomLabel()
                }
            }

            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })

        // Pinch the preview to zoom.
        scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    service?.zoomBy(detector.scaleFactor)
                    syncZoomUi()
                    return true
                }
            },
        )
        previewView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            true
        }

        requestStartupPermissions()
        refreshUi()
    }

    override fun onStart() {
        super.onStart()
        bound = bindService(
            Intent(this, CameraStreamService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
    }

    override fun onStop() {
        service?.stateListener = null
        service?.previewListener = null
        service = null
        if (bound) {
            unbindService(connection)
            bound = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        previewExecutor.shutdownNow()
        super.onDestroy()
    }

    // --------------------------------------------------------------- actions

    private fun requestStartupPermissions() {
        val wanted = mutableListOf<String>()
        if (!hasCameraPermission()) wanted.add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (wanted.isNotEmpty()) permissionLauncher.launch(wanted.toTypedArray())
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun beginStream() {
        if (selectedScreen) {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(manager.createScreenCaptureIntent())
            return
        }
        if (!hasCameraPermission()) {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
            return
        }
        @Suppress("DEPRECATION")
        val rotation = windowManager.defaultDisplay.rotation
        val intent = Intent(this, CameraStreamService::class.java)
            .setAction(CameraStreamService.ACTION_START_CAMERA)
            .putExtra(CameraStreamService.EXTRA_ROTATION, rotation)
        ContextCompat.startForegroundService(this, intent)
    }

    /** Decodes a streamed frame for the on-screen preview. Called on a background thread. */
    private fun showPreview(jpeg: ByteArray) {
        if (!previewBusy.compareAndSet(false, true)) return // still busy with the last one
        try {
            previewExecutor.execute {
                try {
                    val options = BitmapFactory.Options().apply { inSampleSize = 2 }
                    val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
                    if (bitmap != null) runOnUiThread { previewView.setImageBitmap(bitmap) }
                } finally {
                    previewBusy.set(false)
                }
            }
        } catch (e: Exception) {
            previewBusy.set(false) // executor already shut down
        }
    }

    // -------------------------------------------------------------------- UI

    private fun refreshUi() {
        val svc = service
        val streaming = svc?.streaming == true
        val screenMode = if (svc != null && streaming) {
            svc.source == CameraStreamService.Source.SCREEN
        } else {
            selectedScreen
        }

        startButton.text = if (streaming) "Stop" else "Start"
        sourceButton.text = if (screenMode) "Source: Screen" else "Source: Camera"
        rotateButton.text = "Rotate: ${svc?.extraRotation ?: 0}°"
        mirrorButton.text = if (svc?.mirror == true) "Mirror: on" else "Mirror: off"
        switchButton.text = if (svc?.useFront == true) "Front cam" else "Back cam"
        resButton.text = svc?.resolutionLabel() ?: "720p"
        cameraControls.visibility = if (screenMode) View.GONE else View.VISIBLE
        syncZoomUi()

        if (!streaming) previewView.setImageDrawable(null)

        statusText.text = when {
            !streaming -> {
                val error = svc?.lastError
                if (error != null) "$error\nTap Start to try again." else "Not streaming. Tap Start."
            }
            else -> {
                val what = if (screenMode) "screen" else "camera"
                val urls = svc?.streamUrls().orEmpty()
                val wifi = if (urls.isEmpty()) "No Wi-Fi address found" else urls.joinToString("  |  ")
                "Streaming your $what  (viewers: ${svc?.clientCount ?: 0}). " +
                    "Keeps running if you minimise the app.\n" +
                    "Wi-Fi: $wifi\nUSB: adb forward tcp:${CameraStreamService.PORT} tcp:${CameraStreamService.PORT}"
            }
        }
    }

    private fun syncZoomUi() {
        val svc = service ?: return
        updatingZoomBar = true
        zoomBar.progress = (svc.zoomFraction() * 100).toInt()
        updatingZoomBar = false
        updateZoomLabel()
    }

    private fun updateZoomLabel() {
        val ratio = service?.zoomRatio ?: 1f
        zoomLabel.text = String.format(Locale.US, "Zoom %.1fx", ratio)
    }
}
