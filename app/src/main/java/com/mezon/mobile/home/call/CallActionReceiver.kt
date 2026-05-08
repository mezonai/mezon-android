package com.mezon.mobile.home.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telecom.DisconnectCause
import android.util.Log
import com.mezon.mobile.di.FragmentEntryPoint
import dagger.hilt.android.EntryPointAccessors

private const val TAG = "CallActionReceiver"

class CallActionReceiver : BroadcastReceiver() {

    private fun ensureCallController(context: Context): CallController? {
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

    override fun onReceive(context: Context, intent: Intent) {
        val controller = ensureCallController(context)
        Log.d(TAG, "onReceive: action=${intent.action}, instance=${controller != null}, state=${controller?.callState?.let { it::class.simpleName }}")
        when (intent.action) {
            ACTION_END -> {
                controller?.hangup()
                MezonCallConnection.activeConnection?.let {
                    it.setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
                    it.destroy()
                    MezonCallConnection.activeConnection = null
                }
                CallNotificationManager(context).dismissOngoingNotification()
            }
        }
    }

    companion object {
        const val ACTION_END = "com.mezon.mobile.call.END"
    }
}

