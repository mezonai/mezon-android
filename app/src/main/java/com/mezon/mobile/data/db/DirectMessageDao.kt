package com.mezon.mobile.data.db

import com.mezon.mobile.home.messages.DirectMessage
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface DirectMessageDao {

    @Query("SELECT * FROM direct_messages ORDER BY lastMessageTimestamp DESC LIMIT :limit")
    suspend fun getAll(limit: Int = 200): List<DirectMessage>

    @Query("SELECT * FROM direct_messages WHERE channelId = :channelId")
    suspend fun getById(channelId: Long): DirectMessage?

    @Upsert
    suspend fun upsertAll(directs: List<DirectMessage>)

    @Upsert
    suspend fun upsert(direct: DirectMessage)

    @Query("UPDATE direct_messages SET unreadCount = :count WHERE channelId = :channelId")
    suspend fun updateUnreadCount(channelId: Long, count: Int)

    @Query("UPDATE direct_messages SET isOnline = :online WHERE channelId = :channelId")
    suspend fun updateOnlineStatus(channelId: Long, online: Boolean)
}
