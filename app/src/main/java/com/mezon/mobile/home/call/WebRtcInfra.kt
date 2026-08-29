package com.mezon.mobile.home.call

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.CountDownLatch
import javax.inject.Inject
import javax.inject.Singleton
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.SoftwareVideoDecoderFactory
import org.webrtc.VideoCodecInfo
import org.webrtc.VideoDecoder
import org.webrtc.VideoDecoderFactory
import org.webrtc.audio.JavaAudioDeviceModule

private const val TAG = "WebRtcInfra"

@Singleton
class WebRtcInfra @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    @Volatile private var initialized = false
    private val initLock = Any()
    private var _factory: PeerConnectionFactory? = null
    private var _eglContext: EglBase.Context? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    val factory: PeerConnectionFactory
        get() {
            ensureReadyBlocking()
            return _factory ?: error("PeerConnectionFactory not initialized")
        }

    val eglContext: EglBase.Context
        get() {
            ensureReadyBlocking()
            return _eglContext ?: error("EglBase.Context not initialized")
        }

    fun prewarm() {
        if (initialized) return
        mainHandler.post { ensureInitializedOnMain() }
    }

    fun ensureFactoryReady() {
        ensureReadyBlocking()
    }

    private fun ensureReadyBlocking() {
        if (initialized) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            ensureInitializedOnMain()
            return
        }
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                ensureInitializedOnMain()
            } finally {
                latch.countDown()
            }
        }
        latch.await()
    }

    private fun ensureInitializedOnMain() {
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return
            try {
                doInitialize()
                initialized = true
                Log.d(TAG, "WebRTC infra ready")
            } catch (e: Exception) {
                Log.e(TAG, "ensureInitializedOnMain failed", e)
            }
        }
    }

    private fun doInitialize() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )

        val sharedEglContext = EglBaseProvider.acquire()
        _eglContext = sharedEglContext

        val audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .createAudioDeviceModule()

        _factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(sharedEglContext, true, true))
            .setVideoDecoderFactory(SfuVideoDecoderFactory(sharedEglContext))
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()
    }
}

private class SfuVideoDecoderFactory(eglContext: EglBase.Context?) : VideoDecoderFactory {
    private val hardware = DefaultVideoDecoderFactory(eglContext)
    private val software = SoftwareVideoDecoderFactory()

    override fun createDecoder(codecInfo: VideoCodecInfo): VideoDecoder? {
        if (codecInfo.name.equals("VP8", ignoreCase = true)) {
            return software.createDecoder(codecInfo) ?: hardware.createDecoder(codecInfo)
        }
        return hardware.createDecoder(codecInfo)
    }

    override fun getSupportedCodecs(): Array<VideoCodecInfo> = hardware.supportedCodecs
}
