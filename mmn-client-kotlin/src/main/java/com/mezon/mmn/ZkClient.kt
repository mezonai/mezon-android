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

@Serializable
private data class ZkProofResponse(
    val data: IZkProof
)

@Serializable
private data class ZkRequestBody(
    @SerialName("user_id")
    val userId: String,
    @SerialName("ephemeral_pk")
    val ephemeralPublicKey: String,
    val jwt: String,
    val address: String,
    @SerialName("client_type")
    val clientType: EZkClientType
)

class ZkClient(
    private val config: ZkClientConfig,
    private val httpClient: HttpClient
) {
    suspend fun getZkProofs(
        userId: String,
        ephemeralPublicKey: String,
        jwt: String,
        address: String,
        clientType: EZkClientType = EZkClientType.MEZON
    ): IZkProof {
        return try {
            withTimeout(config.timeoutMs) {
                val body = MmnJson.encodeToString(
                    ZkRequestBody.serializer(),
                    ZkRequestBody(
                        userId = userId,
                        ephemeralPublicKey = ephemeralPublicKey,
                        jwt = jwt,
                        address = address,
                        clientType = clientType
                    )
                )
                val response = httpClient.post(joinUrl("prove")) {
                    contentType(ContentType.Application.Json)
                    headers {
                        for ((k, v) in defaultHeaders().entries) {
                            append(k, v)
                        }
                    }
                    setBody(body)
                }
                if (!response.status.isSuccess()) {
                    error("HTTP ${response.status.value}: ${response.status.description}")
                }
                MmnJson.decodeFromString<ZkProofResponse>(response.bodyAsText()).data
            }
        } catch (e: CancellationException) {
            throw e
        }
    }

    private fun joinUrl(
        path: String
    ): String {
        return config.endpoint.trimEnd('/') + "/" + path.trimStart('/')
    }

    private fun defaultHeaders(): Map<String, String> {
        return buildMap {
            put("Accept", "application/json")
            put("Content-Type", "application/json")
            putAll(config.headers)
        }
    }
}

fun createZkClient(
    config: ZkClientConfig,
    httpClient: HttpClient
): ZkClient = ZkClient(config, httpClient)
