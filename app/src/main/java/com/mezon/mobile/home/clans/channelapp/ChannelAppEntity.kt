package com.mezon.mobile.home.clans.channelapp

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "channel_apps",
    indices = [Index("clanId")]
)
data class ChannelAppEntity(
    @PrimaryKey val channelId: Long,
    val clanId: Long,
    val appId: Long,
    val appUrl: String,
    val appName: String,
    val appLogo: String,
    val sortOrder: Int = 0
)
