package com.mezon.mobile.home.clans

import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.StartupCache
import com.mezon.mobile.data.db.ClanChannelDao
import com.mezon.mobile.data.db.FavoriteChannelDao
import com.mezon.mobile.data.db.FavoriteChannelEntity
import com.mezon.mobile.data.db.MessageDao
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.network.ApiCacheTracker
import com.mezon.mobile.network.CHANNEL_TYPE_DM
import com.mezon.mobile.network.CHANNEL_TYPE_GROUP
import com.mezon.mobile.network.CODE_CHAT_REMOVE
import com.mezon.mobile.network.CODE_CHAT_UPDATE
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.network.STREAM_MODE_DM
import com.mezon.mobile.network.SocketEventDispatcher
import com.mezon.mobile.network.apiCacheKey
import com.mezon.mobile.session.SessionManager
import com.mezon.mezon.api.ChannelDescription
import com.mezon.mobile.home.chat.SdTopicEntity
import com.mezon.mobile.home.chat.toClanChannelEntity
import com.mezon.mezon.rtapi.LastSeenMessageEvent
import com.mezon.mezon.rtapi.UserChannelAdded
import com.mezon.mezon.rtapi.UserChannelRemoved
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

const val FAVORITE_CATEGORY_ID = -1L
const val FAVORITE_CATEGORY_NAME = "Favorites"

