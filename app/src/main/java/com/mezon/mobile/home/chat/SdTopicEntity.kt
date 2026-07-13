package com.mezon.mobile.home.chat

import com.mezon.mezon.api.ChannelMessageHeader
import com.mezon.mezon.api.SdTopic
import com.mezon.mezon.rtapi.SdTopicEvent
import com.mezon.mobile.home.clans.ClanChannelEntity
import com.mezon.mobile.network.CHANNEL_TYPE_THREAD
import com.mezon.mobile.util.TopicOriginalPreviewToken
import com.mezon.mobile.util.parseTopicOriginalMessagePreview

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
        get() = parseTopicOriginalMessagePreview(content)

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
    val createTs = if (hasMessage()) message.createTimeSeconds.toLong() else 0L
    val updateTs = last.timestampSeconds.toLong().takeIf { it > 0L } ?: createTs
    return SdTopicEntity(
        id = id,
        creatorId = userId,
        messageId = messageId,
        clanId = clanId,
        channelId = channelId,
        content = rootContent,
        createTimeSeconds = createTs,
        updateTimeSeconds = updateTs,
        lastSentMessageId = last.id,
        lastSentSenderId = last.senderId,
        lastSentContent = last.content,
        lastSentTimestampSeconds = last.timestampSeconds.toLong()
    )
}

fun SdTopicEntity.toClanChannelEntity(
    parent: ClanChannelEntity? = null,
    existing: ClanChannelEntity? = null
): ClanChannelEntity {
    val label = channelLabelPreview(rootMessagePreview).take(80).ifBlank { "Topic" }
    val lastSentId = lastSentMessageId.takeIf { it > 0L } ?: messageId
    val lastSentTs = lastSentTimestampSeconds.takeIf { it > 0L }
        ?: updateTimeSeconds.takeIf { it > 0L }
        ?: createTimeSeconds
    return ClanChannelEntity(
        clanId = clanId,
        channelId = id,
        parentId = channelId,
        categoryId = 0L,
        categoryName = "",
        channelLabel = label,
        type = CHANNEL_TYPE_THREAD,
        isPrivate = parent?.isPrivate ?: false,
        topic = label,
        unreadCount = existing?.unreadCount ?: 0,
        isMuted = parent?.isMuted ?: false,
        lastSeenMessageId = existing?.lastSeenMessageId ?: 0L,
        lastSentMessageId = maxOf(existing?.lastSentMessageId ?: 0L, lastSentId),
        lastSeenMessageTs = existing?.lastSeenMessageTs ?: 0L,
        lastSentMessageTs = maxOf(existing?.lastSentMessageTs ?: 0L, lastSentTs),
        active = 1,
        categoryOrder = 0
    )
}

private fun channelLabelPreview(preview: String): String =
    when (preview) {
        TopicOriginalPreviewToken.ATTACHMENT -> "[Attachment]"
        TopicOriginalPreviewToken.CONTACT -> "[Contact]"
        TopicOriginalPreviewToken.INTERACTIVE_MESSAGE -> "[Interactive message]"
        else -> preview
    }
