package com.mezon.mobile.qr

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.home.profile.AccountController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import dagger.hilt.android.lifecycle.HiltViewModel

enum class MyQrTab { PROFILE, TRANSFER }

data class UserInfo(
    val avatarUrl: String,
    val username: String,
    val displayName: String
)

data class MyQrState(
    val activeTab: MyQrTab = MyQrTab.PROFILE,
    val isGenerating: Boolean = false,
    val qrProfileBitmap: Bitmap? = null,
    val qrTransferBitmap: Bitmap? = null,
    val userInfo: UserInfo = UserInfo("", "", ""),
    val walletBalance: String = ""
)

sealed interface MyQrIntent {
    data class TabChanged(val tab: MyQrTab) : MyQrIntent
    data object Download : MyQrIntent
    data object Share : MyQrIntent
    data object Back : MyQrIntent
}

sealed interface MyQrEvent {
    data object Download : MyQrEvent
    data object Share : MyQrEvent
    data object Back : MyQrEvent
}

@HiltViewModel
class MyQrViewModel @Inject constructor(
    private val accountController: AccountController,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {
    private val _state = MutableStateFlow(MyQrState())
    val state: StateFlow<MyQrState> = _state

    private val _event = Channel<MyQrEvent>(Channel.BUFFERED)
    val event = _event.receiveAsFlow()

    init {
        viewModelScope.launch {
            accountController.accountInfo.collect { info ->
                val userInfo = UserInfo(
                    avatarUrl = info.avatarUrl,
                    username = info.username,
                    displayName = info.displayName
                )
                _state.value = _state.value.copy(userInfo = userInfo, walletBalance = info.balance)
                generateQrs(userInfo, info.balance, info.userId)
            }
        }
    }

    fun onIntent(intent: MyQrIntent) {
        when (intent) {
            is MyQrIntent.TabChanged -> {
                _state.value = _state.value.copy(activeTab = intent.tab)
            }
            MyQrIntent.Download -> _event.trySend(MyQrEvent.Download)
            MyQrIntent.Share -> _event.trySend(MyQrEvent.Share)
            MyQrIntent.Back -> _event.trySend(MyQrEvent.Back)
        }
    }

    private fun generateQrs(userInfo: UserInfo, balance: String, userId: Long) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isGenerating = true)
            val profileBitmap = withContext(ioDispatcher) {
                val value = buildProfileQrValue(
                    com.mezon.mobile.BuildConfig.MEZON_REDIRECT_URI,
                    userInfo.username,
                    ProfilePayload(
                        id = userId,
                        avatar = userInfo.avatarUrl,
                        name = userInfo.displayName
                    )
                )
                generateQrBitmap(value, 400)
            }
            val transferBitmap = withContext(ioDispatcher) {
                val value = buildTransferPayload(userInfo.username, userId)
                generateQrBitmap(value, 220)
            }
            _state.value = _state.value.copy(
                qrProfileBitmap = profileBitmap,
                qrTransferBitmap = transferBitmap,
                walletBalance = balance,
                isGenerating = false
            )
        }
    }
}


