package com.mezon.mobile.session

import com.mezon.mobile.core.StartupCache
import com.mezon.mobile.home.chat.poll.PollVotePersistence
import com.mezon.mobile.util.EmbedFormUtil
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.network.NetworkMonitor
import com.mezon.mobile.network.UnauthorizedException
import android.util.Base64
import android.util.Log
import java.io.IOException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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
    val idToken: String = "",
    val isRemember: Boolean = false
)

@Singleton
class SessionManager @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val api: MezonApi,
    private val networkMonitor: NetworkMonitor,
    private val secretStorage: SecretStorage,
    @ApplicationScope private val appScope: CoroutineScope
) {
    companion object {
        private const val TAG = "SessionManager"
        internal const val MAX_REFRESH_RETRIES = 5
        private const val TOKEN_EXPIRY_BUFFER_SEC = 60
        private const val SESSION_SECRET_ALIAS = "mezon_session_v1"
        private const val ENCRYPTED_PREFIX = "enc:v1:"
    }

    private fun encryptSecret(plain: String): String {
        if (plain.isEmpty()) return ""
        val encrypted = secretStorage.encryptToString(plain, SESSION_SECRET_ALIAS) ?: return plain
        return ENCRYPTED_PREFIX + encrypted
    }

    private fun decryptSecret(blob: String): String {
        if (blob.isEmpty()) return ""
        if (blob.startsWith(ENCRYPTED_PREFIX)) {
            val encryptedPart = blob.removePrefix(ENCRYPTED_PREFIX)
            return secretStorage.decryptFromString(encryptedPart, SESSION_SECRET_ALIAS) ?: ""
        }
        if (isLikelyJwt(blob)) return blob
        if (!isLikelyEncryptedBlob(blob)) return blob
        val legacyDecrypted = secretStorage.decryptFromString(blob, SESSION_SECRET_ALIAS)
        return legacyDecrypted ?: blob
    }

    val sessionFlow: Flow<StoredSession?> = dataStore.data.map { prefs ->
        val rawToken = prefs[SessionKeys.TOKEN] ?: return@map null
        if (rawToken.isEmpty()) return@map null
        val token = decryptSecret(rawToken)
        if (token.isEmpty() && rawToken.startsWith(ENCRYPTED_PREFIX)) return@map null
        StoredSession(
            token = token,
            refreshToken = decryptSecret(prefs[SessionKeys.REFRESH_TOKEN] ?: ""),
            apiUrl = prefs[SessionKeys.API_URL] ?: "",
            wsUrl = prefs[SessionKeys.WS_URL] ?: "",
            userId = prefs[SessionKeys.USER_ID] ?: "",
            idToken = decryptSecret(prefs[SessionKeys.ID_TOKEN] ?: ""),
            isRemember = prefs[SessionKeys.IS_REMEMBER] ?: false
        )
    }

    private val _sessionExpired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val sessionExpired: SharedFlow<Unit> = _sessionExpired.asSharedFlow()

    private val refreshMutex = Mutex()
    private val sessionPersistMutex = Mutex()
    private var activeRefresh: Deferred<StoredSession>? = null
    private var lastRefreshToken: String = ""
    private var failCount: Int = 0

    @Volatile
    private var sessionEpoch: Long = 0

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

    private fun isLikelyJwt(value: String): Boolean {
        if (value.count { it == '.' } != 2) return false
        val parts = value.split(".")
        if (parts.size != 3) return false
        return parts.all { it.isNotEmpty() }
    }

    private fun isLikelyEncryptedBlob(value: String): Boolean {
        val decoded = runCatching { Base64.decode(value, Base64.NO_WRAP) }.getOrNull() ?: return false
        return decoded.size > 12
    }

    suspend fun requireValidSession(): StoredSession {
        val session = sessionFlow.first()
            ?: throw SessionExpiredException("No session")
        if (!isLikelyJwt(session.token)) {
            Log.w(TAG, "Stored access token unreadable — attempting refresh instead of logout")
            return refresh()
        }
        if (!isTokenExpired(session.token)) return session
        if (!networkMonitor.isOnline.value) {
            Log.w(TAG, "Token expired but offline — returning cached session")
            return session
        }
        return refresh()
    }

    internal var launchRetryBaseMs: Long = 1000L

    suspend fun ensureFreshSession(): StoredSession? {
        var attempt = 0
        while (true) {
            try {
                return requireValidSession()
            } catch (e: SessionExpiredException) {
                Log.w(TAG, "Launch refresh: session expired or cleared", e)
                return null
            } catch (e: Exception) {
                attempt++
                if (!networkMonitor.isOnline.value || attempt >= MAX_REFRESH_RETRIES) {
                    Log.w(TAG, "Launch refresh gave up after $attempt attempts — using cached session", e)
                    return sessionFlow.first()
                }
                delay(attempt * launchRetryBaseMs)
            }
        }
    }

    suspend fun refresh(): StoredSession {
        val deferred = refreshMutex.withLock {
            activeRefresh?.takeIf { it.isActive } ?: appScope.async { doRefresh() }
                .also { activeRefresh = it }
        }
        return deferred.await()
    }

    private suspend fun doRefresh(): StoredSession {
        val epochAtStart = sessionEpoch
        val current = sessionFlow.first()
            ?: throw SessionExpiredException("No session to refresh")

        if (!networkMonitor.isOnline.value) {
            Log.w(TAG, "Offline during refresh — returning cached session")
            return current
        }

        try {
            val protoSession = api.sessionRefresh(
                apiUrl = current.apiUrl,
                refreshToken = current.refreshToken,
                isRemember = current.isRemember
            )

            val newSession = StoredSession(
                token = protoSession.getToken(),
                refreshToken = protoSession.getRefreshToken(),
                apiUrl = protoSession.getApiUrl().ifEmpty { current.apiUrl },
                wsUrl = protoSession.getWsUrl().ifEmpty { current.wsUrl },
                userId = current.userId,
                idToken = protoSession.getIdToken().ifEmpty { current.idToken },
                isRemember = current.isRemember,
            )

            if (!persistSession(newSession, requiredEpoch = epochAtStart)) {
                Log.w(TAG, "Session cleared during refresh — discarding refreshed session")
                throw SessionExpiredException("Session cleared during refresh")
            }
            lastRefreshToken = newSession.refreshToken
            failCount = 0
            Log.d(TAG, "Session refreshed successfully")
            return newSession
        } catch (e: SessionExpiredException) {
            throw e
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: UnauthorizedException) {
            val stored = sessionFlow.first()
            if (stored == null || sessionEpoch != epochAtStart) {
                Log.w(TAG, "Refresh rejected but session already cleared — not emitting expired", e)
                throw SessionExpiredException("Session cleared during refresh")
            }
            if (stored.refreshToken != current.refreshToken) {
                Log.w(TAG, "Refresh rejected but refresh token already rotated — adopting stored session")
                failCount = 0
                return stored
            }
            incrementFailCount(current.refreshToken)
            if (failCount >= MAX_REFRESH_RETRIES) {
                Log.e(TAG, "Session refresh 401/403 (failCount=$failCount/$MAX_REFRESH_RETRIES) — forcing logout", e)
                emitSessionExpired()
                throw SessionExpiredException("Session refresh failed: ${e.message}")
            }
            Log.w(TAG, "Session refresh 401/403 (failCount=$failCount/$MAX_REFRESH_RETRIES) — treating as transient", e)
            throw IOException("Session refresh unauthorized: ${e.message}", e)
        } catch (e: IOException) {
            Log.w(TAG, "Network error during refresh, not logging out", e)
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Server error during refresh — treating as transient, keeping session", e)
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
        persistSession(session, requiredEpoch = null)
    }

    private suspend fun persistSession(session: StoredSession, requiredEpoch: Long?): Boolean {
        sessionPersistMutex.withLock {
            if (requiredEpoch != null && sessionEpoch != requiredEpoch) return false
            StartupCache.hasSession = true
            StartupCache.userId = session.userId
            val encryptedToken = encryptSecret(session.token)
            val encryptedRefresh = encryptSecret(session.refreshToken)
            val encryptedIdToken = encryptSecret(session.idToken)
            dataStore.edit { prefs ->
                prefs[SessionKeys.TOKEN] = encryptedToken
                prefs[SessionKeys.REFRESH_TOKEN] = encryptedRefresh
                prefs[SessionKeys.API_URL] = session.apiUrl
                prefs[SessionKeys.WS_URL] = session.wsUrl
                prefs[SessionKeys.USER_ID] = session.userId
                prefs[SessionKeys.ID_TOKEN] = encryptedIdToken
                prefs[SessionKeys.IS_REMEMBER] = session.isRemember
            }
        }
        return true
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


    suspend fun getLastClanId(): Long = dataStore.data.first()[SessionKeys.LAST_CLAN_ID] ?: 0L

    suspend fun saveLastClanId(clanId: Long) {
        dataStore.edit { it[SessionKeys.LAST_CLAN_ID] = clanId }
    }

    suspend fun clearSession() {
        sessionPersistMutex.withLock {
            sessionEpoch++
            StartupCache.hasSession = false
            StartupCache.needsUsernameSetup = false
            StartupCache.userId = ""
            StartupCache.clearAccountProfileScratch()
            PollVotePersistence.clearAll()
            EmbedFormUtil.clearAll()
            lastRefreshToken = ""
            failCount = 0
            dataStore.edit { prefs -> prefs.removeAllSessionData() }
            secretStorage.deleteKey(SESSION_SECRET_ALIAS)
        }
    }

    private fun MutablePreferences.removeAllSessionData() {
        remove(SessionKeys.TOKEN)
        remove(SessionKeys.REFRESH_TOKEN)
        remove(SessionKeys.API_URL)
        remove(SessionKeys.WS_URL)
        remove(SessionKeys.USER_ID)
        remove(SessionKeys.ID_TOKEN)
        remove(SessionKeys.IS_REMEMBER)
        remove(SessionKeys.LAST_CLAN_ID)
    }
}

class SessionExpiredException(message: String) : RuntimeException(message)
