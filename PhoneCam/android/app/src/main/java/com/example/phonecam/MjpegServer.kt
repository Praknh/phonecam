package com.example.phonecam

import android.util.Log
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Tiny dependency-free HTTP server that serves the latest camera frame as an
 * MJPEG stream (multipart/x-mixed-replace).
 *
 *   /        -> small HTML page with the live view (handy for testing in a browser)
 *   /video   -> the MJPEG stream
 *
 * Each connected client always receives the newest frame, so a slow client
 * skips frames instead of building up lag.
 */
class MjpegServer(
    private val port: Int,
    private val onClientsChanged: (Int) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val pool = Executors.newCachedThreadPool()
    private var serverSocket: ServerSocket? = null

    private val lock = ReentrantLock()
    private val newFrame = lock.newCondition()
    private var latest: ByteArray? = null
    private var frameId = 0L

    private val clients = AtomicInteger(0)

    @Volatile
    private var running = false

    fun hasClients(): Boolean = clients.get() > 0

    fun start() {
        running = true
        pool.execute {
            try {
                val ss = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                }
                serverSocket = ss
                while (running) {
                    val socket = ss.accept()
                    pool.execute { handle(socket) }
                }
            } catch (e: Exception) {
                if (running) {
                    Log.e(TAG, "Server error", e)
                    onError(e.message ?: "server error")
                }
            }
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        lock.withLock { newFrame.signalAll() }
        pool.shutdownNow()
    }

    /** Called by the camera thread with each new JPEG frame. */
    fun publish(jpeg: ByteArray) {
        lock.withLock {
            latest = jpeg
            frameId++
            newFrame.signalAll()
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 10_000
            val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val requestLine = reader.readLine() ?: return
            // Drain the request headers.
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            socket.soTimeout = 0

            val path = requestLine.split(" ").getOrNull(1)?.substringBefore('?') ?: "/"
            val out = BufferedOutputStream(socket.getOutputStream())
            when (path) {
                "/video" -> streamVideo(out)
                else -> sendPage(out)
            }
        } catch (_: Exception) {
            // Client went away, or the server is stopping (interrupt); nothing to do.
            // Must never escape: an uncaught exception on any thread kills an Android app.
        } finally {
            try {
                socket.close()
            } catch (_: IOException) {
            }
        }
    }

    private fun sendPage(out: OutputStream) {
        val body = "<!doctype html><html><head><meta charset=\"utf-8\"><title>PhoneCam</title>" +
            "<style>body{margin:0;background:#000}img{width:100%;height:100vh;object-fit:contain}</style>" +
            "</head><body><img src=\"/video\"></body></html>"
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    private fun streamVideo(out: OutputStream) {
        onClientsChanged(clients.incrementAndGet())
        try {
            val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n" +
                "Cache-Control: no-cache, no-store\r\n" +
                "Pragma: no-cache\r\n" +
                "Connection: close\r\n\r\n"
            out.write(header.toByteArray(Charsets.ISO_8859_1))
            out.flush()

            var lastId = -1L
            while (running) {
                var frame: ByteArray? = null
                lock.withLock {
                    while (running && frameId == lastId) {
                        newFrame.await(1, TimeUnit.SECONDS)
                    }
                    frame = latest
                    lastId = frameId
                }
                val jpeg = frame ?: continue
                val partHeader = "--frame\r\n" +
                    "Content-Type: image/jpeg\r\n" +
                    "Content-Length: ${jpeg.size}\r\n\r\n"
                out.write(partHeader.toByteArray(Charsets.ISO_8859_1))
                out.write(jpeg)
                out.write("\r\n".toByteArray(Charsets.ISO_8859_1))
                out.flush()
            }
        } finally {
            onClientsChanged(clients.decrementAndGet())
        }
    }

    companion object {
        private const val TAG = "MjpegServer"
    }
}
