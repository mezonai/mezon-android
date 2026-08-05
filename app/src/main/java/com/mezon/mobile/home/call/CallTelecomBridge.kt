package com.mezon.mobile.home.call

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.DisconnectCause
import android.telecom.VideoProfile
import android.util.Log
import com.mezon.mobile.di.FragmentEntryPoint
import dagger.hilt.android.EntryPointAccessors
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CallTelecomBridge"

@Singleton
class CallTelecomBridge @Inject constructor(
    private val callManager: CallManager
) {

    @Volatile
    private var connection: MezonCallConnection? = null

    @Volatile
    private var pendingAudioRoute: Int? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        instance = this
    }

    fun attach(newConnection: MezonCallConnection) {
        val previous = connection
        if (previous != null && previous !== newConnection) {
            Log.w(TAG, "replacing a live Telecom connection")
            teardown(previous, DisconnectCause.CANCELED)
        }
        connection = newConnection
        pendingAudioRoute?.let { route ->
            mainHandler.post {
                if (connection === newConnection) {
                    applyAudioRoute(newConnection, route)
                }
            }
        }
    }

    fun requestAudioRoute(route: Int): Boolean {
        pendingAudioRoute = route
        val current = connection ?: return false
        return applyAudioRoute(current, route)
    }

    private fun applyAudioRoute(target: MezonCallConnection, route: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return try {
            target.setAudioRoute(route)
            true
        } catch (e: Exception) {
            Log.w(TAG, "setAudioRoute failed", e)
            false
        }
    }

    fun updateVideoState(isVideo: Boolean) {
        val current = connection ?: return
        try {
            current.setVideoState(
                if (isVideo) VideoProfile.STATE_BIDIRECTIONAL else VideoProfile.STATE_AUDIO_ONLY
            )
        } catch (e: Exception) {
            Log.w(TAG, "setVideoState failed", e)
        }
    }

    fun startOutgoing(callInfo: CallInfo): Boolean {
        return try {
            callManager.placeOutgoingCall(
                callerName = callInfo.peerName,
                peerId = callInfo.peerId.toString(),
                channelId = callInfo.channelId.toString(),
                isVideoCall = callInfo.isVideo
            )
        } catch (e: Exception) {
            Log.w(TAG, "placeOutgoingCall failed, continuing without Telecom", e)
            false
        }
    }

    fun startIncoming(callInfo: CallInfo, offerJson: String): Boolean {
        return try {
            callManager.showIncomingCall(
                callInfo.peerName,
                callInfo.peerId.toString(),
                callInfo.channelId.toString(),
                offerJson,
                callInfo.isVideo
            )
        } catch (e: Exception) {
            Log.w(TAG, "showIncomingCall failed, continuing without Telecom", e)
            false
        }
    }

    fun markActive() {
        val current = connection ?: return
        try {
            current.setCallActive()
        } catch (e: Exception) {
            Log.w(TAG, "setActive failed", e)
        }
    }

    fun endWithReason(reason: CallEndReason) {
        end(disconnectCauseFor(reason))
    }

    fun endWithCause(cause: Int) {
        end(cause)
    }

    private fun end(cause: Int) {
        pendingAudioRoute = null
        val current = connection ?: return
        connection = null
        teardown(current, cause)
    }

    private fun teardown(target: MezonCallConnection, cause: Int) {
        try {
            target.setDisconnected(DisconnectCause(cause))
            target.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Telecom teardown failed", e)
        }
    }

    companion object {
        @Volatile
        var instance: CallTelecomBridge? = null
            private set

        fun from(context: Context): CallTelecomBridge? {
            instance?.let { return it }
            return try {
                EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    FragmentEntryPoint::class.java
                ).callTelecomBridge()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get CallTelecomBridge from Hilt", e)
                null
            }
        }

        fun disconnectCauseFor(reason: CallEndReason): Int = when (reason) {
            CallEndReason.LOCAL_HANGUP -> DisconnectCause.LOCAL
            CallEndReason.REMOTE_HANGUP -> DisconnectCause.REMOTE
            CallEndReason.LOCAL_REJECT, CallEndReason.REMOTE_REJECT -> DisconnectCause.REJECTED
            CallEndReason.TIMEOUT, CallEndReason.REMOTE_TIMEOUT -> DisconnectCause.MISSED
            CallEndReason.ICE_FAILED, CallEndReason.ERROR -> DisconnectCause.ERROR
            CallEndReason.BUSY -> DisconnectCause.BUSY
            CallEndReason.CLEAR_CALL, CallEndReason.CANCELLED -> DisconnectCause.CANCELED
        }
    }
}
