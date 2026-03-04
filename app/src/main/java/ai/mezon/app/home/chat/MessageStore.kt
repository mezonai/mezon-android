package ai.mezon.app.home.chat

import ai.mezon.app.data.db.MessageDao
import ai.mezon.app.di.ApplicationScope
import ai.mezon.app.network.SocketEventDispatcher
import ai.mezon.app.session.SessionManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val CODE_CHAT_UPDATE = 2
private const val CODE_CHAT_REMOVE = 3
private const val MAX_MESSAGES_PER_CHANNEL = 200
private const val MAX_CACHED_CHANNELS = 30

data class ChannelState(
    val messages: List<MessageEntity> = emptyList(),
    val hasMoreTop: Boolean = false,
    val hasMoreBottom: Boolean = false
)

@Singleton
class MessageStore @Inject constructor(
    private val messageDao: MessageDao,
    private val socketEventDispatcher: SocketEventDispatcher,
    private val sessionManager: SessionManager,
    @ApplicationScope private val appScope: CoroutineScope
) {
    companion object {
        private const val TAG = "MessageStore"
    }

    private val channels = ConcurrentHashMap<Long, MutableStateFlow<ChannelState>>()
    private val channelAccessOrder = java.util.LinkedList<Long>()

    init {
        appScope.launch { observeIncomingMessages() }
    }

    fun getChannelFlow(channelId: Long): StateFlow<ChannelState> =
        getOrCreate(channelId).asStateFlow()

    fun getMessages(channelId: Long): List<MessageEntity> =
        channels[channelId]?.value?.messages ?: emptyList()

    fun setMessages(channelId: Long, messages: List<MessageEntity>, hasMoreTop: Boolean, hasMoreBottom: Boolean) {
        getOrCreate(channelId).value = ChannelState(messages, hasMoreTop, hasMoreBottom)
        appScope.launch { messageDao.upsertAll(messages) }
    }

    fun prependMessages(channelId: Long, older: List<MessageEntity>, hasMoreTop: Boolean) {
        getOrCreate(channelId).update { state ->
            val existingIds = state.messages.mapTo(HashSet()) { it.id }
            val deduped = older.filter { it.id !in existingIds }
            state.copy(
                messages = deduped + state.messages,
                hasMoreTop = hasMoreTop
            )
        }
        appScope.launch { messageDao.upsertAll(older) }
    }

    fun appendMessages(channelId: Long, newer: List<MessageEntity>, hasMoreBottom: Boolean) {
        getOrCreate(channelId).update { state ->
            val existingIds = state.messages.mapTo(HashSet()) { it.id }
            val deduped = newer.filter { it.id !in existingIds }
            val combined = state.messages + deduped
            val trimmed = if (combined.size > MAX_MESSAGES_PER_CHANNEL)
                combined.drop(combined.size - MAX_MESSAGES_PER_CHANNEL)
            else combined
            state.copy(messages = trimmed, hasMoreBottom = hasMoreBottom)
        }
        appScope.launch { messageDao.upsertAll(newer) }
    }

    fun evictChannel(channelId: Long) {
        channels.remove(channelId)
    }

    suspend fun loadFromDb(channelId: Long): List<MessageEntity> {
        val cached = messageDao.getLatestByChannel(channelId)
        if (cached.isNotEmpty()) {
            getOrCreate(channelId).value = ChannelState(
                messages = cached,
                hasMoreTop = true,
                hasMoreBottom = false
            )
        }
        return cached
    }

    private fun getOrCreate(channelId: Long): MutableStateFlow<ChannelState> =
        channels.getOrPut(channelId) {
            evictLruIfNeeded()
            MutableStateFlow(ChannelState())
        }.also { touchAccessOrder(channelId) }

    @Synchronized
    private fun touchAccessOrder(channelId: Long) {
        channelAccessOrder.remove(channelId)
        channelAccessOrder.addLast(channelId)
    }

    @Synchronized
    private fun evictLruIfNeeded() {
        while (channels.size >= MAX_CACHED_CHANNELS && channelAccessOrder.isNotEmpty()) {
            val oldest = channelAccessOrder.removeFirst()
            channels.remove(oldest)
        }
    }

    private suspend fun observeIncomingMessages() {
        val currentUserId = sessionManager.sessionFlow
            .first { it != null }
            ?.userId
            ?.toLongOrNull() ?: 0L

        socketEventDispatcher.channelMessages.collect { msg ->
            val entity = msg.toMessageEntity(currentUserId)
            when (msg.code) {
                CODE_CHAT_UPDATE -> updateMessage(entity)
                CODE_CHAT_REMOVE -> removeMessage(msg.channelId, msg.messageId)
                else -> addNewMessage(entity)
            }
        }
    }

    private fun addNewMessage(entity: MessageEntity) {
        val flow = channels[entity.channelId]
        if (flow == null) {
            appScope.launch { messageDao.upsert(entity) }
            return
        }
        flow.update { state ->
            if (state.messages.any { it.id == entity.id }) return@update state
            val combined = state.messages + entity
            val trimmed = if (combined.size > MAX_MESSAGES_PER_CHANNEL)
                combined.drop(combined.size - MAX_MESSAGES_PER_CHANNEL)
            else combined
            state.copy(messages = trimmed)
        }
        appScope.launch { messageDao.upsert(entity) }
    }

    private fun updateMessage(entity: MessageEntity) {
        channels[entity.channelId]?.update { state ->
            state.copy(messages = state.messages.map { if (it.id == entity.id) entity else it })
        }
        appScope.launch { messageDao.upsert(entity) }
    }

    private fun removeMessage(channelId: Long, messageId: Long) {
        channels[channelId]?.update { state ->
            state.copy(messages = state.messages.filter { it.id != messageId })
        }
        appScope.launch { messageDao.delete(channelId, messageId) }
    }
}
