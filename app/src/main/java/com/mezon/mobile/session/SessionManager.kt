package com.mezon.mobile.session

import com.mezon.mobile.core.StartupCache
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.network.NetworkMonitor
import com.mezon.mobile.network.UnauthorizedException
import android.util.Base64
import android.util.Log
import java.io.IOException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class StoredSession(
    val token: String,
    val refreshToken: String,
    val apiUrl: String,
    val wsUrl: String,
    val userId: String,
    val isRemember: Boolean = false
)

@Singleton
class SessionManager @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val api: MezonApi,
    private val networkMonitor: NetworkMonitor,
    @ApplicationScope private val appScope: CoroutineScope
) {
    companion object {
        private const val TAG = "SessionManager"
        private const val MAX_REFRESH_RETRIES = 5
        private const val TOKEN_EXPIRY_BUFFER_SEC = 60
    }

    val sessionFlow: Flow<StoredSession?> = dataStore.data.map { prefs ->
        val token = prefs[SessionKeys.TOKEN] ?: return@map null
        StoredSession(
            token = token,
            refreshToken = prefs[SessionKeys.REFRESH_TOKEN] ?: "",
            apiUrl = prefs[SessionKeys.API_URL] ?: "",
            wsUrl = prefs[SessionKeys.WS_URL] ?: "",
            userId = prefs[SessionKeys.USER_ID] ?: "",
            isRemember = prefs[SessionKeys.IS_REMEMBER] ?: false
        )
    }

    private val _sessionExpired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val sessionExpired: SharedFlow<Unit> = _sessionExpired.asSharedFlow()

    private val refreshMutex = Mutex()
    private var activeRefresh: Deferred<StoredSession>? = null
    private var lastRefreshToken: String = ""
    private var failCount: Int = 0

    fun isTokenExpired(token: String): Boolean {
        return try {
            val parts = token.split(".")
            if (parts.size != 3) return true
            val payload = String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP))
            val json = JSONObject(payload)
            val exp = json.getLong("exp")
            val now = System.currentTimeMillis() / 1000
            now >= (exp - TOKEN_EXPIRY_BUFFER_SEC)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse JWT expiry", e)
            true
        }
    }

    suspend fun requireValidSession(): StoredSession {
        val session = sessionFlow.first()
            ?: throw SessionExpiredException("No session")
        if (!isTokenExpired(session.token)) return session
        if (!networkMonitor.isOnline.value) {
            Log.w(TAG, "Token expired but offline — returning cached session")
            return session
        }
        return refresh()
    }

    suspend fun refresh(): StoredSession {
        val deferred = refreshMutex.withLock {
            activeRefresh?.takeIf { it.isActive } ?: appScope.async { doRefresh() }
                .also { activeRefresh = it }
        }
        return deferred.await()
    }

    private suspend fun doRefresh(): StoredSession {
        val current = sessionFlow.first()
            ?: throw SessionExpiredException("No session to refresh")

        if (!networkMonitor.isOnline.value) {
            Log.w(TAG, "Offline during refresh — returning cached session")
            return current
        }

        if (lastRefreshToken == current.refreshToken && failCount >= MAX_REFRESH_RETRIES) {
            Log.e(TAG, "Max retries ($MAX_REFRESH_RETRIES) with same refresh token")
            emitSessionExpired()
            throw SessionExpiredException("Session refresh failed: max retries with same token")
        }

        try {
            val protoSession = api.sessionRefresh(
                apiUrl = current.apiUrl,
                refreshToken = current.refreshToken,
                isRemember = current.isRemember
            )

            val newSession = StoredSession(
                token = protoSession.token,
                refreshToken = protoSession.refreshToken,
                apiUrl = protoSession.apiUrl.ifEmpty { current.apiUrl },
                wsUrl = protoSession.wsUrl.ifEmpty { current.wsUrl },
                userId = current.userId,
                isRemember = current.isRemember
            )

            saveSession(newSession)
            lastRefreshToken = newSession.refreshToken
            failCount = 0
            Log.d(TAG, "Session refreshed successfully")
            return newSession
        } catch (e: SessionExpiredException) {
            throw e
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: UnauthorizedException) {
            incrementFailCount(current.refreshToken)
            Log.e(TAG, "Session refresh 401/403 (failCount=$failCount/$MAX_REFRESH_RETRIES)", e)
            emitSessionExpired()
            throw SessionExpiredException("Session refresh failed: ${e.message}")
        } catch (e: IOException) {
            Log.w(TAG, "Network error during refresh, not logging out", e)
            throw e
        } catch (e: Exception) {
            incrementFailCount(current.refreshToken)
            Log.w(TAG, "Server error during refresh (failCount=$failCount/$MAX_REFRESH_RETRIES)", e)
            throw IOException("Session refresh server error: ${e.message}", e)
        } finally {
            refreshMutex.withLock {
                activeRefresh = null
            }
        }
    }

    private fun incrementFailCount(refreshToken: String) {
        if (lastRefreshToken == refreshToken) failCount++ else { lastRefreshToken = refreshToken; failCount = 1 }
    }

    private fun emitSessionExpired() {
        failCount = 0
        lastRefreshToken = ""
        _sessionExpired.tryEmit(Unit)
    }

    suspend fun saveSession(session: StoredSession) {
        StartupCache.hasSession = true
        dataStore.edit { prefs ->
            prefs[SessionKeys.TOKEN] = session.token
            prefs[SessionKeys.REFRESH_TOKEN] = session.refreshToken
            prefs[SessionKeys.API_URL] = session.apiUrl
            prefs[SessionKeys.WS_URL] = session.wsUrl
            prefs[SessionKeys.USER_ID] = session.userId
            prefs[SessionKeys.IS_REMEMBER] = session.isRemember
        }
    }

    suspend fun <T> withAutoRefresh(block: suspend (StoredSession) -> T): T {
        val session = requireValidSession()
        return try {
            block(session)
        } catch (_: com.mezon.mobile.network.UnauthorizedException) {
            if (!networkMonitor.isOnline.value) throw IOException("Offline, cannot refresh")
            Log.d(TAG, "Got 401, refreshing and retrying...")
            val refreshed = refresh()
            block(refreshed)
        }
    }

    suspend fun clearSession() {
        StartupCache.hasSession = false
        dataStore.edit { it.clear() }
    }
}

class SessionExpiredException(message: String) : RuntimeException(message)
