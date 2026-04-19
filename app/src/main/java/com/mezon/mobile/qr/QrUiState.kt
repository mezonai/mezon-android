package com.mezon.mobile.qr

sealed interface QrUiState {
    data class Content(
        val hasPermission: Boolean = false,
        val scanningEnabled: Boolean = false,
        val isNavigating: Boolean = false,
        val valueCode: String? = null,
        val isSuccess: Boolean = false,
        val cameraRestartKey: Int = 0,
        val lastScanAtMs: Long = 0L
    ) : QrUiState
}

sealed interface QrIntent {
    data object StartScan : QrIntent
    data class PermissionResult(val granted: Boolean) : QrIntent
    data class ScanResult(val result: String) : QrIntent
    data object ShowMyQr : QrIntent
    data object PickFromGallery : QrIntent
    data object ConfirmLogin : QrIntent
    data object CancelLogin : QrIntent
    data object OnResume : QrIntent
    data object Cancel : QrIntent
}

sealed interface QrEvent {
    data class ShowError(val messageResId: Int) : QrEvent
    data class ShowSuccess(val messageResId: Int) : QrEvent
    data object RequestCameraPermission : QrEvent
    data object OpenGallery : QrEvent
    data object NavigateBack : QrEvent
    data object OpenSettings : QrEvent
    data object ShowMyQr : QrEvent
    data class NavigateDeepLink(val value: String) : QrEvent
    data class NavigateInvite(val inviteId: String) : QrEvent
    data class NavigateProfile(val username: String, val data: String?) : QrEvent
    data class NavigateLuckyMoney(val luckyMoneyId: String) : QrEvent
    data class NavigateTransfer(val rawJson: String) : QrEvent
}

