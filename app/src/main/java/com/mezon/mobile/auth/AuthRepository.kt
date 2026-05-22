package com.mezon.mobile.auth

import android.util.Log
import com.mezon.mobile.BuildConfig
import com.mezon.mobile.core.StartupCache
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.notification.FcmRepository
import com.mezon.mobile.session.SessionManager
import com.mezon.mobile.session.StoredSession
import com.mezon.mobile.wallet.WalletCacheStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val api: MezonApi,
    private val sessionManager: SessionManager,
    private val fcmRepository: FcmRepository,
    private val walletCacheStore: WalletCacheStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "AuthRepository"
    }

    val sessionFlow: Flow<StoredSession?> = sessionManager.sessionFlow

    suspend fun loginWithEmail(email: String, password: String): Result<StoredSession> =
        withContext(ioDispatcher) {
            runCatching {
                val response = api.authenticateEmail(
                    gatewayUrl = BuildConfig.MEZON_GATEWAY_URL,
                    email = email,
                    password = password
                )
                val stored = StoredSession(
                    token = response.token,
                    refreshToken = response.refreshToken,
                    apiUrl = response.apiUrl,
                    wsUrl = response.wsUrl,
                    userId = response.userId,
                    idToken = response.idToken
                )
                sessionManager.clearSession()
                walletCacheStore.clear()
                sessionManager.saveSession(stored)
                StartupCache.needsUsernameSetup = false
                stored
            }
        }

    suspend fun requestEmailOTP(email: String): Result<String> =
        withContext(ioDispatcher) {
            runCatching {
                val response = api.authenticateEmailOTP(
                    gatewayUrl = BuildConfig.MEZON_GATEWAY_URL,
                    email = email,
                    vars = mapOf("m" to "true")
                )
                response.reqId
            }
        }

    suspend fun requestSmsOTP(phone: String): Result<String> =
        withContext(ioDispatcher) {
            runCatching {
                val response = api.authenticateSmsOTP(
                    gatewayUrl = BuildConfig.MEZON_GATEWAY_URL,
                    phone = phone,
                    vars = mapOf("m" to "true")
                )
                response.reqId
            }
        }

    suspend fun confirmOTP(reqId: String, otpCode: String): Result<StoredSession> =
        withContext(ioDispatcher) {
            runCatching {
                val session = api.confirmAuthenticateOTP(
                    gatewayUrl = BuildConfig.MEZON_GATEWAY_URL,
                    reqId = reqId,
                    otpCode = otpCode
                )
                val stored = StoredSession(
                    token = session.token,
                    refreshToken = session.refreshToken,
                    apiUrl = session.apiUrl,
                    wsUrl = session.wsUrl,
                    userId = session.userId,
                    idToken = session.idToken
                )
                sessionManager.clearSession()
                StartupCache.needsUsernameSetup = session.created == true
                walletCacheStore.clear()
                sessionManager.saveSession(stored)
                stored
            }
        }

    suspend fun updateUsername(username: String): Result<StoredSession> =
        withContext(ioDispatcher) {
            runCatching {
                val current = sessionManager.requireValidSession()
                val r = api.updateUsername(current.apiUrl, current.token, username)
                if (r.token.isBlank() || r.refreshToken.isBlank()) {
                    throw IllegalStateException("UpdateUsername did not return session tokens")
                }
                val userIdStr = if (r.userId != 0L) r.userId.toString() else current.userId
                val merged = StoredSession(
                    token = r.token,
                    refreshToken = r.refreshToken,
                    apiUrl = r.apiUrl.ifEmpty { current.apiUrl },
                    wsUrl = r.wsUrl.ifEmpty { current.wsUrl },
                    userId = userIdStr,
                    idToken = r.idToken.ifEmpty { current.idToken },
                    isRemember = current.isRemember
                )
                walletCacheStore.clear()
                sessionManager.saveSession(merged)
                StartupCache.needsUsernameSetup = false
                merged
            }
        }

    suspend fun refreshSession(): Result<StoredSession> =
        withContext(ioDispatcher) {
            runCatching { sessionManager.refresh() }
        }

    suspend fun logout(): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            fcmRepository.deleteToken()
            sessionManager.clearSession()
        }
    }

    suspend fun confirmLoginByQr(loginId: Long): Result<Unit> =
        withContext(ioDispatcher) {
            runCatching {
                val currentSession = sessionManager.requireValidSession()
                val response = api.confirmLoginRequest(
                    gatewayUrl = BuildConfig.MEZON_GATEWAY_URL,
                    token = currentSession.token,
                    loginId = loginId
                )
                val merged = StoredSession(
                    token = response.token.ifBlank { currentSession.token },
                    refreshToken = response.refreshToken.ifBlank { currentSession.refreshToken },
                    apiUrl = response.apiUrl.ifBlank { currentSession.apiUrl },
                    wsUrl = response.wsUrl.ifBlank { currentSession.wsUrl },
                    userId = response.userId.ifBlank { currentSession.userId },
                    idToken = response.idToken.ifBlank { currentSession.idToken },
                    isRemember = currentSession.isRemember
                )
                val identityOrTokensChanged =
                    response.token.isNotBlank() ||
                        response.refreshToken.isNotBlank() ||
                        (response.userId.isNotBlank() && response.userId != currentSession.userId)
                if (identityOrTokensChanged) {
                    walletCacheStore.clear()
                }
                sessionManager.saveSession(merged)
                Unit
            }.onFailure { e ->
                Log.e(
                    TAG,
                    "confirmLoginByQr failed loginId=$loginId gatewayUrl=${BuildConfig.MEZON_GATEWAY_URL}",
                    e
                )
            }
        }
}
