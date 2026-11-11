package com.mezon.mobile.home.chat.channelinfo

import java.util.Calendar
import java.util.Locale

data class ChannelDocumentItem(
    val stableId: String,
    val filename: String,
    val filetype: String,
    val url: String,
    val uploader: Long,
    val createTimeSeconds: Int,
    val messageId: Long,
    val anonymousSharerName: String? = null
)

private val GROUPING_CAL_TL = ThreadLocal.withInitial { Calendar.getInstance() }
private val WHITESPACE_REGEX = Regex("\\s+")

object ChannelDocumentItemUtil {
    fun parseItemDate(item: ChannelDocumentItem): Calendar {
        val c = GROUPING_CAL_TL.get()
        val sec = item.createTimeSeconds
        c.timeInMillis = if (sec > 0) sec.toLong() * 1000L else System.currentTimeMillis()
        return c
    }
}

data class YearDayGroup<T>(
    val year: String,
    val dayTs: Long,
    val items: List<T>,
    val isFirstOfYear: Boolean
)

fun <T> groupByYearDay(items: List<T>, getDate: (T) -> Calendar): List<YearDayGroup<T>> {
    if (items.isEmpty()) return emptyList()
    val sorted = items.sortedByDescending { getDate(it).timeInMillis }
    val map = LinkedHashMap<String, LinkedHashMap<Long, ArrayList<T>>>()
    for (it in sorted) {
        val cal = getDate(it)
        val year = cal.get(Calendar.YEAR).toString()
        val dayKey = startOfDayMillis(cal)
        map.getOrPut(year) { LinkedHashMap() }.getOrPut(dayKey) { ArrayList() }.add(it)
    }
    val result = ArrayList<YearDayGroup<T>>()
    val years = map.keys.sortedByDescending { it.toIntOrNull() ?: 0 }
    for (year in years) {
        val daysMap = map[year] ?: continue
        val dayKeys = daysMap.keys.sortedDescending()
        dayKeys.forEachIndexed { idx, dayTs ->
            val dayItems = daysMap[dayTs] ?: return@forEachIndexed
            result.add(
                YearDayGroup(
                    year = year,
                    dayTs = dayTs,
                    items = dayItems,
                    isFirstOfYear = idx == 0
                )
            )
        }
    }
    return result
}

fun startOfDayMillis(cal: Calendar): Long {
    val savedHour = cal.get(Calendar.HOUR_OF_DAY)
    val savedMin = cal.get(Calendar.MINUTE)
    val savedSec = cal.get(Calendar.SECOND)
    val savedMs = cal.get(Calendar.MILLISECOND)
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    val ms = cal.timeInMillis
    cal.set(Calendar.HOUR_OF_DAY, savedHour)
    cal.set(Calendar.MINUTE, savedMin)
    cal.set(Calendar.SECOND, savedSec)
    cal.set(Calendar.MILLISECOND, savedMs)
    return ms
}

fun formatDateHeader(dayCal: Calendar, isVietnamese: Boolean): String {
    val day = (dayCal.get(Calendar.DAY_OF_MONTH)).toString().padStart(2, '0')
    val monthNum = (dayCal.get(Calendar.MONTH) + 1).toString().padStart(2, '0')
    val year = dayCal.get(Calendar.YEAR).toString()
    if (isVietnamese) {
        return "$day tháng $monthNum, $year"
    }
    val monthName = dayCal.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.US) ?: ""
    return "$monthName $day, $year"
}

fun normalizeSearchQuery(s: String): String = s.replace(WHITESPACE_REGEX, "").lowercase(Locale.getDefault())
