package com.mezon.mobile.home

import android.util.Log
import com.mezon.mobile.BuildConfig
import com.mezon.mezon.api.ChannelAttachment
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.data.db.MessageDao
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.home.chat.AttachmentInfo
import com.mezon.mobile.home.chat.MessageEntity
import com.mezon.mobile.home.chat.isImageAttachmentType
import com.mezon.mobile.home.chat.isVideoAttachmentType
import com.mezon.mobile.network.ApiCacheTracker
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
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ChannelGalleryCtl"
private const val PAGE_LIMIT = 50

data class ChannelGalleryMediaItem(
    val id: Long,
    val url: String,
    val filetype: String,
    val createTimeSeconds: Int,
    val uploaderId: Long,
    val messageId: Long,
    val thumbnail: String = "",
    val filename: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val size: Int = 0,
    val duration: Int = 0
) {
    val isVideo: Boolean get() = isVideoAttachmentType(filetype)
}

enum class ChannelGalleryMediaType(val apiValue: String) {
    IMAGE("image"),
    VIDEO("video")
}

private data class ChannelGalleryKey(
    val channelId: Long,
    val mediaType: ChannelGalleryMediaType
)

private class ChannelGalleryState {
    val items = ArrayList<ChannelGalleryMediaItem>()
    var hasMoreBefore = true
    var initialLoading = false
    var pagingLoading = false
    var initialLoadFinished = false
}

