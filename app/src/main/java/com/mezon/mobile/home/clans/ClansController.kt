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
import com.mezon.mezon.api.ClanDesc
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
import com.mezon.mobile.home.BadgeCoordinator
import com.mezon.mobile.home.TopicBadgeTracker
import com.mezon.mobile.home.clans.channelapp.ChannelAppController
import com.mezon.mobile.home.notifications.NotificationStore
import com.mezon.mobile.home.UserClanController
import com.mezon.mobile.home.profile.UserController
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
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope
) {
    private val _clans = MutableStateFlow<List<ClanEntity>>(emptyList())
    val clans: StateFlow<List<ClanEntity>> = _clans.asStateFlow()

    private val _selectedClanId = MutableStateFlow(0L)
    val selectedClanId: StateFlow<Long> = _selectedClanId.asStateFlow()

    private val _clanLogoUpdateInFlight = MutableStateFlow<Set<Long>>(emptySet())
    val clanLogoUpdateInFlight: StateFlow<Set<Long>> = _clanLogoUpdateInFlight.asStateFlow()

    var clansLoaded = false
        private set

    init {
        appScope.launch {
            val cached = withContext(ioDispatcher) { clanDao.getAll() }
            if (cached.isNotEmpty()) {
                Log.d(TAG, "init Room cache (${cached.size} clans): ${cached.map { "${it.clanName}(order=${it.clanOrder})" }}")
                _clans.value = cached
                clansLoaded = true
                preWarmClanLogos(cached)
                notificationCenter.postNotificationOnMainThread(NotificationCenter.clansDidLoad)
                val lastClanId = withContext(ioDispatcher) { sessionManager.getLastClanId() }
                val initialClan = cached.firstOrNull { it.clanId == lastClanId } ?: cached.first()
                selectClan(initialClan.clanId)
                badgeCoordinator.get().processDeferredQueue()
            }
        }
        observeSocketEvents()
    }

    fun cleanup() {
        _clans.value = emptyList()
        _selectedClanId.value = 0L
        clansLoaded = false
        ClanCell.clearAvatarCache()
    }

    fun selectClan(clanId: Long, force: Boolean = false) {
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
                    mezonSocket.joinClanChat(clanId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "joinClanChat($clanId) failed", e)
            }
        }
        notificationCenter.postNotificationOnMainThread(NotificationCenter.selectedClanChanged, clanId)
    }

    fun getClanCount(): Int = _clans.value.size

    suspend fun isDuplicateClanName(clanName: String): Boolean {
        if (!mezonSocket.awaitConnected()) {
            return false
        }
        return runCatching { mezonSocket.checkDuplicateClanName(clanName) }.getOrDefault(false)
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
        val old = _clans.value
        val next = old + createdClan.copy(clanOrder = old.size)
        _clans.value = next
        withContext(ioDispatcher) {
            clanDao.upsert(createdClan.copy(clanOrder = old.size))
        }
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
        appScope.launch {
            try {
                if (!force && cacheTracker.shouldCall(cacheKey) == ApiCacheTracker.ShouldCall.SKIP) {
                    Log.d(TAG, "loadClans: SKIP listClanDescs cache (still may fetch badges)")
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
                Log.d(TAG, "loadClans API result (${apiEntities.size} clans): ${apiEntities.map { "${it.clanName}(order=${it.clanOrder})" }}")

                val existingOrder = _clans.value.mapIndexed { i, c -> c.clanId to i }.toMap()
                val entities = apiEntities
                val sorted = if (existingOrder.isNotEmpty()) {
                    entities.sortedBy { existingOrder[it.clanId] ?: it.clanOrder }
                } else {
                    entities.sortedBy { it.clanOrder }
                }
                _clans.value = sorted
                clansLoaded = true
                cacheTracker.markCalled(cacheKey)
                preWarmClanLogos(sorted)
                withContext(ioDispatcher) { clanDao.upsertAll(sorted) }
                notificationCenter.postNotificationOnMainThread(NotificationCenter.clansDidLoad)
                badgeCoordinator.get().processDeferredQueue()

                val previousSelected = _selectedClanId.value
                if (previousSelected == 0L && sorted.isNotEmpty()) {
                    _selectedClanId.value = sorted.first().clanId
                }
                val sel = _selectedClanId.value
                if (sel != 0L) {
                    channelController.loadChannelsForClanNow(sel, force)
                    channelAppController.loadAppsForClan(sel, force)
                    roleController.loadPermissionCatalogIfNeeded()
                    roleController.loadPermissionsUserForClan(sel, force = previousSelected == 0L)
                    roleController.loadRolesForClan(sel)
                    if (previousSelected == 0L && sorted.isNotEmpty()) {
                        notificationCenter.postNotificationOnMainThread(NotificationCenter.selectedClanChanged, sel)
                        appScope.launch {
                            try {
                                if (mezonSocket.awaitConnected()) {
                                    mezonSocket.joinClanChat(sel)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "joinClanChat($sel) failed", e)
                            }
                        }
                    }
                }

                fetchClanBadgeCountsIfNeeded(force)
            } catch (e: Exception) {
                Log.e(TAG, "loadClans failed", e)
            }
        }
    }

    private suspend fun fetchClanBadgeCountsIfNeeded(force: Boolean) {
        val badgeKey = apiCacheKey("listClanBadgeCount")
        if (!force && cacheTracker.shouldCall(badgeKey) == ApiCacheTracker.ShouldCall.SKIP) {
            return
        }
        runCatching {
            val badgeResponse = sessionManager.withAutoRefresh { session ->
                api.listClanBadgeCount(session.apiUrl, session.token)
            }
            cacheTracker.markCalled(badgeKey)
            val list = badgeResponse.listBadgeList
            applyClanBadgeList(list)
        }.onFailure { Log.e(TAG, "listClanBadgeCount: request failed", it) }
    }

    private fun applyClanBadgeList(badges: List<ClanBadgeCount>) {
        if (badges.isEmpty()) {
            return
        }
        val byClanId = badges.associateBy { it.clanId }
        val list = _clans.value
        var changed = false
        var matched = 0
        val updated = list.map { clan ->
            val b = byClanId[clan.clanId] ?: return@map clan
            matched++
            val newBadge = b.badge.coerceAtLeast(0)
            val newHasUnread = b.hasUnread || newBadge > 0
            if (clan.badgeCount == newBadge && clan.hasUnread == newHasUnread) clan
            else {
                changed = true
                clan.copy(badgeCount = newBadge, hasUnread = newHasUnread)
            }
        }
        if (!changed) return
        _clans.value = updated
        appScope.launch(ioDispatcher) {
            for (c in updated) {
                if (byClanId.containsKey(c.clanId)) clanDao.upsert(c)
            }
        }
        notificationCenter.postNotificationOnMainThread(
            NotificationCenter.updateInterfaces, NotificationCenter.UPDATE_MASK_BADGE
        )
    }

    fun requestMarkAllClanChannelsRead(clanId: Long) {
        if (clanId == 0L) return
        appScope.launch {
            runCatching {
                if (!mezonSocket.awaitConnected()) return@launch
                mezonSocket.markAsRead(channelId = 0L, categoryId = 0L, clanId = clanId)
            }.onFailure { Log.e(TAG, "requestMarkAllClanChannelsRead($clanId) failed", it) }
        }
    }

    fun mergeClanOnboardingFlag(clanId: Long, enabled: Boolean) {
        val list = _clans.value
        val idx = list.indexOfFirst { it.clanId == clanId }
        if (idx < 0) return
        val updated = list[idx].copy(isOnboarding = enabled)
        _clans.value = list.toMutableList().also { it[idx] = updated }
        appScope.launch(ioDispatcher) { clanDao.upsert(updated) }
        notificationCenter.postNotificationOnMainThread(NotificationCenter.clanInfoUpdated, clanId)
    }

    fun mergeClanFromDesc(desc: ClanDesc, trustedBanner: String? = null, knownClanId: Long = 0L) {
        val list = _clans.value
        val mergeKey = when {
            desc.clanId != 0L -> desc.clanId
            knownClanId != 0L -> knownClanId
            else -> return
        }
        val idx = list.indexOfFirst { it.clanId == mergeKey }
        if (idx < 0) return
        var merged = desc.mergeOnto(list[idx])
        if (trustedBanner != null) {
            merged = merged.copy(banner = trustedBanner)
        }
        _clans.value = list.toMutableList().also { it[idx] = merged }
        appScope.launch(ioDispatcher) { clanDao.upsert(merged) }
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
    ): ClanDesc {
        val desc = sessionManager.withAutoRefresh { session ->
            withContext(ioDispatcher) {
                api.updateClanDesc(
                    session.apiUrl,
                    session.token,
                    clanId,
                    clanName = clanName,
                    banner = clanBannerUrl,
                    clearBanner = false,
                    preventAnonymous = preventAnonymous,
                    welcomeChannelId = welcomeChannelId,
                    isOnboarding = isOnboarding,
                )
            }
        }
        if (!invalidateBannerSources.isNullOrEmpty()) {
            invalidateBannerImageCaches(invalidateBannerSources, bannerExtraWidthPx)
        }
        mergeClanFromDesc(desc, trustedBanner = clanBannerUrl, knownClanId = clanId)
        return desc
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
                val presign = api.uploadAttachmentFile(
                    session.apiUrl,
                    session.token,
                    filename,
                    mime,
                    jpegBytes.size,
                    1920,
                    1080,
                )
                api.putFileToPresignedUrl(presign.url, jpegBytes, mime)
                "${BuildConfig.MEZON_BASE_IMG_URL}/${presign.filename}"
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

    private fun applyClanLeftLocally(clanId: Long) {
        cacheTracker.invalidate(apiCacheKey("listClanDescs"))
        cacheTracker.invalidate(apiCacheKey("listClanUsers", clanId.toString()))
        cacheTracker.invalidate(apiCacheKey("listClanBadgeCount"))
        val filtered = _clans.value.filter { it.clanId != clanId }
        _clans.value = filtered
        appScope.launch(ioDispatcher) { clanDao.delete(clanId) }
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
        val channels = channelController.getChannels(clanId)
        val total = channels.sumOf { it.unreadCount.coerceAtLeast(0) }
        val anyUnread = channels.any { it.hasUnread }
        val list = _clans.value
        val idx = list.indexOfFirst { it.clanId == clanId }
        if (idx < 0) return
        val clan = list[idx]
        if (clan.badgeCount == total && clan.hasUnread == anyUnread) return
        val updated = clan.copy(badgeCount = total, hasUnread = anyUnread)
        _clans.value = list.toMutableList().also { it[idx] = updated }
        appScope.launch(ioDispatcher) { clanDao.upsert(updated) }
        notificationCenter.postNotificationOnMainThread(
            NotificationCenter.updateInterfaces, NotificationCenter.UPDATE_MASK_BADGE
        )
    }

    fun setHasUnread(clanId: Long) {
        val list = _clans.value
        val idx = list.indexOfFirst { it.clanId == clanId }
        if (idx < 0) return
        val clan = list[idx]
        if (clan.hasUnread) return
        val updated = clan.copy(hasUnread = true)
        _clans.value = list.toMutableList().also { it[idx] = updated }
        appScope.launch(ioDispatcher) { clanDao.upsert(updated) }
        notificationCenter.postNotificationOnMainThread(
            NotificationCenter.updateInterfaces, NotificationCenter.UPDATE_MASK_BADGE
        )
    }

    fun updateClanBadgeCount(clanId: Long, delta: Int) {
        val list = _clans.value
        val idx = list.indexOfFirst { it.clanId == clanId }
        if (idx < 0) return
        val clan = list[idx]
        val newCount = (clan.badgeCount + delta).coerceAtLeast(0)
        if (newCount == clan.badgeCount) return
        val updated = clan.copy(badgeCount = newCount)
        _clans.value = list.toMutableList().also { it[idx] = updated }
        appScope.launch(ioDispatcher) { clanDao.upsert(updated) }
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
                val desc = sessionManager.withAutoRefresh { session ->
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
                val list = _clans.value
                val idx = list.indexOfFirst { it.clanId == clanId }
                if (idx < 0) {
                    withContext(Dispatchers.Main.immediate) {
                        onResult(false, appContext.getString(R.string.clan_settings_logo_update_failed))
                    }
                    return@launch
                }
                val existing = list[idx]
                val clearedLogo = logoUrl.isBlank()
                val merged = desc.mergeOnto(existing).copy(
                    logo = when {
                        clearedLogo -> ""
                        desc.logo.isNotEmpty() -> desc.logo
                        else -> logoUrl
                    }
                )
                _clans.value = list.toMutableList().also { it[idx] = merged }
                appScope.launch(ioDispatcher) { clanDao.upsert(merged) }
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
        val idx = _clans.value.indexOfFirst { it.clanId == clanId }
        if (idx < 0) return
        val updated = _clans.value[idx].copy(isCommunity = enabled)
        _clans.value = _clans.value.toMutableList().also { it[idx] = updated }
        appScope.launch(ioDispatcher) { clanDao.upsert(updated) }
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
                val presign = api.uploadAttachmentFile(
                    session.apiUrl,
                    session.token,
                    name,
                    mimeType,
                    bytes.size,
                    512,
                    512,
                )
                api.putFileToPresignedUrl(presign.url, bytes, mimeType)
                "${BuildConfig.MEZON_BASE_IMG_URL}/${presign.filename}"
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
                val presign = api.uploadAttachmentFile(
                    session.apiUrl,
                    session.token,
                    name,
                    mimeType,
                    bytes.size,
                    512,
                    512,
                )
                api.putFileToPresignedUrl(presign.url, bytes, mimeType)
                "${BuildConfig.MEZON_BASE_IMG_URL}/${presign.filename}"
            }
        }
    }

    private fun observeSocketEvents() {
        appScope.launch {
            dispatcher.clanUpdatedEvents.collect { event ->
                val existing = _clans.value.find { it.clanId == event.clanId } ?: return@collect
                val updated = existing.copy(
                    clanName = event.clanName.ifEmpty { existing.clanName },
                    logo = event.logo.ifEmpty { existing.logo },
                    banner = event.banner.ifEmpty { existing.banner },
                    isCommunity = event.isCommunity,
                    preventAnonymous = event.preventAnonymous,
                    welcomeChannelId = event.welcomeChannelId.takeIf { it != 0L } ?: existing.welcomeChannelId,
                    isOnboarding = event.isOnboarding,
                )
                _clans.value = _clans.value.map { if (it.clanId == updated.clanId) updated else it }
                appScope.launch(ioDispatcher) { clanDao.upsert(updated) }
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
