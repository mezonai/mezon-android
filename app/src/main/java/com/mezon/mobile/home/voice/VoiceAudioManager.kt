package com.mezon.mobile.home.voice

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.ui.cells.MezonIcon
import com.twilio.audioswitch.AudioDevice
import com.twilio.audioswitch.AudioDeviceChangeListener
import io.livekit.android.audio.AudioHandler
import io.livekit.android.audio.AudioSwitchHandler

class VoiceAudioManager(context: Context) {

    private val appContext = context.applicationContext

    val audioSwitchHandler: AudioSwitchHandler = AudioSwitchHandler(appContext).apply {
        preferredDeviceList = PREFERRED_DEVICE_LIST
        forceHandleAudioRouting = true
        focusMode = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
    }

    private val outputChangedRunnable = Runnable {
        onOutputChanged?.invoke()
    }

    private val deviceChangeListener: AudioDeviceChangeListener = { _, _ ->
        AndroidUtilities.runOnUIThread(outputChangedRunnable)
    }

    var onOutputChanged: (() -> Unit)? = null

    private var userHasChosenOutput = false
    private var defaultRoutingApplied = false

    init {
        audioSwitchHandler.registerAudioDeviceChangeListener(deviceChangeListener)
    }

    fun release() {
        AndroidUtilities.cancelRunOnUIThread(outputChangedRunnable)
        audioSwitchHandler.unregisterAudioDeviceChangeListener(deviceChangeListener)
        onOutputChanged = null
    }

    fun asLiveKitAudioHandler(): AudioHandler = audioSwitchHandler

    fun resetDefaultRouting() {
        defaultRoutingApplied = false
    }

    fun applyDefaultRouting() {
        if (userHasChosenOutput) return
        val handler = audioSwitchHandler
        val available = handler.availableAudioDevices
        val headset = available.firstOrNull { it is AudioDevice.BluetoothHeadset }
            ?: available.firstOrNull { it is AudioDevice.WiredHeadset }
        if (headset != null) {
            if (handler.selectedAudioDevice?.javaClass != headset.javaClass) {
                handler.selectDevice(headset)
            }
            defaultRoutingApplied = true
            return
        }
        val speaker = available.filterIsInstance<AudioDevice.Speakerphone>().firstOrNull() ?: return
        if (handler.selectedAudioDevice is AudioDevice.Speakerphone) {
            if (!defaultRoutingApplied) {
                val earpiece = available.filterIsInstance<AudioDevice.Earpiece>().firstOrNull()
                if (earpiece != null) {
                    handler.selectDevice(earpiece)
                    handler.selectDevice(speaker)
                }
            }
        } else {
            handler.selectDevice(speaker)
        }
        defaultRoutingApplied = true
        Log.d("VoiceAudioManager", "applyDefaultRouting selected=${handler.selectedAudioDevice}")
    }

    fun cycleOutput() {
        userHasChosenOutput = true
        val handler = audioSwitchHandler
        val selected = handler.selectedAudioDevice
        val available = handler.availableAudioDevices
        when (selected) {
            is AudioDevice.Speakerphone -> {
                val bluetooth = available.filterIsInstance<AudioDevice.BluetoothHeadset>().firstOrNull()
                if (bluetooth != null) {
                    handler.selectDevice(bluetooth)
                } else {
                    available.filterIsInstance<AudioDevice.Earpiece>().firstOrNull()?.let { handler.selectDevice(it) }
                }
            }
            is AudioDevice.BluetoothHeadset -> {
                available.filterIsInstance<AudioDevice.Earpiece>().firstOrNull()?.let { handler.selectDevice(it) }
            }
            else -> {
                available.filterIsInstance<AudioDevice.Speakerphone>().firstOrNull()?.let { handler.selectDevice(it) }
            }
        }
    }

    fun currentOutputIcon(): MezonIcon {
        return when (audioSwitchHandler.selectedAudioDevice) {
            is AudioDevice.Speakerphone -> MezonIcon.voiceWaveDoubleIcon
            is AudioDevice.BluetoothHeadset -> MezonIcon.bluetoothIcon
            else -> MezonIcon.voiceWaveIcon
        }
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
