package ai.mezon.app.home

import ai.mezon.app.home.chat.MessageStore
import ai.mezon.app.home.messages.DirectStore
import ai.mezon.app.network.ApiCacheTracker
import ai.mezon.app.network.ConnectionState
import ai.mezon.app.network.MezonSocket
import ai.mezon.app.network.NetworkMonitor
import ai.mezon.app.network.SocketEventDispatcher
import ai.mezon.app.notification.FcmRepository
import ai.mezon.app.notification.NotificationObserver
import ai.mezon.app.session.SessionManager
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val sessionManager: SessionManager,
    private val mezonSocket: MezonSocket,
    private val networkMonitor: NetworkMonitor,
    private val apiCacheTracker: ApiCacheTracker,
    private val socketEventDispatcher: SocketEventDispatcher,
    private val fcmRepository: FcmRepository,
    @ApplicationContext private val appContext: android.content.Context,
    @Suppress("unused") private val directStore: DirectStore,
    @Suppress("unused") private val messageStore: MessageStore,
    @Suppress("unused") private val notificationObserver: NotificationObserver
) : ViewModel() {

    val connectionState = mezonSocket.connectionState

    val session = sessionManager.sessionFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _forceLogout = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val forceLogout: SharedFlow<Unit> = _forceLogout.asSharedFlow()

    init {
        connectSocket()
        joinClanChatOnConnected()
        observeChannelMessages()
        observeSessionExpired()
        observeNetworkRestore()
        observeSocketReconnect()
        registerFcmToken()
    }

    private fun connectSocket() {
        viewModelScope.launch {
            session.collect { s ->
                if (s != null && mezonSocket.connectionState.value == ConnectionState.DISCONNECTED) {
                    val connectSession = try {
                        sessionManager.requireValidSession()
                    } catch (e: java.io.IOException) {
                        Log.w(TAG, "No network on start, using cached session")
                        s
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to get valid session for socket connect", e)
                        return@collect
                    }
                    Log.d(TAG, "Connecting WebSocket... wsUrl=${connectSession.wsUrl}")
                    mezonSocket.connect(connectSession.wsUrl, connectSession.token)
                }
            }
        }
    }

    private fun joinClanChatOnConnected() {
        viewModelScope.launch {
            mezonSocket.connectionState.collect { state ->
                if (state == ConnectionState.CONNECTED) {
                    try {
                        val response = mezonSocket.joinClanChat(0L)
                        Log.d(TAG, "joinClanChat(0) success: ${response.messageCase}")
                    } catch (e: Exception) {
                        Log.e(TAG, "joinClanChat(0) failed", e)
                    }
                }
            }
        }
    }

    private fun observeChannelMessages() {
        viewModelScope.launch {
            socketEventDispatcher.channelMessages.collect { msg ->
                Log.d(TAG, "onChannelMessage: channelId=${msg.channelId}, senderId=${msg.senderId}, username=${msg.username}, content=${msg.content.take(200)}")
            }
        }
    }

    private fun observeSessionExpired() {
        viewModelScope.launch {
            sessionManager.sessionExpired.collect {
                Log.e(TAG, "Session expired — forcing logout")
                mezonSocket.disconnect()
                sessionManager.clearSession()
                _forceLogout.tryEmit(Unit)
            }
        }
    }

    private fun observeSocketReconnect() {
        viewModelScope.launch {
            mezonSocket.reconnected.collect {
                Log.d(TAG, "Socket reconnected — invalidating API cache")
                apiCacheTracker.invalidateAll()
            }
        }
    }

    private fun observeNetworkRestore() {
        viewModelScope.launch {
            networkMonitor.onlineEvents.collectLatest { online ->
                if (online && mezonSocket.connectionState.value == ConnectionState.DISCONNECTED) {
                    Log.d(TAG, "Network restored — triggering reconnect")
                    mezonSocket.reconnectIfNeeded()
                }
            }
        }
    }

    private fun registerFcmToken() {
        viewModelScope.launch {
            try {
                val token = FirebaseMessaging.getInstance().token.await()
                val deviceId = Settings.Secure.getString(
                    appContext.contentResolver,
                    Settings.Secure.ANDROID_ID
                )
                fcmRepository.registerToken(token, deviceId)
                    .onSuccess { Log.d(TAG, "FCM token registered on login") }
                    .onFailure { Log.e(TAG, "FCM token registration failed", it) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get FCM token", e)
            }
        }
    }

    fun onAppForeground() {
        viewModelScope.launch {
            if (!networkMonitor.isOnline.value) {
                Log.w(TAG, "No network on foreground, skipping refresh")
                return@launch
            }
            try {
                sessionManager.requireValidSession()
            } catch (e: java.io.IOException) {
                Log.w(TAG, "Network error on foreground, skipping")
                return@launch
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh session on foreground", e)
                return@launch
            }
            mezonSocket.reconnectIfNeeded()
        }
    }

    fun disconnect() {
        mezonSocket.disconnect()
    }

    companion object {
        private const val TAG = "HomeViewModel"
    }
}
