package com.mezon.mobile.home.profile

internal const val CUSTOM_STATUS_DURATION_TODAY = -1
internal const val CUSTOM_STATUS_DURATION_DONT_CLEAR = 0

internal fun customStatusDurationSelection(timeReset: Int?, noClear: Boolean?): Int {
    if (noClear == true) return CUSTOM_STATUS_DURATION_DONT_CLEAR
    return when (timeReset) {
        240, 60, 30 -> timeReset
        else -> CUSTOM_STATUS_DURATION_TODAY
    }
}
