package com.mezon.mobile.home.chat

import com.mezon.mezon.api.ChannelMessageHeader
import com.mezon.mezon.api.SdTopic
import com.mezon.mezon.rtapi.SdTopicEvent
import com.mezon.mobile.util.parseContentPreview
import org.json.JSONObject

data class SdTopicEntity(
    val id: Long,
    val creatorId: Long,
    val messageId: Long,
    val clanId: Long,
    val channelId: Long,
    val content: String,
    val createTimeSeconds: Long,
    val updateTimeSeconds: Long,
    val lastSentMessageId: Long = 0L,
    val lastSentSenderId: Long = 0L,
    val lastSentContent: String = "",
    val lastSentTimestampSeconds: Long = 0L
) {
    val rootMessagePreview: String
        get() = parseContentPreview(content).ifBlank { parseMessagePreview(content) }

    val lastMessagePreview: String
        get() = parseContentPreview(lastSentContent).ifBlank { parseMessagePreview(lastSentContent) }

    fun senderIdForAvatar(): Long = lastSentSenderId.takeIf { it != 0L } ?: creatorId
}

fun SdTopic.toSdTopicEntity(): SdTopicEntity {
    val last = if (hasLastSentMessage()) lastSentMessage else ChannelMessageHeader.getDefaultInstance()
    return SdTopicEntity(
        id = id,
        creatorId = creatorId,
        messageId = messageId,
        clanId = clanId,
        channelId = channelId,
        content = content,
        createTimeSeconds = createTimeSeconds.toLong(),
        updateTimeSeconds = updateTimeSeconds.toLong(),
        lastSentMessageId = last.id,
        lastSentSenderId = last.senderId,
        lastSentContent = last.content,
        lastSentTimestampSeconds = last.timestampSeconds.toLong()
    )
}

fun SdTopicEvent.toSdTopicEntityFromEvent(): SdTopicEntity {
    val last = if (hasLastSentMessage()) lastSentMessage else ChannelMessageHeader.getDefaultInstance()
    val rootContent = if (hasMessage()) message.content else ""
    val createTs = last.timestampSeconds.toLong().takeIf { it > 0L }
        ?: if (hasMessage()) message.createTimeSeconds.toLong() else 0L
    return SdTopicEntity(
        id = id,
        creatorId = userId,
        messageId = messageId,
        clanId = clanId,
        channelId = channelId,
        content = rootContent,
        createTimeSeconds = createTs,
        updateTimeSeconds = createTs,
        lastSentMessageId = last.id,
        lastSentSenderId = last.senderId,
        lastSentContent = last.content,
        lastSentTimestampSeconds = last.timestampSeconds.toLong()
    )
}

private fun parseMessagePreview(raw: String): String {
    if (raw.isBlank()) return ""
    return runCatching {
        val json = JSONObject(raw)
        json.optString("t", "").ifBlank {
            if (json.has("embed") || json.has("components")) "[attachment]" else ""
        }
    }.getOrDefault("")
}
