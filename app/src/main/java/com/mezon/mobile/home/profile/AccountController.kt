package com.mezon.mobile.home.profile

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import com.mezon.mobile.BuildConfig
import com.mezon.mobile.R
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.StartupCache
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.network.ApiCacheTracker
import com.mezon.mobile.network.MmnApi
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.network.SocketEventDispatcher
import com.mezon.mobile.network.UnauthorizedException
import com.mezon.mobile.network.apiCacheKey
import com.mezon.mobile.session.SessionManager
import com.mezon.mobile.util.avatarImgproxyUrl
import com.mezon.mobile.util.AttachmentUploader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.math.BigInteger
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
    val passwordSetted: Boolean = false,
    val createTimeSeconds: Long = 0L,
    val userStatus: String = "",
    val onlineStatus: UserOnlineStatus = UserOnlineStatus.ONLINE,
    val balance: String = "0",
    val address: String = ""
)

private fun AccountInfo.hasColdStartScratch(): Boolean =
    userId != 0L || displayName.isNotEmpty() || avatarUrl.isNotEmpty() || logo.isNotEmpty()

private const val ACCOUNT_LOG = "AccountController"

@Singleton
class AccountController @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val api: MezonApi,
    private val mmnApi: MmnApi,
    private val sessionManager: SessionManager,
    private val userController: UserController,
    private val dispatcher: SocketEventDispatcher,
    private val notificationCenter: NotificationCenter,
    private val cacheTracker: ApiCacheTracker,
    private val mezonSocket: com.mezon.mobile.network.MezonSocket,
    @ApplicationScope private val appScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    private fun readBootstrappedAccountInfo(): AccountInfo {
        if (!StartupCache.hasSession) return AccountInfo()
        val userId = StartupCache.userId.toLongOrNull() ?: 0L
        val logo = StartupCache.cachedDmLogoUrl
        val displayName = StartupCache.cachedUserDisplayName
        val avatarUrl = StartupCache.cachedUserAvatarUrl
        if (userId == 0L && logo.isEmpty() && displayName.isEmpty() && avatarUrl.isEmpty()) return AccountInfo()
        return AccountInfo(userId = userId, displayName = displayName, avatarUrl = avatarUrl, logo = logo)
    }

    private fun persistAccountScratch(info: AccountInfo) {
        StartupCache.cachedDmLogoUrl = info.logo
        StartupCache.cachedUserDisplayName = info.displayName
        StartupCache.cachedUserAvatarUrl = info.avatarUrl
    }

    private val _accountInfo = MutableStateFlow(readBootstrappedAccountInfo())
    val accountInfo: StateFlow<AccountInfo> = _accountInfo.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        val boot = _accountInfo.value
        if (boot.hasColdStartScratch()) {
            userController.updateFromAccount(boot)
        }
        appScope.launch {
            sessionManager.sessionFlow.collectLatest { session ->
                Log.d(ACCOUNT_LOG, "sessionFlow emitted: session=${if (session != null) "non-null userId=${session.userId}" else "null"}")
                if (session != null) loadAccountInternal()
            }
        }
        appScope.launch { observeProfileUpdates() }
        appScope.launch { observeUserStatusEvents() }
        appScope.launch { observeCustomStatusEvents() }
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
            persistAccountScratch(updated)
            userController.updateFromAccount(updated)
            notificationCenter.postNotificationOnMainThread(NotificationCenter.accountInfoLoaded)
        }
    }

    private suspend fun observeUserStatusEvents() {
        dispatcher.userStatusEvents.collect { event ->
            val current = _accountInfo.value
            if (current.userId == 0L || event.userId != current.userId) return@collect
            val newOnlineStatus = event.customStatus
            if (newOnlineStatus.isNotEmpty()) {
                val updated = current.copy(onlineStatus = UserOnlineStatus.fromString(newOnlineStatus))
                _accountInfo.value = updated
                userController.updateFromAccount(updated)
                notificationCenter.postNotificationOnMainThread(NotificationCenter.accountInfoLoaded)
            }
        }
    }

    private suspend fun observeCustomStatusEvents() {
        dispatcher.customStatusEvents.collect { event ->
            val current = _accountInfo.value
            if (current.userId == 0L || event.userId != current.userId) return@collect
            val updated = current.copy(userStatus = event.status)
            _accountInfo.value = updated
            userController.updateFromAccount(updated)
            notificationCenter.postNotificationOnMainThread(NotificationCenter.accountInfoLoaded)
        }
    }

    private companion object {
        private const val ACCOUNT_CACHE_PREFIX = "getAccount"

        private val LINK_SMS_RPC_FAIL_REGEX = Regex(
            """RPC\s+LinkSms\s+failed\s+\((\d+)\):\s*(.*)""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
    }

    private fun extractLinkSmsErrorDetail(body: String): String {
        val t = body.trim()
        if (t.isEmpty()) return ""
        return try {
            val o = JSONObject(t)
            o.optString("message", "").trim()
                .ifEmpty { o.optString("error", "").trim() }
                .ifEmpty { o.optString("msg", "").trim() }
        } catch (_: Exception) {
            if (t.length <= 240 && !t.startsWith("<") && t.lines().size <= 3) t else ""
        }
    }

    private fun humanizeLinkPhoneFailure(e: Exception): String {
        if (e is UnauthorizedException) {
            return appContext.getString(R.string.phone_link_error_session)
        }
        when (e) {
            is java.net.UnknownHostException,
            is java.net.SocketTimeoutException,
            is java.net.ConnectException,
            is javax.net.ssl.SSLException -> return appContext.getString(R.string.common_error_connection_failed)
        }
        val raw = e.message?.trim().orEmpty()
        if (raw.isEmpty()) return appContext.getString(R.string.phone_link_failed)

        if (Regex("""RPC\s+LinkSms\s*:\s*401\s+Unauthorized""", RegexOption.IGNORE_CASE).containsMatchIn(raw)) {
            return appContext.getString(R.string.phone_link_error_session)
        }

        val match = LINK_SMS_RPC_FAIL_REGEX.find(raw)
        if (match != null) {
            val code = match.groupValues[1].toIntOrNull()
                ?: return appContext.getString(R.string.phone_link_failed)
            val detail = extractLinkSmsErrorDetail(match.groupValues[2])
            if (detail.isNotEmpty()) return detail

            return when (code) {
                400 -> appContext.getString(R.string.phone_link_error_bad_request)
                403 -> appContext.getString(R.string.phone_link_error_forbidden)
                404 -> appContext.getString(R.string.phone_link_error_not_found)
                429 -> appContext.getString(R.string.phone_link_error_rate_limit)
                in 500..599 -> appContext.getString(R.string.phone_link_error_server)
                else -> appContext.getString(R.string.phone_link_failed)
            }
        }

        return appContext.getString(R.string.phone_link_failed)
    }

    private fun scheduleDmLogoPrefetch(logoRaw: String) {
        if (logoRaw.isEmpty()) return
        val px = LayoutHelper.dp(42)
        val proxied = avatarImgproxyUrl(logoRaw, px).ifEmpty { logoRaw }
        MezonImageLoader.getInstance(appContext).load(proxied, px, px, onSuccess = {})
    }

    private suspend fun loadAccountInternal(noCache: Boolean = false) {
        Log.d(ACCOUNT_LOG, "loadAccountInternal called noCache=$noCache")
        try {
            sessionManager.withAutoRefresh { session ->
                val cacheKey = apiCacheKey(ACCOUNT_CACHE_PREFIX, session.userId)
                if (!noCache &&
                    cacheTracker.shouldCall(cacheKey, noCache = false) == ApiCacheTracker.ShouldCall.SKIP
                ) {
                    Log.d(ACCOUNT_LOG, "loadAccountInternal SKIP (cache valid) cacheKey=$cacheKey")
                    return@withAutoRefresh
                }
                coroutineScope {
                    Log.d(ACCOUNT_LOG, "loadAccountInternal CALL getAccount + getWalletBalance userId=${session.userId}")
                    val accountDeferred = async(ioDispatcher) { api.getAccount(session.apiUrl, session.token) }
                    val walletDeferred = async(ioDispatcher) {
                        runCatching { mmnApi.getWalletBalance(session.userId) }.getOrNull()
                    }
                    val account = accountDeferred.await()
                    val walletData = walletDeferred.await()
                    Log.d(ACCOUNT_LOG, "loadAccountInternal API done getAccount userId=${account.user.id} walletBalance=${walletData?.balance ?: "null"}")
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
                        passwordSetted = account.passwordSetted,
                        createTimeSeconds = user.createTimeSeconds.toLong(),
                        userStatus = user.userStatus,
                        onlineStatus = UserOnlineStatus.fromString(user.status),
                        balance = walletData?.balance ?: "0",
                        address = walletData?.address ?: ""
                    )

                    _accountInfo.value = info
                    syncUsernameGate(info)
                    cacheTracker.markCalled(cacheKey)
                    userController.updateFromAccount(info)
                    persistAccountScratch(info)
                    scheduleDmLogoPrefetch(info.logo)
                    notificationCenter.postNotificationOnMainThread(NotificationCenter.accountInfoLoaded)
                }
            }
        } catch (e: Exception) {
            Log.e(ACCOUNT_LOG, "loadAccountInternal failed (profile API or session)", e)
        }
    }

    fun loadAccount(noCache: Boolean = false) {
        appScope.launch { loadAccountInternal(noCache) }
    }

    private fun syncUsernameGate(info: AccountInfo) {
        val phone = info.phoneNumber.trim()
        if (phone.isEmpty()) return
        val needsSetup = usernameMatchesPhone(info.username, phone)
        val previouslyNeeded = StartupCache.needsUsernameSetup
        StartupCache.needsUsernameSetup = needsSetup
        if (needsSetup && !previouslyNeeded) {
            notificationCenter.postNotificationOnMainThread(NotificationCenter.needUsernameSetup)
        }
    }

    private fun usernameMatchesPhone(username: String, phone: String): Boolean {
        val u = username.trim().removePrefix("+")
        if (u.isEmpty()) return false
        return u == phone.removePrefix("+")
    }

    fun reduceBalanceLocally(deltaRaw: BigInteger) {
        if (deltaRaw <= BigInteger.ZERO) return
        val current = _accountInfo.value
        val currentRaw = runCatching { current.balance.toBigInteger() }.getOrNull() ?: return
        val nextRaw = (currentRaw - deltaRaw).coerceAtLeast(BigInteger.ZERO)
        val updated = current.copy(balance = nextRaw.toString())
        _accountInfo.value = updated
        userController.updateFromAccount(updated)
        notificationCenter.postNotificationOnMainThread(NotificationCenter.accountInfoLoaded)
    }

    fun linkEmail(email: String, onResult: (success: Boolean, reqId: String, errorMsg: String) -> Unit) {
        appScope.launch {
            _isLoading.value = true
            try {
                val bytes = sessionManager.withAutoRefresh { session ->
                    withContext(ioDispatcher) { api.linkEmail(session.apiUrl, session.token, email) }
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

    fun confirmLinkOTP(reqId: String, otpCode: String, contact: String, isPhone: Boolean, onResult: (success: Boolean, errorMsg: String) -> Unit) {
        appScope.launch {
            _isLoading.value = true
            try {
                sessionManager.withAutoRefresh { session ->
                    withContext(ioDispatcher) { api.confirmLinkOTP(session.apiUrl, session.token, reqId, otpCode) }
                }
                val updated = if (isPhone) {
                    _accountInfo.value.copy(phoneNumber = contact)
                } else {
                    _accountInfo.value.copy(email = contact)
                }
                _accountInfo.value = updated
                userController.updateFromAccount(updated)
                notificationCenter.postNotificationOnMainThread(NotificationCenter.accountInfoLoaded)
                cacheTracker.invalidateByPrefix(ACCOUNT_CACHE_PREFIX)
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
                val requestBytes = com.mezon.mezon.api.AccountMezon.newBuilder()
                    .setPhoneNumber(phoneNumber)
                    .build()
                    .toByteArray()
                val bytes = sessionManager.withAutoRefresh { session ->
                    withContext(ioDispatcher) {
                        api.linkSms(session.apiUrl, session.token, requestBytes)
                    }
                }
                val reqId = if (bytes.isNotEmpty()) {
                    com.mezon.mezon.api.LinkAccountConfirmRequest.parseFrom(bytes).reqId
                } else ""
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onResult(true, reqId, "")
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val errorMsg = withContext(ioDispatcher) { humanizeLinkPhoneFailure(e) }
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onResult(false, "", errorMsg)
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
                sessionManager.withAutoRefresh { session ->
                    withContext(ioDispatcher) { api.deleteAccount(session.apiUrl, session.token) }
                }
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
                sessionManager.withAutoRefresh { session ->
                    withContext(ioDispatcher) {
                        api.registrationEmail(session.apiUrl, session.token, email, newPassword, oldPassword)
                    }
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
                sessionManager.withAutoRefresh { session ->
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
                }
                val current = _accountInfo.value
                val updated = current.copy(
                    displayName = displayName,
                    avatarUrl = avatarUrl.ifEmpty { current.avatarUrl },
                    aboutMe = aboutMe,
                    logo = logoUrl
                )
                _accountInfo.value = updated
                cacheTracker.invalidateByPrefix(ACCOUNT_CACHE_PREFIX)
                userController.updateFromAccount(updated)
                persistAccountScratch(updated)
                scheduleDmLogoPrefetch(logoUrl)
                notificationCenter.postNotificationOnMainThread(NotificationCenter.accountInfoLoaded)
                withContext(kotlinx.coroutines.Dispatchers.Main) { onResult(true, "") }
            } catch (e: Exception) {
                withContext(kotlinx.coroutines.Dispatchers.Main) { onResult(false, e.message ?: "") }
            } finally {
                _isLoading.value = false
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
                val mimeType = contentResolver.getType(uri) ?: "image/jpeg"
                val fileBytes = withContext(ioDispatcher) {
                    com.mezon.mobile.util.AttachmentUploader.readUriBytesSafely(
                        contentResolver, uri, mimeType, appContext.cacheDir
                    )
                } ?: throw RuntimeException("Cannot read file or over size limit")

                val timestamp = System.currentTimeMillis() / 1000
                val filename = "${timestamp}_avatar.jpg"

                val cdnUrl = sessionManager.withAutoRefresh { session ->
                    withContext(ioDispatcher) {
                        AttachmentUploader.uploadAttachmentBytes(
                            api,
                            session.apiUrl,
                            session.token,
                            filename,
                            mimeType,
                            fileBytes,
                            400,
                            400,
                            BuildConfig.MEZON_BASE_IMG_URL,
                        ).cdnUrl
                    }
                }
                withContext(kotlinx.coroutines.Dispatchers.Main) { onResult(true, cdnUrl) }
            } catch (e: Exception) {
                withContext(kotlinx.coroutines.Dispatchers.Main) { onResult(false, "") }
            }
        }
    }

    fun loadClanProfile(
        clanId: Long,
        onResult: (nickName: String, avatar: String) -> Unit
    ) {
        appScope.launch {
            try {
                val profile = sessionManager.withAutoRefresh { session ->
                    withContext(ioDispatcher) {
                        api.getUserProfileOnClan(session.apiUrl, session.token, clanId)
                    }
                }
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onResult(profile.nickName, profile.avatar)
                }
            } catch (_: Exception) {
                withContext(kotlinx.coroutines.Dispatchers.Main) { onResult("", "") }
            }
        }
    }

    fun updateClanProfile(
        clanId: Long,
        nickName: String,
        avatar: String,
        onResult: (success: Boolean, errorMsg: String) -> Unit
    ) {
        appScope.launch {
            _isLoading.value = true
            try {
                sessionManager.withAutoRefresh { session ->
                    withContext(ioDispatcher) {
                        api.updateClanProfile(
                            session.apiUrl,
                            session.token,
                            clanId,
                            nickName,
                            avatar
                        )
                    }
                }
                withContext(kotlinx.coroutines.Dispatchers.Main) { onResult(true, "") }
            } catch (e: Exception) {
                withContext(kotlinx.coroutines.Dispatchers.Main) { onResult(false, e.message ?: "") }
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateCustomStatus(clanId: Long, status: String, timeReset: Int, noClear: Boolean, onResult: (success: Boolean) -> Unit) {
        appScope.launch {
            _isLoading.value = true
            try {
                mezonSocket.writeCustomStatus(clanId, status, timeReset, noClear)
                val current = _accountInfo.value
                val updated = current.copy(userStatus = status.trim())
                _accountInfo.value = updated
                userController.updateFromAccount(updated)
                notificationCenter.postNotificationOnMainThread(NotificationCenter.accountInfoLoaded)
                withContext(kotlinx.coroutines.Dispatchers.Main) { onResult(true) }
            } catch (e: Exception) {
                withContext(kotlinx.coroutines.Dispatchers.Main) { onResult(false) }
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateOnlineStatus(status: String) {
        appScope.launch {
            try {
                sessionManager.withAutoRefresh { session ->
                    api.updateUserStatus(session.apiUrl, session.token, status)
                }
                val current = _accountInfo.value
                val updated = current.copy(onlineStatus = UserOnlineStatus.fromString(status))
                _accountInfo.value = updated
                userController.updateFromAccount(updated)
                cacheTracker.invalidateByPrefix(ACCOUNT_CACHE_PREFIX)
                notificationCenter.postNotificationOnMainThread(NotificationCenter.accountInfoLoaded)
            } catch (e: Exception) {
            }
        }
    }

    fun cleanup() {
        StartupCache.clearAccountProfileScratch()
        _accountInfo.value = AccountInfo()
        _isLoading.value = false
    }
}