package com.mezon.mobile.home.voice

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log

private const val TAG = "VoiceAudioManager"

enum class AudioOutputDevice {
    EARPIECE,
    SPEAKER,
    BLUETOOTH
}

class VoiceAudioManager(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var currentDevice = AudioOutputDevice.EARPIECE
    private var bluetoothAvailable = false
    private var bluetoothReceiver: BroadcastReceiver? = null

    var onBluetoothStateChanged: ((available: Boolean) -> Unit)? = null

    fun start() {
        requestAudioFocus()
        setEarpiece()
        registerBluetoothReceiver()
    }

    fun stop() {
        abandonAudioFocus()
        unregisterBluetoothReceiver()
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.isSpeakerphoneOn = false
    }

    fun setEarpiece() {
        currentDevice = AudioOutputDevice.EARPIECE
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = false
        stopBluetoothSco()
    }

    fun setSpeaker() {
        currentDevice = AudioOutputDevice.SPEAKER
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = true
        stopBluetoothSco()
    }

    fun setBluetooth() {
        if (!bluetoothAvailable) return
        currentDevice = AudioOutputDevice.BLUETOOTH
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = false
        startBluetoothSco()
    }

    fun getCurrentDevice(): AudioOutputDevice = currentDevice

    fun isBluetoothAvailable(): Boolean = bluetoothAvailable

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener { }
                .build()
            audioFocusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                { }, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN
            )
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus { }
        }
        audioFocusRequest = null
    }

    @Suppress("DEPRECATION")
    private fun startBluetoothSco() {
        try {
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
        } catch (e: Exception) {
            Log.e(TAG, "startBluetoothSco failed", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun stopBluetoothSco() {
        try {
            if (audioManager.isBluetoothScoOn) {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "stopBluetoothSco failed", e)
        }
    }

    private fun registerBluetoothReceiver() {
        bluetoothReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                checkBluetoothAvailability()
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
            addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        }
        context.registerReceiver(bluetoothReceiver, filter)
        checkBluetoothAvailability()
    }

    private fun unregisterBluetoothReceiver() {
        bluetoothReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: Exception) {}
        }
        bluetoothReceiver = null
    }

    private fun checkBluetoothAvailability() {
        val wasAvailable = bluetoothAvailable
        bluetoothAvailable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isBluetoothA2dpOn || audioManager.isBluetoothScoOn
        }
        if (wasAvailable != bluetoothAvailable) {
            onBluetoothStateChanged?.invoke(bluetoothAvailable)
        }
    }
}
