package com.example.phonecam

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private data class Res(val label: String, val size: Size)

    private val resolutions = listOf(
        Res("540p", Size(960, 540)),
        Res("720p", Size(1280, 720)),
        Res("1080p", Size(1920, 1080)),
    )

    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var switchButton: Button
    private lateinit var resButton: Button
    private lateinit var mirrorButton: Button

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var server: MjpegServer? = null

    private var useFront = false
    private var resIndex = 1

    @Volatile
    private var mirror = false

    @Volatile
    private var streaming = false

    private var clientCount = 0

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                statusText.text = "Camera permission is required."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        statusText = findViewById(R.id.statusText)
        startButton = findViewById(R.id.startButton)
        switchButton = findViewById(R.id.switchButton)
        resButton = findViewById(R.id.resButton)
        mirrorButton = findViewById(R.id.mirrorButton)

        startButton.setOnClickListener { if (streaming) stopStreaming() else startStreaming() }
        switchButton.setOnClickListener {
            useFront = !useFront
            switchButton.text = if (useFront) "Front cam" else "Back cam"
            bindCamera()
        }
        resButton.setOnClickListener {
            resIndex = (resIndex + 1) % resolutions.size
            resButton.text = resolutions[resIndex].label
            bindCamera()
        }
        mirrorButton.setOnClickListener {
            mirror = !mirror
            mirrorButton.text = if (mirror) "Mirror: on" else "Mirror: off"
        }

        resButton.text = resolutions[resIndex].label
        updateStatus()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stop()
        server = null
        analysisExecutor.shutdown()
    }

    // ---------------------------------------------------------------- camera

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        val selector = if (useFront) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        if (!provider.hasCamera(selector)) {
            Toast.makeText(this, "That camera is not available", Toast.LENGTH_SHORT).show()
            return
        }

        val analysisSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(
                    resolutions[resIndex].size,
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                )
            )
            .build()

        val previewSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .build()

        val preview = Preview.Builder()
            .setResolutionSelector(previewSelector)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(analysisSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { it.setAnalyzer(analysisExecutor) { image -> handleFrame(image) } }

        try {
            provider.unbindAll()
            provider.bindToLifecycle(this, selector, preview, analysis)
        } catch (e: Exception) {
            Log.e(TAG, "Camera bind failed", e)
            Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** Runs on the analysis thread for every camera frame. */
    private fun handleFrame(image: ImageProxy) {
        try {
            val srv = server
            // Skip all the work when nobody is watching: saves battery and heat.
            if (srv == null || !srv.hasClients()) return

            val source = image.toBitmap()
            val matrix = Matrix()
            val rotation = image.imageInfo.rotationDegrees
            if (rotation != 0) matrix.postRotate(rotation.toFloat())
            if (mirror) matrix.postScale(-1f, 1f)

            val oriented = if (matrix.isIdentity) {
                source
            } else {
                Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
            }

            val buffer = ByteArrayOutputStream(64 * 1024)
            oriented.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, buffer)
            srv.publish(buffer.toByteArray())

            if (oriented !== source) oriented.recycle()
            source.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Frame error", e)
        } finally {
            image.close()
        }
    }

    // ------------------------------------------------------------- streaming

    private fun startStreaming() {
        val srv = MjpegServer(
            PORT,
            onClientsChanged = { count ->
                runOnUiThread {
                    clientCount = count
                    updateStatus()
                }
            },
            onError = { message ->
                runOnUiThread {
                    stopStreaming()
                    statusText.text = "Server error: $message"
                }
            },
        )
        srv.start()
        server = srv
        streaming = true
        clientCount = 0
        startButton.text = "Stop"
        updateStatus()
    }

    private fun stopStreaming() {
        server?.stop()
        server = null
        streaming = false
        clientCount = 0
        startButton.text = "Start"
        updateStatus()
    }

    private fun updateStatus() {
        if (!streaming) {
            statusText.text = "Not streaming. Tap Start."
            return
        }
        val ips = localIpv4Addresses()
        val wifi = if (ips.isEmpty()) {
            "No Wi-Fi address found"
        } else {
            ips.joinToString("  |  ") { "http://$it:$PORT/video" }
        }
        statusText.text = "Streaming  (viewers: $clientCount)\nWi-Fi: $wifi\n" +
            "USB: run  adb forward tcp:$PORT tcp:$PORT"
    }

    private fun localIpv4Addresses(): List<String> {
        val result = mutableListOf<String>()
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        addr.hostAddress?.let { result.add(it) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not list addresses", e)
        }
        return result
    }

    companion object {
        private const val TAG = "PhoneCam"
        private const val PORT = 8080
        private const val JPEG_QUALITY = 80
    }
}