@Singleton
class ChannelGalleryController @Inject constructor(
    private val api: MezonApi,
    private val sessionManager: SessionManager,
    private val notificationCenter: NotificationCenter,
    private val apiCacheTracker: ApiCacheTracker,
    private val messageDao: MessageDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope
) {

    private val stateByGallery = HashMap<ChannelGalleryKey, ChannelGalleryState>()
    private val mutexByGallery = HashMap<ChannelGalleryKey, Mutex>()
    private val loadingGalleries = Collections.synchronizedSet(HashSet<ChannelGalleryKey>())

    private val messageObserver = object : NotificationCenter.NotificationCenterDelegate {
        override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
            when (id) {
                NotificationCenter.didReceiveNewMessages -> {
                    val entity = args.getOrNull(1) as? MessageEntity ?: return
                    tryPrependFromMessage(entity)
                }

                NotificationCenter.messageDidUpdate -> {
                    val entity = args.getOrNull(1) as? MessageEntity ?: return
                    val mask = args.getOrNull(2) as? Int ?: 0
                    if ((mask and NotificationCenter.UPDATE_MASK_ATTACHMENTS) == 0) return
                    tryPrependFromMessage(entity)
                }

                NotificationCenter.messageDidDelete -> {
                    val channelId = args.getOrNull(0) as? Long ?: return
                    val messageId = args.getOrNull(1) as? Long ?: return
                    if (removeByMessageId(channelId, messageId)) {
                        notificationCenter.postNotificationOnMainThread(
                            NotificationCenter.channelGalleryDidLoad,
                            channelId
                        )
                    }
                }
            }
        }
    }

    init {
        notificationCenter.addObserver(messageObserver, NotificationCenter.didReceiveNewMessages)
        notificationCenter.addObserver(messageObserver, NotificationCenter.messageDidUpdate)
        notificationCenter.addObserver(messageObserver, NotificationCenter.messageDidDelete)
    }

    fun getItems(
        channelId: Long,
        mediaType: ChannelGalleryMediaType = ChannelGalleryMediaType.IMAGE
    ): List<ChannelGalleryMediaItem> =
        synchronized(stateByGallery) {
            stateByGallery[ChannelGalleryKey(channelId, mediaType)]?.items?.toList() ?: emptyList()
        }

    fun hasMoreBefore(
        channelId: Long,
        mediaType: ChannelGalleryMediaType = ChannelGalleryMediaType.IMAGE
    ): Boolean =
        synchronized(stateByGallery) {
            stateByGallery[ChannelGalleryKey(channelId, mediaType)]?.hasMoreBefore != false
        }

    fun isInitialLoading(
        channelId: Long,
        mediaType: ChannelGalleryMediaType = ChannelGalleryMediaType.IMAGE
    ): Boolean =
        synchronized(stateByGallery) {
            stateByGallery[ChannelGalleryKey(channelId, mediaType)]?.initialLoading ?: false
        }

    fun isPagingLoading(
        channelId: Long,
        mediaType: ChannelGalleryMediaType = ChannelGalleryMediaType.IMAGE
    ): Boolean =
        synchronized(stateByGallery) {
            stateByGallery[ChannelGalleryKey(channelId, mediaType)]?.pagingLoading ?: false
        }

    fun isInitialLoadFinished(
        channelId: Long,
        mediaType: ChannelGalleryMediaType = ChannelGalleryMediaType.IMAGE
    ): Boolean =
        synchronized(stateByGallery) {
            stateByGallery[ChannelGalleryKey(channelId, mediaType)]?.initialLoadFinished == true
        }

    fun isFetching(
        channelId: Long,
        mediaType: ChannelGalleryMediaType = ChannelGalleryMediaType.IMAGE
    ): Boolean =
        synchronized(loadingGalleries) { ChannelGalleryKey(channelId, mediaType) in loadingGalleries }

    fun ensureLoaded(
        channelId: Long,
        clanId: Long,
        forceRefresh: Boolean = false,
        mediaType: ChannelGalleryMediaType = ChannelGalleryMediaType.IMAGE
    ) {
        val key = ChannelGalleryKey(channelId, mediaType)
        val cacheKey = galleryCacheKey(key)
        if (!forceRefresh &&
            apiCacheTracker.shouldCall(cacheKey) == ApiCacheTracker.ShouldCall.SKIP &&
            isInitialLoadFinished(channelId, mediaType)
        ) {
            notificationCenter.postNotificationOnMainThread(NotificationCenter.channelGalleryDidLoad, channelId, mediaType)
            return
        }
        synchronized(loadingGalleries) {
            if (!loadingGalleries.add(key)) return
        }
        if (forceRefresh) {
            apiCacheTracker.invalidate(cacheKey)
            resetState(key)
        } else if (isInitialLoadFinished(channelId, mediaType)) {
            resetState(key)
        } else {
            synchronized(stateByGallery) {
                stateByGallery.getOrPut(key) { ChannelGalleryState() }
            }
        }
        appScope.launch {
            try {
                mutexFor(key).withLock {
                    loadImpl(key, clanId, isInitial = true)
                }
            } finally {
                synchronized(loadingGalleries) { loadingGalleries.remove(key) }
            }
        }
    }

    fun clearAndReload(
        channelId: Long,
        clanId: Long,
        mediaType: ChannelGalleryMediaType = ChannelGalleryMediaType.IMAGE
    ) {
        ensureLoaded(channelId, clanId, forceRefresh = true, mediaType = mediaType)
    }

    private fun resetState(key: ChannelGalleryKey) {
        synchronized(stateByGallery) {
            val st = stateByGallery[key] ?: ChannelGalleryState()
            st.items.clear()
            st.hasMoreBefore = true
            st.initialLoadFinished = false
            st.initialLoading = false
            st.pagingLoading = false
            stateByGallery[key] = st
        }
    }

    fun fetchOlderIfNeeded(
        channelId: Long,
        clanId: Long,
        mediaType: ChannelGalleryMediaType = ChannelGalleryMediaType.IMAGE
    ) {
        val key = ChannelGalleryKey(channelId, mediaType)
        appScope.launch {
            mutexFor(key).withLock {
                val st =
                    synchronized(stateByGallery) {
                        stateByGallery[key]
                    } ?: return@withLock

                if (!st.initialLoadFinished || st.pagingLoading || !st.hasMoreBefore || st.initialLoading) return@withLock

                if (st.items.isEmpty()) return@withLock

                loadImpl(key, clanId, isInitial = false)
            }
        }
    }

    private fun mutexFor(key: ChannelGalleryKey): Mutex =
        synchronized(mutexByGallery) {
            mutexByGallery.getOrPut(key) { Mutex() }
        }

    private suspend fun loadImpl(key: ChannelGalleryKey, clanId: Long, isInitial: Boolean) {
        val state =
            synchronized(stateByGallery) {
                stateByGallery.getOrPut(key) { ChannelGalleryState() }
            }

        val shouldProceed =
            synchronized(state) {
                when {
                    isInitial -> {
                        if (state.initialLoading) false
                        else {
                            state.initialLoading = true
                            true
                        }
                    }

                    else -> {
                        if (state.pagingLoading) false
                        else {
                            state.pagingLoading = true
                            true
                        }
                    }
                }
            }
        if (!shouldProceed) return

        val beforeSecs: Int? =
            if (!isInitial) synchronized(state) {
                state.items.lastOrNull()?.createTimeSeconds?.takeIf { it > 0 }
            } else null

        try {
            sessionManager.withAutoRefresh { session ->
                val raw = withContext(ioDispatcher) {
                    api.listChannelAttachments(
                        apiUrl = session.apiUrl,
                        token = session.token,
                        clanId = clanId,
                        channelId = key.channelId,
                        limit = PAGE_LIMIT,
                        fileType = key.mediaType.apiValue,
                        beforeTimeSeconds = beforeSecs
                    )
                }

                val messageIds = raw.attachmentsList.mapNotNull { attachment ->
                    attachment.messageId.takeIf { it != 0L }
                }.distinct()
                val cachedMessages = withContext(ioDispatcher) {
                    if (messageIds.isEmpty()) emptyMap<Long, MessageEntity>()
                    else messageDao.getByIds(key.channelId, messageIds).associateBy { it.id }
                }
                val batchSorted = raw.attachmentsList.mapNotNull { attachment ->
                    attachment.toFilteredMediaOrNull()
                        ?.takeIf { (key.mediaType == ChannelGalleryMediaType.VIDEO) == it.isVideo }
                        ?.enrichFromMessage(cachedMessages[attachment.messageId])
                }
                    .distinctBy { it.id }
                    .sortedWith(
                        compareByDescending<ChannelGalleryMediaItem> { it.createTimeSeconds }.thenByDescending { it.id }
                    )

                var appendedCount = 0
                synchronized(state) {
                    when {
                        isInitial -> {
                            state.items.clear()
                            state.items.addAll(batchSorted)
                            state.initialLoadFinished = true
                            state.hasMoreBefore = raw.attachmentsList.size >= PAGE_LIMIT
                        }

                        else -> {
                            val idsBeforeMerge =
                                state.items.mapTo(HashSet(state.items.size + batchSorted.size)) { it.id }

                            val appended = ArrayList<ChannelGalleryMediaItem>(batchSorted.size)
                            batchSorted.forEach { cand ->
                                if (cand.id !in idsBeforeMerge && appended.none { it.id == cand.id }) {
                                    appended.add(cand)
                                }
                            }

                            appendedCount = appended.size

                            state.items.addAll(appended)
                            state.items.sortWith(
                                compareByDescending<ChannelGalleryMediaItem> { it.createTimeSeconds }
                                    .thenByDescending { it.id }
                            )

                            state.hasMoreBefore = batchSorted.any { cand -> cand.id !in idsBeforeMerge }
                        }
                    }
                }

                if (BuildConfig.DEBUG) {
                    val hm = synchronized(state) { state.hasMoreBefore }
                    Log.d(
                        TAG,
                        "loadMore ch=${key.channelId} initial=$isInitial before=$beforeSecs " +
                            "raw=${raw.attachmentsList.size} batch=${batchSorted.size} " +
                            "appended=$appendedCount hasMoreBefore=$hm"
                    )
                }

                if (isInitial) {
                    apiCacheTracker.markCalled(galleryCacheKey(key))
                }

                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.channelGalleryDidLoad,
                    key.channelId,
                    key.mediaType
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "listChannelAttachments channel=${key.channelId}", e)
            synchronized(state) {
                if (isInitial) state.initialLoadFinished = true
            }

            if (isInitial) {
                apiCacheTracker.invalidate(galleryCacheKey(key))
            }

            notificationCenter.postNotificationOnMainThread(
                NotificationCenter.channelGalleryLoadError,
                key.channelId,
                key.mediaType
            )
        } finally {
            synchronized(state) {
                when {
                    isInitial -> state.initialLoading = false
                    else -> state.pagingLoading = false
                }
            }
        }
    }

    private fun tryPrependFromMessage(message: MessageEntity): Boolean {
        if (!message.mightHaveGalleryMedia()) return false
        if (!message.isRenderable) return false
        if (message.code != MessageEntity.CODE_CHAT && message.code != MessageEntity.CODE_CHAT_UPDATE) return false

        val batch = message.toGalleryMediaItems()
        if (batch.isEmpty()) return false

        var changed = false
        batch.groupBy { if (it.isVideo) ChannelGalleryMediaType.VIDEO else ChannelGalleryMediaType.IMAGE }
            .forEach typeLoop@{ (mediaType, mediaItems) ->
                val key = ChannelGalleryKey(message.channelId, mediaType)
                val state = synchronized(stateByGallery) { stateByGallery[key] }
                if (state == null || !state.initialLoadFinished) {
                    apiCacheTracker.invalidate(galleryCacheKey(key))
                    return@typeLoop
                }

                var stateChanged = false
                synchronized(state) {
                    val existingUrls = state.items.mapTo(HashSet(state.items.size)) { it.url }
                    val existingIds = state.items.mapTo(HashSet(state.items.size)) { it.id }

                    mediaItems.forEach candidateLoop@{ candidate ->
                        if (candidate.url in existingUrls || candidate.id in existingIds) return@candidateLoop
                        state.items.add(candidate)
                        existingUrls.add(candidate.url)
                        existingIds.add(candidate.id)
                        stateChanged = true
                        changed = true
                    }

                    if (stateChanged) {
                        state.items.sortWith(
                            compareByDescending<ChannelGalleryMediaItem> { it.createTimeSeconds }
                                .thenByDescending { it.id }
                        )
                    }
                }
                if (stateChanged) apiCacheTracker.invalidate(galleryCacheKey(key))
            }

        if (!changed) return false

        notificationCenter.postNotificationOnMainThread(NotificationCenter.channelGalleryDidLoad, message.channelId)
        return true
    }

    private fun removeByMessageId(channelId: Long, messageId: Long): Boolean {
        synchronized(stateByGallery) {
            var changed = false
            stateByGallery
                .filterKeys { it.channelId == channelId }
                .values
                .filter { it.initialLoadFinished }
                .forEach { state ->
                    val before = state.items.size
                    state.items.removeAll { it.messageId == messageId }
                    if (state.items.size != before) changed = true
                }
            return changed
        }
    }

    private fun galleryCacheKey(key: ChannelGalleryKey): String =
        apiCacheKey("channelGallery", key.channelId, key.mediaType.apiValue)

    fun cleanup() {
        synchronized(stateByGallery) {
            stateByGallery.clear()
        }
        synchronized(mutexByGallery) {
            mutexByGallery.clear()
        }
        synchronized(loadingGalleries) {
            loadingGalleries.clear()
        }
    }
}

