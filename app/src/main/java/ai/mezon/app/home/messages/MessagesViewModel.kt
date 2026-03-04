package ai.mezon.app.home.messages

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface MessagesUiState {
    data object Loading : MessagesUiState
    data object Empty : MessagesUiState
    data class Success(val messages: List<DirectMessage>) : MessagesUiState
    data class Error(val message: String) : MessagesUiState
}

sealed interface MessagesIntent {
    data object Load : MessagesIntent
    data object Refresh : MessagesIntent
}

@HiltViewModel
class MessagesViewModel @Inject constructor(
    private val repository: DirectRepository,
    private val directStore: DirectStore
) : ViewModel() {

    private val _hasLoadedOnce = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)

    val uiState: StateFlow<MessagesUiState> = combine(
        directStore.directs,
        _hasLoadedOnce,
        _error
    ) { list, hasLoaded, error ->
        when {
            error != null && list.isEmpty() -> MessagesUiState.Error(error)
            list.isNotEmpty() -> MessagesUiState.Success(list)
            hasLoaded -> MessagesUiState.Empty
            else -> MessagesUiState.Loading
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MessagesUiState.Loading
    )

    init {
        onIntent(MessagesIntent.Load)
    }

    fun onIntent(intent: MessagesIntent) {
        when (intent) {
            MessagesIntent.Load -> fetchDirects(isRefresh = false)
            MessagesIntent.Refresh -> fetchDirects(isRefresh = true)
        }
    }

    private fun fetchDirects(isRefresh: Boolean) {
        if (isRefresh) _error.value = null
        viewModelScope.launch {
            repository.fetchAndSync()
                .onSuccess { _hasLoadedOnce.value = true }
                .onFailure { error ->
                    Log.e(TAG, "Failed to ${if (isRefresh) "refresh" else "load"} directs", error)
                    _hasLoadedOnce.value = true
                    if (directStore.directs.value.isEmpty()) {
                        _error.value = error.message ?: "Failed to load messages"
                    }
                }
        }
    }

    companion object {
        private const val TAG = "MessagesViewModel"
    }
}
