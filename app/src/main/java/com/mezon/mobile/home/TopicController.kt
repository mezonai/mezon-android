package com.mezon.mobile.home

import android.util.Log
import android.util.LongSparseArray
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.home.chat.SdTopicEntity
import com.mezon.mobile.home.chat.toSdTopicEntity
import com.mezon.mobile.home.chat.toSdTopicEntityFromEvent
import com.mezon.mobile.home.clans.ChannelController
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.network.SocketEventDispatcher
import com.mezon.mobile.session.SessionManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TopicController"
private const val TOPICS_PAGE_SIZE = 50

@Singleton
class TopicController @Inject constructor(
    private val api: MezonApi,
    private val sessionManager: SessionManager,
    private val dispatcher: SocketEventDispatcher,
    private val notificationCenter: NotificationCenter,
    private val channelController: dagger.Lazy<ChannelController>,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope
) {
    private val topics = ArrayList<SdTopicEntity>()
    private val topicsDict = LongSparseArray<SdTopicEntity>()
    private var currentClanId = 0L
    private var isLoading = false
    private var clanBound = false

    init {
        appScope.launch { observeSdTopicEvents() }
        appScope.launch { observeTopicMessages() }
    }

    fun cleanup() {
        synchronized(this) {
            topics.clear()
            topicsDict.clear()
            currentClanId = 0L
            clanBound = false
        }
    }

    fun resetForClan(clanId: Long) {
        if (clanId == 0L) return
        synchronized(this) {
            if (currentClanId == clanId && topics.isNotEmpty()) return
            if (currentClanId != 0L && currentClanId != clanId) {
                channelController.get().clearSdTopicsForClan(currentClanId)
            }
            topics.clear()
            topicsDict.clear()
            currentClanId = 0L
            clanBound = false
            isLoading = false
        }
    }

    fun getTopics(): List<SdTopicEntity> = synchronized(this) { ArrayList(topics) }

    fun findTopic(topicId: Long): SdTopicEntity? = synchronized(this) { topicsDict.get(topicId) }

    fun loadTopics(clanId: Long, forceRefresh: Boolean = false) {
        if (clanId == 0L) return
        if (!forceRefresh && clanId == currentClanId && topics.isNotEmpty()) {
            notificationCenter.postNotificationOnMainThread(NotificationCenter.topicsNeedReload)
            return
        }
        if (isLoading && clanId == currentClanId) return
        isLoading = true
        currentClanId = clanId
        appScope.launch(ioDispatcher) {
            try {
                sessionManager.withAutoRefresh { session ->
                    val response = api.listSdTopic(
                        session.apiUrl,
                        session.token,
                        clanId,
                        TOPICS_PAGE_SIZE
                    )
                    val items = response.topicsList.map { it.toSdTopicEntity() }
                        .sortedByDescending { it.lastSentTimestampSeconds.takeIf { ts -> ts > 0L } ?: it.updateTimeSeconds }
                    synchronized(this@TopicController) {
                        topics.clear()
                        topics.addAll(items)
                        topicsDict.clear()
                        items.forEach { topicsDict.put(it.id, it) }
                        clanBound = true
                    }
                    channelController.get().registerSdTopicChannels(items)
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadTopics failed clanId=$clanId", e)
            } finally {
                isLoading = false
                notificationCenter.postNotificationOnMainThread(NotificationCenter.topicsNeedReload)
            }
        }
    }

    suspend fun fetchTopicDetail(topicId: Long): SdTopicEntity? {
        return try {
            sessionManager.withAutoRefresh { session ->
                val detail = api.getTopicDetail(session.apiUrl, session.token, topicId)
                detail.toSdTopicEntity()
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchTopicDetail failed topicId=$topicId", e)
            null
        }
    }

    suspend fun createTopic(
        clanId: Long,
        parentChannelId: Long,
        messageId: Long
    ): SdTopicEntity? {
        if (clanId == 0L || parentChannelId == 0L || messageId == 0L) return null
        return try {
            val created = sessionManager.withAutoRefresh { session ->
                api.createSdTopic(
                    apiUrl = session.apiUrl,
                    token = session.token,
                    clanId = clanId,
                    channelId = parentChannelId,
                    messageId = messageId
                )
            }.toSdTopicEntity()
            synchronized(this) {
                if (currentClanId == clanId) {
                    val index = topics.indexOfFirst { it.id == created.id }
                    if (index >= 0) topics.removeAt(index)
                    topics.add(0, created)
                    topicsDict.put(created.id, created)
                    clanBound = true
                }
            }
            channelController.get().registerSdTopicChannel(created)
            notificationCenter.postNotificationOnMainThread(NotificationCenter.topicsNeedReload)
            created
        } catch (e: Exception) {
            Log.e(TAG, "createTopic failed clanId=$clanId parentChannelId=$parentChannelId messageId=$messageId", e)
            null
        }
    }

    private suspend fun observeSdTopicEvents() {
        dispatcher.sdTopicEvents.collect { event ->
            val clanId = event.clanId
            if (clanId == 0L) return@collect
            val entity = event.toSdTopicEntityFromEvent()
            var shouldReload = false
            synchronized(this) {
                if (!clanBound || clanId != currentClanId) return@collect
                val existing = topicsDict.get(entity.id)
                if (existing != null) {
                    val index = topics.indexOfFirst { it.id == entity.id }
                    if (index >= 0) {
                        topics.removeAt(index)
                        topics.add(0, entity)
                        topicsDict.put(entity.id, entity)
                        shouldReload = true
                    }
                } else {
                    topics.add(0, entity)
                    topicsDict.put(entity.id, entity)
                    shouldReload = true
                }
            }
            if (shouldReload) {
                channelController.get().registerSdTopicChannel(entity)
                notificationCenter.postNotificationOnMainThread(NotificationCenter.topicsNeedReload)
            }
        }
    }

    private suspend fun observeTopicMessages() {
        dispatcher.channelMessages.collect { message ->
            val topicId = message.topicId
            if (topicId == 0L) return@collect
            val clanId = message.clanId
            if (clanId == 0L) return@collect
            var shouldReload = false
            var updated: SdTopicEntity? = null
            synchronized(this) {
                if (!clanBound || clanId != currentClanId) return@collect
                val existing = topicsDict.get(topicId) ?: return@collect
                updated = existing.copy(
                    lastSentMessageId = message.messageId,
                    lastSentSenderId = message.senderId,
                    lastSentContent = message.content,
                    lastSentTimestampSeconds = message.createTimeSeconds.toLong()
                )
                val index = topics.indexOfFirst { it.id == topicId }
                if (index >= 0) {
                    topics.removeAt(index)
                    topics.add(0, updated!!)
                    topicsDict.put(topicId, updated!!)
                    shouldReload = true
                }
            }
            if (shouldReload && updated != null) {
                channelController.get().registerSdTopicChannel(updated!!)
                notificationCenter.postNotificationOnMainThread(NotificationCenter.topicsNeedReload)
            }
        }
    }
}
