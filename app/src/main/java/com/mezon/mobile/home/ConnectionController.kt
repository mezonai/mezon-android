package com.mezon.mobile.home

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.network.ApiCacheTracker
import com.mezon.mobile.network.ConnectionState
import com.mezon.mobile.network.MezonSocket
import com.mezon.mobile.network.NetworkMonitor
import com.mezon.mobile.notification.FcmRepository
import com.mezon.mobile.session.SessionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ConnectionController"

@Singleton
class ConnectionController @Inject constructor(
    val mezonSocket: MezonSocket,
    private val sessionManager: SessionManager,
    private val notificationCenter: NotificationCenter,
    private val networkMonitor: NetworkMonitor,
    private val cacheTracker: ApiCacheTracker,
    private val fcmRepository: FcmRepository,
    @ApplicationContext private val appContext: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope
) {

    @Volatile var connectionState: ConnectionState = ConnectionState.DISCONNECTED
        private set

    @Volatile private var fcmRegistered = false

    init {
        appScope.launch { observeConnectionState() }
        appScope.launch { connectSocket() }
        appScope.launch { joinClanOnConnected() }
        appScope.launch { observeSessionExpired() }
        appScope.launch { observeNetworkRestore() }
        appScope.launch { observeSocketReconnect() }
    }

    fun disconnect() {
        mezonSocket.disconnect()
    }

    fun handleAppForeground() {
        appScope.launch {
            if (!fcmRegistered) {
                fcmRegistered = true
                registerFcmToken()
            }
            if (!networkMonitor.isOnline.value) return@launch
            try { sessionManager.requireValidSession() }
            catch (e: java.io.IOException) { return@launch }
            catch (e: Exception) { Log.e(TAG, "Failed to refresh session", e); return@launch }
            mezonSocket.reconnectIfNeeded()
        }
    }

    private suspend fun observeConnectionState() {
        mezonSocket.connectionState.collect { state ->
            connectionState = state
            notificationCenter.postNotificationOnMainThread(NotificationCenter.connectionStateChanged, state)
        }
    }

    private suspend fun connectSocket() {
        sessionManager.sessionFlow.collect { session ->
            if (session != null && mezonSocket.connectionState.value == ConnectionState.DISCONNECTED) {
                val s = try { sessionManager.requireValidSession() }
                catch (e: java.io.IOException) { session }
                catch (e: Exception) { Log.e(TAG, "Failed to get valid session", e); return@collect }
                Log.d(TAG, "Connecting WebSocket... wsUrl=${s.wsUrl}")
                mezonSocket.connect(s.wsUrl, s.token)
            }
        }
    }

    private suspend fun joinClanOnConnected() {
        mezonSocket.connectionState.collect { state ->
            if (state == ConnectionState.CONNECTED) {
                try { mezonSocket.joinClanChat(0L) }
                catch (e: Exception) { Log.e(TAG, "joinClanChat(0) failed", e) }
            }
        }
    }

    private suspend fun observeSessionExpired() {
        sessionManager.sessionExpired.collect {
            Log.e(TAG, "Session expired — forcing logout")
            mezonSocket.disconnect()
            sessionManager.clearSession()
            notificationCenter.postNotificationOnMainThread(NotificationCenter.sessionExpired)
        }
    }

    private suspend fun observeSocketReconnect() {
        mezonSocket.reconnected.collect {
            Log.d(TAG, "Socket reconnected — invalidating API cache")
            cacheTracker.invalidateAll()
        }
    }

    private suspend fun observeNetworkRestore() {
        networkMonitor.onlineEvents.collectLatest { online ->
            if (online && mezonSocket.connectionState.value == ConnectionState.DISCONNECTED) {
                Log.d(TAG, "Network restored — triggering reconnect")
                mezonSocket.reconnectIfNeeded()
            }
        }
    }

    private suspend fun registerFcmToken() {
        try {
            val token = FirebaseMessaging.getInstance().token.await()
            val deviceId = Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
            fcmRepository.registerToken(token, deviceId)
                .onSuccess { Log.d(TAG, "FCM token registered") }
                .onFailure { Log.e(TAG, "FCM token registration failed", it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get FCM token", e)
        }
    }
}
