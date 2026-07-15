package com.mezon.mobile.home.call

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.telecom.Connection
import android.telecom.DisconnectCause
import android.util.Log
import com.mezon.mobile.di.FragmentEntryPoint
import dagger.hilt.android.EntryPointAccessors

private const val TAG = "MezonCallConnection"

class MezonCallConnection(private val context: Context) : Connection() {

    var incomingExtras: Bundle? = null

    private fun ensureCallController(): CallController? {
        CallController.instance?.let { return it }
        return try {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext,
                FragmentEntryPoint::class.java
            )
            entryPoint.callController()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get CallController from Hilt", e)
            null
        }
    }

    private fun ensureTelecomBridge(): CallTelecomBridge? = CallTelecomBridge.from(context)

    override fun onShowIncomingCallUi() {
        if (ensureCallController()?.isAppInForegroundForIncomingCall() == true) return
        launchIncomingCallActivity()
    }

    private fun launchIncomingCallActivity() {
        val intent = Intent(context, IncomingCallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            incomingExtras?.let { putExtras(it) }
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "launchIncomingCallActivity failed", e)
        }
    }

    override fun onAnswer() {
        val controller = ensureCallController()
        Log.d(TAG, "onAnswer: instance=${controller != null}, state=${controller?.callState?.let { it::class.simpleName }}")
        setActive()
        controller?.acceptCall()
    }

    override fun onReject() {
        val offerEnvelope = incomingExtras?.getString(CallManager.EXTRA_OFFER_JSON)?.trim()?.takeIf { it.isNotEmpty() }
            ?: context.getSharedPreferences("call_data", Context.MODE_PRIVATE).getString("incoming_call", null)
        ensureTelecomBridge()?.endWithCause(DisconnectCause.REJECTED)
        ensureCallController()?.rejectCallFromIncomingCallUi(offerEnvelope)
    }

    override fun onDisconnect() {
        ensureTelecomBridge()?.endWithCause(DisconnectCause.LOCAL)
        ensureCallController()?.hangup()
    }

    override fun onAbort() {
        ensureTelecomBridge()?.endWithCause(DisconnectCause.CANCELED)
    }

    fun setCallActive() {
        setActive()
    }
}
