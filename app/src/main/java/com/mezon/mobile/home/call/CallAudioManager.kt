package com.mezon.mobile.home.call

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.PowerManager
import android.os.Vibrator
import android.os.VibratorManager
import android.os.VibrationEffect
import android.util.Log

private const val TAG = "CallAudioManager"

enum class AudioOutputDevice {
    EARPIECE, SPEAKER, BLUETOOTH
}

class CallAudioManager(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private var audioFocusRequest: AudioFocusRequest? = null
    private var proximityWakeLock: PowerManager.WakeLock? = null
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var tonePlayer: MediaPlayer? = null
    private var toneGenerator: ToneGenerator? = null
    private var vibrator: Vibrator? = null
    private var isVibrating = false
    private var currentOutput = AudioOutputDevice.EARPIECE
    private var bluetoothReceiver: BroadcastReceiver? = null
    var isStarted = false; private set

    fun start(isVideo: Boolean) {
        if (isStarted) return
        isStarted = true

        requestAudioFocus()
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        acquireCpuWakeLock()
        registerBluetoothReceiver()

        if (isVideo) {
            setSpeaker()
        } else {
            setEarpiece()
        }
        ensureAudibleCallVolume()
    }

    fun startForIncomingRing() {
        if (isStarted) return
        isStarted = true
        acquireCpuWakeLock()
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    fun advanceToEstablishedCallRouting(isVideo: Boolean) {
        if (!isStarted) {
            start(isVideo)
            return
        }
        if (audioFocusRequest == null) {
            requestAudioFocus()
        }
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (bluetoothReceiver == null) {
            registerBluetoothReceiver()
        }
        if (isVideo) {
            setSpeaker()
        } else {
            setEarpiece()
        }
        ensureAudibleCallVolume()
    }

    fun startRinging() {
        acquireCpuWakeLock()
    }

    fun stop() {
        stopTone()
        stopVibration()
        releaseProximityWakeLock()
        releaseCpuWakeLock()
        if (!isStarted) return
        isStarted = false

        abandonAudioFocus()
        unregisterBluetoothReceiver()
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    fun setEarpiece() {
        currentOutput = AudioOutputDevice.EARPIECE
        if (Build.VERSION.SDK_INT >= 31) {
            audioManager.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
        }
        acquireProximityWakeLock()
    }

    fun setSpeaker() {
        currentOutput = AudioOutputDevice.SPEAKER
        if (Build.VERSION.SDK_INT >= 31) {
            val devices = audioManager.availableCommunicationDevices
            val speaker = devices.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            speaker?.let { audioManager.setCommunicationDevice(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = true
        }
        releaseProximityWakeLock()
    }

    fun setBluetooth() {
        currentOutput = AudioOutputDevice.BLUETOOTH
        if (Build.VERSION.SDK_INT >= 31) {
            val devices = audioManager.availableCommunicationDevices
            val bt = devices.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            bt?.let { audioManager.setCommunicationDevice(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.startBluetoothSco()
        }
        releaseProximityWakeLock()
    }

    private fun ensureAudibleCallVolume() {
        try {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            if (max <= 0) return
            val target = (max * 0.7f).toInt().coerceIn(1, max)
            val current = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            if (current < target) {
                audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, target, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "ensureAudibleCallVolume failed", e)
        }
    }

    fun playDialTone() {
        stopTone()
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80)
            toneGenerator?.startTone(ToneGenerator.TONE_SUP_RINGTONE, 30000)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play dial tone", e)
        }
    }

    fun playRingtone() {
        stopTone()
        try {
            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            tonePlayer = MediaPlayer().apply {
                setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, ringtoneUri)
                isLooping = true
                setVolume(1f, 1f)
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play ringtone, falling back to tone generator", e)
            toneGenerator = ToneGenerator(AudioManager.STREAM_RING, 80)
            toneGenerator?.startTone(ToneGenerator.TONE_SUP_RINGTONE, 30000)
        }
        startVibration()
    }

    fun playEndCallTone() {
        stopTone()
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80)
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_PROMPT, 200)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play end call tone", e)
        }
    }

    fun playBusyTone() {
        stopTone()
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80)
            toneGenerator?.startTone(ToneGenerator.TONE_SUP_BUSY, 2000)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play busy tone", e)
        }
    }

    fun stopTone() {
        tonePlayer?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.reset() } catch (_: Exception) {}
            it.release()
        }
        tonePlayer = null

        toneGenerator?.let {
            try { it.stopTone() } catch (_: Exception) {}
            it.release()
        }
        toneGenerator = null

        stopVibration()
    }

    private fun startVibration() {
        if (isVibrating) return
        isVibrating = true

        vibrator = if (Build.VERSION.SDK_INT >= 31) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        val pattern = longArrayOf(0, 1000, 500, 1000, 500, 1000)
        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
    }

    private fun stopVibration() {
        if (!isVibrating) return
        isVibrating = false
        vibrator?.cancel()
        vibrator = null
    }

    private fun requestAudioFocus() {
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { }
            .build()

        audioManager.requestAudioFocus(audioFocusRequest!!)
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
        }
        audioFocusRequest = null
    }

    private fun acquireProximityWakeLock() {
        if (proximityWakeLock != null) return
        proximityWakeLock = powerManager.newWakeLock(
            PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
            "mezon:call_proximity"
        )
        proximityWakeLock?.acquire(60 * 60 * 1000L)
    }

    private fun releaseProximityWakeLock() {
        proximityWakeLock?.let {
            if (it.isHeld) it.release()
        }
        proximityWakeLock = null
    }

    private fun acquireCpuWakeLock() {
        if (cpuWakeLock != null) return
        cpuWakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "mezon:call_cpu"
        )
        cpuWakeLock?.acquire(60 * 60 * 1000L)
    }

    private fun releaseCpuWakeLock() {
        cpuWakeLock?.let {
            if (it.isHeld) it.release()
        }
        cpuWakeLock = null
    }

    private fun registerBluetoothReceiver() {
        bluetoothReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        if (currentOutput != AudioOutputDevice.SPEAKER) {
                            setBluetooth()
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (currentOutput == AudioOutputDevice.BLUETOOTH) {
                            setEarpiece()
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        }
        context.registerReceiver(bluetoothReceiver, filter)
    }

    private fun unregisterBluetoothReceiver() {
        bluetoothReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: Exception) {}
        }
        bluetoothReceiver = null
    }
}
