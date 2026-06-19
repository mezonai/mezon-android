package com.mezon.mobile.home.clans

import android.util.Log
import com.mezon.mezon.api.ChannelCanvasDetailResponse
import com.mezon.mezon.api.ChannelCanvasItem
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.network.ApiCacheTracker
import com.mezon.mobile.network.CHANNEL_TYPE_DM
import com.mezon.mobile.network.CHANNEL_TYPE_GROUP
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.network.apiCacheKey
import com.mezon.mobile.session.SessionManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

data class ChannelCanvasData(
    val id: Long,
    val title: String,
    val content: String,
    val isDefault: Boolean,
    val creatorId: Long,
    val createTimeSeconds: Int,
    val updateTimeSeconds: Int
)

private const val TAG = "ChannelCanvasController"
private const val CANVAS_LIST_CACHE_TTL_MS = 5 * 60 * 1_000L
private const val CANVAS_DETAIL_CACHE_TTL_MS = 2 * 60 * 1_000L
private const val CANVAS_LIST_PAGE_SIZE = 50

private data class ChannelCanvasRequestContext(
    val clanId: Long,
    val channelType: Int,
    val apiClanId: Long,
)

private class ChannelCanvasListState {
    val items = ArrayList<ChannelCanvasData>()
    var nextPage = 0
    var hasMore = true
    var initialLoading = false
    var pagingLoading = false
    var initialLoadFinished = false
}

