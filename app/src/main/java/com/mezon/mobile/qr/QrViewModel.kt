package com.mezon.mobile.qr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.session.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class QrViewModel @Inject constructor(
    private val sessionManager: SessionManager,
    private val api: MezonApi
) : ViewModel() {
    private val _state = MutableStateFlow<QrUiState>(QrUiState.Content())
    val state: StateFlow<QrUiState> = _state

    private val _event = Channel<QrEvent>(Channel.BUFFERED)
    val event = _event.receiveAsFlow()

    fun onIntent(intent: QrIntent) {
        when (intent) {
            is QrIntent.StartScan -> {
                _event.trySend(QrEvent.RequestCameraPermission)
            }
            is QrIntent.PermissionResult -> {
                val current = contentState()
                _state.value = current.copy(
                    hasPermission = intent.granted,
                    scanningEnabled = intent.granted,
                    cameraRestartKey = if (intent.granted) current.cameraRestartKey + 1 else current.cameraRestartKey
                )
                if (!intent.granted) {
                    _event.trySend(QrEvent.OpenSettings)
                }
            }
            is QrIntent.ScanResult -> {
                handleScanResult(intent.result)
            }
            is QrIntent.ShowMyQr -> {
                _event.trySend(QrEvent.ShowMyQr)
            }
            is QrIntent.PickFromGallery -> {
                _event.trySend(QrEvent.OpenGallery)
            }
            is QrIntent.ConfirmLogin -> {
                confirmLogin()
            }
            is QrIntent.CancelLogin -> {
                val current = contentState()
                _state.value = current.copy(valueCode = null, isSuccess = false, scanningEnabled = true)
            }
            is QrIntent.OnResume -> {
                val current = contentState()
                _state.value = current.copy(scanningEnabled = current.hasPermission, isNavigating = false, cameraRestartKey = current.cameraRestartKey + 1)
            }
            is QrIntent.Cancel -> {
                _event.trySend(QrEvent.NavigateBack)
            }
        }
    }

    private fun handleScanResult(value: String) {
        val current = contentState()
        if (!current.scanningEnabled || current.isNavigating) return
        val now = System.currentTimeMillis()
        if (now - current.lastScanAtMs < 5000L) return
        if (value.isBlank()) {
            _event.trySend(QrEvent.ShowError(com.mezon.mobile.R.string.qr_invalid_code))
            return
        }
        val action = parseQrValue(value)
        _state.value = current.copy(scanningEnabled = false, lastScanAtMs = now)
        when (action) {
            is QrAction.DeepLink -> {
                _event.trySend(QrEvent.NavigateDeepLink(action.value))
                markNavigating()
            }
            is QrAction.Invite -> {
                _event.trySend(QrEvent.NavigateInvite(action.inviteId))
                markNavigating()
            }
            is QrAction.Profile -> {
                _event.trySend(QrEvent.NavigateProfile(action.username, action.data))
                markNavigating()
            }
            is QrAction.LuckyMoney -> {
                _event.trySend(QrEvent.NavigateLuckyMoney(action.luckyMoneyId))
                markNavigating()
            }
            is QrAction.Transfer -> {
                _event.trySend(QrEvent.NavigateTransfer(action.rawJson))
                markNavigating()
            }
            is QrAction.Login -> {
                _state.value = contentState().copy(valueCode = action.loginId, isSuccess = false)
            }
            is QrAction.Invalid -> {
                _event.trySend(QrEvent.ShowError(com.mezon.mobile.R.string.qr_invalid_code))
            }
        }
        viewModelScope.launch {
            delay(5000L)
            val updated = contentState()
            _state.value = updated.copy(scanningEnabled = updated.hasPermission && updated.valueCode == null && !updated.isNavigating)
        }
    }

    private fun markNavigating() {
        val current = contentState()
        _state.value = current.copy(isNavigating = true, scanningEnabled = false)
    }

    private fun confirmLogin() {
        val code = contentState().valueCode ?: return
        viewModelScope.launch {
            val loginId = code.toLongOrNull()
            if (loginId == null) {
                _event.trySend(QrEvent.ShowError(com.mezon.mobile.R.string.qr_error))
                return@launch
            }
            val result = runCatching {
                sessionManager.withAutoRefresh { session ->
                    val response = api.confirmLoginRequest(session.apiUrl, session.token, loginId)
                    response.idToken.isNotEmpty() && response.userId != 0L
                }
            }.getOrElse { false }
            val current = contentState()
            if (result) {
                _state.value = current.copy(isSuccess = true)
                _event.trySend(QrEvent.ShowSuccess(com.mezon.mobile.R.string.qr_success_login))
            } else {
                _event.trySend(QrEvent.ShowError(com.mezon.mobile.R.string.qr_error))
            }
        }
    }

    private fun contentState(): QrUiState.Content = _state.value as QrUiState.Content
}

