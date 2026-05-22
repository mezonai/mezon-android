package com.mezon.mobile.home.sharing

import com.mezon.mobile.home.chat.ForwardDestination
import com.mezon.mobile.home.clans.ClanChannelEntity
import com.mezon.mobile.home.messages.DirectMessage
import com.mezon.mobile.home.messages.isDefaultGroupAvatarUrl
import com.mezon.mobile.network.CHANNEL_TYPE_DM
import com.mezon.mobile.network.CHANNEL_TYPE_GROUP
import com.mezon.mobile.network.CHANNEL_TYPE_THREAD

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
    val lastActivityTs: Long
) {
    val isDm: Boolean get() = channelType == CHANNEL_TYPE_DM
    val isGroup: Boolean get() = channelType == CHANNEL_TYPE_GROUP
    val isThread: Boolean get() = channelType == CHANNEL_TYPE_THREAD || parentId != 0L
    val isClanChannel: Boolean get() = !isDm && !isGroup && clanId != 0L

    fun hasCustomAvatar(): Boolean {
        val url = avatarUrl.ifEmpty { clanLogo }
        if (!isGroup) return url.isNotBlank()
        return url.isNotBlank() && !isDefaultGroupAvatarUrl(url)
    }
}

fun SharingTarget.toForwardDestination(): ForwardDestination = ForwardDestination(
    channelId = channelId,
    clanId = clanId,
    channelType = channelType,
    displayLabel = channelLabel,
    subtitle = if (isClanChannel && clanName.isNotEmpty()) clanName else "",
    isChannelPrivate = isPrivate
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
        lastActivityTs = maxOf(lastSeenMessageTs, lastSentMessageTs)
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
