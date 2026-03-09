package com.mezon.mobile.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.mezon.mobile.home.clans.ClanChannelEntity

@Dao
interface ClanChannelDao {
    @Query("SELECT * FROM clan_channels WHERE clanId = :clanId ORDER BY categoryId ASC, channelLabel ASC")
    suspend fun getByClan(clanId: Long): List<ClanChannelEntity>

    @Upsert
    suspend fun upsertAll(channels: List<ClanChannelEntity>)

    @Upsert
    suspend fun upsert(channel: ClanChannelEntity)

    @Query("DELETE FROM clan_channels WHERE clanId = :clanId AND channelId = :channelId")
    suspend fun delete(clanId: Long, channelId: Long)

    @Query("UPDATE clan_channels SET unreadCount = :count WHERE channelId = :channelId")
    suspend fun updateUnread(channelId: Long, count: Int)
}
