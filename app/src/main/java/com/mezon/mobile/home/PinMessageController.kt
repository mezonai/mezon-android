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
)

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
            synchronized(this) {
                val list = pinMessagesByChannel.getOrPut(channelId) { ArrayList() }
                if (list.none { it.messageId == data.messageId }) {
                    list.add(0, data)
                }
            }
            apiCacheTracker.invalidate(apiCacheKey("pinMessages", channelId))
            notificationCenter.postNotificationOnMainThread(
                NotificationCenter.pinMessageAdded, channelId
            )
        }
    }

    private suspend fun observeUnpinEvents() {
        socketEventDispatcher.unpinMessageEvents.collect { event ->
            val channelId = event.channelId
            val messageId = event.messageId
            synchronized(this) {
                pinMessagesByChannel[channelId]?.removeAll { it.messageId == messageId }
            }
            apiCacheTracker.invalidate(apiCacheKey("pinMessages", channelId))
            notificationCenter.postNotificationOnMainThread(
                NotificationCenter.pinMessageRemoved, channelId, messageId
            )
        }
    }

    fun getPinMessages(channelId: Long): List<PinMessageData> {
        synchronized(this) {
            return pinMessagesByChannel[channelId]?.toList() ?: emptyList()
        }
    }

    fun isPinned(channelId: Long, messageId: Long): Boolean {
        synchronized(this) {
            return pinMessagesByChannel[channelId]?.any { it.messageId == messageId } == true
        }
    }

    fun loadPinMessages(channelId: Long, clanId: Long, noCache: Boolean = false) {
        val cacheKey = apiCacheKey("pinMessages", channelId)
        if (apiCacheTracker.shouldCall(cacheKey, noCache = noCache) == ApiCacheTracker.ShouldCall.SKIP) {
            notificationCenter.postNotificationOnMainThread(
                NotificationCenter.pinMessagesDidLoad, channelId
            )
            return
        }
        appScope.launch {
            try {
                sessionManager.withAutoRefresh { session ->
                    val response = withContext(ioDispatcher) {
                        api.listPinMessages(session.apiUrl, session.token, channelId, clanId)
                    }
                    val items = response.pinMessagesListList.map { it.toPinMessageData() }
                    synchronized(this@PinMessageController) {
                        pinMessagesByChannel[channelId] = ArrayList(items)
                    }
                    apiCacheTracker.markCalled(cacheKey)
                    notificationCenter.postNotificationOnMainThread(
                        NotificationCenter.pinMessagesDidLoad, channelId
                    )
                }
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
        appScope.launch {
            try {
                sessionManager.withAutoRefresh { session ->
                    withContext(ioDispatcher) {
                        api.createPinMessage(session.apiUrl, session.token, channelId, clanId, messageId)
                    }
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
                Log.d(TAG, "Pinned message=$messageId in channel=$channelId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to pin message=$messageId", e)
            }
        }
    }

    fun unpinMessage(channelId: Long, clanId: Long, messageId: Long) {
        appScope.launch {
            try {
                sessionManager.withAutoRefresh { session ->
                    withContext(ioDispatcher) {
                        api.deletePinMessage(session.apiUrl, session.token, messageId, channelId, clanId)
                    }
                }
                synchronized(this@PinMessageController) {
                    pinMessagesByChannel[channelId]?.removeAll { it.messageId == messageId }
                }
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.pinMessageRemoved, channelId, messageId
                )
                Log.d(TAG, "Unpinned message=$messageId from channel=$channelId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unpin message=$messageId", e)
            }
        }
    }
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
            PinAttachment(
                url = a.optString("url"),
                filename = a.optString("filename"),
                filetype = a.optString("filetype"),
                width = a.optInt("width"),
                height = a.optInt("height"),
                thumbnail = a.optString("thumbnail"),
                size = a.optInt("size"),
                duration = a.optInt("duration")
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}
