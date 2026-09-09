package com.mezon.mobile.home.clans

import android.util.Log
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.home.profile.AccountController
import com.mezon.mobile.home.profile.UserController
import com.mezon.mobile.network.ApiCacheTracker
import com.mezon.mobile.network.CHANNEL_TYPE_CHANNEL
import com.mezon.mobile.network.CHANNEL_TYPE_THREAD
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.network.apiCacheKey
import com.mezon.mobile.session.SessionManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ClanEventController @Inject constructor(
    private val api: MezonApi,
    private val sessionManager: SessionManager,
    private val channelController: ChannelController,
    private val notificationCenter: NotificationCenter,
    private val apiCacheTracker: ApiCacheTracker,
    private val accountController: AccountController,
    private val userController: UserController,
    private val permissionPolicy: PermissionPolicy,
    private val socketEventDispatcher: com.mezon.mobile.network.SocketEventDispatcher,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @param:ApplicationScope private val appScope: CoroutineScope,
) {
    private val eventsByClan = ConcurrentHashMap<Long, ArrayList<ClanEventEntity>>()
    private val loadErrorsByClan = ConcurrentHashMap<Long, String>()
    private val loadingClanIds = ConcurrentHashMap.newKeySet<Long>()
    private val pendingForceEventReload = HashSet<Long>()
    private val cacheLock = Any()

    init {
        appScope.launch { observeClanEventCreated() }
    }

    private suspend fun observeClanEventCreated() {
        socketEventDispatcher.clanEventCreated.collect {
            val clanId = it.clanId
            if (clanId != 0L) {
                loadEvents(clanId, force = true)
            }
        }
    }

    fun getEvents(clanId: Long): List<ClanEventEntity> = synchronized(cacheLock) {
        eventsByClan[clanId]?.toList().orEmpty()
    }

    fun getEvent(clanId: Long, eventId: Long): ClanEventEntity? = synchronized(cacheLock) {
        eventsByClan[clanId]?.firstOrNull { it.id == eventId }
    }

    fun getLoadError(clanId: Long): String? = loadErrorsByClan[clanId]

    fun getChannel(clanId: Long, channelId: Long): ClanChannelEntity? {
        if (channelId == 0L) return null
        return channelController.getChannels(clanId).firstOrNull { it.channelId == channelId }
    }

    fun isLoading(clanId: Long): Boolean = loadingClanIds.contains(clanId)

    fun currentUserId(): Long = accountController.accountInfo.value.userId.takeIf { it != 0L }
        ?: userController.userId

    fun canModifyEvent(event: ClanEventEntity): Boolean {
        val userId = currentUserId()
        return (userId != 0L && userId == event.creatorId) || permissionPolicy.checkAnyPermission(
            listOf(PermissionPolicy.CLAN_OWNER, PermissionPolicy.MANAGE_CLAN, PermissionPolicy.ADMINISTRATOR),
            clanId = event.clanId,
        )
    }

    fun canEndEvent(event: ClanEventEntity): Boolean =
        event.displayStatus() == ClanEventStatus.ONGOING &&
            permissionPolicy.checkPermission(PermissionPolicy.CLAN_OWNER, clanId = event.clanId)

    fun visibleEvents(clanId: Long, currentUserId: Long): List<ClanEventEntity> {
        val textChannelIds = textChannels(clanId).mapTo(HashSet()) { it.channelId }
        return getEvents(clanId).filter { event ->
            (!event.isPrivate || event.creatorId == currentUserId) &&
                (event.channelId == 0L || textChannelIds.contains(event.channelId))
        }
    }

    fun loadEvents(clanId: Long, force: Boolean = false) {
        if (clanId == 0L) return
        val cacheKey = apiCacheKey("listEvents", clanId)
        appScope.launch(ioDispatcher) {
            if (!force && apiCacheTracker.shouldCall(cacheKey) == ApiCacheTracker.ShouldCall.SKIP) {
                val cached = synchronized(cacheLock) { eventsByClan[clanId] }
                if (!cached.isNullOrEmpty()) {
                    notificationCenter.postNotificationOnMainThread(
                        NotificationCenter.clanEventsDidLoad,
                        clanId,
                    )
                    return@launch
                }
            }
            synchronized(cacheLock) {
                if (!loadingClanIds.add(clanId)) {
                    if (force) pendingForceEventReload.add(clanId)
                    return@launch
                }
            }
            notificationCenter.postNotificationOnMainThread(
                NotificationCenter.clanEventsDidLoad,
                clanId,
            )
            try {
                val list = sessionManager.withAutoRefresh { session ->
                    api.listEvents(session.apiUrl, session.token, clanId)
                }
                val mapped = ArrayList(list.map { it.toClanEventEntity() })
                synchronized(cacheLock) {
                    eventsByClan[clanId] = mapped
                }
                loadErrorsByClan.remove(clanId)
                apiCacheTracker.markCalled(cacheKey)
            } catch (e: Exception) {
                Log.w(TAG, "loadEvents failed clanId=$clanId", e)
                loadErrorsByClan[clanId] = e.message?.takeIf { it.isNotBlank() }
                    ?: "Failed to load events"
            } finally {
                val reload = synchronized(cacheLock) {
                    loadingClanIds.remove(clanId)
                    pendingForceEventReload.remove(clanId)
                }
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.clanEventsDidLoad,
                    clanId,
                )
                if (reload) loadEvents(clanId, force = true)
            }
        }
    }

    fun createEvent(
        draft: CreateEventDraft,
        clanId: Long,
        onDone: (success: Boolean, error: String?) -> Unit,
    ) {
        appScope.launch(ioDispatcher) {
            try {
                sessionManager.withAutoRefresh { session ->
                    api.createEvent(
                        session.apiUrl,
                        session.token,
                        clanId = clanId,
                        title = draft.title,
                        description = draft.description,
                        logo = draft.logoUrl,
                        channelVoiceId = draft.channelVoiceId,
                        channelId = draft.channelId,
                        address = draft.address,
                        startTimeSeconds = draft.startTimeSeconds,
                        endTimeSeconds = draft.endTimeSeconds,
                        repeatType = draft.repeatType,
                        isPrivate = draft.isPrivate,
                        creatorId = currentUserId(),
                    )
                }
                apiCacheTracker.invalidate(apiCacheKey("listEvents", clanId))
                loadEvents(clanId, force = true)
                withContext(Dispatchers.Main.immediate) {
                    onDone(true, null)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main.immediate) {
                    onDone(false, e.message)
                }
            }
        }
    }

    fun updateEvent(
        draft: CreateEventDraft,
        clanId: Long,
        original: ClanEventEntity,
        onDone: (success: Boolean, error: String?) -> Unit,
    ) {
        appScope.launch(ioDispatcher) {
            try {
                sessionManager.withAutoRefresh { session ->
                    api.updateEvent(
                        session.apiUrl,
                        session.token,
                        eventId = draft.editingEventId,
                        clanId = clanId,
                        title = draft.title.takeUnless { it == original.title }.orEmpty(),
                        description = draft.description,
                        logo = draft.logoUrl,
                        channelVoiceId = draft.channelVoiceId.takeUnless { it == original.channelVoiceId } ?: 0L,
                        channelId = draft.channelId,
                        channelIdOld = original.channelId,
                        address = draft.address.takeUnless { it == original.address }.orEmpty(),
                        startTimeSeconds = draft.startTimeSeconds.takeUnless { it == original.startTimeSeconds } ?: 0,
                        endTimeSeconds = draft.endTimeSeconds.takeUnless { it == original.endTimeSeconds } ?: 0,
                        repeatType = draft.repeatType.takeUnless { it == original.repeatType } ?: 0,
                        creatorId = original.creatorId,
                    )
                }
                apiCacheTracker.invalidate(apiCacheKey("listEvents", clanId))
                loadEvents(clanId, force = true)
                withContext(Dispatchers.Main.immediate) {
                    onDone(true, null)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main.immediate) {
                    onDone(false, e.message)
                }
            }
        }
    }

    fun deleteEvent(
        clanId: Long,
        eventId: Long,
        creatorId: Long,
        title: String,
        channelId: Long = 0L,
        onDone: (success: Boolean, error: String?) -> Unit,
    ) {
        appScope.launch(ioDispatcher) {
            try {
                sessionManager.withAutoRefresh { session ->
                    api.deleteEvent(
                        session.apiUrl,
                        session.token,
                        eventId = eventId,
                        clanId = clanId,
                        creatorId = creatorId,
                        eventLabel = title,
                        channelId = channelId,
                    )
                }
                synchronized(cacheLock) {
                    eventsByClan[clanId]?.removeAll { it.id == eventId }
                }
                apiCacheTracker.invalidate(apiCacheKey("listEvents", clanId))
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.clanEventsDidLoad,
                    clanId,
                )
                withContext(Dispatchers.Main.immediate) {
                    onDone(true, null)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main.immediate) {
                    onDone(false, e.message)
                }
            }
        }
    }

    fun setInterested(
        clanId: Long,
        eventId: Long,
        interested: Boolean,
        onDone: (success: Boolean, error: String?) -> Unit,
    ) {
        appScope.launch(ioDispatcher) {
            try {
                sessionManager.withAutoRefresh { session ->
                    if (interested) {
                        api.addUserEvent(session.apiUrl, session.token, clanId, eventId)
                    } else {
                        api.deleteUserEvent(session.apiUrl, session.token, clanId, eventId)
                    }
                }
                synchronized(cacheLock) {
                    val list = eventsByClan[clanId] ?: return@launch
                    val idx = list.indexOfFirst { it.id == eventId }
                    if (idx >= 0) {
                        val event = list[idx]
                        val userId = currentUserId()
                        val ids = event.userIds.toMutableList()
                        if (interested) {
                            if (userId != 0L && !ids.contains(userId)) ids.add(userId)
                        } else {
                            ids.remove(userId)
                        }
                        list[idx] = event.copy(userIds = ids)
                    }
                }
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.clanEventsDidLoad,
                    clanId,
                )
                withContext(Dispatchers.Main.immediate) {
                    onDone(true, null)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main.immediate) {
                    onDone(false, e.message)
                }
            }
        }
    }

    fun voiceChannels(clanId: Long): List<ClanChannelEntity> =
        channelController.getChannels(clanId).filter { it.type == CHANNEL_TYPE_VOICE }

    suspend fun uploadEventCover(
        bytes: ByteArray,
        mimeType: String,
        width: Int,
        height: Int,
    ): String {
        require(bytes.isNotEmpty())
        require(mimeType.startsWith("image/"))
        if (bytes.size > ClanEventCreateUi.MAX_LOGO_SIZE_BYTES) {
            throw IllegalStateException("File too large")
        }
        return sessionManager.withAutoRefresh { session ->
            withContext(ioDispatcher) {
                val extension = when (mimeType) {
                    "image/png" -> "png"
                    "image/webp" -> "webp"
                    "image/gif" -> "gif"
                    "image/heic" -> "heic"
                    "image/heif" -> "heif"
                    else -> "jpg"
                }
                val filename = "${System.currentTimeMillis()}_event_cover.$extension"
                com.mezon.mobile.util.AttachmentUploader.uploadAttachmentBytes(
                    api,
                    session.apiUrl,
                    session.token,
                    filename,
                    mimeType,
                    bytes,
                    width,
                    height,
                    com.mezon.mobile.BuildConfig.MEZON_BASE_IMG_URL,
                ).cdnUrl
            }
        }
    }

    fun textChannels(clanId: Long): List<ClanChannelEntity> =
        channelController.getChannels(clanId).filter {
            it.isPrivate && (it.type == CHANNEL_TYPE_CHANNEL || it.type == CHANNEL_TYPE_THREAD)
        }

    companion object {
        private const val TAG = "ClanEventController"
    }
}
