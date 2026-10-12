package com.mezon.mobile.home.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import com.mezon.mobile.R

class VoiceJoinSound(context: Context) {

    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(1)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val soundId: Int
    private var loaded = false
    private var playWhenLoaded = false
    private var released = false
    private var lastPlayAtMs: Long? = null

    init {
        soundPool.setOnLoadCompleteListener { _, _, status ->
            if (released) return@setOnLoadCompleteListener
            loaded = status == 0
            if (loaded && playWhenLoaded) {
                playWhenLoaded = false
                start()
            }
        }
        soundId = soundPool.load(context.applicationContext, R.raw.joincallsound, 1)
    }

    fun play() {
        if (released) return
        val now = SystemClock.elapsedRealtime()
        if (lastPlayAtMs?.let { now - it < MIN_INTERVAL_MS } == true) return
        if (loaded) start() else playWhenLoaded = true
    }

    fun release() {
        if (released) return
        released = true
        playWhenLoaded = false
        soundPool.release()
    }

    private fun start() {
        if (soundPool.play(soundId, 1f, 1f, 1, 0, 1f) != 0) {
            lastPlayAtMs = SystemClock.elapsedRealtime()
        }
    }

    companion object {
        private const val MIN_INTERVAL_MS = 5_000L
    }
}
