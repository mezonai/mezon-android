package com.mezon.mobile.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.mezon.mobile.home.clans.ClanEntity

@Dao
interface ClanDao {
    @Query("SELECT * FROM clans ORDER BY clanOrder ASC LIMIT :limit")
    suspend fun getAll(limit: Int = 500): List<ClanEntity>

    @Query("SELECT * FROM clans WHERE clanId = :clanId LIMIT 1")
    suspend fun getById(clanId: Long): ClanEntity?

    @Upsert
    suspend fun upsertAll(clans: List<ClanEntity>)

    @Upsert
    suspend fun upsert(clan: ClanEntity)

    @Query("DELETE FROM clans WHERE clanId = :clanId")
    suspend fun delete(clanId: Long)

    @Query("UPDATE clans SET hasUnread = :hasUnread WHERE clanId = :clanId")
    suspend fun updateUnread(clanId: Long, hasUnread: Boolean)
}
