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
    private val streamStateRevisions = HashMap<Long, Long>()
    private var lifecycleGeneration = 0L

    init {
        appScope.launch { dispatcher.streamingJoinedEvents.collect { onStreamingJoined(it) } }
        appScope.launch { dispatcher.streamingLeavedEvents.collect { onStreamingLeaved(it) } }
    }

    fun cleanup() {
        synchronized(this) {
            lifecycleGeneration++
            streamMembersByClan.clear()
            streamStateRevisions.clear()
        }
        streamMemberListFetchInflight.clear()
        cacheTracker.invalidateByPrefix("ListStreamingChannelUsers")
    }

    @Synchronized
    fun getStreamMembersForChannel(channelId: Long, clanId: Long): List<Long> {
        return streamMembersByClan[clanId]?.get(channelId)?.let { ArrayList(it) } ?: emptyList()
    }

    fun fetchStreamChannelMembers(clanId: Long, noCache: Boolean = false) {
        if (clanId == 0L) return
        val key = apiCacheKey("ListStreamingChannelUsers", clanId)
        if (cacheTracker.shouldCall(key, noCache = noCache) == ApiCacheTracker.ShouldCall.SKIP) return
        if (!streamMemberListFetchInflight.add(clanId)) return

        val requestState = synchronized(this) {
            (streamStateRevisions[clanId] ?: 0L) to lifecycleGeneration
        }
        appScope.launch {
            try {
                val requestedRevision = requestState.first
                val requestedLifecycle = requestState.second
                sessionManager.withAutoRefresh { session ->
                    val response = withContext(ioDispatcher) {
                        api.listStreamingChannelUsers(session.apiUrl, session.token, clanId)
                    }
                    var changed = false
                    var applySnapshot = false
                    synchronized(this@StreamingController) {
                        val currentRevision = streamStateRevisions[clanId] ?: 0L
                        if (lifecycleGeneration == requestedLifecycle && currentRevision == requestedRevision) {
                            applySnapshot = true
                            val next = HashMap<Long, ArrayList<Long>>()
                            for (user in response.streamingChannelUsersList) {
                                val channelId = user.channelId
                                val userId = user.userId
                                if (channelId == 0L || userId == 0L) continue
                                val ids = next.getOrPut(channelId) { ArrayList() }
                                if (!ids.contains(userId)) ids.add(userId)
                            }
                            val previous = streamMembersByClan[clanId]
                            changed = previous != next
                            if (changed) streamMembersByClan[clanId] = next
                        }
                    }
                    if (applySnapshot) cacheTracker.markCalled(key)
                    if (applySnapshot && changed) {
                        notificationCenter.postNotificationOnMainThread(
                            NotificationCenter.voiceChannelMembersChanged, clanId
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "fetchStreamChannelMembers failed", e)
                cacheTracker.invalidate(key)
            } finally {
                streamMemberListFetchInflight.remove(clanId)
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
        if (ids.contains(userId)) return
        ids.add(userId)
        streamStateRevisions[clanId] = (streamStateRevisions[clanId] ?: 0L) + 1L
        notificationCenter.postNotificationOnMainThread(
            NotificationCenter.voiceChannelMembersChanged, clanId
        )
    }

    @Synchronized
    fun applyStreamLeaved(clanId: Long, channelId: Long, userId: Long) {
        if (clanId == 0L || channelId == 0L || userId == 0L) return
        val clanMap = streamMembersByClan[clanId] ?: return
        val ids = clanMap[channelId] ?: return
        if (!ids.remove(userId)) return
        if (ids.isEmpty()) clanMap.remove(channelId)
        if (clanMap.isEmpty()) streamMembersByClan.remove(clanId)
        streamStateRevisions[clanId] = (streamStateRevisions[clanId] ?: 0L) + 1L
        notificationCenter.postNotificationOnMainThread(
            NotificationCenter.voiceChannelMembersChanged, clanId
        )
    }

    private fun onStreamingJoined(event: StreamingJoinedEvent) {
        applyStreamJoined(event.clanId, event.streamingChannelId, event.userId)
    }

    private fun onStreamingLeaved(event: StreamingLeavedEvent) {
        val channelId = event.streamingChannelId.toLongOrNull() ?: return
        val userId = event.streamingUserId.toLongOrNull() ?: return
        applyStreamLeaved(event.clanId, channelId, userId)
    }
}
