package com.mezon.mobile.network

import com.mezon.mobile.BuildConfig
import android.util.Base64
import com.mezon.mezon.api.Account
import com.mezon.mezon.api.AccountEmail
import com.mezon.mezon.api.AllUserClans
import com.mezon.mezon.api.BlockFriendsRequest
import com.mezon.mezon.api.ChannelDescList
import com.mezon.mezon.api.ChannelMessageList
import com.mezon.mezon.api.ClanDescList
import com.mezon.mezon.api.ChannelUserList
import com.mezon.mezon.api.ClanUserList
import com.mezon.mezon.api.DeleteNotificationsRequest
import com.mezon.mezon.api.FriendList
import com.mezon.mezon.api.LinkAccountConfirmRequest
import com.mezon.mezon.api.ListFriendsRequest
import com.mezon.mezon.api.ListNotificationsRequest
import com.mezon.mezon.api.NotificationList
import com.mezon.mezon.api.SearchMessageResponse
import com.mezon.mezon.api.Session
import com.mezon.mezon.api.UploadAttachment
import com.mezon.mezon.api.uploadAttachmentRequest
import com.mezon.mezon.api.accountEmail
import com.mezon.mezon.api.blockFriendsRequest
import com.mezon.mezon.api.deleteNotificationsRequest
import com.mezon.mezon.api.filterParam
import com.mezon.mezon.api.linkAccountConfirmRequest
import com.mezon.mezon.api.listClanDescRequest
import com.mezon.mezon.api.listChannelUsersRequest
import com.mezon.mezon.api.listClanUsersRequest
import com.mezon.mezon.api.listChannelDescsRequest
import com.mezon.mezon.api.listChannelMessagesRequest
import com.mezon.mezon.api.listFriendsRequest
import com.mezon.mezon.api.listNotificationsRequest
import com.mezon.mezon.api.searchMessageRequest
import com.mezon.mezon.api.sessionRefreshRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
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

@Serializable
data class AuthSmsOtpBody(
    val account: AccountSmsOtpBody
)

@Serializable
data class AccountSmsOtpBody(
    val phoneno: String,
    val vars: Map<String, String> = emptyMap()
)

@Serializable
data class AuthEmailOtpBody(
    val account: AccountEmailOtpBody
)

@Serializable
data class AccountEmailOtpBody(
    val email: String,
    val vars: Map<String, String> = emptyMap()
)

@Serializable
data class ConfirmOtpBody(
    @SerialName("req_id") val reqId: String,
    @SerialName("otp_code") val otpCode: String
)

@Serializable
data class OtpRequestResponse(
    @SerialName("req_id") val reqId: String = "",
    @SerialName("otp_code") val otpCode: String = "",
    val status: Int = 0
)


private val CONTENT_TYPE_PROTO = ContentType("application", "proto")

