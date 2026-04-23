package com.mezon.mmn

import com.mezon.mmn.internal.MmnCrypto
import com.mezon.mmn.internal.MmnCrypto.TX_TYPE_TRANSFER_BY_KEY
import com.mezon.mmn.internal.MmnCrypto.TX_TYPE_TRANSFER_BY_ZK
import com.mezon.mmn.internal.MmnCrypto.TX_TYPE_USER_CONTENT
import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import java.util.concurrent.atomic.AtomicLong

@Serializable
private data class GetAccountParams(val address: String)

@Serializable
private data class GetCurrentNonceParams(
    val address: String,
    val tag: String
)

@Serializable
private data class JsonRpcError(
    val code: Int = 0,
    val message: String = ""
)

@Serializable
private data class JsonRpcResponse(
    @SerialName("jsonrpc")
    val jsonrpc: String = "2.0",
    val result: JsonElement? = null,
    val error: JsonRpcError? = null,
    @Serializable(with = JsonRpcIdSerializer::class)
    val id: Long = 0
)

@Serializable
private data class JsonRpcRequestBody(
    @SerialName("jsonrpc")
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val jsonrpc: String = "2.0",
    val method: String,
    val params: JsonElement? = null,
    val id: Long
)

class MmnClient(
    private val config: MmnClientConfig,
    private val httpClient: HttpClient
) {
    private val requestId = AtomicLong(0L)

    suspend fun sendTransaction(
        params: SendTransactionRequest
    ): AddTxResponse {
        val fromAddress = getAddressFromUserId(params.sender)
        val toAddress = getAddressFromUserId(params.recipient)
        val signed = MmnCrypto.createAndSignTx(
            type = TX_TYPE_TRANSFER_BY_ZK,
            sender = fromAddress,
            recipient = toAddress,
            amount = params.amount,
            nonce = params.nonce,
            timestamp = params.timestamp ?: System.currentTimeMillis(),
            textData = params.textData.orEmpty(),
            extraInfo = params.extraInfo,
            privateKeyPcs8Hex = params.privateKey,
            zkProof = params.zkProof,
            zkPub = params.zkPub
        )
        return postSigned(signed)
    }

    suspend fun sendTransactionByAddress(
        params: SendTransactionRequest
    ): AddTxResponse {
        val signed = MmnCrypto.createAndSignTx(
            type = TX_TYPE_TRANSFER_BY_ZK,
            sender = params.sender,
            recipient = params.recipient,
            amount = params.amount,
            nonce = params.nonce,
            timestamp = params.timestamp ?: System.currentTimeMillis(),
            textData = params.textData.orEmpty(),
            extraInfo = params.extraInfo,
            privateKeyPcs8Hex = params.privateKey,
            zkProof = params.zkProof,
            zkPub = params.zkPub
        )
        return postSigned(signed)
    }

    suspend fun sendTransactionByPrivateKey(
        params: SendTransactionBase
    ): AddTxResponse {
        val signed = MmnCrypto.createAndSignTx(
            type = TX_TYPE_TRANSFER_BY_KEY,
            sender = params.sender,
            recipient = params.recipient,
            amount = params.amount,
            nonce = params.nonce,
            timestamp = params.timestamp ?: System.currentTimeMillis(),
            textData = params.textData.orEmpty(),
            extraInfo = params.extraInfo,
            privateKeyPcs8Hex = params.privateKey,
            zkProof = null,
            zkPub = null
        )
        return postSigned(signed)
    }

    suspend fun postDonationCampaignFeed(
        params: SendTransactionRequest
    ): AddTxResponse {
        val signed = MmnCrypto.createAndSignTx(
            type = TX_TYPE_USER_CONTENT,
            sender = params.sender,
            recipient = params.recipient,
            amount = params.amount,
            nonce = params.nonce,
            timestamp = params.timestamp ?: System.currentTimeMillis(),
            textData = params.textData.orEmpty(),
            extraInfo = params.extraInfo,
            privateKeyPcs8Hex = params.privateKey,
            zkProof = params.zkProof,
            zkPub = params.zkPub
        )
        return postSigned(signed)
    }

    private suspend fun postSigned(
        signedTx: SignedTx
    ): AddTxResponse {
        return makeRequest(
            "tx.addtx",
            MmnJson.encodeToJsonElement(SignedTx.serializer(), signedTx)
        )
    }

    suspend fun getCurrentNonce(
        userId: String,
        tag: String = "latest"
    ): GetCurrentNonceResponse {
        val address = getAddressFromUserId(userId)
        return getCurrentNonceByAddress(address, tag)
    }

    suspend fun getCurrentNonceByAddress(
        address: String,
        tag: String = "latest"
    ): GetCurrentNonceResponse {
        val p = GetCurrentNonceParams(address, tag)
        return makeRequest(
            "account.getcurrentnonce",
            MmnJson.encodeToJsonElement(GetCurrentNonceParams.serializer(), p)
        )
    }

    suspend fun getAccountByUserId(
        userId: String
    ): GetAccountByAddressResponse {
        val address = getAddressFromUserId(userId)
        return getAccountByAddress(address)
    }

    suspend fun getAccountByAddress(
        address: String
    ): GetAccountByAddressResponse {
        val p = GetAccountParams(address)
        return makeRequest(
            "account.getaccount",
            MmnJson.encodeToJsonElement(GetAccountParams.serializer(), p)
        )
    }

    fun getAddressFromUserId(userId: String): String {
        return MmnCrypto.getAddressFromUserId(userId)
    }

    fun scaleAmountToDecimals(
        original: String,
        decimals: Int = MmnCrypto.DECIMALS
    ): String {
        return MmnCrypto.scaleAmountToDecimals(original, decimals)
    }

    fun validateAddress(addr: String): Boolean = MmnCrypto.validateAddress(addr)

    fun validateAmount(
        balance: String,
        amount: String
    ): Boolean = MmnCrypto.validateAmount(balance, amount)

    fun generateEphemeralKeyPair(): IEphemeralKeyPair = MmnCrypto.generateEphemeralKeyPair()

    private suspend inline fun <reified T> makeRequest(
        method: String,
        params: JsonElement? = null
    ): T {
        return try {
            withTimeout(config.timeoutMs) {
                val id = requestId.incrementAndGet()
                val bodyString = MmnJson.encodeToString(
                    JsonRpcRequestBody.serializer(),
                    JsonRpcRequestBody(
                        method = method,
                        params = params,
                        id = id
                    )
                )
                val response = httpClient.post(config.baseUrl) {
                    contentType(ContentType.Application.Json)
                    headers {
                        for ((k, v) in config.headers) {
                            append(k, v)
                        }
                    }
                    setBody(bodyString)
                }
                if (!response.status.isSuccess()) {
                    error("HTTP ${response.status.value}: ${response.status.description}")
                }
                val text = response.bodyAsText()
                try {
                    val rpc = MmnJson.decodeFromString(JsonRpcResponse.serializer(), text)
                    rpc.error?.let { e ->
                        error("JSON-RPC Error ${e.code}: ${e.message}")
                    }
                    val result = rpc.result ?: error("JSON-RPC: missing result")
                    MmnJson.decodeFromJsonElement(serializer(), result)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    val hp = hostPathForMmnLog(config.baseUrl)
                    Log.e("MmnClient", "mmn $hp method=$method outLen=${bodyString.length}")
                    Log.e("MmnClient", "mmn outJson=$bodyString")
                    Log.e("MmnClient", "mmn resHead=" + text.take(800), e)
                    throw e
                }
            }
        } catch (e: CancellationException) {
            throw e
        }
    }
}

fun createMmnClient(
    config: MmnClientConfig,
    httpClient: HttpClient
): MmnClient = MmnClient(config, httpClient)

private fun hostPathForMmnLog(baseUrl: String): String =
    runCatching {
        val u = Uri.parse(baseUrl)
        "host=" + (u.host ?: "") + " path=" + (u.encodedPath ?: "/")
    }.getOrDefault("urlChars=" + baseUrl.length)
