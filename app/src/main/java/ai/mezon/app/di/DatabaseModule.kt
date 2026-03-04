package ai.mezon.app.di

import ai.mezon.app.data.db.DirectMessageDao
import ai.mezon.app.data.db.MezonDatabase
import ai.mezon.app.data.db.MessageDao
import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MezonDatabase =
        Room.databaseBuilder(context, MezonDatabase::class.java, "mezon.db")
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideMessageDao(db: MezonDatabase): MessageDao = db.messageDao()

    @Provides
    fun provideDirectMessageDao(db: MezonDatabase): DirectMessageDao = db.directMessageDao()
}
