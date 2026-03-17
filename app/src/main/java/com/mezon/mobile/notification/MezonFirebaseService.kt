package com.mezon.mobile.notification

import android.provider.Settings
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.mezon.mobile.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MezonFirebaseService : FirebaseMessagingService() {

    @Inject lateinit var notificationHelper: NotificationHelper
    @Inject lateinit var fcmRepository: FcmRepository
    @Inject lateinit var activeChannelTracker: ActiveChannelTracker

    companion object {
        private const val TAG = "MezonFirebaseService"
        private val CHANNEL_LINK_REGEX = Regex("""/chat/clans/(\d+)/channels/(\d+)""")
        private val DM_LINK_REGEX = Regex("""/chat/direct/message/(\d+)""")
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM token received")
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        fcmRepository.registerTokenAsync(token, deviceId)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        Log.d(TAG, "FCM message received: from=${message.from}, data=${message.data}")

        val data = message.data
        if (data.isEmpty()) {
            handleNotificationPayload(message)
            return
        }

        handleDataPayload(data)
    }

    private fun handleNotificationPayload(message: RemoteMessage) {
        val notification = message.notification ?: return
        val title = notification.title ?: getString(com.mezon.mobile.R.string.app_name)
        val body = notification.body ?: ""

        if (isAppInForeground()) {
            notificationHelper.showInAppToast(title, body)
        } else {
            notificationHelper.showMessageNotification(
                title = title,
                body = body,
                channelId = 0L,
                clanId = 0L,
                channelName = "",
                channelType = 0
            )
        }
    }

    private fun handleDataPayload(data: Map<String, String>) {
        val title = data["title"] ?: getString(com.mezon.mobile.R.string.app_name)
        val body = data["body"] ?: data["message"] ?: return

        val link = data["link"] ?: ""
        val channel = data["channel"] ?: ""

        val channelId = extractChannelIdFromLink(link)
        val clanId = extractClanIdFromLink(link)
        val channelType = data["channel_type"]?.toIntOrNull() ?: 0
        val channelName = data["channel_label"] ?: ""

        if (channelId != 0L && activeChannelTracker.isViewing(channelId)) {
            Log.d(TAG, "Suppressing notification: user is viewing channel $channelId")
            return
        }

        val isDm = link.contains("direct/") || channel.isNotEmpty()
        val dmId = if (isDm && channel.isNotEmpty()) channel.toLongOrNull() ?: 0L else 0L

        if (dmId != 0L && activeChannelTracker.isViewing(dmId)) {
            Log.d(TAG, "Suppressing notification: user is viewing DM $dmId")
            return
        }

        if (isAppInForeground()) {
            notificationHelper.showInAppToast(title, body)
        } else {
            if (isDm && channel.isNotEmpty()) {
                notificationHelper.showDmNotification(
                    title = title,
                    body = body,
                    dmChannelId = dmId
                )
            } else {
                notificationHelper.showMessageNotification(
                    title = title,
                    body = body,
                    channelId = channelId,
                    clanId = clanId,
                    channelName = channelName,
                    channelType = channelType
                )
            }
        }
    }

    private fun isAppInForeground(): Boolean {
        return MainActivity.isResumed
    }

    private fun extractChannelIdFromLink(link: String): Long {
        val match = CHANNEL_LINK_REGEX.find(link) ?: return 0L
        return match.groupValues[2].toLongOrNull() ?: 0L
    }

    private fun extractClanIdFromLink(link: String): Long {
        val match = CHANNEL_LINK_REGEX.find(link) ?: return 0L
        return match.groupValues[1].toLongOrNull() ?: 0L
    }
}
