package com.mezon.mobile.core

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import java.io.RandomAccessFile
import java.util.Locale

object SharedConfig {

    private const val PREFS_NAME = "mezon_shared_config"
    private const val TAG = "SharedConfig"

    const val PERFORMANCE_CLASS_LOW = 0
    const val PERFORMANCE_CLASS_AVERAGE = 1
    const val PERFORMANCE_CLASS_HIGH = 2

    @Volatile var fontSize = 16
        private set
    @Volatile var bubbleRadius = 17
        private set
    @Volatile private var animationsEnabledCached: Boolean? = null
    @Volatile private var devicePerformanceClass = -1
    @Volatile private var overrideDevicePerformanceClass = -1

    @JvmField var drawActionBarShadow = true
    @JvmField var noStatusBar = false
    @JvmField var shadowsInSections = true
    @JvmField var useSystemBoldFont = false
    @JvmField var useThreeLinesLayout = false
    @JvmField var archiveHidden = false
    @JvmField var forceDisableTabletMode = false
    @JvmField var mediaColumnsCount = 3
    @JvmField var chatBubbles = true

    private var prefs: SharedPreferences? = null

    private val LOW_SOC = intArrayOf(
        -1775228513, // EXYNOS 850
        802464304,   // EXYNOS 7872
        802464333,   // EXYNOS 7880
        802464302,   // EXYNOS 7870
        2067362118,  // MSM8953
        2067362060,  // MSM8937
        2067362084,  // MSM8940
        2067362241,  // MSM8992
        2067362117,  // MSM8952
        2067361998   // MSM8917
    )

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        fontSize = prefs?.getInt("font_size", 16) ?: 16
        bubbleRadius = prefs?.getInt("bubble_radius", 17) ?: 17
        overrideDevicePerformanceClass = prefs?.getInt("overrideDevicePerformanceClass", -1) ?: -1
        devicePerformanceClass = prefs?.getInt("devicePerformanceClass", -1) ?: -1
        drawActionBarShadow = prefs?.getBoolean("drawActionBarShadow", true) ?: true
        shadowsInSections = prefs?.getBoolean("shadowsInSections", true) ?: true
        useSystemBoldFont = prefs?.getBoolean("useSystemBoldFont", false) ?: false
        useThreeLinesLayout = prefs?.getBoolean("useThreeLinesLayout", false) ?: false
        forceDisableTabletMode = prefs?.getBoolean("forceDisableTabletMode", false) ?: false
        mediaColumnsCount = prefs?.getInt("mediaColumnsCount", 3) ?: 3

        if (devicePerformanceClass == -1) {
            devicePerformanceClass = measureDevicePerformanceClass(context)
            prefs?.edit()?.putInt("devicePerformanceClass", devicePerformanceClass)?.apply()
        }

        liteModeValue = prefs?.getInt("lite_mode", LITE_FLAGS_ALL) ?: LITE_FLAGS_ALL

