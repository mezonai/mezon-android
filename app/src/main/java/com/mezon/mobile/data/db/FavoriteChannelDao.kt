package com.mezon.mobile.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert

@Entity(tableName = "favorite_channels", primaryKeys = ["clanId", "channelId"])
data class FavoriteChannelEntity(
    val clanId: Long,
    val channelId: Long,
    val sortOrder: Int = 0
)

@Dao
interface FavoriteChannelDao {
    @Query("SELECT channelId FROM favorite_channels WHERE clanId = :clanId ORDER BY sortOrder ASC")
    suspend fun getByClan(clanId: Long): List<Long>

    @Upsert
    suspend fun upsertAll(items: List<FavoriteChannelEntity>)

    @Query("DELETE FROM favorite_channels WHERE clanId = :clanId AND channelId = :channelId")
    suspend fun delete(clanId: Long, channelId: Long)

    @Query("DELETE FROM favorite_channels WHERE clanId = :clanId")
    suspend fun deleteByClan(clanId: Long)
}
