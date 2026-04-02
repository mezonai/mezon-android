package com.mezon.mobile.network

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TenorApi"
private const val BASE_URL = "https://tenor.googleapis.com/v2"

@Serializable
data class TenorCategoryResponse(
    val tags: List<TenorCategoryTag> = emptyList()
)

@Serializable
data class TenorCategoryTag(
    @SerialName("searchterm") val searchTerm: String = "",
    val image: String = ""
)

@Serializable
data class TenorSearchResponse(
    val results: List<TenorResult> = emptyList(),
    val next: String = ""
)

@Serializable
data class TenorResult(
    val id: String = "",
    @SerialName("media_formats") val mediaFormats: TenorMediaFormats = TenorMediaFormats()
)

@Serializable
data class TenorMediaFormats(
    val gif: TenorMediaFormat? = null,
    val tinygif: TenorMediaFormat? = null,
    val mp4: TenorMediaFormat? = null
)

@Serializable
data class TenorMediaFormat(
    val url: String = "",
    @SerialName("dims") val dims: List<Int> = emptyList(),
    val size: Long = 0L
)

data class TenorGif(
    val id: String,
    val gifUrl: String,
    val thumbnailUrl: String,
    val width: Int,
    val height: Int
)

data class TenorCategory(
    val name: String,
    val imageUrl: String
)

@Singleton
class TenorApi @Inject constructor(
    private val httpClient: HttpClient
) {
    suspend fun fetchCategories(apiKey: String, clientKey: String = "mezon_android"): List<TenorCategory> {
        return try {
            val response = httpClient.get("$BASE_URL/categories") {
                parameter("key", apiKey)
                parameter("client_key", clientKey)
            }
            if (!response.status.isSuccess()) return emptyList()
            val body: TenorCategoryResponse = response.body()
            body.tags.map { TenorCategory(name = it.searchTerm, imageUrl = it.image) }
        } catch (e: Exception) {
            Log.e(TAG, "fetchCategories failed", e)
            emptyList()
        }
    }

    suspend fun searchGifs(apiKey: String, query: String, limit: Int = 30, clientKey: String = "mezon_android"): List<TenorGif> {
        return try {
            val response = httpClient.get("$BASE_URL/search") {
                parameter("q", query)
                parameter("key", apiKey)
                parameter("client_key", clientKey)
                parameter("limit", limit)
                parameter("media_filter", "gif,tinygif")
            }
            if (!response.status.isSuccess()) return emptyList()
            val body: TenorSearchResponse = response.body()
            body.results.mapNotNull { it.toTenorGif() }
        } catch (e: Exception) {
            Log.e(TAG, "searchGifs failed", e)
            emptyList()
        }
    }

    suspend fun fetchFeatured(apiKey: String, limit: Int = 30, clientKey: String = "mezon_android"): List<TenorGif> {
        return try {
            val response = httpClient.get("$BASE_URL/featured") {
                parameter("key", apiKey)
                parameter("client_key", clientKey)
                parameter("limit", limit)
                parameter("media_filter", "gif,tinygif")
            }
            if (!response.status.isSuccess()) return emptyList()
            val body: TenorSearchResponse = response.body()
            body.results.mapNotNull { it.toTenorGif() }
        } catch (e: Exception) {
            Log.e(TAG, "fetchFeatured failed", e)
            emptyList()
        }
    }

    private fun TenorResult.toTenorGif(): TenorGif? {
        val gif = mediaFormats.gif ?: return null
        val thumb = mediaFormats.tinygif ?: gif
        val dims = gif.dims
        return TenorGif(
            id = id,
            gifUrl = gif.url,
            thumbnailUrl = thumb.url,
            width = dims.getOrElse(0) { 0 },
            height = dims.getOrElse(1) { 0 }
        )
    }
}
