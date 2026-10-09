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
        if (intent.action != ACTION_END && intent.action != ACTION_DECLINE) return
        val controller = ensureCallController(context)
        val currentInfo = controller?.currentCallInfo()
        val localCallId = intent.getStringExtra(CallManager.EXTRA_LOCAL_CALL_ID)
        if (currentInfo != null && currentInfo.localCallId != localCallId) return
        if (currentInfo == null) {
            CallTelecomBridge.from(context)?.endOrphanedConnection(DisconnectCause.CANCELED)
            CallForegroundService.stop(context)
            val notifications = CallNotificationManager(context)
            notifications.dismissIncomingNotification()
            notifications.dismissOngoingNotification()
            context.getSharedPreferences("call_data", Context.MODE_PRIVATE).edit().remove("incoming_call").apply()
            return
        }
        when (intent.action) {
            ACTION_END -> {
                controller?.hangup()
                CallNotificationManager(context).dismissOngoingNotification()
            }
            ACTION_DECLINE -> {
                if (controller?.callState !is CallState.Incoming) return
                controller?.rejectCall()
                CallNotificationManager(context).dismissIncomingNotification()
            }
        }
    }

    companion object {
        const val ACTION_END = "com.mezon.mobile.call.END"
        const val ACTION_DECLINE = "com.mezon.mobile.call.DECLINE"
    }
}
