package com.mezon.mobile.home.notifications

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mezon.mezon.api.ChannelMessage
import com.mezon.mezon.api.DirectFcmProto
import com.mezon.mezon.api.Notification
import com.mezon.mobile.util.parseContentText
import org.json.JSONObject

const val NOTIF_TAB_TOPICS_UI = -1

const val NOTIF_CATEGORY_MENTIONS = 1
const val NOTIF_CATEGORY_MESSAGES = 2
const val NOTIF_CATEGORY_FOR_YOU = 3
const val NOTIF_CODE_MESSAGE_TO_INBOX = -12

private val FOR_YOU_USERNAME_REGEX = Regex("""^([\w.]+)\s""")

internal fun pendingNotificationId(channelId: Long, messageId: Long): Long {
    val mixed = channelId xor java.lang.Long.rotateLeft(messageId, 32)
    return mixed or Long.MIN_VALUE
}

internal fun NotificationEntity.hasSameMessageIdentity(other: NotificationEntity): Boolean =
    channelId != 0L && messageId != 0L &&
        channelId == other.channelId && messageId == other.messageId

private fun JSONObject.positiveLong(key: String): Long {
    val parsed = when (val value = opt(key)) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull() ?: 0L
        else -> 0L
    }
    return parsed.takeIf { it > 0L } ?: 0L
}

private fun topicIdFromMessageContent(content: String): Long {
    if (content.isBlank()) return 0L
    return runCatching {
        JSONObject(content).positiveLong("tp")
    }.getOrDefault(0L)
}

private fun enrichForYouUsername(category: Int, senderUsername: String, messageText: String): String {
    if (category != NOTIF_CATEGORY_FOR_YOU || senderUsername.isNotEmpty()) return senderUsername
    return FOR_YOU_USERNAME_REGEX.find(messageText)?.groupValues?.getOrNull(1).orEmpty()
}

@Entity(
    tableName = "notifications",
    indices = [Index(value = ["category", "clanId", "createTimeSeconds"])]
)
data class NotificationEntity(
    @PrimaryKey val id: Long,
    val subject: String,
    val code: Int,
    val senderId: Long,
    val createTimeSeconds: Long,
    val clanId: Long,
    val channelId: Long,
    val channelType: Int,
    val avatarUrl: String,
    val category: Int,
    val topicId: Long,
    val messageId: Long,
    val senderName: String,
    val senderUsername: String = "",
    val senderAvatar: String,
    val clanName: String,
    val channelLabel: String,
    val messageText: String
)

fun Notification.toNotificationEntity(): NotificationEntity {
    var channelId = this.channelId
    var clanId = this.clanId
    var channelType = this.channelType
    var senderName = ""
    var senderUsername = ""
    var senderAvatar = ""
    var clanName = ""
    var channelLabel = if (hasChannel()) this.channel.channelLabel else ""
    var messageText = ""
    var createTimeSeconds = this.createTimeSeconds.toLong()
    var messageId = 0L
    var topicId = this.topicId.takeIf { it != 0L } ?: 0L

    if (content != null && !content.isEmpty) {
        try {
            val bytes = content.toByteArray()
            val firstByte = bytes[0].toInt() and 0xFF
            if (firstByte == 123 || firstByte == 91) {
                val obj = JSONObject(content.toStringUtf8())
                val innerStr = obj.optString("content", "")
                if (channelId == 0L) channelId = obj.optString("channel_id", "0").toLongOrNull() ?: 0L
                if (clanId == 0L) clanId = obj.optString("clan_id", "0").toLongOrNull() ?: 0L
                if (messageId == 0L) {
                    messageId = obj.optLong("message_id", 0L).takeIf { it != 0L }
                        ?: obj.optString("message_id", "").toLongOrNull() ?: 0L
                }
                if (topicId == 0L) {
                    topicId = obj.positiveLong("topic_id")
                        .takeIf { it != 0L }
                        ?: topicIdFromMessageContent(innerStr)
                }
                senderUsername = obj.optString("username", "")
                senderName = obj.optString("display_name", "").ifEmpty { senderUsername }
                senderAvatar = obj.optString("avatar", "")
                if (createTimeSeconds == 0L) createTimeSeconds = obj.optLong("create_time_seconds", 0L)
                val textRaw = if (innerStr.isNotEmpty()) {
                    try { JSONObject(innerStr).optString("t", "") } catch (_: Exception) { innerStr }
                } else obj.optString("t", "")
                messageText = parseContentText(textRaw).ifEmpty { textRaw }
            } else {
                val fcm = runCatching { DirectFcmProto.parseFrom(bytes) }.getOrNull()
                val channelMessage = if (fcm == null || fcm.messageId == 0L) {
                    runCatching { ChannelMessage.parseFrom(bytes) }.getOrNull()
                        ?.takeIf { it.messageId > 0L && it.channelId > 0L }
                } else {
                    null
                }
                val innerStr = when {
                    channelMessage != null -> {
                        if (channelId == 0L) channelId = channelMessage.channelId
                        if (clanId == 0L) clanId = channelMessage.clanId
                        senderUsername = channelMessage.username
                        senderName = channelMessage.displayName.ifEmpty { channelMessage.username }
                        senderAvatar = channelMessage.avatar
                        if (createTimeSeconds == 0L) {
                            createTimeSeconds = channelMessage.createTimeSeconds.toLong()
                        }
                        if (messageId == 0L) messageId = channelMessage.messageId
                        if (topicId == 0L) {
                            topicId = channelMessage.topicId.takeIf { it > 0L }
                                ?: topicIdFromMessageContent(channelMessage.content)
                        }
                        channelMessage.content
                    }
                    fcm != null -> {
                        if (channelId == 0L) channelId = fcm.channelId
                        if (clanId == 0L) clanId = fcm.clanId
                        senderUsername = fcm.username
                        senderName = fcm.displayName.ifEmpty { fcm.username }
                        senderAvatar = fcm.avatar
                        if (createTimeSeconds == 0L) createTimeSeconds = fcm.createTimeSeconds.toLong()
                        if (messageId == 0L && fcm.messageId != 0L) messageId = fcm.messageId
                        if (topicId == 0L) {
                            topicId = fcm.topicId.takeIf { it > 0L }
                                ?: topicIdFromMessageContent(fcm.content)
                        }
                        fcm.content
                    }
                    else -> ""
                }
                val textRaw = if (innerStr.isNotEmpty()) {
                    try { JSONObject(innerStr).optString("t", "") } catch (_: Exception) { innerStr }
                } else ""
                messageText = parseContentText(textRaw).ifEmpty { textRaw }
            }
        } catch (_: Exception) {
        }
    }


    val resolvedAvatar = this.avatarUrl.ifEmpty { senderAvatar }
    senderUsername = enrichForYouUsername(category, senderUsername, messageText)

    return NotificationEntity(
        id = id,
        subject = subject ?: "",
        code = code,
        senderId = senderId,
        createTimeSeconds = createTimeSeconds,
        clanId = clanId,
        channelId = channelId,
        channelType = channelType.takeIf { it != 0 } ?: if (clanId == 0L) 3 else 1,
        avatarUrl = resolvedAvatar,
        category = category,
        topicId = topicId,
        messageId = messageId,
        senderName = senderName,
        senderUsername = senderUsername,
        senderAvatar = resolvedAvatar,
        clanName = clanName,
        channelLabel = channelLabel,
        messageText = messageText
    )
}

