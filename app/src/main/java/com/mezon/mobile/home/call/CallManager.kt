package com.mezon.mobile.home.call

import android.app.Activity
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CallManager"
private const val ACCOUNT_ID = "MezonCallAccount"
private const val TELECOM_OFFER_JSON_MAX_CHARS = 48_000

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
            var capabilities = PhoneAccount.CAPABILITY_SELF_MANAGED
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                capabilities = capabilities or PhoneAccount.CAPABILITY_SUPPORTS_VIDEO_CALLING
            }
            val account = PhoneAccount.builder(phoneAccountHandle, "Mezon")
                .setCapabilities(capabilities)
                .setSupportedUriSchemes(listOf(PhoneAccount.SCHEME_TEL))
                .build()
            telecomManager?.registerPhoneAccount(account)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register PhoneAccount", e)
        }
    }

    fun placeOutgoingCall(
        callerName: String,
        peerId: String,
        channelId: String,
        isVideoCall: Boolean
    ): Boolean {
        val manager = telecomManager ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                if (!manager.isOutgoingCallPermitted(phoneAccountHandle)) {
                    Log.w(TAG, "Telecom denied outgoing call (isOutgoingCallPermitted=false)")
                    return false
                }
            } catch (e: Exception) {
                Log.w(TAG, "isOutgoingCallPermitted check failed", e)
            }
        }
        val callExtras = Bundle().apply {
            putString(EXTRA_CALLER_NAME, callerName)
            putString(EXTRA_CALLER_ID, peerId)
            putString(EXTRA_CHANNEL_ID, channelId)
            putBoolean(EXTRA_IS_VIDEO_CALL, isVideoCall)
        }
        val extras = Bundle().apply {
            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle)
            putBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, callExtras)
            if (isVideoCall) {
                putInt(
                    TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE,
                    VideoProfile.STATE_BIDIRECTIONAL
                )
            }
        }
        return try {
            manager.placeCall(Uri.fromParts(PhoneAccount.SCHEME_TEL, peerId, null), extras)
            true
        } catch (e: Exception) {
            Log.e(TAG, "TelecomManager.placeCall failed", e)
            false
        }
    }

    fun showIncomingCall(
        callerName: String,
        callerId: String,
        channelId: String,
        offerJson: String,
        isVideoCall: Boolean
    ): Boolean {
        val offerForBundle = if (offerJson.length <= TELECOM_OFFER_JSON_MAX_CHARS) offerJson else ""
        if (offerJson.length > TELECOM_OFFER_JSON_MAX_CHARS) {
            Log.w(TAG, "offerJson too large for Telecom Bundle (${offerJson.length} chars), omitting extras; use prefs")
        }
        val extras = Bundle().apply {
            putString(EXTRA_CALLER_NAME, callerName)
            putString(EXTRA_CALLER_ID, callerId)
            putString(EXTRA_CHANNEL_ID, channelId)
            putString(EXTRA_OFFER_JSON, offerForBundle)
            putBoolean(EXTRA_IS_VIDEO_CALL, isVideoCall)
        }
        val manager = telecomManager ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                if (!manager.isIncomingCallPermitted(phoneAccountHandle)) {
                    Log.w(TAG, "Telecom denied incoming call (isIncomingCallPermitted=false)")
                    return false
                }
            } catch (e: Exception) {
                Log.w(TAG, "isIncomingCallPermitted check failed", e)
            }
        }
        return try {
            manager.addNewIncomingCall(phoneAccountHandle, extras)
            true
        } catch (e: Exception) {
            Log.e(TAG, "TelecomManager.addNewIncomingCall failed", e)
            false
        }
    }

    fun isDeviceLockedOrScreenOff(): Boolean {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val locked = keyguardManager?.isKeyguardLocked == true
        val screenOff = powerManager?.isInteractive == false
        return locked || screenOff
    }

    fun canUseFullScreenIntent(): Boolean {
        if (Build.VERSION.SDK_INT >= 34) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            return nm?.canUseFullScreenIntent() == true
        }
        return true
    }

    fun needsFullScreenIntentSettings(): Boolean {
        return Build.VERSION.SDK_INT >= 34 && !canUseFullScreenIntent()
    }

    fun launchFullScreenIntentSettings(from: Context) {
        if (Build.VERSION.SDK_INT < 34) return
        val pkg = from.packageName
        try {
            val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                data = Uri.parse("package:$pkg")
                if (from !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            from.startActivity(intent)
            return
        } catch (e: Exception) {
            Log.e(TAG, "ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT failed", e)
        }
        try {
            val fallback = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                putExtra("android.provider.extra.APP_PACKAGE", pkg)
                if (from !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            from.startActivity(fallback)
        } catch (e: Exception) {
            Log.e(TAG, "notification settings fallback failed", e)
            try {
                val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", pkg, null)
                    if (from !is Activity) {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
                from.startActivity(details)
            } catch (e2: Exception) {
                Log.e(TAG, "app details fallback failed", e2)
            }
        }
    }

    companion object {
        const val EXTRA_CALLER_NAME = "caller_name"
        const val EXTRA_CALLER_ID = "caller_id"
        const val EXTRA_CHANNEL_ID = "channel_id"
        const val EXTRA_CALLER_AVATAR = "caller_avatar"
        const val EXTRA_OFFER_JSON = "offer_json"
        const val EXTRA_IS_VIDEO_CALL = "is_video_call"
        const val EXTRA_AUTO_ANSWER = "auto_answer"
    }
}
