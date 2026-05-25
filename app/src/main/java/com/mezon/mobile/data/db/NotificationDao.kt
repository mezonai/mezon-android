package com.mezon.mobile.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.mezon.mobile.home.notifications.NotificationEntity

@Dao
interface NotificationDao {

    @Query("SELECT * FROM notifications WHERE category = :category ORDER BY createTimeSeconds DESC, id DESC LIMIT :limit")
    suspend fun getByCategory(category: Int, limit: Int = 200): List<NotificationEntity>

    @Query("""
        SELECT * FROM notifications
        WHERE category = :category
        AND (createTimeSeconds < :lastTime OR (createTimeSeconds = :lastTime AND id < :lastId))
        ORDER BY createTimeSeconds DESC, id DESC LIMIT :limit
    """)
    suspend fun getByCategoryBefore(category: Int, lastTime: Long, lastId: Long, limit: Int = 50): List<NotificationEntity>

    @Upsert
    suspend fun upsertAll(notifications: List<NotificationEntity>)

    @Query("DELETE FROM notifications WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("""
        DELETE FROM notifications WHERE category = :category AND id NOT IN (
            SELECT id FROM notifications WHERE category = :category ORDER BY createTimeSeconds DESC LIMIT :keep
        )
    """)
    suspend fun trimCategory(category: Int, keep: Int = 200)

    @Query("""
        SELECT * FROM notifications
        WHERE category = :category AND clanId = :clanId AND topicId != 0
        AND channelId = :parentChannelId
        AND code IN (:mentionCode, :replyCode)
        ORDER BY createTimeSeconds DESC, id DESC LIMIT :limit
    """)
    suspend fun getTopicMentionsForChannel(
        category: Int,
        clanId: Long,
        parentChannelId: Long,
        mentionCode: Int,
        replyCode: Int,
        limit: Int = 200
    ): List<NotificationEntity>

    @Query("""
        SELECT * FROM notifications
        WHERE category = :category AND clanId = :clanId AND topicId != 0
        AND code IN (:mentionCode, :replyCode)
        ORDER BY createTimeSeconds DESC, id DESC LIMIT :limit
    """)
    suspend fun getTopicMentionsForClan(
        category: Int,
        clanId: Long,
        mentionCode: Int,
        replyCode: Int,
        limit: Int = 200
    ): List<NotificationEntity>
}
