package com.mezon.mobile.home.clans

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class CategoryExpandState(
    val allExpanded: Boolean,
    val expandedCategoryIds: Set<Long>
) {
    companion object {
        val DEFAULT = CategoryExpandState(true, emptySet())
    }
}

@Singleton
class ChannelCategoryExpandStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(clanId: Long): CategoryExpandState {
        if (clanId == 0L) return CategoryExpandState.DEFAULT
        val allExpanded = prefs.getBoolean(keyAll(clanId), true)
        val raw = prefs.getString(keySet(clanId), null)
        val expanded = if (raw.isNullOrBlank()) {
            emptySet()
        } else {
            raw.split(',').mapNotNull { it.toLongOrNull() }.toSet()
        }
        return CategoryExpandState(allExpanded, expanded)
    }

    fun save(clanId: Long, allExpanded: Boolean, expandedCategoryIds: Set<Long>) {
        if (clanId == 0L) return
        prefs.edit()
            .putBoolean(keyAll(clanId), allExpanded)
            .putString(keySet(clanId), expandedCategoryIds.sorted().joinToString(","))
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "channel_category_expand"
        private fun keyAll(clanId: Long) = "all_$clanId"
        private fun keySet(clanId: Long) = "set_$clanId"
    }
}
