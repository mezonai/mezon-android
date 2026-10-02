package com.mezon.mobile.home.clans

import com.google.protobuf.ByteString
import com.mezon.mobile.util.MENTION_HERE_USER_ID
import com.mezon.mezon.api.MessageMentionList
import com.mezon.mezon.api.MessageRefList
import com.mezon.mezon.api.DirectFcmProto
import com.mezon.mezon.api.ChannelMessage
import org.json.JSONArray
import org.json.JSONObject

internal class ChannelBadgeVisibility {
    private data class VisibleChannel(val channelId: Long = 0L, val topicId: Long = 0L)
    @Volatile private var visible = VisibleChannel()

    fun show(channelId: Long, topicId: Long = 0L) {
        visible = VisibleChannel(channelId, topicId)
    }

    fun hide(channelId: Long, topicId: Long = 0L) {
        if (visible == VisibleChannel(channelId, topicId)) clear()
    }

    fun clear() {
        visible = VisibleChannel()
    }

    fun isViewingChannel(channelId: Long, paused: Boolean): Boolean {
        val current = visible
        return !paused && channelId != 0L && current.channelId == channelId && current.topicId == 0L
    }
}

private fun isHereMentionId(id: String): Boolean = id == "here" || id == MENTION_HERE_USER_ID

private fun JSONArray.containsUserMention(userId: Long, roleIds: Set<Long>): Boolean {
    for (index in 0 until length()) {
        val mention = optJSONObject(index) ?: continue
        val mentionedUser = mention.optString("user_id")
        if (isHereMentionId(mentionedUser) || mentionedUser.toLongOrNull() == userId) return true
        if (mention.optString("role_id").toLongOrNull() in roleIds) return true
    }
    return false
}

internal fun channelMessageMentionsUser(
    content: String,
    mentions: ByteString,
    references: ByteString,
    userId: Long,
    roleIds: Set<Long>
): Boolean {
    if (userId == 0L) return false
    if (mentions.isEmpty && references.isEmpty && !content.contains("\"mentions\"")) return false
    val contentMentions = runCatching { JSONObject(content).optJSONArray("mentions") }.getOrNull()
    if (contentMentions?.containsUserMention(userId, roleIds) == true) return true
    if (!mentions.isEmpty) {
        val bytes = mentions.toByteArray()
        val json = bytes.toString(Charsets.UTF_8).trimStart()
        val jsonMentions = runCatching {
            when {
                json.startsWith("[") -> JSONArray(json)
                json.startsWith("{") -> JSONObject(json).optJSONArray("mentions")
                else -> null
            }
        }.getOrNull()
        if (jsonMentions?.containsUserMention(userId, roleIds) == true) return true
        if (runCatching {
            MessageMentionList.parseFrom(bytes).mentionsList.any {
                isHereMentionId(it.userId.toString()) || it.userId == userId || it.roleId in roleIds
            }
        }.getOrDefault(false)) return true
    }
    if (!references.isEmpty) {
        if (runCatching {
            MessageRefList.parseFrom(references).refsList.any { it.messageSenderId == userId }
        }.getOrDefault(false)) return true
        val json = references.toStringUtf8().trimStart()
        val refs = runCatching {
            when {
                json.startsWith("[") -> JSONArray(json)
                json.startsWith("{") -> JSONObject(json).optJSONArray("refs")
                else -> null
            }
        }.getOrNull()
        if (refs != null && (0 until refs.length()).any {
                refs.optJSONObject(it)?.optString("message_sender_id")?.toLongOrNull() == userId
            }) return true
    }
    return false
}

internal data class NotificationBadgeMessage(val messageId: Long, val timestamp: Long)

private fun JSONObject.badgeLong(key: String): Long = when (val value = opt(key)) {
    is Number -> value.toLong()
    is String -> value.toLongOrNull() ?: 0L
    else -> 0L
}

internal fun notificationBadgeMessage(content: ByteString): NotificationBadgeMessage {
    if (content.isEmpty) return NotificationBadgeMessage(0L, 0L)
    val bytes = content.toByteArray()
    val text = content.toStringUtf8().trimStart()
    if (text.startsWith("{")) {
        val json = runCatching { JSONObject(text) }.getOrNull()
            ?: return NotificationBadgeMessage(0L, 0L)
        return NotificationBadgeMessage(
            json.badgeLong("message_id"),
            json.badgeLong("create_time_seconds")
        )
    }
    runCatching { DirectFcmProto.parseFrom(bytes) }.getOrNull()?.let {
        if (it.messageId != 0L) return NotificationBadgeMessage(
            it.messageId, it.createTimeSeconds.toLong() and 0xFFFF_FFFFL
        )
    }
    runCatching { ChannelMessage.parseFrom(bytes) }.getOrNull()?.let {
        if (it.messageId != 0L) return NotificationBadgeMessage(
            it.messageId, it.createTimeSeconds.toLong() and 0xFFFF_FFFFL
        )
    }
    return NotificationBadgeMessage(0L, 0L)
}

internal fun pendingMentionUnreadDelta(
    messageIds: Collection<Long>,
    lastSeenMessageId: Long,
    unreadCount: Int
): Int = (messageIds.count { it > lastSeenMessageId } - unreadCount).coerceAtLeast(0)

internal fun isMentionTimestampAlreadySeen(messageTimestamp: Long, lastSeenTimestamp: Long): Boolean =
    messageTimestamp > 0L && lastSeenTimestamp > 0L && messageTimestamp <= lastSeenTimestamp

internal fun isMentionAlreadySeen(messageId: Long, messageTimestamp: Long, lastSeenMessageId: Long, lastSeenTimestamp: Long): Boolean {
    if (messageTimestamp > 0L && lastSeenTimestamp > 0L) {
        return isMentionTimestampAlreadySeen(messageTimestamp, lastSeenTimestamp)
    }
    return messageId > 0L && lastSeenMessageId > 0L && messageId <= lastSeenMessageId
}

internal fun isChannelMessageAlreadySeen(
    messageId: Long,
    messageTimestamp: Long,
    lastSeenMessageId: Long,
    lastSeenTimestamp: Long
): Boolean {
    if (messageId > 0L && lastSeenMessageId > 0L) return messageId <= lastSeenMessageId
    return messageTimestamp > 0L && lastSeenTimestamp > 0L && messageTimestamp <= lastSeenTimestamp
}

internal fun canDecrementDeletedMention(
    wasCounted: Boolean,
    unreadCount: Int,
    messageId: Long,
    messageTimestamp: Long,
    lastSeenMessageId: Long,
    lastSeenTimestamp: Long
): Boolean {
    if (unreadCount <= 0 || isChannelMessageAlreadySeen(
            messageId, messageTimestamp, lastSeenMessageId, lastSeenTimestamp
        )) return false
    return wasCounted || lastSeenMessageId > 0L || lastSeenTimestamp > 0L
}
