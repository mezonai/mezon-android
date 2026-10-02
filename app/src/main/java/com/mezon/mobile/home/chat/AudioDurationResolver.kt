package com.mezon.mobile.home.chat

import android.media.MediaMetadataRetriever
import android.util.LruCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal object AudioDurationResolver {
    private const val MAX_CACHE_ENTRIES = 128

    private val lock = Any()
    private val cache = LruCache<String, Long>(MAX_CACHE_ENTRIES)
    private val inFlight = HashMap<String, Deferred<Long>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun resolve(url: String): Long {
        val key = url.trim()
        if (!key.startsWith("http://", true) && !key.startsWith("https://", true)) return 0L
        return getOrStart(key).await()
    }

    private fun getOrStart(url: String): Deferred<Long> = synchronized(lock) {
        cache.get(url)?.let { return@synchronized CompletableDeferred(it) }
        inFlight[url]?.let { return@synchronized it }

        val result = CompletableDeferred<Long>()
        inFlight[url] = result
        scope.launch {
            val durationMs = readDurationMs(url)
            synchronized(lock) {
                if (durationMs > 0L) cache.put(url, durationMs)
                inFlight.remove(url)
            }
            result.complete(durationMs)
        }
        result
    }

    private fun readDurationMs(url: String): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(url, emptyMap())
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }
}