private fun MessageEntity.mightHaveGalleryMedia(): Boolean {
    if (attachmentUrl.isNotEmpty()) return true
    val json = extraAttachmentsJson
    if (json.isEmpty() || json == "[]") return false
    return true
}

private fun MessageEntity.toGalleryMediaItems(): List<ChannelGalleryMediaItem> {
    val media = allImageAttachments
    if (media.isEmpty()) return emptyList()

    val out = ArrayList<ChannelGalleryMediaItem>(media.size)
    media.forEachIndexed { index, attachment ->
        attachment.toGalleryMediaItem(this, index)?.let { out.add(it) }
    }
    return out
}

private fun AttachmentInfo.toGalleryMediaItem(message: MessageEntity, index: Int): ChannelGalleryMediaItem? {
    val image = isImageAttachmentType(filetype)
    val video = isVideoAttachmentType(filetype)
    if (!image && !video) return null

    val u = url.trim()
    if (u.isEmpty()) return null
    if (u.startsWith("content://") || u.startsWith("file://")) return null
    if (u.lowercase(Locale.US).contains("/stickers")) return null

    return ChannelGalleryMediaItem(
        id = message.id * 1000L + index,
        url = u,
        filetype = filetype,
        createTimeSeconds = message.timestampSeconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        uploaderId = message.senderId,
        messageId = message.id,
        thumbnail = thumb,
        filename = filename,
        width = width,
        height = height,
        size = size,
        duration = duration
    )
}

