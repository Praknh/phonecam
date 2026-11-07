package com.example.phonecam

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: ImageView
    private lateinit var statusText: TextView
    private lateinit var badgeText: TextView
    private lateinit var startButton: Button
    private lateinit var sourceButton: Button
    private lateinit var switchButton: Button
    private lateinit var resButton: Button
    private lateinit var torchButton: Button
    private lateinit var zoomLabel: TextView
    private lateinit var zoomBar: SeekBar
    private lateinit var stealthButton: Button
    private lateinit var stealthOverlay: FrameLayout
    private lateinit var stealthStats: TextView
    private lateinit var mainUiContainer: LinearLayout

    private var service: CameraStreamService? = null
    private var bound = false
    private var isScreenSelected = false
    private var isStealth = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val svc = (binder as CameraStreamService.LocalBinder).service
            service = svc
            svc.stateListener = { refreshUi() }
            svc.previewBitmapListener = { bmp -> onPreviewFrame(bmp) }
            refreshUi()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshUi()
    }

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data
        if (res.resultCode == RESULT_OK && data != null) {
            val intent = Intent(this, CameraStreamService::class.java).apply {
                action = CameraStreamService.ACTION_START_SCREEN
                putExtra(CameraStreamService.EXTRA_RESULT_CODE, res.resultCode)
                putExtra(CameraStreamService.EXTRA_RESULT_DATA, data)
            }
            ContextCompat.startForegroundService(this, intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        statusText = findViewById(R.id.statusText)
        badgeText = findViewById(R.id.badgeText)
        startButton = findViewById(R.id.startButton)
        sourceButton = findViewById(R.id.sourceButton)
        switchButton = findViewById(R.id.switchButton)
        resButton = findViewById(R.id.resButton)
        torchButton = findViewById(R.id.torchButton)
        zoomLabel = findViewById(R.id.zoomLabel)
        zoomBar = findViewById(R.id.zoomBar)
        stealthButton = findViewById(R.id.stealthButton)
        stealthOverlay = findViewById(R.id.stealthOverlay)
        stealthStats = findViewById(R.id.stealthStats)
        mainUiContainer = findViewById(R.id.mainUiContainer)

        setupListeners()
        requestAppPermissions()
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, CameraStreamService::class.java), connection, Context.BIND_AUTO_CREATE)
        bound = true
    }

    override fun onStop() {
        service?.stateListener = null
        service?.previewBitmapListener = null
        if (bound) {
            unbindService(connection)
            bound = false
        }
        super.onStop()
    }

    private fun setupListeners() {
        startButton.setOnClickListener {
            val svc = service
            if (svc?.streaming == true) svc.stopStreaming() else startStream()
        }
        sourceButton.setOnClickListener {
            if (service?.streaming != true) {
                isScreenSelected = !isScreenSelected
                refreshUi()
            }
        }
        switchButton.setOnClickListener { service?.switchCamera() }
        resButton.setOnClickListener {
            val nextRes = if (resButton.text == "1080p") "720p" else "1080p"
            service?.setResolution(nextRes)
        }
        torchButton.setOnClickListener {
            val cur = service?.isTorchOn ?: false
            service?.setTorch(!cur)
        }
        stealthButton.setOnClickListener { toggleAmoled() }

        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleAmoled()
                return true
            }
        })
        stealthOverlay.setOnTouchListener { _, ev -> detector.onTouchEvent(ev); true }

        zoomBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(b: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    val (min, max) = service?.zoomRange() ?: Pair(1f, 1f)
                    service?.setZoom(min + (max - min) * (p / 100f))
                }
            }
            override fun onStartTrackingTouch(b: SeekBar?) {}
            override fun onStopTrackingTouch(b: SeekBar?) {}
        })
    }

    fun toggleAmoled() {
        isStealth = !isStealth
        stealthOverlay.visibility = if (isStealth) View.VISIBLE else View.GONE
        mainUiContainer.visibility = if (isStealth) View.GONE else View.VISIBLE
        val lp = window.attributes
        lp.screenBrightness = if (isStealth) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
    }

    private fun startStream() {
        if (isScreenSelected) {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(mgr.createScreenCaptureIntent())
        } else {
            val intent = Intent(this, CameraStreamService::class.java).apply {
                action = CameraStreamService.ACTION_START_CAMERA
            }
            ContextCompat.startForegroundService(this, intent)
        }
    }

    private fun onPreviewFrame(bmp: Bitmap) {
        if (!isStealth) {
            runOnUiThread { previewView.setImageBitmap(bmp) }
        }
    }

    private fun refreshUi() {
        val svc = service
        val streaming = svc?.streaming == true
        startButton.text = if (streaming) "Stop Streaming" else "Start Streaming"
        badgeText.text = if (streaming) "LIVE (H.264)" else "READY"
        badgeText.setTextColor(ContextCompat.getColor(this, if (streaming) R.color.accent_red else R.color.accent_green))

        val urls = svc?.streamUrls().orEmpty()
        statusText.text = if (streaming) {
            "Streaming via hardware H.264 (Viewers: ${svc?.clientCount ?: 0})\n" +
                    "URL: ${urls.firstOrNull() ?: "http://localhost:8080/video.h264"}"
        } else "Not streaming. Press Start Streaming."

        sourceButton.text = if (isScreenSelected) "Screen" else "Camera"
        switchButton.text = if (svc?.useFront == true) "Front Cam" else "Back Cam"
        torchButton.text = if (svc?.isTorchOn == true) "Torch: On" else "Torch: Off"
        zoomLabel.text = String.format(Locale.US, "Zoom %.1fx", svc?.zoomRatio ?: 1f)
    }

    private fun requestAppPermissions() {
        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        permissionLauncher.launch(perms.toTypedArray())
    }

    companion object {
        var instance: MainActivity? = null
    }
}
