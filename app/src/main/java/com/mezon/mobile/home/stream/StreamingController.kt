package com.mezon.mobile.home.stream

import android.util.Log
import com.mezon.mezon.api.StreamingChannelUser
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.network.ApiCacheTracker
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.network.MezonSocket
import com.mezon.mobile.network.SocketEventDispatcher
import com.mezon.mobile.network.apiCacheKey
import com.mezon.mobile.session.SessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Server-owned stream presence, independent of voice/SFU membership. */
@Singleton
class StreamingController @Inject constructor(
    private val api: MezonApi,
    dispatcher: SocketEventDispatcher,
    socket: MezonSocket,
    private val sessionManager: SessionManager,
    private val notificationCenter: NotificationCenter,
    private val cacheTracker: ApiCacheTracker,
    @ApplicationScope private val appScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val members = HashMap<Long, MutableMap<Long, LinkedHashMap<Long, StreamingChannelUser>>>()
    private val viewerCounts = HashMap<Long, MutableMap<Long, Int>>()
    private val revisions = HashMap<Long, Long>()
    private val inflight = ConcurrentHashMap.newKeySet<Long>()
    private var lifecycleGeneration = 0L
    private val notificationJobs = HashMap<Long, Job>()
    private val _changes = MutableStateFlow<Map<Long, Long>>(emptyMap())
    val changes = _changes.asStateFlow()

    init {
        appScope.launch {
            dispatcher.streamingJoinedEvents.collect { event ->
                applyStreamJoined(event.clanId, event.streamingChannelId, event.userId, event.isPublisher, event.participant)
            }
        }
        appScope.launch {
            dispatcher.streamingLeavedEvents.collect { event ->
                applyStreamLeaved(event.clanId, event.streamingChannelId.toLongOrNull() ?: 0, event.streamingUserId.toLongOrNull() ?: 0)
            }
        }
        appScope.launch {
            dispatcher.streamingEndedEvents.collect { event -> endStream(event.clanId, event.channelId) }
        }
        appScope.launch {
            dispatcher.streamingViewerCountEvents.collect { event ->
                synchronized(this@StreamingController) {
                    viewerCounts.getOrPut(event.clanId) { HashMap() }[event.channelId] = event.viewerCount.coerceAtLeast(0)
                    changed(event.clanId, membership = false)
                }
            }
        }
        appScope.launch {
            socket.reconnected.collect {
                val clans = synchronized(this@StreamingController) { members.keys.toList() }
                clans.forEach { fetchStreamChannelMembers(it, noCache = true) }
            }
        }
    }

    @Synchronized fun cleanup() {
        lifecycleGeneration++
        members.clear()
        viewerCounts.clear()
        revisions.clear()
        _changes.value = emptyMap()
        inflight.clear()
        notificationJobs.values.forEach { it.cancel() }; notificationJobs.clear()
        cacheTracker.invalidateByPrefix("ListStreamingChannelUsers")
    }

    @Synchronized fun getStreamMembersForChannel(channelId: Long, clanId: Long): List<Long> {
        val entries = members[clanId]?.get(channelId)?.values ?: return emptyList()
        val publisher = entries.firstOrNull { it.isPublisher } ?: entries.firstOrNull() ?: return emptyList()
        return listOf(publisher.userId) + entries.asSequence().filter { it.userId != publisher.userId }.map { it.userId }.toList()
    }

    @Synchronized fun getViewerCount(channelId: Long, clanId: Long): Int =
        viewerCounts[clanId]?.get(channelId) ?: ((members[clanId]?.get(channelId)?.size ?: 0) - 1).coerceAtLeast(0)

    @Synchronized fun getParticipantName(channelId: Long, clanId: Long, userId: Long): String =
        members[clanId]?.get(channelId)?.get(userId)?.participant.orEmpty()

    fun fetchStreamChannelMembers(clanId: Long, noCache: Boolean = false) {
        if (clanId == 0L) return
        val key = apiCacheKey("ListStreamingChannelUsers", clanId)
        if (cacheTracker.shouldCall(key, noCache = noCache) == ApiCacheTracker.ShouldCall.SKIP || !inflight.add(clanId)) return
        val lifecycle = synchronized(this) { lifecycleGeneration }
        appScope.launch {
            try {
                // A snapshot racing a socket event must not overwrite newer presence.
                repeat(3) {
                    val revision = synchronized(this@StreamingController) { revisions[clanId] ?: 0L }
                    val response = sessionManager.withAutoRefresh { session ->
                        withContext(ioDispatcher) { api.listStreamingChannelUsers(session.apiUrl, session.token, clanId) }
                    }
                    var applied = false
                    synchronized(this@StreamingController) {
                        if (lifecycle != lifecycleGeneration) return@launch
                        if (revision == (revisions[clanId] ?: 0L)) {
                            val next = HashMap<Long, LinkedHashMap<Long, StreamingChannelUser>>()
                            response.streamingChannelUsersList.forEach { user ->
                                if (user.channelId != 0L && user.userId != 0L) {
                                    val channel = next.getOrPut(user.channelId) { LinkedHashMap() }
                                    if (channel[user.userId]?.isPublisher != true || user.isPublisher) channel[user.userId] = user
                                }
                            }
                            val old = members[clanId]
                            members[clanId] = next
                            // Empty authoritative channels cannot retain an old aggregate count.
                            viewerCounts[clanId]?.keys?.retainAll(next.keys)
                            cacheTracker.markCalled(key)
                            applied = true
                            if (old != next) changed(clanId)
                        }
                    }
                    if (applied) return@launch
                }
                cacheTracker.invalidate(key)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.w("StreamingController", "Presence refresh failed (${e.javaClass.simpleName})")
                cacheTracker.invalidate(key)
            } finally {
                synchronized(this@StreamingController) { if (lifecycle == lifecycleGeneration) inflight.remove(clanId) }
            }
        }
    }

    @Synchronized fun applyStreamJoined(clanId: Long, channelId: Long, userId: Long, isPublisher: Boolean = false, participant: String = "") {
        if (clanId == 0L || channelId == 0L || userId == 0L) return
        val channel = members.getOrPut(clanId) { HashMap() }.getOrPut(channelId) { LinkedHashMap() }
        val existing = channel[userId]
        val entry = StreamingChannelUser.newBuilder().setChannelId(channelId).setUserId(userId)
            .setParticipant(participant.ifBlank { existing?.participant.orEmpty() })
            .setIsPublisher(isPublisher || existing?.isPublisher == true).build()
        if (existing == entry) return
        channel[userId] = entry
        changed(clanId)
    }

    @Synchronized fun applyStreamLeaved(clanId: Long, channelId: Long, userId: Long) {
        val channel = members[clanId]?.get(channelId) ?: return
        val publisher = channel.values.firstOrNull { it.isPublisher }?.userId ?: channel.keys.firstOrNull()
        if (publisher == userId) { endStream(clanId, channelId); return }
        if (channel.remove(userId) == null) return
        changed(clanId)
    }

    @Synchronized fun endStream(clanId: Long, channelId: Long) {
        if (clanId == 0L || channelId == 0L) return
        members[clanId]?.remove(channelId)
        viewerCounts[clanId]?.remove(channelId)
        // An ended event must stop an active session even if no member snapshot was cached.
        changed(clanId)
    }

    private fun changed(clanId: Long, membership: Boolean = true) {
        if (membership) {
            revisions[clanId] = (revisions[clanId] ?: 0L) + 1L
            // Retain the latest revision of every clan so a busy channel cannot drop a publisher-end update.
            _changes.value = HashMap(revisions)
        }
        if (notificationJobs[clanId]?.isActive == true) return
        val lifecycle = lifecycleGeneration
        notificationJobs[clanId] = appScope.launch {
            delay(150)
            synchronized(this@StreamingController) {
                if (lifecycle != lifecycleGeneration) return@launch
                notificationJobs.remove(clanId)
                notificationCenter.postNotificationOnMainThread(NotificationCenter.voiceChannelMembersChanged, clanId)
            }
        }
    }
}
