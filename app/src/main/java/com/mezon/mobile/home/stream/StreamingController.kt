package com.mezon.mobile.home.stream

import android.util.Log
import com.mezon.mezon.rtapi.StreamingJoinedEvent
import com.mezon.mezon.rtapi.StreamingLeavedEvent
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.network.ApiCacheTracker
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.network.SocketEventDispatcher
import com.mezon.mobile.network.apiCacheKey
import com.mezon.mobile.session.SessionManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "StreamingController"

@Singleton
class StreamingController @Inject constructor(
    private val api: MezonApi,
    private val dispatcher: SocketEventDispatcher,
    private val sessionManager: SessionManager,
    private val notificationCenter: NotificationCenter,
    private val cacheTracker: ApiCacheTracker,
    @ApplicationScope private val appScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val streamMembersByClan = HashMap<Long, HashMap<Long, ArrayList<Long>>>()
    private val streamMemberListFetchInflight = ConcurrentHashMap.newKeySet<Long>()

    init {
        appScope.launch { dispatcher.streamingJoinedEvents.collect { onStreamingJoined(it) } }
        appScope.launch { dispatcher.streamingLeavedEvents.collect { onStreamingLeaved(it) } }
    }

    fun cleanup() {
        synchronized(this) {
            streamMembersByClan.clear()
        }
        streamMemberListFetchInflight.clear()
    }

    @Synchronized
    fun getStreamMembersForChannel(channelId: Long, clanId: Long): List<Long> {
        return streamMembersByClan[clanId]?.get(channelId)?.let { ArrayList(it) } ?: emptyList()
    }

    fun fetchStreamChannelMembers(clanId: Long, noCache: Boolean = false) {
        if (clanId == 0L) return
        val key = apiCacheKey("ListStreamingChannelUsers", clanId)
        if (cacheTracker.shouldCall(key, noCache = noCache) == ApiCacheTracker.ShouldCall.SKIP) return
        if (!noCache && !streamMemberListFetchInflight.add(clanId)) return

        appScope.launch {
            try {
                sessionManager.withAutoRefresh { session ->
                    val response = withContext(ioDispatcher) {
                        api.listStreamingChannelUsers(session.apiUrl, session.token, clanId)
                    }
                    synchronized(this@StreamingController) {
                        val clanMap = streamMembersByClan.getOrPut(clanId) { HashMap() }
                        clanMap.clear()
                        for (user in response.streamingChannelUsersList) {
                            val channelId = user.channelId
                            val userId = user.userId
                            if (channelId == 0L || userId == 0L) continue
                            val ids = clanMap.getOrPut(channelId) { ArrayList() }
                            if (!ids.contains(userId)) ids.add(userId)
                        }
                    }
                    cacheTracker.markCalled(key)
                    notificationCenter.postNotificationOnMainThread(
                        NotificationCenter.voiceChannelMembersChanged, clanId
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "fetchStreamChannelMembers failed", e)
                cacheTracker.invalidate(key)
            } finally {
                if (!noCache) streamMemberListFetchInflight.remove(clanId)
            }
        }
    }

    @Synchronized
    fun applyStreamJoined(
        clanId: Long,
        channelId: Long,
        userId: Long,
    ) {
        if (clanId == 0L || channelId == 0L || userId == 0L) return
        val clanMap = streamMembersByClan.getOrPut(clanId) { HashMap() }
        val ids = clanMap.getOrPut(channelId) { ArrayList() }
        ids.remove(userId)
        ids.add(userId)
        notificationCenter.postNotificationOnMainThread(
            NotificationCenter.voiceChannelMembersChanged, clanId
        )
    }

    @Synchronized
    fun applyStreamLeaved(clanId: Long, channelId: Long, userId: Long) {
        if (clanId == 0L || channelId == 0L || userId == 0L) return
        val clanMap = streamMembersByClan[clanId] ?: return
        val ids = clanMap[channelId] ?: return
        ids.remove(userId)
        if (ids.isEmpty()) clanMap.remove(channelId)
        notificationCenter.postNotificationOnMainThread(
            NotificationCenter.voiceChannelMembersChanged, clanId
        )
    }

    private fun onStreamingJoined(event: StreamingJoinedEvent) {
        applyStreamJoined(event.clanId, event.streamingChannelId, event.userId)
    }

    private fun onStreamingLeaved(event: StreamingLeavedEvent) {
        if (event.clanId == 0L) return
        fetchStreamChannelMembers(event.clanId, noCache = true)
    }
}
