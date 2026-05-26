package com.mezon.mobile.home.clans

import androidx.room.Entity
import androidx.room.Index
import com.mezon.mezon.api.ChannelDescription
import com.mezon.mobile.home.extractLastSeenMessageId
import com.mezon.mobile.home.extractLastSeenMessageTs
import com.mezon.mobile.home.extractLastSentMessageId
import com.mezon.mobile.home.extractLastSentMessageTs

const val CHANNEL_TYPE_VOICE = 10
const val CHANNEL_TYPE_FORUM = 5
const val CHANNEL_TYPE_STREAMING = 6
const val CHANNEL_TYPE_ANNOUNCEMENT = 9
const val CHANNEL_TYPE_APP = 8

@Entity(
    tableName = "clan_channels",
    primaryKeys = ["clanId", "channelId"],
    indices = [Index(value = ["clanId", "categoryId"]), Index("channelId")]
)
data class ClanChannelEntity(
    val clanId: Long,
    val channelId: Long,
    val parentId: Long,
    val categoryId: Long,
    val categoryName: String,
    val channelLabel: String,
    val type: Int,
    val isPrivate: Boolean,
    val isAgeRestricted: Boolean = false,
    val topic: String,
    val unreadCount: Int,
    val isMuted: Boolean,
    val lastSeenMessageId: Long = 0L,
    val lastSentMessageId: Long = 0L,
    val lastSeenMessageTs: Long = 0L,
    val lastSentMessageTs: Long = 0L,
    val active: Int = 0,
    val categoryOrder: Int = 0,
    val ageRestricted: Int = 0,
) {
    val isThread: Boolean get() = type == 7 && parentId != 0L
    val hasUnread: Boolean get() {
        if (unreadCount > 0) return true
        if (lastSentMessageId != 0L && lastSeenMessageId < lastSentMessageId) return true
        if (lastSentMessageId == 0L && lastSeenMessageId == 0L &&
            lastSentMessageTs > lastSeenMessageTs && lastSentMessageTs != 0L
        ) return true
        return false
    }
}

fun ChannelDescription.toClanChannelEntity(): ClanChannelEntity = ClanChannelEntity(
    clanId = clanId,
    channelId = channelId,
    parentId = parentId,
    categoryId = categoryId,
    categoryName = categoryName,
    channelLabel = channelLabel,
    type = type,
    isPrivate = channelPrivate != 0,
    isAgeRestricted = ageRestricted != 0,
    topic = topic,
    unreadCount = countMessUnread,
    isMuted = isMute,
    lastSeenMessageId = extractLastSeenMessageId(),
    lastSentMessageId = extractLastSentMessageId(),
    lastSeenMessageTs = extractLastSeenMessageTs(),
    lastSentMessageTs = extractLastSentMessageTs(fallbackToCreateTime = false),
    active = active,
    ageRestricted = ageRestricted,
)
