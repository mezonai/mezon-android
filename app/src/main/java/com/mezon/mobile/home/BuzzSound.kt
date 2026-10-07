package com.mezon.mobile.home

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import com.mezon.mobile.R

internal class BuzzSound(context: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build())
        .build()
    private val soundId: Int
    private var loaded = false
    private var playWhenLoaded = false
    private var released = false

    init {
        pool.setOnLoadCompleteListener { _, _, status ->
            if (released) return@setOnLoadCompleteListener
            loaded = status == 0
            if (!loaded) Log.e("BuzzSound", "Failed to load Buzz sample: $status")
            if (loaded && playWhenLoaded) {
                playWhenLoaded = false
                play()
            }
        }
        soundId = pool.load(context.applicationContext, R.raw.buzz, 1)
    }

    fun play() {
        if (released) return
        if (loaded) {
            pool.play(soundId, 1f, 1f, 1, 0, 1f)
        } else playWhenLoaded = true
    }

    fun release() {
        if (released) return
        released = true
        playWhenLoaded = false
        pool.release()
    }
}
