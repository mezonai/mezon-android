package com.mezon.mobile.home.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.mezon.mobile.R

class CallNotificationManager(private val context: Context) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createCallChannels()
    }

    private fun createCallChannels() {
        val incomingChannel = NotificationChannel(
            CHANNEL_INCOMING_CALL,
            "Incoming Calls",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Incoming voice and video calls"
            setBypassDnd(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(null, null)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 1000, 500, 1000, 500, 1000)
        }

        val ongoingChannel = NotificationChannel(
            CHANNEL_ONGOING_CALL,
            "Ongoing Calls",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Active call indicator"
            setSound(null, null)
            enableVibration(false)
        }

        notificationManager.createNotificationChannels(listOf(incomingChannel, ongoingChannel))
    }

    private fun baseIncomingCallActivityIntent(extra: Intent.() -> Unit = {}): Intent {
        return Intent(context, IncomingCallActivity::class.java).apply {
            var flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                flags = flags or INTENT_FLAG_ACTIVITY_SHOW_WHEN_LOCKED or INTENT_FLAG_ACTIVITY_TURN_SCREEN_ON
            }
            addFlags(flags)
            extra()
        }
    }

    private fun createIncomingCallNotification(
        callerName: String,
        callerAvatar: String?,
        callerId: String,
        channelId: String,
        offerJson: String? = null,
        useFullScreenIntent: Boolean = true
    ): Notification {
        val commonExtras = Intent().apply {
            putExtra(CallManager.EXTRA_CALLER_NAME, callerName)
            putExtra(CallManager.EXTRA_CALLER_ID, callerId)
            putExtra(CallManager.EXTRA_CHANNEL_ID, channelId)
            if (!callerAvatar.isNullOrBlank()) {
                putExtra(CallManager.EXTRA_CALLER_AVATAR, callerAvatar)
            }
            if (!offerJson.isNullOrBlank()) {
                putExtra(CallManager.EXTRA_OFFER_JSON, offerJson)
            }
        }
        val fullScreenIntent = PendingIntent.getActivity(
            context, 2,
            baseIncomingCallActivityIntent { putExtras(commonExtras) },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return if (Build.VERSION.SDK_INT >= 31) {
            val builder = Notification.Builder(context, CHANNEL_INCOMING_CALL)
                .setContentTitle("Incoming Call")
                .setContentText("$callerName is calling...")
                .setContentIntent(fullScreenIntent)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_CALL)
                .setTimeoutAfter(30_000)
                .setColor(0xFF7029C6.toInt())
                .setSmallIcon(R.drawable.ic_notification)
            if (useFullScreenIntent) {
                builder.setFullScreenIntent(fullScreenIntent, true)
            }
            builder.build()
        } else {
            val builder = NotificationCompat.Builder(context, CHANNEL_INCOMING_CALL)
                .setContentTitle("Incoming Call")
                .setContentText("$callerName is calling...")
                .setContentIntent(fullScreenIntent)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setTimeoutAfter(30_000)
                .setColor(0xFF7029C6.toInt())
                .setSmallIcon(R.drawable.ic_notification)
            if (useFullScreenIntent) {
                builder.setFullScreenIntent(fullScreenIntent, true)
            }
            builder.build()
        }
    }

    fun showIncomingCallNotification(
        callerName: String,
        callerAvatar: String?,
        callerId: String,
        channelId: String,
        offerJson: String? = null,
        useFullScreenIntent: Boolean = true
    ) {
        notificationManager.notify(
            INCOMING_CALL_NOTIFICATION_ID,
            createIncomingCallNotification(
                callerName,
                callerAvatar,
                callerId,
                channelId,
                offerJson,
                useFullScreenIntent
            )
        )
    }

    fun buildIncomingCallNotification(
        callerName: String,
        callerAvatar: String?,
        callerId: String,
        channelId: String,
        offerJson: String? = null,
        useFullScreenIntent: Boolean = true
    ): Notification =
        createIncomingCallNotification(
            callerName,
            callerAvatar,
            callerId,
            channelId,
            offerJson,
            useFullScreenIntent
        )

    fun buildConnectingNotification(callerName: String): Notification {
        val openIntent = PendingIntent.getActivity(
            context, 8,
            baseIncomingCallActivityIntent(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(context, CHANNEL_INCOMING_CALL)
            .setContentTitle("Connecting...")
            .setContentText(callerName)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(openIntent)
            .setColor(0xFF7029C6.toInt())
            .build()
    }

    fun buildOngoingNotification(callerName: String, connectedTime: Long): Notification {
        val endIntent = PendingIntent.getBroadcast(
            context, 3,
            Intent(context, CallActionReceiver::class.java).setAction(CallActionReceiver.ACTION_END),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val openCallIntent = PendingIntent.getActivity(
            context, 4,
            baseIncomingCallActivityIntent(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(context, CHANNEL_ONGOING_CALL)
            .setContentTitle("Call with $callerName")
            .setContentText("Ongoing call")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setUsesChronometer(true)
            .setWhen(System.currentTimeMillis() - (SystemClock.elapsedRealtime() - connectedTime))
            .addAction(0, "End call", endIntent)
            .setContentIntent(openCallIntent)
            .setColor(0xFF7029C6.toInt())
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()
    }

    fun dismissIncomingNotification() {
        notificationManager.cancel(INCOMING_CALL_NOTIFICATION_ID)
    }

    fun dismissOngoingNotification() {
        notificationManager.cancel(ONGOING_CALL_NOTIFICATION_ID)
    }

    companion object {
        private const val INTENT_FLAG_ACTIVITY_SHOW_WHEN_LOCKED = 0x00080000
        private const val INTENT_FLAG_ACTIVITY_TURN_SCREEN_ON = 0x00200000

        const val CHANNEL_INCOMING_CALL = "incoming_call"
        const val CHANNEL_ONGOING_CALL = "ongoing_call"
        const val INCOMING_CALL_NOTIFICATION_ID = 9001
        const val ONGOING_CALL_NOTIFICATION_ID = 9002
        const val EXTRA_OPEN_CALL = "open_call_fragment"
    }
}
