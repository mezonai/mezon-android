package com.mezon.mobile

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.view.Gravity
import android.view.View
import android.view.ActionMode
import android.view.Menu
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.mezon.mobile.auth.LoginFragment
import com.mezon.mobile.auth.OTPVerificationFragment
import com.mezon.mobile.auth.UpdateUsernameFragment
import com.mezon.mobile.core.ActionBarLayout
import com.mezon.mobile.core.AlertDialog
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.DrawerLayoutContainer
import com.mezon.mobile.core.INavigationLayout
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.SharedConfig
import com.mezon.mobile.core.StartupCache
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.deeplink.DeepLinkRouter
import com.mezon.mobile.home.ConnectionController
import com.mezon.mobile.home.DialogsController
import com.mezon.mobile.home.messages.MessageActivitiesController
import com.mezon.mobile.home.MainTabsActivity
import com.mezon.mobile.home.chat.ChatFragment
import com.mezon.mobile.home.chat.PhotoViewer
import com.mezon.mobile.home.call.CallController
import com.mezon.mobile.home.call.CallFragment
import com.mezon.mobile.home.call.CallManager
import com.mezon.mobile.home.call.CallInfo
import com.mezon.mobile.home.call.CallingOverlay
import com.mezon.mobile.home.call.IncomingCallActivity
import com.mezon.mobile.home.call.IncomingCallFcmHandler
import com.mezon.mobile.home.clans.ClanChannelEntity
import com.mezon.mobile.home.sharing.SharingFragment
import com.mezon.mobile.home.stream.StreamingRoomFragment
import com.mezon.mobile.home.voice.VoiceOverlayManager
import com.mezon.mobile.home.voice.VoiceRoomFragment
import com.mezon.mobile.network.CHANNEL_TYPE_CHANNEL
import com.mezon.mobile.network.CHANNEL_TYPE_DM
import com.mezon.mobile.network.CHANNEL_TYPE_GROUP
import com.mezon.mobile.network.CHANNEL_TYPE_THREAD
import com.mezon.mobile.network.NetworkMonitor
import com.mezon.mobile.notification.NotificationHelper
import com.mezon.mobile.session.AutoNightConfig
import com.mezon.mobile.session.LocaleManager
import com.mezon.mobile.session.SessionManager
import com.mezon.mobile.session.ThemeManager
import com.mezon.mobile.ui.theme.ThemeMode
import com.mezon.mobile.ui.OfflineNetworkBannerView
import com.mezon.mobile.update.AppUpdateGateManager
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import javax.inject.Inject
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class MainActivity : BasePermissionsActivity(),
    INavigationLayout.INavigationLayoutDelegate,
    NotificationCenter.NotificationCenterDelegate {

    private data class ChatRouteMeta(
        val channelType: Int,
        val isPrivate: Boolean,
        val isAgeRestricted: Boolean,
        val parentId: Long
    )

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_INCOMING_CALL_PERMISSIONS = 9041

        var instance: MainActivity? = null
            private set

        var isActive = false
            private set

        var isResumed = false
            private set

        var applicationPaused = true
            private set

        private var fullScreenIntentPromptShown = false

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
    @Inject lateinit var dialogsController: DialogsController
    @Inject lateinit var messageActivitiesController: MessageActivitiesController
    @Inject lateinit var connectionController: ConnectionController
    @Inject lateinit var notificationHelper: NotificationHelper
    @Inject lateinit var appUpdateGateManager: AppUpdateGateManager
    @Inject lateinit var incomingCallFcmHandler: IncomingCallFcmHandler
    @Inject lateinit var callController: CallController
    @Inject lateinit var callManager: CallManager
    @Inject lateinit var networkMonitor: NetworkMonitor
    @Inject lateinit var deepLinkRouter: DeepLinkRouter

    lateinit var actionBarLayout: ActionBarLayout
    lateinit var drawerLayoutContainer: DrawerLayoutContainer
        private set

    var voiceOverlayManager: VoiceOverlayManager? = null
        private set
    private var voiceRoomFragment: VoiceRoomFragment? = null

    var streamingOverlayManager: VoiceOverlayManager? = null
        private set
    private var streamingRoomFragment: StreamingRoomFragment? = null

    private var currentConnectionState = 0
    lateinit var autoNightConfig: AutoNightConfig
        private set

    private var isContentReady = false
    private val dismissSplashRunnable = Runnable { isContentReady = true }
    private var splashContentObserver: NotificationCenter.NotificationCenterDelegate? = null
    private var appUpdateGateRunnable: Runnable? = null
    private var callingOverlay: CallingOverlay? = null
    private var offlineNetworkBanner: OfflineNetworkBannerView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        instance = this
        isActive = true
        super.onCreate(savedInstanceState)

        AndroidUtilities.init(this)
        SharedConfig.init(this)
        autoNightConfig = AutoNightConfig(this)
        themeManager.initAutoNight(autoNightConfig)

        val themeMode = StartupCache.themeMode
        val hasSession = StartupCache.hasSession
        localeManager.restoreFromCache()

        applyLocaleToActivity()
        lastLocaleTag = resources.configuration.locales[0]?.toLanguageTag()
        themeColors.setTheme(themeMode, isSystemDarkMode())

        if (hasSession) {
            splashScreen.setKeepOnScreenCondition { !isContentReady }
            preInitControllers()
        } else {
            isContentReady = true
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)

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

        setupOfflineNetworkBanner()

        voiceOverlayManager = VoiceOverlayManager(drawerLayoutContainer, themeColors).also { manager ->
            manager.onExpandRequest = { expandVoiceRoom() }
        }
        streamingOverlayManager = VoiceOverlayManager(drawerLayoutContainer, themeColors).also { manager ->
            manager.onExpandRequest = { expandStreamingRoom() }
        }

        setContentView(drawerLayoutContainer)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackPressed()
            }
        })

        @Suppress("DEPRECATION")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        applySystemBarColors(themeMode)

        if (mainFragmentsStack.isEmpty()) {
            if (hasSession) {
                if (StartupCache.needsUsernameSetup) {
                    showUpdateUsernameGate()
                    setupSplashDismiss()
                } else {
                    showHome()
                    setupSplashDismiss()
                }
            } else {
                showLogin()
            }
        } else {
            actionBarLayout.showLastFragment()
            rewireTopFragmentCallbacks()
        }
        if (!handleShareIntent(intent)) {
            if (!handleDeepLinkIntent(intent)) {
                handleNotificationIntent(intent)
            }
        }
        flushPendingDeepLink()
        if (hasSession) {
            val run = Runnable {
                appUpdateGateManager.checkAndShowIfNeeded(
                    this@MainActivity,
                    BuildConfig.MEZON_GOOGLE_PLAY_URL
                )
            }
            appUpdateGateRunnable = run
            AndroidUtilities.runOnUIThread(run, 2000L)
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
        notificationCenter.addObserver(this, NotificationCenter.incomingCall)
        notificationCenter.addObserver(this, NotificationCenter.callEnded)
    }

    override fun onResume() {
        super.onResume()
        isResumed = true
        applicationPaused = false
        actionBarLayout.onResume()
        if (StartupCache.hasSession) {
            connectionController.handleAppForeground()
            maybePromptFullScreenIntentForIncomingCalls()
        }
        flushPendingDeepLink()
    }

    private fun maybePromptFullScreenIntentForIncomingCalls() {
        if (fullScreenIntentPromptShown) return
        if (!callManager.needsFullScreenIntentSettings()) return
        fullScreenIntentPromptShown = true
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.call_full_screen_intent_title))
            .setMessage(getString(R.string.call_full_screen_intent_message))
            .setPositiveButton(getString(R.string.call_full_screen_intent_open_settings)) { d, _ ->
                callManager.launchFullScreenIntentSettings(this)
                d.dismiss()
            }
            .setNegativeButton(getString(R.string.permission_not_now)) { d, _ ->
                d.dismiss()
            }
            .show()
    }

    override fun onPause() {
        super.onPause()
        isResumed = false
        val inPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode
        if (!inPip) {
            applicationPaused = true
            actionBarLayout.onPause()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        actionBarLayout.getLastFragment()?.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        super.onDestroy()
        dismissVoiceRoom()
        dismissStreamingRoom()
        AndroidUtilities.cancelRunOnUIThread(dismissSplashRunnable)
        appUpdateGateRunnable?.let { AndroidUtilities.cancelRunOnUIThread(it) }
        appUpdateGateRunnable = null
        splashContentObserver?.let {
            notificationCenter.removeObserver(it, NotificationCenter.clansDidLoad)
            notificationCenter.removeObserver(it, NotificationCenter.dialogsNeedReload)
            splashContentObserver = null
        }

        autoNightConfig.stopSensorListening()
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.themeChanged)
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.languageChanged)
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.autoNightModeChanged)
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.needCheckSystemBarColors)
        notificationCenter.removeObserver(this, NotificationCenter.connectionStateChanged)
        notificationCenter.removeObserver(this, NotificationCenter.sessionExpired)
        notificationCenter.removeObserver(this, NotificationCenter.appDidLogout)
        notificationCenter.removeObserver(this, NotificationCenter.incomingCall)
        notificationCenter.removeObserver(this, NotificationCenter.callEnded)

        dismissIncomingCallOverlay(removeView = true)

        isActive = false
        if (instance === this) instance = null
    }

    override fun onLowMemory() {
        super.onLowMemory()
        actionBarLayout.onLowMemory()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        com.mezon.mobile.home.chat.ThumbnailCache.trimMemory(level)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (tryEnterPiP()) return
        actionBarLayout.onUserLeaveHint()
    }

    private fun tryEnterPiP(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val fragment = voiceRoomFragment ?: return false
        if (fragment.getRoom() == null) return false
        val manager = voiceOverlayManager ?: return false
        if (!manager.isVisible()) return false
        if (manager.isMinimized()) {
            manager.expand()
        }
        try {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(9, 16))
                .build()
            return enterPictureInPictureMode(params)
        } catch (_: Exception) {
            return false
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        val fragment = voiceRoomFragment ?: return
        if (isInPictureInPictureMode) {
            fragment.enterPipMode()
        } else {
            fragment.exitPipMode()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (handleShareIntent(intent)) return
        if (handleDeepLinkIntent(intent)) {
            flushPendingDeepLink()
            return
        }
        handleNotificationIntent(intent)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        handleBackPressed()
    }

    private fun handleBackPressed() {
        val manager = voiceOverlayManager
        if (manager != null && manager.isExpanded()) {
            minimizeVoiceRoom()
            return
        }
        if (actionBarLayout.getLastFragment() is UpdateUsernameFragment) {
            return
        }
        if (!actionBarLayout.onBackPressedInternal()) {
            finishAndRemoveTask()
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
        actionBarLayout.onActivityResult(requestCode, resultCode, data)
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!checkPermissionsResult(requestCode, permissions, grantResults)) return
        if (voiceOverlayManager?.isVisible() == true) {
            voiceRoomFragment?.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
        actionBarLayout.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    // ── NotificationCenterDelegate ──────────────────────────────────────────

    override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
        when (id) {
            NotificationCenter.themeChanged -> {
                val mode = args.firstOrNull() as? ThemeMode
                    ?: StartupCache.themeMode
                themeColors.setTheme(mode, isSystemDarkMode())
                drawerLayoutContainer.setBehindKeyboardColor(themeColors.surface)
                applySystemBarColors(mode)
                rebuildAllFragments(true)
            }
            NotificationCenter.languageChanged -> {
                applyLocaleToActivity()
                offlineNetworkBanner?.refreshLabel()
                rebuildAllFragments(true)
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
                dismissIncomingCallOverlay(removeView = false)
                switchToLogin()
            }
            NotificationCenter.incomingCall -> {
                if (!StartupCache.hasSession) return
                if (IncomingCallActivity.shouldSuppressMainTabsIncomingOverlay()) return
                val callInfo = args.firstOrNull() as? CallInfo ?: return
                showIncomingCallingOverlay(callInfo)
            }
            NotificationCenter.callEnded -> {
                dismissIncomingCallOverlay(removeView = false)
            }
        }
    }

    private fun setupOfflineNetworkBanner() {
        val banner = OfflineNetworkBannerView(this)
        banner.tag = DrawerLayoutContainer.CHILD_TAG_TOP_END_OVERLAY
        offlineNetworkBanner = banner
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
        }
        drawerLayoutContainer.addView(banner, lp)
        ViewCompat.requestApplyInsets(banner)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                networkMonitor.isOnline.collect { online ->
                    banner.post {
                        banner.visibility = if (online) View.GONE else View.VISIBLE
                    }
                }
            }
        }
    }

    private fun requestIncomingCallPermissionsEagerly() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CAMERA)
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQUEST_INCOMING_CALL_PERMISSIONS)
        }
    }

    private fun showIncomingCallingOverlay(callInfo: CallInfo) {
        if (!StartupCache.hasSession) return
        Log.d(TAG, "showIncomingCallingOverlay: caller=${callInfo.peerName}")
        requestIncomingCallPermissionsEagerly()
        var overlay = callingOverlay
        if (overlay == null) {
            overlay = CallingOverlay(this)
            callingOverlay = overlay
        }
        overlay.setCallerInfo(callInfo.peerName, callInfo.peerUsername, callInfo.peerAvatar)
        overlay.delegate = object : CallingOverlay.Delegate {
            override fun onAcceptClicked() {
                Log.d(TAG, "incoming overlay accept: state=${callController.callState::class.simpleName}")
                dismissIncomingCallOverlay(removeView = false)
                callController.acceptCall()
                actionBarLayout.presentFragment(CallFragment())
            }

            override fun onDeclineClicked() {
                dismissIncomingCallOverlay(removeView = false)
                callController.rejectCall()
            }
        }
        if (overlay.parent == null) {
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
            drawerLayoutContainer.addView(overlay, lp)
        }
        drawerLayoutContainer.bringChildToFront(overlay)
        drawerLayoutContainer.post { applyIncomingCallOverlayLayoutParams(overlay) }
        overlay.requestApplyInsets()
        overlay.show()
    }

    private fun applyIncomingCallOverlayLayoutParams(overlay: View) {
        val lp = overlay.layoutParams as? FrameLayout.LayoutParams ?: return
        val insets = ViewCompat.getRootWindowInsets(drawerLayoutContainer)
        val insetTop = if (insets != null) {
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            max(sys.top, cut.top)
        } else {
            0
        }
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.topMargin = max(insetTop, AndroidUtilities.statusBarHeight) + LayoutHelper.dp(8f)
        lp.marginStart = LayoutHelper.dp(12f)
        lp.marginEnd = LayoutHelper.dp(12f)
        lp.width = FrameLayout.LayoutParams.MATCH_PARENT
        lp.height = FrameLayout.LayoutParams.WRAP_CONTENT
        overlay.layoutParams = lp
    }

    private fun dismissIncomingCallOverlay(removeView: Boolean) {
        val o = callingOverlay ?: return
        o.dismiss()
        o.delegate = null
        if (removeView) {
            (o.parent as? ViewGroup)?.removeView(o)
            callingOverlay = null
        }
    }

    fun bringIncomingCallingOverlayToFront() {
        val o = callingOverlay ?: return
        if (o.parent !== drawerLayoutContainer || o.visibility != View.VISIBLE) return
        drawerLayoutContainer.bringChildToFront(o)
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
            finishAndRemoveTask()
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

    private fun preInitControllers() {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext, FragmentEntryPoint::class.java
        )
        entryPoint.clansController()
        entryPoint.dialogsController()
    }

    private fun setupSplashDismiss() {
        val observer = object : NotificationCenter.NotificationCenterDelegate {
            override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
                isContentReady = true
                AndroidUtilities.cancelRunOnUIThread(dismissSplashRunnable)
                notificationCenter.removeObserver(this, NotificationCenter.clansDidLoad)
                notificationCenter.removeObserver(this, NotificationCenter.dialogsNeedReload)
                splashContentObserver = null
            }
        }
        splashContentObserver = observer
        notificationCenter.addObserver(observer, NotificationCenter.clansDidLoad)
        notificationCenter.addObserver(observer, NotificationCenter.dialogsNeedReload)
        AndroidUtilities.runOnUIThread(dismissSplashRunnable, 3000)
    }

    private fun showLogin() {
        StartupCache.needsUsernameSetup = false
        mainFragmentsStack.clear()
        val fragment = LoginFragment().apply {
            onLoginSuccess = { navigatePostAuth() }
        }
        actionBarLayout.addFragmentToStack(fragment)
        actionBarLayout.showLastFragment()
    }

    private fun navigatePostAuth() {
        if (StartupCache.needsUsernameSetup) {
            showUpdateUsernameGate()
        } else {
            showHome()
        }
    }

    private fun showUpdateUsernameGate() {
        notificationHelper.cancelAllNotifications()
        dismissVoiceRoom()
        dismissStreamingRoom()
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext, FragmentEntryPoint::class.java
        )
        entryPoint.voiceController().cleanup()
        entryPoint.streamingController().cleanup()
        actionBarLayout.removeAllFragments()
        actionBarLayout.containerView.removeAllViews()
        actionBarLayout.containerViewBack.removeAllViews()
        mainFragmentsStack.clear()
        val fragment = UpdateUsernameFragment().apply {
            onComplete = { showHome() }
        }
        actionBarLayout.addFragmentToStack(fragment)
        actionBarLayout.showLastFragment()
    }

    fun logoutToChooseDifferentPhone() {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext, FragmentEntryPoint::class.java
        )
        lifecycleScope.launch {
            StartupCache.needsUsernameSetup = false
            withContext(Dispatchers.IO) {
                entryPoint.authRepository().logout()
            }
            switchToLogin()
        }
    }

    fun popToHomeTabs() {
        showHome()
    }

    fun popToMainTabsIfPresent() {
        val stack = actionBarLayout.getFragmentStack()
        if (stack.none { it is MainTabsActivity }) {
            showHome()
            return
        }
        while (actionBarLayout.getFragmentStack().size > 1) {
            val top = actionBarLayout.getLastFragment() ?: break
            if (top is MainTabsActivity) break
            val sizeBefore = actionBarLayout.getFragmentStack().size
            actionBarLayout.closeLastFragment(animated = false, forceNoAnimation = true)
            if (actionBarLayout.getFragmentStack().size >= sizeBefore) break
        }
        rewireTopFragmentCallbacks()
    }

    private fun showHome() {
        val mainTabsActivity = MainTabsActivity().apply {
            onLogout = { switchToLogin() }
            onOpenChat = { channelId, channelName, clanId, channelType ->
                openChat(channelId, channelName, clanId, channelType)
            }
        }
        actionBarLayout.removeAllFragments()
        actionBarLayout.containerView.removeAllViews()
        actionBarLayout.containerViewBack.removeAllViews()
        mainFragmentsStack.clear()
        actionBarLayout.addFragmentToStack(mainTabsActivity)
        actionBarLayout.showLastFragment()
        if (!StartupCache.suppressHomeListApiForIncomingCallWake) {
            dialogsController.loadDialogs()
            messageActivitiesController.loadListActivities()
            
        }
        flushPendingDeepLink()
    }

    private fun switchToLogin() {
        StartupCache.needsUsernameSetup = false
        StartupCache.suppressHomeListApiForIncomingCallWake = false
        deepLinkRouter.clearPending()
        notificationHelper.cancelAllNotifications()
        dismissVoiceRoom()
        dismissStreamingRoom()
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext, FragmentEntryPoint::class.java
        )
        entryPoint.messagesController().disconnect()
        entryPoint.voiceController().cleanup()
        entryPoint.streamingController().cleanup()
        entryPoint.dialogsController().cleanup()
        entryPoint.chatController().cleanup()
        entryPoint.clansController().cleanup()
        entryPoint.channelController().cleanup()
        entryPoint.channelAppController().cleanup()
        entryPoint.channelPermissionController().cleanup()
        entryPoint.userClanController().cleanup()
        entryPoint.roleController().cleanup()
        entryPoint.notificationStore().cleanup()
        entryPoint.topicController().cleanup()
        entryPoint.friendController().cleanup()
        entryPoint.accountController().cleanup()
        entryPoint.userController().cleanup()
        entryPoint.badgeCoordinator().cleanup()
        entryPoint.channelFilesController().cleanup()
        entryPoint.channelGalleryController().cleanup()
        entryPoint.pinMessageController().cleanup()
        entryPoint.emojiController().cleanup()
        entryPoint.audioPlayerController().stop()
        entryPoint.apiCacheTracker().invalidateAll()
        com.mezon.mobile.home.chat.MezonImageLoader.getInstance(this).also {
            it.cancelAll()
            it.clearMemoryCache()
            it.clearDiskCache()
        }
        actionBarLayout.removeAllFragments()
        actionBarLayout.containerView.removeAllViews()
        actionBarLayout.containerViewBack.removeAllViews()
        mainFragmentsStack.clear()
        showLogin()
    }

    fun switchToAccount(account: Int, removeAll: Boolean) {
        if (account == currentAccount) return
        currentAccount = account
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext, FragmentEntryPoint::class.java
        )
        entryPoint.dialogsController().cleanup()
        entryPoint.chatController().cleanup()
        entryPoint.clansController().cleanup()
        entryPoint.channelController().cleanup()
        entryPoint.channelAppController().cleanup()
        entryPoint.channelPermissionController().cleanup()
        entryPoint.userClanController().cleanup()
        entryPoint.roleController().cleanup()
        entryPoint.notificationStore().cleanup()
        entryPoint.topicController().cleanup()
        entryPoint.friendController().cleanup()
        entryPoint.accountController().cleanup()
        entryPoint.userController().cleanup()
        entryPoint.badgeCoordinator().cleanup()
        entryPoint.channelFilesController().cleanup()
        entryPoint.channelGalleryController().cleanup()
        entryPoint.pinMessageController().cleanup()
        entryPoint.emojiController().cleanup()
        entryPoint.audioPlayerController().stop()
        entryPoint.messagesController().clearCachedUsersAndChannels()
        entryPoint.apiCacheTracker().invalidateAll()
        com.mezon.mobile.home.chat.MezonImageLoader.getInstance(this).also {
            it.cancelAll()
            it.clearMemoryCache()
            it.clearDiskCache()
        }
        if (removeAll) {
            actionBarLayout.removeAllFragments()
        }
        showHome()
    }

    fun showVoiceRoom(channelId: Long, clanId: Long, channelLabel: String) {
        if (isStreamingOverlayVisible()) dismissStreamingRoom()
        val manager = voiceOverlayManager ?: return
        val existing = voiceRoomFragment
        if (existing != null) {
            val sameRoom = existing.getChannelId() == channelId && existing.getClanId() == clanId
            if (sameRoom && manager.isVisible()) {
                manager.expand()
                return
            }
            dismissVoiceRoom()
        }
        val fragment = VoiceRoomFragment.create(channelId, clanId, channelLabel)
        fragment.themeColors = themeColors
        fragment.notificationCenter = notificationCenter
        fragment.parentLayout = actionBarLayout
        fragment.inject(this)
        fragment.onFragmentCreate()
        val contentView = fragment.createView(this)
        fragment.fragmentView = contentView
        voiceRoomFragment = fragment
        fragment.onResume()
        fragment.onBecomeFullyVisible()
        manager.showExpanded(contentView)
    }

    fun minimizeVoiceRoom() {
        val manager = voiceOverlayManager ?: return
        val fragment = voiceRoomFragment ?: return
        val focused = fragment.getFocusedContent()
        if (focused != null) {
            manager.minimize(
                fragment.getRoom(), focused.videoTrack,
                focused.name, focused.username, focused.avatarUrl, focused.isMuted, focused.userId
            )
        } else {
            manager.minimize(null, null, fragment.getChannelLabel(), "", null, false, 0L)
        }
    }

    fun expandVoiceRoom() {
        val manager = voiceOverlayManager ?: return
        manager.expand()
    }

    fun dismissVoiceRoom() {
        val manager = voiceOverlayManager ?: return
        manager.dismiss()
        voiceRoomFragment?.onPause()
        voiceRoomFragment?.onFragmentDestroy()
        voiceRoomFragment = null
    }

    fun isVoiceOverlayVisible(): Boolean = voiceOverlayManager?.isVisible() == true
    fun isVoiceOverlayExpanded(): Boolean = voiceOverlayManager?.isExpanded() == true

    fun showStreamingRoom(
        channelId: Long,
        clanId: Long,
        channelLabel: String,
        channelAvatar: String = "",
    ) {
        if (isVoiceOverlayVisible()) dismissVoiceRoom()
        val manager = streamingOverlayManager ?: return
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext, FragmentEntryPoint::class.java
        )
        val resolvedAvatar = channelAvatar.trim().ifEmpty {
            entryPoint.channelController().getChannelAvatar(clanId, channelId)
        }
        if (resolvedAvatar.isEmpty()) {
            entryPoint.channelController().loadChannelsForClan(clanId, force = false)
        }
        val existing = streamingRoomFragment
        if (existing != null) {
            val sameRoom = existing.getChannelId() == channelId && existing.getClanId() == clanId
            if (sameRoom && manager.isVisible()) {
                manager.expand()
                return
            }
            dismissStreamingRoom()
        }
        val fragment = StreamingRoomFragment.create(channelId, clanId, channelLabel, resolvedAvatar)
        fragment.themeColors = themeColors
        fragment.notificationCenter = notificationCenter
        fragment.parentLayout = actionBarLayout
        fragment.inject(this)
        fragment.onFragmentCreate()
        val contentView = fragment.createView(this)
        fragment.fragmentView = contentView
        streamingRoomFragment = fragment
        fragment.onResume()
        fragment.onBecomeFullyVisible()
        manager.showExpanded(contentView)
    }

    fun minimizeStreamingRoom() {
        val manager = streamingOverlayManager ?: return
        val fragment = streamingRoomFragment ?: return
        manager.minimizeStream(fragment.getChannelLabel(), fragment.getChannelAvatar())
    }

    fun updateStreamingMiniOverlay() {
        val fragment = streamingRoomFragment ?: return
        streamingOverlayManager?.updateMiniStream(fragment.getChannelLabel(), fragment.getChannelAvatar())
    }

    fun expandStreamingRoom() {
        streamingOverlayManager?.expand()
        streamingRoomFragment?.rebindSessionUi()
    }

    fun dismissStreamingRoom(disconnectSession: Boolean = true) {
        val manager = streamingOverlayManager ?: return
        manager.dismiss()
        streamingRoomFragment?.onPause()
        streamingRoomFragment?.onFragmentDestroy()
        streamingRoomFragment = null
        if (disconnectSession) {
            EntryPointAccessors.fromApplication(applicationContext, FragmentEntryPoint::class.java)
                .streamingWebRtcSession()
                .disconnect()
        }
    }

    fun isStreamingOverlayVisible(): Boolean = streamingOverlayManager?.isVisible() == true
    fun isStreamingOverlayExpanded(): Boolean = streamingOverlayManager?.isExpanded() == true

    fun openChat(
        channelId: Long,
        channelName: String,
        clanId: Long,
        channelType: Int,
        messageId: Long = 0L,
        noAnimation: Boolean = false,
        fromNotification: Boolean = false,
        forceRejoin: Boolean = false,
        replaceLastFragment: Boolean = false
    ) {
        val routeMeta = resolveChatRouteMeta(channelId, clanId, channelType)
        val resolvedChannelName = resolveChatDisplayName(channelId, channelName, clanId, routeMeta.channelType)
        val lastFragment = actionBarLayout.getLastFragment()
        if (lastFragment is ChatFragment && lastFragment.getChannelId() == channelId && messageId == 0L && !forceRejoin) {
            preloadChatContext(channelId, resolvedChannelName, clanId, routeMeta)
            if (fromNotification) {
                clearStackAboveTabs()
                switchToTabForClan(clanId)
            }
            return
        }

        if (fromNotification) {
            clearStackAboveTabs()
            switchToTabForClan(clanId)
        }

        preloadChatContext(channelId, resolvedChannelName, clanId, routeMeta)
        val fragment = ChatFragment.newInstance(
            channelId = channelId,
            channelName = resolvedChannelName,
            clanId = clanId,
            channelType = routeMeta.channelType,
            messageId = messageId,
            isChannelPrivate = routeMeta.isPrivate,
            isChannelAgeRestricted = routeMeta.isAgeRestricted,
            parentId = routeMeta.parentId,
            openedFromNotification = fromNotification
        )
        val params = INavigationLayout.NavigationParams(fragment)
            .setNoAnimation(noAnimation)
            .setRemoveLast(replaceLastFragment)
        actionBarLayout.presentFragment(params)
    }

    private fun resolveChatDisplayName(
        channelId: Long,
        channelName: String,
        clanId: Long,
        channelType: Int
    ): String {
        if (clanId != 0L) return channelName
        if (channelType != CHANNEL_TYPE_DM && channelType != CHANNEL_TYPE_GROUP) return channelName
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext, FragmentEntryPoint::class.java
        )
        val dm = entryPoint.dialogsController().getDialog(channelId)
        val dialogName = dm?.displayName?.ifEmpty { dm.label }.orEmpty()
        return when {
            dialogName.isNotBlank() -> dialogName
            channelName.isNotBlank() -> channelName
            else -> dm?.label.orEmpty()
        }
    }

    private fun preloadChatContext(channelId: Long, channelName: String, clanId: Long, routeMeta: ChatRouteMeta) {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext, FragmentEntryPoint::class.java
        )
        if (clanId != 0L) {
            entryPoint.clansController().selectClan(clanId)
            Log.d(TAG, "openChat preload selectClan clanId=$clanId")
        }
        entryPoint.chatController().openChannel(
            channelId = channelId,
            clanId = clanId,
            channelType = routeMeta.channelType,
            isChannelPrivate = routeMeta.isPrivate,
            parentId = routeMeta.parentId
        )
        Log.d(
            TAG,
            "openChat preload openChannel channelId=$channelId clanId=$clanId type=${routeMeta.channelType} private=${routeMeta.isPrivate} parent=${routeMeta.parentId}"
        )
        ensureThreadChannelRow(channelId, channelName, clanId, routeMeta.channelType)
    }

    private fun resolveChatRouteMeta(channelId: Long, clanId: Long, channelType: Int): ChatRouteMeta {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext, FragmentEntryPoint::class.java
        )
        if (clanId == 0L) {
            val dm = entryPoint.dialogsController().getDialog(channelId)
            val resolvedType = when {
                dm?.type == CHANNEL_TYPE_GROUP -> CHANNEL_TYPE_GROUP
                dm?.type == CHANNEL_TYPE_DM -> CHANNEL_TYPE_DM
                channelType == CHANNEL_TYPE_GROUP -> CHANNEL_TYPE_GROUP
                channelType == CHANNEL_TYPE_DM -> CHANNEL_TYPE_DM
                else -> CHANNEL_TYPE_DM
            }
            return ChatRouteMeta(
                channelType = resolvedType,
                isPrivate = false,
                isAgeRestricted = false,
                parentId = 0L
            )
        }
        val channelEntity = entryPoint.channelController().findChannelById(channelId, clanId)
            ?: entryPoint.searchController().findChannelById(channelId)
        val resolvedType = when {
            channelEntity?.isThread == true -> CHANNEL_TYPE_THREAD
            channelEntity?.type != null && channelEntity.type != 0 -> channelEntity.type
            channelType != 0 -> channelType
            else -> CHANNEL_TYPE_CHANNEL
        }
        val resolvedParent = channelEntity?.parentId ?: 0L
        val resolvedPrivate = when {
            channelEntity != null -> channelEntity.isPrivate
            resolvedType == CHANNEL_TYPE_THREAD -> true
            else -> false
        }
        return ChatRouteMeta(
            channelType = resolvedType,
            isPrivate = resolvedPrivate,
            isAgeRestricted = channelEntity?.isAgeRestricted == true,
            parentId = resolvedParent
        )
    }

    private fun ensureThreadChannelRow(channelId: Long, channelName: String, clanId: Long, channelType: Int) {
        if (channelType != CHANNEL_TYPE_THREAD) {
            Log.d(TAG, "ensureThreadChannelRow skip: non-thread type=$channelType")
            return
        }
        if (clanId == 0L || channelId == 0L) {
            Log.d(TAG, "ensureThreadChannelRow skip: invalid ids clanId=$clanId channelId=$channelId")
            return
        }
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext, FragmentEntryPoint::class.java
        )
        val channelController = entryPoint.channelController()
        val searchEntity = entryPoint.searchController().findChannelById(channelId)
        val searchParentId = searchEntity?.parentId ?: 0L
        val searchPrivate = searchEntity?.isPrivate == true
        val searchLabel = searchEntity?.channelLabel.orEmpty()
        channelController.loadChannelsForClan(clanId)
        Log.d(TAG, "ensureThreadChannelRow warm loadChannelsForClan clanId=$clanId")
        val existing = channelController.findChannelById(channelId, clanId)
        if (existing != null) {
            val shouldPatchPrivacy = existing.type == CHANNEL_TYPE_THREAD && !existing.isPrivate && (existing.parentId == 0L || searchPrivate)
            val shouldPatchParent = existing.parentId == 0L && searchParentId != 0L
            if (shouldPatchPrivacy || shouldPatchParent) {
                val patched = existing.copy(
                    parentId = if (shouldPatchParent) searchParentId else existing.parentId,
                    isPrivate = if (shouldPatchPrivacy) true else existing.isPrivate,
                    channelLabel = when {
                        existing.channelLabel.isNotBlank() -> existing.channelLabel
                        searchLabel.isNotBlank() -> searchLabel
                        else -> channelName
                    }
                )
                channelController.upsertChannel(patched)
                Log.d(
                    TAG,
                    "ensureThreadChannelRow patched existing thread clanId=$clanId channelId=$channelId parentId=${patched.parentId} private=${patched.isPrivate}"
                )
                return
            }
            Log.d(TAG, "ensureThreadChannelRow skip existing clanId=$clanId channelId=$channelId")
            return
        }
        val channel = ClanChannelEntity(
            clanId = clanId,
            channelId = channelId,
            parentId = searchParentId,
            categoryId = searchEntity?.categoryId ?: 0L,
            categoryName = searchEntity?.categoryName.orEmpty(),
            channelLabel = when {
                searchLabel.isNotBlank() -> searchLabel
                else -> channelName
            },
            type = CHANNEL_TYPE_THREAD,
            isPrivate = searchPrivate || searchParentId != 0L,
            topic = "",
            unreadCount = 0,
            isMuted = searchEntity?.isMuted ?: false,
            categoryOrder = searchEntity?.categoryOrder ?: 0
        )
        channelController.upsertChannel(channel)
        Log.d(
            TAG,
            "ensureThreadChannelRow upsert clanId=$clanId channelId=$channelId parentId=${channel.parentId} private=${channel.isPrivate}"
        )
    }

    private fun clearStackAboveTabs() {
        val stack = actionBarLayout.getFragmentStack()
        val toRemove = ArrayList<BaseFragment>(stack.size)
        for (i in 1 until stack.size) {
            val f = stack[i]
            if (f !is MainTabsActivity) {
                toRemove.add(f)
            }
        }
        toRemove.forEach { actionBarLayout.removeFragmentFromStack(it) }
    }

    private fun switchToTabForClan(clanId: Long) {
        if (clanId != 0L) {
            notificationCenter.postNotificationOnMainThread(NotificationCenter.navigateToClansTab)
        } else {
            notificationCenter.postNotificationOnMainThread(NotificationCenter.navigateToMessagesTab)
        }
    }

    private fun rewireTopFragmentCallbacks() {
        when (val top = mainFragmentsStack.lastOrNull()) {
            is LoginFragment -> top.onLoginSuccess = { navigatePostAuth() }
            is OTPVerificationFragment -> top.onVerifySuccess = { navigatePostAuth() }
            is UpdateUsernameFragment -> top.onComplete = { showHome() }
            is MainTabsActivity -> {
                top.onLogout = { switchToLogin() }
                top.onOpenChat = { channelId, channelName, clanId, channelType ->
                    openChat(channelId, channelName, clanId, channelType)
                }
            }
        }
    }

    private fun handleShareIntent(intent: Intent?): Boolean {
        intent ?: return false
        val action = intent.action ?: return false
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return false
        if (!StartupCache.hasSession) return false

        val uris = ArrayList<Uri>()
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
        val mimeType = intent.type

        when (action) {
            Intent.ACTION_SEND -> {
                val stream = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                }
                stream?.takeIf { isAllowedShareUri(it) }?.let { uris.add(it) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val streams = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                }
                streams?.filter { isAllowedShareUri(it) }?.let { uris.addAll(it) }
            }
        }

        if (uris.isEmpty() && sharedText.isNullOrBlank()) return false

        intent.action = null
        intent.removeExtra(Intent.EXTRA_STREAM)
        intent.removeExtra(Intent.EXTRA_TEXT)

        PhotoViewer.dismissActiveIfShowing()

        val fragment = SharingFragment.fromDevice(
            uris = uris,
            text = sharedText,
            mimeType = mimeType
        )
        val params = INavigationLayout.NavigationParams(fragment)
        actionBarLayout.presentFragment(params)
        return true
    }

    private fun isAllowedShareUri(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        return when (scheme) {
            "content" -> true
            "android.resource" -> true
            "https" -> true
            else -> {
                Log.w(TAG, "Rejected share URI with scheme=$scheme")
                false
            }
        }
    }

    private fun handleDeepLinkIntent(intent: Intent?): Boolean {
        if (intent == null || intent.action != Intent.ACTION_VIEW) return false
        val uri = intent.data ?: return false
        if (deepLinkRouter.ingest(uri) == null) return false
        intent.data = null
        return true
    }

    private fun flushPendingDeepLink() {
        if (!StartupCache.hasSession || StartupCache.needsUsernameSetup) return
        deepLinkRouter.dispatchPending(this)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        intent ?: return

        val offerJson = sequenceOf("offer", "offer_json", "json_data")
            .mapNotNull { intent.getStringExtra(it)?.trim()?.takeIf { s -> s.isNotEmpty() } }
            .firstOrNull()
        offerJson?.let { json ->
            if (StartupCache.hasSession) {
                incomingCallFcmHandler.handleOfferExtraFromNotificationIntent(json)
            }
            sequenceOf("offer", "offer_json", "json_data").forEach { intent.removeExtra(it) }
            return
        }

        if (intent.getBooleanExtra(com.mezon.mobile.home.call.CallNotificationManager.EXTRA_OPEN_CALL, false)) {
            intent.removeExtra(com.mezon.mobile.home.call.CallNotificationManager.EXTRA_OPEN_CALL)
            if (StartupCache.hasSession) {
                val callFragment = com.mezon.mobile.home.call.CallFragment()
                val params = INavigationLayout.NavigationParams(callFragment)
                actionBarLayout.presentFragment(params)
            }
            return
        }

        val action = intent.action
        val isFromNotification = action != null && action.startsWith(NotificationHelper.ACTION_OPEN_CHAT)
        val extras = intent.extras ?: return

        val clanId = extras.getLong(NotificationHelper.EXTRA_CLAN_ID, 0L)
        val channelId = extras.getLong(NotificationHelper.EXTRA_CHANNEL_ID, 0L)
        val dmId = extras.getLong(NotificationHelper.EXTRA_DM_ID, 0L)
        val channelName = extras.getString(NotificationHelper.EXTRA_CHANNEL_NAME, "") ?: ""

        if (clanId != 0L && channelId != 0L) {
            val channelType = extras.getInt(NotificationHelper.EXTRA_CHANNEL_TYPE, CHANNEL_TYPE_CHANNEL)
            if (StartupCache.hasSession) {
                openChat(channelId, channelName, clanId, channelType, noAnimation = isFromNotification, fromNotification = true)
            }
            intent.removeExtra(NotificationHelper.EXTRA_CHANNEL_ID)
        } else if (dmId != 0L) {
            val dmType = extras.getInt(NotificationHelper.EXTRA_CHANNEL_TYPE, CHANNEL_TYPE_DM)
            if (StartupCache.hasSession) {
                openChat(dmId, channelName, 0L, dmType, noAnimation = isFromNotification, fromNotification = isFromNotification)
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
                    REQUEST_CODE_NOTIFICATION
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

    @Suppress("DEPRECATION")
    private fun applyLocaleToActivity() {
        val locale = localeManager.currentLocale
        val config = android.content.res.Configuration(resources.configuration)
        config.setLocale(locale)
        resources.updateConfiguration(config, resources.displayMetrics)
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
