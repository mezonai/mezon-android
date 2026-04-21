package com.mezon.mobile.network

import com.mezon.mobile.BuildConfig
import android.util.Base64
import com.mezon.mezon.api.Account
import com.mezon.mezon.api.AccountEmail
import com.mezon.mezon.api.AllUsersAddChannelResponse
import com.mezon.mezon.api.AllUserClans
import com.mezon.mezon.api.allUsersAddChannelRequest
import com.mezon.mezon.api.CategoryDesc
import com.mezon.mezon.api.ClanDesc
import com.mezon.mezon.api.EmojiListedResponse
import com.mezon.mezon.api.StickerListedResponse
import com.mezon.mezon.api.BlockFriendsRequest
import com.mezon.mezon.api.ChannelDescList
import com.mezon.mezon.api.ChannelDescription
import com.mezon.mezon.api.ChannelMessageList
import com.mezon.mezon.api.createChannelDescRequest
import com.mezon.mezon.api.ClanDescList
import com.mezon.mezon.api.PinMessagesList
import com.mezon.mezon.api.pinMessageRequest
import com.mezon.mezon.api.deletePinMessage
import com.mezon.mezon.api.ChannelUserList
import com.mezon.mezon.api.ClanUserList
import com.mezon.mezon.api.DeleteNotificationsRequest
import com.mezon.mezon.api.FriendList
import com.mezon.mezon.api.LinkAccountConfirmRequest
import com.mezon.mezon.api.ListFriendsRequest
import com.mezon.mezon.api.ListNotificationsRequest
import com.mezon.mezon.api.NotificationList
import com.mezon.mezon.api.SearchMessageResponse
import com.mezon.mezon.api.UploadAttachment
import com.mezon.mezon.api.uploadAttachmentRequest
import com.mezon.mezon.api.accountEmail
import com.mezon.mezon.api.blockFriendsRequest
import com.mezon.mezon.api.createCategoryDescRequest
import com.mezon.mezon.api.createClanDescRequest
import com.mezon.mezon.api.deleteNotificationsRequest
import com.mezon.mezon.api.filterParam
import com.mezon.mezon.api.linkAccountConfirmRequest
import com.mezon.mezon.api.listClanDescRequest
import com.mezon.mezon.api.listChannelUsersRequest
import com.mezon.mezon.api.listClanUsersRequest
import com.mezon.mezon.api.ListChannelAppsResponse
import com.mezon.mezon.api.GenerateHashChannelAppsResponse
import com.mezon.mezon.api.listChannelAppsRequest
import com.mezon.mezon.api.generateHashChannelAppsRequest
import com.mezon.mezon.api.ListChannelBadgeCountResponse
import com.mezon.mezon.api.listChannelBadgeCountRequest
import com.mezon.mezon.api.listChannelDescsRequest
import com.mezon.mezon.api.listThreadRequest
import com.mezon.mezon.api.ListFavoriteChannelResponse
import com.mezon.mezon.api.AddFavoriteChannelResponse
import com.mezon.mezon.api.addFavoriteChannelRequest
import com.mezon.mezon.api.listFavoriteChannelRequest
import com.mezon.mezon.api.removeFavoriteChannelRequest
import com.mezon.mezon.api.listChannelMessagesRequest
import com.mezon.mezon.api.listFriendsRequest
import com.mezon.mezon.api.listNotificationsRequest
import com.mezon.mezon.api.searchMessageRequest
import com.mezon.mezon.api.sessionRefreshRequest
import com.mezon.mezon.api.Session
import com.mezon.mezon.api.confirmLoginRequest
import com.mezon.mezon.api.GenerateMeetTokenResponse
import com.mezon.mezon.api.VoiceChannelUserList
import com.mezon.mezon.api.generateMeetTokenRequest
import com.mezon.mezon.api.meetParticipantRequest
import com.mezon.mezon.api.updateAIAgentRequest
import com.mezon.mezon.api.ListClanDiscover
import com.mezon.mezon.api.InviteUserRes
import com.mezon.mezon.api.inviteUserRequest
import com.mezon.mezon.api.clanDiscover as clanDiscoverProto
import com.mezon.mezon.api.listClanDiscover
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

