package com.mezon.mobile.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.mezon.mobile.home.notifications.NotificationEntity

@Dao
interface NotificationDao {

    @Query("SELECT * FROM notifications WHERE category = :category AND clanId = :clanId ORDER BY createTimeSeconds DESC, id DESC LIMIT :limit")
    suspend fun getByCategory(category: Int, clanId: Long, limit: Int = 200): List<NotificationEntity>

    @Query("""
        SELECT * FROM notifications 
        WHERE category = :category AND clanId = :clanId 
        AND (createTimeSeconds < :lastTime OR (createTimeSeconds = :lastTime AND id < :lastId))
        ORDER BY createTimeSeconds DESC, id DESC LIMIT :limit
    """)
    suspend fun getByCategoryBefore(category: Int, clanId: Long, lastTime: Long, lastId: Long, limit: Int = 50): List<NotificationEntity>

    @Upsert
    suspend fun upsertAll(notifications: List<NotificationEntity>)

    @Query("DELETE FROM notifications WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("""
        DELETE FROM notifications WHERE category = :category AND clanId = :clanId AND id NOT IN (
            SELECT id FROM notifications WHERE category = :category AND clanId = :clanId ORDER BY createTimeSeconds DESC LIMIT :keep
        )
    """)
    suspend fun trimCategory(category: Int, clanId: Long, keep: Int = 200)
}