@Singleton
class MezonApi @Inject constructor(
    private val httpClient: HttpClient
) {
    companion object {
        private val SERVER_KEY = BuildConfig.MEZON_API_KEY
    }

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
            throw RuntimeException("Auth failed (${response.status.value}): $errorBody")
        }

        val session: AuthSessionResponse = response.body()
        return session
    }

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
            throw RuntimeException("SessionRefresh failed (${response.status.value}): $errorBody")
        }

        val session = Session.parseFrom(response.readBytes())
        return session
    }

    suspend fun listChannelDescs(
        apiUrl: String,
        token: String,
        channelType: Int = CHANNEL_TYPE_GROUP,
        page: Int = 1,
        limit: Int = 500
    ): ChannelDescList {
        val request = listChannelDescsRequest {
            this.limit = limit
            this.state = 1
            this.page = page
            this.clanId = 0L
            this.channelType = channelType
            this.isMobile = true
        }

        val bytes = rpc(apiUrl, token, "ListChannelDescs", request.toByteArray())
        val result = ChannelDescList.parseFrom(bytes)
        return result
    }

    suspend fun listClanDescs(
        apiUrl: String,
        token: String,
        limit: Int = 100
    ): ClanDescList {
        val request = listClanDescRequest {
            this.limit = limit
            this.state = 1
            this.cursor = ""
        }
        val bytes = rpc(apiUrl, token, "ListClanDescs", request.toByteArray())
        val result = ClanDescList.parseFrom(bytes)
        return result
    }

    suspend fun listChannelsByClan(
        apiUrl: String,
        token: String,
        clanId: Long,
        limit: Int = 500
    ): ChannelDescList {
        val request = listChannelDescsRequest {
            this.clanId = clanId
            this.limit = limit
            this.state = 1
            this.page = 0
            this.channelType = CHANNEL_TYPE_CHANNEL
            this.isMobile = true
        }
        val bytes = rpc(apiUrl, token, "ListChannelDescs", request.toByteArray())
        val result = ChannelDescList.parseFrom(bytes)
        return result
    }

    suspend fun registFcmDeviceToken(
        apiUrl: String,
        token: String,
        requestBytes: ByteArray
    ): ByteArray {
        return rpc(apiUrl, token, "RegistFCMDeviceToken", requestBytes)
    }

    suspend fun registrationEmail(
        apiUrl: String,
        token: String,
        email: String,
        password: String,
        oldPassword: String = ""
    ): ByteArray {
        val request = com.mezon.mezon.api.RegistrationEmailRequest.newBuilder()
            .setEmail(email)
            .setPassword(password)
            .also { if (oldPassword.isNotEmpty()) it.setOldPassword(oldPassword) }
            .build()
        return rpc(apiUrl, token, "RegistrationEmail", request.toByteArray())
    }

    suspend fun updateAccount(
        apiUrl: String,
        token: String,
        displayName: String? = null,
        avatarUrl: String? = null,
        aboutMe: String? = null,
        logoUrl: String? = null
    ): ByteArray {
        val builder = com.mezon.mezon.api.UpdateAccountRequest.newBuilder()
        if (displayName != null) builder.displayName = com.google.protobuf.StringValue.of(displayName)
        if (avatarUrl != null) builder.avatarUrl = com.google.protobuf.StringValue.of(avatarUrl)
        if (aboutMe != null) builder.aboutMe = com.google.protobuf.StringValue.of(aboutMe)
        if (logoUrl != null) builder.logo = com.google.protobuf.StringValue.of(logoUrl)
        return rpc(apiUrl, token, "UpdateAccount", builder.build().toByteArray())
    }

    suspend fun getUserProfileOnClan(
        apiUrl: String,
        token: String,
        clanId: Long
    ): com.mezon.mezon.api.ClanProfile {
        val request = com.mezon.mezon.api.ClanProfileRequest.newBuilder()
            .setClanId(clanId)
            .build()
        val bytes = rpc(apiUrl, token, "GetUserProfileOnClan", request.toByteArray())
        return com.mezon.mezon.api.ClanProfile.parseFrom(bytes)
    }

    suspend fun updateClanProfile(
        apiUrl: String,
        token: String,
        clanId: Long,
        nickName: String? = null,
        avatar: String? = null
    ): ByteArray {
        val builder = com.mezon.mezon.api.UpdateClanProfileRequest.newBuilder()
            .setClanId(clanId)
        if (nickName != null) builder.nickName = com.google.protobuf.StringValue.of(nickName)
        if (avatar != null) builder.avatar = com.google.protobuf.StringValue.of(avatar)
        return rpc(apiUrl, token, "UpdateUserProfileByClan", builder.build().toByteArray())
    }

    suspend fun getAccount(apiUrl: String, token: String): Account {
        val bytes = rpc(apiUrl, token, "GetAccount", ByteArray(0))
        return Account.parseFrom(bytes)
    }

    suspend fun linkEmail(apiUrl: String, token: String, email: String): ByteArray {
        val request = accountEmail { this.email = email }
        return rpc(apiUrl, token, "LinkEmail", request.toByteArray())
    }

    suspend fun confirmLinkOTP(apiUrl: String, token: String, reqId: String, otpCode: String): ByteArray {
        val request = linkAccountConfirmRequest {
            this.reqId = reqId
            this.otpCode = otpCode
        }
        return rpc(apiUrl, token, "ConfirmLinkMezonOTP", request.toByteArray())
    }

    suspend fun authenticateEmailOTP(gatewayUrl: String, email: String, vars: Map<String, String> = emptyMap()): OtpRequestResponse {
        val basicCreds = Base64.encodeToString("$SERVER_KEY:".toByteArray(), Base64.NO_WRAP)
        val url = "$gatewayUrl/v2/account/authenticate/emailotp"
        val response = httpClient.post(url) {
            header(HttpHeaders.Authorization, "Basic $basicCreds")
            contentType(ContentType.Application.Json)
            setBody(AuthEmailOtpBody(account = AccountEmailOtpBody(email = email, vars = vars)))
        }
        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            throw RuntimeException("AuthenticateEmailOTP failed (${response.status.value}): $errorBody")
        }
        val result: OtpRequestResponse = response.body()
        return result
    }

    suspend fun authenticateSmsOTP(gatewayUrl: String, phone: String, vars: Map<String, String> = emptyMap()): OtpRequestResponse {
        val basicCreds = Base64.encodeToString("$SERVER_KEY:".toByteArray(), Base64.NO_WRAP)
        val url = "$gatewayUrl/v2/account/authenticate/smsotp"
        val response = httpClient.post(url) {
            header(HttpHeaders.Authorization, "Basic $basicCreds")
            contentType(ContentType.Application.Json)
            setBody(AuthSmsOtpBody(account = AccountSmsOtpBody(phoneno = phone, vars = vars)))
        }
        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            throw RuntimeException("AuthenticateSmsOTP failed (${response.status.value}): $errorBody")
        }
        val result: OtpRequestResponse = response.body()
        return result
    }

    suspend fun confirmAuthenticateOTP(gatewayUrl: String, reqId: String, otpCode: String): AuthSessionResponse {
        val basicCreds = Base64.encodeToString("$SERVER_KEY:".toByteArray(), Base64.NO_WRAP)
        val url = "$gatewayUrl/v2/account/authenticate/confirmotp"
        val response = httpClient.post(url) {
            header(HttpHeaders.Authorization, "Basic $basicCreds")
            contentType(ContentType.Application.Json)
            setBody(ConfirmOtpBody(reqId = reqId, otpCode = otpCode))
        }
        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            throw RuntimeException("ConfirmAuthenticateOTP failed (${response.status.value}): $errorBody")
        }
        val session: AuthSessionResponse = response.body()
        return session
    }

    suspend fun deleteAccount(apiUrl: String, token: String): ByteArray {
        return rpc(apiUrl, token, "DeleteAccount", ByteArray(0))
    }

    suspend fun listFriends(apiUrl: String, token: String, state: Int = 3, limit: Int = 100): FriendList {
        val request = listFriendsRequest {
            this.state = state
            this.limit = limit
        }
        val bytes = rpc(apiUrl, token, "ListFriends", request.toByteArray())
        return FriendList.parseFrom(bytes)
    }

    suspend fun blockFriends(apiUrl: String, token: String, ids: List<Long>, usernames: List<String>): ByteArray {
        val request = blockFriendsRequest {
            this.ids.addAll(ids)
            this.usernames.addAll(usernames)
        }
        return rpc(apiUrl, token, "BlockFriends", request.toByteArray())
    }

    suspend fun unblockFriends(apiUrl: String, token: String, ids: List<Long>, usernames: List<String>): ByteArray {
        val request = blockFriendsRequest {
            this.ids.addAll(ids)
            this.usernames.addAll(usernames)
        }
        return rpc(apiUrl, token, "DeleteFriends", request.toByteArray())
    }

    suspend fun sendChannelMessage(
        apiUrl: String,
        token: String,
        request: com.mezon.mezon.rtapi.ChannelMessageSend
    ): com.mezon.mezon.rtapi.ChannelMessageAck {
        val body = request.toByteArray()
        val bytes = rpc(apiUrl, token, "SendChannelMessage", body)
        return com.mezon.mezon.rtapi.ChannelMessageAck.parseFrom(bytes)
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
        return result
    }

    suspend fun listNotifications(
        apiUrl: String,
        token: String,
        clanId: Long,
        category: Int,
        notificationId: Long = 0L,
        limit: Int = 50
    ): NotificationList {
        val request = listNotificationsRequest {
            this.clanId = clanId
            this.category = category
            this.limit = limit
            if (notificationId != 0L) this.notificationId = notificationId
        }
        val bytes = rpc(apiUrl, token, "ListNotifications", request.toByteArray())
        return NotificationList.parseFrom(bytes)
    }

    suspend fun deleteNotifications(
        apiUrl: String,
        token: String,
        ids: List<Long>,
        category: Int
    ): ByteArray {
        val request = deleteNotificationsRequest {
            this.ids.addAll(ids)
            this.category = category
        }
        return rpc(apiUrl, token, "DeleteNotifications", request.toByteArray())
    }

    suspend fun uploadAttachmentFile(
        apiUrl: String,
        token: String,
        filename: String,
        filetype: String,
        size: Int,
        width: Int = 0,
        height: Int = 0
    ): UploadAttachment {
        val request = uploadAttachmentRequest {
            this.filename = filename
            this.filetype = filetype
            this.size = size
            if (width > 0) this.width = width
            if (height > 0) this.height = height
        }
        val bytes = rpc(apiUrl, token, "UploadAttachmentFile", request.toByteArray())
        return UploadAttachment.parseFrom(bytes)
    }

    suspend fun listUserClansByUserId(
        apiUrl: String,
        token: String
    ): AllUserClans {
        val bytes = rpc(apiUrl, token, "ListUserClansByUserId", ByteArray(0))
        return AllUserClans.parseFrom(bytes)
    }

    suspend fun listClanUsers(
        apiUrl: String,
        token: String,
        clanId: Long
    ): ClanUserList {
        val request = listClanUsersRequest {
            this.clanId = clanId
        }
        val bytes = rpc(apiUrl, token, "ListClanUsers", request.toByteArray())
        return ClanUserList.parseFrom(bytes)
    }

    suspend fun listChannelUsers(
        apiUrl: String,
        token: String,
        clanId: Long,
        channelId: Long,
        channelType: Int
    ): ChannelUserList {
        val request = listChannelUsersRequest {
            this.clanId = clanId
            this.channelId = channelId
            this.channelType = channelType
            this.limit = 2000
            this.state = 1
        }
        val bytes = rpc(apiUrl, token, "ListChannelUsers", request.toByteArray())
        return ChannelUserList.parseFrom(bytes)
    }

    suspend fun listChannelByUserId(
        apiUrl: String,
        token: String
    ): ChannelDescList {
        val request = listChannelDescsRequest {
            this.limit = 500
            this.state = 1
            this.page = 1
            this.clanId = 0L
            this.channelType = 0
            this.isMobile = true
        }
        val bytes = rpc(apiUrl, token, "ListChannelByUserId", request.toByteArray())
        return ChannelDescList.parseFrom(bytes)
    }

    suspend fun searchMessages(
        apiUrl: String,
        token: String,
        filters: List<Pair<String, String>>,
        from: Int = 1,
        size: Int = 20
    ): SearchMessageResponse {
        val request = searchMessageRequest {
            for ((name, value) in filters) {
                this.filters += filterParam {
                    fieldName = name
                    fieldValue = value
                }
            }
            this.from = from
            this.size = size
        }
        val bytes = rpc(apiUrl, token, "SearchMessage", request.toByteArray())
        return SearchMessageResponse.parseFrom(bytes)
    }

    suspend fun putFileToPresignedUrl(
        presignedUrl: String,
        fileBytes: ByteArray,
        contentType: String
    ) {
        val response = httpClient.put(presignedUrl) {
            header(HttpHeaders.ContentType, contentType)
            setBody(fileBytes)
        }
        if (!response.status.isSuccess()) {
            throw RuntimeException("File upload failed (${response.status.value})")
        }
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

const val CODE_CHAT_UPDATE = 1
const val CODE_CHAT_REMOVE = 2

fun channelTypeToStreamMode(channelType: Int): Int = when (channelType) {
    CHANNEL_TYPE_CHANNEL -> STREAM_MODE_CHANNEL
    CHANNEL_TYPE_GROUP -> STREAM_MODE_GROUP
    CHANNEL_TYPE_DM -> STREAM_MODE_DM
    CHANNEL_TYPE_THREAD -> STREAM_MODE_THREAD
    else -> STREAM_MODE_CHANNEL
}

fun streamModeToChannelType(streamMode: Int): Int = when (streamMode) {
    STREAM_MODE_CHANNEL -> CHANNEL_TYPE_CHANNEL
    STREAM_MODE_GROUP -> CHANNEL_TYPE_GROUP
    STREAM_MODE_DM -> CHANNEL_TYPE_DM
    STREAM_MODE_THREAD -> CHANNEL_TYPE_THREAD
    else -> CHANNEL_TYPE_CHANNEL
}
