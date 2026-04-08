package com.mezon.mobile.home.clans

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
import com.mezon.mezon.api.ChannelDescription
import com.mezon.mezon.rtapi.LastSeenMessageEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

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
    private val badgeCoordinator: dagger.Lazy<com.mezon.mobile.home.BadgeCoordinator>,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope
) {
    private val _channelsByClan = MutableStateFlow<Map<Long, List<ClanChannelEntity>>>(emptyMap())
    val channelsByClan: StateFlow<Map<Long, List<ClanChannelEntity>>> = _channelsByClan.asStateFlow()

    private val channelListLoading = ConcurrentHashMap<Long, Boolean>()

    init {
        observeSocketEvents()
    }

    fun isChannelListLoading(clanId: Long): Boolean = channelListLoading[clanId] == true

    fun cleanup() {
        _channelsByClan.value = emptyMap()
        currentOpenChannelId = 0L
        processedBadgeKeys.clear()
        channelListLoading.clear()
    }

    fun loadChannelsForClan(clanId: Long, force: Boolean = false) {
        appScope.launch { loadChannelsForClanNow(clanId, force) }
    }

    internal suspend fun loadChannelsForClanNow(clanId: Long, force: Boolean = false) {
        val cacheKey = apiCacheKey("listChannelsByClan", clanId.toString())
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
        val shouldForce = force
        if (!shouldForce && cacheTracker.shouldCall(cacheKey) == ApiCacheTracker.ShouldCall.SKIP) {
            return
        }
        channelListLoading[clanId] = true
        try {
            val entitiesList = sessionManager.withAutoRefresh { session ->
                val result = withContext(ioDispatcher) {
                    api.listChannelsByClan(session.apiUrl, session.token, clanId)
                }
                val categoryOrderMap = LinkedHashMap<Long, Int>()
                result.channeldescList.forEach { ch ->
                    categoryOrderMap.putIfAbsent(ch.categoryId, categoryOrderMap.size)
                }
                val entities = result.channeldescList.map { ch ->
                    withClanIdFromContext(
                        clanId,
                        ch.toClanChannelEntity().copy(categoryOrder = categoryOrderMap[ch.categoryId] ?: 0)
                    )
                }
                mergeCache(clanId, entities)
                runCatching {
                    val badge = api.listChannelBadgeCount(session.apiUrl, session.token, clanId)
                    if (badge.channeldescList.isNotEmpty()) {
                        applyChannelBadgeReadStatePatch(clanId, badge.channeldescList)
                    }
                }
                entities
            }
            cacheTracker.markCalled(cacheKey)
            val merged = _channelsByClan.value[clanId] ?: entitiesList
            withContext(ioDispatcher) { clanChannelDao.upsertAll(merged) }
            notificationCenter.postNotificationOnMainThread(NotificationCenter.channelsDidLoad, clanId)
        } catch (_: Exception) {
        } finally {
            channelListLoading.remove(clanId)
            badgeCoordinator.get().processDeferredQueue()
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
            val result = runCatching {
                sessionManager.withAutoRefresh { session ->
                    withContext(ioDispatcher) { api.listChannelsByClan(session.apiUrl, session.token, clanId) }
                }
            }.getOrNull() ?: return ""
            val entities = result.channeldescList.map { ch ->
                withClanIdFromContext(clanId, ch.toClanChannelEntity())
            }
            updateCache(clanId, entities)
            withContext(ioDispatcher) { clanChannelDao.upsertAll(entities) }
        }
        return findChannelById(channelId)?.channelLabel.orEmpty()
    }

    fun getChannelSections(clanId: Long): List<ChannelSection> {
        val channels = getChannels(clanId)
        val threads = channels.filter { it.isThread }.groupBy { it.parentId }
        val nonThreads = channels.filter { !it.isThread }.sortedBy { it.channelId }

        return nonThreads
            .groupBy { it.categoryId }
            .entries
            .sortedBy { (_, items) -> items.first().categoryOrder }
            .map { (_, items) ->
                val categoryId = items.first().categoryId
                val categoryName = items.first().categoryName
                val channelsWithThreads = items.flatMap { ch ->
                    val childThreads = threads[ch.channelId]?.sortedBy { it.channelId } ?: emptyList()
                    listOf(ch) + childThreads
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

    private fun withClanIdFromContext(contextClanId: Long, entity: ClanChannelEntity): ClanChannelEntity =
        if (entity.clanId != 0L) entity else entity.copy(clanId = contextClanId)

    private fun applyChannelBadgeReadStatePatch(clanId: Long, badgeDescs: List<ChannelDescription>) {
        val existing = _channelsByClan.value[clanId] ?: return
        if (badgeDescs.isEmpty()) return
        val byId = badgeDescs.associateBy { it.channelId }
        var changed = false
        val updated = existing.map { row ->
            val p = byId[row.channelId] ?: return@map row
            val (next, rowChanged) = patchChannelRowReadStateFromSparseBadge(row, p)
            if (rowChanged) changed = true
            next
        }
        if (!changed) return
        updateCache(clanId, updated)
    }

    private fun patchChannelRowReadStateFromSparseBadge(
        row: ClanChannelEntity,
        p: ChannelDescription
    ): Pair<ClanChannelEntity, Boolean> {
        var next = row
        var changed = false
        if (p.countMessUnread != next.unreadCount) {
            changed = true
            next = next.copy(unreadCount = p.countMessUnread)
        }
        if (p.hasLastSentMessage()) {
            val m = p.lastSentMessage
            if (m.id != 0L) {
                val merged = maxOf(next.lastSentMessageId, m.id)
                if (merged != next.lastSentMessageId) {
                    changed = true
                    next = next.copy(lastSentMessageId = merged)
                }
            }
            val ts = m.timestampSeconds.toLong() and 0xFFFF_FFFFL
            if (ts > 0L) {
                val mergedTs = maxOf(next.lastSentMessageTs, ts)
                if (mergedTs != next.lastSentMessageTs) {
                    changed = true
                    next = next.copy(lastSentMessageTs = mergedTs)
                }
            }
        }
        if (p.hasLastSeenMessage()) {
            val m = p.lastSeenMessage
            if (m.id != 0L) {
                val merged = maxOf(next.lastSeenMessageId, m.id)
                if (merged != next.lastSeenMessageId) {
                    changed = true
                    next = next.copy(lastSeenMessageId = merged)
                }
            }
            val ts = m.timestampSeconds.toLong() and 0xFFFF_FFFFL
            if (ts > 0L) {
                val mergedTs = maxOf(next.lastSeenMessageTs, ts)
                if (mergedTs != next.lastSeenMessageTs) {
                    changed = true
                    next = next.copy(lastSeenMessageTs = mergedTs)
                }
            }
        }
        return Pair(next, changed)
    }

    private fun mergeUnreadFromChannelList(cachedUnread: Int, apiNorm: ClanChannelEntity): Int {
        val listSignalsReadState =
            apiNorm.lastSeenMessageTs != 0L || apiNorm.lastSentMessageTs != 0L
        return when {
            apiNorm.unreadCount != 0 -> apiNorm.unreadCount
            listSignalsReadState -> apiNorm.unreadCount
            else -> cachedUnread
        }
    }

    private fun mergeCache(clanId: Long, apiChannels: List<ClanChannelEntity>) {
        val existing = _channelsByClan.value[clanId] ?: emptyList()
        val existingMap = existing.associateBy { it.channelId }
        val merged = apiChannels.map { apiCh ->
            val apiNorm = withClanIdFromContext(clanId, apiCh)
            val cached = existingMap[apiNorm.channelId]
            if (cached == null) {
                apiNorm
            } else {
                apiNorm.copy(
                    clanId = when {
                        apiNorm.clanId != 0L -> apiNorm.clanId
                        cached.clanId != 0L -> cached.clanId
                        else -> clanId
                    },
                    channelLabel = apiNorm.channelLabel.ifBlank { cached.channelLabel },
                    categoryName = apiNorm.categoryName.ifBlank { cached.categoryName },
                    topic = apiNorm.topic.ifBlank { cached.topic },
                    type = if (apiNorm.type != 0) apiNorm.type else cached.type,
                    parentId = if (apiNorm.parentId != 0L) apiNorm.parentId else cached.parentId,
                    categoryId = if (apiNorm.categoryId != 0L) apiNorm.categoryId else cached.categoryId,
                    isPrivate = if (apiNorm.type != 0) apiNorm.isPrivate else cached.isPrivate,
                    lastSeenMessageId = maxOf(cached.lastSeenMessageId, apiNorm.lastSeenMessageId),
                    lastSentMessageId = maxOf(cached.lastSentMessageId, apiNorm.lastSentMessageId),
                    lastSeenMessageTs = maxOf(cached.lastSeenMessageTs, apiNorm.lastSeenMessageTs),
                    lastSentMessageTs = maxOf(cached.lastSentMessageTs, apiNorm.lastSentMessageTs),
                    unreadCount = mergeUnreadFromChannelList(cached.unreadCount, apiNorm)
                )
            }
        }.sortedWith(compareBy<ClanChannelEntity> { it.categoryOrder }
            .thenBy { if (it.parentId == 0L) 0 else 1 }
            .thenBy { it.channelId })
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

    fun updateLastSentMessage(channelId: Long, messageId: Long, createTimeSeconds: Long = 0L) {
        for ((clanId, channels) in _channelsByClan.value) {
            val idx = channels.indexOfFirst { it.channelId == channelId }
            if (idx >= 0) {
                val ch = channels[idx]
                if (messageId <= ch.lastSentMessageId) return
                val ts = createTimeSeconds and 0xFFFF_FFFFL
                val newSentTs = if (ts > 0L) maxOf(ch.lastSentMessageTs, ts) else ch.lastSentMessageTs
                val updated = channels.toMutableList()
                updated[idx] = ch.copy(lastSentMessageId = messageId, lastSentMessageTs = newSentTs)
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

    fun updateChannelLastSeen(
        channelId: Long,
        messageId: Long,
        remainingUnread: Int,
        timestampSeconds: Int = 0
    ) {
        for ((clanId, channels) in _channelsByClan.value) {
            val idx = channels.indexOfFirst { it.channelId == channelId }
            if (idx >= 0) {
                val ch = channels[idx]
                if (messageId <= ch.lastSeenMessageId) return
                val oldUnread = ch.unreadCount
                val newUnread = remainingUnread.coerceAtLeast(0)
                if (newUnread == oldUnread && messageId == ch.lastSeenMessageId) return
                val tsLong = timestampSeconds.toLong() and 0xFFFF_FFFFL
                val syncTs = when {
                    newUnread == 0 -> maxOf(ch.lastSeenMessageTs, ch.lastSentMessageTs, tsLong)
                    tsLong > 0L -> maxOf(ch.lastSeenMessageTs, tsLong)
                    else -> ch.lastSeenMessageTs
                }
                val newRow = ch.copy(
                    lastSeenMessageId = messageId,
                    unreadCount = newUnread,
                    lastSeenMessageTs = syncTs
                )
                val updated = channels.toMutableList()
                updated[idx] = newRow
                val map = _channelsByClan.value.toMutableMap()
                map[clanId] = updated
                _channelsByClan.value = map
                val delta = newUnread - oldUnread
                if (delta != 0) clansController.get().updateClanBadgeCount(clanId, delta)
                appScope.launch(ioDispatcher) { clanChannelDao.upsert(newRow) }
                return
            }
        }
    }

    fun markChannelAsRead(channelId: Long, seenTimestampSeconds: Int = 0) {
        for ((clanId, channels) in _channelsByClan.value) {
            val idx = channels.indexOfFirst { it.channelId == channelId }
            if (idx >= 0) {
                val ch = channels[idx]
                if (!ch.hasUnread && ch.unreadCount == 0) return
                val oldUnread = ch.unreadCount
                val newSeenId = maxOf(ch.lastSeenMessageId, ch.lastSentMessageId)
                val tsLong = seenTimestampSeconds.toLong() and 0xFFFF_FFFFL
                val newSeenTs = maxOf(ch.lastSeenMessageTs, ch.lastSentMessageTs, tsLong)
                val newRow = ch.copy(
                    unreadCount = 0,
                    lastSeenMessageId = newSeenId,
                    lastSeenMessageTs = newSeenTs
                )
                val updated = channels.toMutableList()
                updated[idx] = newRow
                val map = _channelsByClan.value.toMutableMap()
                map[clanId] = updated
                _channelsByClan.value = map
                if (oldUnread > 0) clansController.get().updateClanBadgeCount(clanId, -oldUnread)
                appScope.launch(ioDispatcher) { clanChannelDao.upsert(newRow) }
                notificationCenter.postNotificationOnMainThread(NotificationCenter.channelsDidLoad, clanId)
                return
            }
        }
    }

    fun updateLastSeen(channelId: Long, messageId: Long, timestampSeconds: Int = 0) {
        for ((clanId, channels) in _channelsByClan.value) {
            val idx = channels.indexOfFirst { it.channelId == channelId }
            if (idx >= 0) {
                val ch = channels[idx]
                if (messageId <= ch.lastSeenMessageId) return
                val oldUnread = ch.unreadCount
                val tsLong = timestampSeconds.toLong() and 0xFFFF_FFFFL
                val newSeenTs = maxOf(ch.lastSeenMessageTs, ch.lastSentMessageTs, tsLong)
                val newRow = ch.copy(
                    lastSeenMessageId = messageId,
                    unreadCount = 0,
                    lastSeenMessageTs = newSeenTs
                )
                val updated = channels.toMutableList()
                updated[idx] = newRow
                val map = _channelsByClan.value.toMutableMap()
                map[clanId] = updated
                _channelsByClan.value = map
                if (oldUnread > 0) clansController.get().updateClanBadgeCount(clanId, -oldUnread)
                appScope.launch(ioDispatcher) { clanChannelDao.upsert(newRow) }
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.updateInterfaces, NotificationCenter.UPDATE_MASK_BADGE
                )
                return
            }
        }
    }

    private fun applyLastSeenMessageSocketEvent(event: LastSeenMessageEvent) {
        if (event.channelId == 0L || event.clanId == 0L) return
        val ts = event.timestampSeconds
        when {
            event.messageId != 0L && event.badgeCount == 0 ->
                updateLastSeen(event.channelId, event.messageId, ts)
            event.messageId != 0L ->
                updateChannelLastSeen(event.channelId, event.messageId, event.badgeCount, ts)
            ts != 0 ->
                applyLastSeenTsOnlyFromSocket(event.channelId, ts, event.badgeCount)
        }
    }

    private fun applyLastSeenTsOnlyFromSocket(channelId: Long, timestampSeconds: Int, badgeCount: Int) {
        val tsLong = timestampSeconds.toLong() and 0xFFFF_FFFFL
        if (tsLong == 0L) return
        for ((clanId, channels) in _channelsByClan.value) {
            val idx = channels.indexOfFirst { it.channelId == channelId }
            if (idx < 0) return
            val ch = channels[idx]
            val newSeenTs = maxOf(ch.lastSeenMessageTs, tsLong)
            val newUnread = badgeCount.coerceAtLeast(0)
            if (newSeenTs == ch.lastSeenMessageTs && newUnread == ch.unreadCount) return
            val oldUnread = ch.unreadCount
            val newRow = ch.copy(
                lastSeenMessageTs = newSeenTs,
                unreadCount = newUnread
            )
            val updated = channels.toMutableList()
            updated[idx] = newRow
            val map = _channelsByClan.value.toMutableMap()
            map[clanId] = updated
            _channelsByClan.value = map
            val delta = newUnread - oldUnread
            if (delta != 0) clansController.get().updateClanBadgeCount(clanId, delta)
            appScope.launch(ioDispatcher) { clanChannelDao.upsert(newRow) }
            notificationCenter.postNotificationOnMainThread(
                NotificationCenter.updateInterfaces, NotificationCenter.UPDATE_MASK_BADGE
            )
            return
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
                val msgTs = msg.createTimeSeconds.toLong() and 0xFFFF_FFFFL
                val clanId = findClanIdForChannel(msg.channelId)
                if (clanId != 0L) {
                    badgeCoordinator.get().onIncomingUnreadChannelSignal(clanId, msg.channelId, msg.messageId, msgTs)
                } else {
                    updateLastSentMessage(msg.channelId, msg.messageId, msgTs)
                    notificationCenter.postNotificationOnMainThread(
                        NotificationCenter.updateInterfaces, NotificationCenter.UPDATE_MASK_BADGE
                    )
                }
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
                applyLastSeenMessageSocketEvent(event)
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
