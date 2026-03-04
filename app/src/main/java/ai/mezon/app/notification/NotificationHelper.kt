package ai.mezon.app.notification

import ai.mezon.app.MainActivity
import ai.mezon.app.R
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val GROUP_MESSAGES = "mezon_messages"
        const val CHANNEL_MESSAGES = "mezon_channel_messages"
        const val CHANNEL_DM = "mezon_dm"
        const val CHANNEL_SYSTEM = "mezon_system"

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
        channelId: Long,
        clanId: Long,
        channelName: String,
        channelType: Int
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CHANNEL_ID, channelId)
            putExtra(EXTRA_CLAN_ID, clanId)
            putExtra(EXTRA_CHANNEL_NAME, channelName)
            putExtra(EXTRA_CHANNEL_TYPE, channelType)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            channelId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notifChannel = if (channelType == CHANNEL_TYPE_DM) CHANNEL_DM else CHANNEL_MESSAGES

        val notification = NotificationCompat.Builder(context, notifChannel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setGroup(GROUP_MESSAGES)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVibrate(longArrayOf(300, 500, 300, 500))
            .build()

        notificationManager.notify(channelId.toInt(), notification)
    }

    fun showDmNotification(
        title: String,
        body: String,
        dmChannelId: Long
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_DM_ID, dmChannelId)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            dmChannelId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_DM)
            .setSmallIcon(R.drawable.ic_notification)
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

    fun cancelNotification(notificationId: Int) {
        notificationManager.cancel(notificationId)
    }

    fun cancelAllNotifications() {
        notificationManager.cancelAll()
    }
}

private const val CHANNEL_TYPE_DM = 3
