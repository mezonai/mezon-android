package com.mezon.mobile.network

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RealtimeServerStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    var choice: RealtimeServerChoice = storedChoice()
        private set

    fun save(next: RealtimeServerChoice) {
        choice = next
        prefs.edit().putString(KEY_CHOICE, next.name).apply()
    }

    private fun storedChoice(): RealtimeServerChoice {
        if (!RealtimeServerChoice.isAvailable) return RealtimeServerChoice.AUTO
        val stored = prefs.getString(KEY_CHOICE, null)
        return RealtimeServerChoice.entries.firstOrNull { it.name == stored } ?: RealtimeServerChoice.AUTO
    }

    companion object {
        private const val PREFS_NAME = "realtime_server"
        private const val KEY_CHOICE = "choice"
    }
}
