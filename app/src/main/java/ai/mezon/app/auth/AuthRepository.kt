package ai.mezon.app.auth

import ai.mezon.app.BuildConfig
import ai.mezon.app.di.IoDispatcher
import ai.mezon.app.network.MezonApi
import ai.mezon.app.session.SessionManager
import ai.mezon.app.session.StoredSession
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

    suspend fun logout() = withContext(ioDispatcher) {
        sessionManager.clearSession()
    }
}
