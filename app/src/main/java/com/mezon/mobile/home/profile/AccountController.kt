package com.mezon.mobile.home.profile

import android.content.ContentResolver
import android.net.Uri
import com.mezon.mezon.api.Friend
import com.mezon.mobile.BuildConfig
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.network.ApiCacheTracker
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.network.SocketEventDispatcher
import com.mezon.mobile.network.apiCacheKey
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



data class AccountInfo(
    val userId: Long = 0L,
    val username: String = "",
    val displayName: String = "",
    val aboutMe: String = "",
    val email: String = "",
    val phoneNumber: String = "",
    val avatarUrl: String = "",
    val logo: String = "",
    val passwordSetted: Boolean = false
)

@Singleton
class AccountController @Inject constructor(
    private val api: MezonApi,
    private val sessionManager: SessionManager,
    private val userController: UserController,
    private val dispatcher: SocketEventDispatcher,
    private val notificationCenter: NotificationCenter,
    private val cacheTracker: ApiCacheTracker,
    @ApplicationScope private val appScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    private val _accountInfo = MutableStateFlow(AccountInfo())
    val accountInfo: StateFlow<AccountInfo> = _accountInfo.asStateFlow()

    private val _blockedUsers = MutableStateFlow<List<Friend>>(emptyList())
    val blockedUsers: StateFlow<List<Friend>> = _blockedUsers.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        appScope.launch {
            sessionManager.sessionFlow.first { it != null }
            loadAccountInternal()
        }
        appScope.launch { observeProfileUpdates() }
    }

    private suspend fun observeProfileUpdates() {
        dispatcher.userProfileUpdatedEvents.collect { event ->
            val current = _accountInfo.value
            if (current.userId == 0L || event.userId != current.userId) return@collect
            val updated = current.copy(
                displayName = event.displayName.ifEmpty { current.displayName },
                avatarUrl = event.avatar.ifEmpty { current.avatarUrl }
            )
            _accountInfo.value = updated
            userController.updateFromAccount(updated)
            notificationCenter.postNotificationOnMainThread(NotificationCenter.accountInfoLoaded)
        }
    }

    private val cacheKey = apiCacheKey("getAccount")

    private suspend fun loadAccountInternal(noCache: Boolean = false) {
        try {
            val session = withContext(ioDispatcher) { sessionManager.sessionFlow.first() } ?: return
            if (!noCache && _accountInfo.value.userId != 0L &&
                cacheTracker.shouldCall(cacheKey, noCache = false) == ApiCacheTracker.ShouldCall.SKIP
            ) return
            val account = withContext(ioDispatcher) { api.getAccount(session.apiUrl, session.token) }
            val user = account.user
            val info = AccountInfo(
                userId = user.id,
                username = user.username,
                displayName = user.displayName,
                aboutMe = user.aboutMe,
                email = account.email,
                phoneNumber = user.phoneNumber,
                avatarUrl = user.avatarUrl,
                logo = account.logo,
                passwordSetted = account.passwordSetted
            )
            _accountInfo.value = info
            cacheTracker.markCalled(cacheKey)
            userController.updateFromAccount(info)
            notificationCenter.postNotificationOnMainThread(NotificationCenter.accountInfoLoaded)
        } catch (e: Exception) {
        }
    }

    fun loadAccount(noCache: Boolean = false) {
        appScope.launch { loadAccountInternal(noCache) }
    }

    fun loadBlockedUsers() {
        appScope.launch {
            try {
                val session = withContext(ioDispatcher) { sessionManager.sessionFlow.first() } ?: return@launch
                val friendList = withContext(ioDispatcher) { api.listFriends(session.apiUrl, session.token, state = 3) }
                _blockedUsers.value = friendList.friendsList
                notificationCenter.postNotificationOnMainThread(NotificationCenter.blockedUsersLoaded)
            } catch (e: Exception) {
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
                cacheTracker.invalidate(cacheKey)
                loadAccount()
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onResult(true, "")
                }
            } catch (e: Exception) {
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
        logoUrl: String = "",
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
                        displayName,
                        avatarUrl.ifEmpty { null },
                        aboutMe,
                        logoUrl
                    )
                }
                val current = _accountInfo.value
                val updated = current.copy(
                    displayName = displayName,
                    avatarUrl = avatarUrl.ifEmpty { current.avatarUrl },
                    aboutMe = aboutMe,
                    logo = logoUrl
                )
                _accountInfo.value = updated
                cacheTracker.invalidate(cacheKey)
                userController.updateFromAccount(updated)
                notificationCenter.postNotificationOnMainThread(NotificationCenter.accountInfoLoaded)
                withContext(kotlinx.coroutines.Dispatchers.Main) { onResult(true, "") }
            } catch (e: Exception) {
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
                withContext(kotlinx.coroutines.Dispatchers.Main) { onResult(false) }
            }
        }
    }

    fun uploadAvatar(
        uri: Uri,
        contentResolver: ContentResolver,
        onResult: (success: Boolean, avatarUrl: String) -> Unit
    ) {
        appScope.launch {
            try {
                val session = withContext(ioDispatcher) { sessionManager.sessionFlow.first() } ?: return@launch
                val fileBytes = withContext(ioDispatcher) {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: throw RuntimeException("Cannot read file")

                val timestamp = System.currentTimeMillis() / 1000
                val filename = "${timestamp}_avatar.jpg"
                val mimeType = contentResolver.getType(uri) ?: "image/jpeg"

                val cdnUrl = withContext(ioDispatcher) {
                    val presignResult = api.uploadAttachmentFile(
                        session.apiUrl, session.token,
                        filename, mimeType, fileBytes.size, 400, 400
                    )
                    api.putFileToPresignedUrl(presignResult.url, fileBytes, mimeType)
                    "${BuildConfig.MEZON_BASE_IMG_URL}/${presignResult.filename}"
                }
                withContext(kotlinx.coroutines.Dispatchers.Main) { onResult(true, cdnUrl) }
            } catch (e: Exception) {
                withContext(kotlinx.coroutines.Dispatchers.Main) { onResult(false, "") }
            }
        }
    }
}
