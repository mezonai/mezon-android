package com.mezon.mobile.home.stream

import android.content.Context
import com.mezon.mobile.home.call.MezonAudioSwitch
import com.mezon.mobile.ui.cells.MezonIcon
import com.twilio.audioswitch.AudioDevice
import com.twilio.audioswitch.AudioDeviceChangeListener

/** Session-owned routing: survives fullscreen/mini transitions and transport reconnects. */
class StreamingAudioManager(context: Context) {
    private val audioSwitch = MezonAudioSwitch(context.applicationContext).apply {
        preferredDeviceList = listOf(
            AudioDevice.Speakerphone::class.java,
            AudioDevice.BluetoothHeadset::class.java,
            AudioDevice.WiredHeadset::class.java,
            AudioDevice.Earpiece::class.java,
        )
    }
    private var started = false
    var onChanged: (() -> Unit)? = null
    val isSpeaker: Boolean get() = audioSwitch.selectedAudioDevice is AudioDevice.Speakerphone
    val deviceName: String get() = audioSwitch.selectedAudioDevice?.name.orEmpty()
    private val listener: AudioDeviceChangeListener = { _, _ -> onChanged?.invoke() }

    fun start() {
        if (started) return
        started = true
        audioSwitch.registerAudioDeviceChangeListener(listener)
        audioSwitch.start()
        audioSwitch.availableAudioDevices.firstOrNull { it is AudioDevice.Speakerphone }?.let { audioSwitch.selectDevice(it) }
    }

    // Match the SFU voice room's VoiceAudioManager.currentOutputIcon mapping.
    fun currentOutputIcon(): MezonIcon = when (audioSwitch.selectedAudioDevice) {
        is AudioDevice.Speakerphone -> MezonIcon.voiceWaveDoubleIcon
        is AudioDevice.BluetoothHeadset -> MezonIcon.bluetoothIcon
        else -> MezonIcon.voiceWaveIcon
    }

    fun toggleSpeaker() {
        val next = if (isSpeaker) audioSwitch.availableAudioDevices.firstOrNull { it is AudioDevice.Earpiece }
            ?: audioSwitch.availableAudioDevices.firstOrNull { it !is AudioDevice.Speakerphone }
        else audioSwitch.availableAudioDevices.firstOrNull { it is AudioDevice.Speakerphone }
        next?.let { audioSwitch.selectDevice(it) }
        onChanged?.invoke()
    }

    fun stop() {
        if (!started) return
        started = false
        audioSwitch.unregisterAudioDeviceChangeListener(listener)
        audioSwitch.stop()
    }
}
