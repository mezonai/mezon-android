package com.mezon.mobile.home.clans

import androidx.room.Entity
import androidx.room.Index
import com.mezon.mezon.api.ChannelDescription

const val CHANNEL_TYPE_VOICE = 10
const val CHANNEL_TYPE_FORUM = 5
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
    val topic: String,
    val unreadCount: Int,
    val isMuted: Boolean
) {
    val isThread: Boolean get() = type == 7 && parentId != 0L
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
    topic = topic,
    unreadCount = countMessUnread,
    isMuted = isMute
)
