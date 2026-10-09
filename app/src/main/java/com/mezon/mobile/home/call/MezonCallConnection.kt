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

class MezonCallConnection(private val context: Context, val localCallId: String) : Connection() {

    var incomingExtras: Bundle? = null

    private fun ensureCallController(): CallController? {
        CallController.instance?.let { return it.takeIf { controller -> controller.currentCallInfo()?.localCallId == localCallId } }
        return try {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext,
                FragmentEntryPoint::class.java
            )
            entryPoint.callController().takeIf { it.currentCallInfo()?.localCallId == localCallId }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get CallController from Hilt", e)
            null
        }
    }

    private fun ensureTelecomBridge(): CallTelecomBridge? = CallTelecomBridge.from(context)

    override fun onShowIncomingCallUi() {
        val controller = ensureCallController()
        if (controller == null) {
            ensureTelecomBridge()?.endOwnedConnection(this, DisconnectCause.CANCELED)
            return
        }
        if (controller.isAppInForegroundForIncomingCall()) return
        launchIncomingCallActivity()
    }

    private fun launchIncomingCallActivity() {
        val intent = Intent(context, IncomingCallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            incomingExtras?.let { putExtras(it) }
            putExtra(CallManager.EXTRA_LOCAL_CALL_ID, localCallId)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "launchIncomingCallActivity failed", e)
        }
    }

    override fun onAnswer() {
        val controller = ensureCallController()
        if (controller == null) {
            ensureTelecomBridge()?.endOwnedConnection(this, DisconnectCause.CANCELED)
        } else {
            controller.acceptCall()
        }
    }

    override fun onReject() {
        val offerEnvelope = incomingExtras?.getString(CallManager.EXTRA_OFFER_JSON)?.trim()?.takeIf { it.isNotEmpty() }
            ?: context.getSharedPreferences("call_data", Context.MODE_PRIVATE).getString("incoming_call", null)
        ensureTelecomBridge()?.endOwnedConnection(this, DisconnectCause.REJECTED)
        ensureCallController()?.rejectCallFromIncomingCallUi(offerEnvelope)
    }

    override fun onDisconnect() {
        ensureTelecomBridge()?.endOwnedConnection(this, DisconnectCause.LOCAL)
        ensureCallController()?.hangup()
    }

    override fun onAbort() {
        ensureTelecomBridge()?.endOwnedConnection(this, DisconnectCause.CANCELED)
        ensureCallController()?.hangup()
    }

    fun setCallActive() {
        setActive()
    }
}
