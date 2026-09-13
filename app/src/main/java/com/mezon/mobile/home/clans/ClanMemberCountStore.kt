package com.mezon.mobile.home.clans

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ClanMemberCountStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(clanId: Long): Int {
        if (clanId == 0L) return 0
        return prefs.getInt(key(clanId), 0)
    }

    fun save(clanId: Long, count: Int) {
        if (clanId == 0L || count < 0) return
        if (prefs.getInt(key(clanId), -1) == count) return
        prefs.edit().putInt(key(clanId), count).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "clan_member_count"
        private fun key(clanId: Long) = "count_$clanId"
    }
}
