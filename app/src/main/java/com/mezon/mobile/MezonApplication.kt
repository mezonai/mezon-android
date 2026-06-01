package com.mezon.mobile

import android.app.Application
import android.content.res.Configuration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mezon.mobile.core.StartupCache
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.data.db.MezonDatabase
import com.mezon.mobile.session.SessionKeys
import com.mezon.mobile.home.messages.EmbedAnimationHttp
import com.mezon.mobile.util.SentryReporter
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@HiltAndroidApp
class MezonApplication : Application() {

    @Inject lateinit var dataStore: DataStore<Preferences>
    @Inject lateinit var database: MezonDatabase
    @Inject lateinit var themeColors: ThemeColors
    @Inject lateinit var sentryReporter: SentryReporter
    @Inject lateinit var okHttpClient: OkHttpClient

    private val appStartScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        EmbedAnimationHttp.install(okHttpClient)
        sentryReporter.init(this)
        StartupCache.init(this)

        val seedStartupCacheFromDataStore: suspend () -> Unit = {
            runCatching {
                val prefs = dataStore.data.first()
                StartupCache.seed(
                    hasSession = prefs[SessionKeys.TOKEN] != null,
                    themeMode = prefs[stringPreferencesKey("app_theme")] ?: "dark",
                    locale = prefs[stringPreferencesKey("app_language")] ?: "en"
                )
            }
            Unit
        }

        if (StartupCache.isSeeded) {
            appStartScope.launch { seedStartupCacheFromDataStore() }
        } else {
            runBlocking(Dispatchers.IO) { seedStartupCacheFromDataStore() }
        }

        val systemDark =
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        themeColors.setTheme(StartupCache.themeMode, systemDark)

        if (StartupCache.hasSession) {
            appStartScope.launch { database.openHelper.writableDatabase }
        }
    }
}
