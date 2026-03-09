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

    suspend fun refreshSession(): Result<StoredSession> =
        withContext(ioDispatcher) {
            runCatching { sessionManager.refresh() }
        }

    suspend fun logout(): Result<Unit> = withContext(ioDispatcher) {
        runCatching { sessionManager.clearSession() }
    }
}
