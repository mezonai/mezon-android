package com.mezon.mobile.notification

import android.app.ActivityManager
import android.provider.Settings
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.mezon.mobile.MainActivity
import com.mezon.mobile.core.StartupCache
import com.mezon.mobile.home.call.IncomingCallFcmHandler
import dagger.hilt.android.AndroidEntryPoint

import javax.inject.Inject

private const val TAG = "MezonFirebaseService"
private const val FCM_LOG_VALUE_MAX_VISIBLE = 220

@AndroidEntryPoint
class MezonFirebaseService : FirebaseMessagingService() {

    @Inject lateinit var notificationHelper: NotificationHelper
    @Inject lateinit var fcmRepository: FcmRepository
    @Inject lateinit var activeChannelTracker: ActiveChannelTracker
    @Inject lateinit var incomingCallFcmHandler: IncomingCallFcmHandler

    companion object {
        private val CHANNEL_LINK_REGEX = Regex("""/chat/clans/(\d+)/channels/(\d+)""")
        private val DM_LINK_REGEX = Regex("""/chat/direct/message/(\d+)""")
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        if (!StartupCache.hasSession) return
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        fcmRepository.registerTokenAsync(token, deviceId)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        logRemoteMessageForDiagnostics(message)
        val data = message.data
        val keysStr = data.keys.joinToString(",")
        Log.i(
            TAG,
            "onMessageReceived keys=[$keysStr] size=${data.size} hasOffer=${data.containsKey("offer")} hasNotif=${message.notification != null} sentTime=${message.sentTime}"
        )
        if (!StartupCache.hasSession) {
            Log.w(
                TAG,
                "onMessageReceived: no session, skipping (hasOfferKey=${message.data.containsKey("offer")})"
            )
            return
        }
        super.onMessageReceived(message)
        if (data.isEmpty()) {
            handleNotificationPayload(message)
            return
        }

        if (incomingCallFcmHandler.hasOfferPayload(data)) {
            incomingCallFcmHandler.handleRemoteMessage(message)
            return
        }

        if (keysStr.isNotEmpty()) {
            Log.i(TAG, "data payload (no offer key), keys=[$keysStr]")
        }

        handleDataPayload(data)
    }

    private fun logRemoteMessageForDiagnostics(message: RemoteMessage) {
        val notif = message.notification
        val notifStr = if (notif != null) {
            "title=${notif.title} body=${notif.body} icon=${notif.icon} sound=${notif.sound} tag=${notif.tag} " +
                "color=${notif.color} clickAction=${notif.clickAction} channelId=${notif.channelId} " +
                "imageUrl=${notif.imageUrl} link=${notif.link} ticker=${notif.ticker}"
        } else {
            "null"
        }
        val dataStr = message.data.entries.joinToString("; ") { (key, value) ->
            formatFcmDataFieldForLog(key, value)
        }
        val text = buildString {
            append("messageId=${message.messageId} ")
            append("from=${message.from} ")
            append("to=${message.to} ")
            append("collapseKey=${message.collapseKey} ")
            append("messageType=${message.messageType} ")
            append("sentTime=${message.sentTime} ")
            append("ttl=${message.ttl} ")
            append("priority=${message.priority} originalPriority=${message.originalPriority} ")
            append("notification={$notifStr} ")
            append("data={$dataStr}")
        }
        var index = 0
        var remaining = text
        while (remaining.isNotEmpty()) {
            val chunk = remaining.take(3500)
            remaining = remaining.drop(3500)
            Log.i(
                TAG,
                if (index == 0) "onMessageReceived diagnostic: $chunk" else "onMessageReceived diagnostic cont.$index: $chunk"
            )
            index++
        }
    }

    private fun formatFcmDataFieldForLog(key: String, value: String): String {
        val k = key.lowercase()
        val forceBrief = k == "offer" || k == "offer_json" || k == "json_data" ||
            k == "message" || k.endsWith("_token") || k.contains("credential")
        if (!forceBrief && value.length <= FCM_LOG_VALUE_MAX_VISIBLE) {
            return "$key=$value"
        }
        val head = value.take(FCM_LOG_VALUE_MAX_VISIBLE).replace("\n", "\\n")
        return "$key(len=${value.length})=$head…"
    }

    private fun handleNotificationPayload(message: RemoteMessage) {
        val notification = message.notification ?: return
        val title = notification.title ?: getString(com.mezon.mobile.R.string.app_name)
        val body = notification.body ?: ""

        if (isAppInForeground()) {
            notificationHelper.showInAppToast(title, body)
        } else {
            notificationHelper.showMessageNotification(title, body)
        }
    }


    private fun handleDataPayload(data: Map<String, String>) {
        val title = data["title"] ?: getString(com.mezon.mobile.R.string.app_name)
        val body = data["body"] ?: data["message"] ?: return
        if (isCallCompanionSystemMessageBody(body)) {
            return
        }
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
                    notificationHelper.showInAppToast(
                        title,
                        body,
                        channelId = channelId,
                        clanId = clanId
                    )
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
                        notificationHelper.showInAppToast(
                            title,
                            body,
                            dmId = dmId
                        )
                    } else {
                        notificationHelper.showDmNotification(title, body, dmChannelId = dmId)
                    }
                }
            }
        }
    }

    private fun isCallCompanionSystemMessageBody(body: String): Boolean {
        val normalized = body.trim().lowercase()
        if (normalized.isEmpty()) return false
        if (normalized == "started voice call" || normalized == "started video call") return true
        if (normalized.endsWith(" started a voice call") || normalized.endsWith(" started a video call")) return true
        return normalized.contains("voice call started") || normalized.contains("video call started")
    }

    private fun isAppInForeground(): Boolean {
        if (MainActivity.isResumed) return true
        val am = getSystemService(ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val processes = am.runningAppProcesses ?: return false
        return processes.any {
            it.processName == packageName &&
                it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }
    }

}
