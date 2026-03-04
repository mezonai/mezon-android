package ai.mezon.app.network

import ai.mezon.app.BuildConfig
import android.util.Base64
import android.util.Log
import com.mezon.mezon.api.ChannelDescList
import com.mezon.mezon.api.ChannelMessageList
import com.mezon.mezon.api.Session
import com.mezon.mezon.api.listChannelDescsRequest
import com.mezon.mezon.api.listChannelMessagesRequest
import com.mezon.mezon.api.sessionRefreshRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

class UnauthorizedException(message: String) : RuntimeException(message)

@Serializable
data class AuthEmailBody(
    val account: AccountEmailBody
)

@Serializable
data class AccountEmailBody(
    val email: String,
    val password: String
)

@Serializable
data class AuthSessionResponse(
    val token: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
    @SerialName("user_id") val userId: String = "",
    @SerialName("api_url") val apiUrl: String = "",
    @SerialName("ws_url") val wsUrl: String = "",
    @SerialName("id_token") val idToken: String = ""
)

private val CONTENT_TYPE_PROTO = ContentType("application", "proto")

@Singleton
class MezonApi @Inject constructor(
    private val httpClient: HttpClient
) {
    companion object {
        private val SERVER_KEY = BuildConfig.MEZON_API_KEY
        private const val TAG = "MezonApi"
    }

    // ── Auth (JSON) ──────────────────────────────────────────────

    suspend fun authenticateEmail(
        gatewayUrl: String,
        email: String,
        password: String
    ): AuthSessionResponse {
        val basicCreds = Base64.encodeToString(
            "$SERVER_KEY:".toByteArray(),
            Base64.NO_WRAP
        )
        val response = httpClient.post("$gatewayUrl/v2/account/authenticate/email") {
            header(HttpHeaders.Authorization, "Basic $basicCreds")
            contentType(ContentType.Application.Json)
            setBody(AuthEmailBody(account = AccountEmailBody(email, password)))
        }

        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            Log.e(TAG, "Auth failed: ${response.status} - $errorBody")
            throw RuntimeException("Auth failed (${response.status.value}): $errorBody")
        }

        val session: AuthSessionResponse = response.body()
        Log.d(TAG, "Auth success: userId=${session.userId}, apiUrl=${session.apiUrl}")
        return session
    }

    // ── Protobuf RPC ─────────────────────────────────────────────

    suspend fun rpc(
        apiUrl: String,
        token: String,
        method: String,
        body: ByteArray
    ): ByteArray {
        val url = "$apiUrl/mezon.api.Mezon/$method"
        val response = httpClient.post(url) {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, CONTENT_TYPE_PROTO.toString())
            contentType(CONTENT_TYPE_PROTO)
            setBody(body)
        }

        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            Log.e(TAG, "RPC $method failed: ${response.status} - $errorBody")
            if (response.status == HttpStatusCode.Unauthorized) {
                throw UnauthorizedException("RPC $method: 401 Unauthorized")
            }
            throw RuntimeException("RPC $method failed (${response.status.value}): $errorBody")
        }

        return response.readBytes()
    }

    suspend fun sessionRefresh(
        apiUrl: String,
        refreshToken: String,
        isRemember: Boolean = false
    ): Session {
        val request = sessionRefreshRequest {
            this.token = refreshToken
            this.isRemember = isRemember
        }
        val basicCreds = Base64.encodeToString(
            "$SERVER_KEY:".toByteArray(),
            Base64.NO_WRAP
        )
        val url = "$apiUrl/mezon.api.Mezon/SessionRefresh"
        val response = httpClient.post(url) {
            header(HttpHeaders.Authorization, "Basic $basicCreds")
            header(HttpHeaders.Accept, CONTENT_TYPE_PROTO.toString())
            contentType(CONTENT_TYPE_PROTO)
            setBody(request.toByteArray())
        }

        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            Log.e(TAG, "SessionRefresh failed: ${response.status} - $errorBody")
            throw RuntimeException("SessionRefresh failed (${response.status.value}): $errorBody")
        }

        val session = Session.parseFrom(response.readBytes())
        Log.d(TAG, "SessionRefresh success")
        return session
    }

    // ── Channel / DM APIs ────────────────────────────────────────

    suspend fun listChannelDescs(
        apiUrl: String,
        token: String,
        channelType: Int = CHANNEL_TYPE_GROUP,
        page: Int = 1,
        limit: Int = 50
    ): ChannelDescList {
        val request = listChannelDescsRequest {
            this.limit = limit
            this.state = 1          // open/active channels
            this.page = page
            this.clanId = 0L        // 0 = DM/groups (not clan channels)
            this.channelType = channelType
            this.isMobile = true
        }

        val bytes = rpc(apiUrl, token, "ListChannelDescs", request.toByteArray())
        val result = ChannelDescList.parseFrom(bytes)
        Log.d(TAG, "ListChannelDescs: ${result.channeldescCount} channels (type=$channelType, page=$page)")
        return result
    }

    suspend fun registFcmDeviceToken(
        apiUrl: String,
        token: String,
        requestBytes: ByteArray
    ): ByteArray {
        return rpc(apiUrl, token, "RegistFCMDeviceToken", requestBytes)
    }

    suspend fun listChannelMessages(
        apiUrl: String,
        token: String,
        channelId: Long,
        clanId: Long = 0L,
        messageId: Long = 0L,
        direction: Int = 0,
        limit: Int = 50
    ): ChannelMessageList {
        val request = listChannelMessagesRequest {
            this.channelId = channelId
            this.clanId = clanId
            if (messageId != 0L) this.messageId = messageId
            if (direction != 0) this.direction = direction
            this.limit = limit
        }
        val bytes = rpc(apiUrl, token, "ListChannelMessages", request.toByteArray())
        val result = ChannelMessageList.parseFrom(bytes)
        Log.d(TAG, "ListChannelMessages: ${result.messagesCount} msgs (channelId=$channelId)")
        return result
    }
}

const val CHANNEL_TYPE_CHANNEL = 1
const val CHANNEL_TYPE_GROUP = 2
const val CHANNEL_TYPE_DM = 3
const val CHANNEL_TYPE_THREAD = 7

const val STREAM_MODE_CHANNEL = 2
const val STREAM_MODE_GROUP = 3
const val STREAM_MODE_DM = 4
const val STREAM_MODE_THREAD = 6

fun channelTypeToStreamMode(channelType: Int): Int = when (channelType) {
    CHANNEL_TYPE_CHANNEL -> STREAM_MODE_CHANNEL
    CHANNEL_TYPE_GROUP -> STREAM_MODE_GROUP
    CHANNEL_TYPE_DM -> STREAM_MODE_DM
    CHANNEL_TYPE_THREAD -> STREAM_MODE_THREAD
    else -> STREAM_MODE_CHANNEL
}
