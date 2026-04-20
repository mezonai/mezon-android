package com.mezon.mobile

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mezon.mobile.core.StartupCache
import com.mezon.mobile.data.db.MezonDatabase
import com.mezon.mobile.session.SessionKeys
import dagger.hilt.android.HiltAndroidApp
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

    private val appStartScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        StartupCache.init(this)

        val prefs = runBlocking { dataStore.data.first() }

        StartupCache.seed(
            hasSession = prefs[SessionKeys.TOKEN] != null,
            themeMode = prefs[stringPreferencesKey("app_theme")] ?: "system",
            locale = prefs[stringPreferencesKey("app_language")] ?: "en"
        )

        if (StartupCache.hasSession) {
            appStartScope.launch { database.openHelper.writableDatabase }
        }

       // warmupWebView()
    }

    private fun warmupWebView() {
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching {
                val wv = WebView(applicationContext)
                wv.destroy()
            }
        }, WEBVIEW_WARMUP_DELAY_MS)
    }

    companion object {
        private const val WEBVIEW_WARMUP_DELAY_MS = 3000L
    }
}
