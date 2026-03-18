package com.mezon.mobile.session

import android.content.res.Configuration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.StartupCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.Locale
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

    var currentLocale: Locale = Locale.getDefault()
        private set

    val currentLanguage: Flow<String> = dataStore.data.map { prefs ->
        prefs[LANGUAGE_KEY] ?: ENGLISH
    }

    suspend fun setLanguage(languageTag: String) {
        StartupCache.locale = languageTag
        dataStore.edit { prefs -> prefs[LANGUAGE_KEY] = languageTag }
        withContext(Dispatchers.Main) {
            applyLocale(languageTag)
            NotificationCenter.getGlobalInstance()
                .postNotificationName(NotificationCenter.languageChanged)
        }
    }

    fun restoreFromCache() {
        applyLocale(StartupCache.locale)
    }

    suspend fun restoreOnColdStart() {
        val saved = currentLanguage.first()
        applyLocale(saved)
    }

    @Suppress("DEPRECATION")
    fun applyLocale(languageTag: String) {
        val locale = Locale.forLanguageTag(languageTag)
        currentLocale = locale
        Locale.setDefault(locale)

        val context = AndroidUtilities.applicationContext ?: return
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
    }
}
