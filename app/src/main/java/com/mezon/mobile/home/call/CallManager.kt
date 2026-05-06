package com.mezon.mobile.home.call

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CallManager"
private const val ACCOUNT_ID = "MezonCallAccount"

@Singleton
class CallManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager

    val phoneAccountHandle = PhoneAccountHandle(
        ComponentName(context, MezonCallConnectionService::class.java),
        ACCOUNT_ID
    )

    init {
        registerPhoneAccount()
    }

    private fun registerPhoneAccount() {
        try {
            val account = PhoneAccount.builder(phoneAccountHandle, "Mezon")
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                .build()
            telecomManager?.registerPhoneAccount(account)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register PhoneAccount", e)
        }
    }

    fun showIncomingCall(
        callerName: String,
        callerId: String,
        channelId: String,
        offerJson: String
    ): Boolean {
        val extras = Bundle().apply {
            putString(EXTRA_CALLER_NAME, callerName)
            putString(EXTRA_CALLER_ID, callerId)
            putString(EXTRA_CHANNEL_ID, channelId)
            putString(EXTRA_OFFER_JSON, offerJson)
        }
        val manager = telecomManager ?: return false
        return try {
            manager.addNewIncomingCall(phoneAccountHandle, extras)
            true
        } catch (e: Exception) {
            Log.e(TAG, "TelecomManager.addNewIncomingCall failed", e)
            false
        }
    }

    fun canUseFullScreenIntent(): Boolean {
        if (Build.VERSION.SDK_INT >= 34) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            return nm?.canUseFullScreenIntent() == true
        }
        return true
    }

    companion object {
        const val EXTRA_CALLER_NAME = "caller_name"
        const val EXTRA_CALLER_ID = "caller_id"
        const val EXTRA_CHANNEL_ID = "channel_id"
        const val EXTRA_CALLER_AVATAR = "caller_avatar"
        const val EXTRA_OFFER_JSON = "offer_json"
    }
}
