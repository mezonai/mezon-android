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
private const val INCOMING_CALL_FCM_MAX_AGE_MS = 60_000L
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
        Log.i(TAG, "offer from notification intent, len=${offerJson.length}")
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
        if (!StartupCache.hasSession) {
            Log.w(TAG, "dispatchOffer: no session")
            return
        }
        val parsed = try {
            JSONObject(offerJson)
        } catch (e: Exception) {
            Log.w(TAG, "dispatchOffer: invalid offer JSON", e)
            return
        }
        val innerOffer = parsed.optString("offer", parsed.optString("sdp", ""))
        if (innerOffer != "CANCEL_CALL") {
            if (sentTimeMs > 0L) {
                val now = System.currentTimeMillis()
                val ageMs = now - sentTimeMs
                if (ageMs > INCOMING_CALL_FCM_MAX_AGE_MS) {
                    Log.w(
                        TAG,
                        "ignoring stale offer ageMs=$ageMs max=$INCOMING_CALL_FCM_MAX_AGE_MS now=$now sentTimeMs=$sentTimeMs"
                    )
                    return
                }
            }
        }
        if (innerOffer == "CANCEL_CALL") {
            val answeredElsewhere = parsed.optBoolean("isConnected", false)
            val ctrl = CallController.instance
            val stateLabel = ctrl?.callState?.let { it::class.simpleName } ?: "null"
            Log.d(
                TAG,
                "CANCEL_CALL FCM: answeredElsewhere=$answeredElsewhere controller=${ctrl != null} callState=$stateLabel"
            )
            CallNotificationManager(appContext).dismissIncomingNotification()
            if (answeredElsewhere && ctrl?.shouldIgnoreCancelCallFcmAnsweredElsewhere() == true) {
                Log.i(
                    TAG,
                    "CANCEL_CALL FCM: ignored — local session active ($stateLabel), do not endCall"
                )
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
                    Log.d(TAG, "CANCEL_CALL FCM: answeredElsewhere + Idle → cleared stale incoming artifacts")
                } else {
                    ctrl?.endCall(CallEndReason.CLEAR_CALL)
                    Log.d(TAG, "CANCEL_CALL FCM: answeredElsewhere → endCall(CLEAR_CALL)")
                }
            } else {
                ctrl?.endCall(CallEndReason.CANCELLED)
                Log.d(TAG, "CANCEL_CALL FCM: real cancel → endCall(CANCELLED)")
            }
            return
        }
        if (callController.callState !is CallState.Idle) {
            Log.d(TAG, "skip offer, callState=${callController.callState::class.simpleName}")
            return
        }
        val callerName = parsed.optString("callerName", "Unknown")
        val callerAvatar = parsed.optString("callerAvatar", "")
        val callerId = parsed.optString("callerId", "")
        val channelId = parsed.optString("channelId", "")
        Log.i(TAG, "dispatchOffer: offer filters passed (not cancel, session ok) → reconnect socket for signaling")
        connectionController.reconnectSocketForOfferSignaling()
        webRtcInfra.ensureFactoryReady()
        Log.d(TAG, "feeding offer to CallController")
        callController.handleIncomingOfferFromFcm(
            callerName,
            callerAvatar = callerAvatar,
            callerId,
            channelId,
            offerJson
        )
        if (callController.callState !is CallState.Incoming) {
            Log.d(TAG, "dispatchOffer: offer consumed without incoming UI, state=${callController.callState::class.simpleName}")
            return
        }
        StartupCache.suppressHomeListApiForIncomingCallWake = true
        appContext.getSharedPreferences("call_data", Context.MODE_PRIVATE).edit()
            .putString("incoming_call", offerJson)
            .commit()
        mainHandler.post {
            if (MainActivity.isResumed) {
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
                Log.i(TAG, "incoming ring via FGS+Telecom notified")
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

    companion object {
        private val OFFER_DATA_KEYS = arrayOf("offer", "offer_json", "json_data")
    }
}
