package com.mezon.mobile.util

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object LocaleHelper {

    private val locale: Locale get() = Locale.getDefault()

    private val formatterDay by lazy { SimpleDateFormat("HH:mm", locale) }
    private val formatterDayAmPm by lazy { SimpleDateFormat("h:mm a", locale) }
    private val formatterWeek by lazy { SimpleDateFormat("EEE", locale) }
    private val formatterMonth by lazy { SimpleDateFormat("MMM d", locale) }
    private val formatterYear by lazy { SimpleDateFormat("MM/dd/yy", locale) }
    private val formatterFullDate by lazy { SimpleDateFormat("MMMM d, yyyy", locale) }
    private val formatterScheduleDay by lazy { SimpleDateFormat("MMM d", locale) }

    fun formatPluralString(context: Context, resId: Int, count: Int): String {
        return try {
            context.resources.getQuantityString(resId, count, count)
        } catch (_: Exception) {
            count.toString()
        }
    }

    fun formatPluralString(context: Context, resId: Int, count: Int, vararg args: Any): String {
        return try {
            context.resources.getQuantityString(resId, count, count, *args)
        } catch (_: Exception) {
            count.toString()
        }
    }

    fun getQuantitySuffix(count: Int): String {
        return when (quantityForNumber(count)) {
            QUANTITY_ZERO -> "zero"
            QUANTITY_ONE -> "one"
            QUANTITY_TWO -> "two"
            QUANTITY_FEW -> "few"
            QUANTITY_MANY -> "many"
            else -> "other"
        }
    }

    private fun quantityForNumber(n: Int): Int {
        val lang = locale.language
        return when {
            lang == "vi" || lang == "zh" || lang == "ja" || lang == "ko" -> QUANTITY_OTHER
            lang == "ar" -> pluralRulesArabic(n)
            else -> if (n == 1) QUANTITY_ONE else QUANTITY_OTHER
        }
    }

    private fun pluralRulesArabic(n: Int): Int {
        val mod100 = n % 100
        return when {
            n == 0 -> QUANTITY_ZERO
            n == 1 -> QUANTITY_ONE
            n == 2 -> QUANTITY_TWO
            mod100 in 3..10 -> QUANTITY_FEW
            mod100 in 11..99 -> QUANTITY_MANY
            else -> QUANTITY_OTHER
        }
    }

    fun formatCount(count: Int): String {
        return when {
            count >= 1_000_000_000 -> String.format(locale, "%.1fB", count / 1_000_000_000.0)
            count >= 1_000_000 -> String.format(locale, "%.1fM", count / 1_000_000.0)
            count >= 1_000 -> String.format(locale, "%.1fK", count / 1_000.0)
            else -> count.toString()
        }
    }

    fun formatShortNumber(number: Int): String {
        if (number < 1000) return number.toString()
        val locale = locale
        return when {
            number < 1_000_000 -> {
                val k = number / 1000.0
                if (k == k.toLong().toDouble()) String.format(locale, "%dK", k.toLong())
                else String.format(locale, "%.1fK", k)
            }
            else -> {
                val m = number / 1_000_000.0
                if (m == m.toLong().toDouble()) String.format(locale, "%dM", m.toLong())
                else String.format(locale, "%.1fM", m)
            }
        }
    }

    fun formatDateChat(timeMillis: Long): String {
        val date = Date(timeMillis)
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { time = date }

        return when {
            isSameDay(now, then) -> formatterDay.format(date)
            isSameWeek(now, then) -> formatterWeek.format(date)
            now.get(Calendar.YEAR) == then.get(Calendar.YEAR) -> formatterMonth.format(date)
            else -> formatterYear.format(date)
        }
    }

    fun formatTime(timeMillis: Long): String = formatterDay.format(Date(timeMillis))

    fun formatFullDate(timeMillis: Long): String = formatterFullDate.format(Date(timeMillis))

    fun formatScheduleDate(timeMillis: Long): String {
        val date = Date(timeMillis)
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { time = date }
        val dateStr = if (isSameDay(now, then)) "Today"
        else if (isTomorrow(now, then)) "Tomorrow"
        else formatterScheduleDay.format(date)
        return "$dateStr at ${formatterDay.format(date)}"
    }

    fun isRTL(): Boolean {
        val lang = locale.language
        return lang == "ar" || lang == "fa" || lang == "he" || lang == "iw" ||
                lang.startsWith("ar_") || lang.startsWith("fa_") || lang.startsWith("he_")
    }

    private fun isSameDay(a: Calendar, b: Calendar): Boolean =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
                a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    private fun isSameWeek(a: Calendar, b: Calendar): Boolean {
        if (a.get(Calendar.YEAR) != b.get(Calendar.YEAR)) return false
        return a.get(Calendar.WEEK_OF_YEAR) == b.get(Calendar.WEEK_OF_YEAR)
    }

    private fun isTomorrow(now: Calendar, then: Calendar): Boolean {
        val tomorrow = now.clone() as Calendar
        tomorrow.add(Calendar.DAY_OF_YEAR, 1)
        return isSameDay(tomorrow, then)
    }

    private const val QUANTITY_OTHER = 0
    private const val QUANTITY_ZERO = 1
    private const val QUANTITY_ONE = 2
    private const val QUANTITY_TWO = 3
    private const val QUANTITY_FEW = 4
    private const val QUANTITY_MANY = 5
}
