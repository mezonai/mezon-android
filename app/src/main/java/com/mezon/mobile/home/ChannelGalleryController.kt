package com.mezon.mobile.home

import android.util.Log
import com.mezon.mobile.BuildConfig
import com.mezon.mezon.api.ChannelAttachment
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.home.chat.AttachmentInfo
import com.mezon.mobile.home.chat.MessageEntity
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
    val messageId: Long
) {
    val isVideo: Boolean get() = filetype.lowercase(Locale.US).startsWith("video/")
}

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
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope
) {

    private val stateByChannel = HashMap<Long, ChannelGalleryState>()
    private val mutexByChannel = HashMap<Long, Mutex>()
    private val loadingChannels = Collections.synchronizedSet(HashSet<Long>())

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

    fun getItems(channelId: Long): List<ChannelGalleryMediaItem> =
        synchronized(stateByChannel) {
            stateByChannel[channelId]?.items?.toList() ?: emptyList()
        }

    fun hasMoreBefore(channelId: Long): Boolean =
        synchronized(stateByChannel) {
            stateByChannel[channelId]?.hasMoreBefore != false
        }

    fun isInitialLoading(channelId: Long): Boolean =
        synchronized(stateByChannel) {
            stateByChannel[channelId]?.initialLoading ?: false
        }

    fun isPagingLoading(channelId: Long): Boolean =
        synchronized(stateByChannel) {
            stateByChannel[channelId]?.pagingLoading ?: false
        }

    fun isInitialLoadFinished(channelId: Long): Boolean =
        synchronized(stateByChannel) {
            stateByChannel[channelId]?.initialLoadFinished == true
        }

    fun isFetching(channelId: Long): Boolean = synchronized(loadingChannels) { channelId in loadingChannels }

    fun ensureLoaded(channelId: Long, clanId: Long, forceRefresh: Boolean = false) {
        val cacheKey = apiCacheKey("channelGallery", channelId)
        if (!forceRefresh &&
            apiCacheTracker.shouldCall(cacheKey) == ApiCacheTracker.ShouldCall.SKIP &&
            isInitialLoadFinished(channelId)
        ) {
            notificationCenter.postNotificationOnMainThread(NotificationCenter.channelGalleryDidLoad, channelId)
            return
        }
        synchronized(loadingChannels) {
            if (!loadingChannels.add(channelId)) return
        }
        if (forceRefresh) {
            apiCacheTracker.invalidate(cacheKey)
            resetState(channelId)
        } else if (isInitialLoadFinished(channelId)) {
            resetState(channelId)
        } else {
            synchronized(stateByChannel) {
                stateByChannel.getOrPut(channelId) { ChannelGalleryState() }
            }
        }
        appScope.launch {
            try {
                mutexFor(channelId).withLock {
                    loadImpl(channelId, clanId, isInitial = true)
                }
            } finally {
                synchronized(loadingChannels) { loadingChannels.remove(channelId) }
            }
        }
    }

    fun clearAndReload(channelId: Long, clanId: Long) {
        ensureLoaded(channelId, clanId, forceRefresh = true)
    }

    private fun resetState(channelId: Long) {
        synchronized(stateByChannel) {
            val st = stateByChannel[channelId] ?: ChannelGalleryState()
            st.items.clear()
            st.hasMoreBefore = true
            st.initialLoadFinished = false
            st.initialLoading = false
            st.pagingLoading = false
            stateByChannel[channelId] = st
        }
    }

    fun fetchOlderIfNeeded(channelId: Long, clanId: Long) {
        appScope.launch {
            mutexFor(channelId).withLock {
                val st =
                    synchronized(stateByChannel) {
                        stateByChannel[channelId]
                    } ?: return@withLock

                if (!st.initialLoadFinished || st.pagingLoading || !st.hasMoreBefore || st.initialLoading) return@withLock

                if (st.items.isEmpty()) return@withLock

                loadImpl(channelId, clanId, isInitial = false)
            }
        }
    }

    private fun mutexFor(channelId: Long): Mutex =
        synchronized(mutexByChannel) {
            mutexByChannel.getOrPut(channelId) { Mutex() }
        }

    private suspend fun loadImpl(channelId: Long, clanId: Long, isInitial: Boolean) {
        val state =
            synchronized(stateByChannel) {
                stateByChannel.getOrPut(channelId) { ChannelGalleryState() }
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
                        channelId = channelId,
                        limit = PAGE_LIMIT,
                        beforeTimeSeconds = beforeSecs
                    )
                }

                val batchSorted = raw.attachmentsList.mapNotNull { it.toFilteredMediaOrNull() }
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
                        "loadMore ch=$channelId initial=$isInitial before=$beforeSecs " +
                            "raw=${raw.attachmentsList.size} batch=${batchSorted.size} " +
                            "appended=$appendedCount hasMoreBefore=$hm"
                    )
                }

                if (isInitial) {
                    apiCacheTracker.markCalled(apiCacheKey("channelGallery", channelId))
                }

                notificationCenter.postNotificationOnMainThread(NotificationCenter.channelGalleryDidLoad, channelId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "listChannelAttachments channel=$channelId", e)
            synchronized(state) {
                if (isInitial) state.initialLoadFinished = true
            }

            if (isInitial) {
                apiCacheTracker.invalidate(apiCacheKey("channelGallery", channelId))
            }

            notificationCenter.postNotificationOnMainThread(NotificationCenter.channelGalleryLoadError, channelId)
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

        val channelId = message.channelId
        val cacheKey = apiCacheKey("channelGallery", channelId)
        if (!isInitialLoadFinished(channelId)) {
            apiCacheTracker.invalidate(cacheKey)
            return false
        }

        val batch = message.toGalleryMediaItems()
        if (batch.isEmpty()) return false

        var changed = false
        synchronized(stateByChannel) {
            val state = stateByChannel[channelId] ?: return false
            val existingUrls = state.items.mapTo(HashSet(state.items.size)) { it.url }
            val existingIds = state.items.mapTo(HashSet(state.items.size)) { it.id }

            batch.forEach { candidate ->
                if (candidate.url in existingUrls || candidate.id in existingIds) return@forEach
                state.items.add(candidate)
                existingUrls.add(candidate.url)
                existingIds.add(candidate.id)
                changed = true
            }

            if (changed) {
                state.items.sortWith(
                    compareByDescending<ChannelGalleryMediaItem> { it.createTimeSeconds }
                        .thenByDescending { it.id }
                )
            }
        }

        if (!changed) return false

        apiCacheTracker.invalidate(cacheKey)
        notificationCenter.postNotificationOnMainThread(NotificationCenter.channelGalleryDidLoad, channelId)
        return true
    }

    private fun removeByMessageId(channelId: Long, messageId: Long): Boolean {
        synchronized(stateByChannel) {
            val state = stateByChannel[channelId] ?: return false
            if (!state.initialLoadFinished) return false
            val before = state.items.size
            state.items.removeAll { it.messageId == messageId }
            return state.items.size != before
        }
    }

    fun cleanup() {
        synchronized(stateByChannel) {
            stateByChannel.clear()
        }
        synchronized(mutexByChannel) {
            mutexByChannel.clear()
        }
        synchronized(loadingChannels) {
            loadingChannels.clear()
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
    val ft = filetype.lowercase(Locale.US)
    val image = ft.startsWith("image/")
    val video = ft.startsWith("video/")
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
        messageId = message.id
    )
}

private fun ChannelAttachment.toFilteredMediaOrNull(): ChannelGalleryMediaItem? {
    val ft = filetype.lowercase(Locale.US)
    val image = ft.startsWith("image/")
    val video = ft.startsWith("video/")
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
        messageId = messageId
    )
}
