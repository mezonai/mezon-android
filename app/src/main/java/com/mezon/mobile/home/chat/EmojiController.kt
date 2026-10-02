package com.mezon.mobile.home.chat

import android.content.Context
import android.util.Log
import com.mezon.mezon.api.EmojiListedResponse
import com.mezon.mezon.api.StickerListedResponse
import com.mezon.mobile.BuildConfig
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.StartupCache
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.network.ApiCacheTracker
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.network.SocketEventDispatcher
import com.mezon.mobile.network.KlipyApi
import com.mezon.mobile.network.KlipyCategory
import com.mezon.mobile.network.KlipyGif
import com.mezon.mobile.session.SessionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val STARTUP_REFRESH_DELAY_MS = 6_000L

const val LIKE_EMOJI_ID = 7227274405303613492L
const val LIKE_EMOJI_SHORTNAME = ":like:"
const val LIKE_EMOJI_DISPLAY = "👍"

data class EmojiItem(
    val id: String,
    val shortname: String,
    val src: String,
    val category: String,
    val clanId: String,
    val clanName: String,
    val clanLogo: String,
    val isForSale: Boolean,
    val creatorId: String
)

data class StickerItem(
    val id: String,
    val shortname: String,
    val src: String,
    val category: String,
    val clanId: String,
    val clanName: String,
    val clanLogo: String,
    val isForSale: Boolean,
    val creatorId: String,
    val mediaType: Int
) {
    val isAudio: Boolean get() = mediaType == MEDIA_TYPE_AUDIO ||
        src.endsWith(".mp3", true) || src.endsWith(".wav", true) || src.contains("/sounds/")

    companion object {
        const val MEDIA_TYPE_STICKER = 0
        const val MEDIA_TYPE_AUDIO = 1
    }
}

data class EmojiCategory(
    val name: String,
    val clanId: String,
    val clanLogo: String
)

private const val TAG = "EmojiController"

val PREDEFINED_CATEGORIES = listOf(
    "Recent", "Frequently", "People", "Nature", "Food",
    "Activities", "Travel", "Objects", "Symbols", "Flags"
)

