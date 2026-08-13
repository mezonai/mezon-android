package com.mezon.mobile.home.chat.thread

import com.mezon.mezon.api.ChannelDescription
import com.mezon.mobile.home.clans.ClanChannelEntity
import com.mezon.mobile.network.CHANNEL_TYPE_THREAD
import com.mezon.mobile.util.parseContentPreview

data class ThreadInfo(
    val channelId: Long,
    val clanId: Long,
    val parentId: Long,
    val channelLabel: String,
    val active: Int,
    val isPrivate: Boolean,
    val creatorId: Long,
    val creatorName: String,
    val lastSenderId: Long,
    val lastMessageContent: String,
    val lastMessageTs: Long
)

fun ChannelDescription.toThreadInfo(): ThreadInfo {
    val msgContent = if (hasLastSentMessage()) {
        parseContentPreview(lastSentMessage.content)
    } else ""
    val senderId = if (hasLastSentMessage()) lastSentMessage.senderId else 0L
    val msgTs = if (hasLastSentMessage()) lastSentMessage.timestampSeconds.toLong() else 0L
    return ThreadInfo(
        channelId = channelId,
        clanId = clanId,
        parentId = parentId,
        channelLabel = channelLabel,
        active = active,
        isPrivate = channelPrivate != 0,
        creatorId = creatorId,
        creatorName = creatorName,
        lastSenderId = senderId,
        lastMessageContent = msgContent,
        lastMessageTs = msgTs
    )
}

fun ThreadInfo.toClanChannelEntity(
    existing: ClanChannelEntity? = null,
    parentChannel: ClanChannelEntity? = null
): ClanChannelEntity {
    return ClanChannelEntity(
        clanId = if (clanId != 0L) clanId else existing?.clanId ?: 0L,
        channelId = channelId,
        parentId = if (parentId != 0L) parentId else existing?.parentId ?: 0L,
        categoryId = existing?.categoryId ?: parentChannel?.categoryId ?: 0L,
        categoryName = existing?.categoryName ?: parentChannel?.categoryName.orEmpty(),
        channelLabel = channelLabel.ifBlank { existing?.channelLabel.orEmpty() },
        type = existing?.type?.takeIf { it != 0 } ?: CHANNEL_TYPE_THREAD,
        isPrivate = isPrivate || existing?.isPrivate == true,
        topic = existing?.topic.orEmpty(),
        unreadCount = existing?.unreadCount ?: 0,
        isMuted = existing?.isMuted ?: false,
        lastSeenMessageId = existing?.lastSeenMessageId ?: 0L,
        lastSentMessageId = existing?.lastSentMessageId ?: 0L,
        lastSeenMessageTs = existing?.lastSeenMessageTs ?: 0L,
        lastSentMessageTs = existing?.lastSentMessageTs ?: 0L,
        active = if (active != 0) active else existing?.active ?: 0,
        categoryOrder = existing?.categoryOrder ?: parentChannel?.categoryOrder ?: 0,
        creatorId = creatorId.takeIf { it != 0L } ?: existing?.creatorId ?: 0L,
    )
}