@Singleton
class ChannelController @Inject constructor(
    private val api: MezonApi,
    private val sessionManager: SessionManager,
    private val clanChannelDao: ClanChannelDao,
    private val favoriteChannelDao: FavoriteChannelDao,
    private val messageDao: MessageDao,
    private val dispatcher: SocketEventDispatcher,
    private val notificationCenter: NotificationCenter,
    private val cacheTracker: ApiCacheTracker,
    private val clansController: dagger.Lazy<ClansController>,
    private val badgeCoordinator: dagger.Lazy<com.mezon.mobile.home.BadgeCoordinator>,
    private val topicBadgeTracker: dagger.Lazy<com.mezon.mobile.home.TopicBadgeTracker>,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope
) {
    private val _channelsByClan = MutableStateFlow<Map<Long, List<ClanChannelEntity>>>(emptyMap())
    val channelsByClan: StateFlow<Map<Long, List<ClanChannelEntity>>> = _channelsByClan.asStateFlow()

    @Volatile
    private var _channelByIdSnapshot: Map<Long, List<ClanChannelEntity>>? = null
    @Volatile
    private var _channelByIdIndex: Map<Long, ClanChannelEntity> = emptyMap()

    private val channelListLoading = ConcurrentHashMap<Long, Boolean>()
    private val channelListNetworkFetchInflight = ConcurrentHashMap.newKeySet<Long>()
    private val favoritesByClan = ConcurrentHashMap<Long, MutableSet<Long>>()
    private val sdTopicChannelsById = ConcurrentHashMap<Long, ClanChannelEntity>()

    init {
        observeSocketEvents()
    }

    fun isChannelListLoading(clanId: Long): Boolean = channelListLoading[clanId] == true

    fun cleanup() {
        _channelsByClan.value = emptyMap()
        sdTopicChannelsById.clear()
        currentOpenChannelId = 0L
        currentOpenTopicId = 0L
        synchronized(badgeKeyLock) { processedBadgeKeys.clear() }
        channelListLoading.clear()
        channelListNetworkFetchInflight.clear()
        favoritesByClan.clear()
    }

    fun loadChannelsForClan(clanId: Long, force: Boolean = false) {
        appScope.launch { loadChannelsForClanNow(clanId, force) }
    }

    fun purgeClanChannelsCache(clanId: Long) {
        if (clanId == 0L) return
        clearSdTopicsForClan(clanId)
        val m = _channelsByClan.value.toMutableMap()
        m.remove(clanId)
        _channelsByClan.value = m
        favoritesByClan.remove(clanId)
        channelListLoading.remove(clanId)
        channelListNetworkFetchInflight.remove(clanId)
        appScope.launch(ioDispatcher) {
            clanChannelDao.deleteByClan(clanId)
            favoriteChannelDao.deleteByClan(clanId)
        }
    }

    internal suspend fun loadChannelsForClanNow(clanId: Long, force: Boolean = false) {
        val cacheKey = apiCacheKey("listChannelsByClan", clanId.toString())
        val inMemory = _channelsByClan.value[clanId]
        if (!inMemory.isNullOrEmpty()) {
            if (favoritesByClan[clanId] == null) {
                val cachedFavs = withContext(ioDispatcher) { favoriteChannelDao.getByClan(clanId) }
                favoritesByClan[clanId] = LinkedHashSet(cachedFavs)
            }
            notificationCenter.postNotificationOnMainThread(NotificationCenter.channelsDidLoad, clanId)
        } else {
            val (cachedRaw, cachedFavs) = withContext(ioDispatcher) {
                clanChannelDao.getByClan(clanId) to favoriteChannelDao.getByClan(clanId)
            }
            favoritesByClan[clanId] = LinkedHashSet(cachedFavs)
            val cached = cachedRaw.filter {
                it.clanId == clanId && it.type != CHANNEL_TYPE_DM && it.type != CHANNEL_TYPE_GROUP
            }
            if (cached.isNotEmpty()) {
                updateCache(clanId, cached)
                notificationCenter.postNotificationOnMainThread(NotificationCenter.channelsDidLoad, clanId)
            }
            if (cachedRaw.size != cached.size) {
                appScope.launch(ioDispatcher) {
                    val toDelete = cachedRaw.filter {
                        it.clanId != clanId || it.type == CHANNEL_TYPE_DM || it.type == CHANNEL_TYPE_GROUP
                    }
                    for (ch in toDelete) {
                        clanChannelDao.delete(ch.clanId, ch.channelId)
                    }
                }
            }
        }
        if (StartupCache.suppressHomeListApiForIncomingCallWake && !force) {
            return
        }
        val shouldForce = force
        if (!shouldForce && cacheTracker.shouldCall(cacheKey) == ApiCacheTracker.ShouldCall.SKIP) {
            return
        }
        if (!shouldForce && !channelListNetworkFetchInflight.add(clanId)) {
            return
        }
        channelListLoading[clanId] = true
        try {
            val entitiesList = sessionManager.withAutoRefresh { session ->
                val (result, categoryListResult) = coroutineScope {
                    val channelsAsync = async(ioDispatcher) {
                        api.listChannelsByClan(session.apiUrl, session.token, clanId)
                    }
                    val categoriesAsync = async(ioDispatcher) {
                        runCatching { api.listCategoryDescs(session.apiUrl, session.token, clanId) }
                    }
                    channelsAsync.await() to categoriesAsync.await()
                }
                val categoryOrderMap = HashMap<Long, Int>()
                categoryListResult.onSuccess { categoryList ->
                    categoryList.categorydescList.forEach { cat ->
                        categoryOrderMap[cat.categoryId] = cat.categoryOrder
                    }
                }
                val cachedOrderByCategory = _channelsByClan.value[clanId]
                    ?.asSequence()
                    ?.filter { it.categoryOrder != 0 }
                    ?.associate { it.categoryId to it.categoryOrder }
                    ?: emptyMap()
                val resolvedOrderFor: (Long) -> Int = { categoryId ->
                    categoryOrderMap[categoryId]
                        ?: cachedOrderByCategory[categoryId]
                        ?: 0
                }
                if (categoryOrderMap.isEmpty() && cachedOrderByCategory.isEmpty()) {
                    var fallback = 0
                    result.channeldescList.forEach { ch ->
                        if (!categoryOrderMap.containsKey(ch.categoryId)) {
                            categoryOrderMap[ch.categoryId] = fallback++
                        }
                    }
                }
                val entities = result.channeldescList.map { ch ->
                    withClanIdFromContext(
                        clanId,
                        ch.toClanChannelEntity().copy(categoryOrder = resolvedOrderFor(ch.categoryId))
                    )
                }
                mergeCache(clanId, entities)
                runCatching {
                    val badge = api.listChannelBadgeCount(session.apiUrl, session.token, clanId)
                    if (badge.channeldescList.isNotEmpty()) {
                        applyChannelBadgeReadStatePatch(clanId, badge.channeldescList)
                    }
                }
                runCatching {
                    val favResponse = api.listFavoriteChannels(session.apiUrl, session.token, clanId)
                    val ids = favResponse.channelIdsList
                    favoritesByClan[clanId] = LinkedHashSet(ids)
                    appScope.launch(ioDispatcher) {
                        favoriteChannelDao.deleteByClan(clanId)
                        if (ids.isNotEmpty()) {
                            favoriteChannelDao.upsertAll(ids.mapIndexed { idx, id ->
                                FavoriteChannelEntity(clanId, id, idx)
                            })
                        }
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
            if (!force) channelListNetworkFetchInflight.remove(clanId)
            channelListLoading.remove(clanId)
            badgeCoordinator.get().processDeferredQueue()
        }
    }

    fun getChannels(clanId: Long): List<ClanChannelEntity> =
        _channelsByClan.value[clanId] ?: emptyList()

    fun findChannelById(channelId: Long): ClanChannelEntity? {
        sdTopicChannelsById[channelId]?.let { return it }
        val current = _channelsByClan.value
        if (_channelByIdSnapshot !== current) {
            synchronized(this) {
                if (_channelByIdSnapshot !== current) {
                    val totalSize = current.values.sumOf { it.size }
                    val idx = HashMap<Long, ClanChannelEntity>(totalSize)
                    for (list in current.values) {
                        for (ch in list) idx[ch.channelId] = ch
                    }
                    _channelByIdIndex = idx
                    _channelByIdSnapshot = current
                }
            }
        }
        return _channelByIdIndex[channelId]
    }

    fun findChannelById(channelId: Long, clanId: Long): ClanChannelEntity? {
        sdTopicChannelsById[channelId]?.let { ch ->
            if (clanId == 0L || ch.clanId == clanId) return ch
        }
        if (clanId != 0L) {
            val list = _channelsByClan.value[clanId]
            if (list != null) {
                for (ch in list) if (ch.channelId == channelId) return ch
            }
        }
        return findChannelById(channelId)
    }

    fun upsertChannel(channel: ClanChannelEntity) {
        val clanId = channel.clanId
        if (clanId == 0L) return
        if (isDirectChannelType(channel.type)) return
        val existing = _channelsByClan.value[clanId] ?: emptyList()
        val merged = sortChannels(existing.filter { it.channelId != channel.channelId } + channel)
        updateCache(clanId, merged)
        appScope.launch(ioDispatcher) { clanChannelDao.upsert(channel) }
        notificationCenter.postNotificationOnMainThread(NotificationCenter.channelsDidLoad, clanId)
    }

    suspend fun createClanChannel(
        clanId: Long,
        categoryId: Long,
        type: Int,
        channelLabel: String,
        channelPrivate: Int
    ): ChannelDescription {
        return sessionManager.withAutoRefresh { session ->
            val desc = withContext(ioDispatcher) {
                api.createChannelDesc(
                    apiUrl = session.apiUrl,
                    token = session.token,
                    type = type,
                    userIds = emptyList(),
                    clanId = clanId,
                    channelPrivate = channelPrivate,
                    channelLabel = channelLabel.trim(),
                    categoryId = categoryId,
                    parentId = 0L,
                    appId = 0L
                )
            }
            upsertChannel(desc.toClanChannelEntity())
            desc
        }
    }

    suspend fun findOrFetchChannelLabel(channelId: Long, clanId: Long = 0L): String {
        findChannelById(channelId)?.let { return it.channelLabel }
        val fromDb = withContext(ioDispatcher) { clanChannelDao.getByChannelId(channelId) }
        if (fromDb != null) {
            val existing = _channelsByClan.value[fromDb.clanId] ?: emptyList()
            updateCache(fromDb.clanId, sortChannels(existing.filter { it.channelId != fromDb.channelId } + fromDb))
            return fromDb.channelLabel
        }
        if (clanId != 0L) {
            val result = runCatching {
                sessionManager.withAutoRefresh { session ->
                    withContext(ioDispatcher) { api.listChannelsByClan(session.apiUrl, session.token, clanId) }
                }
            }.getOrNull() ?: return ""
            val cachedOrderByCategory = _channelsByClan.value[clanId]
                ?.asSequence()
                ?.filter { it.categoryOrder != 0 }
                ?.associate { it.categoryId to it.categoryOrder }
                ?: emptyMap()
            val entities = result.channeldescList.map { ch ->
                withClanIdFromContext(
                    clanId,
                    ch.toClanChannelEntity().copy(categoryOrder = cachedOrderByCategory[ch.categoryId] ?: 0)
                )
            }
            updateCache(clanId, sortChannels(entities))
            withContext(ioDispatcher) { clanChannelDao.upsertAll(entities) }
        }
        return findChannelById(channelId)?.channelLabel.orEmpty()
    }

    fun isFavorite(clanId: Long, channelId: Long): Boolean =
        favoritesByClan[clanId]?.contains(channelId) == true

    fun getFavoriteChannelIds(clanId: Long): Set<Long> =
        favoritesByClan[clanId]?.toSet() ?: emptySet()

    fun addFavorite(clanId: Long, channelId: Long) {
        val favSet = favoritesByClan.getOrPut(clanId) { LinkedHashSet() }
        favSet.add(channelId)
        notificationCenter.postNotificationOnMainThread(NotificationCenter.favoriteChannelsChanged, clanId)
        appScope.launch(ioDispatcher) {
            favoriteChannelDao.upsertAll(listOf(FavoriteChannelEntity(clanId, channelId, favSet.size - 1)))
        }
        appScope.launch {
            runCatching {
                sessionManager.withAutoRefresh { session ->
                    api.addFavoriteChannel(session.apiUrl, session.token, channelId, clanId)
                }
            }
        }
    }

    fun removeFavorite(clanId: Long, channelId: Long) {
        favoritesByClan[clanId]?.remove(channelId)
        notificationCenter.postNotificationOnMainThread(NotificationCenter.favoriteChannelsChanged, clanId)
        appScope.launch(ioDispatcher) {
            favoriteChannelDao.delete(clanId, channelId)
        }
        appScope.launch {
            runCatching {
                sessionManager.withAutoRefresh { session ->
                    api.removeFavoriteChannel(session.apiUrl, session.token, clanId, channelId)
                }
            }
        }
    }

    fun registerSdTopicChannels(topics: List<SdTopicEntity>) {
        for (topic in topics) {
            registerSdTopicChannel(topic)
        }
    }

    fun registerSdTopicChannel(topic: SdTopicEntity) {
        if (topic.id == 0L || topic.clanId == 0L) return
        val parent = findChannelById(topic.channelId, topic.clanId)
        sdTopicChannelsById.compute(topic.id) { _, existing ->
            topic.toClanChannelEntity(parent, existing)
        }
    }

    fun clearSdTopicsForClan(clanId: Long) {
        if (clanId == 0L) return
        val toRemove = sdTopicChannelsById.filterValues { it.clanId == clanId }.keys
        for (id in toRemove) {
            sdTopicChannelsById.remove(id)
        }
    }

    fun getChannelSections(clanId: Long): List<ChannelSection> {
        val channels = getChannels(clanId)
        val threadsByParent = HashMap<Long, MutableList<ClanChannelEntity>>()
        val nonThreads = ArrayList<ClanChannelEntity>(channels.size)
        for (ch in channels) {
            if (ch.isThread) {
                threadsByParent.getOrPut(ch.parentId) { ArrayList() }.add(ch)
            } else {
                nonThreads.add(ch)
            }
        }
        nonThreads.sortBy { it.channelId }
        for ((_, list) in threadsByParent) list.sortBy { it.channelId }

        val grouped = LinkedHashMap<Long, MutableList<ClanChannelEntity>>()
        for (ch in nonThreads) {
            grouped.getOrPut(ch.categoryId) { ArrayList() }.add(ch)
        }
        val categorySections = grouped.entries
            .sortedWith(compareBy({ it.value.first().categoryOrder }, { it.key }))
            .map { (categoryId, items) ->
                val categoryName = items.first().categoryName
                val channelsWithThreads = ArrayList<ClanChannelEntity>(items.size)
                for (ch in items) {
                    channelsWithThreads.add(ch)
                    val children = threadsByParent[ch.channelId]
                    if (children != null) channelsWithThreads.addAll(children)
                }
                ChannelSection(
                    categoryId = categoryId,
                    categoryName = categoryName,
                    channels = channelsWithThreads
                )
            }

        val favIds = getFavoriteChannelIds(clanId)
        if (favIds.isEmpty()) return categorySections

        val channelMap = channels.associateBy { it.channelId }
        val favChannels = favIds.mapNotNull { channelMap[it] }
        if (favChannels.isEmpty()) return categorySections

        val favSection = ChannelSection(
            categoryId = FAVORITE_CATEGORY_ID,
            categoryName = FAVORITE_CATEGORY_NAME,
            channels = favChannels
        )
        return listOf(favSection) + categorySections
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
                    categoryOrder = if (apiNorm.categoryOrder != 0) apiNorm.categoryOrder else cached.categoryOrder,
                    lastSeenMessageId = maxOf(cached.lastSeenMessageId, apiNorm.lastSeenMessageId),
                    lastSentMessageId = maxOf(cached.lastSentMessageId, apiNorm.lastSentMessageId),
                    lastSeenMessageTs = maxOf(cached.lastSeenMessageTs, apiNorm.lastSeenMessageTs),
                    lastSentMessageTs = maxOf(cached.lastSentMessageTs, apiNorm.lastSentMessageTs),
                    unreadCount = mergeUnreadFromChannelList(cached.unreadCount, apiNorm)
                )
            }
        }
        val mergedIds = HashSet<Long>(merged.size)
        for (ch in merged) mergedIds.add(ch.channelId)
        val preservedExtras = existing.filter { cached ->
            cached.channelId !in mergedIds &&
                cached.clanId == clanId &&
                cached.type != CHANNEL_TYPE_DM &&
                cached.type != CHANNEL_TYPE_GROUP
        }
        val finalList = if (preservedExtras.isEmpty()) merged else merged + preservedExtras
        updateCache(clanId, sortChannels(finalList))
    }

    private fun sortChannels(channels: List<ClanChannelEntity>): List<ClanChannelEntity> {
        return channels.sortedWith(compareBy<ClanChannelEntity> { it.categoryOrder }
            .thenBy { if (it.parentId == 0L) 0 else 1 }
            .thenBy { it.channelId })
    }

    @Volatile
    private var currentOpenChannelId = 0L
    @Volatile
    private var currentOpenTopicId = 0L
    private val processedBadgeKeys = LinkedHashSet<Long>()
    private val badgeKeyLock = Any()

    fun setCurrentChannel(channelId: Long) { currentOpenChannelId = channelId }

    fun clearCurrentChannel() {
        currentOpenChannelId = 0L
        currentOpenTopicId = 0L
    }

    fun setCurrentTopic(topicId: Long) { currentOpenTopicId = topicId }

    fun clearCurrentTopic() { currentOpenTopicId = 0L }

    fun getCurrentOpenTopicId(): Long = currentOpenTopicId

    fun tryMarkBadgeProcessed(channelId: Long, messageId: Long): Boolean {
        if (messageId == 0L) return true
        val key = (channelId shl 32) xor messageId
        synchronized(badgeKeyLock) {
            if (!processedBadgeKeys.add(key)) return false
            if (processedBadgeKeys.size > MAX_BADGE_CACHE) {
                val iter = processedBadgeKeys.iterator()
                val removeCount = processedBadgeKeys.size - MAX_BADGE_CACHE / 2
                repeat(removeCount) { if (iter.hasNext()) { iter.next(); iter.remove() } }
            }
        }
        return true
    }

    private fun isBadgeProcessed(channelId: Long, messageId: Long): Boolean =
        !tryMarkBadgeProcessed(channelId, messageId)

    private fun resolveNotificationTopicId(notification: com.mezon.mezon.api.Notification): Long {
        if (notification.topicId != 0L) return notification.topicId
        if (notification.content == null || notification.content.isEmpty) return 0L
        return try {
            val json = JSONObject(notification.content.toStringUtf8())
            json.optString("topic_id", "0").toLongOrNull()?.takeIf { it != 0L }
                ?: json.optLong("topic_id", 0L).takeIf { it != 0L }
                ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    fun clearBadgeDedup() {
        synchronized(badgeKeyLock) { processedBadgeKeys.clear() }
    }

    private fun findClanIdForChannel(channelId: Long): Long {
        sdTopicChannelsById[channelId]?.clanId?.takeIf { it != 0L }?.let { return it }
        for ((clanId, channels) in _channelsByClan.value) {
            if (channels.any { it.channelId == channelId }) return clanId
        }
        return 0L
    }

    private fun isDirectChannelType(type: Int): Boolean =
        type == CHANNEL_TYPE_DM || type == CHANNEL_TYPE_GROUP

    private fun applyUserChannelAdded(event: UserChannelAdded, currentUserId: Long) {
        if (!event.hasChannelDesc()) return
        val desc = event.channelDesc
        if (isDirectChannelType(desc.type)) return
        if (event.usersList.none { it.userId == currentUserId }) return
        val clanId = event.clanId.takeIf { it != 0L } ?: desc.clanId
        if (clanId == 0L || desc.channelId == 0L) return
        val active = event.active.takeIf { it != 0 } ?: desc.active.takeIf { it != 0 } ?: 1
        val channel = desc.toClanChannelEntity().copy(clanId = clanId, active = active)
        val existing = _channelsByClan.value[clanId] ?: emptyList()
        updateCache(clanId, sortChannels(existing.filter { it.channelId != channel.channelId } + channel))
        appScope.launch(ioDispatcher) { clanChannelDao.upsert(channel) }
        notificationCenter.postNotificationOnMainThread(NotificationCenter.channelsDidLoad, clanId)
        notificationCenter.postNotificationOnMainThread(NotificationCenter.updateInterfaces, NotificationCenter.UPDATE_MASK_CHAT)
    }

    private fun applyUserChannelRemoved(event: UserChannelRemoved, currentUserId: Long) {
        if (isDirectChannelType(event.channelType)) return
        if (event.userIdsList.none { it == currentUserId }) return
        val channelId = event.channelId
        if (channelId == 0L) return
        val clanId = event.clanId.takeIf { it != 0L } ?: findClanIdForChannel(channelId)
        if (clanId != 0L) {
            val existing = _channelsByClan.value[clanId]
            if (existing != null) {
                updateCache(clanId, existing.filter { it.channelId != channelId })
            }
            favoritesByClan[clanId]?.remove(channelId)
            appScope.launch(ioDispatcher) {
                clanChannelDao.delete(clanId, channelId)
                favoriteChannelDao.delete(clanId, channelId)
                messageDao.deleteByChannel(channelId)
            }
            notificationCenter.postNotificationOnMainThread(NotificationCenter.channelsDidLoad, clanId)
        }
        val wasOpen = currentOpenChannelId == channelId
        if (wasOpen) {
            currentOpenChannelId = 0L
            notificationCenter.postNotificationOnMainThread(NotificationCenter.closeChats, channelId, event.channelType)
            notificationCenter.postNotificationOnMainThread(NotificationCenter.navigateToClansTab)
        }
        notificationCenter.postNotificationOnMainThread(NotificationCenter.updateInterfaces, NotificationCenter.UPDATE_MASK_CHAT)
    }

    fun updateLastSentMessage(channelId: Long, messageId: Long, createTimeSeconds: Long = 0L) {
        val wasTopic = sdTopicChannelsById.computeIfPresent(channelId) { _, ch ->
            if (messageId <= ch.lastSentMessageId) return@computeIfPresent ch
            val ts = createTimeSeconds and 0xFFFF_FFFFL
            val newSentTs = if (ts > 0L) maxOf(ch.lastSentMessageTs, ts) else ch.lastSentMessageTs
            ch.copy(lastSentMessageId = messageId, lastSentMessageTs = newSentTs)
        } != null
        if (wasTopic) return
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

    fun incrementUnread(channelId: Long, messageId: Long = 0L, updateClanBadge: Boolean = true): Boolean =
        adjustChannelUnread(channelId, 1, messageId, updateClanBadge)

    fun adjustChannelUnread(
        channelId: Long,
        delta: Int,
        messageId: Long = 0L,
        updateClanBadge: Boolean = true
    ): Boolean {
        if (delta == 0) return false
        var topicClanId = 0L
        val wasTopic = sdTopicChannelsById.computeIfPresent(channelId) { _, ch ->
            topicClanId = ch.clanId
            val newLastSent = if (messageId > ch.lastSentMessageId) messageId else ch.lastSentMessageId
            ch.copy(
                lastSentMessageId = newLastSent,
                unreadCount = (ch.unreadCount + delta).coerceAtLeast(0)
            )
        } != null
        if (wasTopic) {
            if (updateClanBadge) {
                clansController.get().updateClanBadgeCount(topicClanId, delta)
            }
            return true
        }
        for ((clanId, channels) in _channelsByClan.value) {
            val idx = channels.indexOfFirst { it.channelId == channelId }
            if (idx >= 0) {
                val ch = channels[idx]
                val newLastSent = if (messageId > ch.lastSentMessageId) messageId else ch.lastSentMessageId
                val updated = channels.toMutableList()
                updated[idx] = ch.copy(
                    lastSentMessageId = newLastSent,
                    unreadCount = (ch.unreadCount + delta).coerceAtLeast(0)
                )
                val map = _channelsByClan.value.toMutableMap()
                map[clanId] = updated
                _channelsByClan.value = map
                if (updateClanBadge) {
                    clansController.get().updateClanBadgeCount(clanId, delta)
                }
                return true
            }
        }
        return false
    }

    fun updateChannelLastSeen(
        channelId: Long,
        messageId: Long,
        remainingUnread: Int,
        timestampSeconds: Int = 0
    ) {
        var topicClanId = 0L
        var topicDelta = 0
        val wasTopic = sdTopicChannelsById.computeIfPresent(channelId) { _, ch ->
            topicClanId = ch.clanId
            if (messageId <= ch.lastSeenMessageId) return@computeIfPresent ch
            val oldUnread = ch.unreadCount
            val newUnread = remainingUnread.coerceAtLeast(0)
            if (newUnread == oldUnread && messageId == ch.lastSeenMessageId) return@computeIfPresent ch
            val tsLong = timestampSeconds.toLong() and 0xFFFF_FFFFL
            val syncTs = when {
                newUnread == 0 -> maxOf(ch.lastSeenMessageTs, ch.lastSentMessageTs, tsLong)
                tsLong > 0L -> maxOf(ch.lastSeenMessageTs, tsLong)
                else -> ch.lastSeenMessageTs
            }
            topicDelta = newUnread - oldUnread
            ch.copy(
                lastSeenMessageId = messageId,
                unreadCount = newUnread,
                lastSeenMessageTs = syncTs
            )
        } != null
        if (wasTopic) {
            if (topicDelta != 0) clansController.get().updateClanBadgeCount(topicClanId, topicDelta)
            return
        }
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

    fun markChannelAsRead(channelId: Long, seenTimestampSeconds: Int = 0, seenMessageId: Long = 0L) {
        var topicClanId = 0L
        var topicOldUnread = 0
        val wasTopic = sdTopicChannelsById.computeIfPresent(channelId) { _, ch ->
            topicClanId = ch.clanId
            if (!ch.hasUnread && ch.unreadCount == 0 && seenMessageId <= ch.lastSeenMessageId) return@computeIfPresent ch
            topicOldUnread = ch.unreadCount
            val newSeenId = maxOf(ch.lastSeenMessageId, ch.lastSentMessageId, seenMessageId)
            val tsLong = seenTimestampSeconds.toLong() and 0xFFFF_FFFFL
            val newSeenTs = maxOf(ch.lastSeenMessageTs, ch.lastSentMessageTs, tsLong)
            ch.copy(
                unreadCount = 0,
                lastSeenMessageId = newSeenId,
                lastSeenMessageTs = newSeenTs
            )
        } != null
        if (wasTopic) {
            if (topicOldUnread > 0) clansController.get().updateClanBadgeCount(topicClanId, -topicOldUnread)
            return
        }
        for ((clanId, channels) in _channelsByClan.value) {
            val idx = channels.indexOfFirst { it.channelId == channelId }
            if (idx >= 0) {
                val ch = channels[idx]
                if (!ch.hasUnread && ch.unreadCount == 0 && seenMessageId <= ch.lastSeenMessageId) return
                val oldUnread = ch.unreadCount
                val newSeenId = maxOf(ch.lastSeenMessageId, ch.lastSentMessageId, seenMessageId)
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
        var topicClanId = 0L
        var topicOldUnread = 0
        val wasTopic = sdTopicChannelsById.computeIfPresent(channelId) { _, ch ->
            topicClanId = ch.clanId
            if (messageId <= ch.lastSeenMessageId) return@computeIfPresent ch
            topicOldUnread = ch.unreadCount
            val tsLong = timestampSeconds.toLong() and 0xFFFF_FFFFL
            val newSeenTs = maxOf(ch.lastSeenMessageTs, ch.lastSentMessageTs, tsLong)
            ch.copy(
                lastSeenMessageId = messageId,
                unreadCount = 0,
                lastSeenMessageTs = newSeenTs
            )
        } != null
        if (wasTopic) {
            if (topicOldUnread > 0) clansController.get().updateClanBadgeCount(topicClanId, -topicOldUnread)
            return
        }
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
        var topicClanId = 0L
        var topicDelta = 0
        var topicChanged = false
        val wasTopic = sdTopicChannelsById.computeIfPresent(channelId) { _, ch ->
            topicClanId = ch.clanId
            val newSeenTs = maxOf(ch.lastSeenMessageTs, tsLong)
            val newUnread = badgeCount.coerceAtLeast(0)
            if (newSeenTs == ch.lastSeenMessageTs && newUnread == ch.unreadCount) return@computeIfPresent ch
            topicChanged = true
            topicDelta = newUnread - ch.unreadCount
            ch.copy(lastSeenMessageTs = newSeenTs, unreadCount = newUnread)
        } != null
        if (wasTopic) {
            if (topicChanged) {
                if (topicDelta != 0) clansController.get().updateClanBadgeCount(topicClanId, topicDelta)
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.updateInterfaces, NotificationCenter.UPDATE_MASK_BADGE
                )
            }
            return
        }
        for ((clanId, channels) in _channelsByClan.value) {
            val idx = channels.indexOfFirst { it.channelId == channelId }
            if (idx < 0) continue
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

    private fun markAsReadTargetIds(clanId: Long, categoryId: Long, channelId: Long): List<Long> {
        val channels = _channelsByClan.value[clanId] ?: return emptyList()
        if (channelId != 0L) {
            val ids = LinkedHashSet<Long>()
            ids.add(channelId)
            channels.forEach { ch ->
                if (ch.parentId == channelId) ids.add(ch.channelId)
            }
            return ids.toList()
        }
        if (categoryId != 0L) {
            val baseIds = LinkedHashSet<Long>()
            channels.forEach { ch ->
                if (ch.categoryId == categoryId) baseIds.add(ch.channelId)
            }
            val ids = LinkedHashSet<Long>()
            ids.addAll(baseIds)
            channels.forEach { ch ->
                if (baseIds.contains(ch.parentId)) ids.add(ch.channelId)
            }
            return ids.toList()
        }
        return channels.map { it.channelId }
    }

    private fun markTargetsAsRead(clanId: Long, ids: List<Long>) {
        if (ids.isEmpty()) return
        val channels = _channelsByClan.value[clanId] ?: return
        val byId = channels.associateBy { it.channelId }
        ids.forEach { id ->
            val ch = byId[id] ?: return@forEach
            if (ch.hasUnread || ch.unreadCount > 0) {
                markChannelAsRead(id)
            }
        }
    }

    private fun observeSocketEvents() {
        appScope.launch {
            dispatcher.channelCreatedEvents.collect { event ->
                val clanId = event.clanId
                if (clanId == 0L) return@collect
                if (isDirectChannelType(event.channelType)) return@collect
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
                val inheritedOrder = existing.firstOrNull {
                    it.categoryId == newChannel.categoryId && it.categoryOrder != 0
                }?.categoryOrder ?: 0
                val placed = if (inheritedOrder != 0) newChannel.copy(categoryOrder = inheritedOrder) else newChannel
                updateCache(clanId, sortChannels(existing + placed))
                appScope.launch(ioDispatcher) { clanChannelDao.upsert(placed) }
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

            dispatcher.userChannelAddedEvents.collect { event ->
                applyUserChannelAdded(event, currentUserId)
            }
        }

        appScope.launch {
            val currentUserId = sessionManager.sessionFlow
                .first { it != null }?.userId?.toLongOrNull() ?: 0L

            dispatcher.userChannelRemovedEvents.collect { event ->
                applyUserChannelRemoved(event, currentUserId)
            }
        }

        appScope.launch {
            val currentUserId = sessionManager.sessionFlow
                .first { it != null }?.userId?.toLongOrNull() ?: 0L

            dispatcher.channelMessages.collect { msg ->
                if (msg.mode == STREAM_MODE_DM) return@collect
                if (msg.code == CODE_CHAT_UPDATE || msg.code == CODE_CHAT_REMOVE) return@collect
                if (msg.senderId == currentUserId) return@collect
                if (msg.topicId != 0L) return@collect
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
                val topicId = resolveNotificationTopicId(notification)
                if (topicId != 0L) {
                    if (currentOpenTopicId == topicId) return@collect
                } else if (channelId == currentOpenChannelId) {
                    return@collect
                }

                val messageId = try {
                    val json = JSONObject(notification.content.toStringUtf8())
                    json.optLong("message_id", 0L).takeIf { it != 0L }
                        ?: json.optString("message_id", "").toLongOrNull() ?: 0L
                } catch (_: Exception) { 0L }

                if (topicId != 0L) {
                    val msgTime = try {
                        val json = JSONObject(notification.content.toStringUtf8())
                        json.optLong("create_time_seconds", 0L).takeIf { it != 0L }
                            ?: json.optString("create_time_seconds", "").toLongOrNull() ?: 0L
                    } catch (_: Exception) { 0L }
                    val topicChannel = findChannelById(topicId)
                    if (topicChannel != null && topicChannel.lastSeenMessageTs > 0L &&
                        msgTime > 0L && msgTime <= topicChannel.lastSeenMessageTs
                    ) {
                        return@collect
                    }
                    topicBadgeTracker.get().tryIncrementFromNotification(clanId, channelId, topicId, messageId)
                } else {
                    if (isBadgeProcessed(channelId, messageId)) return@collect
                    val channelInCache = _channelsByClan.value[clanId]?.any { it.channelId == channelId } == true
                    if (channelInCache) {
                        incrementUnread(channelId, messageId)
                    } else {
                        clansController.get().updateClanBadgeCount(clanId, 1)
                    }
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
                val targetIds = markAsReadTargetIds(event.clanId, event.categoryId, event.channelId)
                markTargetsAsRead(event.clanId, targetIds)
                notificationCenter.postNotificationOnMainThread(NotificationCenter.channelsDidLoad, event.clanId)
            }
        }
    }
}
