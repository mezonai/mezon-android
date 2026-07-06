package com.mezon.mobile.home

import android.util.Log
import com.mezon.mezon.api.ChannelAttachment
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.home.chat.AttachmentInfo
import com.mezon.mobile.home.chat.MessageEntity
import com.mezon.mobile.home.chat.channelinfo.ChannelDocumentItem
import com.mezon.mobile.network.ApiCacheTracker
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.network.apiCacheKey
import com.mezon.mobile.session.SessionManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ChannelFilesController"
private const val FILES_CACHE_TTL_MS = 60 * 60 * 1_000L

@Singleton
class ChannelFilesController @Inject constructor(
    private val api: MezonApi,
    private val sessionManager: SessionManager,
    private val notificationCenter: NotificationCenter,
    private val apiCacheTracker: ApiCacheTracker,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope
) {

    private val docsByChannel = HashMap<Long, ArrayList<ChannelDocumentItem>>()
    private val loadFailed = Collections.synchronizedSet(HashSet<Long>())
    private val loadingChannels = Collections.synchronizedSet(HashSet<Long>())

    private val messageObserver = object : NotificationCenter.NotificationCenterDelegate {
        override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
            when (id) {
                NotificationCenter.didReceiveNewMessages -> {
                    val message = args.getOrNull(1) as? MessageEntity ?: return
                    mergeFilesFromMessage(message, replaceExisting = false)
                }

                NotificationCenter.messageDidUpdate -> {
                    val message = args.getOrNull(1) as? MessageEntity ?: return
                    val mask = args.getOrNull(2) as? Int ?: 0
                    if ((mask and NotificationCenter.UPDATE_MASK_ATTACHMENTS) == 0) return
                    mergeFilesFromMessage(message, replaceExisting = true)
                }

                NotificationCenter.messageDidDelete -> {
                    val channelId = args.getOrNull(0) as? Long ?: return
                    val messageId = args.getOrNull(1) as? Long ?: return
                    if (removeByMessageId(channelId, messageId)) {
                        apiCacheTracker.invalidate(apiCacheKey("channelFiles", channelId))
                        notifyFilesChanged(channelId)
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

    fun getDocuments(channelId: Long): List<ChannelDocumentItem> {
        synchronized(this) {
            return docsByChannel[channelId]?.toList() ?: emptyList()
        }
    }

    fun hadLoadError(channelId: Long): Boolean = synchronized(loadFailed) { channelId in loadFailed }

    fun isFetching(channelId: Long): Boolean = synchronized(loadingChannels) { channelId in loadingChannels }

    fun loadChannelFiles(channelId: Long, clanId: Long, forceRefresh: Boolean = false) {
        val cacheKey = apiCacheKey("channelFiles", channelId)
        if (!forceRefresh &&
            apiCacheTracker.shouldCall(cacheKey, ttlMs = FILES_CACHE_TTL_MS) == ApiCacheTracker.ShouldCall.SKIP &&
            synchronized(this@ChannelFilesController) { docsByChannel.containsKey(channelId) }
        ) {
            notificationCenter.postNotificationOnMainThread(NotificationCenter.channelFilesDidLoad, channelId)
            return
        }
        synchronized(loadingChannels) {
            if (!loadingChannels.add(channelId)) return
        }
        appScope.launch {
            try {
                sessionManager.withAutoRefresh { session ->
                    val raw = withContext(ioDispatcher) {
                        api.listChannelAttachments(session.apiUrl, session.token, clanId, channelId, limit = 100)
                    }
                    val list = raw.attachmentsList.mapNotNull { it.toFilteredDocumentOrNull() }
                        .distinctBy { it.stableId }
                    synchronized(this@ChannelFilesController) {
                        docsByChannel[channelId] = ArrayList(list)
                    }
                    synchronized(loadFailed) { loadFailed.remove(channelId) }
                    apiCacheTracker.markCalled(cacheKey, ttlMs = FILES_CACHE_TTL_MS)
                    notificationCenter.postNotificationOnMainThread(NotificationCenter.channelFilesDidLoad, channelId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "listChannelAttachments failed channel=$channelId", e)
                synchronized(loadFailed) { loadFailed.add(channelId) }
                apiCacheTracker.invalidate(cacheKey)
                notificationCenter.postNotificationOnMainThread(NotificationCenter.channelFilesLoadError, channelId)
            } finally {
                synchronized(loadingChannels) { loadingChannels.remove(channelId) }
            }
        }
    }

    private fun mergeFilesFromMessage(message: MessageEntity, replaceExisting: Boolean) {
        if (message.code != MessageEntity.CODE_CHAT && message.code != MessageEntity.CODE_CHAT_UPDATE) return

        val channelId = message.channelId
        val newItems = message.toChannelDocumentItems()
        if (!replaceExisting && newItems.isEmpty()) return

        val cacheKey = apiCacheKey("channelFiles", channelId)
        var changed = false

        synchronized(this) {
            val current = docsByChannel[channelId]
            if (current == null) {
                apiCacheTracker.invalidate(cacheKey)
                return
            }

            if (replaceExisting) {
                changed = current.removeAll { it.messageId == message.id } || changed
            }

            val existingIds = current.mapTo(HashSet(current.size)) { it.stableId }
            newItems.forEach { item ->
                if (existingIds.add(item.stableId)) {
                    current.add(item)
                    changed = true
                }
            }
        }

        if (changed) {
            apiCacheTracker.invalidate(cacheKey)
            notifyFilesChanged(channelId)
        }
    }

    private fun removeByMessageId(channelId: Long, messageId: Long): Boolean = synchronized(this) {
        val current = docsByChannel[channelId] ?: return@synchronized false
        current.removeAll { it.messageId == messageId }
    }

    private fun notifyFilesChanged(channelId: Long) {
        notificationCenter.postNotificationOnMainThread(NotificationCenter.channelFilesDidLoad, channelId)
    }

    fun cleanup() {
        synchronized(this) { docsByChannel.clear() }
        synchronized(loadFailed) { loadFailed.clear() }
        synchronized(loadingChannels) { loadingChannels.clear() }
    }
}

private fun MessageEntity.toChannelDocumentItems(): List<ChannelDocumentItem> {
    return allAttachmentsInfo.mapNotNull { attachment ->
        attachment.toChannelDocumentItem(this)
    }
}

private fun AttachmentInfo.toChannelDocumentItem(message: MessageEntity): ChannelDocumentItem? {
    if (!filetype.isDocumentFiletype()) return null

    val urlStr = url.trim()
    if (urlStr.isEmpty() || urlStr.startsWith("content://") || urlStr.startsWith("file://")) return null

    return ChannelDocumentItem(
        stableId = "${message.id}_$urlStr",
        filename = filename.ifBlank { "File" },
        filetype = filetype.ifBlank { "File" },
        url = urlStr,
        uploader = message.senderId,
        createTimeSeconds = message.timestampSeconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        messageId = message.id
    )
}

private fun ChannelAttachment.toFilteredDocumentOrNull(): ChannelDocumentItem? {
    if (!filetype.isDocumentFiletype()) return null
    val urlStr = url
    if (urlStr.isEmpty()) return null
    val msgId = messageId
    return ChannelDocumentItem(
        stableId = "${msgId}_$urlStr",
        filename = filename.ifBlank { "File" },
        filetype = filetype.ifBlank { "File" },
        url = urlStr,
        uploader = uploader,
        createTimeSeconds = createTimeSeconds,
        messageId = msgId
    )
}

private fun String.isDocumentFiletype(): Boolean {
    val normalized = lowercase(Locale.US)
    return !normalized.startsWith("image") &&
        !normalized.startsWith("video") &&
        normalized != "sticker"
}
