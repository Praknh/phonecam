package com.example.phonecam

import android.util.Log
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class HttpStreamServer(
    private val port: Int,
    private val controller: ControlInterface,
    private val onClientsChanged: (Int) -> Unit
) {
    interface ControlInterface {
        fun getStatusJson(): String
        fun setTorch(enable: Boolean): Boolean
        fun setZoom(ratio: Float)
        fun switchCamera()
        fun setResolution(res: String)
        fun toggleAmoled()
        fun getCachedSpsPps(): ByteArray?
        fun requestKeyFrame()
    }

    private val pool = Executors.newCachedThreadPool()
    private var serverSocket: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<OutputStream>()
    private val isRunning = AtomicBoolean(false)

    fun hasClients(): Boolean = clients.isNotEmpty()

    fun start() {
        if (isRunning.getAndSet(true)) return
        pool.execute {
            try {
                val ss = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                }
                serverSocket = ss
                while (isRunning.get()) {
                    val socket = ss.accept()
                    pool.execute { handleConnection(socket) }
                }
            } catch (e: Exception) {
                if (isRunning.get()) Log.e(TAG, "Server error: ${e.message}")
            }
        }
    }

    fun broadcastNal(nal: ByteArray, isKey: Boolean) {
        if (clients.isEmpty()) return
        for (out in clients) {
            try {
                out.write(nal)
                out.flush()
            } catch (e: IOException) {
                clients.remove(out)
                onClientsChanged(clients.size)
            }
        }
    }

    private fun handleConnection(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.sendBufferSize = 128 * 1024
            val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val requestLine = reader.readLine() ?: return
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }

            val parts = requestLine.split(" ")
            val rawPath = parts.getOrNull(1) ?: "/"
            val path = rawPath.substringBefore('?')
            val query = rawPath.substringAfter('?', "")
            val out = BufferedOutputStream(socket.getOutputStream())

            when {
                path == "/video.h264" || path == "/video" -> streamH264(out)
                path == "/status" -> sendJson(out, controller.getStatusJson())
                path == "/control" -> handleControl(out, query)
                else -> sendDashboard(out)
            }
        } catch (_: Exception) {}
    }

    private fun handleControl(out: OutputStream, query: String) {
        val params = query.split("&").associate {
            val kv = it.split("=")
            kv[0] to (kv.getOrNull(1) ?: "")
        }

        params["torch"]?.let { controller.setTorch(it == "1" || it == "true" || it == "toggle") }
        params["zoom"]?.toFloatOrNull()?.let { controller.setZoom(it) }
        params["camera"]?.let { if (it == "switch") controller.switchCamera() }
        params["res"]?.let { controller.setResolution(it) }
        params["amoled"]?.let { controller.toggleAmoled() }

        sendJson(out, "{\"status\":\"ok\"}")
    }

    private fun streamH264(out: OutputStream) {
        val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: video/h264\r\n" +
                "Connection: close\r\n" +
                "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                "Pragma: no-cache\r\n\r\n"
        out.write(header.toByteArray(Charsets.ISO_8859_1))
        out.flush()

        controller.getCachedSpsPps()?.let {
            out.write(it)
            out.flush()
        }
        controller.requestKeyFrame()

        clients.add(out)
        onClientsChanged(clients.size)

        try {
            while (isRunning.get() && clients.contains(out)) {
                Thread.sleep(500)
            }
        } finally {
            clients.remove(out)
            onClientsChanged(clients.size)
        }
    }

    private fun sendJson(out: OutputStream, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    private fun sendDashboard(out: OutputStream) {
        val body = "<!doctype html><html><head><meta charset='utf-8'><title>PhoneCam Active</title></head>" +
                "<body style='background:#121212;color:#fff;font-family:sans-serif;text-align:center;padding:40px;'>" +
                "<h2>PhoneCam H.264 Stream is Active</h2>" +
                "<p>Stream URL: <code>/video.h264</code></p></body></html>"
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        clients.clear()
        pool.shutdownNow()
    }

    companion object {
        private const val TAG = "HttpStreamServer"
    }
}
