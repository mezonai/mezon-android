package com.mezon.mobile.home.call

import android.content.Context
import android.media.AudioManager
import com.twilio.audioswitch.AudioDevice
import com.twilio.audioswitch.AudioDeviceChangeListener
import com.twilio.audioswitch.AudioSwitch

class MezonAudioSwitch(context: Context) {

    private val appContext = context.applicationContext

    var preferredDeviceList: List<Class<out AudioDevice>> = DEFAULT_PREFERRED_DEVICE_LIST
    var focusMode: Int = AudioManager.AUDIOFOCUS_GAIN
    var forceHandleAudioRouting: Boolean = true

    private var audioSwitch: AudioSwitch? = null
    private var started = false
    private val listeners = LinkedHashSet<AudioDeviceChangeListener>()

    val availableAudioDevices: List<AudioDevice>
        get() = audioSwitch?.availableAudioDevices ?: emptyList()

    val selectedAudioDevice: AudioDevice?
        get() = audioSwitch?.selectedAudioDevice

    fun registerAudioDeviceChangeListener(listener: AudioDeviceChangeListener) {
        listeners.add(listener)
    }

    fun unregisterAudioDeviceChangeListener(listener: AudioDeviceChangeListener) {
        listeners.remove(listener)
    }

    fun start() {
        if (started) return
        val switch = audioSwitch ?: AudioSwitch(
            context = appContext,
            preferredDeviceList = preferredDeviceList,
        ).also { audioSwitch = it }
        switch.start { devices, selected ->
            listeners.toList().forEach { it(devices, selected) }
        }
        switch.activate()
        started = true
    }

    fun stop() {
        if (!started) return
        started = false
        audioSwitch?.deactivate()
        audioSwitch?.stop()
    }

    fun selectDevice(device: AudioDevice?) {
        audioSwitch?.selectDevice(device)
    }

    companion object {
        private val DEFAULT_PREFERRED_DEVICE_LIST = listOf(
            AudioDevice.BluetoothHeadset::class.java,
            AudioDevice.WiredHeadset::class.java,
            AudioDevice.Speakerphone::class.java,
            AudioDevice.Earpiece::class.java,
        )
    }
}
