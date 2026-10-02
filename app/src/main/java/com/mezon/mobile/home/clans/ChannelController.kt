package com.mezon.mobile.home.clans

import android.util.Log
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.MainActivity
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
import com.mezon.mezon.api.CategoryDesc
import com.mezon.mezon.api.CategoryDescList
import com.mezon.mezon.api.ChannelDescription
import com.mezon.mezon.api.NotificationUserChannel
import com.mezon.mezon.rtapi.CategoryEvent
import com.mezon.mezon.rtapi.ChannelArchiveEvent
import com.mezon.mobile.home.chat.SdTopicEntity
import com.mezon.mobile.home.UserClanController
import com.mezon.mobile.home.chat.toClanChannelEntity
import com.mezon.mezon.rtapi.LastSeenMessageEvent
import com.mezon.mezon.rtapi.UserChannelAdded
import com.mezon.mezon.rtapi.UserChannelRemoved
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.text.Collator
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ChannelController"
private const val NOTIFICATION_CODE_USER_MENTIONED = -9
private const val NOTIFICATION_CODE_USER_REPLIED = -11
private const val MAX_BADGE_CACHE = 500

private val NON_BADGE_MESSAGE_CODES = setOf(3, 4, 5, 7, 14, 15)
private const val CHANNEL_NOTIFICATION_STATE_CACHE_TTL_MS = 30_000L
private const val CHANNEL_DESCS_GATE_TIMEOUT_MS = 5_000L

private val VIETNAMESE_LOCALE: Locale = Locale.forLanguageTag("vi-VN")

private fun compareCaseVariantsLowercaseFirst(left: String, right: String): Int {
    val commonLength = minOf(left.length, right.length)
    for (index in 0 until commonLength) {
        val leftChar = left[index]
        val rightChar = right[index]
        if (leftChar == rightChar || leftChar.lowercaseChar() != rightChar.lowercaseChar()) continue

        val leftIsLowercase = leftChar.isLowerCase()
        val rightIsLowercase = rightChar.isLowerCase()
        if (leftIsLowercase != rightIsLowercase) return if (leftIsLowercase) -1 else 1
    }
    return 0
}

internal fun vietnameseThreadNameComparator(): Comparator<ClanChannelEntity> {
    val collator = Collator.getInstance(VIETNAMESE_LOCALE).apply {
        strength = Collator.SECONDARY
        decomposition = Collator.CANONICAL_DECOMPOSITION
    }
    return Comparator { left, right ->
        collator.compare(left.channelLabel, right.channelLabel)
            .takeIf { it != 0 }
            ?: compareCaseVariantsLowercaseFirst(left.channelLabel, right.channelLabel)
    }
}

const val FAVORITE_CATEGORY_ID = -1L
const val FAVORITE_CATEGORY_NAME = "Favorites"

const val CHANNEL_MUTE_ACTIVE_INFINITY = -1
const val SET_MUTE_ACTIVE_UNMUTE = 0
const val NOTIFICATION_ACTIVE_ON = 1
const val NOTIFICATION_DEFAULT_SETTING_ID = 0L
const val CHANNEL_NOTIFICATION_USE_DEFAULT = 0
const val CHANNEL_NOTIFICATION_ALL_MESSAGES = 1
const val CHANNEL_NOTIFICATION_MENTIONS_ONLY = 2
const val CHANNEL_NOTIFICATION_NOTHING = 3
const val CHANNEL_MUTE_DURATION_15M = 15 * 60
const val CHANNEL_MUTE_DURATION_1H = 3600
const val CHANNEL_MUTE_DURATION_3H = 3 * 3600
const val CHANNEL_MUTE_DURATION_8H = 8 * 3600
const val CHANNEL_MUTE_DURATION_24H = 24 * 3600

fun isMutedFromSetMuteRequest(muteTimeSeconds: Int, active: Int): Boolean {
    if (muteTimeSeconds == CHANNEL_MUTE_ACTIVE_INFINITY) return true
    if (muteTimeSeconds == 0 && active == SET_MUTE_ACTIVE_UNMUTE) return false
    if (muteTimeSeconds > 0) return true
    return active == CHANNEL_MUTE_ACTIVE_INFINITY
}

fun isMutedFromNotificationUserChannel(noti: NotificationUserChannel): Boolean {
    val timeMute = noti.timeMuteSeconds
    if (timeMute == CHANNEL_MUTE_ACTIVE_INFINITY) return true
    return timeMute.toLong() > System.currentTimeMillis() / 1000
}

internal fun resolveLoadedChannelMuted(
    channelId: Long,
    mutedChannelIds: Set<Long>?,
    channelDescriptionMuted: Boolean,
    cachedMuted: Boolean?,
): Boolean = if (mutedChannelIds != null) {
    channelId in mutedChannelIds
} else {
    cachedMuted ?: channelDescriptionMuted
}

fun normalizeChannelNotificationType(type: Int): Int =
    type.takeIf { it in CHANNEL_NOTIFICATION_USE_DEFAULT..CHANNEL_NOTIFICATION_NOTHING }
        ?: CHANNEL_NOTIFICATION_USE_DEFAULT

fun channelTypeForClanNotificationDefault(type: Int): Int? =
    type.takeIf { it in CHANNEL_NOTIFICATION_ALL_MESSAGES..CHANNEL_NOTIFICATION_NOTHING }

fun normalizeClanNotificationType(type: Int): Int =
    channelTypeForClanNotificationDefault(type) ?: CHANNEL_NOTIFICATION_ALL_MESSAGES

fun normalizeDmMuteExpirySeconds(raw: Int): Int {
    if (raw == CHANNEL_MUTE_ACTIVE_INFINITY) return raw
    if (raw <= 0) return 0
    if (raw > 100_000_000) return raw
    return (System.currentTimeMillis() / 1000 + raw).toInt()
}

