package com.mezon.mobile.home.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import com.mezon.mobile.MainActivity
import com.mezon.mobile.R
import com.mezon.mobile.core.AvatarDrawable
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.util.avatarImgproxyUrl

private const val TAG = "CallNotificationManager"

class CallNotificationManager(private val context: Context) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createCallChannels()
    }

    private fun createCallChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

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

        val incomingQuietChannel = NotificationChannel(
            CHANNEL_INCOMING_CALL_QUIET,
            "Incoming Calls (call screen showing)",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Incoming call while the full call screen is already on screen"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(null, null)
            enableVibration(false)
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

        notificationManager.createNotificationChannels(
            listOf(incomingChannel, incomingQuietChannel, ongoingChannel)
        )
    }

    private val avatarSizePx: Int
        get() = (AVATAR_ICON_DP * context.resources.displayMetrics.density).toInt()

    private fun letterAvatarBitmap(callerName: String, callerId: String): Bitmap {
        val size = avatarSizePx.coerceAtLeast(1)
        val drawable = AvatarDrawable().apply {
            cornerRadius = 0f
            setInfo(callerId.toLongOrNull() ?: 0L, callerName)
            setBounds(0, 0, size, size)
        }
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    private fun personFor(callerName: String, callerAvatar: String?, callerId: String): Person {
        val cached = callerAvatar?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { avatarCache[it] }
            ?.takeIf { !it.isRecycled }
        val bitmap = cached ?: letterAvatarBitmap(callerName, callerId)
        return Person.Builder()
            .setName(callerName)
            .setKey(callerId)
            .setIcon(IconCompat.createWithAdaptiveBitmap(bitmap))
            .setImportant(true)
            .build()
    }

    fun warmAvatarThenRepost(
        callerAvatar: String?,
        notificationId: Int,
        rebuild: () -> Notification
    ) {
        val url = callerAvatar?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (avatarCache.containsKey(url)) return
        val sizePx = avatarSizePx.coerceAtLeast(1)
        try {
            MezonImageLoader.getInstance(context).load(
                avatarImgproxyUrl(url, sizePx), sizePx, sizePx,
                onSuccess = { bitmap ->
                    avatarCache[url] = bitmap
                    try {
                        notificationManager.notify(notificationId, rebuild())
                    } catch (e: Exception) {
                        Log.w(TAG, "avatar repost failed", e)
                    }
                },
                onError = { }
            )
        } catch (e: Exception) {
            Log.w(TAG, "avatar load could not start", e)
        }
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

    private fun openCallScreenIntent(): PendingIntent = PendingIntent.getActivity(
        context, REQ_OPEN_CALL,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_CALL, true)
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun hangUpIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, REQ_END,
        Intent(context, CallActionReceiver::class.java).setAction(CallActionReceiver.ACTION_END),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun declineIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, REQ_DECLINE,
        Intent(context, CallActionReceiver::class.java).setAction(CallActionReceiver.ACTION_DECLINE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun buildIncomingCallNotification(
        callerName: String,
        callerAvatar: String?,
        callerId: String,
        channelId: String,
        offerJson: String? = null,
        useFullScreenIntent: Boolean = true,
        quiet: Boolean = false
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
            context, REQ_FULL_SCREEN,
            baseIncomingCallActivityIntent { putExtras(commonExtras) },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val answerIntent = PendingIntent.getActivity(
            context, REQ_ANSWER,
            baseIncomingCallActivityIntent {
                putExtras(commonExtras)
                putExtra(CallManager.EXTRA_AUTO_ANSWER, true)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val style = NotificationCompat.CallStyle.forIncomingCall(
            personFor(callerName, callerAvatar, callerId),
            declineIntent(),
            answerIntent
        )

        val builder = NotificationCompat.Builder(
            context,
            if (quiet) CHANNEL_INCOMING_CALL_QUIET else CHANNEL_INCOMING_CALL
        )
            .setStyle(style)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(fullScreenIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(
                if (quiet) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_MAX
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setTimeoutAfter(30_000)
            .setColor(CALL_ACCENT_COLOR)
            .setColorized(true)

        if (!quiet && useFullScreenIntent) {
            builder.setFullScreenIntent(fullScreenIntent, true)
        }
        return builder.build()
    }

    fun showIncomingCallNotification(
        callerName: String,
        callerAvatar: String?,
        callerId: String,
        channelId: String,
        offerJson: String? = null,
        useFullScreenIntent: Boolean = true
    ) {
        val build = {
            buildIncomingCallNotification(
                callerName, callerAvatar, callerId, channelId, offerJson, useFullScreenIntent
            )
        }
        notificationManager.notify(INCOMING_CALL_NOTIFICATION_ID, build())
        warmAvatarThenRepost(callerAvatar, INCOMING_CALL_NOTIFICATION_ID, build)
    }

    fun buildInCallNotification(
        callerName: String,
        callerAvatar: String?,
        callerId: String,
        isVideo: Boolean,
        connectedTime: Long
    ): Notification {
        val style = NotificationCompat.CallStyle
            .forOngoingCall(personFor(callerName, callerAvatar, callerId), hangUpIntent())
            .setIsVideo(isVideo)

        val builder = NotificationCompat.Builder(context, CHANNEL_ONGOING_CALL)
            .setStyle(style)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openCallScreenIntent())
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(CALL_ACCENT_COLOR)
            .setColorized(true)

        if (connectedTime > 0L) {
            builder.setUsesChronometer(true)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis() - (SystemClock.elapsedRealtime() - connectedTime))
        } else {
            builder.setUsesChronometer(false).setShowWhen(false)
        }
        return builder.build()
    }

    fun buildFallbackCallNotification(callerName: String, incoming: Boolean): Notification {
        val builder = NotificationCompat.Builder(
            context,
            if (incoming) CHANNEL_INCOMING_CALL else CHANNEL_ONGOING_CALL
        )
            .setContentTitle(if (incoming) "Incoming call" else "Ongoing call")
            .setContentText(callerName)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(CALL_ACCENT_COLOR)

        if (incoming) {
            val open = PendingIntent.getActivity(
                context, REQ_FALLBACK,
                baseIncomingCallActivityIntent(),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.setContentIntent(open)
                .setFullScreenIntent(open, true)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .addAction(0, "Decline", declineIntent())
        } else {
            builder.setContentIntent(openCallScreenIntent())
                .addAction(0, "End call", hangUpIntent())
        }
        return builder.build()
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

        private const val REQ_FULL_SCREEN = 2
        private const val REQ_END = 3
        private const val REQ_OPEN_CALL = 4
        private const val REQ_ANSWER = 5
        private const val REQ_DECLINE = 6
        private const val REQ_FALLBACK = 7

        private const val AVATAR_ICON_DP = 64
        private val CALL_ACCENT_COLOR = 0xFF7029C6.toInt()

        private val avatarCache = HashMap<String, Bitmap>(4)

        const val CHANNEL_INCOMING_CALL = "incoming_call"
        const val CHANNEL_INCOMING_CALL_QUIET = "incoming_call_quiet"
        const val CHANNEL_ONGOING_CALL = "ongoing_call"
        const val INCOMING_CALL_NOTIFICATION_ID = 9001
        const val ONGOING_CALL_NOTIFICATION_ID = 9002
        const val EXTRA_OPEN_CALL = "open_call_fragment"
    }
}
