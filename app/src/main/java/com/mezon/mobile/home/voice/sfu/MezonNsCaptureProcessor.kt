package com.mezon.mobile.home.voice.sfu

import ai.mezon.ns.MezonNS
import android.content.Context
import android.media.AudioFormat
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.webrtc.audio.JavaAudioDeviceModule

private const val TAG = "MezonNsCapture"

class MezonNsCaptureProcessor(
    private val context: Context,
    private val onFirstProcessedFrame: () -> Unit,
    private val onFailure: () -> Unit,
) : JavaAudioDeviceModule.AudioBufferCallback, AutoCloseable {
    private val lock = Any()
    private var engine: MezonNS? = null
    private var closed = false
    private val firstFrame = AtomicBoolean(false)
    private val failureReported = AtomicBoolean(false)
    private var slowFrameStreak = 0
    private val capturedFrames = AtomicLong(0)
    val capturedFrameCount: Long get() = capturedFrames.get()
    @Volatile private var captureActive = false
    @Volatile private var blocking = false
    @Volatile private var enabled = false

    fun setCaptureActive(active: Boolean) {
        synchronized(lock) {
            if (captureActive != active) {
                captureActive = active
                engine?.reset()
            }
        }
    }

    fun enable(): Boolean {
        val prepared = runCatching {
            val current = synchronized(lock) { engine }
            current ?: MezonNS.createFromCache(context)
        }.onFailure { Log.e(TAG, "model initialization failed", it) }.getOrNull()
        if (prepared == null) {
            blocking = false
            Log.e(TAG, "model initialization returned no engine")
            return false
        }
        synchronized(lock) {
            if (closed) {
                prepared.close()
                return false
            }
            if (engine == null) engine = prepared
            engine?.reset()
            firstFrame.set(false)
            failureReported.set(false)
            slowFrameStreak = 0
            enabled = true
            blocking = false
        }
        return true
    }

    fun disable() {
        synchronized(lock) {
            enabled = false
            engine?.reset()
            blocking = false
        }
    }

    fun cancelChange() {
        blocking = false
    }

    override fun onBuffer(
        buffer: ByteBuffer,
        audioFormat: Int,
        channelCount: Int,
        sampleRate: Int,
        bytesRead: Int,
        captureTimeNs: Long,
    ): Long {
        if (!captureActive) {
            silence(buffer, bytesRead)
            return captureTimeNs
        }
        if (audioFormat == AudioFormat.ENCODING_PCM_16BIT && channelCount > 0 && bytesRead > 0 && bytesRead <= buffer.capacity()) {
            capturedFrames.addAndGet(bytesRead.toLong() / (2 * channelCount))
        }
        if (blocking) {
            silence(buffer, bytesRead)
            return captureTimeNs
        }
        if (!enabled) return captureTimeNs
        val valid = buffer.isDirect && audioFormat == AudioFormat.ENCODING_PCM_16BIT &&
            channelCount == 1 && sampleRate == MezonNS.SAMPLE_RATE &&
            bytesRead > 0 && bytesRead % MezonNS.FRAME_BYTES == 0 && bytesRead <= buffer.capacity()
        var changed = false
        var inferenceUs = 0L
        val processed = synchronized(lock) {
            if (blocking || !enabled || !captureActive) {
                changed = true
                false
            } else {
                val started = System.nanoTime()
                val success = valid && engine?.process(buffer, bytesRead) == true
                inferenceUs = (System.nanoTime() - started) / 1_000
                if (success) {
                    val frameCount = bytesRead / MezonNS.FRAME_BYTES
                    slowFrameStreak = if (inferenceUs > frameCount * 8_000L) slowFrameStreak + 1 else 0
                }
                success && slowFrameStreak < 3
            }
        }
        if (changed) {
            return captureTimeNs
        }
        if (!processed) {
            silence(buffer, bytesRead)
            enabled = false
            blocking = false
            if (failureReported.compareAndSet(false, true)) {
                Log.e(TAG, "capture processing failed: format=$audioFormat channels=$channelCount rate=$sampleRate bytes=$bytesRead inferenceUs=$inferenceUs")
                onFailure()
            }
        } else if (firstFrame.compareAndSet(false, true)) {
            onFirstProcessedFrame()
        }
        return captureTimeNs
    }

    private fun silence(buffer: ByteBuffer, bytesRead: Int) {
        for (index in 0 until bytesRead.coerceIn(0, buffer.capacity())) buffer.put(index, 0)
    }

    override fun close() {
        blocking = true
        synchronized(lock) {
            closed = true
            enabled = false
            engine?.close()
            engine = null
        }
    }
}
