package com.mezon.mobile.home.clans

import android.util.Log
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.data.db.ClanChannelDao
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.network.ApiCacheTracker
import com.mezon.mobile.network.CODE_CHAT_REMOVE
import com.mezon.mobile.network.CODE_CHAT_UPDATE
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.network.STREAM_MODE_DM
import com.mezon.mobile.network.SocketEventDispatcher
import com.mezon.mobile.network.apiCacheKey
import com.mezon.mobile.session.SessionManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ChannelController"

@Singleton
class ChannelController @Inject constructor(
    private val api: MezonApi,
    private val sessionManager: SessionManager,
    private val clanChannelDao: ClanChannelDao,
    private val dispatcher: SocketEventDispatcher,
    private val notificationCenter: NotificationCenter,
    private val cacheTracker: ApiCacheTracker,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope
) {
    private val _channelsByClan = MutableStateFlow<Map<Long, List<ClanChannelEntity>>>(emptyMap())
    val channelsByClan: StateFlow<Map<Long, List<ClanChannelEntity>>> = _channelsByClan.asStateFlow()

    init {
        observeSocketEvents()
    }

    fun cleanup() {
        _channelsByClan.value = emptyMap()
        currentOpenChannelId = 0L
    }

    fun loadChannelsForClan(clanId: Long, force: Boolean = false) {
        val cacheKey = apiCacheKey("listChannelsByClan", clanId.toString())
        appScope.launch {
            val inMemory = _channelsByClan.value[clanId]
            if (!inMemory.isNullOrEmpty()) {
                notificationCenter.postNotificationOnMainThread(NotificationCenter.channelsDidLoad, clanId)
            } else {
                val cached = withContext(ioDispatcher) { clanChannelDao.getByClan(clanId) }
                if (cached.isNotEmpty()) {
                    updateCache(clanId, cached)
                    notificationCenter.postNotificationOnMainThread(NotificationCenter.channelsDidLoad, clanId)
                }
            }
            if (!force && cacheTracker.shouldCall(cacheKey) == ApiCacheTracker.ShouldCall.SKIP) return@launch
            try {
                val session = sessionManager.sessionFlow.first() ?: return@launch
                val result = withContext(ioDispatcher) {
                    api.listChannelsByClan(session.apiUrl, session.token, clanId)
                }
                Log.d(TAG, "API returned ${result.channeldescCount} channels for clanId=$clanId")
                for (ch in result.channeldescList) {
                    Log.d(TAG, "  raw: label=${ch.channelLabel} type=${ch.type} countMessUnread=${ch.countMessUnread} active=${ch.active} parentId=${ch.parentId} hasSeen=${ch.hasLastSeenMessage()} hasSent=${ch.hasLastSentMessage()}")
                }
                val entities = result.channeldescList.map { it.toClanChannelEntity() }
                for (e in entities) {
                    if (e.unreadCount > 0 || e.isThread) {
                        Log.d(TAG, "Channel: ${e.channelLabel} id=${e.channelId} unread=${e.unreadCount} active=${e.active} isThread=${e.isThread} lastSeen=${e.lastSeenMessageId} lastSent=${e.lastSentMessageId}")
                    }
                }
                updateCache(clanId, entities)
                cacheTracker.markCalled(cacheKey)
                withContext(ioDispatcher) { clanChannelDao.upsertAll(entities) }
                notificationCenter.postNotificationOnMainThread(NotificationCenter.channelsDidLoad, clanId)
            } catch (e: Exception) {
                Log.e(TAG, "loadChannelsForClan clanId=$clanId failed", e)
            }
        }
    }

    fun getChannels(clanId: Long): List<ClanChannelEntity> =
        _channelsByClan.value[clanId] ?: emptyList()

    fun findChannelById(channelId: Long): ClanChannelEntity? =
        _channelsByClan.value.values.flatten().firstOrNull { it.channelId == channelId }

    fun getChannelSections(clanId: Long): List<ChannelSection> {
        val channels = getChannels(clanId)
        val threads = channels.filter { it.isThread }.groupBy { it.parentId }
        val nonThreads = channels.filter { !it.isThread }

        return nonThreads
            .groupBy { it.categoryId }
            .entries
            .sortedBy { it.key }
            .map { (_, items) ->
                val categoryId = items.first().categoryId
                val categoryName = items.first().categoryName
                val channelsWithThreads = items.flatMap { ch ->
                    listOf(ch) + (threads[ch.channelId] ?: emptyList())
                }
                ChannelSection(
                    categoryId = categoryId,
                    categoryName = categoryName,
                    channels = channelsWithThreads
                )
            }
    }

    private fun updateCache(clanId: Long, channels: List<ClanChannelEntity>) {
        val updated = _channelsByClan.value.toMutableMap()
        updated[clanId] = channels
        _channelsByClan.value = updated
    }

    private var currentOpenChannelId = 0L

    fun setCurrentChannel(channelId: Long) { currentOpenChannelId = channelId }
    fun clearCurrentChannel() { currentOpenChannelId = 0L }

    fun incrementUnread(channelId: Long, messageId: Long = 0L) {
        for ((clanId, channels) in _channelsByClan.value) {
            val idx = channels.indexOfFirst { it.channelId == channelId }
            if (idx >= 0) {
                val ch = channels[idx]
                val newLastSent = if (messageId > ch.lastSentMessageId) messageId else ch.lastSentMessageId
                val updated = channels.toMutableList()
                updated[idx] = ch.copy(lastSentMessageId = newLastSent)
                val map = _channelsByClan.value.toMutableMap()
                map[clanId] = updated
                _channelsByClan.value = map
                return
            }
        }
    }

    fun markChannelAsRead(channelId: Long) {
        for ((clanId, channels) in _channelsByClan.value) {
            val idx = channels.indexOfFirst { it.channelId == channelId }
            if (idx >= 0) {
                val ch = channels[idx]
                if (!ch.hasUnread && ch.unreadCount == 0) return
                val updated = channels.toMutableList()
                updated[idx] = ch.copy(
                    unreadCount = 0,
                    lastSeenMessageId = ch.lastSentMessageId
                )
                val map = _channelsByClan.value.toMutableMap()
                map[clanId] = updated
                _channelsByClan.value = map
                return
            }
        }
    }

    private fun observeSocketEvents() {
        appScope.launch {
            dispatcher.channelCreatedEvents.collect { event ->
                val clanId = event.clanId
                val newChannel = ClanChannelEntity(
                    clanId = clanId,
                    channelId = event.channelId,
                    parentId = event.parentId,
                    categoryId = event.categoryId,
                    categoryName = "",
                    channelLabel = event.channelLabel,
                    type = event.channelType,
                    isPrivate = event.channelPrivate != 0,
                    topic = "",
                    unreadCount = 0,
                    isMuted = false
                )
                val existing = _channelsByClan.value[clanId] ?: emptyList()
                updateCache(clanId, existing + newChannel)
                appScope.launch(ioDispatcher) { clanChannelDao.upsert(newChannel) }
                notificationCenter.postNotificationOnMainThread(NotificationCenter.channelsDidLoad, clanId)
            }
        }

        appScope.launch {
            dispatcher.channelDeletedEvents.collect { event ->
                val clanId = event.clanId
                val existing = _channelsByClan.value[clanId] ?: return@collect
                updateCache(clanId, existing.filter { it.channelId != event.channelId })
                appScope.launch(ioDispatcher) { clanChannelDao.delete(clanId, event.channelId) }
                notificationCenter.postNotificationOnMainThread(NotificationCenter.channelsDidLoad, clanId)
            }
        }

        appScope.launch {
            dispatcher.channelMessages.collect { msg ->
                if (msg.mode == STREAM_MODE_DM) return@collect
                if (msg.code == CODE_CHAT_UPDATE || msg.code == CODE_CHAT_REMOVE) return@collect
                if (msg.channelId == currentOpenChannelId) return@collect
                incrementUnread(msg.channelId, msg.messageId)
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.updateInterfaces, NotificationCenter.UPDATE_MASK_BADGE
                )
            }
        }

        appScope.launch {
            dispatcher.channelUpdatedEvents.collect { event ->
                val clanId = event.clanId
                val existing = _channelsByClan.value[clanId] ?: return@collect
                val updated = existing.map { ch ->
                    if (ch.channelId != event.channelId) ch
                    else ch.copy(
                        channelLabel = event.channelLabel.ifEmpty { ch.channelLabel },
                        topic = event.topic.ifEmpty { ch.topic },
                        isPrivate = event.channelPrivate
                    )
                }
                updateCache(clanId, updated)
                val entity = updated.find { it.channelId == event.channelId } ?: return@collect
                appScope.launch(ioDispatcher) { clanChannelDao.upsert(entity) }
                notificationCenter.postNotificationOnMainThread(NotificationCenter.channelsDidLoad, clanId)
            }
        }
    }
}
