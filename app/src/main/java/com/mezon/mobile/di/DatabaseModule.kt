package com.mezon.mobile.di

import com.mezon.mobile.data.db.ChannelAppDao
import com.mezon.mobile.data.db.ClanChannelDao
import com.mezon.mobile.data.db.ClanDao
import com.mezon.mobile.data.db.ClanRoleCacheDao
import com.mezon.mobile.data.db.ClanRoleListMetaDao
import com.mezon.mobile.data.db.ClanUserMaxPermissionDao
import com.mezon.mobile.data.db.DirectMessageDao
import com.mezon.mobile.data.db.FavoriteChannelDao
import com.mezon.mobile.data.db.MezonDatabase
import com.mezon.mobile.data.db.MessageDao
import com.mezon.mobile.data.db.NotificationDao
import com.mezon.mobile.data.db.PermissionCatalogDao
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
    @Singleton
    fun provideMessageDao(db: MezonDatabase): MessageDao = db.messageDao()

    @Provides
    @Singleton
    fun provideDirectMessageDao(db: MezonDatabase): DirectMessageDao = db.directMessageDao()

    @Provides
    @Singleton
    fun provideClanDao(db: MezonDatabase): ClanDao = db.clanDao()

    @Provides
    @Singleton
    fun provideClanChannelDao(db: MezonDatabase): ClanChannelDao = db.clanChannelDao()

    @Provides
    @Singleton
    fun provideNotificationDao(db: MezonDatabase): NotificationDao = db.notificationDao()

    @Provides
    @Singleton
    fun provideFavoriteChannelDao(db: MezonDatabase): FavoriteChannelDao = db.favoriteChannelDao()

    @Provides
    @Singleton
    fun provideChannelAppDao(db: MezonDatabase): ChannelAppDao = db.channelAppDao()

    @Provides
    @Singleton
    fun providePermissionCatalogDao(db: MezonDatabase): PermissionCatalogDao = db.permissionCatalogDao()

    @Provides
    @Singleton
    fun provideClanUserMaxPermissionDao(db: MezonDatabase): ClanUserMaxPermissionDao =
        db.clanUserMaxPermissionDao()

    @Provides
    @Singleton
    fun provideClanRoleListMetaDao(db: MezonDatabase): ClanRoleListMetaDao =
        db.clanRoleListMetaDao()

    @Provides
    @Singleton
    fun provideClanRoleCacheDao(db: MezonDatabase): ClanRoleCacheDao =
        db.clanRoleCacheDao()
}
