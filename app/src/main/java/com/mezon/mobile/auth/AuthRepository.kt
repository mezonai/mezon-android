package com.mezon.mobile.auth

import com.mezon.mobile.BuildConfig
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.session.SessionManager
import com.mezon.mobile.session.StoredSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val api: MezonApi,
    private val sessionManager: SessionManager,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
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
                    userId = response.userId
                )
                sessionManager.saveSession(stored)
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
                    userId = session.userId
                )
                sessionManager.saveSession(stored)
                stored
            }
        }

    suspend fun refreshSession(): Result<StoredSession> =
        withContext(ioDispatcher) {
            runCatching { sessionManager.refresh() }
        }

    suspend fun logout(): Result<Unit> = withContext(ioDispatcher) {
        runCatching { sessionManager.clearSession() }
    }

    suspend fun confirmLoginByQr(loginId: Long): Result<com.mezon.mezon.api.Session> =
        withContext(ioDispatcher) {
            runCatching {
                api.confirmLoginRequest(
                    gatewayUrl = BuildConfig.MEZON_GATEWAY_URL,
                    loginId = loginId
                )
            }
        }
}
