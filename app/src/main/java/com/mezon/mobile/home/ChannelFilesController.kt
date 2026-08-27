package com.mezon.mobile.home

import android.util.Log
import com.mezon.mezon.api.ChannelAttachment
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.home.chat.AttachmentInfo
import com.mezon.mobile.home.chat.MessageEntity
import com.mezon.mobile.home.chat.isAudioAttachmentType
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

enum class ChannelFilesType(val apiValue: String) {
    DOC("doc"),
    AUDIO("audio")
}

private data class ChannelFilesKey(
    val channelId: Long,
    val filesType: ChannelFilesType
)

@Singleton
class ChannelFilesController @Inject constructor(
    private val api: MezonApi,
    private val sessionManager: SessionManager,
    private val notificationCenter: NotificationCenter,
    private val apiCacheTracker: ApiCacheTracker,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope
) {

    private val filesByType = HashMap<ChannelFilesKey, ArrayList<ChannelDocumentItem>>()
    private val loadFailed = Collections.synchronizedSet(HashSet<ChannelFilesKey>())
    private val loadingFiles = Collections.synchronizedSet(HashSet<ChannelFilesKey>())

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
                    ChannelFilesType.entries.forEach { filesType ->
                        if (removeByMessageId(channelId, messageId, filesType)) {
                            apiCacheTracker.invalidate(filesCacheKey(ChannelFilesKey(channelId, filesType)))
                            notifyFilesChanged(channelId, filesType)
                        }
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

    fun getDocuments(
        channelId: Long,
        filesType: ChannelFilesType = ChannelFilesType.DOC
    ): List<ChannelDocumentItem> {
        synchronized(this) {
            return filesByType[ChannelFilesKey(channelId, filesType)]?.toList() ?: emptyList()
        }
    }

    fun hadLoadError(
        channelId: Long,
        filesType: ChannelFilesType = ChannelFilesType.DOC
    ): Boolean = synchronized(loadFailed) { ChannelFilesKey(channelId, filesType) in loadFailed }

    fun isFetching(
        channelId: Long,
        filesType: ChannelFilesType = ChannelFilesType.DOC
    ): Boolean = synchronized(loadingFiles) { ChannelFilesKey(channelId, filesType) in loadingFiles }

    fun loadChannelFiles(
        channelId: Long,
        clanId: Long,
        forceRefresh: Boolean = false,
        filesType: ChannelFilesType = ChannelFilesType.DOC
    ) {
        val key = ChannelFilesKey(channelId, filesType)
        val cacheKey = filesCacheKey(key)
        if (!forceRefresh &&
            apiCacheTracker.shouldCall(cacheKey, ttlMs = FILES_CACHE_TTL_MS) == ApiCacheTracker.ShouldCall.SKIP &&
            synchronized(this@ChannelFilesController) { filesByType.containsKey(key) }
        ) {
            notificationCenter.postNotificationOnMainThread(NotificationCenter.channelFilesDidLoad, channelId, filesType)
            return
        }
        synchronized(loadingFiles) {
            if (!loadingFiles.add(key)) return
        }
        appScope.launch {
            try {
                sessionManager.withAutoRefresh { session ->
                    val raw = withContext(ioDispatcher) {
                        api.listChannelAttachments(
                            session.apiUrl,
                            session.token,
                            clanId,
                            channelId,
                            limit = 100,
                            fileType = filesType.apiValue
                        )
                    }
                    val list = raw.attachmentsList.mapNotNull { it.toFilteredDocumentOrNull(filesType) }
                        .distinctBy { it.stableId }
                    synchronized(this@ChannelFilesController) {
                        filesByType[key] = ArrayList(list)
                    }
                    synchronized(loadFailed) { loadFailed.remove(key) }
                    apiCacheTracker.markCalled(cacheKey, ttlMs = FILES_CACHE_TTL_MS)
                    notificationCenter.postNotificationOnMainThread(NotificationCenter.channelFilesDidLoad, channelId, filesType)
                }
            } catch (e: Exception) {
                Log.e(TAG, "listChannelAttachments failed channel=$channelId", e)
                synchronized(loadFailed) { loadFailed.add(key) }
                apiCacheTracker.invalidate(cacheKey)
                notificationCenter.postNotificationOnMainThread(NotificationCenter.channelFilesLoadError, channelId, filesType)
            } finally {
                synchronized(loadingFiles) { loadingFiles.remove(key) }
            }
        }
    }

    private fun mergeFilesFromMessage(message: MessageEntity, replaceExisting: Boolean) {
        if (message.code != MessageEntity.CODE_CHAT && message.code != MessageEntity.CODE_CHAT_UPDATE) return
        val attachments = message.allAttachmentsInfo
        if (!replaceExisting && attachments.isEmpty()) return

        ChannelFilesType.entries.forEach { filesType ->
            mergeFilesFromMessage(message, attachments, replaceExisting, filesType)
        }
    }

    private fun mergeFilesFromMessage(
        message: MessageEntity,
        attachments: List<AttachmentInfo>,
        replaceExisting: Boolean,
        filesType: ChannelFilesType
    ) {
        val key = ChannelFilesKey(message.channelId, filesType)
        val newItems = attachments.mapNotNull { it.toChannelDocumentItem(message, filesType) }
        if (!replaceExisting && newItems.isEmpty()) return

        val cacheKey = filesCacheKey(key)
        var changed = false

        synchronized(this) {
            val current = filesByType[key]
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
            notifyFilesChanged(message.channelId, filesType)
        }
    }

    private fun removeByMessageId(
        channelId: Long,
        messageId: Long,
        filesType: ChannelFilesType
    ): Boolean = synchronized(this) {
        val current = filesByType[ChannelFilesKey(channelId, filesType)] ?: return@synchronized false
        current.removeAll { it.messageId == messageId }
    }

    private fun notifyFilesChanged(channelId: Long, filesType: ChannelFilesType) {
        notificationCenter.postNotificationOnMainThread(NotificationCenter.channelFilesDidLoad, channelId, filesType)
    }

    private fun filesCacheKey(key: ChannelFilesKey): String =
        apiCacheKey("channelFiles", key.channelId, key.filesType.apiValue)

    fun cleanup() {
        synchronized(this) { filesByType.clear() }
        synchronized(loadFailed) { loadFailed.clear() }
        synchronized(loadingFiles) { loadingFiles.clear() }
    }
}

private fun AttachmentInfo.toChannelDocumentItem(
    message: MessageEntity,
    filesType: ChannelFilesType
): ChannelDocumentItem? {
    if (!filetype.matchesFilesType(filesType)) return null

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

private fun ChannelAttachment.toFilteredDocumentOrNull(filesType: ChannelFilesType): ChannelDocumentItem? {
    if (!filetype.matchesFilesType(filesType)) return null
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
        !isAudioAttachmentType(this) &&
        normalized != "sticker"
}

private fun String.matchesFilesType(filesType: ChannelFilesType): Boolean =
    when (filesType) {
        ChannelFilesType.DOC -> isDocumentFiletype()
        ChannelFilesType.AUDIO -> isAudioAttachmentType(this)
    }