fun parseNotificationsJson(jsonString: String): List<NotificationEntity> {
    return try {
        val root = JSONObject(jsonString)
        val arr = root.optJSONArray("notifications") ?: return emptyList()
        (0 until arr.length()).map { parseNotificationItemJson(arr.getJSONObject(it)) }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun parseNotificationItemJson(item: JSONObject): NotificationEntity {
    val id = item.optString("id", "0").toLongOrNull() ?: 0L
    val avatarUrl = item.optString("avatar_url", "")

    var channelId = item.optString("channel_id", "0").toLongOrNull() ?: 0L
    var clanId = item.optString("clan_id", "0").toLongOrNull() ?: 0L
    var channelType = item.optInt("channel_type", 0)
    var senderName = ""; var senderUsername = ""; var senderAvatar = ""; var clanName = ""
    var channelLabel = ""; var messageText = ""; var createTimeSeconds = 0L
    var messageId = item.optString("message_id", "0").toLongOrNull() ?: 0L

    val content = item.optJSONObject("content")
    if (content != null) {
        if (channelId == 0L) channelId = content.optString("channel_id", "0").toLongOrNull() ?: 0L
        if (clanId == 0L) clanId = content.optString("clan_id", "0").toLongOrNull() ?: 0L
        if (channelType == 0) channelType = content.optInt("channel_type", 0).takeIf { it != 0 } ?: content.optInt("mode", 0)
        if (messageId == 0L) messageId = content.optString("message_id", "0").toLongOrNull() ?: 0L
        senderUsername = content.optString("username", "")
        senderName = content.optString("display_name", "").ifEmpty { senderUsername }
        senderAvatar = content.optString("avatar", "")
        clanName = content.optString("clan_name", "")
        channelLabel = content.optString("channel_label", "")
        createTimeSeconds = content.optLong("create_time_seconds", 0L)
        val innerStr = content.optString("content", "")
        val textRaw = if (innerStr.isNotEmpty()) {
            try { JSONObject(innerStr).optString("t", "") } catch (_: Exception) { innerStr }
        } else content.optString("t", "")
        messageText = parseContentText(textRaw).ifEmpty { textRaw }
    }

    senderUsername = enrichForYouUsername(item.optInt("category", 0), senderUsername, messageText)

    return NotificationEntity(
        id = id, subject = item.optString("subject", ""),
        code = item.optInt("code", 0),
        senderId = item.optString("sender_id", "0").toLongOrNull() ?: 0L,
        createTimeSeconds = createTimeSeconds,
        clanId = clanId, channelId = channelId,
        channelType = channelType.takeIf { it != 0 } ?: if (clanId == 0L) 3 else 1,
        avatarUrl = avatarUrl.ifEmpty { senderAvatar },
        category = item.optInt("category", 0),
        topicId = item.optString("topic_id", "0").toLongOrNull() ?: 0L,
        messageId = messageId,
        senderName = senderName, senderUsername = senderUsername, senderAvatar = senderAvatar,
        clanName = clanName, channelLabel = channelLabel, messageText = messageText
    )
}
