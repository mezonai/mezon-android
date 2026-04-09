package com.mezon.mobile.home.notifications

import android.util.Log
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.network.SocketEventDispatcher
import com.mezon.mobile.session.SessionManager
import com.mezon.mezon.rtapi.SdTopicEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TopicStore"

@Singleton
class TopicStore @Inject constructor(
    private val api: MezonApi,
    private val dispatcher: SocketEventDispatcher,
    private val sessionManager: SessionManager,
    private val notificationCenter: NotificationCenter,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope
) {
    private val _topics = MutableStateFlow<List<TopicEntity>>(emptyList())
    val topics: StateFlow<List<TopicEntity>> = _topics.asStateFlow()

    private val _loadingStatus = MutableStateFlow(LoadingStatus.NOT_LOADED)
    val loadingStatus: StateFlow<LoadingStatus> = _loadingStatus.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var currentClanId: Long = 0L

    init {
        appScope.launch {
            dispatcher.sdTopicEvents.collect { event -> handleSdTopicEvent(event) }
        }
    }

    private fun handleSdTopicEvent(event: SdTopicEvent) {
        if (event.clanId != currentClanId) return
        if (event.id == 0L) return
        if (event.channelId == 0L) return

        try {
            val msg = event.message
            val last = event.lastSentMessage
            val newTopic = TopicEntity(
                id = event.id.toString(),
                clanId = event.clanId,
                channelId = event.channelId,
                topicContentRaw = msg.content,
                senderId = msg.senderId,
                senderName = msg.username,
                senderAvatar = msg.avatar,
                createTimeSeconds = msg.createTimeSeconds.toLong(),
                messageId = msg.messageId,
                lastSentMessageContentRaw = last.content,
                lastSentMessageSenderId = last.senderId,
                lastSentMessageTimestampSeconds = last.timestampSeconds.toLong()
            )

            val existingTopic = _topics.value.find { it.id == newTopic.id }
            if (existingTopic != null) {
                _topics.update { list -> list.map { if (it.id == newTopic.id) newTopic else it } }
            } else {
                _topics.update { list -> listOf(newTopic) + list }
            }
            _error.value = null
            _loadingStatus.value = LoadingStatus.LOADED
            notificationCenter.postNotificationOnMainThread(NotificationCenter.topicsDidUpdate, currentClanId)
        } catch (e: Exception) {
            Log.e(TAG, "handleSdTopicEvent parse failed", e)
        }
    }

    fun setCurrentClan(clanId: Long) {
        if (currentClanId == clanId) return
        currentClanId = clanId
        _topics.value = emptyList()
        _loadingStatus.value = LoadingStatus.NOT_LOADED
        _error.value = null
        if (clanId != 0L) loadTopics()
    }

    fun loadTopics() {
        if (currentClanId == 0L) {
            return
        }
        _loadingStatus.value = LoadingStatus.LOADING
        _error.value = null

        appScope.launch {
            try {
                val result = sessionManager.withAutoRefresh { session ->
                    withContext(ioDispatcher) {
                        api.listSdTopics(session.apiUrl, session.token, currentClanId)
                    }
                }

                val topicsList = result.topicsList.mapNotNull { proto ->
                    try {
                        if (proto.id == 0L || proto.channelId == 0L) return@mapNotNull null
                        val last = proto.lastSentMessage
                        TopicEntity(
                            id = proto.id.toString(),
                            clanId = proto.clanId,
                            channelId = proto.channelId,
                            topicContentRaw = proto.content,
                            senderId = proto.creatorId,
                            senderName = "",
                            senderAvatar = "",
                            createTimeSeconds = proto.createTimeSeconds.toLong(),
                            messageId = proto.messageId,
                            lastSentMessageContentRaw = last.content,
                            lastSentMessageSenderId = last.senderId,
                            lastSentMessageTimestampSeconds = last.timestampSeconds.toLong()
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "skip invalid SdTopic row", e)
                        null
                    }
                }

                val sorted = topicsList.sortedByDescending { it.lastSentMessageTimestampSeconds }
                _topics.value = sorted
                _loadingStatus.value = LoadingStatus.LOADED
                notificationCenter.postNotificationOnMainThread(NotificationCenter.topicsDidLoad, currentClanId)

            } catch (e: Exception) {
                Log.e(TAG, "listSdTopics failed", e)
                _error.value = e.message?.takeIf { it.isNotBlank() }
                _loadingStatus.value = LoadingStatus.ERROR
                notificationCenter.postNotificationOnMainThread(NotificationCenter.topicsLoadError, currentClanId)
            }
        }
    }
}

enum class LoadingStatus {
    NOT_LOADED,
    LOADING,
    LOADED,
    ERROR
}
