package com.mezon.mobile.home.call

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.telecom.DisconnectCause
import android.util.Log
import com.google.firebase.messaging.RemoteMessage
import com.mezon.mobile.MainActivity
import com.mezon.mobile.core.StartupCache
import com.mezon.mobile.home.ConnectionController
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "IncomingCallFcm"
private val mainHandler = Handler(Looper.getMainLooper())

@Singleton
class IncomingCallFcmHandler @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val callManager: CallManager,
    private val callController: CallController,
    private val webRtcInfra: WebRtcInfra,
    private val connectionController: ConnectionController,
) {

    fun hasOfferPayload(data: Map<String, String>): Boolean =
        extractOfferPayload(data) != null

    fun handleRemoteMessage(message: RemoteMessage) {
        val offerJson = extractOfferPayload(message.data) ?: return
        dispatchOffer(offerJson, message.sentTime)
    }

    fun handleOfferExtraFromNotificationIntent(offerJson: String) {
        dispatchOffer(offerJson, sentTimeMs = 0L)
    }

    private fun extractOfferPayload(data: Map<String, String>): String? {
        for (key in OFFER_DATA_KEYS) {
            data[key]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return data.entries.firstOrNull { it.key.equals("offer", ignoreCase = true) }
            ?.value?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun dispatchOffer(offerJson: String, sentTimeMs: Long) {
        if (!StartupCache.hasSession) return
        val parsed = try {
            JSONObject(offerJson)
        } catch (_: Exception) {
            return
        }
        val innerOffer = parsed.optString("offer", parsed.optString("sdp", ""))
        if (innerOffer != "CANCEL_CALL" && IncomingCallStaleness.isStaleOffer(offerJson, sentTimeMs)) {
            appContext.getSharedPreferences("call_data", Context.MODE_PRIVATE).edit()
                .remove("incoming_call")
                .apply()
            CallNotificationManager(appContext).dismissIncomingNotification()
            return
        }
        if (innerOffer == "CANCEL_CALL") {
            handleCancelCallFcm(parsed)
            return
        }
        if (callController.callState !is CallState.Idle) return
        val callerName = parsed.optString("callerName", "Unknown")
        val callerAvatar = parsed.optString("callerAvatar", "")
        val callerId = parsed.optString("callerId", "")
        val channelId = parsed.optString("channelId", "")
        connectionController.reconnectSocketForOfferSignaling()
        webRtcInfra.ensureFactoryReady()
        callController.handleIncomingOfferFromFcm(
            callerName,
            callerAvatar = callerAvatar,
            callerId,
            channelId,
            offerJson
        )
        if (callController.callState !is CallState.Incoming) return
        StartupCache.suppressHomeListApiForIncomingCallWake = true
        appContext.getSharedPreferences("call_data", Context.MODE_PRIVATE).edit()
            .putString("incoming_call", offerJson)
            .commit()
        mainHandler.post {
            if (MainActivity.isResumed && !callManager.isDeviceLockedOrScreenOff()) {
                return@post
            }
            callManager.showIncomingCall(
                callerName,
                callerId,
                channelId,
                offerJson,
                callController.currentCallInfo()?.isVideo == true
            )
            val useFsi = callManager.canUseFullScreenIntent()
            try {
                CallForegroundService.startRinging(
                    appContext,
                    callerName,
                    callerAvatar,
                    callerId,
                    channelId,
                    offerJson,
                    useFsi
                )
            } catch (e: Exception) {
                Log.e(TAG, "ring FGS failed, notify fallback", e)
                CallNotificationManager(appContext).showIncomingCallNotification(
                    callerName = callerName,
                    callerAvatar = callerAvatar,
                    callerId = callerId,
                    channelId = channelId,
                    offerJson = offerJson,
                    useFullScreenIntent = useFsi
                )
            }
        }
    }

    private fun handleCancelCallFcm(parsed: JSONObject) {
        val answeredElsewhere = parsed.optBoolean("isConnected", false)
        val ctrl = CallController.instance
        CallNotificationManager(appContext).dismissIncomingNotification()
        if (answeredElsewhere && ctrl?.shouldIgnoreCancelCallFcmAnsweredElsewhere() == true) {
            return
        }
        MezonCallConnection.activeConnection?.let {
            it.setDisconnected(DisconnectCause(DisconnectCause.CANCELED))
            it.destroy()
            MezonCallConnection.activeConnection = null
        }
        if (answeredElsewhere) {
            if (ctrl?.callState is CallState.Idle) {
                ctrl.clearIdleIncomingArtifactsAfterAnsweredElsewhere()
            } else {
                ctrl?.endCall(CallEndReason.CLEAR_CALL)
            }
        } else {
            ctrl?.endCall(CallEndReason.CANCELLED)
        }
    }

    companion object {
        private val OFFER_DATA_KEYS = arrayOf("offer", "offer_json", "json_data")
    }
}
