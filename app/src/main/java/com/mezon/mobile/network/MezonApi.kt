
package com.mezon.mobile.network

import com.mezon.mobile.BuildConfig
import com.mezon.mobile.util.SentryReporter
import android.net.Uri
import android.util.Base64
import com.mezon.mezon.api.Account
import com.mezon.mezon.api.AllUsersAddChannelResponse
import com.mezon.mezon.api.AllUserClans
import com.mezon.mezon.api.allUsersAddChannelRequest
import com.mezon.mezon.api.addChannelUsersRequest
import com.mezon.mezon.api.addRoleChannelDescRequest
import com.mezon.mezon.api.CategoryDesc
import com.mezon.mezon.api.CategoryDescList
import com.mezon.mezon.api.categoryDesc
import com.mezon.mezon.api.ClanDesc
import com.mezon.mezon.api.EmojiListedResponse
import com.mezon.mezon.api.StickerListedResponse
import com.mezon.mezon.api.clanStickerAddRequest
import com.mezon.mezon.api.clanStickerUpdateByIdRequest
import com.mezon.mezon.api.clanStickerDeleteRequest
import com.mezon.mezon.api.ChannelDescList
import com.mezon.mezon.api.ChannelDescription
import com.mezon.mezon.api.ChannelMessageList
import com.mezon.mezon.api.createChannelDescRequest
import com.mezon.mezon.api.ClanDescList
import com.mezon.mezon.api.NotificationSetting
import com.mezon.mezon.api.SystemMessage
import com.mezon.mezon.api.SystemMessageRequest
import com.mezon.mezon.api.PinMessagesList
import com.mezon.mezon.api.pinMessageRequest
import com.mezon.mezon.api.deletePinMessage
import com.mezon.mezon.api.reportMessageAbuseReqest
import com.mezon.mezon.api.ChannelUserList
import com.mezon.mezon.api.ClanUserList
import com.mezon.mezon.api.FriendList
import com.mezon.mezon.api.NotificationList
import com.mezon.mezon.api.SearchMessageResponse
import com.mezon.mezon.api.ChannelAttachmentList
import com.mezon.mezon.api.UploadAttachment
import com.mezon.mezon.api.MultipartUploadAttachment
import com.mezon.mezon.api.listChannelAttachmentRequest
import com.mezon.mezon.api.uploadAttachmentRequest
import com.mezon.mezon.api.multipartUploadAttachmentFinishRequest
import com.mezon.mezon.api.multipartUploadAttachmentPart
import com.mezon.mezon.api.accountEmail
import com.mezon.mezon.api.AddFriendsResponse
import com.mezon.mezon.api.addFriendsRequest
import com.mezon.mezon.api.blockFriendsRequest
import com.mezon.mezon.api.changeChannelPrivateRequest
import com.mezon.mezon.api.changeChannelCategoryRequest
import com.mezon.mezon.api.checkDuplicateNameRequest
import com.mezon.mezon.api.deleteChannelDescRequest
import com.mezon.mezon.api.updateChannelDescRequest
import com.mezon.mezon.api.listCategoryDescsRequest
import com.mezon.mezon.api.CheckDuplicateNameResponse
import com.mezon.mezon.api.listQuickMenuAccessRequest
import com.mezon.mezon.api.QuickMenuAccessList
import com.mezon.mezon.api.quickMenuAccess
import com.mezon.mezon.api.bannedUserListRequest
import com.mezon.mezon.api.BannedUserList
import com.mezon.mezon.api.banClanUsersRequest
import com.mezon.mezon.api.leaveThreadRequest
import com.mezon.mezon.api.createCategoryDescRequest
import com.mezon.mezon.api.createClanDescRequest
import com.mezon.mezon.api.deleteClanDescRequest
import com.mezon.mezon.api.getSystemMessage
import com.mezon.mezon.api.notificationClan
import com.mezon.mezon.api.setDefaultNotificationRequest
import com.mezon.mezon.api.updateClanDescRequest
import com.mezon.mezon.api.updateChannelDescRequest
import com.mezon.mezon.api.deleteFriendsRequest
import com.mezon.mezon.api.deleteNotificationsRequest
import com.mezon.mezon.api.filterParam
import com.mezon.mezon.api.linkAccountConfirmRequest
import com.mezon.mezon.api.listClanDescRequest
import com.mezon.mezon.api.listChannelUsersRequest
import com.mezon.mezon.api.listClanUsersRequest
import com.mezon.mezon.api.removeChannelUsersRequest
import com.mezon.mezon.api.removeClanUsersRequest
import com.mezon.mezon.api.ListChannelAppsResponse
import com.mezon.mezon.api.GenerateHashChannelAppsResponse
import com.mezon.mezon.api.listChannelAppsRequest
import com.mezon.mezon.api.generateHashChannelAppsRequest
import com.mezon.mezon.api.App
import com.mezon.mezon.api.appId
import com.mezon.mezon.api.appClan
import com.mezon.mezon.api.ListChannelBadgeCountResponse
import com.mezon.mezon.api.ListClanBadgeCountResponse
import com.mezon.mezon.api.listChannelBadgeCountRequest
import com.mezon.mezon.api.listChannelDescsRequest
import com.mezon.mezon.api.SdTopic
import com.mezon.mezon.api.SdTopicList
import com.mezon.mezon.api.listSdTopicRequest
import com.mezon.mezon.api.sdTopicRequest
import com.mezon.mezon.api.sdTopicDetailRequest
import com.mezon.mezon.api.listThreadRequest
import com.mezon.mezon.api.ListFavoriteChannelResponse
import com.mezon.mezon.api.AddFavoriteChannelResponse
import com.mezon.mezon.api.addFavoriteChannelRequest
import com.mezon.mezon.api.listFavoriteChannelRequest
import com.mezon.mezon.api.removeFavoriteChannelRequest
import com.mezon.mezon.api.CreatePollResponse
import com.mezon.mezon.api.GetPollResponse
import com.mezon.mezon.api.VotePollResponse
import com.mezon.mezon.api.createPollRequest
import com.mezon.mezon.api.getPollRequest
import com.mezon.mezon.api.listChannelMessagesRequest
import com.mezon.mezon.api.votePollRequest
import com.mezon.mezon.api.listFriendsRequest
import com.mezon.mezon.api.listNotificationsRequest
import com.mezon.mezon.api.searchMessageRequest
import com.mezon.mezon.api.sessionRefreshRequest
import com.mezon.mezon.api.Session
import com.mezon.mezon.api.updateUsernameRequest
import com.mezon.mezon.api.GenerateMeetTokenResponse
import com.mezon.mezon.api.VoiceChannelUserList
import com.mezon.mezon.api.generateMeetTokenRequest
import com.mezon.mezon.api.meetParticipantRequest
import com.mezon.mezon.api.messageReaction
import com.mezon.mezon.api.updateAIAgentRequest
import com.mezon.mezon.api.LogedDeviceList
import com.mezon.mezon.api.ListClanDiscover
import com.mezon.mezon.api.ListAuditLog
import com.mezon.mezon.api.WebhookListResponse
import com.mezon.mezon.api.WebhookGenerateResponse
import com.mezon.mezon.api.ListClanWebhookResponse
import com.mezon.mezon.api.GenerateClanWebhookResponse
import com.mezon.mezon.api.clanWebhookRequest
import com.mezon.mezon.api.generateClanWebhookRequest
import com.mezon.mezon.api.listClanWebhookRequest
import com.mezon.mezon.api.updateClanWebhookRequest
import com.mezon.mezon.api.webhookCreateRequest
import com.mezon.mezon.api.webhookDeleteRequestById
import com.mezon.mezon.api.webhookListRequest
import com.mezon.mezon.api.webhookUpdateRequestById
import com.mezon.mezon.api.InviteUserRes
import com.mezon.mezon.api.LinkInviteUser
import com.mezon.mezon.api.inviteUserRequest
import com.mezon.mezon.api.linkInviteUserRequest
import com.mezon.mezon.api.clanDiscover as clanDiscoverProto
import com.mezon.mezon.api.listClanDiscover
import com.mezon.mezon.api.clanEmojiCreateRequest
import com.mezon.mezon.api.clanEmojiDeleteRequest
import com.mezon.mezon.api.clanEmojiUpdateRequest
import com.mezon.mezon.api.listAuditLogRequest
import com.mezon.mezon.rtapi.ActiveArchivedThread
import com.mezon.mezon.rtapi.ChannelMessageSend
import com.mezon.mezon.rtapi.ListActivity
import com.mezon.mezon.rtapi.messageButtonClicked
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readBytes
import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.http.HttpHeaders
import io.ktor.util.cio.readChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.json.JSONObject
import com.google.protobuf.BoolValue
import com.google.protobuf.StringValue
import com.mezon.mezon.api.PermissionList
import com.mezon.mezon.api.PermissionRoleChannelListEventResponse
import com.mezon.mezon.api.PermissionUpdate
import com.mezon.mezon.api.Role
import com.mezon.mezon.api.RoleUserList
import com.mezon.mezon.api.UserPermissionInChannelListResponse
import com.mezon.mezon.api.createRoleRequest
import com.mezon.mezon.api.deleteRoleRequest
import com.mezon.mezon.api.listRoleUsersRequest
import com.mezon.mezon.api.permissionRoleChannelListEventRequest
import com.mezon.mezon.api.updateRoleRequest
import com.mezon.mezon.api.updateRoleChannelRequest
import com.mezon.mezon.api.userPermissionInChannelListRequest
import kotlinx.coroutines.CompletableDeferred
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
class UnauthorizedException(message: String) : RuntimeException(message)

