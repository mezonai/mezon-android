package com.mezon.mobile.home.call

import android.content.Context
import android.util.Log
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "WebRtcInfra"

@Singleton
class WebRtcInfra @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val appScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    @Volatile private var initialized = false
    private val mutex = Mutex()
    private var _factory: PeerConnectionFactory? = null
    private var _eglContext: EglBase.Context? = null

    val factory: PeerConnectionFactory
        get() {
            ensureInitializedBlocking()
            return _factory!!
        }

    val eglContext: EglBase.Context
        get() {
            ensureInitializedBlocking()
            return _eglContext!!
        }

    fun prewarm() {
        if (initialized) return
        appScope.launch(ioDispatcher) {
            ensureInitializedSuspend()
        }
    }

    @Synchronized
    private fun ensureInitializedBlocking() {
        if (initialized) return
        try {
            doInitialize()
            initialized = true
        } catch (e: Exception) {
            Log.e(TAG, "ensureInitializedBlocking failed", e)
        }
    }

    private suspend fun ensureInitializedSuspend() {
        if (initialized) return
        mutex.withLock {
            if (initialized) return
            withContext(ioDispatcher) {
                try {
                    doInitialize()
                    initialized = true
                    Log.d(TAG, "WebRTC infra prewarmed")
                } catch (e: Exception) {
                    Log.e(TAG, "ensureInitializedSuspend failed", e)
                }
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
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(sharedEglContext))
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()
    }
}
