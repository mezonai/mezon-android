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
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ChannelController"
private const val NOTIFICATION_CODE_USER_MENTIONED = -9
private const val NOTIFICATION_CODE_USER_REPLIED = -11
private const val MAX_BADGE_CACHE = 500

@Singleton
class ChannelController @Inject constructor(
    private val api: MezonApi,
    private val sessionManager: SessionManager,
    private val clanChannelDao: ClanChannelDao,
    private val dispatcher: SocketEventDispatcher,
    private val notificationCenter: NotificationCenter,
    private val cacheTracker: ApiCacheTracker,
    private val clansController: dagger.Lazy<ClansController>,
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
        processedBadgeKeys.clear()
    }

    fun loadChannelsForClan(clanId: Long, force: Boolean = false) {
        val cacheKey = apiCacheKey("listChannelsByClan", clanId.toString())
        appScope.launch {
            var hasStaleReadState = false
            val inMemory = _channelsByClan.value[clanId]
            if (!inMemory.isNullOrEmpty()) {
                notificationCenter.postNotificationOnMainThread(NotificationCenter.channelsDidLoad, clanId)
            } else {
                val cached = withContext(ioDispatcher) { clanChannelDao.getByClan(clanId) }
                if (cached.isNotEmpty()) {
                    updateCache(clanId, cached)
                    notificationCenter.postNotificationOnMainThread(NotificationCenter.channelsDidLoad, clanId)
                    hasStaleReadState = cached.any { it.lastSeenMessageId == 0L && it.lastSentMessageId != 0L }
                }
            }
            val shouldForce = force || hasStaleReadState
            if (!shouldForce && cacheTracker.shouldCall(cacheKey) == ApiCacheTracker.ShouldCall.SKIP) return@launch
            try {
                val session = sessionManager.sessionFlow.first() ?: return@launch
                val result = withContext(ioDispatcher) {
                    api.listChannelsByClan(session.apiUrl, session.token, clanId)
                }
                val entities = result.channeldescList.map { it.toClanChannelEntity() }
                mergeCache(clanId, entities)
                cacheTracker.markCalled(cacheKey)
                withContext(ioDispatcher) { clanChannelDao.upsertAll(entities) }
                val merged = _channelsByClan.value[clanId] ?: entities
                clansController.get().syncClanBadgeFromChannels(clanId, merged)
                notificationCenter.postNotificationOnMainThread(NotificationCenter.channelsDidLoad, clanId)
            } catch (_: Exception) {
            }
        }
    }

    fun getChannels(clanId: Long): List<ClanChannelEntity> =
        _channelsByClan.value[clanId] ?: emptyList()

    fun findChannelById(channelId: Long): ClanChannelEntity? =
        _channelsByClan.value.values.flatten().firstOrNull { it.channelId == channelId }

    suspend fun findOrFetchChannelLabel(channelId: Long, clanId: Long = 0L): String {
        findChannelById(channelId)?.let { return it.channelLabel }
        val fromDb = withContext(ioDispatcher) { clanChannelDao.getByChannelId(channelId) }
        if (fromDb != null) {
            val existing = _channelsByClan.value[fromDb.clanId] ?: emptyList()
            updateCache(fromDb.clanId, existing.filter { it.channelId != fromDb.channelId } + fromDb)
            return fromDb.channelLabel
        }
        if (clanId != 0L) {
            val session = sessionManager.sessionFlow.first() ?: return ""
            val result = runCatching {
                withContext(ioDispatcher) { api.listChannelsByClan(session.apiUrl, session.token, clanId) }
            }.getOrNull() ?: return ""
            val entities = result.channeldescList.map { it.toClanChannelEntity() }
            updateCache(clanId, entities)
            withContext(ioDispatcher) { clanChannelDao.upsertAll(entities) }
        }
        return findChannelById(channelId)?.channelLabel.orEmpty()
    }

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

    private fun mergeCache(clanId: Long, apiChannels: List<ClanChannelEntity>) {
        val existing = _channelsByClan.value[clanId] ?: emptyList()
        val existingMap = existing.associateBy { it.channelId }
        val merged = apiChannels.map { apiCh ->
            val cached = existingMap[apiCh.channelId]
            if (cached == null) {
                apiCh
            } else {
                apiCh.copy(
                    lastSeenMessageId = maxOf(cached.lastSeenMessageId, apiCh.lastSeenMessageId),
                    unreadCount = if (apiCh.lastSentMessageId >= cached.lastSentMessageId) apiCh.unreadCount else cached.unreadCount
                )
            }
        }
        updateCache(clanId, merged)
    }

    private var currentOpenChannelId = 0L
    private val processedBadgeKeys = LinkedHashSet<String>()

    fun setCurrentChannel(channelId: Long) { currentOpenChannelId = channelId }
    fun clearCurrentChannel() { currentOpenChannelId = 0L }

    private fun isBadgeProcessed(channelId: Long, messageId: Long): Boolean {
        if (messageId == 0L) return false
        val key = "${channelId}_${messageId}"
        if (processedBadgeKeys.contains(key)) return true
        processedBadgeKeys.add(key)
        if (processedBadgeKeys.size > MAX_BADGE_CACHE) {
            val iter = processedBadgeKeys.iterator()
            val removeCount = processedBadgeKeys.size - MAX_BADGE_CACHE / 2
            repeat(removeCount) { if (iter.hasNext()) { iter.next(); iter.remove() } }
        }
        return false
    }

    fun clearBadgeDedup() {
        processedBadgeKeys.clear()
    }

    private fun findClanIdForChannel(channelId: Long): Long {
        for ((clanId, channels) in _channelsByClan.value) {
            if (channels.any { it.channelId == channelId }) return clanId
        }
        return 0L
    }

    fun updateLastSentMessage(channelId: Long, messageId: Long) {
        for ((clanId, channels) in _channelsByClan.value) {
            val idx = channels.indexOfFirst { it.channelId == channelId }
            if (idx >= 0) {
                val ch = channels[idx]
                if (messageId <= ch.lastSentMessageId) return
                val updated = channels.toMutableList()
                updated[idx] = ch.copy(lastSentMessageId = messageId)
                val map = _channelsByClan.value.toMutableMap()
                map[clanId] = updated
                _channelsByClan.value = map
                return
            }
        }
    }

    fun incrementUnread(channelId: Long, messageId: Long = 0L) {
        for ((clanId, channels) in _channelsByClan.value) {
            val idx = channels.indexOfFirst { it.channelId == channelId }
            if (idx >= 0) {
                val ch = channels[idx]
                val newLastSent = if (messageId > ch.lastSentMessageId) messageId else ch.lastSentMessageId
                val updated = channels.toMutableList()
                updated[idx] = ch.copy(
                    lastSentMessageId = newLastSent,
                    unreadCount = ch.unreadCount + 1
                )
                val map = _channelsByClan.value.toMutableMap()
                map[clanId] = updated
                _channelsByClan.value = map
                clansController.get().updateClanBadgeCount(clanId, 1)
                return
            }
        }
    }

    fun updateChannelLastSeen(channelId: Long, messageId: Long, remainingUnread: Int) {
        for ((clanId, channels) in _channelsByClan.value) {
            val idx = channels.indexOfFirst { it.channelId == channelId }
            if (idx >= 0) {
                val ch = channels[idx]
                if (messageId <= ch.lastSeenMessageId) return
                val oldUnread = ch.unreadCount
                val newUnread = remainingUnread.coerceAtLeast(0)
                if (newUnread == oldUnread && messageId == ch.lastSeenMessageId) return
                val updated = channels.toMutableList()
                updated[idx] = ch.copy(
                    lastSeenMessageId = messageId,
                    unreadCount = newUnread
                )
                val map = _channelsByClan.value.toMutableMap()
                map[clanId] = updated
                _channelsByClan.value = map
                val delta = newUnread - oldUnread
                if (delta != 0) clansController.get().updateClanBadgeCount(clanId, delta)
                appScope.launch(ioDispatcher) { clanChannelDao.updateLastSeen(channelId, messageId) }
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
                val oldUnread = ch.unreadCount
                val newSeenId = maxOf(ch.lastSeenMessageId, ch.lastSentMessageId)
                val updated = channels.toMutableList()
                updated[idx] = ch.copy(
                    unreadCount = 0,
                    lastSeenMessageId = newSeenId
                )
                val map = _channelsByClan.value.toMutableMap()
                map[clanId] = updated
                _channelsByClan.value = map
                if (oldUnread > 0) clansController.get().updateClanBadgeCount(clanId, -oldUnread)
                appScope.launch(ioDispatcher) { clanChannelDao.updateLastSeen(channelId, newSeenId) }
                notificationCenter.postNotificationOnMainThread(NotificationCenter.channelsDidLoad, clanId)
                return
            }
        }
    }

    fun updateLastSeen(channelId: Long, messageId: Long) {
        for ((clanId, channels) in _channelsByClan.value) {
            val idx = channels.indexOfFirst { it.channelId == channelId }
            if (idx >= 0) {
                val ch = channels[idx]
                if (messageId <= ch.lastSeenMessageId) return
                val oldUnread = ch.unreadCount
                val updated = channels.toMutableList()
                updated[idx] = ch.copy(
                    lastSeenMessageId = messageId,
                    unreadCount = 0
                )
                val map = _channelsByClan.value.toMutableMap()
                map[clanId] = updated
                _channelsByClan.value = map
                if (oldUnread > 0) clansController.get().updateClanBadgeCount(clanId, -oldUnread)
                appScope.launch(ioDispatcher) { clanChannelDao.updateLastSeen(channelId, messageId) }
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.updateInterfaces, NotificationCenter.UPDATE_MASK_BADGE
                )
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
            val currentUserId = sessionManager.sessionFlow
                .first { it != null }?.userId?.toLongOrNull() ?: 0L

            dispatcher.channelMessages.collect { msg ->
                if (msg.mode == STREAM_MODE_DM) return@collect
                if (msg.code == CODE_CHAT_UPDATE || msg.code == CODE_CHAT_REMOVE) return@collect
                if (msg.senderId == currentUserId) return@collect
                if (msg.channelId == currentOpenChannelId) return@collect
                updateLastSentMessage(msg.channelId, msg.messageId)
                val clanId = findClanIdForChannel(msg.channelId)
                if (clanId != 0L) {
                    clansController.get().setHasUnread(clanId)
                }
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.updateInterfaces, NotificationCenter.UPDATE_MASK_BADGE
                )
            }
        }

        appScope.launch {
            dispatcher.notifications.collect { notification ->
                val code = notification.code
                if (code != NOTIFICATION_CODE_USER_MENTIONED && code != NOTIFICATION_CODE_USER_REPLIED) return@collect
                val clanId = notification.clanId
                val channelId = notification.channelId
                if (clanId == 0L || channelId == 0L) return@collect
                if (channelId == currentOpenChannelId) return@collect

                val messageId = try {
                    val json = JSONObject(notification.content.toStringUtf8())
                    json.optLong("message_id", 0L).takeIf { it != 0L }
                        ?: json.optString("message_id", "").toLongOrNull() ?: 0L
                } catch (_: Exception) { 0L }

                if (isBadgeProcessed(channelId, messageId)) return@collect

                val channelInCache = _channelsByClan.value[clanId]?.any { it.channelId == channelId } == true
                if (channelInCache) {
                    incrementUnread(channelId, messageId)
                } else {
                    clansController.get().updateClanBadgeCount(clanId, 1)
                }
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.updateInterfaces, NotificationCenter.UPDATE_MASK_BADGE
                )
                Log.d(TAG, "Mention badge increment: channel=$channelId, clan=$clanId, inCache=$channelInCache")
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

        appScope.launch {
            dispatcher.lastSeenMessageEvents.collect { event ->
                if (event.channelId == 0L || event.messageId == 0L) return@collect
                if (event.clanId == 0L) return@collect
                updateLastSeen(event.channelId, event.messageId)
            }
        }

        appScope.launch {
            dispatcher.markAsRead.collect { event ->
                if (event.clanId == 0L) return@collect
                if (event.channelId != 0L) {
                    markChannelAsRead(event.channelId)
                } else {
                    val clanId = event.clanId
                    val channels = _channelsByClan.value[clanId] ?: return@collect
                    val toMark = if (event.categoryId != 0L) {
                        channels.filter { it.categoryId == event.categoryId }
                    } else {
                        channels
                    }
                    for (ch in toMark) {
                        if (ch.hasUnread || ch.unreadCount > 0) {
                            markChannelAsRead(ch.channelId)
                        }
                    }
                }
                notificationCenter.postNotificationOnMainThread(NotificationCenter.channelsDidLoad, event.clanId)
            }
        }
    }
}
