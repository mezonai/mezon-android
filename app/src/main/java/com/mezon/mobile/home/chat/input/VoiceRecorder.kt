package com.mezon.mobile.home.chat.input

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

class VoiceRecorder(private val context: Context) {

    data class Result(val file: File, val durationMs: Long)

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startTimeMs: Long = 0

    val isRecording: Boolean get() = recorder != null

    fun elapsedMs(): Long =
        if (startTimeMs == 0L) 0L else System.currentTimeMillis() - startTimeMs

    @Suppress("DEPRECATION")
    fun start(): Boolean {
        if (recorder != null) return true
        val dir = File(context.cacheDir, DIR_NAME).apply { mkdirs() }
        val file = File(dir, "voice_${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(44100)
            r.setAudioEncodingBitRate(96_000)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            outputFile = file
            startTimeMs = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            safeRelease(r)
            try { file.delete() } catch (_: Exception) {}
            outputFile = null
            startTimeMs = 0
            false
        }
    }

    fun stop(): Result? {
        val r = recorder ?: return null
        val file = outputFile
        val elapsed = elapsedMs()
        recorder = null
        outputFile = null
        startTimeMs = 0
        return try {
            try { r.stop() } catch (_: Exception) {}
            try { r.release() } catch (_: Exception) {}
            if (file != null && file.exists() && file.length() > 0) {
                Result(file, elapsed)
            } else {
                try { file?.delete() } catch (_: Exception) {}
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "stop failed", e)
            safeRelease(r)
            try { file?.delete() } catch (_: Exception) {}
            null
        }
    }

    fun cancel() {
        val r = recorder
        val file = outputFile
        recorder = null
        outputFile = null
        startTimeMs = 0
        safeRelease(r)
        try { file?.delete() } catch (_: Exception) {}
    }

    private fun safeRelease(r: MediaRecorder?) {
        if (r == null) return
        try { r.stop() } catch (_: Exception) {}
        try { r.release() } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "VoiceRecorder"
        private const val DIR_NAME = "voice_messages"
        const val MIN_RECORD_MS = 500L
        const val MIME_TYPE = "audio/mp4"
    }
}