@Singleton
class ChannelCanvasController @Inject constructor(
    private val api: MezonApi,
    private val sessionManager: SessionManager,
    private val notificationCenter: NotificationCenter,
    private val apiCacheTracker: ApiCacheTracker,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope
) {

    private val stateByChannel = HashMap<Long, ChannelCanvasListState>()
    private val requestContextByChannel = HashMap<Long, ChannelCanvasRequestContext>()
    private val canvasesRevisionByChannel = HashMap<Long, Int>()
    private val detailByKey = HashMap<String, ChannelCanvasData>()
    private val loadFailed = Collections.synchronizedSet(HashSet<Long>())
    private val loadingChannels = Collections.synchronizedSet(HashSet<Long>())
    private val loadingDetails = Collections.synchronizedSet(HashSet<String>())
    private val mutexByChannel = HashMap<Long, Mutex>()

    fun getCanvases(channelId: Long): List<ChannelCanvasData> {
        synchronized(stateByChannel) {
            return stateByChannel[channelId]?.items?.toList() ?: emptyList()
        }
    }

    fun getCanvasesRevision(channelId: Long): Int {
        synchronized(this) {
            return canvasesRevisionByChannel[channelId] ?: 0
        }
    }

    fun getCanvasDetail(channelId: Long, canvasId: Long): ChannelCanvasData? {
        synchronized(this) {
            return detailByKey[detailKey(channelId, canvasId)]
        }
    }

    fun hasMoreCanvases(channelId: Long): Boolean {
        synchronized(stateByChannel) {
            return stateByChannel[channelId]?.hasMore != false
        }
    }

    fun isInitialLoading(channelId: Long): Boolean {
        synchronized(stateByChannel) {
            return stateByChannel[channelId]?.initialLoading == true
        }
    }

    fun isPagingLoading(channelId: Long): Boolean {
        synchronized(stateByChannel) {
            return stateByChannel[channelId]?.pagingLoading == true
        }
    }

    fun isInitialLoadFinished(channelId: Long): Boolean {
        synchronized(stateByChannel) {
            return stateByChannel[channelId]?.initialLoadFinished == true
        }
    }

    fun isFetching(channelId: Long): Boolean = synchronized(loadingChannels) { channelId in loadingChannels }

    fun isFetchingDetail(channelId: Long, canvasId: Long): Boolean {
        synchronized(loadingDetails) { return detailKey(channelId, canvasId) in loadingDetails }
    }

    fun hasLoadFailed(channelId: Long): Boolean = synchronized(loadFailed) { channelId in loadFailed }

    fun loadChannelCanvases(
        channelId: Long,
        clanId: Long,
        channelType: Int,
        forceRefresh: Boolean = false
    ) {
        val apiClanId = resolveApiClanId(clanId, channelType)
        val cacheKey = apiCacheKey("channelCanvases", channelId, apiClanId)
        if (!forceRefresh &&
            apiCacheTracker.shouldCall(cacheKey, ttlMs = CANVAS_LIST_CACHE_TTL_MS) == ApiCacheTracker.ShouldCall.SKIP &&
            isInitialLoadFinished(channelId)
        ) {
            notificationCenter.postNotificationOnMainThread(NotificationCenter.channelCanvasesDidLoad, channelId)
            return
        }
        synchronized(loadingChannels) {
            if (!loadingChannels.add(channelId)) return
        }
        synchronized(requestContextByChannel) {
            requestContextByChannel[channelId] = ChannelCanvasRequestContext(clanId, channelType, apiClanId)
        }
        if (forceRefresh) {
            apiCacheTracker.invalidate(cacheKey)
            resetListState(channelId)
        } else if (!isInitialLoadFinished(channelId)) {
            synchronized(stateByChannel) {
                stateByChannel.getOrPut(channelId) { ChannelCanvasListState() }
            }
        } else {
            resetListState(channelId)
        }
        appScope.launch {
            try {
                mutexFor(channelId).withLock {
                    loadListPage(channelId, isInitial = true)
                }
            } finally {
                synchronized(loadingChannels) { loadingChannels.remove(channelId) }
            }
        }
    }

    fun loadMoreChannelCanvases(channelId: Long) {
        appScope.launch {
            mutexFor(channelId).withLock {
                val state = synchronized(stateByChannel) { stateByChannel[channelId] } ?: return@withLock
                if (!state.initialLoadFinished || state.pagingLoading || !state.hasMore || state.initialLoading) {
                    return@withLock
                }
                if (state.items.isEmpty()) return@withLock
                loadListPage(channelId, isInitial = false)
            }
        }
    }

    fun loadCanvasDetail(
        channelId: Long,
        clanId: Long,
        channelType: Int,
        canvasId: Long,
        forceRefresh: Boolean = false
    ) {
        val apiClanId = resolveApiClanId(clanId, channelType)
        val key = detailKey(channelId, canvasId)
        val cacheKey = apiCacheKey("channelCanvasDetail", channelId, canvasId, apiClanId)
        if (!forceRefresh &&
            apiCacheTracker.shouldCall(cacheKey, ttlMs = CANVAS_DETAIL_CACHE_TTL_MS) == ApiCacheTracker.ShouldCall.SKIP &&
            synchronized(this) { detailByKey.containsKey(key) }
        ) {
            notificationCenter.postNotificationOnMainThread(
                NotificationCenter.channelCanvasDetailDidLoad, channelId, canvasId
            )
            return
        }
        synchronized(loadingDetails) {
            if (!loadingDetails.add(key)) return
        }
        appScope.launch {
            try {
                sessionManager.withAutoRefresh { session ->
                    val response = withContext(ioDispatcher) {
                        api.getChannelCanvasDetail(session.apiUrl, session.token, canvasId, apiClanId, channelId)
                    }
                    val data = response.toChannelCanvasData(channelId)
                    synchronized(this@ChannelCanvasController) {
                        detailByKey[key] = data
                        val list = synchronized(stateByChannel) { stateByChannel[channelId]?.items }
                        if (list != null) {
                            val idx = list.indexOfFirst { it.id == canvasId }
                            if (idx >= 0) {
                                val existing = list[idx]
                                list[idx] = existing.copy(
                                    title = data.title,
                                    isDefault = data.isDefault,
                                    creatorId = data.creatorId,
                                    updateTimeSeconds = data.updateTimeSeconds,
                                )
                                bumpCanvasesRevision(channelId)
                            } else {
                                list.add(data.copy(content = ""))
                                bumpCanvasesRevision(channelId)
                            }
                        }
                    }
                    apiCacheTracker.markCalled(cacheKey, ttlMs = CANVAS_DETAIL_CACHE_TTL_MS)
                    notificationCenter.postNotificationOnMainThread(
                        NotificationCenter.channelCanvasDetailDidLoad, channelId, canvasId
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "getChannelCanvasDetail failed channel=$channelId canvas=$canvasId", e)
                apiCacheTracker.invalidate(cacheKey)
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.channelCanvasDetailLoadError, channelId, canvasId
                )
            } finally {
                synchronized(loadingDetails) { loadingDetails.remove(key) }
            }
        }
    }

    private fun resetListState(channelId: Long) {
        synchronized(stateByChannel) {
            val state = stateByChannel[channelId] ?: ChannelCanvasListState()
            state.items.clear()
            state.nextPage = 0
            state.hasMore = true
            state.initialLoadFinished = false
            state.initialLoading = false
            state.pagingLoading = false
            stateByChannel[channelId] = state
        }
    }

    private fun mutexFor(channelId: Long): Mutex {
        synchronized(mutexByChannel) {
            return mutexByChannel.getOrPut(channelId) { Mutex() }
        }
    }

    private suspend fun loadListPage(channelId: Long, isInitial: Boolean) {
        val context = synchronized(requestContextByChannel) { requestContextByChannel[channelId] } ?: return
        val state = synchronized(stateByChannel) {
            stateByChannel.getOrPut(channelId) { ChannelCanvasListState() }
        }
        val page = synchronized(state) {
            when {
                isInitial -> {
                    if (state.initialLoading) return
                    state.initialLoading = true
                    0
                }
                else -> {
                    if (state.pagingLoading) return
                    state.pagingLoading = true
                    state.nextPage
                }
            }
        }
        val cacheKey = apiCacheKey("channelCanvases", channelId, context.apiClanId)
        try {
            sessionManager.withAutoRefresh { session ->
                val response = withContext(ioDispatcher) {
                    api.getChannelCanvasList(
                        apiUrl = session.apiUrl,
                        token = session.token,
                        clanId = context.apiClanId,
                        channelId = channelId,
                        limit = CANVAS_LIST_PAGE_SIZE,
                        page = page,
                    )
                }
                val batch = response.channelCanvasesList.map { it.toChannelCanvasData() }
                synchronized(state) {
                    if (isInitial) {
                        state.items.clear()
                        state.items.addAll(batch)
                        state.initialLoadFinished = true
                        state.nextPage = 1
                        state.hasMore = batch.size >= CANVAS_LIST_PAGE_SIZE
                    } else {
                        val existingIds = state.items.mapTo(HashSet(state.items.size + batch.size)) { it.id }
                        batch.forEach { item ->
                            if (item.id !in existingIds) {
                                state.items.add(item)
                                existingIds.add(item.id)
                            }
                        }
                        state.nextPage = page + 1
                        state.hasMore = batch.size >= CANVAS_LIST_PAGE_SIZE
                    }
                    bumpCanvasesRevision(channelId)
                }
                synchronized(loadFailed) { loadFailed.remove(channelId) }
                if (isInitial) {
                    apiCacheTracker.markCalled(cacheKey, ttlMs = CANVAS_LIST_CACHE_TTL_MS)
                }
                notificationCenter.postNotificationOnMainThread(NotificationCenter.channelCanvasesDidLoad, channelId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "getChannelCanvasList failed channel=$channelId page=$page", e)
            synchronized(state) {
                if (isInitial) {
                    state.initialLoadFinished = true
                    state.hasMore = false
                }
            }
            if (isInitial) {
                synchronized(loadFailed) { loadFailed.add(channelId) }
                apiCacheTracker.invalidate(cacheKey)
                notificationCenter.postNotificationOnMainThread(NotificationCenter.channelCanvasesLoadError, channelId)
            }
        } finally {
            synchronized(state) {
                if (isInitial) state.initialLoading = false else state.pagingLoading = false
            }
        }
    }

    private fun resolveApiClanId(clanId: Long, channelType: Int): Long {
        return if (channelType == CHANNEL_TYPE_DM || channelType == CHANNEL_TYPE_GROUP) 0L else clanId
    }

    private fun detailKey(channelId: Long, canvasId: Long): String = "$channelId:$canvasId"

    private fun bumpCanvasesRevision(channelId: Long) {
        synchronized(this) {
            canvasesRevisionByChannel[channelId] = (canvasesRevisionByChannel[channelId] ?: 0) + 1
        }
    }
}

private fun ChannelCanvasItem.toChannelCanvasData(): ChannelCanvasData = ChannelCanvasData(
    id = id,
    title = title,
    content = "",
    isDefault = isDefault,
    creatorId = creatorId,
    createTimeSeconds = createTimeSeconds,
    updateTimeSeconds = updateTimeSeconds
)

private fun ChannelCanvasDetailResponse.toChannelCanvasData(channelId: Long): ChannelCanvasData = ChannelCanvasData(
    id = id,
    title = title,
    content = content,
    isDefault = isDefault,
    creatorId = creatorId,
    createTimeSeconds = 0,
    updateTimeSeconds = 0
)
