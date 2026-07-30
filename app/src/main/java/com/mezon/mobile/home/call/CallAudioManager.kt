package com.mezon.mobile.home.call

import android.content.Context
import android.media.AudioAttributes
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
import com.twilio.audioswitch.AudioDevice
import io.livekit.android.audio.AudioSwitchHandler

private const val TAG = "CallAudioManager"

class CallAudioManager(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager

    private val audioSwitch = AudioSwitchHandler(appContext).apply {
        focusMode = AudioManager.AUDIOFOCUS_GAIN
        forceHandleAudioRouting = true
        preferredDeviceList = listOf(
            AudioDevice.BluetoothHeadset::class.java,
            AudioDevice.WiredHeadset::class.java,
            AudioDevice.Earpiece::class.java,
            AudioDevice.Speakerphone::class.java,
        )
    }

    private val deviceChangeListener: (List<AudioDevice>, AudioDevice?) -> Unit = { devices, selected ->
        if (desiredSpeaker && selected !is AudioDevice.Speakerphone) {
            devices.firstOrNull { it is AudioDevice.Speakerphone }?.let { audioSwitch.selectDevice(it) }
        }
    }

    private var switchStarted = false
    private var desiredSpeaker = false

    private var proximityWakeLock: PowerManager.WakeLock? = null
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var tonePlayer: MediaPlayer? = null
    private var toneGenerator: ToneGenerator? = null
    private var vibrator: Vibrator? = null
    private var isVibrating = false
    var isStarted = false; private set

    init {
        audioSwitch.registerAudioDeviceChangeListener(deviceChangeListener)
    }

    fun start(isVideo: Boolean) {
        if (isStarted) return
        isStarted = true

        activateRouting()
        acquireCpuWakeLock()
        applyInitialRoute(isVideo)
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
        activateRouting()
        applyInitialRoute(isVideo)
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

        if (switchStarted) {
            audioSwitch.stop()
            switchStarted = false
        }
    }

    private fun activateRouting() {
        if (switchStarted) return
        audioSwitch.start()
        switchStarted = true
    }

    fun hasExternalAudioDevice(): Boolean {
        return audioSwitch.availableAudioDevices.any {
            it is AudioDevice.BluetoothHeadset || it is AudioDevice.WiredHeadset
        }
    }

    private fun applyInitialRoute(isVideo: Boolean) {
        val hasHeadset = hasExternalAudioDevice()
        when {
            isVideo -> setSpeaker()
            hasHeadset -> {
                desiredSpeaker = false
                releaseProximityWakeLock()
            }
            else -> setEarpiece()
        }
    }

    fun setEarpiece() {
        desiredSpeaker = false
        audioSwitch.availableAudioDevices
            .firstOrNull { it is AudioDevice.Earpiece }
            ?.let { audioSwitch.selectDevice(it) }
        acquireProximityWakeLock()
    }

    fun setSpeaker() {
        desiredSpeaker = true
        audioSwitch.availableAudioDevices
            .firstOrNull { it is AudioDevice.Speakerphone }
            ?.let { audioSwitch.selectDevice(it) }
        releaseProximityWakeLock()
    }

    fun setBluetooth() {
        desiredSpeaker = false
        audioSwitch.availableAudioDevices
            .firstOrNull { it is AudioDevice.BluetoothHeadset }
            ?.let { audioSwitch.selectDevice(it) }
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
                setWakeMode(appContext, PowerManager.PARTIAL_WAKE_LOCK)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(appContext, ringtoneUri)
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
            val vm = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
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
}