fun isDmMutedFromNotificationSetting(noti: NotificationUserChannel): Boolean {
    if (noti.id == NOTIFICATION_DEFAULT_SETTING_ID) return false
    val timeMute = noti.timeMuteSeconds
    if (timeMute == CHANNEL_MUTE_ACTIVE_INFINITY) return true
    if (timeMute <= 0) return false
    return timeMute.toLong() > System.currentTimeMillis() / 1000
}

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
    private val userClanController: dagger.Lazy<UserClanController>,
    private val channelAppController: dagger.Lazy<com.mezon.mobile.home.clans.channelapp.ChannelAppController>,
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
    private val badgeSnapshotLoadedClans = ConcurrentHashMap.newKeySet<Long>()
    private val favoritesByClan = ConcurrentHashMap<Long, MutableSet<Long>>()
    private val mutedChannelIdsByClan = ConcurrentHashMap<Long, MutableSet<Long>>()
    private val notificationSettingTypesByChannel = ConcurrentHashMap<Long, Int>()
    private val _notificationSettingTypes = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val notificationSettingTypes: StateFlow<Map<Long, Int>> = _notificationSettingTypes.asStateFlow()
    private val categoriesByClan = ConcurrentHashMap<Long, List<ClanCategoryItem>>()
    private val sdTopicChannelsById = ConcurrentHashMap<Long, ClanChannelEntity>()
    private val channelAvatarByKey = ConcurrentHashMap<Long, String>()
    private val linkedChannels = ChannelLinkLookupCache<ClanChannelEntity>(appScope)

    init {
        observeSocketEvents()
    }

    fun isChannelListLoading(clanId: Long): Boolean = channelListLoading[clanId] == true

    fun linkedChannelDetail(channelId: Long): ClanChannelEntity? = linkedChannels.get(channelId)

    // After an access change, only a new detail lookup can authorize a cached channel link.
    fun requiresLinkedChannelValidation(channelId: Long): Boolean = linkedChannels.wasInvalidated(channelId)

    private fun invalidateLinkedChannel(channelId: Long) {
        if (channelId == 0L) return
        linkedChannels.invalidate(channelId)
        notificationCenter.postNotificationOnMainThread(NotificationCenter.linkedChannelDidLoad, channelId)
    }

    suspend fun resolveLinkedChannelForNavigation(channelId: Long, clanId: Long): ClanChannelEntity? {
        if (clanId != 0L && clansController.get().clans.value.none { it.clanId == clanId }) return null
        return sessionManager.withAutoRefresh { session ->
            withContext(ioDispatcher) { api.listChannelDetail(session.apiUrl, session.token, channelId) }
        }.takeIf { it.channelId == channelId && (clanId == 0L || it.clanId == clanId) }?.toClanChannelEntity()
    }

    fun requestLinkedChannel(channelId: Long, clanId: Long) {
        if (channelId == 0L || (clanId != 0L && clansController.get().clans.value.none { it.clanId == clanId })) return
        linkedChannels.request(channelId, groupId = clanId, lookup = {
            if (clanId != 0L && clansController.get().clans.value.none { it.clanId == clanId }) {
                throw kotlinx.coroutines.CancellationException("Clan left")
            }
            try {
                sessionManager.withAutoRefresh { session ->
                    withContext(ioDispatcher) { api.listChannelDetail(session.apiUrl, session.token, channelId) }
                }.takeIf { it.channelId == channelId && (clanId == 0L || it.clanId == clanId) }
                    ?.toClanChannelEntity()
            } catch (e: com.mezon.mobile.network.SocketRpcServerException) {
                if (e.code in setOf(3, 5, 7)) null else throw e
            } catch (e: com.mezon.mobile.network.HttpRpcStatusException) {
                if (e.code in setOf(400, 403, 404)) null else throw e
            }
        }, onResolved = {
            notificationCenter.postNotificationOnMainThread(NotificationCenter.linkedChannelDidLoad, channelId)
        })
    }

    fun cleanup() {
        linkedChannels.clear()
        _channelsByClan.value = emptyMap()
        sdTopicChannelsById.clear()
        currentOpenChannelId = 0L
        currentOpenTopicId = 0L
        badgeVisibility.clear()
        synchronized(badgeKeyLock) {
            processedBadgeKeys.clear()
            countedBadgeKeys.clear()
            deletedBadgeKeys.clear()
        }
        channelListLoading.clear()
        channelListNetworkFetchInflight.clear()
        badgeSnapshotLoadedClans.clear()
        remoteReadCursors.clear()
        pendingMentionsByChannel.clear()
        favoritesByClan.clear()
        mutedChannelIdsByClan.clear()
        notificationSettingTypesByChannel.clear()
        _notificationSettingTypes.value = emptyMap()
        categoriesByClan.clear()
        channelAvatarByKey.clear()
    }

    fun getChannelAvatar(clanId: Long, channelId: Long): String =
        channelAvatarByKey[channelAvatarKey(clanId, channelId)].orEmpty()

    private fun channelAvatarKey(clanId: Long, channelId: Long): Long =
        clanId xor (channelId * 31L)

    private fun cacheChannelAvatar(clanId: Long, desc: ChannelDescription) {
        val avatar = desc.channelAvatar.trim()
        if (avatar.isNotEmpty()) {
            channelAvatarByKey[channelAvatarKey(clanId, desc.channelId)] = avatar
        }
    }

    fun loadChannelsForClan(clanId: Long, force: Boolean = false) {
        appScope.launch { loadChannelsForClanNow(clanId, force) }
    }

    suspend fun loadChannelsForClanSuspend(clanId: Long, force: Boolean = false) {
        loadChannelsForClanNow(clanId, force)
    }

    suspend fun awaitChannelsForClan(clanId: Long, timeoutMs: Long = CHANNEL_DESCS_GATE_TIMEOUT_MS) {
        if (clanId == 0L) return
        if (!_channelsByClan.value[clanId].isNullOrEmpty()) return
        loadChannelsForClan(clanId)
        withTimeoutOrNull(timeoutMs) {
            channelsByClan.first { !it[clanId].isNullOrEmpty() }
        }
    }

    fun purgeClanChannelsCache(clanId: Long) {
        if (clanId == 0L) return
        linkedChannels.invalidateGroup(clanId)
        clearSdTopicsForClan(clanId)
        val m = _channelsByClan.value.toMutableMap()
        m.remove(clanId)
        _channelsByClan.value = m
        notificationCenter.postNotificationOnMainThread(NotificationCenter.linkedChannelDidLoad)
        favoritesByClan.remove(clanId)
        mutedChannelIdsByClan.remove(clanId)
        categoriesByClan.remove(clanId)
        channelListLoading.remove(clanId)
        channelListNetworkFetchInflight.remove(clanId)
        badgeSnapshotLoadedClans.remove(clanId)
        pendingMentionsByChannel.entries.removeIf { it.value.clanId == clanId }
        appScope.launch(ioDispatcher) {
            clanChannelDao.deleteByClan(clanId)
            favoriteChannelDao.deleteByClan(clanId)
        }
    }

    internal suspend fun loadChannelsForClanNow(clanId: Long, force: Boolean = false) =
        withContext(Dispatchers.Main.immediate) { loadChannelsForClanOnMain(clanId, force) }

    private suspend fun loadChannelsForClanOnMain(clanId: Long, force: Boolean) {
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
                val mutedChannelIds = runCatching {
                    val mutedResponse = api.listMutedChannels(session.apiUrl, session.token, clanId)
                    LinkedHashSet(mutedResponse.mutedListList)
                }.onSuccess { mutedIds ->
                    mutedChannelIdsByClan[clanId] = mutedIds
                }.onFailure { error ->
                    Log.w(TAG, "Failed to refresh muted channels for clan=$clanId; keeping cached state", error)
                }.getOrNull()
                val cachedChannelsById = _channelsByClan.value[clanId]
                    ?.associateBy { it.channelId }
                    .orEmpty()
                for (ch in result.channeldescList) {
                    cacheChannelAvatar(clanId, ch)
                }
                val entities = result.channeldescList.map { ch ->
                    val channelEntity = ch.toClanChannelEntity()
                    withClanIdFromContext(
                        clanId,
                        channelEntity.copy(
                            categoryOrder = resolvedOrderFor(ch.categoryId),
                            isMuted = resolveLoadedChannelMuted(
                                channelId = ch.channelId,
                                mutedChannelIds = mutedChannelIds,
                                channelDescriptionMuted = channelEntity.isMuted,
                                cachedMuted = cachedChannelsById[ch.channelId]?.isMuted,
                            ),
                        )
                    )
                }
                mergeCache(clanId, entities)
                categoryListResult.onSuccess { categoryList ->
                    val fromApi = categoryList.categorydescList.mapNotNull { desc ->
                        normalizeCategoryItem(desc.toClanCategoryItem(), clanId)
                    }
                    categoriesByClan[clanId] = mergeAllCategoriesForClan(clanId, fromApi)
                }
                val badgeSnapshotLoaded = runCatching {
                    val beforeBadge = _channelsByClan.value[clanId].orEmpty().associateBy { it.channelId }
                    val badge = api.listChannelBadgeCount(session.apiUrl, session.token, clanId)
                    if (badge.channeldescList.isNotEmpty()) {
                        applyChannelBadgeReadStatePatch(clanId, badge.channeldescList, beforeBadge)
                    }
                    badge.channeldescList.isNotEmpty()
                }.getOrDefault(false)
                if (badgeSnapshotLoaded) badgeSnapshotLoadedClans.add(clanId)
                else badgeSnapshotLoadedClans.remove(clanId)
                val flushedPending = getChannels(clanId).sumOf { flushPendingMentionsInto(it.channelId) }
                if (flushedPending > 0) {
                    clansController.get().reconcileClanBadgeFromChannels(clanId)
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
            cacheTracker.markCalled(cacheKey, ttlMs = ApiCacheTracker.LIST_CACHE_TTL_MS)
            val merged = _channelsByClan.value[clanId] ?: entitiesList
            withContext(ioDispatcher) {
                if (merged.isEmpty()) {
                    clanChannelDao.deleteByClan(clanId)
                } else {
                    clanChannelDao.deleteMissing(clanId, merged.map { it.channelId })
                    clanChannelDao.upsertAll(merged)
                }
            }
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

    fun hasLoadedBadgeSnapshot(clanId: Long): Boolean = clanId in badgeSnapshotLoadedClans

    fun getPendingMentionCount(clanId: Long): Int = pendingMentionsByChannel.entries.sumOf { (channelId, pending) ->
        if (pending.clanId != clanId) return@sumOf 0
        val channel = findChannelById(channelId)
        pendingMentionUnreadDelta(
            pending.messageIds,
            channel?.lastSeenMessageId ?: 0L,
            channel?.unreadCount ?: 0
        )
    }

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

    suspend fun addAppToClan(clanId: Long, appId: Long) {
        sessionManager.withAutoRefresh { session ->
            withContext(ioDispatcher) {
                api.addAppToClan(session.apiUrl, session.token, appId, clanId)
            }
        }
    }

    suspend fun createAppChannel(
        clanId: Long,
        categoryId: Long,
        channelLabel: String,
        appId: Long,
    ): ChannelDescription {
        return sessionManager.withAutoRefresh { session ->
            val desc = withContext(ioDispatcher) {
                api.createChannelDesc(
                    apiUrl = session.apiUrl,
                    token = session.token,
                    type = CHANNEL_TYPE_APP,
                    userIds = emptyList(),
                    clanId = clanId,
                    channelPrivate = 0,
                    channelLabel = channelLabel.trim(),
                    categoryId = categoryId,
                    parentId = 0L,
                    appId = appId
                )
            }
            upsertChannel(desc.toClanChannelEntity())
            desc
        }
    }

    suspend fun updateChannelDescSettings(
        clanId: Long,
        channelId: Long,
        channelLabel: String,
        categoryId: Long,
        topic: String,
        appId: Long = 0L,
        ageRestricted: Int = 0,
        e2ee: Int = 0,
    ): Result<ChannelDescription> = runCatching {
        sessionManager.withAutoRefresh { session ->
            val desc = withContext(ioDispatcher) {
                api.updateChannelDesc(
                    apiUrl = session.apiUrl,
                    token = session.token,
                    clanId = clanId,
                    channelId = channelId,
                    channelLabel = channelLabel.trim(),
                    categoryId = categoryId,
                    topic = topic,
                    appId = appId,
                    ageRestricted = ageRestricted,
                    e2ee = e2ee,
                )
            }
            upsertChannel(desc.toClanChannelEntity())
            notificationCenter.postNotificationOnMainThread(
                NotificationCenter.updateInterfaces,
                NotificationCenter.UPDATE_MASK_CHAT_NAME,
                channelId,
                clanId
            )
            desc
        }
    }

    suspend fun createCategory(clanId: Long, categoryName: String): CategoryDesc {
        if (clanId == 0L) throw IllegalArgumentException("Invalid clan")
        return sessionManager.withAutoRefresh { session ->
            val desc = withContext(ioDispatcher) {
                api.createCategoryDesc(
                    session.apiUrl,
                    session.token,
                    clanId,
                    categoryName.trim(),
                )
            }
            val item = normalizeCategoryItem(desc.toClanCategoryItem(), clanId)
            if (item != null) {
                val list = getCachedCategories(clanId).toMutableList()
                val idx = list.indexOfFirst { it.categoryId == item.categoryId }
                if (idx >= 0) {
                    list[idx] = item
                } else {
                    list.add(item)
                }
                categoriesByClan[clanId] = list.sortedWith(
                    compareBy<ClanCategoryItem> { it.categoryOrder }.thenBy { it.categoryName.lowercase() },
                )
            }
            notificationCenter.postNotificationOnMainThread(NotificationCenter.channelsDidLoad, clanId)
            desc
        }
    }

    suspend fun checkDuplicateChannelName(
        name: String,
        type: Int,
        conditionId: Long,
    ): Result<Boolean> = runCatching {
        sessionManager.withAutoRefresh { session ->
            withContext(ioDispatcher) {
                api.checkDuplicateName(session.apiUrl, session.token, name, type, conditionId)
            }
        }
    }

    suspend fun loadCategoriesForClan(clanId: Long, force: Boolean = false): List<ClanCategoryItem> {
        if (clanId == 0L) return emptyList()
        if (!force) {
            val cached = categoriesByClan[clanId]
            if (!cached.isNullOrEmpty()) return cached
        }
        return sessionManager.withAutoRefresh { session ->
            val fromApi = withContext(ioDispatcher) {
                runCatching { fetchAllCategoriesFromApi(session.apiUrl, session.token, clanId) }.getOrElse { emptyList() }
            }
            val merged = mergeAllCategoriesForClan(clanId, fromApi)
            categoriesByClan[clanId] = merged
            merged
        }
    }

    private suspend fun fetchAllCategoriesFromApi(apiUrl: String, token: String, clanId: Long): List<ClanCategoryItem> {
        return runCatching {
            api.listCategoryDescs(apiUrl, token, clanId).categorydescList.mapNotNull { desc ->
                normalizeCategoryItem(desc.toClanCategoryItem(), clanId)
            }
        }.getOrElse { emptyList() }
    }

    private fun categoriesDerivedFromChannels(clanId: Long): List<ClanCategoryItem> {
        return getChannels(clanId)
            .asSequence()
            .filter { !it.isThread }
            .groupBy { it.categoryId }
            .mapNotNull { (categoryId, channels) ->
                if (categoryId == 0L || categoryId == FAVORITE_CATEGORY_ID) return@mapNotNull null
                val name = channels
                    .map { it.categoryName.trim() }
                    .firstOrNull { it.isNotEmpty() }
                    ?: return@mapNotNull null
                val order = channels.minOfOrNull { it.categoryOrder } ?: 0
                ClanCategoryItem(categoryId, name, order, clanId)
            }
            .toList()
    }

    private fun mergeAllCategoriesForClan(clanId: Long, fromApi: List<ClanCategoryItem> = emptyList()): List<ClanCategoryItem> {
        val merged = LinkedHashMap<Long, ClanCategoryItem>()
        fun absorb(item: ClanCategoryItem) {
            normalizeCategoryItem(item, clanId)?.let { normalized ->
                val existing = merged[normalized.categoryId]
                merged[normalized.categoryId] = when {
                    existing == null -> normalized
                    existing.categoryOrder == 0 && normalized.categoryOrder != 0 -> normalized
                    else -> existing
                }
            }
        }
        fromApi.forEach(::absorb)
        categoriesDerivedFromChannels(clanId).forEach(::absorb)
        return merged.values
            .sortedWith(compareBy<ClanCategoryItem> { it.categoryOrder }.thenBy { it.categoryName.lowercase() })
    }

    private fun normalizeCategoryItem(item: ClanCategoryItem, clanId: Long): ClanCategoryItem? {
        if (item.categoryId == 0L || item.categoryId == FAVORITE_CATEGORY_ID) return null
        if (item.clanId != 0L && item.clanId != clanId) return null
        val name = item.categoryName.trim()
        if (name.isEmpty()) return null
        return item.copy(clanId = clanId, categoryName = name)
    }

    fun getCachedCategories(clanId: Long): List<ClanCategoryItem> =
        categoriesByClan[clanId] ?: emptyList()

    fun resolveCategoryDisplayName(clanId: Long, channel: ClanChannelEntity): String {
        if (channel.categoryName.isNotBlank()) return channel.categoryName
        return mergeAllCategoriesForClan(clanId)
            .firstOrNull { it.categoryId == channel.categoryId }
            ?.categoryName
            .orEmpty()
    }

    fun categoriesForMove(clanId: Long, excludeCategoryId: Long, @Suppress("UNUSED_PARAMETER") channel: ClanChannelEntity? = null): List<ClanCategoryItem> =
        mergeAllCategoriesForClan(clanId, getCachedCategories(clanId))
            .filter { it.categoryId != excludeCategoryId }

    suspend fun changeChannelCategory(
        clanId: Long,
        channelId: Long,
        target: ClanCategoryItem,
    ): Result<Unit> = runCatching {
        sessionManager.withAutoRefresh { session ->
            withContext(ioDispatcher) {
                api.changeChannelCategory(session.apiUrl, session.token, clanId, channelId, target.categoryId)
            }
            findChannelById(channelId, clanId)?.let { ch ->
                upsertChannel(
                    ch.copy(
                        categoryId = target.categoryId,
                        categoryName = target.categoryName,
                    )
                )
            }
            notificationCenter.postNotificationOnMainThread(NotificationCenter.channelsDidLoad, clanId)
        }
    }

    suspend fun deleteChannelDesc(clanId: Long, channelId: Long, channelType: Int): Result<Unit> = runCatching {
        sessionManager.withAutoRefresh { session ->
            withContext(ioDispatcher) {
                api.deleteChannelDesc(session.apiUrl, session.token, clanId, channelId)
            }
            removeChannelLocally(clanId, channelId, channelType)
        }
    }

    suspend fun leaveThread(clanId: Long, threadId: Long, parentChannelId: Long): Result<Unit> = runCatching {
        sessionManager.withAutoRefresh { session ->
            withContext(ioDispatcher) {
                api.leaveThread(session.apiUrl, session.token, clanId, threadId)
            }
            removeChannelLocally(clanId, threadId, com.mezon.mobile.network.CHANNEL_TYPE_THREAD)
        }
    }

    private fun removeChannelLocally(clanId: Long, channelId: Long, channelType: Int) {
        invalidateLinkedChannel(channelId)
        val existing = _channelsByClan.value[clanId] ?: emptyList()
        updateCache(clanId, existing.filter { it.channelId != channelId })
        favoritesByClan[clanId]?.remove(channelId)
        channelAppController.get().removeAppLocally(clanId, channelId)
        appScope.launch(ioDispatcher) {
            clanChannelDao.delete(clanId, channelId)
            favoriteChannelDao.delete(clanId, channelId)
            messageDao.deleteByChannel(channelId)
        }
        notificationCenter.postNotificationOnMainThread(NotificationCenter.channelsDidLoad, clanId)
        if (channelId == currentOpenChannelId) {
            currentOpenChannelId = 0L
            notificationCenter.postNotificationOnMainThread(NotificationCenter.closeChats, channelId, channelType)
            notificationCenter.postNotificationOnMainThread(NotificationCenter.navigateToClansTab)
        }
    }

    private fun applyChannelArchiveEvent(event: ChannelArchiveEvent) {
        val clanId = event.clanId
        val channelId = event.channelId
        if (clanId == 0L || channelId == 0L || isDirectChannelType(event.channelType)) return
        invalidateLinkedChannel(channelId)

        val existing = _channelsByClan.value[clanId].orEmpty()
        if (event.active == 0) {
            updateCache(clanId, existing.filter { it.channelId != channelId })
            favoritesByClan[clanId]?.remove(channelId)
            appScope.launch(ioDispatcher) {
                clanChannelDao.delete(clanId, channelId)
                favoriteChannelDao.delete(clanId, channelId)
            }
            notificationCenter.postNotificationOnMainThread(NotificationCenter.channelsDidLoad, clanId)
            if (channelId == currentOpenChannelId) {
                currentOpenChannelId = 0L
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.closeChats,
                    channelId,
                    event.channelType
                )
                notificationCenter.postNotificationOnMainThread(NotificationCenter.navigateToClansTab)
            }
            return
        }

        val previous = existing.firstOrNull { it.channelId == channelId }
        val categoryOrder = previous?.categoryOrder
            ?: existing.firstOrNull { it.categoryId == event.categoryId && it.categoryOrder != 0 }?.categoryOrder
            ?: 0
        val restored = ClanChannelEntity(
            clanId = clanId,
            channelId = channelId,
            parentId = event.parentId,
            categoryId = event.categoryId,
            categoryName = previous?.categoryName
                ?: getCachedCategories(clanId).firstOrNull { it.categoryId == event.categoryId }?.categoryName.orEmpty(),
            channelLabel = event.channelLabel.ifBlank { previous?.channelLabel.orEmpty() },
            type = event.channelType.takeIf { it != 0 } ?: previous?.type ?: 0,
            isPrivate = event.channelPrivate,
            topic = event.topic.ifBlank { previous?.topic.orEmpty() },
            unreadCount = event.countMessUnread,
            isMuted = resolveChannelMuted(clanId, channelId, previous?.isMuted ?: false),
            lastSeenMessageId = previous?.lastSeenMessageId ?: 0L,
            lastSentMessageId = previous?.lastSentMessageId ?: 0L,
            lastSeenMessageTs = previous?.lastSeenMessageTs ?: 0L,
            lastSentMessageTs = previous?.lastSentMessageTs ?: 0L,
            active = event.active,
            categoryOrder = categoryOrder,
            ageRestricted = event.ageRestricted,
        )
        updateCache(clanId, sortChannels(existing.filter { it.channelId != channelId } + restored))
        appScope.launch(ioDispatcher) { clanChannelDao.upsert(restored) }
        notificationCenter.postNotificationOnMainThread(NotificationCenter.channelsDidLoad, clanId)
    }

    suspend fun findOrFetchChannelLabel(channelId: Long, clanId: Long = 0L): String {
        val cached = findChannelById(channelId)
        if (cached != null && cached.channelLabel.isNotBlank()) {
            return cached.channelLabel
        }

        val fromDb = withContext(ioDispatcher) { clanChannelDao.getByChannelId(channelId) }
        if (fromDb != null) {
            val existing = _channelsByClan.value[fromDb.clanId] ?: emptyList()
            updateCache(fromDb.clanId, sortChannels(existing.filter { it.channelId != fromDb.channelId } + fromDb))
            if (fromDb.channelLabel.isNotBlank()) {
                return fromDb.channelLabel
            }
        }

        if (clanId != 0L) {
            val result = runCatching {
                sessionManager.withAutoRefresh { session ->
                    withContext(ioDispatcher) { api.listChannelsByClan(session.apiUrl, session.token, clanId) }
                }
            }.getOrNull()
            if (result == null) {
                return findChannelById(channelId)?.channelLabel.orEmpty()
            }
            val cachedOrderByCategory = _channelsByClan.value[clanId]
                ?.asSequence()
                ?.filter { it.categoryOrder != 0 }
                ?.associate { it.categoryId to it.categoryOrder }
                ?: emptyMap()
            for (ch in result.channeldescList) {
                cacheChannelAvatar(clanId, ch)
            }
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

    suspend fun setChannelMuted(
        clanId: Long,
        channelId: Long,
        muteTimeSeconds: Int,
        active: Int = 0,
    ): Result<Unit> {
        val isMuted = isMutedFromSetMuteRequest(muteTimeSeconds, active)
        val result = runCatching {
            sessionManager.withAutoRefresh { session ->
                withContext(ioDispatcher) {
                    api.setMuteChannel(
                        session.apiUrl,
                        session.token,
                        channelId,
                        clanId,
                        muteTimeSeconds,
                        active,
                    )
                }
            }
        }
        if (result.isSuccess) {
            patchChannelMuteLocally(clanId, channelId, isMuted, persistAsync = false)
            runCatching { persistChannelMute(clanId, channelId) }
                .onFailure { error ->
                    Log.w(TAG, "Failed to persist mute state for channel=$channelId", error)
                }
        }
        return result
    }

    suspend fun unmuteChannel(clanId: Long, channelId: Long): Result<Unit> =
        setChannelMuted(clanId, channelId, muteTimeSeconds = 0, active = 0)

    fun getCachedChannelNotificationType(channelId: Long): Int =
        notificationSettingTypesByChannel[channelId] ?: CHANNEL_NOTIFICATION_USE_DEFAULT

    suspend fun getClanDefaultNotificationType(clanId: Long): Result<Int> = runCatching {
        sessionManager.withAutoRefresh { session ->
            withContext(ioDispatcher) {
                normalizeClanNotificationType(
                    api.getClanDefaultNotification(
                        session.apiUrl,
                        session.token,
                        clanId,
                    ).notificationSettingType,
                )
            }
        }
    }

    suspend fun setClanDefaultNotificationType(
        clanId: Long,
        notificationType: Int,
    ): Result<Unit> {
        val normalizedType = normalizeClanNotificationType(notificationType)
        return runCatching {
            sessionManager.withAutoRefresh { session ->
                withContext(ioDispatcher) {
                    api.setClanDefaultNotification(
                        session.apiUrl,
                        session.token,
                        clanId,
                        normalizedType,
                    )
                }
            }
        }
    }

    suspend fun refreshChannelNotificationState(clanId: Long, channelId: Long): Result<Int> {
        val cacheKey = apiCacheKey("channelNotificationState", channelId)
        if (
            cacheTracker.shouldCall(
                cacheKey,
                ttlMs = CHANNEL_NOTIFICATION_STATE_CACHE_TTL_MS,
            ) == ApiCacheTracker.ShouldCall.SKIP
        ) {
            return Result.success(getCachedChannelNotificationType(channelId))
        }

        return runCatching {
            val notification = sessionManager.withAutoRefresh { session ->
                withContext(ioDispatcher) {
                    api.getNotificationChannel(session.apiUrl, session.token, channelId)
                }
            }
            val normalizedType = normalizeChannelNotificationType(notification.notificationSettingType)
            patchChannelNotificationType(channelId, normalizedType)
            val resolvedClanId = clanId.takeIf { it != 0L }
                ?: findChannelById(channelId)?.clanId
                ?: 0L
            if (resolvedClanId != 0L) {
                patchChannelMuteLocally(
                    resolvedClanId,
                    channelId,
                    isMutedFromNotificationUserChannel(notification),
                )
            }
            normalizedType
        }.onSuccess {
            cacheTracker.markCalled(cacheKey, ttlMs = CHANNEL_NOTIFICATION_STATE_CACHE_TTL_MS)
        }
    }

    suspend fun setChannelNotificationType(
        clanId: Long,
        channelId: Long,
        notificationType: Int,
    ): Result<Unit> {
        val normalizedType = normalizeChannelNotificationType(notificationType)
        return runCatching {
            sessionManager.withAutoRefresh { session ->
                withContext(ioDispatcher) {
                    if (normalizedType == CHANNEL_NOTIFICATION_USE_DEFAULT) {
                        api.deleteNotificationChannel(session.apiUrl, session.token, channelId)
                    } else {
                        api.setNotificationChannel(
                            session.apiUrl,
                            session.token,
                            channelId,
                            clanId,
                            normalizedType,
                        )
                    }
                }
            }
        }.onSuccess {
            patchChannelNotificationType(channelId, normalizedType)
        }
    }

    fun isChannelMuted(clanId: Long, channelId: Long): Boolean {
        mutedChannelIdsByClan[clanId]?.let { if (it.contains(channelId)) return true }
        return findChannelById(channelId, clanId)?.isMuted == true
    }

    private fun resolveChannelMuted(clanId: Long, channelId: Long, fromApi: Boolean): Boolean {
        mutedChannelIdsByClan[clanId]?.let { return it.contains(channelId) }
        return fromApi
    }

    private fun patchChannelMuteLocally(
        clanId: Long,
        channelId: Long,
        isMuted: Boolean,
        persistAsync: Boolean = true,
    ) {
        val mutedSet = mutedChannelIdsByClan.getOrPut(clanId) { LinkedHashSet() }
        if (isMuted) mutedSet.add(channelId) else mutedSet.remove(channelId)
        val existing = _channelsByClan.value[clanId] ?: return
        var changed = false
        val updated = existing.map { row ->
            if (row.channelId != channelId || row.isMuted == isMuted) return@map row
            changed = true
            row.copy(isMuted = isMuted)
        }
        if (!changed) return
        updateCache(clanId, updated)
        val entity = updated.firstOrNull { it.channelId == channelId } ?: return
        if (persistAsync) {
            appScope.launch(ioDispatcher) { clanChannelDao.upsert(entity) }
        }
        notificationCenter.postNotificationOnMainThread(NotificationCenter.channelsDidLoad, clanId)
    }

    private suspend fun persistChannelMute(clanId: Long, channelId: Long) {
        val entity = findChannelById(channelId, clanId) ?: return
        withContext(ioDispatcher) { clanChannelDao.upsert(entity) }
    }

    private fun patchChannelNotificationType(channelId: Long, notificationType: Int) {
        notificationSettingTypesByChannel[channelId] = normalizeChannelNotificationType(notificationType)
        _notificationSettingTypes.value = notificationSettingTypesByChannel.toMap()
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

    fun getChannelSections(clanId: Long, showEmptyCategories: Boolean = false): List<ChannelSection> {
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
        val threadNameComparator = vietnameseThreadNameComparator()
        for ((_, list) in threadsByParent) list.sortWith(threadNameComparator)

        val grouped = LinkedHashMap<Long, MutableList<ClanChannelEntity>>()
        for (ch in nonThreads) {
            grouped.getOrPut(ch.categoryId) { ArrayList() }.add(ch)
        }
        val categorySections = buildCategorySections(clanId, grouped, threadsByParent, showEmptyCategories)

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

    private fun buildCategorySections(
        clanId: Long,
        grouped: Map<Long, List<ClanChannelEntity>>,
        threadsByParent: Map<Long, List<ClanChannelEntity>>,
        showEmptyCategories: Boolean,
    ): List<ChannelSection> {
        val categories = mergeAllCategoriesForClan(clanId, getCachedCategories(clanId))
        val seenCategoryIds = HashSet<Long>()
        val sections = ArrayList<ChannelSection>()

        fun channelsWithThreads(items: List<ClanChannelEntity>): List<ClanChannelEntity> {
            val channelsWithThreads = ArrayList<ClanChannelEntity>(items.size)
            for (ch in items) {
                channelsWithThreads.add(ch)
                val children = threadsByParent[ch.channelId]
                if (children != null) channelsWithThreads.addAll(children)
            }
            return channelsWithThreads
        }

        fun appendSection(categoryId: Long, categoryName: String, items: List<ClanChannelEntity>) {
            if (categoryId == FAVORITE_CATEGORY_ID) return
            if (items.isEmpty()) {
                if (!showEmptyCategories || categoryName.isEmpty()) return
            }
            sections.add(
                ChannelSection(
                    categoryId = categoryId,
                    categoryName = categoryName,
                    channels = channelsWithThreads(items),
                )
            )
            seenCategoryIds.add(categoryId)
        }

        for (cat in categories) {
            appendSection(cat.categoryId, cat.categoryName, grouped[cat.categoryId].orEmpty())
        }
        grouped.entries
            .asSequence()
            .filter { (categoryId, _) -> categoryId !in seenCategoryIds }
            .sortedWith(compareBy<Map.Entry<Long, List<ClanChannelEntity>>> { it.value.firstOrNull()?.categoryOrder ?: 0 }.thenBy { it.key })
            .forEach { (categoryId, items) ->
                val categoryName = items.firstOrNull()?.categoryName.orEmpty()
                appendSection(categoryId, categoryName, items)
            }
        return sections
    }

    private fun updateCache(clanId: Long, channels: List<ClanChannelEntity>) {
        val updated = _channelsByClan.value.toMutableMap()
        updated[clanId] = channels
        _channelsByClan.value = updated
    }

    private fun withClanIdFromContext(contextClanId: Long, entity: ClanChannelEntity): ClanChannelEntity =
        if (entity.clanId != 0L) entity else entity.copy(clanId = contextClanId)

    private fun applyChannelBadgeReadStatePatch(clanId: Long, badgeDescs: List<ChannelDescription>, before: Map<Long, ClanChannelEntity>) {
        val existing = _channelsByClan.value[clanId] ?: return
        if (badgeDescs.isEmpty()) return
        val byId = badgeDescs.associateBy { it.channelId }
        var changed = false
        val updated = existing.map { row ->
            val p = byId[row.channelId] ?: return@map row
            val start = before[row.channelId]
            val liveChanged = start != null && (row.unreadCount != start.unreadCount ||
                row.lastSeenMessageId != start.lastSeenMessageId || row.lastSeenMessageTs != start.lastSeenMessageTs ||
                row.lastSentMessageId != start.lastSentMessageId || row.lastSentMessageTs != start.lastSentMessageTs)
            val (patched, _) = patchChannelRowReadStateFromSparseBadge(row, p)
            val snapshotBehindRead = p.hasLastSeenMessage() && (
                p.lastSeenMessage.id < row.lastSeenMessageId ||
                    (p.lastSeenMessage.timestampSeconds.toLong() and 0xFFFF_FFFFL) < row.lastSeenMessageTs)
            val newerLiveActivity = p.hasLastSentMessage() && (
                row.lastSentMessageId > p.lastSentMessage.id ||
                    row.lastSentMessageTs > (p.lastSentMessage.timestampSeconds.toLong() and 0xFFFF_FFFFL))
            val count = if (liveChanged || snapshotBehindRead) row.unreadCount else if (newerLiveActivity)
                maxOf(row.unreadCount, patched.unreadCount) else patched.unreadCount
            val remote = remoteReadCursors[row.channelId]
            val withRemoteCursor = if (remote == null) patched else patched.copy(
                lastSeenMessageId = maxOf(patched.lastSeenMessageId, remote.first),
                lastSeenMessageTs = maxOf(patched.lastSeenMessageTs, remote.second)
            )
            val readThroughLatest = withRemoteCursor.lastSentMessageTs > 0L && withRemoteCursor.lastSeenMessageTs >= withRemoteCursor.lastSentMessageTs
            val next = withRemoteCursor.copy(unreadCount = if (readThroughLatest) 0 else count)
            val rowChanged = next != row
            if (rowChanged) changed = true
            next
        }
        if (changed) updateCache(clanId, updated)
        clansController.get().reconcileClanBadgeFromChannels(clanId)
        notificationCenter.postNotificationOnMainThread(NotificationCenter.updateInterfaces, NotificationCenter.UPDATE_MASK_BADGE)
    }

    private fun patchChannelRowReadStateFromSparseBadge(
        row: ClanChannelEntity,
        p: ChannelDescription
    ): Pair<ClanChannelEntity, Boolean> {
        var next = row
        var changed = false
        if (p.countMessUnread != next.unreadCount) {
            changed = true
            next = next.copy(unreadCount = p.countMessUnread.coerceAtLeast(0))
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
      
        if (next.unreadCount > 0 && next.lastSentMessageTs > 0L &&
            next.lastSeenMessageTs >= next.lastSentMessageTs) {
            next = next.copy(unreadCount = 0)
            changed = true
        }
        return Pair(next, changed)
    }


    private fun mergeCache(clanId: Long, apiChannels: List<ClanChannelEntity>) {
        val existing = _channelsByClan.value[clanId] ?: emptyList()
        if (apiChannels.isEmpty() && existing.isNotEmpty()) {
            Log.w(TAG, "mergeCache: empty channel list for clan=$clanId with ${existing.size} cached — keeping cache")
            return
        }
        val existingMap = existing.associateBy { it.channelId }
        val merged = apiChannels.map { apiCh ->
            val apiNorm = withClanIdFromContext(clanId, apiCh)
            val cached = existingMap[apiNorm.channelId]
            if (cached == null) {
                apiNorm.copy(isMuted = resolveChannelMuted(clanId, apiNorm.channelId, apiNorm.isMuted))
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
                    unreadCount = cached.unreadCount,
                    ageRestricted = apiNorm.ageRestricted,
                    isMuted = resolveChannelMuted(clanId, apiNorm.channelId, apiNorm.isMuted),
                )
            }
        }
        val mergedIds = HashSet<Long>(merged.size)
        for (ch in merged) mergedIds.add(ch.channelId)
        val openId = currentOpenChannelId
        val preservedOpen = if (openId != 0L && openId !in mergedIds) {
            existing.filter { it.channelId == openId }.map { ch ->
                ch.copy(isMuted = resolveChannelMuted(clanId, ch.channelId, ch.isMuted))
            }
        } else {
            emptyList()
        }
        val finalList = if (preservedOpen.isEmpty()) merged else merged + preservedOpen
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
    private val processedBadgeKeys = LinkedHashSet<Pair<Long, Long>>()
    private val countedBadgeKeys = LinkedHashSet<Pair<Long, Long>>()
    private val deletedBadgeKeys = LinkedHashSet<Pair<Long, Long>>()
    private val badgeKeyLock = Any()
    private val remoteReadCursors = ConcurrentHashMap<Long, Pair<Long, Long>>()

    fun setCurrentChannel(channelId: Long) { currentOpenChannelId = channelId }

    private val badgeVisibility = ChannelBadgeVisibility()

    fun setVisibleBadgeChannel(channelId: Long, topicId: Long = 0L) {
        badgeVisibility.show(channelId, topicId)
    }

    fun clearVisibleBadgeChannel(channelId: Long, topicId: Long = 0L) {
        badgeVisibility.hide(channelId, topicId)
    }

    private fun wasRemotelyRead(channelId: Long, messageId: Long, timestamp: Long = 0L): Boolean {
        val cursor = remoteReadCursors[channelId] ?: return false
        return isChannelMessageAlreadySeen(messageId, timestamp, cursor.first, cursor.second)
    }

    private fun isViewingBadgeChannel(channelId: Long): Boolean =
        badgeVisibility.isViewingChannel(channelId, MainActivity.applicationPaused)

    private fun rememberCountedBadge(channelId: Long, messageId: Long) {
        if (messageId == 0L) return
        synchronized(badgeKeyLock) {
            countedBadgeKeys.add(channelId to messageId)
            if (countedBadgeKeys.size > MAX_BADGE_CACHE) countedBadgeKeys.remove(countedBadgeKeys.first())
        }
    }

    private fun forgetCountedBadge(channelId: Long, messageId: Long): Boolean =
        synchronized(badgeKeyLock) { countedBadgeKeys.remove(channelId to messageId) }

    private fun markBadgeDeleted(channelId: Long, messageId: Long): Boolean = synchronized(badgeKeyLock) {
        if (!deletedBadgeKeys.add(channelId to messageId)) return@synchronized false
        if (deletedBadgeKeys.size > MAX_BADGE_CACHE) deletedBadgeKeys.remove(deletedBadgeKeys.first())
        true
    }

    private fun wasBadgeDeleted(channelId: Long, messageId: Long): Boolean =
        synchronized(badgeKeyLock) { channelId to messageId in deletedBadgeKeys }

    fun clearCurrentChannel() {
        currentOpenChannelId = 0L
        currentOpenTopicId = 0L
    }

    fun badgeCountForRead(channelId: Long): Int = maxOf(
        findChannelById(channelId)?.unreadCount ?: 0,
        pendingMentionsByChannel[channelId]?.messageIds?.size ?: 0
    )

    fun setCurrentTopic(topicId: Long) { currentOpenTopicId = topicId }

    fun clearCurrentTopic() { currentOpenTopicId = 0L }

    fun getCurrentOpenTopicId(): Long = currentOpenTopicId

    fun tryMarkBadgeProcessed(channelId: Long, messageId: Long): Boolean {
        if (messageId == 0L) return true
        val key = channelId to messageId
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

    private data class PendingMentions(val clanId: Long, val messageIds: MutableSet<Long>)

    private val pendingMentionsByChannel = ConcurrentHashMap<Long, PendingMentions>()
    private val pendingBadgeRefreshJobs = ConcurrentHashMap<Long, Job>()
    private val pendingNewChannelsByClan = ConcurrentHashMap<Long, MutableSet<Long>>()

    private fun scheduleBadgeRefreshForClan(clanId: Long, channelId: Long) {
        if (clanId == 0L) return
        if (channelId != 0L) {
            pendingNewChannelsByClan.computeIfAbsent(clanId) { ConcurrentHashMap.newKeySet() }.add(channelId)
        }
        pendingBadgeRefreshJobs[clanId]?.cancel()
        pendingBadgeRefreshJobs[clanId] = appScope.launch(Dispatchers.Main.immediate) {
            delay(800)
            val newIds = pendingNewChannelsByClan.remove(clanId)
            try {
                val channels = _channelsByClan.value[clanId].orEmpty()
                val needsRefresh = newIds.isNullOrEmpty() || newIds.any { id ->
                    val ch = channels.firstOrNull { it.channelId == id }
                    ch == null || ch.unreadCount == 0
                }
                if (!needsRefresh) return@launch
                val cacheKey = apiCacheKey("listChannelsByClan", clanId.toString())
                cacheTracker.invalidate(cacheKey)
                loadChannelsForClanNow(clanId, force = true)
                clansController.get().reconcileClanBadgeFromChannels(clanId)
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.updateInterfaces, NotificationCenter.UPDATE_MASK_BADGE
                )
            } catch (_: Exception) {
            } finally {
                pendingBadgeRefreshJobs.remove(clanId)
            }
        }
    }

    private fun trackPendingMention(clanId: Long, channelId: Long, messageId: Long) {
        if (channelId == 0L) return
        pendingMentionsByChannel.compute(channelId) { _, previous ->
            val pending = previous?.takeIf { it.clanId == clanId }
                ?: PendingMentions(clanId, ConcurrentHashMap.newKeySet())
            pending.messageIds.add(messageId)
            pending
        }
    }

    private fun flushPendingMentionsInto(channelId: Long): Int {
        val channel = findChannelById(channelId) ?: return 0
        val pending = pendingMentionsByChannel.remove(channelId) ?: return 0
        val delta = pendingMentionUnreadDelta(
            pending.messageIds,
            channel.lastSeenMessageId,
            channel.unreadCount
        )
        if (delta > 0) adjustChannelUnread(channelId, delta, updateClanBadge = false)
        return pending.messageIds.size
    }

    private fun isDirectChannelType(type: Int): Boolean =
        type == CHANNEL_TYPE_DM || type == CHANNEL_TYPE_GROUP

    private fun applyUserChannelAdded(event: UserChannelAdded, currentUserId: Long) {
        if (!event.hasChannelDesc()) return
        val desc = event.channelDesc
        if (isDirectChannelType(desc.type)) return
        if (event.usersList.none { it.userId == currentUserId }) {
            return
        }
        invalidateLinkedChannel(desc.channelId)
        val clanId = event.clanId.takeIf { it != 0L } ?: desc.clanId
        if (clanId == 0L || desc.channelId == 0L) return
        cacheChannelAvatar(clanId, desc)
        val active = event.active.takeIf { it != 0 } ?: desc.active.takeIf { it != 0 } ?: 1
        val incoming = desc.toClanChannelEntity().copy(clanId = clanId, active = active)
        val existing = _channelsByClan.value[clanId] ?: emptyList()
        val existingRow = existing.firstOrNull { it.channelId == incoming.channelId }
        val merged = if (existingRow != null) {
            incoming.copy(
                unreadCount = maxOf(incoming.unreadCount, existingRow.unreadCount),
                lastSentMessageId = maxOf(incoming.lastSentMessageId, existingRow.lastSentMessageId),
                lastSentMessageTs = maxOf(incoming.lastSentMessageTs, existingRow.lastSentMessageTs),
                lastSeenMessageId = maxOf(incoming.lastSeenMessageId, existingRow.lastSeenMessageId),
                lastSeenMessageTs = maxOf(incoming.lastSeenMessageTs, existingRow.lastSeenMessageTs),
            )
        } else {
            incoming
        }
        updateCache(clanId, sortChannels(existing.filter { it.channelId != merged.channelId } + merged))
        appScope.launch(ioDispatcher) { clanChannelDao.upsert(merged) }
        flushPendingMentionsInto(merged.channelId)
        scheduleBadgeRefreshForClan(clanId, merged.channelId)
        notificationCenter.postNotificationOnMainThread(NotificationCenter.channelsDidLoad, clanId)
        notificationCenter.postNotificationOnMainThread(NotificationCenter.updateInterfaces, NotificationCenter.UPDATE_MASK_CHAT)
    }

    private fun applyUserChannelRemoved(event: UserChannelRemoved, currentUserId: Long) {
        if (isDirectChannelType(event.channelType)) return
        if (event.userIdsList.none { it == currentUserId }) return
        val channelId = event.channelId
        if (channelId == 0L) return
        invalidateLinkedChannel(channelId)
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
                val oldUnread = ch.unreadCount
                val newLastSent = if (messageId > ch.lastSentMessageId) messageId else ch.lastSentMessageId
                val newUnread = (oldUnread + delta).coerceAtLeast(0)
                val updated = channels.toMutableList()
                updated[idx] = ch.copy(
                    lastSentMessageId = newLastSent,
                    unreadCount = newUnread
                )
                val map = _channelsByClan.value.toMutableMap()
                map[clanId] = updated
                _channelsByClan.value = map
                if (updateClanBadge) {
                    clansController.get().updateClanBadgeCount(clanId, newUnread - oldUnread)
                }
                notificationCenter.postNotificationOnMainThread(NotificationCenter.updateInterfaces, NotificationCenter.UPDATE_MASK_BADGE)
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
                if (messageId < ch.lastSeenMessageId) return
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
            topicBadgeTracker.get().clearTopicUiBadge(channelId)
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

    fun requestMarkAsRead(clanId: Long, categoryId: Long = 0L, channelId: Long = 0L) {
        if (clanId == 0L) return
        appScope.launch(Dispatchers.Main.immediate) {
            runCatching {
                sessionManager.withAutoRefresh { session ->
                    api.markAsRead(
                        session.apiUrl,
                        session.token,
                        clanId = clanId,
                        categoryId = categoryId,
                        channelId = channelId
                    )
                }
            }.onSuccess {
                val targetIds = markAsReadTargetIds(clanId, categoryId, channelId)
                markTargetsAsRead(targetIds)
                if (categoryId == 0L && channelId == 0L) {
                    pendingMentionsByChannel.entries.removeIf { it.value.clanId == clanId }
                    clansController.get().applyBadgeRead(clanId)
                } else {
                    clansController.get().reconcileClanBadgeFromChannels(clanId)
                }
                notificationCenter.postNotificationOnMainThread(NotificationCenter.channelsDidLoad, clanId)
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.updateInterfaces, NotificationCenter.UPDATE_MASK_BADGE
                )
            }.onFailure {
                Log.e(TAG, "requestMarkAsRead(clan=$clanId cat=$categoryId ch=$channelId) failed", it)
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
            topicBadgeTracker.get().clearTopicUiBadge(channelId)
            return
        }
        for ((clanId, channels) in _channelsByClan.value) {
            val idx = channels.indexOfFirst { it.channelId == channelId }
            if (idx >= 0) {
                val ch = channels[idx]
                if (messageId < ch.lastSeenMessageId) return
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
        if (event.messageId == 0L && event.timestampSeconds == 0) return
        val channelId = event.channelId
        val tsLong = event.timestampSeconds.toLong() and 0xFFFF_FFFFL
        remoteReadCursors.compute(channelId) { _, old ->
            maxOf(old?.first ?: 0L, event.messageId) to maxOf(old?.second ?: 0L, tsLong)
        }
        if (sdTopicChannelsById.containsKey(channelId)) {
            when {
                event.messageId != 0L && event.badgeCount == 0 ->
                    updateLastSeen(channelId, event.messageId, event.timestampSeconds)
                event.messageId != 0L ->
                    updateChannelLastSeen(channelId, event.messageId, event.badgeCount, event.timestampSeconds)
                event.timestampSeconds != 0 ->
                    applyLastSeenTsOnlyFromSocket(channelId, event.timestampSeconds, event.badgeCount)
            }
            return
        }
        for ((clanId, channels) in _channelsByClan.value) {
            val idx = channels.indexOfFirst { it.channelId == channelId }
            if (idx < 0) continue
            val ch = channels[idx]
            if (event.messageId != 0L && event.messageId < ch.lastSeenMessageId) {
                return
            }
            val oldUnread = ch.unreadCount
            val newRow = ch.copy(
                unreadCount = 0,
                lastSeenMessageId = if (event.messageId != 0L) maxOf(ch.lastSeenMessageId, event.messageId) else ch.lastSeenMessageId,
                lastSeenMessageTs = maxOf(ch.lastSeenMessageTs, tsLong)
            )
            val updated = channels.toMutableList()
            updated[idx] = newRow
            val map = _channelsByClan.value.toMutableMap()
            map[clanId] = updated
            _channelsByClan.value = map
            if (oldUnread > 0) clansController.get().updateClanBadgeCount(clanId, -oldUnread)
            clansController.get().reconcileClanBadgeFromChannels(clanId)
            appScope.launch(ioDispatcher) { clanChannelDao.upsert(newRow) }
            notificationCenter.postNotificationOnMainThread(
                NotificationCenter.updateInterfaces, NotificationCenter.UPDATE_MASK_BADGE
            )
            return
        }
        val pending = pendingMentionsByChannel.remove(channelId)
        val cleared = pending?.takeIf { it.clanId == event.clanId }?.messageIds?.size ?: 0
        if (cleared > 0) {
            clansController.get().updateClanBadgeCount(event.clanId, -cleared)
        }
        clansController.get().reconcileClanBadgeFromChannels(event.clanId)
    }

    private fun applyLastSeenTsOnlyFromSocket(channelId: Long, timestampSeconds: Int, badgeCount: Int) {
        val tsLong = timestampSeconds.toLong() and 0xFFFF_FFFFL
        if (tsLong == 0L) return
        var topicClanId = 0L
        var topicDelta = 0
        var topicNewUnread = 0
        var topicChanged = false
        val wasTopic = sdTopicChannelsById.computeIfPresent(channelId) { _, ch ->
            topicClanId = ch.clanId
            val newSeenTs = maxOf(ch.lastSeenMessageTs, tsLong)
            val newUnread = badgeCount.coerceAtLeast(0)
            topicNewUnread = newUnread
            if (newSeenTs == ch.lastSeenMessageTs && newUnread == ch.unreadCount) return@computeIfPresent ch
            topicChanged = true
            topicDelta = newUnread - ch.unreadCount
            ch.copy(lastSeenMessageTs = newSeenTs, unreadCount = newUnread)
        } != null
        if (wasTopic) {
            if (topicNewUnread == 0) {
                topicBadgeTracker.get().clearTopicUiBadge(channelId)
            }
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
        val channels = _channelsByClan.value[clanId].orEmpty()
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

    private fun markTargetsAsRead(ids: List<Long>) {
        ids.forEach(::markChannelAsRead)
    }

    private fun observeSocketEvents() {
        appScope.launch {
            dispatcher.channelCreatedEvents.collect { event ->
                val clanId = event.clanId
                if (clanId == 0L) return@collect
                if (isDirectChannelType(event.channelType)) {
                    return@collect
                }
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
                if (existing.any { it.channelId == newChannel.channelId }) {
                    return@collect
                }
                val inheritedOrder = existing.firstOrNull {
                    it.categoryId == newChannel.categoryId && it.categoryOrder != 0
                }?.categoryOrder ?: 0
                val placed = if (inheritedOrder != 0) newChannel.copy(categoryOrder = inheritedOrder) else newChannel
                updateCache(clanId, sortChannels(existing + placed))
                appScope.launch(ioDispatcher) { clanChannelDao.upsert(placed) }
                flushPendingMentionsInto(placed.channelId)
                scheduleBadgeRefreshForClan(clanId, placed.channelId)
                notificationCenter.postNotificationOnMainThread(NotificationCenter.channelsDidLoad, clanId)
            }
        }

        appScope.launch {
            dispatcher.channelDeletedEvents.collect { event ->
                invalidateLinkedChannel(event.channelId)
                val clanId = event.clanId
                val existing = _channelsByClan.value[clanId] ?: return@collect
                updateCache(clanId, existing.filter { it.channelId != event.channelId })
                appScope.launch(ioDispatcher) { clanChannelDao.delete(clanId, event.channelId) }
                notificationCenter.postNotificationOnMainThread(NotificationCenter.channelsDidLoad, clanId)
            }
        }

        appScope.launch {
            dispatcher.channelArchiveEvents.collect { event ->
                applyChannelArchiveEvent(event)
            }
        }

        appScope.launch(Dispatchers.Main.immediate) {
            val currentUserId = sessionManager.sessionFlow
                .first { it != null }?.userId?.toLongOrNull() ?: 0L

            dispatcher.userChannelAddedEvents.collect { event ->
                applyUserChannelAdded(event, currentUserId)
            }
        }

        appScope.launch(Dispatchers.Main.immediate) {
            val currentUserId = sessionManager.sessionFlow
                .first { it != null }?.userId?.toLongOrNull() ?: 0L

            dispatcher.userChannelRemovedEvents.collect { event ->
                applyUserChannelRemoved(event, currentUserId)
            }
        }

        appScope.launch(Dispatchers.Main.immediate) {
            val currentUserId = sessionManager.sessionFlow
                .first { it != null }?.userId?.toLongOrNull() ?: 0L

            dispatcher.channelMessages.collect { msg ->
                if (msg.mode == STREAM_MODE_DM) return@collect
                if (msg.topicId != 0L) return@collect
                if (msg.code == CODE_CHAT_UPDATE) return@collect
                val msgTs = msg.createTimeSeconds.toLong() and 0xFFFF_FFFFL
                val clanId = findClanIdForChannel(msg.channelId)
                val effectiveClanId = if (clanId != 0L) clanId else msg.clanId
                if (effectiveClanId == 0L) {
                    if (msg.code == CODE_CHAT_REMOVE || msg.channelId == currentOpenChannelId) return@collect
                    updateLastSentMessage(msg.channelId, msg.messageId, msgTs)
                    notificationCenter.postNotificationOnMainThread(
                        NotificationCenter.updateInterfaces, NotificationCenter.UPDATE_MASK_BADGE
                    )
                    return@collect
                }
                val roleIds by lazy(LazyThreadSafetyMode.NONE) {
                    userClanController.get().getClanMembers(effectiveClanId)
                        .firstOrNull { it.userId == currentUserId }?.roleIds.orEmpty().toSet()
                }
                val hasMentionMetadata = !msg.mentions.isEmpty || !msg.references.isEmpty ||
                    msg.content.contains("\"mentions\"")
                if (msg.code == CODE_CHAT_REMOVE) {
                    if (msg.messageId == 0L || msg.senderId == currentUserId) return@collect
                    val badgeChannelId = msg.channelId
                    if (!markBadgeDeleted(badgeChannelId, msg.messageId)) return@collect
                    val wasCounted = forgetCountedBadge(badgeChannelId, msg.messageId)
                    val channel = findChannelById(badgeChannelId)
                    val mentionsUser = hasMentionMetadata && channelMessageMentionsUser(
                        msg.content, msg.mentions, msg.references, currentUserId, roleIds
                    )
                    if (wasCounted || mentionsUser) {
                        if (channel != null && canDecrementDeletedMention(
                                wasCounted, channel.unreadCount, msg.messageId, msgTs,
                                channel.lastSeenMessageId, channel.lastSeenMessageTs
                            )) {
                            adjustChannelUnread(msg.channelId, -1)
                        } else if (channel == null &&
                            pendingMentionsByChannel[msg.channelId]?.messageIds?.remove(msg.messageId) == true) {
                            clansController.get().updateClanBadgeCount(effectiveClanId, -1)
                        }
                    }
                    return@collect
                }
                val canCountBadge = msg.code !in NON_BADGE_MESSAGE_CODES && msg.messageId != 0L &&
                    !wasBadgeDeleted(msg.channelId, msg.messageId) &&
                    !wasRemotelyRead(msg.channelId, msg.messageId, msgTs)
                val isViewingForBadge = isViewingBadgeChannel(msg.channelId)
                if (msg.senderId == currentUserId) {
                    if (msg.channelId != currentOpenChannelId) {
                        updateLastSentMessage(msg.channelId, msg.messageId, msgTs)
                        notificationCenter.postNotificationOnMainThread(
                            NotificationCenter.updateInterfaces, NotificationCenter.UPDATE_MASK_BADGE
                        )
                    }
                    return@collect
                }
                val mentionsUser = canCountBadge && hasMentionMetadata && channelMessageMentionsUser(
                    msg.content, msg.mentions, msg.references, currentUserId, roleIds
                )
                if (mentionsUser && !isViewingForBadge) {
                    val channel = findChannelById(msg.channelId)
                    val alreadySeen = channel != null && isMentionAlreadySeen(
                        msg.messageId, msgTs, channel.lastSeenMessageId, channel.lastSeenMessageTs
                    )
                    if (!alreadySeen && tryMarkBadgeProcessed(msg.channelId, msg.messageId)) {
                        updateLastSentMessage(msg.channelId, msg.messageId, msgTs)
                        rememberCountedBadge(msg.channelId, msg.messageId)
                        if (channel != null) {
                            incrementUnread(msg.channelId, msg.messageId)
                        } else {
                            trackPendingMention(effectiveClanId, msg.channelId, msg.messageId)
                            clansController.get().updateClanBadgeCount(effectiveClanId, 1)
                        }
                    }
                }
               
                if (msg.channelId != currentOpenChannelId) {
                    badgeCoordinator.get()
                        .onIncomingUnreadChannelSignal(effectiveClanId, msg.channelId, msg.messageId, msgTs)
                }
            }
        }

        appScope.launch(Dispatchers.Main.immediate) {
            dispatcher.notifications.collect { notification ->
                val code = notification.code
                if (code != NOTIFICATION_CODE_USER_MENTIONED && code != NOTIFICATION_CODE_USER_REPLIED) {
                    return@collect
                }
                val clanId = notification.clanId
                val channelId = notification.channelId
                if (clanId == 0L || channelId == 0L) {
                    return@collect
                }
                val legacyTopicId = resolveNotificationTopicId(notification)
                if (legacyTopicId != 0L) {
                    if (currentOpenTopicId == legacyTopicId) return@collect
                    val contentJson = runCatching { JSONObject(notification.content.toStringUtf8()) }.getOrNull()
                    val messageId = contentJson?.optString("message_id")?.toLongOrNull() ?: 0L
                    val msgTime = contentJson?.optString("create_time_seconds")?.toLongOrNull() ?: 0L
                    val topic = findChannelById(legacyTopicId)
                    if (topic != null && topic.lastSeenMessageTs > 0L && msgTime > 0L && msgTime <= topic.lastSeenMessageTs) return@collect
                    topicBadgeTracker.get().tryIncrementFromNotification(clanId, channelId, legacyTopicId, messageId)
                    notificationCenter.postNotificationOnMainThread(NotificationCenter.updateInterfaces, NotificationCenter.UPDATE_MASK_BADGE)
                    return@collect
                }
                if (notification.channelType == CHANNEL_TYPE_APP ||
                    notification.channelType == CHANNEL_TYPE_VOICE) {
                    return@collect
                }
                if (isViewingBadgeChannel(channelId)) {
                    return@collect
                }
                val badgeMessage = notificationBadgeMessage(notification.content)

                val messageId = badgeMessage.messageId
                if (messageId == 0L) {
                    return@collect
                }
                if (wasRemotelyRead(channelId, messageId, badgeMessage.timestamp) ||
                    wasBadgeDeleted(channelId, messageId)) {
                    return@collect
                }
                val msgTime = badgeMessage.timestamp.takeIf { it != 0L }
                    ?: (notification.createTimeSeconds.toLong() and 0xFFFF_FFFFL)
                val badgeChannel = findChannelById(channelId)
                if (badgeChannel != null && isMentionAlreadySeen(
                        messageId, msgTime, badgeChannel.lastSeenMessageId, badgeChannel.lastSeenMessageTs
                    )) {
                    return@collect
                }

                if (!tryMarkBadgeProcessed(channelId, messageId)) {
                    return@collect
                }
                updateLastSentMessage(channelId, messageId, msgTime)
                rememberCountedBadge(channelId, messageId)
                val channelInCache = _channelsByClan.value[clanId]?.any { it.channelId == channelId } == true
                if (channelInCache) {
                    incrementUnread(channelId, messageId)
                } else {
                    trackPendingMention(clanId, channelId, messageId)
                    clansController.get().updateClanBadgeCount(clanId, 1)
                }
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.updateInterfaces, NotificationCenter.UPDATE_MASK_BADGE
                )
            }
        }

        appScope.launch {
            dispatcher.categoryEvents.collect { event ->
                applyCategorySocketEvent(event)
            }
        }

        appScope.launch {
            dispatcher.channelUpdatedEvents.collect { event ->
                // Linked channels may not be present in the sidebar cache.
                invalidateLinkedChannel(event.channelId)
                val clanId = event.clanId
                val existing = _channelsByClan.value[clanId] ?: return@collect
                val updated = existing.map { ch ->
                    if (ch.channelId != event.channelId) ch
                    else {
                        val newCategoryId = if (event.categoryId != 0L) event.categoryId else ch.categoryId
                        val categoryName = if (newCategoryId != ch.categoryId) {
                            getCachedCategories(clanId).firstOrNull { it.categoryId == newCategoryId }?.categoryName
                                ?: ch.categoryName
                        } else {
                            ch.categoryName
                        }
                        ch.copy(
                            channelLabel = event.channelLabel.ifEmpty { ch.channelLabel },
                            topic = if (event.topic.isNotEmpty()) event.topic else ch.topic,
                            categoryId = newCategoryId,
                            categoryName = categoryName,
                            isPrivate = event.channelPrivate,
                            ageRestricted = event.ageRestricted,
                        )
                    }
                }
                updateCache(clanId, updated)
                val entity = updated.find { it.channelId == event.channelId } ?: return@collect
                appScope.launch(ioDispatcher) { clanChannelDao.upsert(entity) }
                notificationCenter.postNotificationOnMainThread(NotificationCenter.channelsDidLoad, clanId)
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.updateInterfaces, NotificationCenter.UPDATE_MASK_CHAT
                )
            }
        }

        appScope.launch {
            dispatcher.permissionChangedEvents.collect { event ->
                val userId = sessionManager.sessionFlow.first()?.userId?.toLongOrNull() ?: 0L
                if (userId != 0L && event.userId == userId) invalidateLinkedChannel(event.channelId)
            }
        }

        appScope.launch {
            dispatcher.permissionSetEvents.collect { event ->
                val userId = sessionManager.sessionFlow.first()?.userId?.toLongOrNull() ?: 0L
                if (userId != 0L && (event.userId == userId || event.userId == 0L || event.roleId != 0L)) {
                    invalidateLinkedChannel(event.channelId)
                }
            }
        }

        appScope.launch(Dispatchers.Main.immediate) {
            dispatcher.lastSeenMessageEvents.collect { event ->
                applyLastSeenMessageSocketEvent(event)
            }
        }

        appScope.launch(Dispatchers.Main.immediate) {
            dispatcher.markAsRead.collect { event ->
                if (event.clanId == 0L) return@collect
                val targetIds = markAsReadTargetIds(event.clanId, event.categoryId, event.channelId)
                markTargetsAsRead(targetIds)
                if (event.categoryId == 0L && event.channelId == 0L) {
                    pendingMentionsByChannel.entries.removeIf { it.value.clanId == event.clanId }
                    clansController.get().applyBadgeRead(event.clanId)
                } else {
                    clansController.get().reconcileClanBadgeFromChannels(event.clanId)
                }
                notificationCenter.postNotificationOnMainThread(NotificationCenter.channelsDidLoad, event.clanId)
            }
        }

        appScope.launch {
            dispatcher.unmuteEvents.collect { event ->
                val clanId = event.clanId
                val channelId = event.channelId
                if (clanId == 0L || channelId == 0L) return@collect
                patchChannelMuteLocally(clanId, channelId, isMuted = false)
            }
        }

        appScope.launch {
            dispatcher.notiUserChannelEvents.collect { noti ->
                val channelId = noti.channelId
                if (channelId == 0L) return@collect
                val channel = findChannelById(channelId) ?: return@collect
                patchChannelNotificationType(channelId, noti.notificationSettingType)
                val isMuted = isMutedFromNotificationUserChannel(noti)
                patchChannelMuteLocally(channel.clanId, channelId, isMuted)
            }
        }
    }

    private fun applyCategorySocketEvent(event: CategoryEvent) {
        val clanId = event.clanId
        val categoryId = event.id
        if (clanId == 0L || categoryId == 0L) return
        when (event.status) {
            CATEGORY_EVENT_DELETE -> {
                categoriesByClan[clanId] = getCachedCategories(clanId).filter { it.categoryId != categoryId }
            }
            CATEGORY_EVENT_CREATE, CATEGORY_EVENT_UPDATE -> {
                val item = ClanCategoryItem(categoryId, event.categoryName, 0, clanId)
                val list = getCachedCategories(clanId).toMutableList()
                val idx = list.indexOfFirst { it.categoryId == categoryId }
                if (idx >= 0) list[idx] = item else list.add(item)
                categoriesByClan[clanId] = list
                if (event.categoryName.isNotBlank()) {
                    val channels = _channelsByClan.value[clanId] ?: return
                    val updated = channels.map { ch ->
                        if (ch.categoryId == categoryId) ch.copy(categoryName = event.categoryName) else ch
                    }
                    if (updated != channels) {
                        updateCache(clanId, updated)
                        appScope.launch(ioDispatcher) {
                            updated.filter { it.categoryId == categoryId }.forEach { clanChannelDao.upsert(it) }
                        }
                        notificationCenter.postNotificationOnMainThread(NotificationCenter.channelsDidLoad, clanId)
                    }
                }
            }
        }
    }

    companion object {
        private const val CATEGORY_EVENT_CREATE = 1
        private const val CATEGORY_EVENT_UPDATE = 2
        private const val CATEGORY_EVENT_DELETE = 3
        private const val MAX_CATEGORY_API_PAGES = 20
    }
}
