package com.mezon.mobile.home.chat

import android.util.Log
import com.mezon.mobile.BuildConfig
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.network.ApiCacheTracker
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.network.SocketEventDispatcher
import com.mezon.mobile.network.TenorApi
import com.mezon.mobile.network.TenorCategory
import com.mezon.mobile.network.TenorGif
import com.mezon.mobile.session.SessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

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
    private val api: MezonApi,
    private val tenorApi: TenorApi,
    private val dispatcher: SocketEventDispatcher,
    private val notificationCenter: NotificationCenter,
    private val sessionManager: SessionManager,
    private val cacheTracker: ApiCacheTracker,
    @ApplicationScope private val appScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    val emojis = ArrayList<EmojiItem>()
    val emojisDict = HashMap<String, EmojiItem>()
    val stickers = ArrayList<StickerItem>()
    val stickersDict = HashMap<String, StickerItem>()

    val gifCategories = ArrayList<TenorCategory>()
    val featuredGifs = ArrayList<TenorGif>()
    val searchGifResults = ArrayList<TenorGif>()

    @Volatile
    private var emojisLoaded = false
    @Volatile
    private var stickersLoaded = false

    private var emojiLoadJob: Job? = null
    private var emojiLoadSerial = 0

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
            emojis.filter { it.shortname.lowercase().contains(lower) }
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
            try {
                val response = sessionManager.withAutoRefresh { session ->
                    api.listEmojisByUserId(session.apiUrl, session.token)
                }
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
                synchronized(this@EmojiController) {
                    if (serial != emojiLoadSerial) return@synchronized
                    emojis.clear()
                    emojisDict.clear()
                    emojis.addAll(items)
                    for (item in items) emojisDict[item.id] = item
                    emojisLoaded = true
                }
                if (serial != emojiLoadSerial) return@launch
                cacheTracker.markCalled("emojis_by_user")
                Log.d(TAG, "Loaded ${items.size} emojis")
                notificationCenter.postNotificationOnMainThread(NotificationCenter.emojisNeedReload)
            } catch (e: Exception) {
                Log.e(TAG, "loadEmojis failed", e)
            }
        }
    }

    fun loadStickers() {
        if (stickersLoaded && cacheTracker.shouldCall("stickers_by_user") == ApiCacheTracker.ShouldCall.SKIP) return
        appScope.launch(ioDispatcher) {
            try {
                val response = sessionManager.withAutoRefresh { session ->
                    api.listStickersByUserId(session.apiUrl, session.token)
                }
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
                synchronized(this@EmojiController) {
                    stickers.clear()
                    stickersDict.clear()
                    stickers.addAll(items)
                    for (item in items) stickersDict[item.id] = item
                    stickersLoaded = true
                }
                cacheTracker.markCalled("stickers_by_user")
                Log.d(TAG, "Loaded ${items.size} stickers")
                notificationCenter.postNotificationOnMainThread(NotificationCenter.stickersNeedReload)
            } catch (e: Exception) {
                Log.e(TAG, "loadStickers failed", e)
            }
        }
    }

    fun loadGifCategories() {
        appScope.launch(ioDispatcher) {
            val cats = tenorApi.fetchCategories(BuildConfig.TENOR_API_KEY)
            synchronized(this@EmojiController) {
                gifCategories.clear()
                gifCategories.addAll(cats)
            }
            notificationCenter.postNotificationOnMainThread(NotificationCenter.gifsNeedReload)
        }
    }

    fun loadFeaturedGifs() {
        appScope.launch(ioDispatcher) {
            val gifs = tenorApi.fetchFeatured(BuildConfig.TENOR_API_KEY)
            synchronized(this@EmojiController) {
                featuredGifs.clear()
                featuredGifs.addAll(gifs)
            }
            notificationCenter.postNotificationOnMainThread(NotificationCenter.gifsNeedReload)
        }
    }

    fun searchGifs(query: String) {
        appScope.launch(ioDispatcher) {
            val gifs = tenorApi.searchGifs(BuildConfig.TENOR_API_KEY, query)
            synchronized(this@EmojiController) {
                searchGifResults.clear()
                searchGifResults.addAll(gifs)
            }
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
        synchronized(this) { stickersLoaded = false }
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
        synchronized(this) { ++emojiLoadSerial }
        emojiLoadJob?.cancel()
        emojiLoadJob = null
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
    }
}
