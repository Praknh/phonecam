package com.example.phonecam

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class H264Encoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int = 30,
    private val bitrate: Int = 3_500_000,
    private val onOutputNal: (ByteArray, Boolean) -> Unit
) {
    private var codec: MediaCodec? = null
    var inputSurface: Surface? = null
        private set

    private val isRunning = AtomicBoolean(false)
    private var workerThread: Thread? = null

    var spsPpsHeader: ByteArray? = null
        private set

    fun start() {
        if (isRunning.getAndSet(true)) return

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setInteger(MediaFormat.KEY_LATENCY, 0)
            }
        }

        val mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = mediaCodec.createInputSurface()
        mediaCodec.start()
        codec = mediaCodec

        workerThread = Thread({ drainEncoder() }, "H264DrainThread").apply { start() }
    }

    fun requestKeyFrame() {
        try {
            val params = Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }
            codec?.setParameters(params)
        } catch (e: Exception) {
            Log.w(TAG, "Sync frame request failed: ${e.message}")
        }
    }

    private fun drainEncoder() {
        val encoder = codec ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        while (isRunning.get()) {
            val index = try {
                encoder.dequeueOutputBuffer(bufferInfo, 10_000)
            } catch (e: Exception) {
                break
            }

            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val newFormat = encoder.outputFormat
                val sps = newFormat.getByteBuffer("csd-0")
                val pps = newFormat.getByteBuffer("csd-1")
                if (sps != null && pps != null) {
                    val baos = ByteArrayOutputStream()
                    baos.write(sps.toByteArray())
                    baos.write(pps.toByteArray())
                    spsPpsHeader = baos.toByteArray()
                }
            } else if (index >= 0) {
                val outputBuffer: ByteBuffer? = encoder.getOutputBuffer(index)
                if (outputBuffer != null && bufferInfo.size > 0) {
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                    val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                    val bytes = ByteArray(bufferInfo.size)
                    outputBuffer.get(bytes)

                    if (isConfig) {
                        spsPpsHeader = bytes
                    } else {
                        onOutputNal(bytes, isKeyFrame)
                    }
                }
                encoder.releaseOutputBuffer(index, false)
            }
        }
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        try {
            workerThread?.interrupt()
            workerThread?.join(500)
        } catch (_: Exception) {}
        workerThread = null

        try {
            codec?.stop()
            codec?.release()
        } catch (_: Exception) {}
        codec = null

        inputSurface?.release()
        inputSurface = null
    }

    private fun ByteBuffer.toByteArray(): ByteArray {
        val bytes = ByteArray(remaining())
        get(bytes)
        rewind()
        return bytes
    }

    companion object {
        private const val TAG = "H264Encoder"
    }
}
