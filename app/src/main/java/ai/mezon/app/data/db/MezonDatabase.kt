package ai.mezon.app.data.db

import ai.mezon.app.home.chat.MessageEntity
import ai.mezon.app.home.messages.DirectMessage
import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [MessageEntity::class, DirectMessage::class],
    version = 3,
    exportSchema = false
)
abstract class MezonDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun directMessageDao(): DirectMessageDao
}