        animationsEnabledCached = prefs?.getBoolean("view_animations", true) ?: true
    }

    fun animationsEnabled(): Boolean {
        return animationsEnabledCached ?: true
    }

    fun setAnimationsEnabled(enabled: Boolean) {
        animationsEnabledCached = enabled
        prefs?.edit()?.putBoolean("view_animations", enabled)?.apply()
    }

    fun getDevicePerformanceClass(): Int {
        if (overrideDevicePerformanceClass != -1) return overrideDevicePerformanceClass
        return devicePerformanceClass
    }

    fun deviceIsLow(): Boolean = getDevicePerformanceClass() == PERFORMANCE_CLASS_LOW
    fun deviceIsAverage(): Boolean = getDevicePerformanceClass() >= PERFORMANCE_CLASS_AVERAGE
    fun deviceIsAboveAverage(): Boolean = getDevicePerformanceClass() >= PERFORMANCE_CLASS_AVERAGE
    fun deviceIsHigh(): Boolean = getDevicePerformanceClass() >= PERFORMANCE_CLASS_HIGH

    fun getPreferences(): SharedPreferences? = prefs

    fun performanceClassName(perfClass: Int): String = when (perfClass) {
        PERFORMANCE_CLASS_LOW -> "LOW"
        PERFORMANCE_CLASS_AVERAGE -> "AVERAGE"
        PERFORMANCE_CLASS_HIGH -> "HIGH"
        else -> "UNKNOWN"
    }

    fun toggleForceDisableTabletMode() {
        forceDisableTabletMode = !forceDisableTabletMode
        prefs?.edit()?.putBoolean("forceDisableTabletMode", forceDisableTabletMode)?.apply()
    }

    fun toggleUseThreeLinesLayout() {
        useThreeLinesLayout = !useThreeLinesLayout
        prefs?.edit()?.putBoolean("useThreeLinesLayout", useThreeLinesLayout)?.apply()
    }

    fun setMediaColumnsCount(count: Int) {
        mediaColumnsCount = count
        prefs?.edit()?.putInt("mediaColumnsCount", count)?.apply()
    }

    fun overrideDevicePerformanceClass(performanceClass: Int) {
        overrideDevicePerformanceClass = performanceClass
        prefs?.edit()?.putInt("overrideDevicePerformanceClass", performanceClass)?.apply()
    }

    private fun measureDevicePerformanceClass(context: Context): Int {
        val androidVersion = Build.VERSION.SDK_INT
        val cpuCount = Runtime.getRuntime().availableProcessors()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryClass = am.memoryClass

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && Build.SOC_MODEL != null) {
            val hash = Build.SOC_MODEL.uppercase(Locale.ENGLISH).hashCode()
            if (LOW_SOC.contains(hash)) return PERFORMANCE_CLASS_LOW
        }

        var totalCpuFreq = 0
        var freqResolved = 0
        for (i in 0 until cpuCount) {
            try {
                val reader = RandomAccessFile(
                    String.format(Locale.ENGLISH, "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", i), "r"
                )
                val line = reader.readLine()
                reader.close()
                if (line != null) {
                    totalCpuFreq += (line.trim().toLongOrNull() ?: 0L).toInt() / 1000
                    freqResolved++
                }
            } catch (_: Throwable) {}
        }
        val maxCpuFreq = if (freqResolved == 0) -1
            else kotlin.math.ceil(totalCpuFreq / freqResolved.toFloat()).toInt()

        var ram = -1L
        try {
            val memInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            ram = memInfo.totalMem
        } catch (_: Exception) {}

        val perfClass = when {
            androidVersion < 21 ||
            cpuCount <= 2 ||
            memoryClass <= 100 ||
            cpuCount <= 4 && maxCpuFreq != -1 && maxCpuFreq <= 1250 ||
            cpuCount <= 4 && maxCpuFreq <= 1600 && memoryClass <= 128 && androidVersion <= 21 ||
            cpuCount <= 4 && maxCpuFreq <= 1300 && memoryClass <= 128 && androidVersion <= 24 ||
            ram != -1L && ram < 2L * 1024L * 1024L * 1024L
                -> PERFORMANCE_CLASS_LOW

            cpuCount < 8 ||
            memoryClass <= 160 ||
            maxCpuFreq != -1 && maxCpuFreq <= 2055 ||
            maxCpuFreq == -1 && cpuCount == 8 && androidVersion <= 23
                -> PERFORMANCE_CLASS_AVERAGE

            else -> PERFORMANCE_CLASS_HIGH
        }

        Log.d(TAG, "device perf=$perfClass (cpu=$cpuCount freq=$maxCpuFreq mem=$memoryClass api=$androidVersion ram=$ram)")
        return perfClass
    }

    const val LITE_FLAG_ANIMATED_EMOJI = 1
    const val LITE_FLAG_CHAT_ANIMATIONS = 2
    const val LITE_FLAG_AUTOPLAY_VIDEOS = 4
    const val LITE_FLAG_AUTOPLAY_GIFS = 8
    const val LITE_FLAG_SMOOTH_KEYBOARD = 16
    const val LITE_FLAG_CALLS_ANIMATIONS = 32
    const val LITE_FLAG_FRAGMENT_TRANSITIONS = 64
    const val LITE_FLAG_STICKER_ANIMATIONS = 128
    const val LITE_FLAG_TAB_ANIMATIONS = 256
    const val LITE_FLAG_BLUR = 512

    const val LITE_FLAGS_ALL = LITE_FLAG_ANIMATED_EMOJI or LITE_FLAG_CHAT_ANIMATIONS or
            LITE_FLAG_AUTOPLAY_VIDEOS or LITE_FLAG_AUTOPLAY_GIFS or LITE_FLAG_SMOOTH_KEYBOARD or
            LITE_FLAG_CALLS_ANIMATIONS or LITE_FLAG_FRAGMENT_TRANSITIONS or
            LITE_FLAG_STICKER_ANIMATIONS or LITE_FLAG_TAB_ANIMATIONS or LITE_FLAG_BLUR

    @Volatile private var liteModeValue = LITE_FLAGS_ALL

    fun getLiteMode(): Int = liteModeValue

    fun setLiteMode(value: Int) {
        liteModeValue = value
        prefs?.edit()?.putInt("lite_mode", value)?.apply()
    }

    fun isLiteModeEnabled(flag: Int): Boolean {
        if (getDevicePerformanceClass() == PERFORMANCE_CLASS_LOW && !animationsEnabled()) return false
        return (liteModeValue and flag) != 0
    }

    fun setLiteModeFlag(flag: Int, enabled: Boolean) {
        liteModeValue = if (enabled) liteModeValue or flag else liteModeValue and flag.inv()
        prefs?.edit()?.putInt("lite_mode", liteModeValue)?.apply()
    }

    fun setFontSize(size: Int) {
        fontSize = size.coerceIn(12, 30)
        prefs?.edit()?.putInt("font_size", fontSize)?.apply()
        NotificationCenter.getGlobalInstance()
            .postNotificationName(NotificationCenter.themeChanged)
    }

    fun setBubbleRadius(radius: Int) {
        bubbleRadius = radius.coerceIn(0, 17)
        prefs?.edit()?.putInt("bubble_radius", bubbleRadius)?.apply()
        NotificationCenter.getGlobalInstance()
            .postNotificationName(NotificationCenter.themeChanged)
    }
}
