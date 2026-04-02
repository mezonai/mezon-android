package com.mezon.mobile.data.db

import com.mezon.mobile.home.chat.MessageEntity
import com.mezon.mobile.home.clans.ClanChannelEntity
import com.mezon.mobile.home.clans.ClanEntity
import com.mezon.mobile.home.messages.DirectMessage
import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        MessageEntity::class,
        DirectMessage::class,
        ClanEntity::class,
        ClanChannelEntity::class
    ],
    version = 19,
    exportSchema = false
)
abstract class MezonDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun directMessageDao(): DirectMessageDao
    abstract fun clanDao(): ClanDao
    abstract fun clanChannelDao(): ClanChannelDao
}
