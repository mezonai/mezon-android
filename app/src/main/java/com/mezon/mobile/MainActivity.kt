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
import android.view.ActionMode
import android.view.Menu
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import com.mezon.mobile.auth.LoginFragment
import com.mezon.mobile.auth.OTPVerificationFragment
import com.mezon.mobile.core.ActionBarLayout
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.DrawerLayoutContainer
import com.mezon.mobile.core.INavigationLayout
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.SharedConfig
import com.mezon.mobile.core.StartupCache
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.ConnectionController
import com.mezon.mobile.home.DialogsController
import com.mezon.mobile.home.MainTabsActivity
import com.mezon.mobile.home.chat.ChatFragment
import com.mezon.mobile.home.clans.ClanChannelEntity
import com.mezon.mobile.home.sharing.SharingFragment
import com.mezon.mobile.home.voice.VoiceOverlayManager
import com.mezon.mobile.home.voice.VoiceRoomFragment
import com.mezon.mobile.network.CHANNEL_TYPE_CHANNEL
import com.mezon.mobile.network.CHANNEL_TYPE_DM
import com.mezon.mobile.network.CHANNEL_TYPE_GROUP
import com.mezon.mobile.network.CHANNEL_TYPE_THREAD
import com.mezon.mobile.notification.NotificationHelper
import com.mezon.mobile.session.AutoNightConfig
import com.mezon.mobile.session.LocaleManager
import com.mezon.mobile.session.SessionManager
import com.mezon.mobile.session.ThemeManager
import com.mezon.mobile.ui.theme.ThemeMode
import com.mezon.mobile.update.AppUpdateGateManager
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : BasePermissionsActivity(),
    INavigationLayout.INavigationLayoutDelegate,
    NotificationCenter.NotificationCenterDelegate {

    private data class ChatRouteMeta(
        val channelType: Int,
        val isPrivate: Boolean,
        val parentId: Long
    )

    companion object {
        private const val TAG = "MainActivity"

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
    @Inject lateinit var dialogsController: DialogsController
    @Inject lateinit var connectionController: ConnectionController
    @Inject lateinit var notificationHelper: NotificationHelper
    @Inject lateinit var appUpdateGateManager: AppUpdateGateManager

    lateinit var actionBarLayout: ActionBarLayout
    lateinit var drawerLayoutContainer: DrawerLayoutContainer
        private set

    var voiceOverlayManager: VoiceOverlayManager? = null
        private set
    private var voiceRoomFragment: VoiceRoomFragment? = null

    private var currentConnectionState = 0
    lateinit var autoNightConfig: AutoNightConfig
        private set

    private var isContentReady = false
    private val dismissSplashRunnable = Runnable { isContentReady = true }
    private var splashContentObserver: NotificationCenter.NotificationCenterDelegate? = null
    private var appUpdateGateRunnable: Runnable? = null

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

        voiceOverlayManager = VoiceOverlayManager(drawerLayoutContainer, themeColors).also { manager ->
            manager.onExpandRequest = { expandVoiceRoom() }
        }

        setContentView(drawerLayoutContainer)

        @Suppress("DEPRECATION")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        applySystemBarColors(themeMode)

        if (mainFragmentsStack.isEmpty()) {
            if (hasSession) {
                showHome()
                setupSplashDismiss()
            } else {
                showLogin()
            }
            if (!handleShareIntent(intent)) {
                handleNotificationIntent(intent)
            }
        } else {
            actionBarLayout.showLastFragment()
            rewireTopFragmentCallbacks()
        }
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
    }

    override fun onResume() {
        super.onResume()
        isResumed = true
        applicationPaused = false
        actionBarLayout.onResume()
        if (StartupCache.hasSession) {
            connectionController.handleAppForeground()
        }
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
        actionBarLayout.unregisterBackCallback()
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

        isActive = false
        if (instance === this) instance = null
    }

    override fun onLowMemory() {
        super.onLowMemory()
        actionBarLayout.onLowMemory()
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
        if (handleShareIntent(intent)) return
        handleNotificationIntent(intent)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val manager = voiceOverlayManager
        if (manager != null && manager.isExpanded()) {
            minimizeVoiceRoom()
            return
        }
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
        actionBarLayout.removeAllFragments()
        actionBarLayout.containerView.removeAllViews()
        actionBarLayout.containerViewBack.removeAllViews()
        mainFragmentsStack.clear()
        actionBarLayout.addFragmentToStack(mainTabsActivity)
        actionBarLayout.showLastFragment()
        dialogsController.loadDialogs()
    }

    private fun switchToLogin() {
        notificationHelper.cancelAllNotifications()
        dismissVoiceRoom()
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext, FragmentEntryPoint::class.java
        )
        entryPoint.voiceController().cleanup()
        actionBarLayout.removeAllFragments()
        actionBarLayout.containerView.removeAllViews()
        actionBarLayout.containerViewBack.removeAllViews()
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

    fun showVoiceRoom(channelId: Long, clanId: Long, channelLabel: String) {
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
                focused.name, focused.avatarUrl, focused.isMuted, focused.userId
            )
        } else {
            manager.minimize(null, null, fragment.getChannelLabel(), null, false, 0L)
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

    fun openChat(
        channelId: Long,
        channelName: String,
        clanId: Long,
        channelType: Int,
        messageId: Long = 0L,
        noAnimation: Boolean = false,
        fromNotification: Boolean = false,
        forceRejoin: Boolean = false
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
            parentId = routeMeta.parentId
        )
        val params = INavigationLayout.NavigationParams(fragment).setNoAnimation(noAnimation)
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
                stream?.let { uris.add(it) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val streams = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                }
                if (streams != null) uris.addAll(streams)
            }
        }

        if (uris.isEmpty() && sharedText.isNullOrBlank()) return false

        intent.action = null
        intent.removeExtra(Intent.EXTRA_STREAM)
        intent.removeExtra(Intent.EXTRA_TEXT)

        val fragment = SharingFragment(
            sharedUris = uris,
            sharedText = sharedText,
            sharedMimeType = mimeType
        )
        val params = INavigationLayout.NavigationParams(fragment)
        actionBarLayout.presentFragment(params)
        return true
    }

    private fun handleNotificationIntent(intent: Intent?) {
        intent ?: return
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
