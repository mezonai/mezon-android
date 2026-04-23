package com.mezon.mmn

import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Serializable
private data class ClaimRedEnvelopeDongData(
    val data: ClaimRedEnvelopeQRResponse
)

@Serializable
private data class ClaimAmountRequestBody(
    @SerialName("user_id")
    val userId: String,
    @SerialName("proof_b64")
    val proofB64: String,
    @SerialName("public_b64")
    val publicB64: String,
    val publickey: String
)

class DongClient(
    private val config: DongClientConfig,
    private val httpClient: HttpClient
) {
    suspend fun claimAmountRedEnvelopeQR(
        params: ClaimRedEnvelopeQRRequest
    ): ClaimRedEnvelopeQRResponse {
        return try {
            withTimeout(config.timeoutMs) {
                val path = "api/v1/red-envelopes/qr/claim-amount"
                val q = "id=" + urlEncodeQuery(params.id)
                val url = joinUrl(path) + "?$q"
                val body = MmnJson.encodeToString(
                    ClaimAmountRequestBody.serializer(),
                    ClaimAmountRequestBody(
                        userId = params.userId,
                        proofB64 = params.proofB64,
                        publicB64 = params.publicB64,
                        publickey = params.publickey
                    )
                )
                val response = httpClient.post(url) {
                    contentType(ContentType.Application.Json)
                    headers {
                        for ((k, v) in allHeaders().entries) {
                            append(k, v)
                        }
                    }
                    setBody(body)
                }
                if (!response.status.isSuccess()) {
                    error("HTTP ${response.status.value}: ${response.status.description}")
                }
                MmnJson.decodeFromString<ClaimRedEnvelopeDongData>(response.bodyAsText()).data
            }
        } catch (e: CancellationException) {
            throw e
        }
    }

    private fun allHeaders(): Map<String, String> = buildMap {
        put("Accept", "application/json")
        put("Content-Type", "application/json")
        putAll(config.headers)
    }

    private fun joinUrl(
        path: String
    ): String {
        return config.endpoint.trimEnd('/') + "/" + path.trimStart('/')
    }

    private fun urlEncodeQuery(
        s: String
    ): String = URLEncoder.encode(s, StandardCharsets.UTF_8)

    suspend fun claimRedEnvelopeQR(
        id: String,
        params: ExecuteClaimRedEnvelopeQRRequest
    ) {
        try {
            withTimeout(config.timeoutMs) {
                val path = "api/v1/red-envelopes/qr/${id}/claim"
                val url = joinUrl(path)
                val body = MmnJson.encodeToString(ExecuteClaimRedEnvelopeQRRequest.serializer(), params)
                val response = httpClient.post(url) {
                    contentType(ContentType.Application.Json)
                    headers {
                        for ((k, v) in allHeaders().entries) {
                            append(k, v)
                        }
                    }
                    setBody(body)
                }
                if (!response.status.isSuccess()) {
                    error("HTTP ${response.status.value}: ${response.status.description}")
                }
            }
        } catch (e: CancellationException) {
            throw e
        }
    }
}

fun createDongClient(
    config: DongClientConfig,
    httpClient: HttpClient
): DongClient = DongClient(config, httpClient)
