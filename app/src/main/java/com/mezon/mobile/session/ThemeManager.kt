package com.mezon.mobile.session

import com.mezon.mobile.core.StartupCache
import com.mezon.mobile.ui.theme.ThemeMode
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ThemeManager @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        private val THEME_KEY = stringPreferencesKey("app_theme")
    }

    var autoNightConfig: AutoNightConfig? = null
        private set

    val themeMode: Flow<ThemeMode> = dataStore.data.map { prefs ->
        when (prefs[THEME_KEY]) {
            "light" -> ThemeMode.LIGHT
            "dark" -> ThemeMode.DARK
            "abyss" -> ThemeMode.ABYSS
            "system" -> ThemeMode.SYSTEM
            else -> ThemeMode.DARK
        }
    }

    suspend fun setTheme(mode: ThemeMode) {
        StartupCache.themeMode = mode
        dataStore.edit { prefs ->
            prefs[THEME_KEY] = when (mode) {
                ThemeMode.LIGHT -> "light"
                ThemeMode.DARK -> "dark"
                ThemeMode.ABYSS -> "abyss"
                ThemeMode.SYSTEM -> "system"
            }
        }
    }

    fun initAutoNight(config: AutoNightConfig) {
        autoNightConfig = config
    }

    fun resolveEffectiveMode(userMode: ThemeMode, systemIsDark: Boolean): ThemeMode {
        if (userMode != ThemeMode.SYSTEM) return userMode

        val config = autoNightConfig ?: return if (systemIsDark) ThemeMode.DARK else ThemeMode.LIGHT

        return if (config.shouldUseDarkTheme(systemIsDark)) {
            config.nightThemeMode
        } else {
            ThemeMode.LIGHT
        }
    }
}
