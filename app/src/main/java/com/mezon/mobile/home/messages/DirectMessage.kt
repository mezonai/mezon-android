package com.mezon.mobile.home.messages

import com.mezon.mobile.home.call.messagePreviewForDialog
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mezon.mezon.api.ChannelDescription
import com.mezon.mezon.api.ChannelMessage
import com.mezon.mobile.home.extractLastSeenMessageId
import com.mezon.mobile.home.extractLastSeenMessageTs
import com.mezon.mobile.home.extractLastSentMessageId
import com.mezon.mobile.home.extractLastSentMessageTs
import com.mezon.mobile.util.parseContentPreview
import com.mezon.mobile.network.CHANNEL_TYPE_DM
import com.mezon.mobile.network.CHANNEL_TYPE_GROUP
import com.mezon.mobile.network.streamModeToChannelType

data class DmParticipant(
    val userId: Long,
    val username: String,
    val displayName: String,
    val avatarUrl: String
)

fun ChannelDescription.extractParticipants(): List<DmParticipant> {
    val count = userIdsCount
    if (count == 0) return emptyList()
    val result = ArrayList<DmParticipant>(count)
    for (i in 0 until count) {
        result.add(DmParticipant(
            userId = getUserIds(i),
            username = usernamesList.getOrElse(i) { "" },
            displayName = displayNamesList.getOrElse(i) { "" }.ifBlank { usernamesList.getOrElse(i) { "" } },
            avatarUrl = avatarsList.getOrElse(i) { "" }
        ))
    }
    return result
}

@Entity(
    tableName = "direct_messages",
    indices = [Index(value = ["lastSentMessageTs"])]
)
data class DirectMessage(
    @PrimaryKey val channelId: Long,
    val type: Int,
    val label: String,
    val avatarUrl: String,
    val displayName: String,
    val username: String = "",
    val lastMessageContent: String,
    val unreadCount: Int,
    val isOnline: Boolean,
    val isMute: Boolean,
    val otherUserId: Long = 0L,
    val lastSeenMessageId: Long = 0L,
    val lastSentMessageId: Long = 0L,
    val lastSeenMessageTs: Long = 0L,
    val lastSentMessageTs: Long = 0L
)


fun ChannelDescription.toDirectMessage(currentUserId: Long, previewContext: android.content.Context? = null): DirectMessage {
    val otherIndex = userIdsList.indexOfFirst { it != currentUserId }
        .takeIf { it >= 0 }
        ?: 0 

    val username = usernamesList.getOrElse(otherIndex) { "" }
    val participantName = displayNamesList.getOrElse(otherIndex) { "" }
        .ifBlank { username.ifBlank { channelLabel } }
    val isGroup = type == CHANNEL_TYPE_GROUP
    val displayName = if (isGroup) {
        channelLabel.ifBlank { participantName }
    } else {
        participantName
    }
    val avatarUrl = when {
        channelAvatar.isNotEmpty() -> channelAvatar
        isGroup -> ""
        else -> avatarsList.getOrElse(otherIndex) { "" }
    }
    val lastMsgContent = if (hasLastSentMessage()) {
        if (previewContext != null) messagePreviewForDialog(previewContext, lastSentMessage.content)
        else parseContentPreview(lastSentMessage.content)
    } else ""

    val otherUserId = if (isGroup) 0L else userIdsList.getOrElse(otherIndex) { 0L }

    return DirectMessage(
        channelId = channelId,
        type = type,
        label = channelLabel.ifBlank { displayName },
        avatarUrl = avatarUrl,
        displayName = displayName,
        username = if (isGroup) displayName else username,
        lastMessageContent = lastMsgContent,
        unreadCount = countMessUnread,
        isOnline = false,
        isMute = isMute,
        otherUserId = otherUserId,
        lastSeenMessageId = extractLastSeenMessageId(),
        lastSentMessageId = extractLastSentMessageId(),
        lastSeenMessageTs = extractLastSeenMessageTs(),
        lastSentMessageTs = extractLastSentMessageTs()
    )
}

fun ChannelMessage.toDirectMessageFromIncoming(
    currentUserId: Long,
    previewContext: android.content.Context,
    viewingChannelId: Long?
): DirectMessage {
    val isFromMe = senderId == currentUserId
    val isOpen = viewingChannelId != null && viewingChannelId == channelId
    val type = streamModeToChannelType(mode)
    val senderName = displayName.ifBlank { username }
    val name = if (type == CHANNEL_TYPE_GROUP && channelLabel.isNotBlank()) {
        channelLabel
    } else {
        senderName.ifBlank { channelLabel }
    }
    val preview = messagePreviewForDialog(previewContext, content)
    val ts = createTimeSeconds.toLong() and 0xFFFF_FFFFL
    val unread = when {
        isOpen -> 0
        isFromMe -> 0
        else -> 1
    }
    return DirectMessage(
        channelId = channelId,
        type = type,
        label = name,
        avatarUrl = if (type == CHANNEL_TYPE_DM) avatar else "",
        displayName = name,
        username = if (type == CHANNEL_TYPE_GROUP) name else username,
        lastMessageContent = preview,
        unreadCount = unread,
        isOnline = false,
        isMute = false,
        otherUserId = if (type == CHANNEL_TYPE_DM && !isFromMe) senderId else 0L,
        lastSeenMessageId = 0L,
        lastSentMessageId = messageId,
        lastSeenMessageTs = 0L,
        lastSentMessageTs = ts
    )
}