class SocketRpcTransportException(
    message: String,
    val retryOverHttp: Boolean = true,
    cause: Throwable? = null
) : RuntimeException(message, cause)

class SocketRpcServerException(
    message: String,
    val code: Int,
    cause: Throwable? = null
) : RuntimeException(message, cause)

class HttpRpcStatusException(
    message: String,
    val code: Int,
    cause: Throwable? = null
) : RuntimeException(message, cause)

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
    @SerialName("id_token") val idToken: String = "",
    val username: String? = null,
    val created: Boolean? = null
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

@Serializable
private data class ConfirmLoginGatewayBody(
    @SerialName("login_id") val loginId: String,
    @SerialName("is_remember") val isRemember: Boolean = true
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
    private val httpClient: HttpClient,
    private val mezonSocketLazy: dagger.Lazy<MezonSocket>,
    private val sentryReporter: SentryReporter
) {
    private data class InFlightReadRpc(
        val startedAtMs: Long,
        val deferred: CompletableDeferred<ByteArray>
    )

    private data class ReadRpcFlight(
        val entry: InFlightReadRpc,
        val owner: Boolean
    )

    companion object {
        private val SERVER_KEY = BuildConfig.MEZON_API_KEY
        private const val DISCOVER_ITEMS_PER_PAGE = 6
        private const val SOCKET_WAIT_MS = 5_000L
        private const val MAX_CONSECUTIVE_SOCKET_TIMEOUTS = 3
        private const val READ_SINGLE_FLIGHT_MAX_AGE_MS = 3_000L
        private val HTTP_RETRY_DELAYS_MS = longArrayOf(300L, 900L)
        private val HTTP_ONLY_API_NAMES = setOf(
            "SessionRefresh",
            "RegistFCMDeviceToken",
            "SendChannelMessage"
        )
        private val SOCKET_RPC_API_NAMES = setOf(
            "GetAccount",
            "GetListEmojisByUserId",
            "GetListFavoriteChannel",
            "GetListStickersByUserId",
            "GetNotificationClan",
            "GetPinMessagesList",
            "GetPoll",
            "GetSystemMessageByClanId",
            "GetUserProfileOnClan",
            "GetUserStatus",
            "ListActivity",
            "ListAuditLog",
            "ListChannelApps",
            "ListChannelAttachment",
            "ListCategoryDescs",
            "ListChannelBadgeCount",
            "ListChannelByUserId",
            "ListChannelDescs",
            "ListChannelMessages",
            "ListChannelUsers",
            "ListChannelUsersUC",
            "ListChannelVoiceUsers",
            "ListClanBadgeCount",
            "ListClanDescs",
            "ListClanUsers",
            "ListClanWebhook",
            "ListFriends",
            "ListLogedDevice",
            "ListNotifications",
            "ListRoles",
            "ListThreadDescs",
            "ListUserClansByUserId",
            "ListWebhookByChannelId",
            "SearchMessage"
        )
        private val READ_RETRYABLE_API_NAMES = SOCKET_RPC_API_NAMES
    }

    private val linkInvitePreviewCache = android.util.LruCache<Long, LinkInvitePreview>(256)
    private val inFlightReadRpcs = ConcurrentHashMap<String, InFlightReadRpc>()

    private val consecutiveSocketTimeouts = AtomicInteger(0)

    private fun logRpcRequest(method: String, url: String) {
        if (!BuildConfig.DEBUG) return
        // Avoid a single https://… token — Logcat URL scrubbing replaces it with asterisks.
        val path = "mezon.api.Mezon/$method"
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        val host = uri?.host?.replace('.', '|') ?: "?"
        Log.d("MezonApi", "rpc method=$method path=$path host=$host")
    }

    private fun logRpcHttpError(method: String, response: HttpResponse, requestByteSize: Int, errorBody: String) {
        val meta = StringBuilder()
        val keys = arrayOf(
            "grpc-message",
            "grpc-status",
            "www-authenticate",
            "x-request-id",
            "x-amzn-requestid",
            "x-amzn-errortype",
            "x-trace-id"
        )
        for (k in keys) {
            val v = response.headers[k]
            if (!v.isNullOrBlank()) meta.append(k).append('=').append(v).append("; ")
        }
        if (errorBody.isEmpty() && meta.isEmpty()) {
            response.headers.forEach { name, values ->
                if (values.isNotEmpty()) {
                    meta.append(name).append('=').append(values.joinToString(",")).append("; ")
                }
            }
        }
        val errPreview = if (errorBody.isEmpty()) "(empty)" else errorBody.take(1500)
        Log.w(
            "MezonApi",
            "rpcFail method=$method http=${response.status.value} reqBytes=$requestByteSize errLen=${errorBody.length} err=$errPreview meta=$meta"
        )
        sentryReporter.logRpcFailure(
            method = method,
            transport = "http",
            detail = errPreview,
            httpCode = response.status.value
        )
    }

    private fun gatewayJsonErrorUserMessage(rawBody: String): String {
        val t = rawBody.trim()
        if (t.isEmpty()) return t
        return try {
            val obj = JSONObject(t)
            val msg = obj.optString("message", "").trim()
            if (msg.isNotEmpty()) msg else t
        } catch (_: Exception) {
            t
        }
    }

    suspend fun getLinkInvitePreview(inviteId: Long): LinkInvitePreview? {
        if (inviteId == 0L) return null
        synchronized(linkInvitePreviewCache) {
            linkInvitePreviewCache.get(inviteId)?.let { return it }
        }
        return try {
            val gatewayUrl = BuildConfig.MEZON_GATEWAY_URL.trimEnd('/')
            val url = "$gatewayUrl/v2/invite/$inviteId?"
            val basicCreds = Base64.encodeToString(
                "$SERVER_KEY:".toByteArray(),
                Base64.NO_WRAP
            )
            val response = httpClient.get(url) {
                header(HttpHeaders.Authorization, "Basic $basicCreds")
                header(HttpHeaders.Accept, ContentType.Application.Json.toString())
            }
            if (!response.status.isSuccess()) {
                return null
            }
            val text = response.bodyAsText()
            val obj = JSONObject(text)
            val clanName = obj.optString("clan_name", "")
                .ifEmpty { obj.optString("clanName", "") }
                .trim()
            val channelLabel = obj.optString("channel_label", "")
                .ifEmpty { obj.optString("channelLabel", "") }
                .trim()
            val logo = obj.optString("clan_logo", "")
                .ifEmpty { obj.optString("clanLogo", "") }
                .trim()
            val memberCount = obj.optInt("member_count", obj.optInt("memberCount", 0))
            val preview = LinkInvitePreview(
                clanName = clanName,
                channelLabel = channelLabel,
                logoUrl = logo,
                memberCount = memberCount,
            )
            synchronized(linkInvitePreviewCache) {
                linkInvitePreviewCache.put(inviteId, preview)
            }
            preview
        } catch (_: Exception) {
            null
        }
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
            setBody(AuthEmailBody(account = AccountEmailBody(email = email, password = password)))
        }

        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            throw RuntimeException(gatewayJsonErrorUserMessage(errorBody))
        }

        return response.body()
    }

    suspend fun rpc(
        apiUrl: String,
        token: String,
        method: String,
        body: ByteArray,
        preferHttp: Boolean = false
    ): ByteArray {
        val retryableRead = method in READ_RETRYABLE_API_NAMES
        if (retryableRead) {
            val key = readRpcKey(apiUrl, token, method, body, preferHttp)
            val flight = startOrJoinReadRpc(key)
            if (!flight.owner) return flight.entry.deferred.await()
            try {
                val bytes = executeRpc(apiUrl, token, method, body, preferHttp, retryableRead)
                flight.entry.deferred.complete(bytes)
                return bytes
            } catch (e: Throwable) {
                flight.entry.deferred.completeExceptionally(e)
                throw e
            } finally {
                inFlightReadRpcs.remove(key, flight.entry)
            }
        }
        return executeRpc(apiUrl, token, method, body, preferHttp, retryableRead)
    }

    private suspend fun executeRpc(
        apiUrl: String,
        token: String,
        method: String,
        body: ByteArray,
        preferHttp: Boolean,
        retryableRead: Boolean
    ): ByteArray {
        if (preferHttp || method in HTTP_ONLY_API_NAMES || method !in SOCKET_RPC_API_NAMES) {
            return rpcOverHttpWithRetry(apiUrl, token, method, body, retryableRead)
        }
        return try {
            rpcOverSocket(method, body, token)
        } catch (e: UnauthorizedException) {
            throw e
        } catch (e: SocketRpcServerException) {
            throw e
        } catch (e: SocketRpcTransportException) {
            if (!retryableRead || !e.retryOverHttp) throw e
            Log.w("MezonApi", "SOCKET unavailable method=$method, falling back to HTTP: ${e.message}")
            sentryReporter.logRpcWarning(method, "socket", "fallback to HTTP: ${e.message}")
            rpcOverHttpWithRetry(apiUrl, token, method, body, true)
        } catch (e: IllegalArgumentException) {
            throw e
        }
    }

    private fun startOrJoinReadRpc(key: String): ReadRpcFlight {
        val now = System.currentTimeMillis()
        synchronized(inFlightReadRpcs) {
            val existing = inFlightReadRpcs[key]
            if (existing != null && now - existing.startedAtMs <= READ_SINGLE_FLIGHT_MAX_AGE_MS) {
                return ReadRpcFlight(existing, false)
            }
            val entry = InFlightReadRpc(now, CompletableDeferred())
            inFlightReadRpcs[key] = entry
            return ReadRpcFlight(entry, true)
        }
    }

    private fun readRpcKey(
        apiUrl: String,
        token: String,
        method: String,
        body: ByteArray,
        preferHttp: Boolean
    ): String {
        val base = apiUrl.trimEnd('/')
        val tokenHash = sha256Base64(token.toByteArray(Charsets.UTF_8))
        val bodyHash = sha256Base64(body)
        return "$base|$method|$preferHttp|$tokenHash|$bodyHash"
    }

    private fun sha256Base64(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    private suspend fun rpcOverSocket(method: String, body: ByteArray, token: String): ByteArray {
        val socket = mezonSocketLazy.get()
        if (!socket.awaitConnected(SOCKET_WAIT_MS)) {
            throw SocketRpcTransportException(
                "WebSocket unavailable for '$method'",
                retryOverHttp = true
            )
        }
        val sessionTokenFp = if (token.isEmpty()) "?" else token.takeLast(6)
        val socketTokenFp = socket.socketTokenFingerprint
        if (socketTokenFp != null && socketTokenFp != sessionTokenFp) {
            sentryReporter.logRpcWarning(
                method,
                "socket",
                "TOKEN_MISMATCH session=$sessionTokenFp socket=$socketTokenFp gen=${socket.connectGen}"
            )
        }
        val started = System.currentTimeMillis()
        try {
            val resp = socket.sendApiRequest(apiName = method, body = body)
            if (BuildConfig.DEBUG) {
                Log.d(
                    "MezonApi",
                    "SOCKET ok method=$method respBytes=${resp.size} elapsedMs=${System.currentTimeMillis() - started}"
                )
            }
            consecutiveSocketTimeouts.set(0)
            return resp
        } catch (e: Exception) {
            Log.w(
                "MezonApi",
                "SOCKET fail method=$method elapsedMs=${System.currentTimeMillis() - started} err=${e.message}"
            )
            if (e !is UnauthorizedException && e !is SocketRpcServerException && e !is IllegalArgumentException) {
                sentryReporter.logRpcFailure(method, "socket", e)
            }
            if (e is UnauthorizedException) {
                consecutiveSocketTimeouts.set(0)
                socket.forceReconnectForAuthFailure("Socket RPC unauthorized for '$method'")
                throw e
            }
            if (e is SocketRpcServerException || e is IllegalArgumentException) {
                consecutiveSocketTimeouts.set(0)
                throw e
            }
            if (isSocketTransportFailure(e)) {
                val streak = consecutiveSocketTimeouts.incrementAndGet()
                if (streak >= MAX_CONSECUTIVE_SOCKET_TIMEOUTS) {
                    consecutiveSocketTimeouts.set(0)
                    socket.forceReconnect(
                        "consecutive socket RPC transport failures=$streak (last method='$method')"
                    )
                }
                throw SocketRpcTransportException(
                    "WebSocket transport failed for '$method': ${e.message}",
                    retryOverHttp = true,
                    cause = e
                )
            }
            throw e
        }
    }

    private suspend fun rpcOverHttpWithRetry(
        apiUrl: String,
        token: String,
        method: String,
        body: ByteArray,
        retryable: Boolean
    ): ByteArray {
        var attempt = 0
        while (true) {
            try {
                return rpcOverHttp(apiUrl, token, method, body)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (!retryable || !isHttpRetryableFailure(e) || attempt >= HTTP_RETRY_DELAYS_MS.size) {
                    if (e !is HttpRpcStatusException) {
                        sentryReporter.logRpcFailure(method, "http", e)
                    }
                    throw e
                }
                val delayMs = HTTP_RETRY_DELAYS_MS[attempt]
                Log.w(
                    "MezonApi",
                    "HTTP retry method=$method attempt=${attempt + 1}/${HTTP_RETRY_DELAYS_MS.size} delayMs=$delayMs err=${e.message}"
                )
                attempt++
                delay(delayMs)
            }
        }
    }

    private suspend fun rpcOverHttp(
        apiUrl: String,
        token: String,
        method: String,
        body: ByteArray
    ): ByteArray {
        val base = apiUrl.trimEnd('/')
        val url = "$base/mezon.api.Mezon/$method"
        logRpcRequest(method, url)
        val started = System.currentTimeMillis()
        val response = httpClient.post(url) {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, CONTENT_TYPE_PROTO.toString())
            contentType(CONTENT_TYPE_PROTO)
            setBody(body)
        }

        if (!response.status.isSuccess()) {
            val errorBody = try {
                response.bodyAsText()
            } catch (e: Exception) {
                "(body:${e.message})"
            }
            logRpcHttpError(method, response, body.size, errorBody)
            if (response.status == HttpStatusCode.Unauthorized) {
                throw UnauthorizedException("RPC $method: 401 Unauthorized")
            }
            throw HttpRpcStatusException(
                "RPC $method failed (${response.status.value}): $errorBody",
                response.status.value
            )
        }

        val bytes = response.readBytes()
        if (BuildConfig.DEBUG) {
            Log.d(
                "MezonApi",
                "HTTP ok method=$method status=${response.status.value} bytes=${bytes.size} elapsedMs=${System.currentTimeMillis() - started}"
            )
        }
        return bytes
    }

    private fun isSocketTransportFailure(e: Throwable): Boolean {
        if (e is SocketRpcTransportException) return true
        if (e is SocketRpcServerException || e is UnauthorizedException || e is IllegalArgumentException) return false
        val message = e.message.orEmpty()
        if (message.startsWith("Server error")) return false
        if (message.contains("WebSocket not connected") ||
            message.contains("Connection closed") ||
            message.contains("Connection failed") ||
            message.contains("Failed to enqueue WebSocket") ||
            message.contains("timed out", ignoreCase = true) ||
            message.contains("timeout", ignoreCase = true)
        ) {
            return true
        }
        return hasCause<IOException>(e)
    }

    private fun isHttpRetryableFailure(e: Throwable): Boolean {
        if (e is UnauthorizedException) return false
        if (e is HttpRpcStatusException) {
            return e.code == 408 || e.code == 429 || e.code in 500..599
        }
        return hasCause<IOException>(e)
    }

    private inline fun <reified T : Throwable> hasCause(e: Throwable): Boolean {
        var current: Throwable? = e
        while (current != null) {
            if (current is T) return true
            current = current.cause
        }
        return false
    }

    private suspend fun rpcNoAuth(
        apiUrl: String,
        method: String,
        body: ByteArray
    ): ByteArray {
        val base = apiUrl.trimEnd('/')
        val url = "$base/mezon.api.Mezon/$method"
        logRpcRequest(method, url)
        val response = httpClient.post(url) {
            header(HttpHeaders.Accept, CONTENT_TYPE_PROTO.toString())
            contentType(CONTENT_TYPE_PROTO)
            setBody(body)
        }

        if (!response.status.isSuccess()) {
            val errorBody = try {
                response.bodyAsText()
            } catch (e: Exception) {
                "(body:${e.message})"
            }
            logRpcHttpError(method, response, body.size, errorBody)
            throw RuntimeException("RPC $method failed (${response.status.value}): $errorBody")
        }

        return response.readBytes()
    }

    suspend fun confirmLoginRequest(
        gatewayUrl: String,
        token: String,
        loginId: Long
    ): AuthSessionResponse {
        val base = gatewayUrl.trimEnd('/')
        val url = "$base/v2/account/authenticate/confirmlogin"
        val response = httpClient.post(url) {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(ConfirmLoginGatewayBody(loginId = loginId.toString(), isRemember = true))
        }
        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            val msg = gatewayJsonErrorUserMessage(errorBody)
            if (response.status == HttpStatusCode.Unauthorized) {
                throw UnauthorizedException(msg)
            }
            throw RuntimeException(msg)
        }
        if (response.status == HttpStatusCode.NoContent) {
            return AuthSessionResponse()
        }
        return response.body()
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

    suspend fun updateChannelDesc(
        apiUrl: String,
        token: String,
        clanId: Long,
        channelId: Long,
        channelLabel: String? = null,
        channelAvatar: String? = null
    ): ByteArray {
        val request = updateChannelDescRequest {
            this.clanId = clanId
            this.channelId = channelId
            channelLabel?.let { this.channelLabel = StringValue.of(it) }
            channelAvatar?.let { this.channelAvatar = StringValue.of(it) }
        }
        return rpc(apiUrl, token, "UpdateChannelDesc", request.toByteArray())
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

    suspend fun updateClanDesc(
        apiUrl: String,
        token: String,
        clanId: Long,
        logo: String? = null,
        clearLogo: Boolean = false,
        clanName: String? = null,
        banner: String? = null,
        clearBanner: Boolean = false,
        preventAnonymous: Boolean? = null,
        welcomeChannelId: Long? = null,
        isOnboarding: Boolean? = null,
        isCommunity: Boolean? = null,
        communityBanner: String? = null,
        clearCommunityBanner: Boolean = false,
        about: String? = null,
        description: String? = null,
        shortUrl: String? = null,
    ): ClanDesc {
        val request = updateClanDescRequest {
            this.clanId = clanId
            when {
                logo != null -> this.logo = StringValue.of(logo)
                clearLogo -> this.logo = StringValue.of("")
            }
            if (clanName != null) {
                this.clanName = clanName
            }
            when {
                banner != null && banner.isNotEmpty() -> this.banner = StringValue.of(banner)
                banner != null && banner.isEmpty() -> this.banner = StringValue.of("")
                clearBanner -> this.banner = StringValue.of("")
            }
            preventAnonymous?.let { this.preventAnonymous = it }
            welcomeChannelId?.let { this.welcomeChannelId = it }
            isOnboarding?.let { this.isOnboarding = BoolValue.of(it) }
            isCommunity?.let { this.isCommunity = BoolValue.of(it) }
            when {
                communityBanner != null -> this.communityBanner = StringValue.of(communityBanner)
                clearCommunityBanner -> this.communityBanner = StringValue.of("")
            }
            about?.let { this.about = StringValue.of(it) }
            description?.let { this.description = StringValue.of(it) }
            shortUrl?.let { this.shortUrl = StringValue.of(it) }
        }
        val bytes = rpc(apiUrl, token, "UpdateClanDesc", request.toByteArray())
        return ClanDesc.parseFrom(bytes)
    }

    suspend fun getSystemMessageForClan(
        apiUrl: String,
        token: String,
        clanId: Long,
    ): SystemMessage {
        val request = getSystemMessage { this.clanId = clanId }
        val bytes = rpc(apiUrl, token, "GetSystemMessageByClanId", request.toByteArray())
        return SystemMessage.parseFrom(bytes)
    }

    suspend fun createLinkInviteUser(
        apiUrl: String,
        token: String,
        clanId: Long,
        channelId: Long,
        expiryTime: Int = 10,
    ): LinkInviteUser {
        val request = linkInviteUserRequest {
            this.clanId = clanId
            this.channelId = channelId
            this.expiryTime = expiryTime
        }
        val bytes = rpc(apiUrl, token, "CreateLinkInviteUser", request.toByteArray())
        return LinkInviteUser.parseFrom(bytes)
    }

    suspend fun updateSystemMessage(
        apiUrl: String,
        token: String,
        body: SystemMessageRequest,
    ): SystemMessage {
        val bytes = rpc(apiUrl, token, "UpdateSystemMessage", body.toByteArray())
        return SystemMessage.parseFrom(bytes)
    }

    suspend fun getClanDefaultNotification(
        apiUrl: String,
        token: String,
        clanId: Long,
    ): NotificationSetting {
        val request = notificationClan { this.clanId = clanId }
        val bytes = rpc(apiUrl, token, "GetNotificationClan", request.toByteArray())
        return NotificationSetting.parseFrom(bytes)
    }

    suspend fun setClanDefaultNotification(
        apiUrl: String,
        token: String,
        clanId: Long,
        notificationType: Int,
    ) {
        val request = setDefaultNotificationRequest {
            this.clanId = clanId
            this.notificationType = notificationType
        }
        rpc(apiUrl, token, "SetNotificationClanSetting", request.toByteArray())
    }

    suspend fun deleteClanDesc(
        apiUrl: String,
        token: String,
        clanId: Long,
    ) {
        val request = deleteClanDescRequest { this.clanDescId = clanId }
        rpc(apiUrl, token, "DeleteClanDesc", request.toByteArray())
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

    suspend fun listCategoryDescs(
        apiUrl: String,
        token: String,
        clanId: Long
    ): CategoryDescList {
        val request = categoryDesc {
            this.clanId = clanId
        }
        val bytes = rpc(apiUrl, token, "ListCategoryDescs", request.toByteArray())
        return CategoryDescList.parseFrom(bytes)
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

    suspend fun listClanBadgeCount(
        apiUrl: String,
        token: String
    ): ListClanBadgeCountResponse {
        val bytes = rpc(apiUrl, token, "ListClanBadgeCount", ByteArray(0))
        return ListClanBadgeCountResponse.parseFrom(bytes)
    }

    suspend fun listLogedDevice(
        apiUrl: String,
        token: String
    ): LogedDeviceList {
        val bytes = rpc(apiUrl, token, "ListLogedDevice", ByteArray(0))
        return LogedDeviceList.parseFrom(bytes)
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

    suspend fun listAuditLog(
        apiUrl: String,
        token: String,
        clanId: Long,
        userId: Long,
        actionLog: String,
        dateLog: String,
    ): ListAuditLog {
        val request = listAuditLogRequest {
            this.clanId = clanId
            this.userId = userId
            this.actionLog = actionLog
            this.dateLog = dateLog
        }
        val bytes = rpc(apiUrl, token, "ListAuditLog", request.toByteArray())
        return ListAuditLog.parseFrom(bytes)
    }

    suspend fun listWebhooksByChannelId(
        apiUrl: String,
        token: String,
        channelId: Long,
        clanId: Long,
    ): WebhookListResponse {
        val request = webhookListRequest {
            this.channelId = channelId
            this.clanId = clanId
        }
        val bytes = rpc(apiUrl, token, "ListWebhookByChannelId", request.toByteArray())
        return WebhookListResponse.parseFrom(bytes)
    }

    suspend fun generateWebhook(
        apiUrl: String,
        token: String,
        webhookName: String,
        channelId: Long,
        clanId: Long,
        avatar: String,
    ): WebhookGenerateResponse {
        val request = webhookCreateRequest {
            this.webhookName = webhookName
            this.channelId = channelId
            this.avatar = avatar
            this.clanId = clanId
        }
        val bytes = rpc(apiUrl, token, "GenerateWebhook", request.toByteArray())
        return WebhookGenerateResponse.parseFrom(bytes)
    }

    suspend fun updateWebhookById(
        apiUrl: String,
        token: String,
        webhookId: Long,
        webhookName: String,
        avatarUrl: String,
        channelIdExisting: Long,
        newChannelId: Long,
        clanId: Long,
    ) {
        val request = webhookUpdateRequestById {
            this.id = webhookId
            this.webhookName = webhookName
            this.avatar = avatarUrl
            this.channelId = channelIdExisting
            this.channelIdUpdate = newChannelId
            this.clanId = clanId
        }
        rpc(apiUrl, token, "UpdateWebhookById", request.toByteArray())
    }

    suspend fun deleteWebhookById(
        apiUrl: String,
        token: String,
        webhookId: Long,
        clanId: Long,
        hookChannelId: Long,
    ) {
        val request = webhookDeleteRequestById {
            this.id = webhookId
            this.clanId = clanId
            this.channelId = hookChannelId
        }
        rpc(apiUrl, token, "DeleteWebhookById", request.toByteArray())
    }

    suspend fun listClanWebhooks(
        apiUrl: String,
        token: String,
        clanId: Long,
    ): ListClanWebhookResponse {
        val request = listClanWebhookRequest {
            this.clanId = clanId
        }
        val bytes = rpc(apiUrl, token, "ListClanWebhook", request.toByteArray())
        return ListClanWebhookResponse.parseFrom(bytes)
    }

    suspend fun generateClanWebhook(
        apiUrl: String,
        token: String,
        clanId: Long,
        webhookName: String,
        avatar: String,
    ): GenerateClanWebhookResponse {
        val request = generateClanWebhookRequest {
            this.clanId = clanId
            this.webhookName = webhookName
            this.avatar = avatar
        }
        val bytes = rpc(apiUrl, token, "GenerateClanWebhook", request.toByteArray())
        return GenerateClanWebhookResponse.parseFrom(bytes)
    }

    suspend fun updateClanWebhookById(
        apiUrl: String,
        token: String,
        webhookId: Long,
        clanId: Long,
        webhookName: String,
        avatar: String,
        resetToken: Boolean,
    ) {
        val request = updateClanWebhookRequest {
            id = webhookId
            this.clanId = clanId
            this.webhookName = webhookName
            this.avatar = avatar
            this.resetToken = resetToken
        }
        rpc(apiUrl, token, "UpdateClanWebhookById", request.toByteArray())
    }

    suspend fun deleteClanWebhookById(
        apiUrl: String,
        token: String,
        webhookId: Long,
        clanId: Long,
    ) {
        val request = clanWebhookRequest {
            id = webhookId
            this.clanId = clanId
        }
        rpc(apiUrl, token, "DeleteClanWebhookById", request.toByteArray())
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
            this.state = 0
        }
        val bytes = rpc(apiUrl, token, "ListThreadDescs", request.toByteArray())
        return ChannelDescList.parseFrom(bytes)
    }

    suspend fun listSdTopic(
        apiUrl: String,
        token: String,
        clanId: Long,
        limit: Int = 50
    ): SdTopicList {
        val request = listSdTopicRequest {
            this.clanId = clanId
            this.limit = limit
        }
        val bytes = rpc(apiUrl, token, "ListSdTopic", request.toByteArray())
        return SdTopicList.parseFrom(bytes)
    }

    suspend fun getTopicDetail(
        apiUrl: String,
        token: String,
        topicId: Long
    ): SdTopic {
        val request = sdTopicDetailRequest {
            this.topicId = topicId
        }
        val bytes = rpc(apiUrl, token, "GetTopicDetail", request.toByteArray())
        return SdTopic.parseFrom(bytes)
    }

    suspend fun createSdTopic(
        apiUrl: String,
        token: String,
        clanId: Long,
        channelId: Long,
        messageId: Long
    ): SdTopic {
        val request = sdTopicRequest {
            this.clanId = clanId
            this.channelId = channelId
            this.messageId = messageId
        }
        val bytes = rpc(apiUrl, token, "CreateSdTopic", request.toByteArray())
        return SdTopic.parseFrom(bytes)
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

    suspend fun linkSms(apiUrl: String, token: String, requestBytes: ByteArray): ByteArray {
        return try {
            rpc(apiUrl, token, "LinkSMS", requestBytes)
        } catch (e: RuntimeException) {
            if (e.message?.contains("(404)") == true ||
                (e as? SocketRpcServerException)?.code == 404
            ) {
                rpc(apiUrl, token, "LinkSms", requestBytes)
            } else {
                throw e
            }
        }
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
            throw RuntimeException(gatewayJsonErrorUserMessage(errorBody))
        }
        return response.body()
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
            throw RuntimeException(gatewayJsonErrorUserMessage(errorBody))
        }
        return response.body()
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
            throw RuntimeException(gatewayJsonErrorUserMessage(errorBody))
        }
        return response.body()
    }

    suspend fun updateUsername(apiUrl: String, token: String, username: String): Session {
        val request = updateUsernameRequest {
            this.username = username
        }
        val bytes = rpc(apiUrl, token, "UpdateUsername", request.toByteArray())
        if (bytes.isEmpty()) {
            throw RuntimeException("UpdateUsername returned empty body")
        }
        return Session.parseFrom(bytes)
    }

    suspend fun deleteAccount(apiUrl: String, token: String): ByteArray {
        return rpc(apiUrl, token, "DeleteAccount", ByteArray(0))
    }

    suspend fun listFriends(apiUrl: String, token: String, state: Int = 3, limit: Int = 1000): FriendList {
        val request = listFriendsRequest {
            this.state = state
            this.limit = limit
        }
        val bytes = rpc(apiUrl, token, "ListFriends", request.toByteArray())
        return FriendList.parseFrom(bytes)
    }

    suspend fun listFriendsAll(apiUrl: String, token: String): FriendList {
        val request = listFriendsRequest {}
        val bytes = rpc(apiUrl, token, "ListFriends", request.toByteArray())
        return FriendList.parseFrom(bytes)
    }

    suspend fun listActivities(apiUrl: String, token: String): ListActivity {
        val bytes = rpc(apiUrl, token, "ListActivity", ByteArray(0))
        return ListActivity.parseFrom(bytes)
    }

    suspend fun blockFriends(apiUrl: String, token: String, ids: List<Long>, usernames: List<String>): ByteArray {
        val request = blockFriendsRequest {
            this.ids.addAll(ids)
            this.usernames.addAll(usernames)
        }
        return rpc(apiUrl, token, "BlockFriends", request.toByteArray())
    }

    suspend fun addFriends(apiUrl: String, token: String, ids: List<Long>, usernames: List<String>): AddFriendsResponse {
        val request = addFriendsRequest {
            this.ids.addAll(ids)
            this.usernames.addAll(usernames)
        }
        val bytes = rpc(apiUrl, token, "AddFriends", request.toByteArray())
        return AddFriendsResponse.parseFrom(bytes)
    }

    suspend fun deleteFriends(apiUrl: String, token: String, ids: List<Long>, usernames: List<String>): ByteArray {
        val request = deleteFriendsRequest {
            this.ids.addAll(ids)
            this.usernames.addAll(usernames)
        }
        return rpc(apiUrl, token, "DeleteFriends", request.toByteArray())
    }

    suspend fun unblockFriends(apiUrl: String, token: String, ids: List<Long>, usernames: List<String>): ByteArray {
        return deleteFriends(apiUrl, token, ids, usernames)
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

    suspend fun updateChannelMessage(
        apiUrl: String,
        token: String,
        request: com.mezon.mezon.rtapi.ChannelMessageUpdate
    ): ByteArray = rpc(apiUrl, token, "UpdateChannelMessage", request.toByteArray())

    suspend fun channelMessageReact(
        apiUrl: String,
        token: String,
        clanId: Long,
        channelId: Long,
        mode: Int,
        isPublic: Boolean,
        messageId: Long,
        emojiId: Long,
        emoji: String,
        count: Int,
        messageSenderId: Long,
        actionDelete: Boolean,
        topicId: Long = 0L,
        emojiRecentId: Long = 0L,
        senderName: String = ""
    ): ChannelMessageSend {
        val request = messageReaction {
            this.clanId = clanId
            this.channelId = channelId
            this.mode = mode
            this.isPublic = isPublic
            this.messageId = messageId
            this.emojiId = emojiId
            this.emoji = emoji
            this.count = count
            this.messageSenderId = messageSenderId
            this.action = actionDelete
            this.topicId = topicId
            this.emojiRecentId = emojiRecentId
            this.senderName = senderName
        }
        val bytes = rpc(apiUrl, token, "ReactChannelMessage", request.toByteArray())
        return if (bytes.isEmpty()) ChannelMessageSend.getDefaultInstance()
        else ChannelMessageSend.parseFrom(bytes)
    }

    suspend fun activeArchivedThread(
        apiUrl: String,
        token: String,
        clanId: Long,
        channelId: Long
    ) {
        val body = ActiveArchivedThread.newBuilder()
            .setClanId(clanId)
            .setChannelId(channelId)
            .build()
            .toByteArray()
        rpc(apiUrl, token, "ActiveArchivedThread", body)
    }

    suspend fun messageButtonClick(
        apiUrl: String,
        token: String,
        messageId: Long,
        channelId: Long,
        buttonId: String,
        senderId: Long,
        userId: Long,
        extraData: String,
    ) {
        val body = messageButtonClicked {
            this.messageId = messageId
            this.channelId = channelId
            this.buttonId = buttonId
            this.senderId = senderId
            this.userId = userId
            this.extraData = extraData
        }.toByteArray()
        rpc(apiUrl, token, "MessageButtonClick", body)
    }

    suspend fun createPoll(
        apiUrl: String,
        token: String,
        channelId: Long,
        clanId: Long,
        question: String,
        answerLabels: List<String>,
        expireHours: Int,
        type: Int
    ): CreatePollResponse {
        val request = createPollRequest {
            this.channelId = channelId
            this.clanId = clanId
            this.question = question
            answers.addAll(answerLabels)
            this.expireHours = expireHours
            this.typeValue = type
        }
        val bytes = rpc(apiUrl, token, "CreatePoll", request.toByteArray())
        return CreatePollResponse.parseFrom(bytes)
    }

    suspend fun votePoll(
        apiUrl: String,
        token: String,
        channelId: Long,
        messageId: Long,
        pollId: Long = 0L,
        indices: List<Int>
    ): VotePollResponse {
        val request = votePollRequest {
            this.messageId = messageId
            this.channelId = channelId
            if (pollId != 0L) this.pollId = pollId
            answerIndices.addAll(indices)
        }
        val bytes = rpc(apiUrl, token, "VotePoll", request.toByteArray())
        return VotePollResponse.parseFrom(bytes)
    }

    suspend fun getPoll(
        apiUrl: String,
        token: String,
        channelId: Long,
        messageId: Long,
        pollId: Long = 0L
    ): GetPollResponse {
        val request = getPollRequest {
            this.messageId = messageId
            this.channelId = channelId
            if (pollId != 0L) this.pollId = pollId
        }
        val bytes = rpc(apiUrl, token, "GetPoll", request.toByteArray())
        return GetPollResponse.parseFrom(bytes)
    }

    suspend fun listChannelMessages(
        apiUrl: String,
        token: String,
        channelId: Long,
        clanId: Long = 0L,
        messageId: Long = 0L,
        direction: Int = 0,
        limit: Int = 50,
        topicId: Long = 0L,
        preferHttp: Boolean = false
    ): ChannelMessageList {
        val request = listChannelMessagesRequest {
            this.channelId = channelId
            this.clanId = clanId
            if (messageId != 0L) this.messageId = messageId
            if (direction != 0) this.direction = direction
            this.limit = limit
            if (topicId != 0L) this.topicId = topicId
        }
        val bytes = rpc(
            apiUrl,
            token,
            "ListChannelMessages",
            request.toByteArray(),
            preferHttp = preferHttp
        )
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

    suspend fun reportMessageAbuse(
        apiUrl: String,
        token: String,
        messageId: Long,
        abuseType: String
    ): ByteArray {
        val request = reportMessageAbuseReqest {
            this.messageId = messageId
            this.abuseType = abuseType
        }
        return rpc(apiUrl, token, "ReportMessageAbuse", request.toByteArray())
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

    suspend fun multipartUploadAttachmentFileStart(
        apiUrl: String,
        token: String,
        filename: String,
        filetype: String,
        size: Int,
        width: Int = 0,
        height: Int = 0,
        partCount: Int = 1,
    ): MultipartUploadAttachment {
        val builder = com.mezon.mezon.api.UploadAttachmentRequest.newBuilder().apply {
            this.filename = filename
            this.filetype = filetype
            this.size = size
            if (width > 0) this.width = width
            if (height > 0) this.height = height
        }
        if (partCount > 0) {
            try {
                val method = builder.javaClass.getMethod("setPartCount", Int::class.javaPrimitiveType)
                method.invoke(builder, partCount)
            } catch (e: Exception) {
                // Fallback / Ignore if old proto without partCount
            }
        }
        val request = builder.build()
        val bytes = rpc(apiUrl, token, "MultipartUploadAttachmentFileStart", request.toByteArray())
        return MultipartUploadAttachment.parseFrom(bytes)
    }

    suspend fun multipartUploadAttachmentFileFinish(
        apiUrl: String,
        token: String,
        uploadId: String,
        parts: List<Pair<Int, String>>,
        filename: String = "",
    ): UploadAttachment {
        val builder = com.mezon.mezon.api.MultipartUploadAttachmentFinishRequest.newBuilder().apply {
            this.uploadId = uploadId
            parts.forEach { (partNumber, eTag) ->
                this.addParts(
                    com.mezon.mezon.api.MultipartUploadAttachmentPart.newBuilder().apply {
                        this.partNumber = partNumber
                        this.eTag = eTag
                    }.build()
                )
            }
        }
        if (filename.isNotEmpty()) {
            try {
                val method = builder.javaClass.getMethod("setFilename", String::class.java)
                method.invoke(builder, filename)
            } catch (e: Exception) {
                // Fallback / Ignore if old proto without filename
            }
        }
        val request = builder.build()
        val bytes = rpc(apiUrl, token, "MultipartUploadAttachmentFileFinish", request.toByteArray())
        return UploadAttachment.parseFrom(bytes)
    }

    suspend fun listChannelAttachments(
        apiUrl: String,
        token: String,
        clanId: Long,
        channelId: Long,
        limit: Int = 100,
        fileType: String = "",
        beforeTimeSeconds: Int? = null,
        afterTimeSeconds: Int? = null,
        state: Int = 0
    ): ChannelAttachmentList {
        val request = listChannelAttachmentRequest {
            this.clanId = clanId
            this.channelId = channelId
            this.limit = limit.coerceIn(1, 100)
            if (fileType.isNotEmpty()) this.fileType = fileType
            if (state != 0) this.state = state
            beforeTimeSeconds?.let { if (it > 0) this.before = it }
            afterTimeSeconds?.let { if (it > 0) this.after = it }
        }
        val bytes = rpc(apiUrl, token, "ListChannelAttachment", request.toByteArray())
        return ChannelAttachmentList.parseFrom(bytes)
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

    suspend fun removeClanUsers(
        apiUrl: String,
        token: String,
        clanId: Long,
        userIds: List<Long>
    ) {
        val request = removeClanUsersRequest {
            this.clanId = clanId
            this.userIds.addAll(userIds)
        }
        rpc(apiUrl, token, "RemoveClanUsers", request.toByteArray())
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

    suspend fun getRoleOfUserInTheClan(
        apiUrl: String,
        token: String,
        clanId: Long,
    ): com.mezon.mezon.api.RoleList {
        val request = com.mezon.mezon.api.roleListEventRequest {
            this.clanId = clanId
            this.limit = 500
            this.state = 1
            this.cursor = ""
        }
        val bytes = rpc(apiUrl, token, "GetRoleOfUserInTheClan", request.toByteArray())
        return try {
            com.mezon.mezon.api.RoleList.parseFrom(bytes)
        } catch (_: com.google.protobuf.InvalidProtocolBufferException) {
            com.mezon.mezon.api.RoleListEventResponse.parseFrom(bytes).roles
        }
    }

    suspend fun getListPermission(
        apiUrl: String,
        token: String
    ): PermissionList {
        val bytes = rpc(apiUrl, token, "GetListPermission", ByteArray(0))
        return PermissionList.parseFrom(bytes)
    }

    suspend fun listUserPermissionInChannel(
        apiUrl: String,
        token: String,
        clanId: Long,
        channelId: Long
    ): UserPermissionInChannelListResponse {
        val request = userPermissionInChannelListRequest {
            this.clanId = clanId
            this.channelId = channelId
        }
        val bytes = rpc(apiUrl, token, "ListUserPermissionInChannel", request.toByteArray())
        return UserPermissionInChannelListResponse.parseFrom(bytes)
    }

    suspend fun listRoleUsers(
        apiUrl: String,
        token: String,
        roleId: Long,
        limit: Int = 100,
        cursor: String = ""
    ): RoleUserList {
        val request = listRoleUsersRequest {
            this.roleId = roleId
            this.limit = limit
            this.cursor = cursor
        }
        val bytes = rpc(apiUrl, token, "ListRoleUsers", request.toByteArray())
        return RoleUserList.parseFrom(bytes)
    }

    suspend fun createRole(
        apiUrl: String,
        token: String,
        clanId: Long,
        title: String,
        color: String,
        maxPermissionRoleId: Long,
        addUserIds: List<Long>,
        activePermissionIds: List<Long>
    ): Role {
        val request = createRoleRequest {
            this.clanId = clanId
            this.title = title
            this.color = color
            this.description = ""
            this.displayOnline = 0
            this.allowMention = 0
            this.maxPermissionId = maxPermissionRoleId
            this.addUserIds.addAll(addUserIds)
            this.activePermissionIds.addAll(activePermissionIds)
        }
        val bytes = rpc(apiUrl, token, "CreateRole", request.toByteArray())
        return Role.parseFrom(bytes)
    }

    suspend fun updateRole(
        apiUrl: String,
        token: String,
        clanId: Long,
        roleId: Long,
        title: String?,
        color: String?,
        roleIcon: String?,
        addUserIds: List<Long>,
        removeUserIds: List<Long>,
        activePermissionIds: List<Long>,
        removePermissionIds: List<Long>,
        maxPermissionRoleId: Long
    ) {
        val request = updateRoleRequest {
            this.roleId = roleId
            this.clanId = clanId
            title?.let { this.title = StringValue.of(it) }
            color?.let { this.color = StringValue.of(it) }
            roleIcon?.let { this.roleIcon = StringValue.of(it) }
            this.displayOnline = 0
            this.allowMention = 0
            this.maxPermissionId = maxPermissionRoleId
            this.addUserIds.addAll(addUserIds)
            this.removeUserIds.addAll(removeUserIds)
            this.activePermissionIds.addAll(activePermissionIds)
            this.removePermissionIds.addAll(removePermissionIds)
        }
        if (BuildConfig.DEBUG) {
            Log.d("MezonApi", "UpdateRole payload bytes=${request.serializedSize} $request")
        }
        rpc(apiUrl, token, "UpdateRole", request.toByteArray())
    }

    suspend fun deleteRole(
        apiUrl: String,
        token: String,
        clanId: Long,
        roleId: Long,
        roleLabel: String
    ) {
        val request = deleteRoleRequest {
            this.roleId = roleId
            this.clanId = clanId
            this.roleLabel = roleLabel
        }
        rpc(apiUrl, token, "DeleteRole", request.toByteArray())
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

    suspend fun addChannelUsers(
        apiUrl: String,
        token: String,
        channelId: Long,
        userIds: List<Long>
    ) {
        val request = addChannelUsersRequest {
            this.channelId = channelId
            this.userIds.addAll(userIds)
        }
        rpc(apiUrl, token, "AddChannelUsers", request.toByteArray())
    }

    suspend fun removeChannelUsers(
        apiUrl: String,
        token: String,
        channelId: Long,
        userIds: List<Long>
    ) {
        val request = removeChannelUsersRequest {
            this.channelId = channelId
            this.userIds.addAll(userIds)
        }
        rpc(apiUrl, token, "RemoveChannelUsers", request.toByteArray())
    }

    suspend fun addRoleChannelDesc(
        apiUrl: String,
        token: String,
        channelId: Long,
        roleIds: List<Long>
    ) {
        val request = addRoleChannelDescRequest {
            this.channelId = channelId
            this.roleIds.addAll(roleIds)
        }
        rpc(apiUrl, token, "AddRolesChannelDesc", request.toByteArray())
    }

    suspend fun deleteRoleChannelDesc(
        apiUrl: String,
        token: String,
        clanId: Long,
        channelId: Long,
        roleId: Long,
        roleLabel: String
    ) {
        val request = deleteRoleRequest {
            this.roleId = roleId
            this.channelId = channelId
            this.clanId = clanId
            this.roleLabel = roleLabel
        }
        rpc(apiUrl, token, "DeleteRoleChannelDesc", request.toByteArray())
    }

    suspend fun updateChannelPrivate(
        apiUrl: String,
        token: String,
        clanId: Long,
        channelId: Long,
        channelPrivate: Int,
        userIds: List<Long>,
        roleIds: List<Long>
    ) {
        val request = changeChannelPrivateRequest {
            this.clanId = clanId
            this.channelId = channelId
            this.channelPrivate = channelPrivate
            this.userIds.addAll(userIds)
            this.roleIds.addAll(roleIds)
        }
        rpc(apiUrl, token, "UpdateChannelPrivate", request.toByteArray())
    }

    suspend fun getPermissionByRoleIdChannelId(
        apiUrl: String,
        token: String,
        roleId: Long,
        channelId: Long,
        userId: Long
    ): PermissionRoleChannelListEventResponse {
        val request = permissionRoleChannelListEventRequest {
            this.roleId = roleId
            this.channelId = channelId
            this.userId = userId
        }
        val bytes = rpc(apiUrl, token, "GetPermissionByRoleIdChannelId", request.toByteArray())
        return PermissionRoleChannelListEventResponse.parseFrom(bytes)
    }

    suspend fun setRoleChannelPermission(
        apiUrl: String,
        token: String,
        channelId: Long,
        roleId: Long,
        userId: Long,
        maxPermissionId: Long,
        permissionUpdates: List<PermissionUpdate>
    ) {
        val request = updateRoleChannelRequest {
            this.channelId = channelId
            this.roleId = roleId
            this.userId = userId
            this.maxPermissionId = maxPermissionId
            this.permissionUpdate.addAll(permissionUpdates)
        }
        rpc(apiUrl, token, "SetRoleChannelPermission", request.toByteArray())
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

    suspend fun createClanEmoji(
        apiUrl: String,
        token: String,
        clanId: Long,
        emojiId: Long,
        sourceUrl: String,
        shortname: String,
        category: String,
        isForSale: Boolean,
    ) {
        val request = clanEmojiCreateRequest {
            this.clanId = clanId
            this.id = emojiId
            this.source = sourceUrl
            this.shortname = shortname
            this.category = category
            this.isForSale = isForSale
        }
        rpc(apiUrl, token, "CreateClanEmoji", request.toByteArray())
    }

    suspend fun updateClanEmojiById(
        apiUrl: String,
        token: String,
        emojiId: Long,
        clanId: Long,
        shortname: String,
    ) {
        val request = clanEmojiUpdateRequest {
            this.id = emojiId
            this.clanId = clanId
            this.shortname = shortname
        }
        rpc(apiUrl, token, "UpdateClanEmojiById", request.toByteArray())
    }

    suspend fun deleteByIdClanEmoji(
        apiUrl: String,
        token: String,
        emojiId: Long,
        clanId: Long,
        emojiLabel: String,
    ) {
        val request = clanEmojiDeleteRequest {
            this.id = emojiId
            this.clanId = clanId
            this.emojiLabel = emojiLabel
        }
        rpc(apiUrl, token, "DeleteByIdClanEmoji", request.toByteArray())
    }

    suspend fun listStickersByUserId(
        apiUrl: String,
        token: String
    ): StickerListedResponse {
        val bytes = rpc(apiUrl, token, "GetListStickersByUserId", ByteArray(0))
        return StickerListedResponse.parseFrom(bytes)
    }

    suspend fun addClanSticker(
        apiUrl: String,
        token: String,
        id: Long,
        clanId: Long,
        source: String,
        shortname: String,
        category: String,
        mediaType: Int,
        isForSale: Boolean = false,
    ) {
        val request = clanStickerAddRequest {
            this.id = id
            this.clanId = clanId
            this.source = source
            this.shortname = shortname
            this.category = category
            this.mediaType = mediaType
            this.isForSale = isForSale
        }
        rpc(apiUrl, token, "AddClanSticker", request.toByteArray())
    }

    suspend fun updateClanStickerById(
        apiUrl: String,
        token: String,
        id: Long,
        clanId: Long,
        source: String,
        shortname: String,
        category: String,
    ) {
        val request = clanStickerUpdateByIdRequest {
            this.id = id
            this.clanId = clanId
            this.source = source
            this.shortname = shortname
            this.category = category
        }
        rpc(apiUrl, token, "UpdateClanStickerById", request.toByteArray())
    }

    suspend fun deleteClanStickerById(
        apiUrl: String,
        token: String,
        id: Long,
        clanId: Long,
        stickerLabel: String,
    ) {
        val request = clanStickerDeleteRequest {
            this.id = id
            this.clanId = clanId
            this.stickerLabel = stickerLabel
        }
        rpc(apiUrl, token, "DeleteClanStickerById", request.toByteArray())
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

    suspend fun getApp(
        apiUrl: String,
        token: String,
        appId: Long
    ): App {
        val request = appId {
            id = appId
        }
        val bytes = rpc(apiUrl, token, "GetApp", request.toByteArray())
        return App.parseFrom(bytes)
    }

    suspend fun addAppToClan(
        apiUrl: String,
        token: String,
        appId: Long,
        clanId: Long
    ) {
        val request = appClan {
            this.appId = appId
            this.clanId = clanId
        }
        rpc(apiUrl, token, "AddAppToClan", request.toByteArray())
    }

    suspend fun updateChannelDesc(
        apiUrl: String,
        token: String,
        clanId: Long,
        channelId: Long,
        channelLabel: String,
        categoryId: Long,
        topic: String,
        appId: Long = 0L,
        ageRestricted: Int = 0,
        e2ee: Int = 0,
    ): ChannelDescription {
        val request = updateChannelDescRequest {
            this.clanId = clanId
            this.channelId = channelId
            this.channelLabel = StringValue.of(channelLabel)
            this.categoryId = categoryId
            this.topic = topic
            this.appId = appId
            this.ageRestricted = ageRestricted
            this.e2Ee = e2ee
        }
        val bytes = rpc(apiUrl, token, "UpdateChannelDesc", request.toByteArray())
        return ChannelDescription.parseFrom(bytes)
    }

    suspend fun deleteChannelDesc(
        apiUrl: String,
        token: String,
        clanId: Long,
        channelId: Long,
    ) {
        val request = deleteChannelDescRequest {
            this.clanId = clanId
            this.channelId = channelId
        }
        rpc(apiUrl, token, "DeleteChannelDesc", request.toByteArray())
    }

    suspend fun checkDuplicateName(
        apiUrl: String,
        token: String,
        name: String,
        type: Int,
        conditionId: Long,
    ): Boolean {
        val request = checkDuplicateNameRequest {
            this.name = name
            this.type = type
            this.conditionId = conditionId
        }
        val bytes = rpc(apiUrl, token, "CheckDuplicateName", request.toByteArray())
        return CheckDuplicateNameResponse.parseFrom(bytes).isDuplicate
    }

    suspend fun changeChannelCategory(
        apiUrl: String,
        token: String,
        clanId: Long,
        channelId: Long,
        newCategoryId: Long,
    ) {
        val request = changeChannelCategoryRequest {
            this.clanId = clanId
            this.channelId = channelId
            this.newCategoryId = newCategoryId
        }
        rpc(apiUrl, token, "ChangeChannelCategory", request.toByteArray())
    }

    suspend fun listCategoryDescs(
        apiUrl: String,
        token: String,
        limit: Int = 100,
        state: Int = 1,
        cursor: String = "",
    ): CategoryDescList {
        val request = listCategoryDescsRequest {
            this.limit = limit
            this.state = state
            this.cursor = cursor
        }
        val bytes = rpc(apiUrl, token, "ListCategoryDescs", request.toByteArray())
        return CategoryDescList.parseFrom(bytes)
    }

    suspend fun listQuickMenuAccess(
        apiUrl: String,
        token: String,
        channelId: Long,
        menuType: Int,
        botId: Long = 0L,
    ): QuickMenuAccessList {
        val request = listQuickMenuAccessRequest {
            this.channelId = channelId
            this.menuType = menuType
            this.botId = botId
        }
        val bytes = rpc(apiUrl, token, "ListQuickMenuAccess", request.toByteArray())
        return QuickMenuAccessList.parseFrom(bytes)
    }

    suspend fun addQuickMenuAccess(
        apiUrl: String,
        token: String,
        item: com.mezon.mezon.api.QuickMenuAccess,
    ) {
        rpc(apiUrl, token, "AddQuickMenuAccess", item.toByteArray())
    }

    suspend fun updateQuickMenuAccess(
        apiUrl: String,
        token: String,
        item: com.mezon.mezon.api.QuickMenuAccess,
    ) {
        rpc(apiUrl, token, "UpdateQuickMenuAccess", item.toByteArray())
    }

    suspend fun deleteQuickMenuAccess(
        apiUrl: String,
        token: String,
        item: com.mezon.mezon.api.QuickMenuAccess,
    ) {
        rpc(apiUrl, token, "DeleteQuickMenuAccess", item.toByteArray())
    }

    suspend fun listBannedUsers(
        apiUrl: String,
        token: String,
        clanId: Long,
        channelId: Long,
    ): BannedUserList {
        val request = bannedUserListRequest {
            this.clanId = clanId
            this.channelId = channelId
        }
        val bytes = rpc(apiUrl, token, "ListBannedUsers", request.toByteArray())
        return BannedUserList.parseFrom(bytes)
    }

    suspend fun banClanUsers(
        apiUrl: String,
        token: String,
        clanId: Long,
        channelId: Long,
        userIds: List<Long>,
        banTime: Int = 0,
    ) {
        val request = banClanUsersRequest {
            this.clanId = clanId
            this.channelId = channelId
            this.userIds.addAll(userIds)
            this.banTime = banTime
        }
        rpc(apiUrl, token, "BanClanUsers", request.toByteArray())
    }

    suspend fun unbanClanUsers(
        apiUrl: String,
        token: String,
        clanId: Long,
        channelId: Long,
        userIds: List<Long>,
    ) {
        val request = banClanUsersRequest {
            this.clanId = clanId
            this.channelId = channelId
            this.userIds.addAll(userIds)
        }
        rpc(apiUrl, token, "UnbanClanUsers", request.toByteArray())
    }

    suspend fun leaveThread(
        apiUrl: String,
        token: String,
        clanId: Long,
        threadId: Long,
    ) {
        val request = leaveThreadRequest {
            this.clanId = clanId
            this.channelId = threadId
        }
        rpc(apiUrl, token, "LeaveThread", request.toByteArray())
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

    suspend fun putFileToPresignedUrlFromFile(
        presignedUrl: String,
        file: java.io.File,
        contentType: String
    ) {
        val parsedType = ContentType.parse(contentType)
        val body = object : OutgoingContent.ReadChannelContent() {
            override val contentLength: Long = file.length()
            override val contentType: ContentType = parsedType
            override fun readFrom(): ByteReadChannel = file.readChannel()
        }
        val response = httpClient.put(presignedUrl) {
            setBody(body)
        }
        if (!response.status.isSuccess()) {
            throw RuntimeException("File upload failed (${response.status.value})")
        }
    }

    suspend fun putFilePartToPresignedUrl(
        presignedUrl: String,
        partBytes: ByteArray,
        contentType: String
    ): String {
        val response = httpClient.put(presignedUrl) {
            header(HttpHeaders.ContentType, contentType)
            setBody(partBytes)
        }
        if (!response.status.isSuccess()) {
            throw RuntimeException("File part upload failed (${response.status.value})")
        }
        return response.headers[HttpHeaders.ETag]
            ?.trim()
            ?.trim('"')
            ?.takeIf { it.isNotEmpty() }
            ?: throw RuntimeException("File part upload missing ETag")
    }

    suspend fun putFilePartRangeToPresignedUrl(
        presignedUrl: String,
        file: java.io.File,
        offset: Long,
        length: Long,
        contentType: String,
    ): String {
        if (length <= 0L) throw IllegalArgumentException("part length must be positive")
        val endInclusive = offset + length - 1L
        val parsedType = ContentType.parse(contentType)
        val body = object : OutgoingContent.ReadChannelContent() {
            override val contentLength: Long = length
            override val contentType: ContentType = parsedType
            override fun readFrom(): ByteReadChannel =
                file.readChannel(start = offset, endInclusive = endInclusive)
        }
        val response = httpClient.put(presignedUrl) {
            setBody(body)
        }
        if (!response.status.isSuccess()) {
            throw RuntimeException("File part upload failed (${response.status.value})")
        }
        return response.headers[HttpHeaders.ETag]
            ?.trim()
            ?.trim('"')
            ?.takeIf { it.isNotEmpty() }
            ?: throw RuntimeException("File part upload missing ETag")
    }

    suspend fun listOnboarding(
        apiUrl: String,
        token: String,
        clanId: Long,
        guideType: Int = 0,
        limit: Int = 100,
        page: Int = 0
    ): com.mezon.mezon.api.ListOnboardingResponse {
        val request = com.mezon.mezon.api.ListOnboardingRequest.newBuilder().apply {
            this.clanId = clanId
            this.guideType = guideType
            this.limit = limit
            this.page = page
        }.build()
        val bytes = rpc(apiUrl, token, "ListOnboarding", request.toByteArray())
        return com.mezon.mezon.api.ListOnboardingResponse.parseFrom(bytes)
    }

    suspend fun listOnboardingStep(
        apiUrl: String,
        token: String,
        clanId: Long,
        limit: Int = 100,
        page: Int = 0
    ): com.mezon.mezon.api.ListOnboardingStepResponse {
        val request = com.mezon.mezon.api.ListOnboardingStepRequest.newBuilder().apply {
            this.clanId = clanId
            this.limit = limit
            this.page = page
        }.build()
        val bytes = rpc(apiUrl, token, "ListOnboardingStep", request.toByteArray())
        return com.mezon.mezon.api.ListOnboardingStepResponse.parseFrom(bytes)
    }

    suspend fun updateOnboardingStep(
        apiUrl: String,
        token: String,
        clanId: Long,
        onboardingStep: Int
    ): ByteArray {
        val request = com.mezon.mezon.api.UpdateOnboardingStepRequest.newBuilder().apply {
            this.clanId = clanId
            this.onboardingStep = onboardingStep
        }.build()
        return rpc(apiUrl, token, "UpdateOnboardingStep", request.toByteArray())
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
