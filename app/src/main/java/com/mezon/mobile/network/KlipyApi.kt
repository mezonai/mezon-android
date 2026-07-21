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

private const val TAG = "KlipyApi"

@Serializable
data class KlipyCategoryResponse(
    val data: KlipyCategoryData? = null
)

@Serializable
data class KlipyCategoryData(
    val categories: List<KlipyCategoryRaw> = emptyList()
)

@Serializable
data class KlipySearchResponse(
    val data: KlipySearchData? = null
)

@Serializable
data class KlipySearchData(
    val data: List<KlipyGifRaw> = emptyList()
)

@Serializable
data class KlipyCategoryRaw(
    val category: String = "",
    @SerialName("preview_url") val previewUrl: String = ""
)

@Serializable
data class KlipyGifRaw(
    val id: Long = 0,
    val slug: String = "",
    @SerialName("blur_preview") val blurPreview: String? = null,
    val file: KlipyFileFormats? = null
)

@Serializable
data class KlipyFileFormats(
    val hd: KlipyFormatDetails? = null,
    val md: KlipyFormatDetails? = null
)

@Serializable
data class KlipyFormatDetails(
    val gif: KlipyFormatUrl? = null
)

@Serializable
data class KlipyFormatUrl(
    val url: String? = null,
    val width: Int = 0,
    val height: Int = 0
)

data class KlipyCategory(
    val name: String,
    val imageUrl: String
)

data class KlipyGif(
    val id: String,
    val gifUrl: String,
    val thumbnailUrl: String,
    val width: Int = 0,
    val height: Int = 0
)

@Singleton
class KlipyApi @Inject constructor(
    private val httpClient: HttpClient
) {
    suspend fun fetchCategories(baseUrl: String, apiKey: String): List<KlipyCategory> {
        return try {
            val response = httpClient.get("$baseUrl/$apiKey/gifs/categories")
            if (!response.status.isSuccess()) {
                Log.e(TAG, "fetchCategories failed with status: ${response.status}")
                return emptyList()
            }
            val body: KlipyCategoryResponse = response.body()
            val cats = body.data?.categories?.map { KlipyCategory(name = it.category, imageUrl = it.previewUrl) } ?: emptyList()
            Log.d(TAG, "fetchCategories returned ${cats.size} items")
            cats
        } catch (e: Exception) {
            Log.e(TAG, "fetchCategories exception", e)
            emptyList()
        }
    }

    suspend fun searchGifs(baseUrl: String, apiKey: String, query: String, page: Int = 1, perPage: Int = 30): List<KlipyGif> {
        return try {
            val response = httpClient.get("$baseUrl/$apiKey/gifs/search") {
                parameter("q", query)
                parameter("page", page)
                parameter("per_page", perPage)
                parameter("format_filter", "gif")
            }
            if (!response.status.isSuccess()) {
                Log.e(TAG, "searchGifs failed with status: ${response.status}")
                return emptyList()
            }
            val body: KlipySearchResponse = response.body()
            val gifs = body.data?.data?.mapNotNull { it.toKlipyGif() } ?: emptyList()
            Log.d(TAG, "searchGifs returned ${gifs.size} items")
            gifs
        } catch (e: Exception) {
            Log.e(TAG, "searchGifs exception", e)
            emptyList()
        }
    }

    suspend fun fetchTrending(baseUrl: String, apiKey: String, page: Int = 1, perPage: Int = 30): List<KlipyGif> {
        return try {
            val response = httpClient.get("$baseUrl/$apiKey/stickers/trending") {
                parameter("page", page)
                parameter("per_page", perPage)
                parameter("format_filter", "gif")
            }
            if (!response.status.isSuccess()) {
                Log.e(TAG, "fetchTrending failed with status: ${response.status}")
                return emptyList()
            }
            val body: KlipySearchResponse = response.body()
            val gifs = body.data?.data?.mapNotNull { it.toKlipyGif() } ?: emptyList()
            Log.d(TAG, "fetchTrending returned ${gifs.size} items")
            gifs
        } catch (e: Exception) {
            Log.e(TAG, "fetchTrending exception", e)
            emptyList()
        }
    }

    private fun KlipyGifRaw.toKlipyGif(): KlipyGif? {
        val hdGif = file?.hd?.gif
        val mdGif = file?.md?.gif
        
        val hdUrl = hdGif?.url
        val mdUrl = mdGif?.url
        
        val url = hdUrl ?: mdUrl ?: return null
        val thumbUrl = mdUrl ?: hdUrl ?: return null
        
        val w = hdGif?.width ?: mdGif?.width ?: 0
        val h = hdGif?.height ?: mdGif?.height ?: 0
        
        return KlipyGif(
            id = id.toString(),
            gifUrl = url,
            thumbnailUrl = thumbUrl,
            width = w,
            height = h
        )
    }
}
