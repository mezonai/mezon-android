package com.mezon.mobile.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.mezon.mobile.home.clans.channelapp.ChannelAppEntity

@Dao
interface ChannelAppDao {
    @Query("SELECT * FROM channel_apps WHERE clanId = :clanId ORDER BY sortOrder ASC LIMIT 500")
    suspend fun getByClan(clanId: Long): List<ChannelAppEntity>

    @Query("SELECT * FROM channel_apps WHERE channelId = :channelId LIMIT 1")
    suspend fun getByChannelId(channelId: Long): ChannelAppEntity?

    @Upsert
    suspend fun upsertAll(items: List<ChannelAppEntity>)

    @Query("DELETE FROM channel_apps WHERE clanId = :clanId")
    suspend fun deleteByClan(clanId: Long)

    @Query("DELETE FROM channel_apps WHERE channelId = :channelId")
    suspend fun deleteByChannelId(channelId: Long)
}
