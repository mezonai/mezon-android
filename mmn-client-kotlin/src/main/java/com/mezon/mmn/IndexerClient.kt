package com.mezon.mmn

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout

class IndexerClient(
    private val config: IndexerClientConfig,
    private val httpClient: HttpClient
) {
    companion object {
        const val FILTER_ALL: Int = 0
        const val FILTER_RECEIVED: Int = 1
        const val FILTER_SENT: Int = 2
    }

    suspend fun getTransactionByHash(
        hash: String
    ): Transaction {
        val path = "${config.chainId}/tx/$hash/detail"
        val res = getJson<TransactionDetailResponse>(path, emptyList())
        return res.data.transaction
    }

    suspend fun getTransactionsByWalletBeforeTimestamp(
        wallet: String,
        filter: Int,
        limit: Int? = null,
        timestampLt: String? = null,
        lastHash: String? = null
    ): ListTransactionResponse {
        require(wallet.isNotEmpty()) { "wallet address cannot be empty" }
        var finalLimit = if (limit != null && limit > 0) limit else 20
        if (finalLimit > 1000) finalLimit = 1000
        val query = mutableListOf<Pair<String, String>>("limit" to finalLimit.toString())
        timestampLt?.let { query.add("timestamp_lt" to it) }
        lastHash?.let { query.add("last_hash" to it) }
        when (filter) {
            FILTER_ALL -> query.add("wallet_address" to wallet)
            FILTER_SENT -> query.add("filter_from_address" to wallet)
            FILTER_RECEIVED -> query.add("filter_to_address" to wallet)
        }
        val path = "${config.chainId}/transactions/infinite"
        return getJson(path, query)
    }

    suspend fun getTransactionByWallet(
        wallet: String,
        page: Int = 1,
        limit: Int = 50,
        filter: Int,
        sortBy: String = "transaction_timestamp",
        sortOrder: String = "desc"
    ): ListTransactionResponse {
        require(wallet.isNotEmpty()) { "wallet address cannot be empty" }
        var p = page
        if (p < 1) p = 1
        var l = limit
        if (l <= 0) l = 50
        if (l > 1000) l = 1000
        val query = mutableListOf(
            "page" to (p - 1).toString(),
            "limit" to l.toString(),
            "sort_by" to sortBy,
            "sort_order" to sortOrder
        )
        when (filter) {
            FILTER_ALL -> query.add("wallet_address" to wallet)
            FILTER_SENT -> query.add("filter_from_address" to wallet)
            FILTER_RECEIVED -> query.add("filter_to_address" to wallet)
        }
        val path = "${config.chainId}/transactions"
        return getJson(path, query)
    }

    suspend fun getWalletDetail(
        wallet: String
    ): WalletDetail {
        require(wallet.isNotEmpty()) { "wallet address cannot be empty" }
        val path = "${config.chainId}/wallets/${wallet}/detail"
        return getJson<WalletDetailResponse>(path, emptyList()).data
    }

    private fun joinUrl(
        path: String
    ): String {
        return config.endpoint.trimEnd('/') + "/" + path.trimStart('/')
    }

    private suspend inline fun <reified T> getJson(
        path: String,
        query: List<Pair<String, String>>
    ): T {
        return try {
            withTimeout(config.timeoutMs) {
                val response = httpClient.get(joinUrl(path)) {
                    headers {
                        append("Accept", "application/json")
                        for ((k, v) in config.headers) {
                            append(k, v)
                        }
                    }
                    for ((k, v) in query) {
                        parameter(k, v)
                    }
                }
                if (!response.status.isSuccess()) {
                    error("HTTP ${response.status.value}: ${response.status.description}")
                }
                MmnJson.decodeFromString<T>(response.bodyAsText())
            }
        } catch (e: CancellationException) {
            throw e
        }
    }
}

fun createIndexerClient(
    config: IndexerClientConfig,
    httpClient: HttpClient
): IndexerClient = IndexerClient(config, httpClient)