@Serializable
private data class ClanDiscoverGatewayRequest(
    @SerialName("page_number") val page_number: Int,
    @SerialName("item_per_page") val item_per_page: Int
)

@Serializable
private data class ClanDiscoverGatewayResponse(
    @SerialName("clan_discover") val clan_discover: List<ClanDiscoverJson> = emptyList(),
    @SerialName("page_number") val page_number: Int = 0,
    @SerialName("page_count") val page_count: Int = 1
)

@Serializable
private data class ClanDiscoverJson(
    @SerialName("clan_id") val clan_id: Long = 0L,
    @SerialName("clan_name") val clan_name: String = "",
    @SerialName("invite_id") val invite_id: Long = 0L,
    @SerialName("clan_logo") val clan_logo: String = "",
    @SerialName("online_members") val online_members: Int = 0,
    @SerialName("total_members") val total_members: Int = 0,
    @SerialName("verified") val verified: Boolean = false,
    @SerialName("description") val description: String = "",
    @SerialName("banner") val banner: String = "",
    @SerialName("about") val about: String = "",
    @SerialName("short_url") val short_url: String = "",
    @SerialName("create_time_seconds") val create_time_seconds: Int = 0
)

@Singleton
class MezonApi @Inject constructor(
    private val httpClient: HttpClient
) {
    companion object {
        private val SERVER_KEY = BuildConfig.MEZON_API_KEY
        private const val DISCOVER_ITEMS_PER_PAGE = 6
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
        val base = apiUrl.trimEnd('/')
        val url = "$base/mezon.api.Mezon/$method"
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

    private suspend fun rpcNoAuth(
        apiUrl: String,
        method: String,
        body: ByteArray
    ): ByteArray {
        val base = apiUrl.trimEnd('/')
        val url = "$base/mezon.api.Mezon/$method"
        val response = httpClient.post(url) {
            header(HttpHeaders.Accept, CONTENT_TYPE_PROTO.toString())
            contentType(CONTENT_TYPE_PROTO)
            setBody(body)
        }

        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            throw RuntimeException("RPC $method failed (${response.status.value}): $errorBody")
        }

        return response.readBytes()
    }

    suspend fun confirmLoginRequest(
        gatewayUrl: String,
        token: String,
        loginId: Long
    ): Session {
        val request = confirmLoginRequest {
            this.loginId = loginId
            this.isRemember = true
        }
        val bytes = rpc(gatewayUrl, token, "ConfirmLogin", request.toByteArray())
        return Session.parseFrom(bytes)
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
        val base = apiUrl.trimEnd('/')
        val url = "$base/mezon.api.Mezon/SessionRefresh"
        val response = httpClient.post(url) {
            header(HttpHeaders.Authorization, "Basic $basicCreds")
            header(HttpHeaders.Accept, CONTENT_TYPE_PROTO.toString())
            contentType(CONTENT_TYPE_PROTO)
            setBody(request.toByteArray())
        }

        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            val code = response.status.value
            if (code == 401 || code == 403 || code == 500) {
                throw UnauthorizedException("SessionRefresh: $code Unauthorized")
            }
            throw RuntimeException("SessionRefresh failed ($code): $errorBody")
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

    suspend fun createChannelDesc(
        apiUrl: String,
        token: String,
        type: Int,
        userIds: List<Long>,
        clanId: Long = 0L,
        channelPrivate: Int = 1,
        channelLabel: String = "",
        categoryId: Long = 0L,
        parentId: Long = 0L,
        appId: Long = 0L
    ): ChannelDescription {
        val request = createChannelDescRequest {
            this.type = type
            this.clanId = clanId
            this.channelPrivate = channelPrivate
            this.userIds.addAll(userIds)
            this.channelLabel = channelLabel
            this.categoryId = categoryId
            this.parentId = parentId
            this.appId = appId
        }
        val bytes = rpc(apiUrl, token, "CreateChannelDesc", request.toByteArray())
        return ChannelDescription.parseFrom(bytes)
    }

    suspend fun createClanDesc(
        apiUrl: String,
        token: String,
        clanName: String,
        logo: String = "",
        banner: String = ""
    ): ClanDesc {
        val request = createClanDescRequest {
            this.clanName = clanName
            this.logo = logo
            this.banner = banner
        }
        val bytes = rpc(apiUrl, token, "CreateClanDesc", request.toByteArray())
        return ClanDesc.parseFrom(bytes)
    }

    suspend fun createCategoryDesc(
        apiUrl: String,
        token: String,
        clanId: Long,
        categoryName: String
    ): CategoryDesc {
        val request = createCategoryDescRequest {
            this.clanId = clanId
            this.categoryName = categoryName
        }
        val bytes = rpc(apiUrl, token, "CreateCategoryDesc", request.toByteArray())
        return CategoryDesc.parseFrom(bytes)
    }

    suspend fun listChannelBadgeCount(
        apiUrl: String,
        token: String,
        clanId: Long
    ): ListChannelBadgeCountResponse {
        val request = listChannelBadgeCountRequest {
            this.clanId = clanId
        }
        val bytes = rpc(apiUrl, token, "ListChannelBadgeCount", request.toByteArray())
        return ListChannelBadgeCountResponse.parseFrom(bytes)
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

    suspend fun listThreadDescs(
        apiUrl: String,
        token: String,
        channelId: Long,
        clanId: Long,
        page: Int = 1,
        limit: Int = 50
    ): ChannelDescList {
        val request = listThreadRequest {
            this.channelId = channelId
            this.clanId = clanId
            this.page = page
            this.limit = limit
            this.state = 1
        }
        val bytes = rpc(apiUrl, token, "ListThreadDescs", request.toByteArray())
        return ChannelDescList.parseFrom(bytes)
    }

    suspend fun listClanDiscover(
        page: Int = 1,
        itemPerPage: Int = DISCOVER_ITEMS_PER_PAGE
    ): ListClanDiscover {
        val gatewayUrl = BuildConfig.MEZON_GATEWAY_URL.trimEnd('/')
        val url = "$gatewayUrl/v2/clan/discover"
        val basicCreds = Base64.encodeToString(
            "$SERVER_KEY:".toByteArray(),
            Base64.NO_WRAP
        )
        val response = httpClient.post(url) {
            header(HttpHeaders.Authorization, "Basic $basicCreds")
            header(HttpHeaders.Accept, ContentType.Application.Json.toString())
            contentType(ContentType.Application.Json)
            setBody(
                ClanDiscoverGatewayRequest(
                    page_number = page,
                    item_per_page = itemPerPage
                )
            )
        }
        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            throw RuntimeException("Clan discover failed (${response.status.value}): $errorBody")
        }
        val dto = response.body<ClanDiscoverGatewayResponse>()
        return listClanDiscover {
            pageNumber = dto.page_number
            pageCount = dto.page_count.coerceAtLeast(1)
            for (c in dto.clan_discover) {
                clanDiscover += clanDiscoverProto {
                    clanId = c.clan_id
                    clanName = c.clan_name
                    inviteId = c.invite_id
                    clanLogo = c.clan_logo
                    onlineMembers = c.online_members
                    totalMembers = c.total_members
                    verified = c.verified
                    description = c.description
                    banner = c.banner
                    about = c.about
                    shortUrl = c.short_url
                    createTimeSeconds = c.create_time_seconds
                }
            }
        }
    }

    suspend fun inviteUserByInviteId(
        apiUrl: String,
        token: String,
        inviteId: Long
    ): InviteUserRes {
        val request = inviteUserRequest {
            this.inviteId = inviteId
        }
        val bytes = rpc(apiUrl, token, "InviteUser", request.toByteArray())
        return InviteUserRes.parseFrom(bytes)
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

    suspend fun updateUserStatus(
        apiUrl: String,
        token: String,
        status: String,
        minutes: Int = 0,
        untilTurnOn: Boolean = true
    ): ByteArray {
        val request = com.mezon.mezon.api.UserStatusUpdate.newBuilder()
            .setStatus(status)
            .setMinutes(minutes)
            .setUntilTurnOn(untilTurnOn)
            .build()
        return rpc(apiUrl, token, "UpdateUserStatus", request.toByteArray())
    }

    suspend fun getUserStatus(
        apiUrl: String,
        token: String
    ): com.mezon.mezon.api.UserStatus {
        val bytes = rpc(apiUrl, token, "GetUserStatus", ByteArray(0))
        return com.mezon.mezon.api.UserStatus.parseFrom(bytes)
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

    suspend fun listPinMessages(
        apiUrl: String,
        token: String,
        channelId: Long,
        clanId: Long
    ): PinMessagesList {
        val request = pinMessageRequest {
            this.channelId = channelId
            this.clanId = clanId
        }
        val bytes = rpc(apiUrl, token, "GetPinMessagesList", request.toByteArray())
        return PinMessagesList.parseFrom(bytes)
    }

    suspend fun createPinMessage(
        apiUrl: String,
        token: String,
        channelId: Long,
        clanId: Long,
        messageId: Long
    ): ByteArray {
        val request = pinMessageRequest {
            this.messageId = messageId
            this.channelId = channelId
            this.clanId = clanId
        }
        return rpc(apiUrl, token, "CreatePinMessage", request.toByteArray())
    }

    suspend fun deletePinMessage(
        apiUrl: String,
        token: String,
        messageId: Long,
        channelId: Long,
        clanId: Long
    ): ByteArray {
        val request = deletePinMessage {
            this.messageId = messageId
            this.channelId = channelId
            this.clanId = clanId
        }
        return rpc(apiUrl, token, "DeletePinMessage", request.toByteArray())
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

    suspend fun listRoles(
        apiUrl: String,
        token: String,
        clanId: Long,
        limit: Int = 500,
        state: Int = 1,
        cursor: String = ""
    ): com.mezon.mezon.api.RoleListEventResponse {
        val request = com.mezon.mezon.api.roleListEventRequest {
            this.clanId = clanId
            this.limit = limit
            this.state = state
            this.cursor = cursor
        }
        val bytes = rpc(apiUrl, token, "ListRoles", request.toByteArray())
        return com.mezon.mezon.api.RoleListEventResponse.parseFrom(bytes)
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

    suspend fun listChannelUsersUC(
        apiUrl: String,
        token: String,
        channelId: Long,
        limit: Int = 500
    ): AllUsersAddChannelResponse {
        val request = allUsersAddChannelRequest {
            this.channelId = channelId
            this.limit = limit
        }
        val bytes = rpc(apiUrl, token, "ListChannelUsersUC", request.toByteArray())
        return AllUsersAddChannelResponse.parseFrom(bytes)
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

    suspend fun listEmojisByUserId(
        apiUrl: String,
        token: String
    ): EmojiListedResponse {
        val bytes = rpc(apiUrl, token, "GetListEmojisByUserId", ByteArray(0))
        return EmojiListedResponse.parseFrom(bytes)
    }

    suspend fun listStickersByUserId(
        apiUrl: String,
        token: String
    ): StickerListedResponse {
        val bytes = rpc(apiUrl, token, "GetListStickersByUserId", ByteArray(0))
        return StickerListedResponse.parseFrom(bytes)
    }

    suspend fun generateMeetToken(
        apiUrl: String,
        token: String,
        channelId: Long,
        roomName: String
    ): GenerateMeetTokenResponse {
        val request = generateMeetTokenRequest {
            this.channelId = channelId
            this.roomName = roomName
        }
        val bytes = rpc(apiUrl, token, "GenerateMeetToken", request.toByteArray())
        return GenerateMeetTokenResponse.parseFrom(bytes)
    }

    suspend fun listChannelVoiceUsers(
        apiUrl: String,
        token: String,
        clanId: Long
    ): VoiceChannelUserList {
        val request = listChannelUsersRequest {
            this.clanId = clanId
            this.limit = 100
            this.state = 1
        }
        val bytes = rpc(apiUrl, token, "ListChannelVoiceUsers", request.toByteArray())
        return VoiceChannelUserList.parseFrom(bytes)
    }

    suspend fun removeMeetParticipant(
        apiUrl: String,
        token: String,
        clanId: Long,
        channelId: Long,
        roomName: String,
        username: String
    ): ByteArray {
        val request = meetParticipantRequest {
            this.clanId = clanId
            this.channelId = channelId
            this.roomName = roomName
            this.username = username
        }
        return rpc(apiUrl, token, "RemoveParticipantMezonMeet", request.toByteArray())
    }

    suspend fun muteMeetParticipant(
        apiUrl: String,
        token: String,
        clanId: Long,
        channelId: Long,
        roomName: String,
        username: String
    ): ByteArray {
        val request = meetParticipantRequest {
            this.clanId = clanId
            this.channelId = channelId
            this.roomName = roomName
            this.username = username
        }
        return rpc(apiUrl, token, "MuteParticipantMezonMeet", request.toByteArray())
    }

    suspend fun addAgentToChannel(
        apiUrl: String,
        token: String,
        channelId: Long,
        roomName: String
    ): ByteArray {
        val request = updateAIAgentRequest {
            this.channelId = channelId
            this.roomName = roomName
        }
        return rpc(apiUrl, token, "AddAgentToChannel", request.toByteArray())
    }

    suspend fun disconnectAgent(
        apiUrl: String,
        token: String,
        channelId: Long,
        roomName: String
    ): ByteArray {
        val request = updateAIAgentRequest {
            this.channelId = channelId
            this.roomName = roomName
        }
        return rpc(apiUrl, token, "DisconnectAgent", request.toByteArray())
    }

    suspend fun listFavoriteChannels(
        apiUrl: String,
        token: String,
        clanId: Long
    ): ListFavoriteChannelResponse {
        val request = listFavoriteChannelRequest {
            this.clanId = clanId
        }
        val bytes = rpc(apiUrl, token, "GetListFavoriteChannel", request.toByteArray())
        return ListFavoriteChannelResponse.parseFrom(bytes)
    }

    suspend fun addFavoriteChannel(
        apiUrl: String,
        token: String,
        channelId: Long,
        clanId: Long
    ): AddFavoriteChannelResponse {
        val request = addFavoriteChannelRequest {
            this.channelId = channelId
            this.clanId = clanId
        }
        val bytes = rpc(apiUrl, token, "AddChannelFavorite", request.toByteArray())
        return AddFavoriteChannelResponse.parseFrom(bytes)
    }

    suspend fun removeFavoriteChannel(
        apiUrl: String,
        token: String,
        clanId: Long,
        channelId: Long
    ) {
        val request = removeFavoriteChannelRequest {
            this.clanId = clanId
            this.channelId = channelId
        }
        rpc(apiUrl, token, "RemoveChannelFavorite", request.toByteArray())
    }

    suspend fun listChannelApps(
        apiUrl: String,
        token: String,
        clanId: Long
    ): ListChannelAppsResponse {
        val request = listChannelAppsRequest {
            this.clanId = clanId
        }
        val bytes = rpc(apiUrl, token, "ListChannelApps", request.toByteArray())
        return ListChannelAppsResponse.parseFrom(bytes)
    }

    suspend fun generateHashChannelApps(
        apiUrl: String,
        token: String,
        appId: Long
    ): GenerateHashChannelAppsResponse {
        val request = generateHashChannelAppsRequest {
            this.appId = appId
        }
        val bytes = rpc(apiUrl, token, "GenerateHashChannelApps", request.toByteArray())
        return GenerateHashChannelAppsResponse.parseFrom(bytes)
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
