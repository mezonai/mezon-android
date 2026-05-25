package com.mezon.mobile.data.db

import com.mezon.mobile.home.chat.MessageEntity
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface MessageDao {

    @Query("SELECT * FROM (SELECT * FROM messages WHERE channelId = :channelId ORDER BY timestampSeconds DESC LIMIT :limit) ORDER BY timestampSeconds ASC")
    suspend fun getLatestByChannel(channelId: Long, limit: Int = 50): List<MessageEntity>

    @Query("""
        SELECT * FROM (
            SELECT * FROM messages WHERE channelId = :channelId AND id <= :anchorId ORDER BY id DESC LIMIT :halfLimit
        )
        UNION ALL
        SELECT * FROM (
            SELECT * FROM messages WHERE channelId = :channelId AND id > :anchorId ORDER BY id ASC LIMIT :halfLimit
        )
        ORDER BY id ASC
    """)
    suspend fun getMessagesAround(channelId: Long, anchorId: Long, halfLimit: Int = 25): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE channelId = :channelId AND id < :beforeId ORDER BY id DESC LIMIT :limit")
    suspend fun getMessagesBefore(channelId: Long, beforeId: Long, limit: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE channelId = :channelId AND id > :afterId ORDER BY id ASC LIMIT :limit")
    suspend fun getMessagesAfter(channelId: Long, afterId: Long, limit: Int): List<MessageEntity>

    @Upsert
    suspend fun upsertAll(messages: List<MessageEntity>)

    @Upsert
    suspend fun upsert(message: MessageEntity)

    @Query("DELETE FROM messages WHERE channelId = :channelId AND id NOT IN (SELECT id FROM messages WHERE channelId = :channelId ORDER BY timestampSeconds DESC LIMIT :keep)")
    suspend fun trimToLatest(channelId: Long, keep: Int = 50)

    @Query("""
        DELETE FROM messages WHERE channelId = :channelId AND id NOT IN (
            SELECT id FROM (
                SELECT id FROM messages WHERE channelId = :channelId AND id <= :anchorId ORDER BY id DESC LIMIT :halfKeep
            )
            UNION ALL
            SELECT id FROM (
                SELECT id FROM messages WHERE channelId = :channelId AND id > :anchorId ORDER BY id ASC LIMIT :halfKeep
            )
        )
    """)
    suspend fun trimAround(channelId: Long, anchorId: Long, halfKeep: Int = 100)

    @Query("UPDATE messages SET content = :content, updateTimeSeconds = :updateTime, hideEditted = :hideEditted, code = :code WHERE channelId = :channelId AND id = :messageId")
    suspend fun updateContent(channelId: Long, messageId: Long, content: String, updateTime: Long, hideEditted: Boolean, code: Int)

    @Query("DELETE FROM messages WHERE channelId = :channelId AND id = :messageId")
    suspend fun delete(channelId: Long, messageId: Long)

    @Query("DELETE FROM messages WHERE channelId = :channelId")
    suspend fun deleteByChannel(channelId: Long)

    @Query("SELECT * FROM messages WHERE channelId = :channelId AND id = :messageId LIMIT 1")
    suspend fun getById(channelId: Long, messageId: Long): MessageEntity?

    @Query("""
        SELECT * FROM messages
        WHERE channelId = :parentChannelId AND topicId = :topicId
        ORDER BY timestampSeconds DESC LIMIT 1
    """)
    suspend fun getLatestByTopicId(parentChannelId: Long, topicId: Long): MessageEntity?

    @Query("SELECT * FROM messages WHERE channelId = :channelId AND id IN (:ids)")
    suspend fun getByIds(channelId: Long, ids: List<Long>): List<MessageEntity>

    @Query("UPDATE messages SET reactionsJson = :reactionsJson WHERE channelId = :channelId AND id = :messageId")
    suspend fun updateReactions(channelId: Long, messageId: Long, reactionsJson: String)
}
