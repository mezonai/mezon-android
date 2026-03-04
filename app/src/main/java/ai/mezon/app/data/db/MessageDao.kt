package ai.mezon.app.data.db

import ai.mezon.app.home.chat.MessageEntity
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface MessageDao {

    @Query("SELECT * FROM (SELECT * FROM messages WHERE channelId = :channelId ORDER BY timestampSeconds DESC LIMIT :limit) ORDER BY timestampSeconds ASC")
    suspend fun getLatestByChannel(channelId: Long, limit: Int = 200): List<MessageEntity>

    @Upsert
    suspend fun upsertAll(messages: List<MessageEntity>)

    @Upsert
    suspend fun upsert(message: MessageEntity)

    @Query("DELETE FROM messages WHERE channelId = :channelId AND id = :messageId")
    suspend fun delete(channelId: Long, messageId: Long)

    @Query("DELETE FROM messages WHERE channelId = :channelId")
    suspend fun deleteByChannel(channelId: Long)
}