@Singleton
class EmojiController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: MezonApi,
    private val klipyApi: KlipyApi,
    private val dispatcher: SocketEventDispatcher,
    val notificationCenter: NotificationCenter,
    private val sessionManager: SessionManager,
    private val cacheTracker: ApiCacheTracker,
    @ApplicationScope private val appScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    val emojis = ArrayList<EmojiItem>()
    val emojisDict = HashMap<String, EmojiItem>()
    val stickers = ArrayList<StickerItem>()
    val stickersDict = HashMap<String, StickerItem>()

    val gifCategories = ArrayList<KlipyCategory>()
    val featuredGifs = ArrayList<KlipyGif>()
    val searchGifResults = ArrayList<KlipyGif>()

    @Volatile
    private var emojisLoaded = false
    @Volatile
    private var stickersLoaded = false

    private var emojiLoadJob: Job? = null
    private var emojiLoadSerial = 0

    private var searchGifsJob: Job? = null
    private var searchGifsSerial = 0
    private var searchGifsIsTrending = false
    private var searchGifsQuery = ""

    init {
        appScope.launch { observeEmojiEvents() }
        appScope.launch { observeStickerEvents() }
        appScope.launch { observeStickerUpdateEvents() }
        appScope.launch { observeStickerDeleteEvents() }
    }

    fun getEmojiById(id: String): EmojiItem? = synchronized(this) { emojisDict[id] }

    fun getCategories(): List<EmojiCategory> {
        val clanCategories = ArrayList<EmojiCategory>()
        val seen = HashSet<String>()
        synchronized(this) {
            for (emoji in emojis) {
                if (emoji.clanId != "0" && emoji.clanId.isNotBlank() && seen.add(emoji.clanId)) {
                    clanCategories.add(EmojiCategory(emoji.clanName, emoji.clanId, emoji.clanLogo))
                }
            }
        }
        val result = ArrayList<EmojiCategory>()
        result.add(EmojiCategory("Recent", "", ""))
        result.add(EmojiCategory("Frequently", "", ""))
        result.addAll(clanCategories)
        for (cat in PREDEFINED_CATEGORIES) {
            if (cat != "Recent" && cat != "Frequently") {
                result.add(EmojiCategory(cat, "0", ""))
            }
        }
        return result
    }

    fun getEmojisByCategory(category: String): List<EmojiItem> {
        return synchronized(this) {
            when (category) {
                "Recent", "Frequently" -> emojis.take(20)
                else -> {
                    val catEmojis = emojis.filter { it.category == category }
                    if (catEmojis.isNotEmpty()) return@synchronized catEmojis
                    emojis.filter { it.clanName == category }
                }
            }
        }
    }

    fun searchEmojis(query: String): List<EmojiItem> {
        val lower = query.lowercase()
        return synchronized(this) {
            emojis.filter { it.shortname.lowercase().contains(lower) }.distinctBy { it.id }
        }
    }

    fun searchStickers(query: String, excludeAudio: Boolean = true): List<StickerItem> {
        val lower = query.lowercase()
        return synchronized(this) {
            stickers.filter {
                it.shortname.lowercase().contains(lower) && (!excludeAudio || !it.isAudio)
            }
        }
    }

    fun invalidateEmojiCacheAndReload() {
        cacheTracker.invalidate("emojis_by_user")
        synchronized(this) {
            emojisLoaded = false
        }
        loadEmojis()
    }

    fun loadEmojis() {
        if (emojisLoaded && cacheTracker.shouldCall("emojis_by_user") == ApiCacheTracker.ShouldCall.SKIP) return
        val serial = synchronized(this) { ++emojiLoadSerial }
        emojiLoadJob?.cancel()
        emojiLoadJob = appScope.launch(ioDispatcher) {
            if (publishEmojisFromDisk(serial)) delay(STARTUP_REFRESH_DELAY_MS)
            try {
                val response = sessionManager.withAutoRefresh { session ->
                    api.listEmojisByUserId(session.apiUrl, session.token)
                }
                if (applyEmojis(response, serial, "network")) {
                    cacheTracker.markCalled("emojis_by_user")
                    writeDiskCache(emojiCacheFile(), response.toByteArray())
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadEmojis failed", e)
            }
        }
    }

    private fun emojiCacheFile(): File = File(context.cacheDir, "emoji_list_${StartupCache.userId}.pb")

    private fun stickerCacheFile(): File = File(context.cacheDir, "sticker_list_${StartupCache.userId}.pb")

    private fun publishEmojisFromDisk(serial: Int): Boolean {
        if (emojisLoaded) return false
        val bytes = readDiskCache(emojiCacheFile()) ?: return false
        return try {
            applyEmojis(EmojiListedResponse.parseFrom(bytes), serial, "disk cache")
        } catch (e: Exception) {
            Log.w(TAG, "emoji disk cache unusable", e)
            emojiCacheFile().delete()
            false
        }
    }

    private fun publishStickersFromDisk(): Boolean {
        if (stickersLoaded) return false
        val bytes = readDiskCache(stickerCacheFile()) ?: return false
        return try {
            applyStickers(StickerListedResponse.parseFrom(bytes), "disk cache")
            true
        } catch (e: Exception) {
            Log.w(TAG, "sticker disk cache unusable", e)
            stickerCacheFile().delete()
            false
        }
    }

    private fun applyEmojis(response: EmojiListedResponse, serial: Int, source: String): Boolean {
        val items = response.emojiListList.map { proto ->
            EmojiItem(
                id = proto.id.toString(),
                shortname = proto.shortname,
                src = proto.src,
                category = proto.category,
                clanId = proto.clanId.toString(),
                clanName = proto.clanName,
                clanLogo = proto.logo,
                isForSale = proto.isForSale,
                creatorId = proto.creatorId.toString()
            )
        }
        synchronized(this) {
            if (serial != emojiLoadSerial) return false
            emojis.clear()
            emojisDict.clear()
            emojis.addAll(items)
            for (item in items) emojisDict[item.id] = item
            emojisLoaded = true
        }
        Log.d(TAG, "Loaded ${items.size} emojis ($source)")
        notificationCenter.postNotificationOnMainThread(NotificationCenter.emojisNeedReload)
        return true
    }

    private fun applyStickers(response: StickerListedResponse, source: String) {
        val items = response.stickersList.map { proto ->
            StickerItem(
                id = proto.id.toString(),
                shortname = proto.shortname,
                src = proto.source,
                category = proto.category,
                clanId = proto.clanId.toString(),
                clanName = proto.clanName,
                clanLogo = proto.logo,
                isForSale = proto.isForSale,
                creatorId = proto.creatorId.toString(),
                mediaType = proto.mediaType
            )
        }
        synchronized(this) {
            stickers.clear()
            stickersDict.clear()
            stickers.addAll(items)
            for (item in items) stickersDict[item.id] = item
            stickersLoaded = true
        }
        Log.d(TAG, "Loaded ${items.size} stickers ($source)")
        notificationCenter.postNotificationOnMainThread(NotificationCenter.stickersNeedReload)
    }

    private fun readDiskCache(file: File): ByteArray? = try {
        if (file.exists() && file.length() > 0L) file.readBytes() else null
    } catch (e: Exception) {
        Log.w(TAG, "read ${file.name} failed", e)
        null
    }

    private fun writeDiskCache(file: File, bytes: ByteArray) {
        try {
            file.writeBytes(bytes)
        } catch (e: Exception) {
            Log.w(TAG, "write ${file.name} failed", e)
        }
    }

    fun loadStickers() {
        if (stickersLoaded && cacheTracker.shouldCall("stickers_by_user") == ApiCacheTracker.ShouldCall.SKIP) return
        appScope.launch(ioDispatcher) {
            if (publishStickersFromDisk()) delay(STARTUP_REFRESH_DELAY_MS)
            try {
                val response = sessionManager.withAutoRefresh { session ->
                    api.listStickersByUserId(session.apiUrl, session.token)
                }
                applyStickers(response, "network")
                cacheTracker.markCalled("stickers_by_user")
                writeDiskCache(stickerCacheFile(), response.toByteArray())
            } catch (e: Exception) {
                Log.e(TAG, "loadStickers failed", e)
            }
        }
    }
    @Volatile
    var gifCategoriesLoaded = false
    @Volatile
    var featuredGifsLoaded = false

    fun loadGifCategories() {
        if (gifCategoriesLoaded) return
        appScope.launch(ioDispatcher) {
            val cats = klipyApi.fetchCategories(BuildConfig.KLIPY_API_URL, BuildConfig.KLIPY_API_KEY)
            synchronized(this@EmojiController) {
                val existingTrending = gifCategories.find { it.isTrending }
                val trendingImg = existingTrending?.imageUrl ?: featuredGifs.firstOrNull()?.thumbnailUrl ?: ""
                gifCategories.clear()
                gifCategories.add(KlipyCategory("trending_synthetic", "trending_synthetic", trendingImg, isTrending = true))
                gifCategories.addAll(cats)
            }
            gifCategoriesLoaded = true
            notificationCenter.postNotificationOnMainThread(NotificationCenter.gifsNeedReload)
        }
    }

    fun loadFeaturedGifs() {
        if (featuredGifsLoaded) return
        appScope.launch(ioDispatcher) {
            val gifs = klipyApi.fetchTrending(BuildConfig.KLIPY_API_URL, BuildConfig.KLIPY_API_KEY)
            synchronized(this@EmojiController) {
                featuredGifs.clear()
                featuredGifs.addAll(gifs)
                val trendingCatIndex = gifCategories.indexOfFirst { it.isTrending }
                if (trendingCatIndex >= 0 && gifs.isNotEmpty()) {
                    gifCategories[trendingCatIndex] = KlipyCategory("trending_synthetic", "trending_synthetic", gifs.first().thumbnailUrl, isTrending = true)
                }
            }
            featuredGifsLoaded = true
            notificationCenter.postNotificationOnMainThread(NotificationCenter.gifsNeedReload)
        }
    }

    @Volatile
    var isSearchingGifs: Boolean = false

    fun searchGifs(query: String, isTrending: Boolean = false) {
        if (query == searchGifsQuery && isTrending == this.searchGifsIsTrending) {
            notificationCenter.postNotificationOnMainThread(NotificationCenter.gifsNeedReload)
            return
        }
        searchGifsQuery = query
        this.searchGifsIsTrending = isTrending
        
        if (isTrending) {
            val hasFeatured = synchronized(this@EmojiController) { featuredGifs.isNotEmpty() }
            if (hasFeatured) {
                synchronized(this@EmojiController) {
                    searchGifResults.clear()
                    searchGifResults.addAll(featuredGifs)
                }
                notificationCenter.postNotificationOnMainThread(NotificationCenter.gifsNeedReload)
                return
            }
        }

        val serial = synchronized(this@EmojiController) {
            searchGifResults.clear()
            ++searchGifsSerial
        }
        searchGifsJob?.cancel()
        isSearchingGifs = true
        notificationCenter.postNotificationOnMainThread(NotificationCenter.gifsNeedReload)
        searchGifsJob = appScope.launch(ioDispatcher) {
            val gifs = if (isTrending) {
                klipyApi.fetchTrending(BuildConfig.KLIPY_API_URL, BuildConfig.KLIPY_API_KEY)
            } else {
                klipyApi.searchGifs(BuildConfig.KLIPY_API_URL, BuildConfig.KLIPY_API_KEY, query)
            }
            synchronized(this@EmojiController) {
                if (serial != searchGifsSerial) return@synchronized
                searchGifResults.clear()
                searchGifResults.addAll(gifs)
            }
            if (serial != searchGifsSerial) return@launch
            isSearchingGifs = false
            notificationCenter.postNotificationOnMainThread(NotificationCenter.gifsNeedReload)
        }
    }

    private suspend fun observeEmojiEvents() {
        dispatcher.emojiEvents.collect { event ->
            Log.d(TAG, "EmojiEvent: id=${event.id} action=${event.action}")
            loadEmojis()
        }
    }

    fun invalidateStickerCacheAndReload() {
        cacheTracker.invalidate("stickers_by_user")
        synchronized(this) { 
            stickersLoaded = false 
        }
        loadStickers()
    }

    fun soundsForClan(clanId: Long): List<StickerItem> = synchronized(this) {
        stickers.filter { it.clanId == clanId.toString() && it.isAudio }
    }

    fun imageStickersForClan(clanId: Long): List<StickerItem> = synchronized(this) {
        stickers.filter { it.clanId == clanId.toString() && !it.isAudio }
    }

    private suspend fun observeStickerEvents() {
        dispatcher.stickerCreateEvents.collect {
            Log.d(TAG, "StickerCreateEvent: ${it.clanId}")
            loadStickers()
        }
    }

    private suspend fun observeStickerUpdateEvents() {
        dispatcher.stickerUpdateEvents.collect {
            Log.d(TAG, "StickerUpdateEvent: ${it.stickerId}")
            loadStickers()
        }
    }

    private suspend fun observeStickerDeleteEvents() {
        dispatcher.stickerDeleteEvents.collect {
            Log.d(TAG, "StickerDeleteEvent: ${it.stickerId}")
            loadStickers()
        }
    }

    fun cleanup() {
        synchronized(this) { 
            ++emojiLoadSerial 
            ++searchGifsSerial
        }
        emojiLoadJob?.cancel()
        emojiLoadJob = null
        searchGifsJob?.cancel()
        searchGifsJob = null
        synchronized(this) {
            emojis.clear()
            emojisDict.clear()
            stickers.clear()
            stickersDict.clear()
            gifCategories.clear()
            featuredGifs.clear()
            searchGifResults.clear()
        }
        emojisLoaded = false
        stickersLoaded = false
        gifCategoriesLoaded = false
        featuredGifsLoaded = false
    }
}
