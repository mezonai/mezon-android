package com.mezon.mobile.notification

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mezon.mobile.MainActivity
import com.mezon.mobile.R
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.home.ChatController
import com.mezon.mobile.home.DialogsController
import com.mezon.mobile.home.clans.ChannelController
import com.mezon.mobile.home.clans.ClansController
import com.mezon.mobile.network.CHANNEL_TYPE_CHANNEL
import com.mezon.mobile.network.CHANNEL_TYPE_DM
import com.mezon.mobile.ui.cells.ToastOverlay
import dagger.Lazy
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
    private val notificationCenter: NotificationCenter,
    private val channelController: ChannelController,
    private val clansController: ClansController,
    private val dialogsController: Lazy<DialogsController>,
    private val chatController: Lazy<ChatController>,
    @ApplicationScope private val appScope: CoroutineScope
) {
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val MAX_NOTI_DELAY: Long = 3000L
        const val GROUP_MESSAGES = "mezon_messages"
        const val CHANNEL_MESSAGES = "mezon_channel_messages"
        const val CHANNEL_DM = "mezon_dm"
        const val CHANNEL_SYSTEM = "mezon_system"

        const val ACTION_OPEN_CHAT = "com.mezon.openchat"

        const val EXTRA_CHANNEL_ID = "notification_channel_id"
        const val EXTRA_CLAN_ID = "notification_clan_id"
        const val EXTRA_CHANNEL_NAME = "notification_channel_name"
        const val EXTRA_CHANNEL_TYPE = "notification_channel_type"
        const val EXTRA_DM_ID = "notification_dm_id"

    }

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        // if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        // val group = NotificationChannelGroup(
        //     GROUP_MESSAGES,
        //     context.getString(R.string.app_name)
        // )
        // notificationManager.createNotificationChannelGroup(group)

        // val messageChannel = NotificationChannel(
        //     CHANNEL_MESSAGES,
        //     context.getString(R.string.notification_channel_messages),
        //     NotificationManager.IMPORTANCE_HIGH
        // ).apply {
        //     description = context.getString(R.string.notification_channel_messages_desc)
        //     setGroup(GROUP_MESSAGES)
        //     enableVibration(true)
        //     vibrationPattern = longArrayOf(300, 500, 300, 500)
        // }

        // val dmChannel = NotificationChannel(
        //     CHANNEL_DM,
        //     context.getString(R.string.notification_channel_dm),
        //     NotificationManager.IMPORTANCE_HIGH
        // ).apply {
        //     description = context.getString(R.string.notification_channel_dm_desc)
        //     setGroup(GROUP_MESSAGES)
        //     enableVibration(true)
        //     vibrationPattern = longArrayOf(300, 500, 300, 500)
        // }

        // val systemChannel = NotificationChannel(
        //     CHANNEL_SYSTEM,
        //     context.getString(R.string.notification_channel_system),
        //     NotificationManager.IMPORTANCE_DEFAULT
        // ).apply {
        //     description = context.getString(R.string.notification_channel_system_desc)
        // }

        // notificationManager.createNotificationChannels(
        //     listOf(messageChannel, dmChannel, systemChannel)
        // )
    }

    fun showMessageNotification(
        title: String,
        body: String,
        channelId: Long? = null,
        clanId: Long? = null,
        channelName: String = "",
        channelType: Int? = null
    ) {
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
                action = ACTION_OPEN_CHAT + "_" + System.nanoTime()
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (channelId != null) putExtra(EXTRA_CHANNEL_ID, channelId)
                if (clanId != null) putExtra(EXTRA_CLAN_ID, clanId)
                if (computedChannelName.isNotEmpty()) putExtra(EXTRA_CHANNEL_NAME, computedChannelName)
                if (channelType != null) putExtra(EXTRA_CHANNEL_TYPE, channelType)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                notificationId,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
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
        appScope.launch {
            val dmName = dialogsController.get().getDialog(dmChannelId)?.let { dm ->
                dm.displayName.ifEmpty { dm.label }
            } ?: title
            val intent = Intent(context, MainActivity::class.java).apply {
                action = ACTION_OPEN_CHAT + "_" + System.nanoTime()
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_DM_ID, dmChannelId)
                if (dmName.isNotEmpty()) putExtra(EXTRA_CHANNEL_NAME, dmName)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                dmChannelId.toInt(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
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
        dmId: Long = 0L
    ) {
        appScope.launch {
            val channelName = when {
                dmId != 0L -> dialogsController.get().getDialog(dmId)?.let { dm ->
                    dm.displayName.ifEmpty { dm.label }
                } ?: title
                clanId != 0L && channelId != 0L -> (withTimeoutOrNull(MAX_NOTI_DELAY) {
                    channelController.findOrFetchChannelLabel(channelId, clanId)
                } ?: title).ifEmpty { title }
                else -> title
            }
            withContext(Dispatchers.Main) {
                val activity = MainActivity.instance ?: return@withContext
                val onTap: (() -> Unit)? = when {
                    dmId != 0L -> { {
                        activity.notificationCenter.postNotificationOnMainThread(NotificationCenter.navigateToMessagesTab)
                        activity.openChat(dmId, channelName, 0L, CHANNEL_TYPE_DM, fromNotification = true)
                    } }
                    clanId != 0L && channelId != 0L -> {
                        {
                            notificationCenter.postNotificationOnMainThread(NotificationCenter.navigateToClansTab)
                            clansController.selectClan(clanId)
                            chatController.get().openChannel(channelId, clanId, CHANNEL_TYPE_CHANNEL)
                            activity.openChat(channelId, channelName, clanId, CHANNEL_TYPE_CHANNEL, fromNotification = true)
                        }
                    }
                    else -> null
                }
                ToastOverlay(activity, activity.themeColors).show(
                    parent = activity.drawerLayoutContainer,
                    type = ToastOverlay.ToastType.INFO,
                    title = title,
                    description = body,
                    onTap = onTap
                )
            }
        }
    }
}
