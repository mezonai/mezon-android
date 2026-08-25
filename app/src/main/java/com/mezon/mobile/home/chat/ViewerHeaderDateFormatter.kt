package com.mezon.mobile.home.chat

import android.content.Context
import com.mezon.mobile.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

internal fun formatViewerHeaderDate(context: Context, timestampSeconds: Long): String? {
    if (timestampSeconds <= 0) return null

    val timestampMillis = timestampSeconds * 1000L
    val messageCalendar = Calendar.getInstance().apply { timeInMillis = timestampMillis }
    val currentCalendar = Calendar.getInstance().apply { timeInMillis = System.currentTimeMillis() }
    val messageYear = messageCalendar.get(Calendar.YEAR)
    val messageDay = messageCalendar.get(Calendar.DAY_OF_YEAR)
    val currentYear = currentCalendar.get(Calendar.YEAR)
    val currentDay = currentCalendar.get(Calendar.DAY_OF_YEAR)
    val isToday = messageYear == currentYear && messageDay == currentDay
    val isYesterday =
        (messageYear == currentYear && messageDay == currentDay - 1) ||
            (currentDay == 1 &&
                messageYear == currentYear - 1 &&
                messageDay == messageCalendar.getActualMaximum(Calendar.DAY_OF_YEAR))
    val locale = Locale.getDefault()
    val time = SimpleDateFormat("h:mm a", locale).format(Date(timestampMillis))

    return when {
        isToday -> "${context.getString(R.string.common_today_at)} $time"
        isYesterday -> "${context.getString(R.string.common_yesterday_at)} $time"
        else -> SimpleDateFormat("MMM d, h:mm a", locale).format(Date(timestampMillis))
    }
}
