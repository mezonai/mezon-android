package com.mezon.mobile.home.chat

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "EmojiRepository"
private const val PREFS_NAME = "reaction_prefs"
private const val KEY_RECENT_EMOJIS = "recent_emojis_v2"
private const val MAX_RECENT = 20
private const val CACHE_TTL_MS = 5 * 60 * 1000L

@Singleton
class EmojiRepository @Inject constructor(
    @ApplicationContext context: Context
) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── In-memory emoji cache ──────────────────────────────────────────────
    @Volatile private var cachedEmojis: List<IEmoji> = emptyList()
    @Volatile private var lastFetchTime: Long = 0L

    fun getCachedEmojis(noCache: Boolean = false): List<IEmoji>? {
        if (noCache) return null
        val now = System.currentTimeMillis()
        if (cachedEmojis.isNotEmpty() && (now - lastFetchTime) < CACHE_TTL_MS) {
            Log.d(TAG, "Cache hit: ${cachedEmojis.size} emojis (age=${now - lastFetchTime}ms)")
            return cachedEmojis
        }
        return null
    }

    fun cacheEmojis(emojis: List<IEmoji>) {
        cachedEmojis = emojis
        lastFetchTime = System.currentTimeMillis()
        Log.d(TAG, "Cached ${emojis.size} emojis")
    }

    fun invalidateCache() {
        cachedEmojis = emptyList()
        lastFetchTime = 0L
        Log.d(TAG, "Cache invalidated")
    }

    fun getRecentEmojis(): List<RecentEmoji> {
        val json = prefs.getString(KEY_RECENT_EMOJIS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optString("id")
                val shortname = obj.optString("shortname")
                if (id.isNotBlank()) RecentEmoji(id, shortname) else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse recent emojis", e)
            emptyList()
        }
    }

    fun saveRecentEmoji(id: String, shortname: String) {
        if (id.isBlank()) return
        val current = getRecentEmojis().toMutableList()
        current.removeAll { it.id == id }
        current.add(0, RecentEmoji(id, shortname))
        if (current.size > MAX_RECENT) {
            current.subList(MAX_RECENT, current.size).clear()
        }
        val arr = JSONArray()
        current.forEach { emoji ->
            arr.put(JSONObject().apply {
                put("id", emoji.id)
                put("shortname", emoji.shortname)
            })
        }
        prefs.edit().putString(KEY_RECENT_EMOJIS, arr.toString()).apply()
        Log.d(TAG, "Saved recent emoji: id=$id shortname=$shortname (total=${current.size})")
    }
}
