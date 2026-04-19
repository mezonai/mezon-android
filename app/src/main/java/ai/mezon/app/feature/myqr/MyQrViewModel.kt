package ai.mezon.app.feature.myqr

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class MyQrTab { PROFILE, TRANSFER }

data class UserInfo(
    val avatarUrl: String,
    val username: String,
    val displayName: String
)

data class MyQrState(
    val activeTab: MyQrTab = MyQrTab.PROFILE,
    val isGenerating: Boolean = false,
    val qrProfileUri: String? = null,
    val qrTransferUri: String? = null,
    val userInfo: UserInfo,
    val walletBalance: String = ""
)

class MyQrViewModel(
    private val generateQr: suspend (String, Int) -> Bitmap,
    private val userInfoProvider: () -> UserInfo,
    private val walletBalanceProvider: () -> String
) : ViewModel() {
    private val _state = MutableStateFlow(
        MyQrState(
            userInfo = userInfoProvider(),
            walletBalance = walletBalanceProvider()
        )
    )
    val state: StateFlow<MyQrState> = _state

    fun onTabChanged(tab: MyQrTab) {
        _state.value = _state.value.copy(activeTab = tab)
        // TODO: trigger QR generation for selected tab
    }

    fun onDownload() {
        // TODO: implement download QR
    }

    fun onShare() {
        // TODO: implement share QR
    }
}

