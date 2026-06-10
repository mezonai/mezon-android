package com.mezon.mobile.home

import android.util.Log
import com.mezon.mezon.api.PinMessage
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.network.ApiCacheTracker
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.network.MezonSocket
import com.mezon.mobile.network.SocketEventDispatcher
import com.mezon.mobile.network.apiCacheKey
import com.mezon.mobile.network.channelTypeToStreamMode
import com.mezon.mobile.session.SessionManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

data class PinMessageData(
    val id: Long,
    val messageId: Long,
    val channelId: Long,
    val senderId: Long,
    val content: String,
    val username: String,
    val avatar: String,
    val createTimeSeconds: Int,
    val attachments: List<PinAttachment> = emptyList()
) {
    fun pinDedupeKey(): String {
        if (messageId != 0L) return "m:$messageId"
        if (id != 0L) return "p:$id"
        val url = attachments.firstOrNull()?.url?.trim().orEmpty()
        if (url.isNotEmpty()) return "u:$url"
        val contentKey = content.trim()
        if (contentKey.isNotEmpty()) return "c:$contentKey"
        return ""
    }
}

data class PinAttachment(
    val url: String = "",
    val filename: String = "",
    val filetype: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val thumbnail: String = "",
    val size: Int = 0,
    val duration: Int = 0
)

private const val TAG = "PinMessageController"

