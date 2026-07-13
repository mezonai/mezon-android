package com.mezon.mobile.home

import android.util.Log
import android.util.LongSparseArray
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.home.chat.MessageEntity
import com.mezon.mobile.home.chat.SdTopicEntity
import com.mezon.mobile.home.chat.toSdTopicEntity
import com.mezon.mobile.home.chat.toSdTopicEntityFromEvent
import com.mezon.mobile.home.clans.ChannelController
import com.mezon.mobile.network.ApiCacheTracker
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.network.SocketEventDispatcher
import com.mezon.mobile.network.apiCacheKey
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
    private val cacheTracker: ApiCacheTracker,
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
        val cacheKey = apiCacheKey("listSdTopic", clanId)
        if (!forceRefresh) {
            val canSkipFetch = synchronized(this) {
                clanId == currentClanId &&
                    (topics.isNotEmpty() ||
                        (clanBound && cacheTracker.shouldCall(cacheKey) == ApiCacheTracker.ShouldCall.SKIP))
            }
            if (canSkipFetch) {
                notificationCenter.postNotificationOnMainThread(NotificationCenter.topicsNeedReload)
                return
            }
        }
        synchronized(this) {
            if (isLoading && clanId == currentClanId) return
            isLoading = true
            currentClanId = clanId
        }
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
                    cacheTracker.markCalled(cacheKey)
                    channelController.get().registerSdTopicChannels(items)
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadTopics failed clanId=$clanId", e)
            } finally {
                synchronized(this@TopicController) { isLoading = false }
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
        messageId: Long,
        rootMessage: MessageEntity? = null
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
            }.toSdTopicEntity().let { created ->
                rootMessage?.let { root ->
                    created.copy(
                        messageId = created.messageId.takeIf { it != 0L } ?: root.id,
                        content = root.content,
                        createTimeSeconds = root.timestampSeconds,
                        updateTimeSeconds = created.updateTimeSeconds.takeIf { it > 0L }
                            ?: root.timestampSeconds
                    )
                } ?: created
            }
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
            val incomingEntity = event.toSdTopicEntityFromEvent()
            var entity = incomingEntity
            var shouldReload = false
            synchronized(this) {
                if (!clanBound || clanId != currentClanId) return@collect
                val existing = topicsDict.get(incomingEntity.id)
                if (existing != null) {
                    entity = incomingEntity.copy(
                        creatorId = existing.creatorId,
                        messageId = existing.messageId,
                        channelId = existing.channelId,
                        content = existing.content.ifBlank { incomingEntity.content },
                        createTimeSeconds = existing.createTimeSeconds.takeIf { it > 0L }
                            ?: incomingEntity.createTimeSeconds,
                        lastSentMessageId = if (event.hasLastSentMessage()) {
                            incomingEntity.lastSentMessageId
                        } else {
                            existing.lastSentMessageId
                        },
                        lastSentSenderId = if (event.hasLastSentMessage()) {
                            incomingEntity.lastSentSenderId
                        } else {
                            existing.lastSentSenderId
                        },
                        lastSentContent = if (event.hasLastSentMessage()) {
                            incomingEntity.lastSentContent
                        } else {
                            existing.lastSentContent
                        },
                        lastSentTimestampSeconds = if (event.hasLastSentMessage()) {
                            incomingEntity.lastSentTimestampSeconds
                        } else {
                            existing.lastSentTimestampSeconds
                        }
                    )
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
                val next = existing.copy(
                    lastSentMessageId = message.messageId,
                    lastSentSenderId = message.senderId,
                    lastSentContent = message.content,
                    lastSentTimestampSeconds = message.createTimeSeconds.toLong()
                )
                if (next == existing && topics.firstOrNull()?.id == topicId) return@collect
                updated = next
                val index = topics.indexOfFirst { it.id == topicId }
                if (index >= 0) {
                    topics.removeAt(index)
                    topics.add(0, next)
                    topicsDict.put(topicId, next)
                    shouldReload = true
                }
            }
            if (shouldReload && updated != null) {
                channelController.get().registerSdTopicChannel(updated!!)
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.topicsNeedReload, updated!!, topicId
                )
            }
        }
    }
}
