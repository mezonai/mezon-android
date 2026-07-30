package com.mezon.mobile.home.stream

import android.content.Context
import android.media.AudioManager
import com.twilio.audioswitch.AudioDevice
import io.livekit.android.audio.AudioSwitchHandler

class StreamingAudioManager(context: Context) {

    private val audioSwitchHandler = AudioSwitchHandler(context.applicationContext).apply {
        preferredDeviceList = PREFERRED_DEVICE_LIST
        forceHandleAudioRouting = true
        focusMode = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
    }

    private var started = false

    fun start() {
        if (started) return
        started = true
        audioSwitchHandler.start()
    }

    fun stop() {
        if (!started) return
        started = false
        audioSwitchHandler.stop()
    }

    companion object {
        private val PREFERRED_DEVICE_LIST = listOf(
            AudioDevice.BluetoothHeadset::class.java,
            AudioDevice.WiredHeadset::class.java,
            AudioDevice.Speakerphone::class.java,
            AudioDevice.Earpiece::class.java,
        )
    }
}
