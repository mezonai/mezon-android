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
import com.mezon.mobile.network.CHANNEL_TYPE_DM
import com.mezon.mobile.network.CHANNEL_TYPE_GROUP
import com.mezon.mobile.network.streamModeToChannelType
import com.mezon.mobile.util.parseContentPreview

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

fun ChannelDescription.resolveOtherParticipantIndex(currentUserId: Long): Int {
    for (i in 0 until userIdsCount) {
        val id = getUserIds(i)
        if (id != 0L && id != currentUserId) return i
    }
    return -1
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
    val lastSentMessageTs: Long = 0L,
    val groupCreatorId: Long = 0L,
) {
    fun avatarPlaceholderKey(): String = when (type) {
        CHANNEL_TYPE_GROUP -> displayName.ifEmpty { label }
        CHANNEL_TYPE_DM -> username
        else -> username.ifEmpty { displayName.ifEmpty { label } }
    }
}

fun ChannelDescription.toDirectMessage(currentUserId: Long, previewContext: android.content.Context? = null): DirectMessage {
    val isGroup = type == CHANNEL_TYPE_GROUP
    val otherIndex = resolveOtherParticipantIndex(currentUserId)
    val username: String
    val participantName: String
    val avatarUrl: String
    val otherUserId: Long
    if (otherIndex >= 0) {
        username = usernamesList.getOrElse(otherIndex) { "" }
        participantName = displayNamesList.getOrElse(otherIndex) { "" }
            .ifBlank { username.ifBlank { channelLabel } }
        avatarUrl = when {
            channelAvatar.isNotEmpty() -> channelAvatar
            isGroup -> ""
            else -> avatarsList.getOrElse(otherIndex) { "" }
        }
        otherUserId = if (isGroup) 0L else getUserIds(otherIndex)
    } else if (isGroup) {
        val fallbackIndex = if (userIdsCount > 0) 0 else -1
        username = if (fallbackIndex >= 0) usernamesList.getOrElse(fallbackIndex) { "" } else ""
        participantName = if (fallbackIndex >= 0) {
            displayNamesList.getOrElse(fallbackIndex) { "" }.ifBlank { username.ifBlank { channelLabel } }
        } else {
            channelLabel
        }
        avatarUrl = if (channelAvatar.isNotEmpty()) channelAvatar else ""
        otherUserId = 0L
    } else {
        username = ""
        participantName = channelLabel
        avatarUrl = if (channelAvatar.isNotEmpty()) channelAvatar else ""
        otherUserId = 0L
    }
    val displayName = if (isGroup) {
        channelLabel.ifBlank { participantName }
    } else {
        participantName
    }
    val lastMsgContent = if (hasLastSentMessage()) {
        if (previewContext != null) messagePreviewForDialog(previewContext, lastSentMessage.content)
        else parseContentPreview(lastSentMessage.content)
    } else ""

    val groupCreatorId = if (isGroup) creatorId else 0L

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
        lastSentMessageTs = extractLastSentMessageTs(),
        groupCreatorId = groupCreatorId,
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
    val name = when {
        type == CHANNEL_TYPE_GROUP && channelLabel.isNotBlank() -> channelLabel
        type == CHANNEL_TYPE_GROUP -> senderName.ifBlank { channelLabel }
        !isFromMe -> senderName.ifBlank { channelLabel }
        else -> channelLabel.ifBlank { senderName }
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
