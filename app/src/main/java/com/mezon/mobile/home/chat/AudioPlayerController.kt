package com.mezon.mobile.home.chat

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.mezon.mobile.core.NotificationCenter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioPlayerController @Inject constructor(
    private val notificationCenter: NotificationCenter
) {

    data class State(
        val messageId: Long,
        val isPlaying: Boolean,
        val isLoading: Boolean,
        val positionMs: Long,
        val durationMs: Long
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var currentMessageId: Long = 0
    private var currentUrl: String = ""
    private var durationMs: Long = 0
    private var isPrepared = false

    private val tickRunnable = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (!isPrepared) return
            try {
                val pos = p.currentPosition.toLong()
                broadcastState(
                    messageId = currentMessageId,
                    isPlaying = p.isPlaying,
                    isLoading = false,
                    positionMs = pos,
                    durationMs = durationMs
                )
            } catch (_: Exception) {}
            mainHandler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    fun isPlayingMessage(messageId: Long): Boolean {
        val p = player ?: return false
        return currentMessageId == messageId && isPrepared && try { p.isPlaying } catch (_: Exception) { false }
    }

    fun getState(messageId: Long): State? {
        if (currentMessageId != messageId || player == null) return null
        val p = player ?: return null
        return try {
            State(
                messageId = messageId,
                isPlaying = isPrepared && p.isPlaying,
                isLoading = !isPrepared,
                positionMs = if (isPrepared) p.currentPosition.toLong() else 0L,
                durationMs = durationMs
            )
        } catch (_: Exception) {
            null
        }
    }

    fun toggle(messageId: Long, url: String, fallbackDurationSec: Int) {
        if (currentMessageId == messageId && player != null) {
            val p = player!!
            try {
                if (isPrepared && p.isPlaying) {
                    p.pause()
                    mainHandler.removeCallbacks(tickRunnable)
                    broadcastState(messageId, false, false, p.currentPosition.toLong(), durationMs)
                    return
                }
                if (isPrepared) {
                    p.start()
                    mainHandler.post(tickRunnable)
                    broadcastState(messageId, true, false, p.currentPosition.toLong(), durationMs)
                    return
                }
            } catch (e: Exception) {
                Log.e(TAG, "toggle same message failed", e)
            }
        }
        startPlaying(messageId, url, fallbackDurationSec)
    }

    private fun startPlaying(messageId: Long, url: String, fallbackDurationSec: Int) {
        stopInternal()
        if (url.isBlank()) return
        currentMessageId = messageId
        currentUrl = url
        durationMs = (fallbackDurationSec * 1000L).coerceAtLeast(0L)
        isPrepared = false
        broadcastState(messageId, false, true, 0L, durationMs)

        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            mp.setDataSource(url)
            mp.setOnPreparedListener { prepared ->
                if (player !== prepared) return@setOnPreparedListener
                isPrepared = true
                val realDuration = try { prepared.duration.toLong() } catch (_: Exception) { 0L }
                if (realDuration > 0) durationMs = realDuration
                try { prepared.start() } catch (_: Exception) {}
                mainHandler.post(tickRunnable)
                broadcastState(messageId, true, false, 0L, durationMs)
            }
            mp.setOnCompletionListener { completed ->
                if (player !== completed) return@setOnCompletionListener
                mainHandler.removeCallbacks(tickRunnable)
                try { completed.seekTo(0) } catch (_: Exception) {}
                broadcastState(messageId, false, false, 0L, durationMs)
            }
            mp.setOnErrorListener { erred, what, extra ->
                Log.e(TAG, "MediaPlayer error what=$what extra=$extra")
                if (player === erred) {
                    mainHandler.removeCallbacks(tickRunnable)
                    broadcastState(messageId, false, false, 0L, durationMs)
                    stopInternal()
                }
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.e(TAG, "prepare failed for $url", e)
            stopInternal()
            broadcastState(messageId, false, false, 0L, durationMs)
        }
    }

    fun stop() {
        val id = currentMessageId
        stopInternal()
        if (id != 0L) {
            broadcastState(id, false, false, 0L, 0L)
        }
    }

    private fun stopInternal() {
        mainHandler.removeCallbacks(tickRunnable)
        val p = player
        player = null
        isPrepared = false
        durationMs = 0
        currentUrl = ""
        currentMessageId = 0
        if (p != null) {
            try { p.stop() } catch (_: Exception) {}
            try { p.reset() } catch (_: Exception) {}
            try { p.release() } catch (_: Exception) {}
        }
    }

    private fun broadcastState(
        messageId: Long,
        isPlaying: Boolean,
        isLoading: Boolean,
        positionMs: Long,
        durationMs: Long
    ) {
        notificationCenter.postNotificationOnMainThread(
            NotificationCenter.audioPlaybackStateChanged,
            messageId,
            isPlaying,
            isLoading,
            positionMs,
            durationMs
        )
    }

    companion object {
        private const val TAG = "AudioPlayerController"
        private const val TICK_INTERVAL_MS = 200L
    }
}
