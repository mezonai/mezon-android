package com.mezon.mobile.network

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApiCacheTracker @Inject constructor() {

    companion object {
        private const val TAG = "ApiCacheTracker"
        const val DEFAULT_CACHE_TTL_MS = 20 * 60 * 1_000L // 20 minutes
        const val LIST_CACHE_TTL_MS = 5 * 60 * 1_000L
        private const val MAX_ENTRIES = 1_000
    }

    enum class ShouldCall { CALL, SKIP }

    private val entries = ConcurrentHashMap<String, Long>()

    fun shouldCall(
        key: String,
        ttlMs: Long = DEFAULT_CACHE_TTL_MS,
        noCache: Boolean = false
    ): ShouldCall {
        if (noCache) return ShouldCall.CALL

        val expiresAt = entries[key]
        if (expiresAt == null) return ShouldCall.CALL

        return if (System.currentTimeMillis() < expiresAt) ShouldCall.SKIP else ShouldCall.CALL
    }

    fun markCalled(key: String, ttlMs: Long = DEFAULT_CACHE_TTL_MS) {
        evictIfNeeded()
        entries[key] = System.currentTimeMillis() + ttlMs
    }

    fun invalidate(key: String) {
        entries.remove(key)
    }

    fun invalidateByPrefix(prefix: String) {
        entries.keys.removeAll { it.startsWith(prefix) }
    }

    fun invalidateAll() {
        Log.d(TAG, "Invalidating all cache entries (${entries.size} keys)")
        entries.clear()
    }

    private fun evictIfNeeded() {
        if (entries.size <= MAX_ENTRIES) return
        val toRemove = entries.size / 2
        var removed = 0
        val iter = entries.keys.iterator()
        while (iter.hasNext() && removed < toRemove) {
            iter.next()
            iter.remove()
            removed++
        }
    }
}

fun apiCacheKey(apiName: String, vararg params: Any): String =
    "${apiName}_${params.joinToString("_")}"
