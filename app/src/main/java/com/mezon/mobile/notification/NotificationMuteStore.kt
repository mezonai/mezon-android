package com.mezon.mobile.notification

import android.content.Context
import android.util.Log
import com.mezon.mobile.core.StartupCache
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Persistent local fallback when the server still sends pushes for a muted conversation. */
@Singleton
class NotificationMuteStore internal constructor(
    context: Context,
    private val currentUserId: () -> String,
    private val nowMillis: () -> Long,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(
        context, { StartupCache.userId }, { System.currentTimeMillis() }
    )

    private val preferences = context.getSharedPreferences("notification_mute_expiries", Context.MODE_PRIVATE)

    private fun key(userId: String, channelId: Long): String? =
        if (userId.isBlank() || channelId == 0L) null else "$userId:$channelId"

    /** Called after the server accepts a mute or unmute request. */
    fun setDuration(userId: String, channelId: Long, durationSeconds: Int) {
        val key = key(userId, channelId) ?: return
        val expiry = when {
            durationSeconds == -1 -> Long.MAX_VALUE
            durationSeconds > 0 -> nowMillis() + durationSeconds * 1000L
            else -> 0L
        }
        val editor = preferences.edit()
        if (expiry == 0L) editor.remove(key) else editor.putLong(key, expiry)
        if (!editor.commit()) Log.w("NotificationMute", "could not persist mute state")
    }

    /** Accept an absolute Unix time, a duration in seconds, or -1 for indefinite mute. */
    fun updateFromServer(channelId: Long, muteTimeSeconds: Int) {
        val key = key(currentUserId(), channelId) ?: return
        val expiry = when {
            muteTimeSeconds == -1 -> Long.MAX_VALUE
            muteTimeSeconds > 100_000_000 -> muteTimeSeconds * 1000L
            muteTimeSeconds > 0 -> nowMillis() + muteTimeSeconds * 1000L
            else -> 0L
        }
        val editor = preferences.edit()
        if (expiry == 0L) editor.remove(key) else editor.putLong(key, expiry)
        editor.apply()
    }

    fun clear(channelId: Long) {
        val key = key(currentUserId(), channelId) ?: return
        preferences.edit().remove(key).apply()
    }

    fun isMuted(channelId: Long): Boolean {
        val key = key(currentUserId(), channelId) ?: return false
        return preferences.getLong(key, 0L) > nowMillis()
    }
}
