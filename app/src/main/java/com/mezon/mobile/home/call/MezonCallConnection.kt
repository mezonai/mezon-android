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

    init {
        activeConnection = this
    }

    override fun onShowIncomingCallUi() {
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
        // setActive()
        controller?.acceptCall()
    }

    override fun onReject() {
        val offerEnvelope = incomingExtras?.getString(CallManager.EXTRA_OFFER_JSON)?.trim()?.takeIf { it.isNotEmpty() }
            ?: context.getSharedPreferences("call_data", Context.MODE_PRIVATE).getString("incoming_call", null)
        setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
        destroy()
        activeConnection = null
        ensureCallController()?.rejectCallFromIncomingCallUi(offerEnvelope)
    }

    override fun onDisconnect() {
        setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        destroy()
        activeConnection = null
        ensureCallController()?.hangup()
    }

    override fun onAbort() {
        setDisconnected(DisconnectCause(DisconnectCause.CANCELED))
        destroy()
        activeConnection = null
    }

    fun setCallActive() {
        setActive()
    }

    fun setCallDisconnected(cause: Int) {
        setDisconnected(DisconnectCause(cause))
        destroy()
        activeConnection = null
    }

    companion object {
        @Volatile
        var activeConnection: MezonCallConnection? = null
    }
}
