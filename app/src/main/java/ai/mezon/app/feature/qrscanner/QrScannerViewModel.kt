package ai.mezon.app.feature.qrscanner

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface QrScannerAction {
    data class DeepLink(val value: String) : QrScannerAction
    data class Invite(val inviteId: String) : QrScannerAction
    data class Profile(val username: String, val data: String?) : QrScannerAction
    data class LuckyMoney(val luckyMoneyId: String) : QrScannerAction
    data class Transfer(val rawJson: String) : QrScannerAction
    data class Login(val loginId: String) : QrScannerAction
    object Invalid : QrScannerAction
}

sealed interface QrScannerTab { object PROFILE; object TRANSFER }

data class QrScannerState(
    val hasPermission: Boolean = false,
    val scanningEnabled: Boolean = false,
    val isNavigating: Boolean = false,
    val valueCode: String? = null,
    val isSuccess: Boolean = false,
    val cameraRestartKey: Int = 0,
    val lastScanAtMs: Long = 0L
)

class QrScannerViewModel(
    private val parseQr: (String) -> QrScannerAction,
    private val confirmLogin: suspend (String) -> Boolean
) : ViewModel() {
    private val _state = MutableStateFlow(QrScannerState())
    val state: StateFlow<QrScannerState> = _state

    fun onQrScanned(value: String) {
        if (!_state.value.scanningEnabled) return
        val action = parseQr(value)
        // TODO: handle action (navigation, set valueCode, etc)
    }

    fun onConfirmLogin() {
        val code = _state.value.valueCode ?: return
        viewModelScope.launch {
            val result = confirmLogin(code)
            _state.value = _state.value.copy(isSuccess = result)
        }
    }

    fun onOpenGallery() {
        // TODO: implement gallery QR scan
    }

    fun onPermissionResult(granted: Boolean) {
        _state.value = _state.value.copy(hasPermission = granted)
    }
}

