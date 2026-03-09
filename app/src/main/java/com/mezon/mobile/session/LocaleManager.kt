package com.mezon.mobile.session

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocaleManager @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        private val LANGUAGE_KEY = stringPreferencesKey("app_language")
        const val ENGLISH = "en"
        const val VIETNAMESE = "vi"
        val SUPPORTED_LANGUAGES = listOf(ENGLISH, VIETNAMESE)
    }

    val currentLanguage: Flow<String> = dataStore.data.map { prefs ->
        prefs[LANGUAGE_KEY] ?: ENGLISH
    }

    suspend fun setLanguage(languageTag: String) {
        dataStore.edit { prefs -> prefs[LANGUAGE_KEY] = languageTag }
        val localeList = LocaleListCompat.forLanguageTags(languageTag)
        AppCompatDelegate.setApplicationLocales(localeList)
    }

    suspend fun restoreOnColdStart() {
        val saved = currentLanguage.first()
        val current = AppCompatDelegate.getApplicationLocales()
        if (current.isEmpty || current.toLanguageTags() != saved) {
            val localeList = LocaleListCompat.forLanguageTags(saved)
            AppCompatDelegate.setApplicationLocales(localeList)
        }
    }
}
