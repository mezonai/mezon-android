package com.mezon.mobile.home.call

import android.Manifest
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.telecom.DisconnectCause
import android.util.Log
import androidx.core.content.ContextCompat

class CallForegroundService : Service() {

    private val escalationHandler = Handler(Looper.getMainLooper())
    private var escalationRunnable: Runnable? = null
    private var ringQuiet = false
    @Volatile private var localCallId: String? = null
    @Volatile private var notificationGeneration = 0L
    private var pendingDeadline = 0L
    private var pendingTimeout: Runnable? = null

    override fun onDestroy() {
        cancelEscalation()
        cancelPendingTimeout()
        notificationGeneration++
        endOwnedCall()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val currentId = CallController.instance?.currentCallInfo()?.localCallId
        val removedId = rootIntent?.getStringExtra(CallManager.EXTRA_LOCAL_CALL_ID)
        if ((currentId != null && currentId != localCallId) || (removedId != null && removedId != localCallId)) {
            super.onTaskRemoved(rootIntent)
            return
        }
        val state = CallController.instance?.callState
        if (state is CallState.Connecting || state is CallState.Outgoing || state is CallState.Idle || state == null) {
            endOwnedCall()
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun endOwnedCall() {
        val owner = localCallId
        val controller = CallController.instance
        if (owner != null && controller?.currentCallInfo()?.localCallId == owner) {
            controller.hangup()
        }
        if (owner != null) CallTelecomBridge.instance?.endIfOwned(owner, DisconnectCause.CANCELED)
        if (CallController.instance?.currentCallInfo() == null) {
            CallTelecomBridge.instance?.endOrphanedConnection(DisconnectCause.CANCELED)
            val notifications = CallNotificationManager(this)
            notifications.dismissIncomingNotification()
            notifications.dismissOngoingNotification()
            getSharedPreferences("call_data", Context.MODE_PRIVATE).edit().remove("incoming_call").apply()
        }
    }

    private fun cancelPendingTimeout() {
        pendingTimeout?.let { escalationHandler.removeCallbacks(it) }
        pendingTimeout = null
    }

    private fun schedulePendingTimeout(phase: Int) {
        cancelPendingTimeout()
        if (phase == PHASE_CONNECTED) {
            pendingDeadline = 0L
            return
        }
        if (pendingDeadline == 0L) pendingDeadline = SystemClock.elapsedRealtime() + CONNECT_TIMEOUT_MS
        val owner = localCallId ?: return
        val timeout = object : Runnable {
            override fun run() {
                if (localCallId != owner) return
                val controller = CallController.instance
                if (controller == null || controller.currentCallInfo()?.localCallId != owner) {
                    stopSelf()
                    return
                }
                if (controller.callState is CallState.Connected) return
                val remaining = controller.connectingTimeoutRemainingMs()
                    ?: (pendingDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                if (remaining > 0L) {
                    escalationHandler.postDelayed(this, remaining)
                    return
                }
                controller.timeoutConnectingCall()
                endOwnedCall()
                stopSelf()
            }
        }
        pendingTimeout = timeout
        escalationHandler.postDelayed(timeout, CallController.instance?.connectingTimeoutRemainingMs()
            ?: (pendingDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
    }

    private fun cancelEscalation() {
        escalationRunnable?.let { escalationHandler.removeCallbacks(it) }
        escalationRunnable = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            Log.w(TAG, "onStartCommand with null intent, stopping")
            endOwnedCall()
            stopSelf()
            return START_NOT_STICKY
        }
        val owner = intent.getStringExtra(CallManager.EXTRA_LOCAL_CALL_ID)
        val controller = CallController.instance
        if (owner == null || controller?.currentCallInfo()?.localCallId != owner) {
            if (localCallId == null || localCallId == owner) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (localCallId != owner) pendingDeadline = 0L
        localCallId = owner
        notificationGeneration++
        try {
            val phase = when (controller?.callState) {
                is CallState.Incoming -> PHASE_RINGING
                is CallState.Outgoing -> PHASE_OUTGOING
                is CallState.Connecting -> PHASE_CONNECTING
                is CallState.Connected -> PHASE_CONNECTED
                else -> {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
            }
            intent.putExtra(EXTRA_PHASE, phase)
            schedulePendingTimeout(phase)
            val nm = CallNotificationManager(this)
            when (phase) {
                PHASE_RINGING -> startRingingPhase(intent, nm)
                else -> startInCallPhase(intent, nm, phase)
            }
        } catch (e: Exception) {
            Log.e(TAG, "onStartCommand failed, falling back to a plain call notification", e)
            startFallbackForeground(intent)
        }
        return START_NOT_STICKY
    }

    private fun startFallbackForeground(intent: Intent) {
        val phase = intent.getIntExtra(EXTRA_PHASE, PHASE_CONNECTED)
        val incoming = phase == PHASE_RINGING
        val callerName = intent.getStringExtra(CallManager.EXTRA_CALLER_NAME) ?: "Unknown"
        val id = if (incoming) {
            CallNotificationManager.INCOMING_CALL_NOTIFICATION_ID
        } else {
            CallNotificationManager.ONGOING_CALL_NOTIFICATION_ID
        }
        try {
            startForegroundWithType(
                id,
                CallNotificationManager(this).buildFallbackCallNotification(callerName, incoming),
                PHASE_RINGING
            )
            if (incoming) {
                Handler(Looper.getMainLooper()).post {
                    tryStartIncomingCallActivityFromRingIntent(intent)
                }
            }
            return
        } catch (e: Exception) {
            Log.e(TAG, "fallback call notification failed", e)
        }
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        stopSelf()
    }

    private fun startRingingPhase(intent: Intent, nm: CallNotificationManager) {
        val callerName = intent.getStringExtra(CallManager.EXTRA_CALLER_NAME) ?: "Unknown"
        val callerAvatar = intent.getStringExtra(CallManager.EXTRA_CALLER_AVATAR)
        val callerId = intent.getStringExtra(CallManager.EXTRA_CALLER_ID) ?: ""
        val channelId = intent.getStringExtra(CallManager.EXTRA_CHANNEL_ID) ?: ""
        val offerJson = intent.getStringExtra(CallManager.EXTRA_OFFER_JSON)
        val useFsi = intent.getBooleanExtra(EXTRA_USE_FULL_SCREEN_INTENT, true)
        val deviceLocked = intent.getBooleanExtra(EXTRA_DEVICE_LOCKED, false)

        val systemOpensCallScreen = useFsi && deviceLocked
        ringQuiet = !systemOpensCallScreen

        val build = {
            nm.buildIncomingCallNotification(
                callerName, callerAvatar, callerId, channelId, offerJson, useFsi, ringQuiet
            )
        }
        val generation = notificationGeneration
        startForegroundWithType(
            CallNotificationManager.INCOMING_CALL_NOTIFICATION_ID,
            build(),
            PHASE_RINGING
        )
        nm.warmAvatarThenRepost(
            callerAvatar,
            CallNotificationManager.INCOMING_CALL_NOTIFICATION_ID,
            build,
            isCurrent = { generation == notificationGeneration && CallController.instance?.currentCallInfo()?.localCallId == localCallId && CallController.instance?.callState is CallState.Incoming }
        )

        if (systemOpensCallScreen) return

        Handler(Looper.getMainLooper()).post {
            tryStartIncomingCallActivityFromRingIntent(intent)
        }
        scheduleHeadsUpEscalation(build)
    }

    private fun scheduleHeadsUpEscalation(build: () -> Notification) {
        cancelEscalation()
        val owner = localCallId
        val generation = notificationGeneration
        val runnable = Runnable {
            escalationRunnable = null
            if (generation != notificationGeneration || CallController.instance?.currentCallInfo()?.localCallId != owner) return@Runnable
            if (IncomingCallActivity.isIncomingCallUiShown()) return@Runnable
            if (CallController.instance?.callState !is CallState.Incoming) return@Runnable
            Log.w(TAG, "call screen never appeared, escalating ring to a heads-up notification")
            ringQuiet = false
            try {
                startForegroundWithType(
                    CallNotificationManager.INCOMING_CALL_NOTIFICATION_ID,
                    build(),
                    PHASE_RINGING
                )
            } catch (e: Exception) {
                Log.e(TAG, "heads-up escalation failed", e)
            }
        }
        escalationRunnable = runnable
        escalationHandler.postDelayed(runnable, CALL_SCREEN_ESCALATION_MS)
    }

    private fun startInCallPhase(intent: Intent, nm: CallNotificationManager, phase: Int) {
        cancelEscalation()
        val callerName = intent.getStringExtra(CallManager.EXTRA_CALLER_NAME) ?: "Unknown"
        val callerAvatar = intent.getStringExtra(CallManager.EXTRA_CALLER_AVATAR)
        val callerId = intent.getStringExtra(CallManager.EXTRA_CALLER_ID) ?: ""
        val isVideo = intent.getBooleanExtra(CallManager.EXTRA_IS_VIDEO_CALL, false)
        val connectedTime =
            if (phase == PHASE_CONNECTED) (CallController.instance?.callState as? CallState.Connected)?.connectedTime
                ?: intent.getLongExtra(EXTRA_CONNECTED_TIME, 0L) else 0L

        val build = {
            nm.buildInCallNotification(callerName, callerAvatar, callerId, isVideo, connectedTime)
        }
        val generation = notificationGeneration
        startForegroundWithType(
            CallNotificationManager.ONGOING_CALL_NOTIFICATION_ID,
            build(),
            phase
        )
        nm.dismissIncomingNotification()
        nm.warmAvatarThenRepost(
            callerAvatar,
            CallNotificationManager.ONGOING_CALL_NOTIFICATION_ID,
            build,
            isCurrent = { generation == notificationGeneration && CallController.instance?.currentCallInfo()?.localCallId == localCallId }
        )
    }

    private fun tryStartIncomingCallActivityFromRingIntent(ringIntent: Intent) {
        if (ringIntent.getStringExtra(CallManager.EXTRA_LOCAL_CALL_ID) != CallController.instance?.currentCallInfo()?.localCallId ||
            CallController.instance?.callState !is CallState.Incoming) return
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
            putExtra(CallManager.EXTRA_LOCAL_CALL_ID, ex.getString(CallManager.EXTRA_LOCAL_CALL_ID))
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

    private fun startForegroundWithType(id: Int, notification: Notification, phase: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(id, notification, foregroundServiceTypeMask(phase))
        } else {
            startForeground(id, notification)
        }
    }

    private fun foregroundServiceTypeMask(phase: Int): Int {
        var mask = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        if (phase == PHASE_RINGING) return mask
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return mask
        if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
            mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (hasPermission(Manifest.permission.CAMERA)) {
            mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        return mask
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "CallFgService"
        private const val INTENT_FLAG_ACTIVITY_SHOW_WHEN_LOCKED = 0x00080000
        private const val INTENT_FLAG_ACTIVITY_TURN_SCREEN_ON = 0x00200000

        const val EXTRA_PHASE = "call_fgs_phase"
        const val PHASE_RINGING = 1
        const val PHASE_CONNECTING = 2
        const val PHASE_CONNECTED = 3
        const val PHASE_OUTGOING = 4

        const val EXTRA_CONNECTED_TIME = "connected_time"
        const val EXTRA_USE_FULL_SCREEN_INTENT = "use_full_screen_intent"
        const val EXTRA_DEVICE_LOCKED = "device_locked"

        private const val CALL_SCREEN_ESCALATION_MS = 800L

        fun startRinging(
            context: Context,
            callerName: String,
            callerAvatar: String,
            callerId: String,
            channelId: String,
            offerJson: String,
            useFullScreenIntent: Boolean,
            deviceLocked: Boolean,
            localCallId: String
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
                        putExtra(CallManager.EXTRA_LOCAL_CALL_ID, localCallId)
                        putExtra(CallManager.EXTRA_OFFER_JSON, offerJson)
                        putExtra(EXTRA_USE_FULL_SCREEN_INTENT, useFullScreenIntent)
                        putExtra(EXTRA_DEVICE_LOCKED, deviceLocked)
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "startForegroundService(ring) rejected", e)
                throw e
            }
        }

        fun startOutgoing(context: Context, callInfo: CallInfo) {
            startInCall(context, PHASE_OUTGOING, callInfo, 0L)
        }

        fun startConnecting(context: Context, callInfo: CallInfo) {
            startInCall(context, PHASE_CONNECTING, callInfo, 0L)
        }

        fun startConnected(context: Context, callInfo: CallInfo, connectedTime: Long) {
            startInCall(context, PHASE_CONNECTED, callInfo, connectedTime)
        }

        private fun startInCall(
            context: Context,
            phase: Int,
            callInfo: CallInfo,
            connectedTime: Long
        ) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CallForegroundService::class.java).apply {
                    putExtra(EXTRA_PHASE, phase)
                    putExtra(CallManager.EXTRA_CALLER_NAME, callInfo.peerName)
                    putExtra(CallManager.EXTRA_CALLER_AVATAR, callInfo.peerAvatar ?: "")
                    putExtra(CallManager.EXTRA_CALLER_ID, callInfo.peerId.toString())
                    putExtra(CallManager.EXTRA_CHANNEL_ID, callInfo.channelId.toString())
                    putExtra(CallManager.EXTRA_LOCAL_CALL_ID, callInfo.localCallId)
                    putExtra(CallManager.EXTRA_IS_VIDEO_CALL, callInfo.isVideo)
                    putExtra(EXTRA_CONNECTED_TIME, connectedTime)
                }
            )
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, CallForegroundService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
