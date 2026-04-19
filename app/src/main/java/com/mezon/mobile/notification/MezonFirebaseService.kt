package com.mezon.mobile.notification

import android.provider.Settings
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
        private val CHANNEL_LINK_REGEX = Regex("""/chat/clans/(\d+)/channels/(\d+)""")
        private val DM_LINK_REGEX = Regex("""/chat/direct/message/(\d+)""")
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        fcmRepository.registerTokenAsync(token, deviceId)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        if (data.isEmpty()) {
            handleNotificationPayload(message)
            return
        }

        // handleDataPayload(data)
    }

    private fun handleNotificationPayload(message: RemoteMessage) {
        val notification = message.notification ?: return
        val title = notification.title ?: getString(com.mezon.mobile.R.string.app_name)
        val body = notification.body ?: ""

        // if (isAppInForeground()) {
        //     notificationHelper.showInAppToast(title, body)
        // } else {
        //     notificationHelper.showMessageNotification(title, body)
        // }
    }


    private fun handleDataPayload(data: Map<String, String>) {
        val title = data["title"] ?: getString(com.mezon.mobile.R.string.app_name)
        val body = data["body"] ?: data["message"] ?: return
        val link = data["link"] ?: ""
        val channel = data["channel"] ?: ""
       
        val isDirectDM = channel.isNotEmpty() && link.contains("direct/friends")
        if (link.isNotEmpty() && !isDirectDM) {
            val linkChannelMatch = CHANNEL_LINK_REGEX.find(link)
            if (linkChannelMatch != null) {
                val clanId = linkChannelMatch.groupValues[1].toLongOrNull() ?: 0L
                val channelId = linkChannelMatch.groupValues[2].toLongOrNull() ?: 0L
                if (channelId != 0L && activeChannelTracker.isViewing(channelId)) {
                    return
                }

                if (isAppInForeground()) {
                    notificationHelper.showInAppToast(title, body, channelId = channelId, clanId = clanId)
                } else {
                    notificationHelper.showMessageNotification(title, body, channelId = channelId, clanId = clanId)
                }
            } else {
                val linkDirectMessageMatch = DM_LINK_REGEX.find(link)
                if (linkDirectMessageMatch != null) {
                    val dmId = linkDirectMessageMatch.groupValues[1].toLongOrNull() ?: 0L
                    if (dmId != 0L && activeChannelTracker.isViewing(dmId)) {
                        return
                    }

                    if (isAppInForeground()) {
                        notificationHelper.showInAppToast(title, body, dmId = dmId)
                    } else {
                        notificationHelper.showDmNotification(title, body, dmChannelId = dmId)
                    }
                }
            }
        }
    }

    private fun isAppInForeground(): Boolean {
        return MainActivity.isResumed
    }
}
