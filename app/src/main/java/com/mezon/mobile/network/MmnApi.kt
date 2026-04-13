package com.mezon.mobile.network

import com.mezon.mobile.BuildConfig
import com.mezon.mobile.util.Base58
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val json = Json { ignoreUnknownKeys = true }

@Serializable
data class MmnGetAccountResponse(
    val address: String = "",
    val balance: String = "0",
    val nonce: Int = 0,
    val decimals: Int = 6
)

@Serializable
data class MmnJsonRpcResponse(
    val jsonrpc: String = "2.0",
    val result: MmnGetAccountResponse? = null,
    val error: kotlinx.serialization.json.JsonElement? = null,
    val id: Long = 1
)

@Singleton
class MmnApi @Inject constructor(
    private val httpClient: HttpClient
) {
    suspend fun getWalletBalance(userId: String): MmnGetAccountResponse? {
        try {
            val address = calculateMmnAddress(userId)
            val requestBody = "{\"jsonrpc\":\"2.0\",\"method\":\"account.getaccount\",\"params\":{\"address\":\"$address\"},\"id\":1}"
            val mmnUrl = BuildConfig.MEZON_MMN_API_URL
            val response = httpClient.post(mmnUrl) {
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }
            if (!response.status.isSuccess()) return null
            val responseBody = response.bodyAsText()
            val rpcResult = json.decodeFromString<MmnJsonRpcResponse>(responseBody)
            return rpcResult.result
        } catch (e: Exception) {
            return null
        }
    }

    private fun calculateMmnAddress(userId: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val hash = md.digest(userId.toByteArray(Charsets.UTF_8))
        return Base58.encode(hash)
    }
}