private fun ChannelAttachment.toFilteredMediaOrNull(): ChannelGalleryMediaItem? {
    val image = isImageAttachmentType(filetype)
    val video = isVideoAttachmentType(filetype)
    if (!image && !video) return null

    val u = url
    if (u.isEmpty()) return null
    if (u.lowercase(Locale.US).contains("/stickers")) return null

    return ChannelGalleryMediaItem(
        id = id,
        url = u,
        filetype = filetype,
        createTimeSeconds = createTimeSeconds,
        uploaderId = uploader,
        messageId = messageId,
        filename = filename,
        width = width,
        height = height,
        size = filesize.trim().toLongOrNull()
            ?.coerceIn(0L, Int.MAX_VALUE.toLong())
            ?.toInt()
            ?: 0
    )
}

private fun ChannelGalleryMediaItem.enrichFromMessage(message: MessageEntity?): ChannelGalleryMediaItem {
    val attachment = message?.allImageAttachments?.firstOrNull { it.url == url } ?: return this
    return copy(
        thumbnail = thumbnail.ifBlank { attachment.thumb },
        filename = filename.ifBlank { attachment.filename },
        width = width.takeIf { it > 0 } ?: attachment.width,
        height = height.takeIf { it > 0 } ?: attachment.height,
        size = size.takeIf { it > 0 } ?: attachment.size,
        duration = duration.takeIf { it > 0 } ?: attachment.duration
    )
}
