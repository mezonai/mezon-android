package com.mezon.mobile.home.sharing

import com.mezon.mobile.home.clans.ClanChannelEntity
import com.mezon.mobile.home.messages.DirectMessage
import com.mezon.mobile.network.CHANNEL_TYPE_DM
import com.mezon.mobile.network.CHANNEL_TYPE_GROUP
import com.mezon.mobile.network.CHANNEL_TYPE_THREAD

data class SharingTarget(
    val channelId: Long,
    val channelLabel: String,
    val avatarUrl: String,
    val clanId: Long,
    val clanName: String,
    val clanLogo: String,
    val channelType: Int,
    val isPrivate: Boolean,
    val parentId: Long,
    val parentChannelLabel: String,
    val lastActivityTs: Long
) {
    val isDm: Boolean get() = channelType == CHANNEL_TYPE_DM
    val isGroup: Boolean get() = channelType == CHANNEL_TYPE_GROUP
    val isThread: Boolean get() = channelType == CHANNEL_TYPE_THREAD || parentId != 0L
    val isClanChannel: Boolean get() = !isDm && !isGroup && clanId != 0L
}

fun DirectMessage.toSharingTarget(): SharingTarget = SharingTarget(
    channelId = channelId,
    channelLabel = displayName.ifBlank { label },
    avatarUrl = avatarUrl,
    clanId = 0L,
    clanName = "",
    clanLogo = "",
    channelType = type,
    isPrivate = type == CHANNEL_TYPE_DM || type == CHANNEL_TYPE_GROUP,
    parentId = 0L,
    parentChannelLabel = "",
    lastActivityTs = maxOf(lastSeenMessageTs, lastSentMessageTs)
)

fun ClanChannelEntity.toSharingTarget(
    clanName: String,
    clanLogo: String,
    parentChannelLabel: String = ""
): SharingTarget {
    val thread = parentId != 0L || type == CHANNEL_TYPE_THREAD
    return SharingTarget(
        channelId = channelId,
        channelLabel = channelLabel,
        avatarUrl = "",
        clanId = clanId,
        clanName = clanName,
        clanLogo = clanLogo,
        channelType = type,
        isPrivate = thread || isPrivate,
        parentId = parentId,
        parentChannelLabel = parentChannelLabel,
        lastActivityTs = maxOf(lastSeenMessageTs, lastSentMessageTs)
    )
}
