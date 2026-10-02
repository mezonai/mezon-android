package com.mezon.mobile.home.sharing

import com.mezon.mobile.home.chat.ForwardDestination
import com.mezon.mobile.home.clans.ClanChannelEntity
import com.mezon.mobile.home.messages.DirectMessage
import com.mezon.mobile.network.CHANNEL_TYPE_DM
import com.mezon.mobile.network.CHANNEL_TYPE_GROUP
import com.mezon.mobile.network.CHANNEL_TYPE_THREAD
import com.mezon.mobile.search.SearchMember

data class SharingTarget(
    val channelId: Long,
    val channelLabel: String,
    val username: String,
    val avatarUrl: String,
    val clanId: Long,
    val clanName: String,
    val clanLogo: String,
    val channelType: Int,
    val isPrivate: Boolean,
    val parentId: Long,
    val parentChannelLabel: String,
    val lastSentMessageTs: Long,
    val lastActivityTs: Long,
    val userId: Long = 0L
) {
    val isDm: Boolean get() = channelType == CHANNEL_TYPE_DM
    val isGroup: Boolean get() = channelType == CHANNEL_TYPE_GROUP
    val isThread: Boolean get() = channelType == CHANNEL_TYPE_THREAD || parentId != 0L
    val isClanChannel: Boolean get() = !isDm && !isGroup && clanId != 0L
    val needsDmChannel: Boolean get() = channelId == 0L && userId != 0L
    val key: String get() = if (channelId != 0L) "${channelId}_$channelType" else "user_$userId"
    val avatarId: Long get() = if (channelId != 0L) channelId else userId
}

fun SharingTarget.toForwardDestination(): ForwardDestination = ForwardDestination(
    channelId = channelId,
    clanId = clanId,
    channelType = channelType,
    displayLabel = channelLabel,
    subtitle = if (isClanChannel && clanName.isNotEmpty()) clanName else "",
    isChannelPrivate = isPrivate,
    parentId = parentId
)

fun DirectMessage.toSharingTarget(): SharingTarget {
    val displayLabel = if (type == CHANNEL_TYPE_GROUP) {
        label.ifBlank { displayName }
    } else {
        displayName.ifBlank { label }
    }
    return SharingTarget(
        channelId = channelId,
        channelLabel = displayLabel,
        username = username,
        avatarUrl = avatarUrl,
        clanId = 0L,
        clanName = "",
        clanLogo = "",
        channelType = type,
        isPrivate = type == CHANNEL_TYPE_DM || type == CHANNEL_TYPE_GROUP,
        parentId = 0L,
        parentChannelLabel = "",
        lastSentMessageTs = lastSentMessageTs,
        lastActivityTs = maxOf(lastSeenMessageTs, lastSentMessageTs),
        userId = if (type == CHANNEL_TYPE_DM) otherUserId else 0L
    )
}

fun SearchMember.toSharingTarget(existingDmChannelId: Long): SharingTarget {
    val isGroup = isDm && channelType == CHANNEL_TYPE_GROUP
    return SharingTarget(
        channelId = if (isDm) channelId else existingDmChannelId,
        channelLabel = displayName.ifBlank { username },
        username = if (isGroup) "" else username,
        avatarUrl = avatarUrl,
        clanId = 0L,
        clanName = "",
        clanLogo = "",
        channelType = if (isDm) channelType else CHANNEL_TYPE_DM,
        isPrivate = true,
        parentId = 0L,
        parentChannelLabel = "",
        lastSentMessageTs = 0L,
        lastActivityTs = 0L,
        userId = if (isGroup) 0L else id
    )
}

fun ClanChannelEntity.toSharingTarget(
    clanName: String,
    clanLogo: String,
    parentChannelLabel: String = ""
): SharingTarget {
    return SharingTarget(
        channelId = channelId,
        channelLabel = channelLabel,
        username = "",
        avatarUrl = "",
        clanId = clanId,
        clanName = clanName,
        clanLogo = clanLogo,
        channelType = type,
        isPrivate = isPrivate,
        parentId = parentId,
        parentChannelLabel = parentChannelLabel,
        lastSentMessageTs = lastSentMessageTs,
        lastActivityTs = maxOf(lastSeenMessageTs, lastSentMessageTs)
    )
}
