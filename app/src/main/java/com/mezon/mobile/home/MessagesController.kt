package com.mezon.mobile.home

import com.mezon.mobile.home.clans.ChannelController
import com.mezon.mobile.home.clans.ClansController
import java.util.concurrent.ConcurrentHashMap
import android.util.LongSparseArray
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessagesController @Inject constructor(
    @Suppress("unused") val connectionController: ConnectionController,
    @Suppress("unused") val dialogsController: DialogsController,
    @Suppress("unused") val chatController: ChatController,
    @Suppress("unused") val clansController: ClansController,
    @Suppress("unused") val channelController: ChannelController
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
        users.clear()
        synchronized(this) { channels.clear() }
    }
}
