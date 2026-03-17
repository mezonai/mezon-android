package com.mezon.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.ActionMode
import android.view.Menu
import android.view.ViewGroup
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.mezon.mobile.auth.LoginFragment
import com.mezon.mobile.auth.OTPVerificationFragment
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.ActionBarLayout
import com.mezon.mobile.core.SharedConfig
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.DrawerLayoutContainer
import com.mezon.mobile.core.INavigationLayout
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.ChatController
import com.mezon.mobile.home.ConnectionController
import com.mezon.mobile.home.DialogsController
import com.mezon.mobile.home.MainTabsActivity
import com.mezon.mobile.home.chat.ChatFragment
import com.mezon.mobile.home.notifications.NotificationStore
import com.mezon.mobile.notification.FcmRepository
import com.mezon.mobile.notification.NotificationHelper
import com.mezon.mobile.session.AutoNightConfig
import com.mezon.mobile.session.LocaleManager
import com.mezon.mobile.session.SessionManager
import com.mezon.mobile.session.ThemeManager
import com.mezon.mobile.ui.theme.ThemeMode
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity(),
    INavigationLayout.INavigationLayoutDelegate,
    NotificationCenter.NotificationCenterDelegate {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_NOTIFICATION_PERMISSION = 1001

        var instance: MainActivity? = null
            private set

        var isActive = false
            private set

        var isResumed = false
            private set

        var applicationPaused = true
            private set

        private val mainFragmentsStack = ArrayList<BaseFragment>()

        fun getLastFragment(): BaseFragment? {
            return instance?.actionBarLayout?.getLastFragment()
        }
    }

    @Inject lateinit var sessionManager: SessionManager
    @Inject lateinit var localeManager: LocaleManager
    @Inject lateinit var themeManager: ThemeManager
    @Inject lateinit var themeColors: ThemeColors
    @Inject lateinit var notificationCenter: NotificationCenter
    @Inject lateinit var fcmRepository: FcmRepository
    @Suppress("unused") @Inject lateinit var connectionController: ConnectionController
    @Suppress("unused") @Inject lateinit var chatController: ChatController
    @Suppress("unused") @Inject lateinit var dialogsController: DialogsController
    @Suppress("unused") @Inject lateinit var notificationStore: NotificationStore

    lateinit var actionBarLayout: ActionBarLayout
    lateinit var drawerLayoutContainer: DrawerLayoutContainer
        private set

    private var currentAccount = 0
    private var currentConnectionState = 0
    lateinit var autoNightConfig: AutoNightConfig
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        instance = this
        isActive = true
        super.onCreate(savedInstanceState)

        AndroidUtilities.init(this)
        SharedConfig.init(this)
        autoNightConfig = AutoNightConfig(this)
        themeManager.initAutoNight(autoNightConfig)
        runBlocking { localeManager.restoreOnColdStart() }
        lastLocaleTag = resources.configuration.locales[0]?.toLanguageTag()
        val themeMode = runBlocking { themeManager.themeMode.first() }
        themeColors.setTheme(themeMode, isSystemDarkMode())

        actionBarLayout = ActionBarLayout(this, this)
        actionBarLayout.setDependencies(themeColors, notificationCenter)
        actionBarLayout.setFragmentStack(mainFragmentsStack)
        actionBarLayout.setDelegate(this)

        drawerLayoutContainer = DrawerLayoutContainer(this).apply {
            parentActionBarLayout = actionBarLayout
            setBehindKeyboardColor(themeColors.surface)
        }
        actionBarLayout.setDrawerLayoutContainer(drawerLayoutContainer)

        drawerLayoutContainer.addView(
            actionBarLayout,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        setContentView(drawerLayoutContainer)
        window.setSoftInputMode(
            if (Build.VERSION.SDK_INT >= 30) WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )

        applySystemBarColors(themeMode)

        if (mainFragmentsStack.isEmpty()) {
            val stored = runBlocking { sessionManager.sessionFlow.first() }
            if (stored != null) {
                showHome()
                fcmRepository.getAndRegisterToken()
            } else {
                showLogin()
            }
            handleNotificationIntent(intent)
        } else {
            actionBarLayout.showLastFragment()
            rewireTopFragmentCallbacks()
        }
        requestNotificationPermission()

        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.themeChanged)
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.languageChanged)
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.autoNightModeChanged)
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.needCheckSystemBarColors)
        autoNightConfig.startSensorListening()
        notificationCenter.addObserver(this, NotificationCenter.connectionStateChanged)
        notificationCenter.addObserver(this, NotificationCenter.sessionExpired)
        notificationCenter.addObserver(this, NotificationCenter.appDidLogout)
    }

    override fun onResume() {
        super.onResume()
        isResumed = true
        applicationPaused = false
        actionBarLayout.onResume()
    }

    override fun onPause() {
        super.onPause()
        isResumed = false
        applicationPaused = true
        actionBarLayout.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        actionBarLayout.getLastFragment()?.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        super.onDestroy()
        actionBarLayout.unregisterBackCallback()

        autoNightConfig.stopSensorListening()
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.themeChanged)
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.languageChanged)
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.autoNightModeChanged)
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.needCheckSystemBarColors)
        notificationCenter.removeObserver(this, NotificationCenter.connectionStateChanged)
        notificationCenter.removeObserver(this, NotificationCenter.sessionExpired)
        notificationCenter.removeObserver(this, NotificationCenter.appDidLogout)

        isActive = false
        if (instance === this) instance = null
    }

    override fun onLowMemory() {
        super.onLowMemory()
        actionBarLayout.onLowMemory()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        actionBarLayout.onUserLeaveHint()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotificationIntent(intent)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!actionBarLayout.onBackPressedInternal()) {
            super.onBackPressed()
        }
    }

    override fun onActionModeStarted(mode: ActionMode) {
        super.onActionModeStarted(mode)
        mode.menu?.let { actionBarLayout.extendActionMode(it) }
        actionBarLayout.onActionModeStarted(mode)
    }

    override fun onActionModeFinished(mode: ActionMode) {
        super.onActionModeFinished(mode)
        actionBarLayout.onActionModeFinished(mode)
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        actionBarLayout.onActivityResult(requestCode, resultCode, data)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        actionBarLayout.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    // ── NotificationCenterDelegate ──────────────────────────────────────────

    override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
        when (id) {
            NotificationCenter.themeChanged -> {
                val mode = args.firstOrNull() as? ThemeMode
                    ?: runBlocking { themeManager.themeMode.first() }
                themeColors.setTheme(mode, isSystemDarkMode())
                drawerLayoutContainer.setBehindKeyboardColor(themeColors.surface)
                applySystemBarColors(mode)
                rebuildAllFragments(true)
            }
            NotificationCenter.languageChanged -> {
            }
            NotificationCenter.autoNightModeChanged -> {
                val userMode = themeColors.currentMode
                if (userMode == ThemeMode.SYSTEM) {
                    themeColors.setTheme(userMode, isSystemDarkMode())
                    drawerLayoutContainer.setBehindKeyboardColor(themeColors.surface)
                    applySystemBarColors(userMode)
                    rebuildAllFragments(true)
                }
            }
            NotificationCenter.needCheckSystemBarColors -> {
                checkSystemBarColors()
            }
            NotificationCenter.connectionStateChanged -> {
                // handled by ConnectionController UI updates
            }
            NotificationCenter.sessionExpired, NotificationCenter.appDidLogout -> {
                switchToLogin()
            }
        }
    }

    // ── INavigationLayoutDelegate ───────────────────────────────────────────

    override fun needPresentFragment(
        layout: INavigationLayout,
        params: INavigationLayout.NavigationParams
    ): Boolean {
        checkSystemBarColors()
        return true
    }

    override fun needAddFragmentToStack(
        fragment: BaseFragment,
        layout: INavigationLayout
    ): Boolean = true

    override fun needCloseLastFragment(layout: INavigationLayout): Boolean {
        if (layout.getFragmentStack().size <= 1) {
            finish()
            return false
        }
        return true
    }

    override fun onRebuildAllFragments(layout: INavigationLayout, last: Boolean) {}

    override fun onFragmentStackChanged(layout: INavigationLayout) {
        checkSystemBarColors()
    }

    // ── Navigation ──────────────────────────────────────────────────────────

    fun rebuildAllFragments(last: Boolean) {
        actionBarLayout.rebuildAllFragmentViews(last, last)
    }

    private fun showLogin() {
        mainFragmentsStack.clear()
        val fragment = LoginFragment().apply {
            onLoginSuccess = { showHome() }
        }
        actionBarLayout.addFragmentToStack(fragment)
        actionBarLayout.showLastFragment()
    }

    private fun showHome() {
        val mainTabsActivity = MainTabsActivity().apply {
            onLogout = { switchToLogin() }
            onOpenChat = { channelId, channelName, clanId, channelType ->
                openChat(channelId, channelName, clanId, channelType)
            }
        }
        mainFragmentsStack.clear()
        actionBarLayout.addFragmentToStack(mainTabsActivity)
        actionBarLayout.showLastFragment()
    }

    private fun switchToLogin() {
        mainFragmentsStack.clear()
        showLogin()
    }

    fun switchToAccount(account: Int, removeAll: Boolean) {
        if (account == currentAccount) return
        currentAccount = account
        if (removeAll) {
            actionBarLayout.removeAllFragments()
        }
        showHome()
    }

    fun openChat(
        channelId: Long,
        channelName: String,
        clanId: Long,
        channelType: Int,
        messageId: Long = 0L,
        noAnimation: Boolean = false
    ) {
        val lastFragment = actionBarLayout.getLastFragment()
        if (lastFragment is ChatFragment && lastFragment.getChannelId() == channelId && messageId == 0L) {
            return
        }
        val fragment = ChatFragment.newInstance(channelId, channelName, clanId, channelType, messageId)
        val params = INavigationLayout.NavigationParams(fragment).setNoAnimation(noAnimation)
        actionBarLayout.presentFragment(params)
    }

    private fun rewireTopFragmentCallbacks() {
        when (val top = mainFragmentsStack.lastOrNull()) {
            is LoginFragment -> top.onLoginSuccess = { showHome() }
            is OTPVerificationFragment -> top.onVerifySuccess = { showHome() }
            is MainTabsActivity -> {
                top.onLogout = { switchToLogin() }
                top.onOpenChat = { channelId, channelName, clanId, channelType ->
                    openChat(channelId, channelName, clanId, channelType)
                }
            }
        }
    }

    private fun handleNotificationIntent(intent: Intent?) {
        intent ?: return
        val action = intent.action
        val isFromNotification = action != null && action.startsWith(NotificationHelper.ACTION_OPEN_CHAT)
        val extras = intent.extras ?: return

        val channelId = extras.getLong(NotificationHelper.EXTRA_CHANNEL_ID, 0L)
        val dmId = extras.getLong(NotificationHelper.EXTRA_DM_ID, 0L)
        val messageId = extras.getLong(NotificationHelper.EXTRA_MESSAGE_ID, 0L)

        if (channelId != 0L) {
            val channelName = extras.getString(NotificationHelper.EXTRA_CHANNEL_NAME, "") ?: ""
            val clanId = extras.getLong(NotificationHelper.EXTRA_CLAN_ID, 0L)
            val channelType = extras.getInt(NotificationHelper.EXTRA_CHANNEL_TYPE, 0)
            val stored = runBlocking { sessionManager.sessionFlow.first() }
            if (stored != null) {
                openChat(channelId, channelName, clanId, channelType, messageId, noAnimation = isFromNotification)
            }
            intent.removeExtra(NotificationHelper.EXTRA_CHANNEL_ID)
        } else if (dmId != 0L) {
            val stored = runBlocking { sessionManager.sessionFlow.first() }
            if (stored != null) {
                openChat(dmId, "", 0L, 3, messageId, noAnimation = isFromNotification)
            }
            intent.removeExtra(NotificationHelper.EXTRA_DM_ID)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATION_PERMISSION
                )
            }
        }
    }

    private var lastLocaleTag: String? = null

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        AndroidUtilities.checkDisplaySize(this, newConfig)
        actionBarLayout.onConfigurationChanged(newConfig)

        val newLocale = newConfig.locales[0]?.toLanguageTag()
        if (lastLocaleTag != null && newLocale != lastLocaleTag) {
            lastLocaleTag = newLocale
            rebuildAllFragments(true)
            return
        }
        lastLocaleTag = newLocale

        if (themeColors.currentMode == ThemeMode.SYSTEM) {
            val dark = isSystemDarkMode()
            if ((dark && themeColors.resolvedMode != ThemeMode.DARK) ||
                (!dark && themeColors.resolvedMode != ThemeMode.LIGHT)) {
                themeColors.setTheme(ThemeMode.SYSTEM, dark)
                drawerLayoutContainer.setBehindKeyboardColor(themeColors.surface)
                applySystemBarColors(ThemeMode.SYSTEM)
                rebuildAllFragments(true)
            }
        }
    }

    private fun isSystemDarkMode(): Boolean =
        resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

    fun applySystemBarColors(mode: ThemeMode) {
        checkSystemBarColors()
    }

    fun checkSystemBarColors(
        useCurrentFragment: Boolean = true,
        checkStatusBar: Boolean = true,
        checkNavigationBar: Boolean = true
    ) {
        val lastFragment = if (useCurrentFragment) actionBarLayout.getLastFragment() else null

        if (checkStatusBar) {
            val statusBarColor = lastFragment?.getStatusBarColor() ?: themeColors.surface
            window.statusBarColor = statusBarColor

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val isLight = lastFragment?.isLightStatusBar()
                    ?: (themeColors.resolvedMode == ThemeMode.LIGHT)
                val flags = window.decorView.systemUiVisibility
                window.decorView.systemUiVisibility = if (isLight) {
                    flags or android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                } else {
                    flags and android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                }
            }
        }

        if (checkNavigationBar) {
            val navBarColor = lastFragment?.getNavigationBarColor() ?: themeColors.surface
            window.navigationBarColor = navBarColor
        }
    }
}
