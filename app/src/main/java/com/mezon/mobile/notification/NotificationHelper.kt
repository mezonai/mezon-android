package com.mezon.mobile.notification

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mezon.mobile.MainActivity
import com.mezon.mobile.R
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.home.DialogsController
import com.mezon.mobile.home.clans.ChannelController
import com.mezon.mobile.network.CHANNEL_TYPE_CHANNEL
import com.mezon.mobile.network.CHANNEL_TYPE_DM
import com.mezon.mobile.ui.cells.ToastOverlay
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val channelController: ChannelController,
    private val dialogsController: dagger.Lazy<DialogsController>,
    @ApplicationScope private val appScope: CoroutineScope
) {
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val MAX_NOTI_DELAY: Long = 3000L
        private const val MAX_BODY_LEN = 120
        const val GROUP_MESSAGES = "mezon_messages"
        const val CHANNEL_MESSAGES = "mezon_channel_messages"
        const val CHANNEL_DM = "mezon_dm"
        const val CHANNEL_SYSTEM = "mezon_system"

        private fun truncateBody(text: String): String {
            val single = text.replace('\n', ' ').replace('\r', ' ').trim()
            return if (single.length > MAX_BODY_LEN) single.substring(0, MAX_BODY_LEN) + "…" else single
        }

        const val ACTION_OPEN_CHAT = "com.mezon.openchat"
        const val ACTION_OPEN_FRIEND_REQUESTS = "com.mezon.open_friend_requests"

        const val EXTRA_CHANNEL_ID = "notification_channel_id"
        const val EXTRA_CLAN_ID = "notification_clan_id"
        const val EXTRA_CHANNEL_NAME = "notification_channel_name"
        const val EXTRA_CHANNEL_TYPE = "notification_channel_type"
        const val EXTRA_DM_ID = "notification_dm_id"
        const val EXTRA_FRIEND_REQUEST = "notification_friend_request"
        const val EXTRA_FRIEND_REQUEST_CONSUMED = "notification_friend_request_consumed"

        fun isFriendRequestNotification(title: String?, body: String?, data: Map<String, String> = emptyMap()): Boolean {
            val values = ArrayList<String>()
            title?.takeIf { it.isNotBlank() }?.let(values::add)
            body?.takeIf { it.isNotBlank() }?.let(values::add)
            for ((key, value) in data) {
                val normalizedKey = key.lowercase()
                if (normalizedKey in FRIEND_REQUEST_SIGNAL_KEYS || FRIEND_REQUEST_SIGNAL_KEYS.any { normalizedKey.contains(it) }) {
                    value.takeIf { it.isNotBlank() }?.let(values::add)
                }
            }
            return values.any { value ->
                val normalized = value.lowercase()
                FRIEND_REQUEST_PATTERNS.any { pattern -> normalized.contains(pattern) }
            }
        }

        fun isFriendRequestNotificationExtras(extras: Bundle?): Boolean {
            if (extras == null || extras.getBoolean(EXTRA_FRIEND_REQUEST_CONSUMED, false)) return false
            if (extras.getBoolean(EXTRA_FRIEND_REQUEST, false)) return true

            val data = LinkedHashMap<String, String>()
            collectBundleStrings(extras, data)
            val title = data["title"]
                ?: data["gcm.notification.title"]
                ?: data["google.c.a.c_l"]
            val body = data["body"]
                ?: data["message"]
                ?: data["gcm.notification.body"]
            return isFriendRequestNotification(title, body, data)
        }

        private fun collectBundleStrings(bundle: Bundle, out: MutableMap<String, String>, prefix: String = "") {
            for (key in bundle.keySet()) {
                val value = bundle.get(key) ?: continue
                val outKey = if (prefix.isEmpty()) key else "$prefix.$key"
                when (value) {
                    is Bundle -> collectBundleStrings(value, out, outKey)
                    is String -> out[outKey] = value
                    is Number, is Boolean -> out[outKey] = value.toString()
                }
            }
        }

        private val FRIEND_REQUEST_SIGNAL_KEYS = setOf(
            "type",
            "notification_type",
            "notificationtype",
            "event",
            "event_type",
            "eventtype",
            "action",
            "category",
            "category_name",
            "categoryname",
            "subject",
            "title",
            "body",
            "message",
            "content",
            "screen",
            "route",
            "link"
        )

        private val FRIEND_REQUEST_PATTERNS = listOf(
            "friend_request",
            "friend-request",
            "friend request",
            "friend.invite",
            "add_friend",
            "add-friend",
            "addfriend",
            "add friend",
            "request_friend",
            "wants to add you",
            "wants to be your friend",
            "sent you a friend request",
            "add you as a friend",
            "loi moi ket ban",
            "muon ket ban",
            "lời mời kết bạn",
            "muốn kết bạn"
        )
    }

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
         if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

         val group = NotificationChannelGroup(
             GROUP_MESSAGES,
             context.getString(R.string.app_name)
         )
         notificationManager.createNotificationChannelGroup(group)

         val messageChannel = NotificationChannel(
             CHANNEL_MESSAGES,
             context.getString(R.string.notification_channel_messages),
             NotificationManager.IMPORTANCE_HIGH
         ).apply {
             description = context.getString(R.string.notification_channel_messages_desc)
             setGroup(GROUP_MESSAGES)
             enableVibration(true)
             vibrationPattern = longArrayOf(300, 500, 300, 500)
         }

         val dmChannel = NotificationChannel(
             CHANNEL_DM,
             context.getString(R.string.notification_channel_dm),
             NotificationManager.IMPORTANCE_HIGH
         ).apply {
             description = context.getString(R.string.notification_channel_dm_desc)
             setGroup(GROUP_MESSAGES)
             enableVibration(true)
             vibrationPattern = longArrayOf(300, 500, 300, 500)
         }

         val systemChannel = NotificationChannel(
             CHANNEL_SYSTEM,
             context.getString(R.string.notification_channel_system),
             NotificationManager.IMPORTANCE_DEFAULT
         ).apply {
             description = context.getString(R.string.notification_channel_system_desc)
         }

         notificationManager.createNotificationChannels(
             listOf(messageChannel, dmChannel, systemChannel)
         )
    }

    fun showMessageNotification(
        title: String,
        body: String,
        channelId: Long? = null,
        clanId: Long? = null,
        channelName: String = "",
        channelType: Int? = null
    ) {
        val body = truncateBody(body)
        appScope.launch {
            val computedChannelName = channelName.ifEmpty {
                if (channelId != null && clanId != null) {
                    (withTimeoutOrNull(MAX_NOTI_DELAY) {
                        channelController.findOrFetchChannelLabel(channelId, clanId)
                    } ?: title).ifEmpty { title }
                } else title
            }
            val notificationId = channelId?.toInt() ?: System.nanoTime().toInt().and(0x7FFFFFFF)
            val intent = Intent(context, MainActivity::class.java).apply {
                action = ACTION_OPEN_CHAT
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                if (channelId != null) putExtra(EXTRA_CHANNEL_ID, channelId)
                if (clanId != null) putExtra(EXTRA_CLAN_ID, clanId)
                if (computedChannelName.isNotEmpty()) putExtra(EXTRA_CHANNEL_NAME, computedChannelName)
                if (channelType != null) putExtra(EXTRA_CHANNEL_TYPE, channelType)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                notificationId,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notifChannel = if (clanId == 0L) CHANNEL_DM else CHANNEL_MESSAGES
            val notification = NotificationCompat.Builder(context, notifChannel)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(ContextCompat.getColor(context, R.color.notification_color))
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setGroup(GROUP_MESSAGES)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVibrate(longArrayOf(300, 500, 300, 500))
                .build()
            notificationManager.notify(notificationId, notification)
        }
    }

    fun showDmNotification(
        title: String,
        body: String,
        dmChannelId: Long
    ) {
        val body = truncateBody(body)
        appScope.launch {
            val dmDialog = dialogsController.get().getDialog(dmChannelId)
            val dmName = dmDialog?.let { dm ->
                dm.displayName.ifEmpty { dm.label }
            } ?: title
            val dmType = dmDialog?.type?.takeIf { it != 0 } ?: CHANNEL_TYPE_DM
            val intent = Intent(context, MainActivity::class.java).apply {
                action = ACTION_OPEN_CHAT
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_DM_ID, dmChannelId)
                if (dmName.isNotEmpty()) putExtra(EXTRA_CHANNEL_NAME, dmName)
                putExtra(EXTRA_CHANNEL_TYPE, dmType)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                dmChannelId.toInt(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_DM)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(ContextCompat.getColor(context, R.color.notification_color))
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setGroup(GROUP_MESSAGES)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVibrate(longArrayOf(300, 500, 300, 500))
                .build()
            notificationManager.notify(dmChannelId.toInt(), notification)
        }
    }

    fun showFriendRequestNotification(title: String, body: String) {
        val truncatedBody = truncateBody(body)
        val notificationId = System.nanoTime().toInt().and(0x7FFFFFFF)
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_FRIEND_REQUESTS
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_FRIEND_REQUEST, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_DM)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.notification_color))
            .setContentTitle(title)
            .setContentText(truncatedBody)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setGroup(GROUP_MESSAGES)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setVibrate(longArrayOf(300, 500, 300, 500))
            .build()
        notificationManager.notify(notificationId, notification)
    }

    fun cancelNotification(notificationId: Int) {
        notificationManager.cancel(notificationId)
    }

    fun cancelAllNotifications() {
        notificationManager.cancelAll()
    }

    fun showInAppToast(
        title: String,
        body: String,
        channelId: Long = 0L,
        clanId: Long = 0L,
        dmId: Long = 0L,
        friendRequest: Boolean = false
    ) {
        val truncatedBody = truncateBody(body)
        appScope.launch {
            val dmDialog = if (dmId != 0L) dialogsController.get().getDialog(dmId) else null
            withContext(Dispatchers.Main) {
                val activity = MainActivity.instance ?: return@withContext
                val onTap: (() -> Unit)? = when {
                    friendRequest -> {
                        {
                            activity.openFriendRequestsFromNotification()
                        }
                    }
                    dmId != 0L -> {
                        val dmType = dmDialog?.type?.takeIf { it != 0 } ?: CHANNEL_TYPE_DM
                        val dmName = dmDialog?.let { dm ->
                            dm.displayName.ifEmpty { dm.label }
                        } ?: title
                        {
                            activity.openChat(dmId, dmName, 0L, dmType, fromNotification = true)
                        }
                    }
                    clanId != 0L && channelId != 0L -> {
                        {
                            appScope.launch {
                                val channelName = (withTimeoutOrNull(MAX_NOTI_DELAY) {
                                    channelController.findOrFetchChannelLabel(channelId, clanId)
                                } ?: title).ifEmpty { title }
                                withContext(Dispatchers.Main) {
                                    activity.openChat(
                                        channelId,
                                        channelName,
                                        clanId,
                                        CHANNEL_TYPE_CHANNEL,
                                        fromNotification = true
                                    )
                                }
                            }
                        }
                    }
                    else -> null
                }
                activity.drawerLayoutContainer.post {
                    ToastOverlay.showInAppNotification(
                        activity = activity,
                        parent = activity.drawerLayoutContainer,
                        title = title,
                        body = truncatedBody,
                        onTap = onTap
                    )
                }
            }
        }
    }
}
