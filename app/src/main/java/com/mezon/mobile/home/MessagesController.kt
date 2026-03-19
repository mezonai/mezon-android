package com.mezon.mobile.home

import android.util.LongSparseArray
import com.mezon.mobile.data.db.MezonDatabase
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.home.clans.ChannelController
import com.mezon.mobile.home.clans.ClansController
import com.mezon.mobile.home.notifications.NotificationStore
import com.mezon.mobile.network.ApiCacheTracker
import com.mezon.mobile.notification.ActiveChannelTracker
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessagesController @Inject constructor(
    @Suppress("unused") val connectionController: ConnectionController,
    @Suppress("unused") val dialogsController: DialogsController,
    @Suppress("unused") val chatController: ChatController,
    @Suppress("unused") val clansController: ClansController,
    @Suppress("unused") val channelController: ChannelController,
    private val notificationStore: NotificationStore,
    private val activeChannelTracker: ActiveChannelTracker,
    private val cacheTracker: ApiCacheTracker,
    private val database: MezonDatabase,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope
) {

    val users = ConcurrentHashMap<Long, CachedUser>(100, 1.0f, 2)
    val channels = LongSparseArray<CachedChannel>()

    fun getUser(userId: Long): CachedUser? = users[userId]

    fun putUser(user: CachedUser) {
        users[user.id] = user
    }

    fun putUsers(list: List<CachedUser>) {
        for (u in list) users[u.id] = u
    }

    @Synchronized
    fun getChannel(channelId: Long): CachedChannel? = channels[channelId]

    @Synchronized
    fun putChannel(channel: CachedChannel) {
        channels.put(channel.channelId, channel)
    }

    @Synchronized
    fun putChannels(list: List<CachedChannel>) {
        for (ch in list) channels.put(ch.channelId, ch)
    }

    fun disconnect() {
        connectionController.disconnect()
        clansController.cleanup()
        dialogsController.cleanup()
        chatController.cleanup()
        channelController.cleanup()
        notificationStore.cleanup()
        activeChannelTracker.clear()
        cacheTracker.invalidateAll()
        users.clear()
        synchronized(this) { channels.clear() }
        appScope.launch(ioDispatcher) { database.clearAllTables() }
    }
}
