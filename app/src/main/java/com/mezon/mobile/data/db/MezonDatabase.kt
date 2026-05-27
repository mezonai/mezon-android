package com.mezon.mobile.data.db

import com.mezon.mobile.home.chat.MessageEntity
import com.mezon.mobile.home.clans.ClanChannelEntity
import com.mezon.mobile.home.clans.ClanEntity
import com.mezon.mobile.home.clans.channelapp.ChannelAppEntity
import com.mezon.mobile.home.messages.DirectMessage
import com.mezon.mobile.home.notifications.NotificationEntity
import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        MessageEntity::class,
        DirectMessage::class,
        ClanEntity::class,
        ClanChannelEntity::class,
        NotificationEntity::class,
        FavoriteChannelEntity::class,
        ChannelAppEntity::class,
        PermissionCatalogEntity::class,
        ClanUserMaxPermissionEntity::class,
        ClanRoleListMetaEntity::class,
        ClanRoleCacheEntity::class,
    ],
    version = 35,
    exportSchema = false
)
abstract class MezonDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun directMessageDao(): DirectMessageDao
    abstract fun clanDao(): ClanDao
    abstract fun clanChannelDao(): ClanChannelDao
    abstract fun notificationDao(): NotificationDao
    abstract fun favoriteChannelDao(): FavoriteChannelDao
    abstract fun channelAppDao(): ChannelAppDao
    abstract fun permissionCatalogDao(): PermissionCatalogDao
    abstract fun clanUserMaxPermissionDao(): ClanUserMaxPermissionDao
    abstract fun clanRoleListMetaDao(): ClanRoleListMetaDao
    abstract fun clanRoleCacheDao(): ClanRoleCacheDao
}
