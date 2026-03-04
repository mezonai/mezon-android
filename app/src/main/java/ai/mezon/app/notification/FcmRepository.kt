package ai.mezon.app.notification

import ai.mezon.app.di.ApplicationScope
import ai.mezon.app.di.IoDispatcher
import ai.mezon.app.network.MezonApi
import ai.mezon.app.session.SessionManager
import android.util.Log
import com.mezon.mezon.api.RegistFcmDeviceTokenResponse
import com.mezon.mezon.api.registFcmDeviceTokenRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FcmRepository @Inject constructor(
    private val mezonApi: MezonApi,
    private val sessionManager: SessionManager,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope
) {
    companion object {
        private const val TAG = "FcmRepository"
        private const val PLATFORM = "android"
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 1000L
    }

    fun registerTokenAsync(fcmToken: String, deviceId: String) {
        appScope.launch {
            registerToken(fcmToken, deviceId)
        }
    }

    suspend fun registerToken(
        fcmToken: String,
        deviceId: String
    ): Result<RegistFcmDeviceTokenResponse> = withContext(ioDispatcher) {
        var lastError: Exception? = null
        repeat(MAX_RETRIES) { attempt ->
            try {
                val response = sessionManager.withAutoRefresh { session ->
                    val request = registFcmDeviceTokenRequest {
                        this.token = fcmToken
                        this.deviceId = deviceId
                        this.platform = PLATFORM
                    }
                    val bytes = mezonApi.registFcmDeviceToken(
                        session.apiUrl,
                        session.token,
                        request.toByteArray()
                    )
                    RegistFcmDeviceTokenResponse.parseFrom(bytes)
                }
                Log.d(TAG, "FCM token registered: deviceId=${response.deviceId}")
                return@withContext Result.success(response)
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Register FCM attempt ${attempt + 1} failed", e)
                if (attempt < MAX_RETRIES - 1) {
                    kotlinx.coroutines.delay(RETRY_DELAY_MS * (attempt + 1))
                }
            }
        }
        Log.e(TAG, "Failed to register FCM token after $MAX_RETRIES attempts")
        Result.failure(lastError ?: RuntimeException("Unknown error"))
    }
}
