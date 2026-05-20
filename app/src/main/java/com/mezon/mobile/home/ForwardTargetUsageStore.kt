package com.mezon.mobile.home

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ForwardTargetUsageStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()
    private val lastSentByTarget = HashMap<String, Long>()

    init {
        for ((key, value) in prefs.all) {
            val ts = when (value) {
                is Long -> value
                is Int -> value.toLong()
                else -> 0L
            }
            if (ts > 0L) lastSentByTarget[key] = ts
        }
    }

    fun getLastSent(channelId: Long, channelType: Int): Long {
        if (channelId == 0L || channelType == 0) return 0L
        return synchronized(lock) { lastSentByTarget[key(channelId, channelType)] ?: 0L }
    }

    fun markLastSent(
        channelId: Long,
        channelType: Int,
        timestampSeconds: Long = System.currentTimeMillis() / 1000L
    ) {
        if (channelId == 0L || channelType == 0 || timestampSeconds <= 0L) return
        val cacheKey = key(channelId, channelType)
        var shouldPersist = false
        synchronized(lock) {
            val previous = lastSentByTarget[cacheKey] ?: 0L
            if (timestampSeconds > previous) {
                lastSentByTarget[cacheKey] = timestampSeconds
                shouldPersist = true
            }
        }
        if (shouldPersist) {
            prefs.edit().putLong(cacheKey, timestampSeconds).apply()
        }
    }

    companion object {
        private const val PREFS_NAME = "forward_target_usage"
        private fun key(channelId: Long, channelType: Int) = "${channelType}_$channelId"
    }
}