@Singleton
class PinMessageController @Inject constructor(
    private val api: MezonApi,
    private val mezonSocket: MezonSocket,
    private val socketEventDispatcher: SocketEventDispatcher,
    private val sessionManager: SessionManager,
    private val notificationCenter: NotificationCenter,
    private val apiCacheTracker: ApiCacheTracker,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope
) {

    private val pinMessagesByChannel = HashMap<Long, ArrayList<PinMessageData>>()
    private val pinnedMessageIdsByChannel = HashMap<Long, HashSet<Long>>()
    private val clanHintByChannel = ConcurrentHashMap<Long, Long>()
    private val pinListLoadGeneration = ConcurrentHashMap<Long, AtomicLong>()

    init {
        appScope.launch { observePinEvents() }
        appScope.launch { observeUnpinEvents() }
    }

    private suspend fun observePinEvents() {
        socketEventDispatcher.lastPinMessageEvents.collect { event ->
            if (event.operation != 1) return@collect
            val channelId = event.channelId
            val data = PinMessageData(
                id = 0,
                messageId = event.messageId,
                channelId = channelId,
                senderId = event.messageSenderId.toLongOrNull() ?: 0L,
                content = event.messageContent,
                username = event.messageSenderUsername,
                avatar = event.messageSenderAvatar,
                createTimeSeconds = event.timestampSeconds,
                attachments = parseAttachmentJson(event.messageAttachment)
            )
            clanHintByChannel[channelId] = event.clanId
            if (data.messageId != 0L) trackPinned(channelId, data.messageId)
            apiCacheTracker.invalidate(apiCacheKey("pinMessages", channelId))
            if (pushToCacheIfLoaded(channelId, data)) {
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.pinMessageAdded, channelId
                )
            }
        }
    }

    private suspend fun observeUnpinEvents() {
        socketEventDispatcher.unpinMessageEvents.collect { event ->
            val channelId = event.channelId
            val messageId = event.messageId
            untrackPinned(channelId, messageId)
            apiCacheTracker.invalidate(apiCacheKey("pinMessages", channelId))
            val hadCache = removeFromCacheIfLoaded(channelId, messageId)
            if (hadCache) {
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.pinMessageRemoved, channelId, messageId
                )
            }
        }
    }

    fun getPinMessages(channelId: Long): List<PinMessageData> {
        synchronized(this) {
            val list = pinMessagesByChannel[channelId] ?: return emptyList()
            return dedupePinMessages(list)
        }
    }

    fun isPinned(channelId: Long, messageId: Long): Boolean {
        synchronized(this) {
            return pinnedMessageIdsByChannel[channelId]?.contains(messageId) == true
        }
    }

    fun hasPinListCache(channelId: Long): Boolean {
        synchronized(this) {
            return pinMessagesByChannel.containsKey(channelId)
        }
    }

    fun loadPinMessages(channelId: Long, clanId: Long, noCache: Boolean = false) {
        clanHintByChannel[channelId] = clanId
        val cacheKey = apiCacheKey("pinMessages", channelId)
        if (apiCacheTracker.shouldCall(cacheKey, noCache = noCache) == ApiCacheTracker.ShouldCall.SKIP) {
            notificationCenter.postNotificationOnMainThread(
                NotificationCenter.pinMessagesDidLoad, channelId
            )
            return
        }
        val generation = pinListLoadGeneration.getOrPut(channelId) { AtomicLong(0) }.incrementAndGet()
        appScope.launch {
            try {
                val response = sessionManager.withAutoRefresh { session ->
                    withContext(ioDispatcher) {
                        api.listPinMessages(session.apiUrl, session.token, channelId, clanId)
                    }
                }
                val raw = response.pinMessagesListList.map { it.toPinMessageData() }
                val items = dedupePinMessages(raw)
                synchronized(this@PinMessageController) {
                    if (pinListLoadGeneration[channelId]?.get() != generation) {
                        return@synchronized
                    }
                    pinMessagesByChannel[channelId] = ArrayList(items)
                    syncPinnedIdsFromList(channelId, items)
                }
                if (pinListLoadGeneration[channelId]?.get() != generation) return@launch
                apiCacheTracker.markCalled(cacheKey)
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.pinMessagesDidLoad, channelId
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load pin messages for channel=$channelId", e)
            }
        }
    }

    fun pinMessage(
        channelId: Long,
        clanId: Long,
        channelType: Int,
        isChannelPrivate: Boolean,
        messageId: Long,
        senderAvatar: String,
        senderId: String,
        senderUsername: String,
        messageContent: String,
        messageAttachment: String,
        messageCreatedTime: String
    ) {
        clanHintByChannel[channelId] = clanId
        trackPinned(channelId, messageId)
        apiCacheTracker.invalidate(apiCacheKey("pinMessages", channelId))
        appScope.launch {
            try {
                sessionManager.withAutoRefresh { session ->
                    withContext(ioDispatcher) {
                        api.createPinMessage(session.apiUrl, session.token, channelId, clanId, messageId)
                    }
                }
                val optimistic = PinMessageData(
                    id = 0,
                    messageId = messageId,
                    channelId = channelId,
                    senderId = senderId.toLongOrNull() ?: 0L,
                    content = messageContent,
                    username = senderUsername,
                    avatar = senderAvatar,
                    createTimeSeconds = (System.currentTimeMillis() / 1000).toInt(),
                    attachments = parseAttachmentJson(messageAttachment)
                )
                if (pushToCacheIfLoaded(channelId, optimistic)) {
                    notificationCenter.postNotificationOnMainThread(
                        NotificationCenter.pinMessageAdded, channelId
                    )
                }
                val mode = channelTypeToStreamMode(channelType)
                val isPublic = !isChannelPrivate
                val now = (System.currentTimeMillis() / 1000).toInt()
                mezonSocket.writeLastPinMessage(
                    clanId = clanId,
                    channelId = channelId,
                    mode = mode,
                    isPublic = isPublic,
                    messageId = messageId,
                    timestampSeconds = now,
                    operation = 1,
                    messageSenderAvatar = senderAvatar,
                    messageSenderId = senderId,
                    messageSenderUsername = senderUsername,
                    messageContent = messageContent,
                    messageAttachment = messageAttachment,
                    messageCreatedTime = messageCreatedTime
                )
            } catch (e: Exception) {
                untrackPinned(channelId, messageId)
                Log.e(TAG, "Failed to pin message=$messageId", e)
            }
        }
    }

    fun unpinMessage(channelId: Long, clanId: Long, messageId: Long) {
        clanHintByChannel[channelId] = clanId
        untrackPinned(channelId, messageId)
        apiCacheTracker.invalidate(apiCacheKey("pinMessages", channelId))
        val hadCache = removeFromCacheIfLoaded(channelId, messageId)
        if (hadCache) {
            notificationCenter.postNotificationOnMainThread(
                NotificationCenter.pinMessageRemoved, channelId, messageId
            )
        }
        appScope.launch {
            try {
                sessionManager.withAutoRefresh { session ->
                    withContext(ioDispatcher) {
                        api.deletePinMessage(session.apiUrl, session.token, messageId, channelId, clanId)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unpin message=$messageId", e)
                if (hasPinListCache(channelId)) {
                    loadPinMessages(channelId, clanId, noCache = true)
                }
            }
        }
    }

    private fun trackPinned(channelId: Long, messageId: Long) {
        if (messageId == 0L) return
        synchronized(this) {
            pinnedMessageIdsByChannel.getOrPut(channelId) { HashSet() }.add(messageId)
        }
    }

    private fun untrackPinned(channelId: Long, messageId: Long) {
        if (messageId == 0L) return
        synchronized(this) {
            pinnedMessageIdsByChannel[channelId]?.remove(messageId)
        }
    }

    private fun syncPinnedIdsFromList(channelId: Long, items: List<PinMessageData>) {
        pinnedMessageIdsByChannel[channelId] = items.map { it.messageId }.filter { it != 0L }.toHashSet()
    }

    private fun pushToCacheIfLoaded(channelId: Long, data: PinMessageData): Boolean {
        if (data.messageId == 0L) return false
        synchronized(this) {
            val list = pinMessagesByChannel[channelId] ?: return false
            removeMatchingPins(list, data)
            list.add(0, data)
            replacePinList(channelId, dedupePinMessages(list))
            return true
        }
    }

    private fun removeFromCacheIfLoaded(channelId: Long, messageId: Long): Boolean {
        synchronized(this) {
            val list = pinMessagesByChannel[channelId] ?: return false
            return list.removeAll { it.messageId == messageId }
        }
    }

    private fun replacePinList(channelId: Long, items: List<PinMessageData>) {
        pinMessagesByChannel[channelId] = ArrayList(items)
    }

    fun cleanup() {
        synchronized(this) {
            pinMessagesByChannel.clear()
            pinnedMessageIdsByChannel.clear()
        }
        clanHintByChannel.clear()
        pinListLoadGeneration.clear()
    }
}

private fun removeMatchingPins(list: ArrayList<PinMessageData>, data: PinMessageData) {
    list.removeAll { existing ->
        (data.messageId != 0L && existing.messageId == data.messageId) ||
            (data.id != 0L && existing.id == data.id)
    }
}

private fun shouldPreferPinEntry(candidate: PinMessageData, existing: PinMessageData): Boolean {
    if (existing.id == 0L && candidate.id != 0L) return true
    if (existing.id != 0L && candidate.id == 0L) return false
    return false
}

private fun dedupePinMessages(items: List<PinMessageData>): List<PinMessageData> {
    if (items.size <= 1) return items
    val indexByKey = LinkedHashMap<String, Int>()
    val out = ArrayList<PinMessageData>(items.size)
    for (item in items) {
        val key = item.pinDedupeKey()
        if (key.isEmpty()) {
            out.add(item)
            continue
        }
        val existingIdx = indexByKey[key]
        if (existingIdx == null) {
            indexByKey[key] = out.size
            out.add(item)
            continue
        }
        val existing = out[existingIdx]
        if (shouldPreferPinEntry(item, existing)) {
            out[existingIdx] = item
        }
    }
    return out
}

private fun PinMessage.toPinMessageData(): PinMessageData = PinMessageData(
    id = this.id,
    messageId = this.messageId,
    channelId = this.channelId,
    senderId = this.senderId,
    content = this.content,
    username = this.username,
    avatar = this.avatar,
    createTimeSeconds = this.createTimeSeconds,
    attachments = parseAttachmentBytes(this.attachment)
)

private fun parseAttachmentBytes(bytes: com.google.protobuf.ByteString): List<PinAttachment> {
    if (bytes.isEmpty) return emptyList()
    return try {
        val list = com.mezon.mezon.api.MessageAttachmentList.parseFrom(bytes)
        if (list.attachmentsCount == 0) return emptyList()
        list.attachmentsList.map { a ->
            PinAttachment(
                url = a.url,
                filename = a.filename,
                filetype = a.filetype,
                width = a.width,
                height = a.height,
                thumbnail = a.thumbnail,
                size = a.size,
                duration = a.duration
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun parseAttachmentJson(jsonStr: String): List<PinAttachment> {
    if (jsonStr.isBlank()) return emptyList()
    return try {
        val arr = try {
            val obj = org.json.JSONObject(jsonStr)
            obj.optJSONArray("attachments") ?: org.json.JSONArray().apply { put(obj) }
        } catch (_: Exception) {
            try { org.json.JSONArray(jsonStr) } catch (_: Exception) { return emptyList() }
        }
        (0 until arr.length()).map { i ->
            val a = arr.getJSONObject(i)
            val thumb = a.optString("thumbnail").ifBlank { a.optString("thumb") }
            PinAttachment(
                url = a.optString("url"),
                filename = a.optString("filename"),
                filetype = a.optString("filetype"),
                width = a.optInt("width"),
                height = a.optInt("height"),
                thumbnail = thumb,
                size = a.optInt("size"),
                duration = a.optInt("duration")
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}
