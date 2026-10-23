package com.mezon.mobile.home.clans

import android.content.Context
import com.mezon.mobile.BuildConfig
import com.mezon.mobile.R
import android.util.Log
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.data.db.ClanDao
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.network.ApiCacheTracker
import com.mezon.mezon.api.ClanBadgeCount
import com.mezon.mezon.api.GenerateClanWebhookResponse
import com.mezon.mezon.api.ListClanWebhookResponse
import com.mezon.mezon.api.SystemMessage
import com.mezon.mezon.api.SystemMessageRequest
import com.mezon.mezon.api.WebhookGenerateResponse
import com.mezon.mezon.api.WebhookListResponse
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.network.MezonSocket
import com.mezon.mobile.network.SocketEventDispatcher
import com.mezon.mobile.network.apiCacheKey
import com.mezon.mobile.session.SessionManager
import com.mezon.mobile.util.avatarImgproxyUrl
import com.mezon.mobile.util.createImgproxyUrl
import com.mezon.mobile.util.AttachmentUploader
import com.mezon.mobile.home.BadgeCoordinator
import com.mezon.mobile.home.TopicBadgeTracker
import com.mezon.mobile.home.clans.channelapp.ChannelAppController
import com.mezon.mobile.home.notifications.NotificationStore
import com.mezon.mobile.home.UserClanController
import com.mezon.mobile.home.profile.UserController
import com.mezon.mobile.home.voice.VoiceController
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ClansController"
private val CLAN_ICON_SIZE_PX = LayoutHelper.dp(40)
const val CLAN_CREATE_LIMIT = 50

