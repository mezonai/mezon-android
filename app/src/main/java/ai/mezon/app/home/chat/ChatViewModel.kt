package ai.mezon.app.home.chat

import ai.mezon.app.network.MezonSocket
import ai.mezon.app.network.channelTypeToStreamMode
import ai.mezon.app.notification.ActiveChannelTracker
import ai.mezon.app.notification.NotificationHelper
import ai.mezon.app.util.buildTextContent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ChatUiState {
    data object Loading : ChatUiState
    data class Success(
        val messages: List<MessageEntity>,
        val hasMoreTop: Boolean,
        val hasMoreBottom: Boolean,
        val isLoadingMore: Boolean,
        val loadMoreError: String? = null
    ) : ChatUiState
    data class Error(val message: String) : ChatUiState
}

sealed interface ChatIntent {
    data class Load(val channelId: Long, val clanId: Long = 0L) : ChatIntent
    data class LoadMoreTop(val channelId: Long, val clanId: Long = 0L) : ChatIntent
    data class LoadMoreBottom(val channelId: Long, val clanId: Long = 0L) : ChatIntent
    data class SendMessage(val text: String) : ChatIntent
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val repository: ChatRepository,
    private val messageStore: MessageStore,
    private val mezonSocket: MezonSocket,
    private val notificationHelper: NotificationHelper,
    private val activeChannelTracker: ActiveChannelTracker
) : ViewModel() {

    private val _channelId = MutableStateFlow<Long?>(null)
    private val _clanId = MutableStateFlow(0L)
    private val _channelType = MutableStateFlow(0)
    private val _isLoadingMore = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)
    private val _loadMoreError = MutableStateFlow<String?>(null)

    val uiState: StateFlow<ChatUiState> = _channelId
        .flatMapLatest { channelId ->
            if (channelId == null) return@flatMapLatest flowOf(ChatUiState.Loading as ChatUiState)
            combine(
                messageStore.getChannelFlow(channelId),
                _isLoadingMore,
                _error,
                _loadMoreError
            ) { channelState, isLoadingMore, error, loadMoreError ->
                when {
                    error != null -> ChatUiState.Error(error)
                    channelState.messages.isEmpty() && !isLoadingMore -> ChatUiState.Loading
                    else -> ChatUiState.Success(
                        messages = channelState.messages,
                        hasMoreTop = channelState.hasMoreTop,
                        hasMoreBottom = channelState.hasMoreBottom,
                        isLoadingMore = isLoadingMore,
                        loadMoreError = loadMoreError
                    )
                }
            }
        }
        .catch { emit(ChatUiState.Error(it.message ?: "Unknown error")) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ChatUiState.Loading)

    fun onIntent(intent: ChatIntent) {
        when (intent) {
            is ChatIntent.Load -> load(intent.channelId, intent.clanId)
            is ChatIntent.LoadMoreTop -> loadMoreTop(intent.channelId, intent.clanId)
            is ChatIntent.LoadMoreBottom -> loadMoreBottom(intent.channelId, intent.clanId)
            is ChatIntent.SendMessage -> sendMessage(intent.text)
        }
    }

    fun setChannelType(channelType: Int) {
        _channelType.value = channelType
    }

    override fun onCleared() {
        super.onCleared()
        activeChannelTracker.clear()
    }

    private fun load(channelId: Long, clanId: Long) {
        _channelId.value = channelId
        _clanId.value = clanId
        _error.value = null
        activeChannelTracker.setActive(channelId)
        notificationHelper.cancelNotification(channelId.toInt())
        viewModelScope.launch {
            repository.loadMessages(channelId, clanId)
                .onFailure { err ->
                    Log.e(TAG, "Failed to load messages", err)
                    _error.value = err.message ?: "Failed to load messages"
                }
        }
    }

    private fun loadMoreTop(channelId: Long, clanId: Long) {
        if (_isLoadingMore.value) return
        val oldest = messageStore.getMessages(channelId).firstOrNull()?.id ?: return
        _isLoadingMore.value = true
        _loadMoreError.value = null
        viewModelScope.launch {
            repository.loadMoreTop(channelId, clanId, oldest)
                .onFailure { err ->
                    Log.e(TAG, "loadMoreTop failed", err)
                    _loadMoreError.value = err.message
                }
            _isLoadingMore.value = false
        }
    }

    private fun loadMoreBottom(channelId: Long, clanId: Long) {
        if (_isLoadingMore.value) return
        val newest = messageStore.getMessages(channelId).lastOrNull()?.id ?: return
        _isLoadingMore.value = true
        _loadMoreError.value = null
        viewModelScope.launch {
            repository.loadMoreBottom(channelId, clanId, newest)
                .onFailure { err ->
                    Log.e(TAG, "loadMoreBottom failed", err)
                    _loadMoreError.value = err.message
                }
            _isLoadingMore.value = false
        }
    }

    private fun sendMessage(text: String) {
        val channelId = _channelId.value ?: return
        val clanId = _clanId.value
        val channelType = _channelType.value
        val mode = channelTypeToStreamMode(channelType)
        val isPublic = channelType != ai.mezon.app.network.CHANNEL_TYPE_DM

        val content = buildTextContent(text)

        viewModelScope.launch {
            try {
                mezonSocket.writeChatMessage(
                    clanId = clanId,
                    channelId = channelId,
                    mode = mode,
                    isPublic = isPublic,
                    content = content
                )
                Log.d(TAG, "Message sent: channelId=$channelId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message", e)
            }
        }
    }

    companion object {
        private const val TAG = "ChatViewModel"
    }
}
