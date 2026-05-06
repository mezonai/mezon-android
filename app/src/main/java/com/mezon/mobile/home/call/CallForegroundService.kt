package com.mezon.mobile.home.call

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat

class CallForegroundService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val phase = intent?.getIntExtra(EXTRA_PHASE, PHASE_CONNECTED) ?: PHASE_CONNECTED
            val nm = CallNotificationManager(this)
            when (phase) {
                PHASE_RINGING -> {
                    val i = intent
                    if (i == null) {
                        Log.e("CallFgService", "PHASE_RINGING with null intent")
                        val emergency = CallNotificationManager(this).buildConnectingNotification("Incoming call")
                        startForegroundWithType(CallNotificationManager.INCOMING_CALL_NOTIFICATION_ID, emergency)
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    val callerName = i.getStringExtra(CallManager.EXTRA_CALLER_NAME) ?: "Unknown"
                    val callerAvatar = i.getStringExtra(CallManager.EXTRA_CALLER_AVATAR)
                    val callerId = i.getStringExtra(CallManager.EXTRA_CALLER_ID) ?: ""
                    val channelId = i.getStringExtra(CallManager.EXTRA_CHANNEL_ID) ?: ""
                    val offerJson = i.getStringExtra(CallManager.EXTRA_OFFER_JSON)
                    val useFsi = i.getBooleanExtra(EXTRA_USE_FULL_SCREEN_INTENT, true)
                    val n = nm.buildIncomingCallNotification(
                        callerName,
                        callerAvatar,
                        callerId,
                        channelId,
                        offerJson,
                        useFsi
                    )
                    startForegroundWithType(CallNotificationManager.INCOMING_CALL_NOTIFICATION_ID, n)
                    Handler(Looper.getMainLooper()).post {
                        tryStartIncomingCallActivityFromRingIntent(i)
                    }
                }
                PHASE_CONNECTING -> {
                    val callerName = intent?.getStringExtra(CallManager.EXTRA_CALLER_NAME) ?: "Unknown"
                    val n = nm.buildConnectingNotification(callerName)
                    startForegroundWithType(CallNotificationManager.INCOMING_CALL_NOTIFICATION_ID, n)
                }
                else -> {
                    val callerName = intent?.getStringExtra(EXTRA_CALLER_NAME) ?: "Unknown"
                    val connectedTime = intent?.getLongExtra(EXTRA_CONNECTED_TIME, 0L) ?: 0L
                    val n = nm.buildOngoingNotification(callerName, connectedTime)
                    startForegroundWithType(CallNotificationManager.ONGOING_CALL_NOTIFICATION_ID, n)
                }
            }
        } catch (e: Exception) {
            Log.e("CallFgService", "onStartCommand failed", e)
            try {
                val n = CallNotificationManager(this).buildConnectingNotification("Call")
                startForegroundWithType(CallNotificationManager.INCOMING_CALL_NOTIFICATION_ID, n)
            } catch (_: Exception) {
            }
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: Exception) {
            }
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun tryStartIncomingCallActivityFromRingIntent(ringIntent: Intent) {
        val ex = ringIntent.extras ?: return
        val act = Intent(this, IncomingCallActivity::class.java).apply {
            var f = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                f = f or INTENT_FLAG_ACTIVITY_SHOW_WHEN_LOCKED or INTENT_FLAG_ACTIVITY_TURN_SCREEN_ON
            }
            addFlags(f)
            putExtra(CallManager.EXTRA_CALLER_NAME, ex.getString(CallManager.EXTRA_CALLER_NAME))
            putExtra(CallManager.EXTRA_CALLER_ID, ex.getString(CallManager.EXTRA_CALLER_ID))
            putExtra(CallManager.EXTRA_CHANNEL_ID, ex.getString(CallManager.EXTRA_CHANNEL_ID))
            ex.getString(CallManager.EXTRA_CALLER_AVATAR)?.let {
                putExtra(CallManager.EXTRA_CALLER_AVATAR, it)
            }
            ex.getString(CallManager.EXTRA_OFFER_JSON)?.let {
                putExtra(CallManager.EXTRA_OFFER_JSON, it)
            }
        }
        try {
            startActivity(act)
        } catch (e: Exception) {
            Log.w(TAG, "start IncomingCallActivity after phone-call FGS", e)
        }
    }

    private fun startForegroundWithType(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                id,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            )
        } else {
            startForeground(id, notification)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "CallFgService"
        private const val INTENT_FLAG_ACTIVITY_SHOW_WHEN_LOCKED = 0x00080000
        private const val INTENT_FLAG_ACTIVITY_TURN_SCREEN_ON = 0x00200000

        const val EXTRA_PHASE = "call_fgs_phase"
        const val PHASE_RINGING = 1
        const val PHASE_CONNECTING = 2
        const val PHASE_CONNECTED = 3

        const val EXTRA_CALLER_NAME = "caller_name"
        const val EXTRA_CONNECTED_TIME = "connected_time"
        const val EXTRA_USE_FULL_SCREEN_INTENT = "use_full_screen_intent"

        fun startRinging(
            context: Context,
            callerName: String,
            callerAvatar: String,
            callerId: String,
            channelId: String,
            offerJson: String,
            useFullScreenIntent: Boolean
        ) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, CallForegroundService::class.java).apply {
                        putExtra(EXTRA_PHASE, PHASE_RINGING)
                        putExtra(CallManager.EXTRA_CALLER_NAME, callerName)
                        putExtra(CallManager.EXTRA_CALLER_AVATAR, callerAvatar)
                        putExtra(CallManager.EXTRA_CALLER_ID, callerId)
                        putExtra(CallManager.EXTRA_CHANNEL_ID, channelId)
                        putExtra(CallManager.EXTRA_OFFER_JSON, offerJson)
                        putExtra(EXTRA_USE_FULL_SCREEN_INTENT, useFullScreenIntent)
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "startForegroundService(ring) rejected", e)
                throw e
            }
        }

        fun startConnecting(context: Context, callerName: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CallForegroundService::class.java).apply {
                    putExtra(EXTRA_PHASE, PHASE_CONNECTING)
                    putExtra(CallManager.EXTRA_CALLER_NAME, callerName)
                }
            )
        }

        fun startConnected(context: Context, callerName: String, connectedTime: Long) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CallForegroundService::class.java).apply {
                    putExtra(EXTRA_PHASE, PHASE_CONNECTED)
                    putExtra(EXTRA_CALLER_NAME, callerName)
                    putExtra(EXTRA_CONNECTED_TIME, connectedTime)
                }
            )
        }
    }
}