@Singleton
class ClansController @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val api: MezonApi,
    private val sessionManager: SessionManager,
    private val clanDao: ClanDao,
    private val dispatcher: SocketEventDispatcher,
    private val notificationCenter: NotificationCenter,
    private val cacheTracker: ApiCacheTracker,
    private val channelController: ChannelController,
    private val channelAppController: ChannelAppController,
    private val userController: UserController,
    private val userClanController: UserClanController,
    private val roleController: RoleController,
    private val mezonSocket: MezonSocket,
    private val badgeCoordinator: Lazy<BadgeCoordinator>,
    private val notificationStore: Lazy<NotificationStore>,
    private val topicBadgeTracker: Lazy<TopicBadgeTracker>,
    private val voiceController: Lazy<VoiceController>,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope
) {
    private val _clans = MutableStateFlow<List<ClanEntity>>(emptyList())
    val clans: StateFlow<List<ClanEntity>> = _clans.asStateFlow()

    private val metadataLock = Any()
    private var metadataRevision = 0L
    private val persistenceMutex = Mutex()

    private fun currentMetadataRevision(): Long = synchronized(metadataLock) { metadataRevision }

    private fun updateClanMetadata(transform: (List<ClanEntity>) -> List<ClanEntity>): Boolean =
        synchronized(metadataLock) {
            metadataRevision++
            updateClanState(transform)
        }

    private fun updateClanState(transform: (List<ClanEntity>) -> List<ClanEntity>): Boolean =
        synchronized(metadataLock) {
            val current = _clans.value
            val updated = transform(current)
            if (updated == current) return@synchronized false
            _clans.value = updated
            true
        }

    private fun updateClanMetadataIfCurrent(
        revision: Long,
        acceptUnchanged: Boolean = false,
        transform: (List<ClanEntity>) -> List<ClanEntity>,
    ): Boolean = synchronized(metadataLock) {
        if (metadataRevision != revision) {
            val current = _clans.value
            return@synchronized acceptUnchanged && transform(current) == current
        }
        updateClanMetadata(transform)
        true
    }

    private suspend fun persistCurrentClans(clanIds: Collection<Long>) {
        persistenceMutex.withLock {
            val ids = clanIds.toSet()
            val latest = _clans.value.filter { it.clanId in ids }
            withContext(ioDispatcher) { clanDao.upsertAll(latest) }
        }
    }

    private val _selectedClanId = MutableStateFlow(0L)
    val selectedClanId: StateFlow<Long> = _selectedClanId.asStateFlow()

    private val _clanLogoUpdateInFlight = MutableStateFlow<Set<Long>>(emptySet())
    val clanLogoUpdateInFlight: StateFlow<Set<Long>> = _clanLogoUpdateInFlight.asStateFlow()

    var clansLoaded = false
        private set

    init {
        val revisionAtCacheRead = currentMetadataRevision()
        appScope.launch {
            val cached = withContext(ioDispatcher) { clanDao.getAll() }
            val restored = cached.isNotEmpty() && synchronized(metadataLock) {
                metadataRevision == revisionAtCacheRead && updateClanState { cached }
            }
            if (restored) {
                clansLoaded = true
                preWarmClanLogos(cached)
                notificationCenter.postNotificationOnMainThread(NotificationCenter.clansDidLoad)
                val lastClanId = withContext(ioDispatcher) { sessionManager.getLastClanId() }
                val initialClan = cached.firstOrNull { it.clanId == lastClanId } ?: cached.first()
                restoreInitialClan(initialClan.clanId)
                badgeCoordinator.get().processDeferredQueue()
            }
        }
        observeSocketEvents()
    }

    @Synchronized
    private fun restoreInitialClan(clanId: Long): Boolean {
        if (_selectedClanId.value != 0L) return false
        selectClanInternal(clanId, force = false)
        return true
    }

    fun cleanup() {
        updateClanMetadata { emptyList() }
        _selectedClanId.value = 0L
        clansLoaded = false
        ClanCell.clearAvatarCache()
    }

    @Synchronized
    fun selectClan(clanId: Long, force: Boolean = false) {
        selectClanInternal(clanId, force)
    }

    private fun selectClanInternal(clanId: Long, force: Boolean) {
        if (!force && _selectedClanId.value == clanId) return
        _selectedClanId.value = clanId
        channelController.loadChannelsForClan(clanId)
        channelAppController.loadAppsForClan(clanId)
        notificationStore.get().setCurrentClan(clanId)
        topicBadgeTracker.get().hydrateForClan(clanId)
        roleController.loadPermissionCatalogIfNeeded()
        roleController.loadPermissionsUserForClan(clanId, force = true)
        roleController.loadRolesForClan(clanId)
        appScope.launch {
            sessionManager.saveLastClanId(clanId)
            try {
                if (clanId != 0L && mezonSocket.awaitConnected()) {
                    channelController.awaitChannelsForClan(clanId)
                    mezonSocket.joinClanChat(clanId)
                    if (_selectedClanId.value == clanId) {
                        voiceController.get().refetchVoiceChannelMembersAfterClanJoin(clanId)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "joinClanChat($clanId) failed", e)
            }
        }
        notificationCenter.postNotificationOnMainThread(NotificationCenter.selectedClanChanged, clanId)
    }

    fun getClanCount(): Int = _clans.value.size

    suspend fun findClanById(clanId: Long): ClanEntity? {
        if (clanId == 0L) return null
        return _clans.value.firstOrNull { it.clanId == clanId }
            ?: withContext(ioDispatcher) { clanDao.getById(clanId) }
    }

    suspend fun isDuplicateClanName(clanName: String): Boolean {
        return runCatching {
            sessionManager.withAutoRefresh { session ->
                withContext(ioDispatcher) {
                    api.checkDuplicateName(
                        apiUrl = session.apiUrl,
                        token = session.token,
                        name = clanName,
                        type = MezonSocket.TYPE_CHECK_CLAN,
                        conditionId = 0L
                    )
                }
            }
        }.getOrDefault(false)
    }

    suspend fun createClan(
        clanName: String,
        logo: String,
        template: ClanTemplateSpec?
    ): ClanEntity {
        val createdClan = sessionManager.withAutoRefresh { session ->
            withContext(ioDispatcher) {
                api.createClanDesc(
                    apiUrl = session.apiUrl,
                    token = session.token,
                    clanName = clanName,
                    logo = logo
                )
            }
        }.toClanEntity().let { created ->
            if (created.creatorId == 0L && userController.userId != 0L) {
                created.copy(creatorId = userController.userId)
            } else {
                created
            }
        }
        updateClanMetadata { clans -> clans + createdClan.copy(clanOrder = clans.size) }
        persistCurrentClans(listOf(createdClan.clanId))
        clansLoaded = true
        notificationCenter.postNotificationOnMainThread(NotificationCenter.clansDidLoad)
        selectClan(createdClan.clanId)
        if (template != null) {
            createTemplateStructure(createdClan.clanId, template)
        }
        channelController.loadChannelsForClanNow(createdClan.clanId, force = true)
        return createdClan
    }

    private suspend fun createTemplateStructure(clanId: Long, template: ClanTemplateSpec) {
        sessionManager.withAutoRefresh { session ->
            val initialChannels = withContext(ioDispatcher) {
                api.listChannelsByClan(session.apiUrl, session.token, clanId)
            }
            var defaultCategoryId = initialChannels.channeldescList.firstOrNull()?.categoryId ?: 0L
            for (category in template.categories) {
                val categoryId = if (category.name.isBlank()) {
                    defaultCategoryId
                } else {
                    val createdCategory = withContext(ioDispatcher) {
                        api.createCategoryDesc(session.apiUrl, session.token, clanId, category.name)
                    }
                    createdCategory.categoryId
                }
                if (categoryId == 0L) {
                    continue
                }
                if (defaultCategoryId == 0L) {
                    defaultCategoryId = categoryId
                }
                for (channel in category.channels) {
                    withContext(ioDispatcher) {
                        api.createChannelDesc(
                            apiUrl = session.apiUrl,
                            token = session.token,
                            clanId = clanId,
                            type = channel.type,
                            channelPrivate = if (channel.isPrivate) 1 else 0,
                            userIds = emptyList(),
                            channelLabel = channel.name,
                            categoryId = categoryId,
                            parentId = 0L
                        )
                    }
                    delay(400)
                }
            }
        }
    }

    fun loadClans(force: Boolean = false) {
        val cacheKey = apiCacheKey("listClanDescs")
        appScope.launch(Dispatchers.Main.immediate) {
            try {
                if (!force && cacheTracker.shouldCall(cacheKey) == ApiCacheTracker.ShouldCall.SKIP) {
                    if (_clans.value.isNotEmpty()) {
                        notificationCenter.postNotificationOnMainThread(NotificationCenter.clansDidLoad)
                        val selectedId = _selectedClanId.value
                        if (selectedId != 0L) {
                            notificationCenter.postNotificationOnMainThread(NotificationCenter.channelsDidLoad, selectedId)
                            channelController.loadChannelsForClanNow(selectedId, force)
                            channelAppController.loadAppsForClan(selectedId, force)
                        }
                        fetchClanBadgeCountsIfNeeded(force)
                        badgeCoordinator.get().processDeferredQueue()
                    }
                    return@launch
                }
                val revisionAtRequest = currentMetadataRevision()
                val result = sessionManager.withAutoRefresh { session ->
                    withContext(ioDispatcher) {
                        api.listClanDescs(session.apiUrl, session.token)
                    }
                }
                val apiEntities = result.clandescList.mapIndexed { index, desc ->
                    desc.toClanEntity().let { entity ->
                        if (entity.clanOrder == 0) entity.copy(clanOrder = index) else entity
                    }
                }

                val applied = updateClanMetadataIfCurrent(revisionAtRequest, acceptUnchanged = true) { current ->
                    val existingOrder = current.mapIndexed { i, c -> c.clanId to i }.toMap()
                    val cachedById = current.associateBy { it.clanId }
                    apiEntities.map { entity ->
                        val cached = cachedById[entity.clanId] ?: return@map entity
                        entity.copy(badgeCount = cached.badgeCount, hasUnread = cached.hasUnread)
                    }.sortedBy { existingOrder[it.clanId] ?: it.clanOrder }
                }
                if (!applied) {
                    loadClans(force = true)
                    return@launch
                }
                val sorted = _clans.value
                clansLoaded = true
                cacheTracker.markCalled(cacheKey)
                preWarmClanLogos(sorted)
                persistCurrentClans(sorted.map { it.clanId })
                notificationCenter.postNotificationOnMainThread(NotificationCenter.clansDidLoad)
                badgeCoordinator.get().processDeferredQueue()

                val initializedDefaultClan = sorted.isNotEmpty() && restoreInitialClan(sorted.first().clanId)
                val sel = _selectedClanId.value
                if (sel != 0L) {
                    channelController.loadChannelsForClanNow(sel, force)
                    channelAppController.loadAppsForClan(sel, force)
                    roleController.loadPermissionCatalogIfNeeded()
                    roleController.loadPermissionsUserForClan(sel, force = initializedDefaultClan)
                    roleController.loadRolesForClan(sel)
                }

                fetchClanBadgeCountsIfNeeded(force)
            } catch (e: Exception) {
                Log.e(TAG, "loadClans failed", e)
            }
        }
    }

    private data class ClanBadgeState(val badgeCount: Int, val hasUnread: Boolean)

    private suspend fun fetchClanBadgeCountsIfNeeded(force: Boolean) {
        val badgeKey = apiCacheKey("listClanBadgeCount")
        if (!force && cacheTracker.shouldCall(badgeKey) == ApiCacheTracker.ShouldCall.SKIP) {
            return
        }
        val before = _clans.value.associateBy { it.clanId }
        runCatching {
            val badgeResponse = sessionManager.withAutoRefresh { session ->
                api.listClanBadgeCount(session.apiUrl, session.token)
            }
            cacheTracker.markCalled(badgeKey)
            applyClanBadgeList(badgeResponse.listBadgeList, before = before)
        }.onFailure {
            Log.e(TAG, "listClanBadgeCount: request failed", it)
        }
    }

    private fun applyClanBadgeList(
        badges: List<ClanBadgeCount>,
        before: Map<Long, ClanEntity>
    ) {
        val byClanId = badges.associateBy { it.clanId }
        val changedRows = ArrayList<ClanEntity>()
        val changed = updateClanState { clans ->
            clans.map { clan ->
                val start = before[clan.clanId]
                if (start != null && (start.badgeCount != clan.badgeCount || start.hasUnread != clan.hasUnread)) {
                    return@map clan
                }
                val target = byClanId[clan.clanId]?.let { b ->
                    val badge = b.badge.coerceAtLeast(0)
                    ClanBadgeState(badge, b.hasUnread || badge > 0)
                } ?: ClanBadgeState(0, false)
                if (clan.badgeCount == target.badgeCount && clan.hasUnread == target.hasUnread) return@map clan
                clan.copy(badgeCount = target.badgeCount, hasUnread = target.hasUnread).also { changedRows.add(it) }
            }
        }
        if (!changed) return
        appScope.launch { persistCurrentClans(changedRows.map { it.clanId }) }
        notificationCenter.postNotificationOnMainThread(
            NotificationCenter.updateInterfaces, NotificationCenter.UPDATE_MASK_BADGE
        )
    }

    fun requestMarkAllClanChannelsRead(clanId: Long) {
        channelController.requestMarkAsRead(clanId)
    }

    private fun applyClanOverviewUpdate(
        clanId: Long,
        clanName: String,
        clanBannerUrl: String?,
        preventAnonymous: Boolean,
        welcomeChannelId: Long?,
        isOnboarding: Boolean?,
    ) {
        updateClanMetadata { clans ->
            clans.map { existing ->
                if (existing.clanId != clanId) existing else existing.copy(
                    clanName = clanName,
                    banner = clanBannerUrl ?: existing.banner,
                    preventAnonymous = preventAnonymous,
                    welcomeChannelId = welcomeChannelId ?: existing.welcomeChannelId,
                    isOnboarding = isOnboarding ?: existing.isOnboarding,
                )
            }
        }
        val merged = _clans.value.find { it.clanId == clanId } ?: return
        appScope.launch { persistCurrentClans(listOf(clanId)) }
        cacheTracker.invalidate(apiCacheKey("listClanDescs"))
        notificationCenter.postNotificationOnMainThread(NotificationCenter.clanInfoUpdated, merged.clanId)
    }

    fun invalidateBannerImageCaches(rawSourceUrls: Collection<String>, extraWidthPx: Int = 0) {
        if (rawSourceUrls.isEmpty()) return
        val h = LayoutHelper.dp(200f)
        val minW = LayoutHelper.dp(300f)
        val screenW = appContext.resources.displayMetrics.widthPixels
        val widths = buildSet {
            add(minW)
            add(screenW.coerceAtLeast(minW))
            if (extraWidthPx > 0) add(extraWidthPx.coerceAtLeast(minW))
        }
        val loader = MezonImageLoader.getInstance(appContext)
        for (src in rawSourceUrls) {
            val t = src.trim()
            if (t.isEmpty()) continue
            for (w in widths) {
                val url = createImgproxyUrl(t, w, h, "fit")
                if (url.isEmpty()) continue
                loader.invalidateCachedLoad(url, w, h)
            }
        }
    }

    suspend fun updateClanOverviewDesc(
        clanId: Long,
        clanName: String,
        clanBannerUrl: String?,
        preventAnonymous: Boolean,
        welcomeChannelId: Long?,
        isOnboarding: Boolean?,
        invalidateBannerSources: Collection<String>? = null,
        bannerExtraWidthPx: Int = 0,
    ) {
        val clearBanner = clanBannerUrl?.isEmpty() == true
        sessionManager.withAutoRefresh { session ->
            withContext(ioDispatcher) {
                api.updateClanDesc(
                    session.apiUrl,
                    session.token,
                    clanId,
                    clanName = clanName,
                    banner = if (clearBanner) null else clanBannerUrl,
                    clearBanner = clearBanner,
                    preventAnonymous = preventAnonymous,
                    welcomeChannelId = welcomeChannelId,
                    isOnboarding = isOnboarding,
                )
            }
        }
        if (!invalidateBannerSources.isNullOrEmpty()) {
            invalidateBannerImageCaches(invalidateBannerSources, bannerExtraWidthPx)
        }
        applyClanOverviewUpdate(clanId, clanName, clanBannerUrl, preventAnonymous, welcomeChannelId, isOnboarding)
    }

    suspend fun updateClanSystemMessage(body: SystemMessageRequest): SystemMessage {
        return sessionManager.withAutoRefresh { session ->
            withContext(ioDispatcher) {
                api.updateSystemMessage(session.apiUrl, session.token, body)
            }
        }
    }

    suspend fun uploadClanBannerJpeg(jpegBytes: ByteArray): String {
        require(jpegBytes.isNotEmpty())
        return sessionManager.withAutoRefresh { session ->
            withContext(ioDispatcher) {
                val ts = System.currentTimeMillis() / 1000
                val filename = "${ts}_banner.jpg"
                val mime = "image/jpeg"
                AttachmentUploader.uploadAttachmentBytes(
                    api,
                    session.apiUrl,
                    session.token,
                    filename,
                    mime,
                    jpegBytes,
                    1920,
                    1080,
                    BuildConfig.CLOUDFLARE_CDN_URL,
                ).cdnUrl
            }
        }
    }

    fun deleteClan(clanId: Long, onResult: (success: Boolean, message: String?) -> Unit) {
        if (clanId == 0L) {
            appScope.launch(Dispatchers.Main.immediate) { onResult(false, "") }
            return
        }
        appScope.launch {
            try {
                sessionManager.withAutoRefresh { session ->
                    withContext(ioDispatcher) {
                        api.deleteClanDesc(session.apiUrl, session.token, clanId)
                    }
                }
                withContext(Dispatchers.Main.immediate) {
                    applyClanLeftLocally(clanId)
                    onResult(true, null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "deleteClan($clanId) failed", e)
                withContext(Dispatchers.Main.immediate) {
                    onResult(false, e.message)
                }
            }
        }
    }

    fun leaveClan(clanId: Long, onResult: (success: Boolean, message: String?) -> Unit) {
        val uid = userController.userId
        if (uid == 0L || clanId == 0L) {
            appScope.launch(Dispatchers.Main.immediate) { onResult(false, "") }
            return
        }
        appScope.launch {
            try {
                sessionManager.withAutoRefresh { session ->
                    withContext(ioDispatcher) {
                        api.removeClanUsers(session.apiUrl, session.token, clanId, listOf(uid))
                    }
                }
                withContext(Dispatchers.Main.immediate) {
                    applyClanLeftLocally(clanId)
                    onResult(true, null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "leaveClan($clanId) failed", e)
                withContext(Dispatchers.Main.immediate) {
                    onResult(false, e.message)
                }
            }
        }
    }

    suspend fun kickMember(clanId: Long, userId: Long): Result<Unit> = runCatching {
        require(clanId != 0L && userId != 0L)
        sessionManager.withAutoRefresh { session ->
            withContext(ioDispatcher) {
                api.removeClanUsers(session.apiUrl, session.token, clanId, listOf(userId))
            }
        }
        userClanController.removeClanMember(clanId, userId)
    }.onFailure { Log.e(TAG, "kickMember($clanId, $userId) failed", it) }

    suspend fun transferOwnership(clanId: Long, newOwnerId: Long): Result<Unit> = runCatching {
        require(clanId != 0L && newOwnerId != 0L)
        sessionManager.withAutoRefresh { session ->
            withContext(ioDispatcher) {
                api.transferClanOwnership(session.apiUrl, session.token, clanId, newOwnerId)
            }
        }
        withContext(Dispatchers.Main.immediate) {
            val index = _clans.value.indexOfFirst { it.clanId == clanId }
            if (index >= 0) {
                updateClanMetadata { clans ->
                    clans.map { if (it.clanId == clanId) it.copy(creatorId = newOwnerId) else it }
                }
                appScope.launch { persistCurrentClans(listOf(clanId)) }
                notificationCenter.postNotificationOnMainThread(NotificationCenter.clanInfoUpdated, clanId)
            }
        }
    }.onFailure { Log.e(TAG, "transferOwnership($clanId, $newOwnerId) failed", it) }

    private fun applyClanLeftLocally(clanId: Long) {
        cacheTracker.invalidate(apiCacheKey("listClanDescs"))
        cacheTracker.invalidate(apiCacheKey("listClanUsers", clanId.toString()))
        cacheTracker.invalidate(apiCacheKey("listClanBadgeCount"))
        updateClanMetadata { clans -> clans.filter { it.clanId != clanId } }
        val filtered = _clans.value
        appScope.launch(ioDispatcher) {
            persistenceMutex.withLock { clanDao.delete(clanId) }
        }
        userClanController.clearClanMembersCache(clanId)
        channelController.purgeClanChannelsCache(clanId)
        channelAppController.purgeAppsForClan(clanId)
        roleController.forgetClanRoles(clanId)
        if (_selectedClanId.value == clanId) {
            val next = filtered.firstOrNull()?.clanId ?: 0L
            if (next != 0L) {
                selectClan(next, force = true)
            } else {
                _selectedClanId.value = 0L
                appScope.launch {
                    withContext(ioDispatcher) {
                        sessionManager.saveLastClanId(0L)
                    }
                }
                notificationCenter.postNotificationOnMainThread(NotificationCenter.selectedClanChanged, 0L)
            }
        }
        notificationCenter.postNotificationOnMainThread(NotificationCenter.clansDidLoad)
    }

    fun reconcileClanBadgeFromChannels(clanId: Long) {
        if (clanId == 0L) return
        if (!channelController.hasLoadedBadgeSnapshot(clanId)) {
            return
        }
        val channels = channelController.getChannels(clanId)
        val pendingCount = channelController.getPendingMentionCount(clanId)
        val total = channels.sumOf { it.unreadCount.coerceAtLeast(0) } + pendingCount
        val anyUnread = pendingCount > 0 || channels.any { it.hasUnread }
        updateClanBadge(clanId) { it.copy(badgeCount = total, hasUnread = anyUnread) }
    }

    fun applyBadgeRead(clanId: Long) {
        updateClanBadge(clanId) { it.copy(badgeCount = 0, hasUnread = false) }
    }

    fun setHasUnread(clanId: Long) {
        updateClanBadge(clanId) { it.copy(hasUnread = true) }
    }

    fun updateClanBadgeCount(clanId: Long, delta: Int) {
        updateClanBadge(clanId) { it.copy(badgeCount = (it.badgeCount + delta).coerceAtLeast(0)) }
    }

    private fun updateClanBadge(clanId: Long, transform: (ClanEntity) -> ClanEntity) {
        val changed = updateClanState { clans ->
            clans.map { if (it.clanId == clanId) transform(it) else it }
        }
        if (!changed) return
        appScope.launch { persistCurrentClans(listOf(clanId)) }
        notificationCenter.postNotificationOnMainThread(
            NotificationCenter.updateInterfaces, NotificationCenter.UPDATE_MASK_BADGE
        )
    }

    fun updateClanLogo(
        clanId: Long,
        logoUrl: String,
        onResult: (success: Boolean, message: String?) -> Unit,
    ) {
        if (clanId == 0L) {
            appScope.launch(Dispatchers.Main.immediate) { onResult(false, null) }
            return
        }
        appScope.launch {
            val snapshot = _clans.value.firstOrNull { it.clanId == clanId }
            if (snapshot == null) {
                withContext(Dispatchers.Main.immediate) {
                    onResult(false, appContext.getString(R.string.clan_settings_logo_update_failed))
                }
                return@launch
            }
            _clanLogoUpdateInFlight.update { it + clanId }
            try {
                sessionManager.withAutoRefresh { session ->
                    withContext(ioDispatcher) {
                        if (logoUrl.isBlank()) {
                            api.updateClanDesc(
                                session.apiUrl,
                                session.token,
                                clanId,
                                clanName = snapshot.clanName,
                                banner = snapshot.banner,
                                logo = null,
                                clearLogo = true,
                                welcomeChannelId = snapshot.welcomeChannelId.takeIf { it != 0L },
                                isOnboarding = snapshot.isOnboarding,
                                preventAnonymous = snapshot.preventAnonymous,
                                isCommunity = snapshot.isCommunity,
                            )
                        } else {
                            api.updateClanDesc(
                                session.apiUrl,
                                session.token,
                                clanId,
                                clanName = snapshot.clanName,
                                banner = snapshot.banner,
                                logo = logoUrl,
                                welcomeChannelId = snapshot.welcomeChannelId.takeIf { it != 0L },
                                isOnboarding = snapshot.isOnboarding,
                                preventAnonymous = snapshot.preventAnonymous,
                                isCommunity = snapshot.isCommunity,
                            )
                        }
                    }
                }
                if (_clans.value.none { it.clanId == clanId }) {
                    withContext(Dispatchers.Main.immediate) {
                        onResult(false, appContext.getString(R.string.clan_settings_logo_update_failed))
                    }
                    return@launch
                }
                val clearedLogo = logoUrl.isBlank()
                updateClanMetadata { clans ->
                    clans.map { existing ->
                        if (existing.clanId != clanId) existing else existing.copy(
                            logo = if (clearedLogo) "" else logoUrl,
                        )
                    }
                }
                persistCurrentClans(listOf(clanId))
                cacheTracker.invalidate(apiCacheKey("listClanDescs"))
                notificationCenter.postNotificationOnMainThread(NotificationCenter.clanInfoUpdated, clanId)
                withContext(Dispatchers.Main.immediate) { onResult(true, null) }
            } catch (e: Exception) {
                Log.e(TAG, "updateClanLogo($clanId) failed", e)
                val msg = e.message?.takeIf { it.isNotBlank() }
                    ?: appContext.getString(R.string.clan_settings_logo_update_failed)
                withContext(Dispatchers.Main.immediate) { onResult(false, msg) }
            } finally {
                _clanLogoUpdateInFlight.update { it - clanId }
            }
        }
    }

    fun mergeCommunityFlag(clanId: Long, enabled: Boolean) {
        updateClanMetadata { clans ->
            clans.map { if (it.clanId == clanId) it.copy(isCommunity = enabled) else it }
        }
        appScope.launch { persistCurrentClans(listOf(clanId)) }
        notificationCenter.postNotificationOnMainThread(NotificationCenter.clanInfoUpdated, clanId)
    }

    private fun preWarmClanLogos(clans: List<ClanEntity>) {
        val loader = MezonImageLoader.getInstance(appContext)
        val sizePx = CLAN_ICON_SIZE_PX
        for (clan in clans) {
            if (clan.logo.isEmpty()) continue
            val url = avatarImgproxyUrl(clan.logo, sizePx)
            loader.load(url, sizePx, sizePx, onSuccess = {})
        }
    }

    suspend fun fetchClanWebhooks(clanId: Long): ListClanWebhookResponse {
        return sessionManager.withAutoRefresh { session ->
            withContext(ioDispatcher) {
                api.listClanWebhooks(session.apiUrl, session.token, clanId)
            }
        }
    }

    suspend fun fetchChannelWebhooksForClan(clanId: Long): WebhookListResponse {
        return sessionManager.withAutoRefresh { session ->
            withContext(ioDispatcher) {
                api.listWebhooksByChannelId(session.apiUrl, session.token, 0L, clanId)
            }
        }
    }

    suspend fun fetchChannelWebhooks(channelId: Long, clanId: Long): WebhookListResponse {
        return sessionManager.withAutoRefresh { session ->
            withContext(ioDispatcher) {
                api.listWebhooksByChannelId(session.apiUrl, session.token, channelId, clanId)
            }
        }
    }

    suspend fun generateChannelWebhook(
        webhookName: String,
        channelId: Long,
        clanId: Long,
        avatar: String,
    ): WebhookGenerateResponse {
        return sessionManager.withAutoRefresh { session ->
            withContext(ioDispatcher) {
                api.generateWebhook(session.apiUrl, session.token, webhookName, channelId, clanId, avatar)
            }
        }
    }

    suspend fun generateClanWebhook(
        clanId: Long,
        webhookName: String,
        avatar: String,
    ): GenerateClanWebhookResponse {
        return sessionManager.withAutoRefresh { session ->
            withContext(ioDispatcher) {
                api.generateClanWebhook(session.apiUrl, session.token, clanId, webhookName, avatar)
            }
        }
    }

    suspend fun updateChannelWebhookById(
        webhookId: Long,
        webhookName: String,
        avatarUrl: String,
        channelIdExisting: Long,
        newChannelId: Long,
        clanId: Long,
    ) {
        sessionManager.withAutoRefresh { session ->
            withContext(ioDispatcher) {
                api.updateWebhookById(
                    session.apiUrl,
                    session.token,
                    webhookId,
                    webhookName,
                    avatarUrl,
                    channelIdExisting = channelIdExisting,
                    newChannelId = newChannelId,
                    clanId = clanId,
                )
            }
        }
    }

    suspend fun updateClanWebhookById(
        webhookId: Long,
        clanId: Long,
        webhookName: String,
        avatar: String,
        resetToken: Boolean,
    ) {
        sessionManager.withAutoRefresh { session ->
            withContext(ioDispatcher) {
                api.updateClanWebhookById(
                    session.apiUrl,
                    session.token,
                    webhookId,
                    clanId,
                    webhookName,
                    avatar,
                    resetToken = resetToken,
                )
            }
        }
    }

    suspend fun deleteChannelWebhook(webhookId: Long, clanId: Long, hookChannelId: Long) {
        sessionManager.withAutoRefresh { session ->
            withContext(ioDispatcher) {
                api.deleteWebhookById(session.apiUrl, session.token, webhookId, clanId, hookChannelId)
            }
        }
    }

    suspend fun deleteClanWebhook(webhookId: Long, clanId: Long) {
        sessionManager.withAutoRefresh { session ->
            withContext(ioDispatcher) {
                api.deleteClanWebhookById(session.apiUrl, session.token, webhookId, clanId)
            }
        }
    }

    suspend fun clanWebhookPublicUrl(webhookId: Long, clanId: Long): String? {
        return sessionManager.withAutoRefresh { session ->
            withContext(ioDispatcher) {
                api.listClanWebhooks(session.apiUrl, session.token, clanId)
                    .listClanWebhooksList.firstOrNull { it.id == webhookId }?.url
            }
        }
    }

    suspend fun uploadWebhookAvatar(bytes: ByteArray, mimeType: String): String {
        return sessionManager.withAutoRefresh { session ->
            withContext(ioDispatcher) {
                val ts = System.currentTimeMillis() / 1000
                val name = "${ts}_wh.jpg"
                AttachmentUploader.uploadAttachmentBytes(
                    api,
                    session.apiUrl,
                    session.token,
                    name,
                    mimeType,
                    bytes,
                    512,
                    512,
                    BuildConfig.CLOUDFLARE_CDN_URL,
                ).cdnUrl
            }
        }
    }

    suspend fun uploadRoleIconImage(bytes: ByteArray, mimeType: String): String {
        require(bytes.isNotEmpty())
        return sessionManager.withAutoRefresh { session ->
            withContext(ioDispatcher) {
                val ts = System.currentTimeMillis() / 1000
                val ext = when {
                    mimeType.contains("png", ignoreCase = true) -> "png"
                    mimeType.contains("webp", ignoreCase = true) -> "webp"
                    else -> "jpg"
                }
                val name = "${ts}_role.$ext"
                AttachmentUploader.uploadAttachmentBytes(
                    api,
                    session.apiUrl,
                    session.token,
                    name,
                    mimeType,
                    bytes,
                    512,
                    512,
                    BuildConfig.CLOUDFLARE_CDN_URL,
                ).cdnUrl
            }
        }
    }

    private fun refreshClanPolicies(clanId: Long) {
        appScope.launch {
            try {
                while (_clans.value.any { it.clanId == clanId }) {
                    val revisionAtRequest = currentMetadataRevision()
                    val desc = sessionManager.withAutoRefresh { session ->
                        withContext(ioDispatcher) {
                            api.listClanDescs(session.apiUrl, session.token).clandescList
                                .firstOrNull { it.clanId == clanId }
                        }
                    } ?: return@launch
                    val applied = updateClanMetadataIfCurrent(revisionAtRequest) { clans ->
                        clans.map { clan ->
                            if (clan.clanId != clanId) clan else clan.copy(
                                preventAnonymous = desc.preventAnonymous,
                                isCommunity = desc.isCommunity,
                            )
                        }
                    }
                    if (!applied) continue
                    persistCurrentClans(listOf(clanId))
                    cacheTracker.invalidate(apiCacheKey("listClanDescs"))
                    notificationCenter.postNotificationOnMainThread(NotificationCenter.clanInfoUpdated, clanId)
                    return@launch
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "refreshClanPolicies($clanId) failed", e)
            }
        }
    }

    private fun observeSocketEvents() {
        appScope.launch {
            dispatcher.allowAnonymousEvents.collect { event ->
                if (event.clanId == 0L) return@collect
                val changed = updateClanMetadata { clans ->
                    clans.map { clan ->
                        if (clan.clanId == event.clanId) clan.copy(preventAnonymous = !event.allow) else clan
                    }
                }
                if (!changed) return@collect
                persistCurrentClans(listOf(event.clanId))
                notificationCenter.postNotificationOnMainThread(NotificationCenter.clanInfoUpdated, event.clanId)
            }
        }
        appScope.launch {
            dispatcher.userClanRemovedEvents.collect { event ->
                val uid = userController.userId
                if (uid == 0L) return@collect
                if (!event.userIdsList.contains(uid)) return@collect
                if (_clans.value.none { it.clanId == event.clanId }) return@collect
                withContext(Dispatchers.Main.immediate) {
                    applyClanLeftLocally(event.clanId)
                }
            }
        }
        appScope.launch {
            dispatcher.clanDeletedEvents.collect { event ->
                if (_clans.value.none { it.clanId == event.clanId }) return@collect
                withContext(Dispatchers.Main.immediate) {
                    applyClanLeftLocally(event.clanId)
                }
            }
        }
        appScope.launch {
            dispatcher.clanUpdatedEvents.collect { event ->
                val existing = _clans.value.find { it.clanId == event.clanId } ?: return@collect
                updateClanMetadata { clans ->
                    clans.map { if (it.clanId == event.clanId) event.mergeOnto(it) else it }
                }
                appScope.launch { persistCurrentClans(listOf(event.clanId)) }
                val updated = _clans.value.find { it.clanId == event.clanId } ?: return@collect
                if (event.needsPolicyRefresh(updated)) refreshClanPolicies(event.clanId)
                notificationCenter.postNotificationOnMainThread(NotificationCenter.clanInfoUpdated, event.clanId)
                var mask = 0
                if (event.clanName.isNotEmpty() && event.clanName != existing.clanName) {
                    mask = mask or NotificationCenter.UPDATE_MASK_CHAT_NAME
                }
                if (event.logo.isNotEmpty() && event.logo != existing.logo) {
                    mask = mask or NotificationCenter.UPDATE_MASK_CHAT_AVATAR
                }
                if (event.banner != existing.banner) {
                    mask = mask or NotificationCenter.UPDATE_MASK_CLAN_BANNER
                }
                if (mask != 0) {
                    notificationCenter.postNotificationOnMainThread(NotificationCenter.updateInterfaces, mask)
                }
            }
        }
    }
}

data class ChannelSection(
    val categoryId: Long,
    val categoryName: String,
    val channels: List<ClanChannelEntity>
)
