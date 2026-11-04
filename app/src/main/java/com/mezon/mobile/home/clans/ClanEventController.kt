package com.mezon.mobile.home.clans

import android.util.Log
import com.mezon.mezon.api.CreateEventRequest
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
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
    private val pendingSocketEventsByClan = HashMap<Long, MutableList<CreateEventRequest>>()
    private val cacheLock = Any()
    private var sessionJob = SupervisorJob(appScope.coroutineContext[Job])
    private var eventExpiryJob: Job? = null
    private var eventExpiryDeadlineMillis: Long? = null

    init {
        observeClanEventCreated()
    }

    fun cleanup() = synchronized(cacheLock) {
        sessionJob.cancel()
        eventExpiryJob = null
        eventExpiryDeadlineMillis = null
        sessionJob = SupervisorJob(appScope.coroutineContext[Job])
        eventsByClan.clear()
        loadErrorsByClan.clear()
        loadingClanIds.clear()
        pendingForceEventReload.clear()
        pendingSocketEventsByClan.clear()
        apiCacheTracker.invalidateByPrefix("listEvents_")
        observeClanEventCreated()
    }

    private fun launchRequest(block: suspend CoroutineScope.() -> Unit) = synchronized(cacheLock) {
        appScope.launch(ioDispatcher + sessionJob, block = block)
    }

    private fun observeClanEventCreated() {
        val job = sessionJob
        appScope.launch(ioDispatcher + job, start = CoroutineStart.UNDISPATCHED) {
            socketEventDispatcher.clanEventCreated.collect { event ->
                applyClanEventFromSocket(event, job)
            }
        }
    }

    private fun applyClanEventFromSocket(event: CreateEventRequest, job: Job) {
        val clanId = event.clanId
        if (clanId == 0L || event.eventId == 0L) return
        val changed = synchronized(cacheLock) {
            job.ensureActive()
            if (loadingClanIds.contains(clanId)) {
                pendingSocketEventsByClan.getOrPut(clanId) { ArrayList() }.add(event)
            }
            val events = eventsByClan[clanId]
                ?: if (event.action == ClanEventAction.CREATED || event.action == ClanEventAction.UPDATE) {
                    ArrayList<ClanEventEntity>().also { eventsByClan[clanId] = it }
                } else {
                    return@synchronized false
                }
            applySocketEvent(events, event).also { changed ->
                if (changed) scheduleEventExpiryLocked()
            }
        }
        if (changed) {
            notificationCenter.postNotificationOnMainThread(
                NotificationCenter.clanEventsDidLoad,
                clanId,
            )
        }
    }

    private fun applySocketEvent(
        events: ArrayList<ClanEventEntity>,
        update: CreateEventRequest,
    ): Boolean {
        val index = events.indexOfFirst { it.id == update.eventId }
        val existing = events.getOrNull(index)

        if (update.action == ClanEventAction.DELETE) {
            if (existing == null) return false
            events.removeAt(index)
            return true
        }

        if (update.action == ClanEventAction.CREATED) {
            if (existing != null) return false
            events.add(update.toClanEventEntity())
            return true
        }

        if (update.action != ClanEventAction.UPDATE &&
            (update.eventStatus == ClanEventStatus.UPCOMING || update.eventStatus == ClanEventStatus.ONGOING)
        ) {
            if (existing == null) return false
            events[index] = existing.copy(
                eventStatus = update.eventStatus,
                startTimeSeconds = update.startTimeSeconds.takeIf { it != 0 } ?: existing.startTimeSeconds,
                endTimeSeconds = update.endTimeSeconds.takeIf { it != 0 } ?: existing.endTimeSeconds,
            )
            return true
        }

        if (update.eventStatus == ClanEventStatus.COMPLETED &&
            update.repeatType != ClanEventRepeatType.DOES_NOT_REPEAT
        ) {
            if (existing == null) return false
            events[index] = existing.copy(
                eventStatus = ClanEventStatus.CREATED,
                startTimeSeconds = update.startTimeSeconds,
                endTimeSeconds = update.endTimeSeconds,
                repeatType = update.repeatType,
            )
            return true
        }

        if (update.action == ClanEventAction.UPDATE) {
            val updated = update.toClanEventEntity(existing)
            if (existing == null) {
                events.add(updated)
            } else {
                events[index] = updated
            }
            return true
        }

        if (update.eventStatus == ClanEventStatus.COMPLETED &&
            update.repeatType == ClanEventRepeatType.DOES_NOT_REPEAT
        ) {
            if (existing == null) return false
            events.removeAt(index)
            return true
        }

        if (update.action == ClanEventAction.INTERESTED ||
            update.action == ClanEventAction.UNINTERESTED
        ) {
            if (existing == null || update.userId == 0L) return false
            val userIds = existing.userIds.filter { it != 0L }.toMutableList()
            if (update.action == ClanEventAction.INTERESTED) {
                if (update.userId in userIds) return false
                userIds.add(update.userId)
            } else if (!userIds.remove(update.userId)) {
                return false
            }
            events[index] = existing.copy(userIds = userIds)
            return true
        }

        return false
    }

    private fun CreateEventRequest.toClanEventEntity(
        existing: ClanEventEntity? = null,
    ): ClanEventEntity = ClanEventEntity(
        id = eventId,
        title = title,
        logo = logo,
        description = description,
        clanId = clanId,
        channelVoiceId = channelVoiceId,
        channelId = channelId,
        address = address,
        startTimeSeconds = startTimeSeconds,
        endTimeSeconds = endTimeSeconds,
        creatorId = creatorId.takeIf { it != 0L } ?: existing?.creatorId ?: 0L,
        userIds = existing?.userIds ?: listOfNotNull(creatorId.takeIf { it != 0L }),
        maxPermission = existing?.maxPermission ?: 0,
        eventStatus = eventStatus,
        repeatType = repeatType,
        isPrivate = isPrivate,
        externalLink = meetRoom.externalLink.ifBlank { existing?.externalLink.orEmpty() },
    )

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

    fun visibleEvents(clanId: Long): List<ClanEventEntity> {
        val channelIds = channelController.getChannels(clanId).mapTo(HashSet()) { it.channelId }
        val nowSeconds = System.currentTimeMillis() / 1_000
        return getEvents(clanId).filter { event ->
            event.eventStatus != ClanEventStatus.COMPLETED &&
                (event.endTimeSeconds == 0 || event.endTimeSeconds.toLong() > nowSeconds) &&
                (event.channelId == 0L || event.channelId in channelIds)
        }
    }

    fun refreshEventExpiry() = synchronized(cacheLock) {
        eventExpiryJob?.cancel()
        eventExpiryJob = null
        eventExpiryDeadlineMillis = null
        scheduleEventExpiryLocked()
        eventsByClan.keys.forEach { clanId ->
            notificationCenter.postNotificationOnMainThread(NotificationCenter.clanEventsDidLoad, clanId)
        }
    }

    private fun scheduleEventExpiryLocked() {
        val nowMillis = System.currentTimeMillis()
        if (eventExpiryJob?.isActive == true &&
            eventExpiryDeadlineMillis?.let { it <= nowMillis } == true
        ) return
        val deadline = eventsByClan.values.asSequence().flatten()
            .filter { it.eventStatus != ClanEventStatus.COMPLETED }
            .map { it.endTimeSeconds.toLong() * 1_000 }
            .filter { it > nowMillis }
            .minOrNull()
        if (deadline == eventExpiryDeadlineMillis) return
        eventExpiryJob?.cancel()
        eventExpiryJob = null
        eventExpiryDeadlineMillis = deadline
        if (deadline == null) return
        eventExpiryJob = appScope.launch(ioDispatcher + sessionJob) {
            delay((deadline - System.currentTimeMillis()).coerceAtLeast(1L))
            synchronized(cacheLock) {
                ensureActive()
                if (eventExpiryDeadlineMillis != deadline) return@synchronized
                eventExpiryJob = null
                eventExpiryDeadlineMillis = null
                val now = System.currentTimeMillis()
                val expiredClanIds = eventsByClan.filterValues { events ->
                    events.any { event ->
                        event.eventStatus != ClanEventStatus.COMPLETED &&
                            event.endTimeSeconds.toLong() * 1_000 in deadline..now
                    }
                }.keys
                scheduleEventExpiryLocked()
                expiredClanIds.forEach { clanId ->
                    notificationCenter.postNotificationOnMainThread(NotificationCenter.clanEventsDidLoad, clanId)
                }
            }
        }
    }

    fun loadEvents(clanId: Long, force: Boolean = false) {
        if (clanId == 0L) return
        val cacheKey = apiCacheKey("listEvents", clanId)
        launchRequest {
            if (!force && apiCacheTracker.shouldCall(cacheKey) == ApiCacheTracker.ShouldCall.SKIP) {
                val cached = synchronized(cacheLock) { eventsByClan[clanId] }
                if (cached != null) {
                    notificationCenter.postNotificationOnMainThread(
                        NotificationCenter.clanEventsDidLoad,
                        clanId,
                    )
                    return@launchRequest
                }
            }
            synchronized(cacheLock) {
                ensureActive()
                if (!loadingClanIds.add(clanId)) {
                    if (force) pendingForceEventReload.add(clanId)
                    return@launchRequest
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
                    ensureActive()
                    pendingSocketEventsByClan.remove(clanId)?.forEach { event ->
                        applySocketEvent(mapped, event)
                    }
                    eventsByClan[clanId] = mapped
                    scheduleEventExpiryLocked()
                    loadErrorsByClan.remove(clanId)
                    apiCacheTracker.markCalled(cacheKey)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                synchronized(cacheLock) {
                    ensureActive()
                    Log.w(TAG, "loadEvents failed clanId=$clanId", e)
                    loadErrorsByClan[clanId] = e.message?.takeIf { it.isNotBlank() }
                        ?: "Failed to load events"
                }
            } finally {
                synchronized(cacheLock) {
                    if (isActive) {
                        loadingClanIds.remove(clanId)
                        pendingSocketEventsByClan.remove(clanId)
                        val reload = pendingForceEventReload.remove(clanId)
                        notificationCenter.postNotificationOnMainThread(
                            NotificationCenter.clanEventsDidLoad,
                            clanId,
                        )
                        if (reload) loadEvents(clanId, force = true)
                    }
                }
            }
        }
    }

    fun createEvent(
        draft: CreateEventDraft,
        clanId: Long,
        onDone: (success: Boolean, error: String?) -> Unit,
    ) {
        launchRequest {
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
                synchronized(cacheLock) {
                    ensureActive()
                    apiCacheTracker.invalidate(apiCacheKey("listEvents", clanId))
                    loadEvents(clanId, force = true)
                }
                withContext(Dispatchers.Main.immediate) {
                    onDone(true, null)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
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
        launchRequest {
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
                synchronized(cacheLock) {
                    ensureActive()
                    apiCacheTracker.invalidate(apiCacheKey("listEvents", clanId))
                    loadEvents(clanId, force = true)
                }
                withContext(Dispatchers.Main.immediate) {
                    onDone(true, null)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
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
        launchRequest {
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
                    ensureActive()
                    eventsByClan[clanId]?.removeAll { it.id == eventId }
                    scheduleEventExpiryLocked()
                    apiCacheTracker.invalidate(apiCacheKey("listEvents", clanId))
                }
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.clanEventsDidLoad,
                    clanId,
                )
                withContext(Dispatchers.Main.immediate) {
                    onDone(true, null)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
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
        launchRequest {
            try {
                sessionManager.withAutoRefresh { session ->
                    if (interested) {
                        api.addUserEvent(session.apiUrl, session.token, clanId, eventId)
                    } else {
                        api.deleteUserEvent(session.apiUrl, session.token, clanId, eventId)
                    }
                }
                synchronized(cacheLock) {
                    ensureActive()
                    val list = eventsByClan[clanId] ?: return@launchRequest
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
                if (e is CancellationException) throw e
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
        originalFilename: String? = null,
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
                com.mezon.mobile.util.AttachmentUploader.uploadPublicImage(
                    api,
                    session.apiUrl,
                    session.token,
                    filename,
                    mimeType,
                    bytes,
                    originalFilename = originalFilename,
                )
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
