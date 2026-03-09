package com.mezon.mobile

import com.mezon.mobile.auth.LoginFragment
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.HomeFragment
import com.mezon.mobile.home.ConnectionController
import com.mezon.mobile.home.chat.ChatFragment
import com.mezon.mobile.notification.NotificationHelper
import com.mezon.mobile.session.LocaleManager
import com.mezon.mobile.session.SessionManager
import com.mezon.mobile.session.ThemeManager
import com.mezon.mobile.ui.theme.ThemeMode
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var sessionManager: SessionManager
    @Inject lateinit var localeManager: LocaleManager
    @Inject lateinit var themeManager: ThemeManager
    @Inject lateinit var themeColors: ThemeColors
    @Inject lateinit var notificationCenter: NotificationCenter
    @Suppress("unused")
    @Inject lateinit var connectionController: ConnectionController
    @Suppress("unused")
    @Inject lateinit var chatController: com.mezon.mobile.home.ChatController
    @Suppress("unused")
    @Inject lateinit var dialogsController: com.mezon.mobile.home.DialogsController
    @Suppress("unused")
    @Inject lateinit var notificationStore: com.mezon.mobile.home.notifications.NotificationStore

    private var themeObserver: NotificationCenter.Observer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        runBlocking { localeManager.restoreOnColdStart() }
        val themeMode = runBlocking { themeManager.themeMode.first() }
        themeColors.setTheme(themeMode)
        applyTheme(themeMode)

        setContentView(R.layout.activity_main)

        if (savedInstanceState == null) {
            val stored = runBlocking { sessionManager.sessionFlow.first() }
            if (stored != null) {
                showHome()
            } else {
                showLogin()
            }
            handleNotificationIntent(intent)
        } else {
            rewireRestoredFragments()
        }

        themeObserver = NotificationCenter.Observer { _, args ->
            val mode = args.firstOrNull() as? ThemeMode
                ?: runBlocking { themeManager.themeMode.first() }
            themeColors.setTheme(mode)
            applyTheme(mode)
        }
        notificationCenter.addObserver(NotificationCenter.themeChanged, themeObserver!!)
    }

    override fun onDestroy() {
        super.onDestroy()
        themeObserver?.let { notificationCenter.removeObserver(NotificationCenter.themeChanged, it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun rewireRestoredFragments() {
        (supportFragmentManager.findFragmentById(R.id.fragment_container) as? LoginFragment)
            ?.also { f ->
                Log.d("MainActivity", "rewire: found LoginFragment, setting onLoginSuccess")
                f.onLoginSuccess = {
                    Log.d("MainActivity", "onLoginSuccess received → showHome()")
                    showHome()
                }
            }
        (supportFragmentManager.findFragmentById(R.id.fragment_container) as? HomeFragment)
            ?.also { f ->
                Log.d("MainActivity", "rewire: found HomeFragment, setting callbacks")
                f.onLogout = { showLogin() }
                f.onOpenChat = { channelId, channelName, clanId, channelType ->
                    openChat(channelId, channelName, clanId, channelType)
                }
            }
    }

    private fun showLogin() {
        Log.d("MainActivity", "showLogin()")
        val fragment = LoginFragment().apply {
            onLoginSuccess = {
                Log.d("MainActivity", "onLoginSuccess received → showHome()")
                showHome()
            }
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    private fun showHome() {
        Log.d("MainActivity", "showHome() called")
        val fragment = HomeFragment().apply {
            onLogout = { showLogin() }
            onOpenChat = { channelId, channelName, clanId, channelType ->
                openChat(channelId, channelName, clanId, channelType)
            }
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    private fun openChat(channelId: Long, channelName: String, clanId: Long, channelType: Int) {
        val fragment = ChatFragment.newInstance(channelId, channelName, clanId, channelType)
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                android.R.anim.fade_in,
                android.R.anim.fade_out,
                android.R.anim.fade_in,
                android.R.anim.fade_out
            )
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun handleNotificationIntent(intent: Intent?) {
        intent ?: return
        val extras = intent.extras ?: return

        val channelId = extras.getLong(NotificationHelper.EXTRA_CHANNEL_ID, 0L)
        val dmId = extras.getLong(NotificationHelper.EXTRA_DM_ID, 0L)

        if (channelId != 0L) {
            val channelName = extras.getString(NotificationHelper.EXTRA_CHANNEL_NAME, "") ?: ""
            val clanId = extras.getLong(NotificationHelper.EXTRA_CLAN_ID, 0L)
            val channelType = extras.getInt(NotificationHelper.EXTRA_CHANNEL_TYPE, 0)
            val stored = runBlocking { sessionManager.sessionFlow.first() }
            if (stored != null) openChat(channelId, channelName, clanId, channelType)
            intent.removeExtra(NotificationHelper.EXTRA_CHANNEL_ID)
        } else if (dmId != 0L) {
            val stored = runBlocking { sessionManager.sessionFlow.first() }
            if (stored != null) openChat(dmId, "", 0L, 3)
            intent.removeExtra(NotificationHelper.EXTRA_DM_ID)
        }
    }

    private fun applyTheme(mode: ThemeMode) {
        val isDark = when (mode) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK, ThemeMode.ABYSS -> true
            ThemeMode.SYSTEM -> resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
        }

        window.statusBarColor = themeColors.surface
        window.navigationBarColor = themeColors.surface

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val flags = window.decorView.systemUiVisibility
            window.decorView.systemUiVisibility = if (isDark) {
                flags and android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            } else {
                flags or android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
        }
    }
}
