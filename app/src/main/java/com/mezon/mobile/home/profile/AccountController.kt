package com.mezon.mobile.home.profile

import android.util.Log
import com.mezon.mezon.api.Friend
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.session.SessionManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AccountController"

data class AccountInfo(
    val userId: Long = 0L,
    val username: String = "",
    val displayName: String = "",
    val email: String = "",
    val phoneNumber: String = "",
    val avatarUrl: String = "",
    val passwordSetted: Boolean = false
)

@Singleton
class AccountController @Inject constructor(
    private val api: MezonApi,
    private val sessionManager: SessionManager,
    private val notificationCenter: NotificationCenter,
    @ApplicationScope private val appScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    private val _accountInfo = MutableStateFlow(AccountInfo())
    val accountInfo: StateFlow<AccountInfo> = _accountInfo.asStateFlow()

    private val _blockedUsers = MutableStateFlow<List<Friend>>(emptyList())
    val blockedUsers: StateFlow<List<Friend>> = _blockedUsers.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun loadAccount() {
        appScope.launch {
            try {
                val session = withContext(ioDispatcher) { sessionManager.sessionFlow.first() } ?: return@launch
                val account = withContext(ioDispatcher) { api.getAccount(session.apiUrl, session.token) }
                val user = account.user
                _accountInfo.value = AccountInfo(
                    userId = user.id,
                    username = user.username,
                    displayName = user.displayName,
                    email = account.email,
                    phoneNumber = user.phoneNumber,
                    avatarUrl = user.avatarUrl,
                    passwordSetted = account.passwordSetted
                )
                notificationCenter.postNotificationOnMainThread(NotificationCenter.accountInfoLoaded)
            } catch (e: Exception) {
                Log.e(TAG, "loadAccount failed", e)
            }
        }
    }

    fun loadBlockedUsers() {
        appScope.launch {
            try {
                val session = withContext(ioDispatcher) { sessionManager.sessionFlow.first() } ?: return@launch
                val friendList = withContext(ioDispatcher) { api.listFriends(session.apiUrl, session.token, state = 3) }
                _blockedUsers.value = friendList.friendsList
                notificationCenter.postNotificationOnMainThread(NotificationCenter.blockedUsersLoaded)
            } catch (e: Exception) {
                Log.e(TAG, "loadBlockedUsers failed", e)
            }
        }
    }

    fun linkEmail(email: String, onResult: (success: Boolean, reqId: String, errorMsg: String) -> Unit) {
        appScope.launch {
            _isLoading.value = true
            try {
                val session = withContext(ioDispatcher) { sessionManager.sessionFlow.first() } ?: return@launch
                val bytes = withContext(ioDispatcher) { api.linkEmail(session.apiUrl, session.token, email) }
                val reqId = if (bytes.isNotEmpty()) {
                    com.mezon.mezon.api.LinkAccountConfirmRequest.parseFrom(bytes).reqId
                } else ""
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onResult(true, reqId, "")
                }
            } catch (e: Exception) {
                Log.e(TAG, "linkEmail failed", e)
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onResult(false, "", e.message ?: "")
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun confirmLinkOTP(reqId: String, otpCode: String, onResult: (success: Boolean, errorMsg: String) -> Unit) {
        appScope.launch {
            _isLoading.value = true
            try {
                val session = withContext(ioDispatcher) { sessionManager.sessionFlow.first() } ?: return@launch
                withContext(ioDispatcher) { api.confirmLinkOTP(session.apiUrl, session.token, reqId, otpCode) }
                loadAccount()
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onResult(true, "")
                }
            } catch (e: Exception) {
                Log.e(TAG, "confirmLinkOTP failed", e)
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onResult(false, e.message ?: "")
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun linkPhone(phoneNumber: String, onResult: (success: Boolean, reqId: String, errorMsg: String) -> Unit) {
        appScope.launch {
            _isLoading.value = true
            try {
                val session = withContext(ioDispatcher) { sessionManager.sessionFlow.first() } ?: return@launch
                val requestBytes = com.mezon.mezon.api.AccountMezon.newBuilder()
                    .setPhoneNumber(phoneNumber)
                    .build()
                    .toByteArray()
                val bytes = withContext(ioDispatcher) {
                    api.rpc(session.apiUrl, session.token, "LinkSms", requestBytes)
                }
                val reqId = if (bytes.isNotEmpty()) {
                    com.mezon.mezon.api.LinkAccountConfirmRequest.parseFrom(bytes).reqId
                } else ""
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onResult(true, reqId, "")
                }
            } catch (e: Exception) {
                Log.e(TAG, "linkPhone failed", e)
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onResult(false, "", e.message ?: "")
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun deleteAccount(onResult: (success: Boolean) -> Unit) {
        appScope.launch {
            _isLoading.value = true
            try {
                val session = withContext(ioDispatcher) { sessionManager.sessionFlow.first() } ?: return@launch
                withContext(ioDispatcher) { api.deleteAccount(session.apiUrl, session.token) }
                withContext(kotlinx.coroutines.Dispatchers.Main) { onResult(true) }
            } catch (e: Exception) {
                Log.e(TAG, "deleteAccount failed", e)
                withContext(kotlinx.coroutines.Dispatchers.Main) { onResult(false) }
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun setPassword(
        email: String,
        newPassword: String,
        oldPassword: String = "",
        onResult: (success: Boolean, errorMsg: String) -> Unit
    ) {
        appScope.launch {
            _isLoading.value = true
            try {
                val session = withContext(ioDispatcher) { sessionManager.sessionFlow.first() } ?: return@launch
                withContext(ioDispatcher) {
                    api.registrationEmail(session.apiUrl, session.token, email, newPassword, oldPassword)
                }
                _accountInfo.value = _accountInfo.value.copy(passwordSetted = true)
                withContext(kotlinx.coroutines.Dispatchers.Main) { onResult(true, "") }
            } catch (e: Exception) {
                Log.e(TAG, "setPassword failed", e)
                withContext(kotlinx.coroutines.Dispatchers.Main) { onResult(false, e.message ?: "") }
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateProfile(
        displayName: String,
        avatarUrl: String,
        aboutMe: String,
        onResult: (success: Boolean, errorMsg: String) -> Unit
    ) {
        appScope.launch {
            _isLoading.value = true
            try {
                val session = withContext(ioDispatcher) { sessionManager.sessionFlow.first() } ?: return@launch
                withContext(ioDispatcher) {
                    api.updateAccount(
                        session.apiUrl,
                        session.token,
                        displayName = displayName.ifEmpty { null },
                        avatarUrl = avatarUrl.ifEmpty { null },
                        aboutMe = aboutMe.ifEmpty { null }
                    )
                }
                val current = _accountInfo.value
                _accountInfo.value = current.copy(
                    displayName = displayName.ifEmpty { current.displayName },
                    avatarUrl = avatarUrl.ifEmpty { current.avatarUrl }
                )
                notificationCenter.postNotificationOnMainThread(NotificationCenter.accountInfoLoaded)
                withContext(kotlinx.coroutines.Dispatchers.Main) { onResult(true, "") }
            } catch (e: Exception) {
                Log.e(TAG, "updateProfile failed", e)
                withContext(kotlinx.coroutines.Dispatchers.Main) { onResult(false, e.message ?: "") }
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun unblockUser(userId: Long, username: String, onResult: (success: Boolean) -> Unit) {
        appScope.launch {
            try {
                val session = withContext(ioDispatcher) { sessionManager.sessionFlow.first() } ?: return@launch
                withContext(ioDispatcher) {
                    api.unblockFriends(session.apiUrl, session.token, listOf(userId), listOf(username))
                }
                _blockedUsers.value = _blockedUsers.value.filter { it.user.id != userId }
                notificationCenter.postNotificationOnMainThread(NotificationCenter.blockedUsersLoaded)
                withContext(kotlinx.coroutines.Dispatchers.Main) { onResult(true) }
            } catch (e: Exception) {
                Log.e(TAG, "unblockUser failed", e)
                withContext(kotlinx.coroutines.Dispatchers.Main) { onResult(false) }
            }
        }
    }
}
