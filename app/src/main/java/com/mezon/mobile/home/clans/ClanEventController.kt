package com.mezon.mobile.home.clans

import android.util.Log
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.network.ApiCacheTracker
import com.mezon.mobile.network.CHANNEL_TYPE_CHANNEL
import com.mezon.mobile.network.CHANNEL_TYPE_THREAD
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.home.profile.AccountController
import com.mezon.mobile.network.apiCacheKey
import com.mezon.mobile.session.SessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
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
    private val socketEventDispatcher: com.mezon.mobile.network.SocketEventDispatcher,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @param:ApplicationScope private val appScope: CoroutineScope,
) {
    private val eventsByClan = ConcurrentHashMap<Long, ArrayList<ClanEventEntity>>()
    private val loadErrorsByClan = ConcurrentHashMap<Long, String>()
    private val loadingClanIds = ConcurrentHashMap.newKeySet<Long>()
    private val cacheLock = Any()
    private val socketVersionsByClan = HashMap<Long, Long>()
    private val loadJobsLock = Any()
    private val loadJobsByClan = HashMap<Long, Job>()

    init {
        appScope.launch { observeClanEventCreated() }
    }

    private suspend fun observeClanEventCreated() {
        socketEventDispatcher.clanEventCreated.collect {
            val clanId = it.clanId
            if (clanId != 0L) {
                val statusApplied = applyEventStatusUpdate(
                    clanId = clanId,
                    eventId = it.eventId,
                    eventStatus = it.eventStatus,
                    startTimeSeconds = it.startTimeSeconds,
                    action = it.action,
                )
                if (!statusApplied || it.eventStatus == ClanEventStatus.COMPLETED) {
                    loadEvents(clanId, force = true)
                }
            }
        }
    }

    fun getEvents(clanId: Long): List<ClanEventEntity> = synchronized(cacheLock) {
        eventsByClan[clanId]?.toList().orEmpty()
    }

    fun getEvent(clanId: Long, eventId: Long): ClanEventEntity? = synchronized(cacheLock) {
        eventsByClan[clanId]?.firstOrNull { it.id == eventId }
    }

    fun getChannelEventStatuses(clanId: Long): Map<Long, Int> = synchronized(cacheLock) {
        channelEventStatuses(eventsByClan[clanId].orEmpty())
    }

    fun getLoadError(clanId: Long): String? = loadErrorsByClan[clanId]

    fun getChannel(clanId: Long, channelId: Long): ClanChannelEntity? {
        if (channelId == 0L) return null
        return channelController.getChannels(clanId).firstOrNull { it.channelId == channelId }
    }

    fun isLoading(clanId: Long): Boolean = loadingClanIds.contains(clanId)

    private fun applyEventStatusUpdate(
        clanId: Long,
        eventId: Long,
        eventStatus: Int,
        startTimeSeconds: Int,
        action: Int,
    ): Boolean {
        if (action != EVENT_STATUS_UPDATE_ACTION ||
            (eventStatus != ClanEventStatus.UPCOMING &&
                eventStatus != ClanEventStatus.ONGOING &&
                eventStatus != ClanEventStatus.COMPLETED)
        ) {
            return false
        }
        var statusChanged = false
        val eventFound = synchronized(cacheLock) {
            val events = eventsByClan[clanId]
            if (events == null) {
                markSocketUpdateLocked(clanId)
                return@synchronized false
            }
            val update = updateClanEventStatus(
                events = events,
                eventId = eventId,
                eventStatus = eventStatus,
                startTimeSeconds = startTimeSeconds,
            )
            if (!update.found) {
                markSocketUpdateLocked(clanId)
                return@synchronized false
            }

            markSocketUpdateLocked(clanId)
            if (update.changed) {
                statusChanged = true
            }
            true
        }
        if (statusChanged) {
            notificationCenter.postNotificationOnMainThread(
                NotificationCenter.clanEventsDidLoad,
                clanId,
            )
        }
        return eventFound
    }

    fun visibleEvents(clanId: Long, currentUserId: Long): List<ClanEventEntity> {
        val textChannelIds = channelController.getChannels(clanId)
            .filter { it.type == CHANNEL_TYPE_CHANNEL || it.type == CHANNEL_TYPE_THREAD }
            .map { it.channelId }
            .toSet()
        return getEvents(clanId).filter { event ->
            (!event.isPrivate || event.creatorId == currentUserId) &&
                (event.channelId == 0L || textChannelIds.contains(event.channelId))
        }
    }

    fun loadEvents(clanId: Long, force: Boolean = false) {
        if (clanId == 0L) return
        val cacheKey = apiCacheKey("listEvents", clanId)
        if (!force && apiCacheTracker.shouldCall(cacheKey) == ApiCacheTracker.ShouldCall.SKIP) {
            val hasCachedResult = synchronized(cacheLock) { eventsByClan.containsKey(clanId) }
            if (hasCachedResult) {
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.clanEventsDidLoad,
                    clanId,
                )
                return
            }
        }

        lateinit var loadJob: Job
        synchronized(loadJobsLock) {
            val previousJob = loadJobsByClan[clanId]
            if (!force && previousJob?.isActive == true) return
            previousJob?.cancel()
            val socketVersionAtStart = synchronized(cacheLock) {
                socketVersionsByClan[clanId] ?: 0L
            }
            loadJob = appScope.launch(ioDispatcher, start = CoroutineStart.LAZY) {
                performLoadEvents(clanId, cacheKey, socketVersionAtStart)
            }
            loadJobsByClan[clanId] = loadJob
            loadingClanIds.add(clanId)
        }
        loadJob.start()
    }

    private suspend fun performLoadEvents(clanId: Long, cacheKey: String, socketVersionAtStart: Long) {
        val currentJob = currentCoroutineContext().job
        var expectedSocketVersion = socketVersionAtStart
        notificationCenter.postNotificationOnMainThread(
            NotificationCenter.clanEventsDidLoad,
            clanId,
        )
        try {
            while (true) {
                val list = sessionManager.withAutoRefresh { session ->
                    api.listEvents(session.apiUrl, session.token, clanId)
                }
                val mapped = ArrayList(list.map { it.toClanEventEntity() })
                var retryForSocketUpdate = false
                val applied = synchronized(loadJobsLock) {
                    if (loadJobsByClan[clanId] !== currentJob) {
                        false
                    } else {
                        synchronized(cacheLock) {
                            val currentSocketVersion = socketVersionsByClan[clanId] ?: 0L
                            if (currentSocketVersion != expectedSocketVersion) {
                                expectedSocketVersion = currentSocketVersion
                                retryForSocketUpdate = true
                                false
                            } else {
                                eventsByClan[clanId] = mapped
                                true
                            }
                        }
                    }
                }
                if (applied) {
                    loadErrorsByClan.remove(clanId)
                    apiCacheTracker.markCalled(cacheKey)
                }
                if (!retryForSocketUpdate) {
                    break
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val isLatestLoad = synchronized(loadJobsLock) {
                loadJobsByClan[clanId] === currentJob
            }
            if (isLatestLoad) {
                Log.w(TAG, "loadEvents failed clanId=$clanId", e)
                loadErrorsByClan[clanId] = e.message?.takeIf { it.isNotBlank() }
                    ?: "Failed to load events"
            }
        } finally {
            val finishedLatestLoad = synchronized(loadJobsLock) {
                if (loadJobsByClan[clanId] === currentJob) {
                    loadJobsByClan.remove(clanId)
                    loadingClanIds.remove(clanId)
                    true
                } else {
                    false
                }
            }
            if (finishedLatestLoad) {
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.clanEventsDidLoad,
                    clanId,
                )
            }
        }
    }

    private fun markSocketUpdateLocked(clanId: Long) {
        socketVersionsByClan[clanId] = (socketVersionsByClan[clanId] ?: 0L) + 1L
    }

    fun createEvent(
        draft: CreateEventDraft,
        clanId: Long,
        creatorId: Long,
        onDone: (success: Boolean, error: String?) -> Unit,
    ) {
        appScope.launch(ioDispatcher) {
            try {
                sessionManager.withAutoRefresh { session ->
                    api.createEvent(
                        session.apiUrl,
                        session.token,
                        clanId = clanId,
                        title = draft.title.trim(),
                        description = draft.description.trim(),
                        logo = draft.logoUrl,
                        channelVoiceId = draft.channelVoiceId,
                        channelId = draft.channelId,
                        address = draft.address.trim(),
                        startTimeSeconds = draft.startTimeSeconds,
                        endTimeSeconds = draft.endTimeSeconds,
                        repeatType = draft.repeatType,
                        isPrivate = draft.isPrivate,
                        creatorId = creatorId,
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
        creatorId: Long,
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
                        title = draft.title.trim(),
                        description = draft.description.trim(),
                        logo = resolveEventLogoUpdate(draft),
                        channelVoiceId = draft.channelVoiceId,
                        channelId = draft.channelId,
                        channelIdOld = draft.editingChannelIdOld,
                        address = draft.address.trim(),
                        startTimeSeconds = draft.startTimeSeconds,
                        endTimeSeconds = draft.endTimeSeconds,
                        repeatType = draft.repeatType,
                        creatorId = creatorId,
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
                        val userId = accountController.accountInfo.value.userId
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

    suspend fun uploadEventCoverJpeg(jpegBytes: ByteArray): String {
        require(jpegBytes.isNotEmpty())
        if (jpegBytes.size > ClanEventCreateUi.MAX_LOGO_SIZE_BYTES) {
            throw IllegalStateException("File too large")
        }
        return sessionManager.withAutoRefresh { session ->
            withContext(ioDispatcher) {
                val filename = "${System.currentTimeMillis()}_event_cover.jpg"
                com.mezon.mobile.util.AttachmentUploader.uploadAttachmentBytes(
                    api,
                    session.apiUrl,
                    session.token,
                    filename,
                    "image/jpeg",
                    jpegBytes,
                    1280,
                    720,
                    com.mezon.mobile.BuildConfig.MEZON_BASE_IMG_URL,
                ).cdnUrl
            }
        }
    }

    fun textChannels(clanId: Long): List<ClanChannelEntity> =
        channelController.getChannels(clanId).filter {
            it.type == CHANNEL_TYPE_CHANNEL || it.type == CHANNEL_TYPE_THREAD
        }

    private fun resolveEventLogoUpdate(draft: CreateEventDraft): String? {
        val current = draft.logoUrl.trim()
        val original = draft.originalLogoUrl?.trim()
            ?: return current.takeIf { it.isNotEmpty() }
        return when {
            current == original -> original.takeIf { it.isNotEmpty() }
            else -> current
        }
    }

    companion object {
        private const val TAG = "ClanEventController"
        private const val EVENT_STATUS_UPDATE_ACTION = 0
    }
}
