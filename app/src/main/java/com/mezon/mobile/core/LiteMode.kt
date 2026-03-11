package com.mezon.mobile.core

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.PowerManager

object LiteMode {

    const val FLAG_ANIMATED_EMOJI_KEYBOARD = 1
    const val FLAG_ANIMATED_EMOJI_REACTIONS = 2
    const val FLAG_ANIMATED_EMOJI_CHAT = 4
    const val FLAGS_ANIMATED_EMOJI = FLAG_ANIMATED_EMOJI_KEYBOARD or FLAG_ANIMATED_EMOJI_REACTIONS or FLAG_ANIMATED_EMOJI_CHAT

    const val FLAG_CHAT_BACKGROUND = 8
    const val FLAG_CHAT_FORUM_TWOCOLUMN = 16
    const val FLAG_CHAT_SPOILER = 32
    const val FLAG_CHAT_BLUR = 64
    const val FLAG_CHAT_SCALE = 128
    const val FLAGS_CHAT = FLAG_CHAT_BACKGROUND or FLAG_CHAT_FORUM_TWOCOLUMN or FLAG_CHAT_SPOILER or FLAG_CHAT_BLUR or FLAG_CHAT_SCALE

    const val FLAG_CALLS_ANIMATIONS = 256
    const val FLAG_AUTOPLAY_VIDEOS = 512
    const val FLAG_AUTOPLAY_GIFS = 1024
    const val FLAGS_AUTOPLAY = FLAG_AUTOPLAY_VIDEOS or FLAG_AUTOPLAY_GIFS

    const val FLAG_STICKERS_PANEL = 2048
    const val FLAG_STICKERS_CHAT = 4096
    const val FLAGS_STICKERS = FLAG_STICKERS_PANEL or FLAG_STICKERS_CHAT

    const val FLAG_FRAGMENT_TRANSITIONS = 8192
    const val FLAG_TAB_ANIMATIONS = 16384

    const val PRESET_LOW = FLAGS_ANIMATED_EMOJI or FLAGS_CHAT or FLAGS_AUTOPLAY or FLAGS_STICKERS or FLAG_CALLS_ANIMATIONS or FLAG_FRAGMENT_TRANSITIONS or FLAG_TAB_ANIMATIONS
    const val PRESET_MEDIUM = FLAGS_ANIMATED_EMOJI or FLAG_CHAT_BLUR or FLAG_CHAT_SCALE
    const val PRESET_HIGH = 0
    const val PRESET_POWER_SAVER = PRESET_LOW

    @Volatile
    private var value = 0
    private var prefs: SharedPreferences? = null
    private var powerSaverEnabled = false
    private var powerManager: PowerManager? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("lite_mode_prefs", Context.MODE_PRIVATE)
        val perfClass = SharedConfig.getDevicePerformanceClass()
        val defaultValue = when (perfClass) {
            SharedConfig.PERFORMANCE_CLASS_LOW -> PRESET_LOW
            SharedConfig.PERFORMANCE_CLASS_AVERAGE -> PRESET_MEDIUM
            else -> PRESET_HIGH
        }
        value = prefs?.getInt("lite_mode_value", defaultValue) ?: defaultValue
        powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    }

    fun getValue(): Int = value

    fun setValue(newValue: Int) {
        value = newValue
        prefs?.edit()?.putInt("lite_mode_value", value)?.apply()
    }

    fun isEnabled(flag: Int): Boolean {
        if (isPowerSaverApplied()) return true
        return (value and flag) != 0
    }

    fun isEnabledAny(vararg flags: Int): Boolean {
        val combined = flags.fold(0) { acc, f -> acc or f }
        return isEnabled(combined)
    }

    fun setAllFlags(flags: Int, enabled: Boolean) {
        value = if (enabled) value or flags else value and flags.inv()
        prefs?.edit()?.putInt("lite_mode_value", value)?.apply()
    }

    fun setFlag(flag: Int, enabled: Boolean) {
        value = if (enabled) value or flag else value and flag.inv()
        prefs?.edit()?.putInt("lite_mode_value", value)?.apply()
    }

    fun isPowerSaverApplied(): Boolean {
        if (!powerSaverEnabled) return false
        return powerManager?.isPowerSaveMode == true
    }

    fun setPowerSaverEnabled(enabled: Boolean) {
        powerSaverEnabled = enabled
        prefs?.edit()?.putBoolean("power_saver_enabled", enabled)?.apply()
    }

    fun isPowerSaverEnabled(): Boolean = powerSaverEnabled

    fun loadPowerSaverPreference() {
        powerSaverEnabled = prefs?.getBoolean("power_saver_enabled", false) ?: false
    }
}
