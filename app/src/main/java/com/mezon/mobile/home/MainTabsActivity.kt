package com.mezon.mobile.home

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.ViewPagerActivity
import com.mezon.mobile.core.ViewPagerFixed
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.ChannelFilesController
import com.mezon.mobile.home.chat.EmojiController
import com.mezon.mobile.home.clans.ClansFragment
import com.mezon.mobile.home.messages.MessagesFragment
import com.mezon.mobile.home.friends.FriendController
import com.mezon.mobile.home.notifications.NotificationStore
import com.mezon.mobile.home.notifications.NotificationsFragment
import com.mezon.mobile.home.profile.AccountController
import com.mezon.mobile.home.profile.ProfileFragment
import com.mezon.mobile.ui.cells.BottomTabBar

class MainTabsActivity : ViewPagerActivity() {

    companion object {
        private const val TAB_CLANS = 0
        private const val TAB_MESSAGES = 1
        private const val TAB_NOTIFICATIONS = 2
        private const val TAB_PROFILE = 3
    }

    private lateinit var connectionController: ConnectionController
    @Suppress("unused")
    private lateinit var messagesController: MessagesController
    private lateinit var friendController: FriendController
    @Suppress("unused")
    private lateinit var voiceController: com.mezon.mobile.home.voice.VoiceController
    private lateinit var anonymousController: AnonymousController
    @Suppress("unused")
    private lateinit var pinMessageController: PinMessageController
    @Suppress("unused")
    private lateinit var channelGalleryController: ChannelGalleryController
    @Suppress("unused")
    private lateinit var channelFilesController: ChannelFilesController
    private lateinit var emojiController: EmojiController
    private lateinit var searchController: com.mezon.mobile.search.SearchController
    private lateinit var accountController: AccountController

    var onLogout: (() -> Unit)? = null
    var onOpenChat: ((channelId: Long, channelName: String, clanId: Long, channelType: Int) -> Unit)? = null

    private lateinit var contentRoot: FrameLayout
    private lateinit var bottomTabBar: BottomTabBar
    private var currentTab = TAB_CLANS
    private var prewarmedProfile = false

    override fun getStartPosition(): Int = currentTab
    override fun getFragmentsCount(): Int = 4

    override fun createFragmentForPosition(position: Int): BaseFragment = when (position) {
        TAB_CLANS -> ClansFragment().also { f ->
            f.onOpenChat = { channelId, channelName, clanId, channelType ->
                onOpenChat?.invoke(channelId, channelName, clanId, channelType)
            }
            f.onSwitchToMessages = {
                viewPager.scrollToPosition(TAB_MESSAGES)
            }
        }
        TAB_MESSAGES -> MessagesFragment().also { f ->
            f.onOpenChat = { channelId, channelName, clanId, channelType ->
                onOpenChat?.invoke(channelId, channelName, clanId, channelType)
            }
        }
        TAB_NOTIFICATIONS -> NotificationsFragment().also { f ->
            f.onOpenChat = { channelId, channelName, clanId, channelType ->
                onOpenChat?.invoke(channelId, channelName, clanId, channelType)
            }
        }
        TAB_PROFILE -> ProfileFragment().also { f ->
            f.onLogout = {
                onLogout?.invoke()
            }
        }
        else -> ClansFragment()
    }

    override fun onInject(entryPoint: FragmentEntryPoint) {
        connectionController = entryPoint.connectionController()
        messagesController = entryPoint.messagesController()
        friendController = entryPoint.friendController()
        voiceController = entryPoint.voiceController()
        anonymousController = entryPoint.anonymousController()
        pinMessageController = entryPoint.pinMessageController()
        channelFilesController = entryPoint.channelFilesController()
        channelGalleryController = entryPoint.channelGalleryController()
        emojiController = entryPoint.emojiController()
        searchController = entryPoint.searchController()
        accountController = entryPoint.accountController()
        entryPoint.notificationStore()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()

        emojiController.loadEmojis()
        emojiController.loadStickers()
        searchController.loadChannels()

        observe(NotificationCenter.sessionExpired) { _, _, _ ->
            onLogout?.invoke()
        }

        observe(NotificationCenter.themeChanged) { _, _, _ ->
            if (fragmentView == null) return@observe
            applyTheme()
        }

        observe(NotificationCenter.navigateToMessagesTab) { _, _, _ ->
            if (fragmentView == null) return@observe
            viewPager.scrollToPosition(TAB_MESSAGES)
        }

        observe(NotificationCenter.navigateToClansTab) { _, _, _ ->
            if (fragmentView == null) return@observe
            viewPager.scrollToPosition(TAB_CLANS)
        }

        return true
    }

    override fun createView(context: Context): View {
        contentRoot = object : FrameLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val sbh = AndroidUtilities.statusBarHeight
                if (paddingTop != sbh) {
                    setPadding(0, sbh, 0, 0)
                }
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }.apply {
            setBackgroundColor(themeColors.serverRailBg)
            setPadding(0, AndroidUtilities.statusBarHeight, 0, 0)
            clipToPadding = false
        }

        bottomTabBar = BottomTabBar(context, themeColors).apply {
            onTabSelected = object : BottomTabBar.OnTabSelectedListener {
                override fun onTabSelected(index: Int) {
                    if (!viewPager.swipeEnabled) return
                    viewPager.scrollToPosition(index)
                }
            }
        }
        contentRoot.addView(bottomTabBar, FrameLayout.LayoutParams(
            LayoutHelper.MATCH_PARENT, LayoutHelper.dp(56)
        ).apply { gravity = Gravity.BOTTOM })

        val pagerContainer = FrameLayout(context)
        contentRoot.addView(pagerContainer, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT,
            Gravity.TOP, 0f, 0f, 0f, 56f
        ))

        viewPager = ViewPagerFixed(context)
        viewPager.notificationCenter = notificationCenter
        viewPager.setAdapter(createAdapter())
        viewPager.setPosition(getStartPosition())
        bottomTabBar.selectTab(currentTab)
        viewPager.onPageChangeListener = object : ViewPagerFixed.OnPageChangeListener {
            override fun onPageSelected(position: Int, forward: Boolean) {
                currentTab = position
                if (::bottomTabBar.isInitialized) bottomTabBar.selectTab(position)
                onTabSelected(position, forward)
                checkFragmentsVisibility()
                updateContentRootBackground()
            }
            override fun onPageScrolled(progress: Float) {
                onTabAnimationUpdate(progress)
            }
            override fun onScrollEnd() {
                checkFragmentsVisibility()
            }
        }
        pagerContainer.addView(viewPager, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT
        ))

        return contentRoot
    }

    override fun getStatusBarColor(): Int = Color.TRANSPARENT

    override fun isLightStatusBar(): Boolean = themeColors.resolvedMode == com.mezon.mobile.ui.theme.ThemeMode.LIGHT

    private fun createAdapter(): ViewPagerFixed.Adapter {
        return object : ViewPagerFixed.Adapter() {
            override fun getItemCount(): Int = getFragmentsCount()
            override fun getItemViewType(position: Int): Int = position

            override fun createView(viewType: Int): View {
                return FrameLayout(contentRoot.context)
            }

            override fun bindView(view: View, position: Int, viewType: Int) {
                val container = view as FrameLayout
                container.removeAllViews()

                val state = getOrCreateFragmentState(position)
                val fragment = state.fragment
                if (!state.onCreateCalled) {
                    fragment.themeColors = themeColors
                    fragment.notificationCenter = notificationCenter
                    fragment.parentLayout = parentLayout
                    fragment.inject(container.context)
                    fragment.onFragmentCreate()
                    state.onCreateCalled = true
                }
                val fView = fragment.fragmentView ?: fragment.createView(container.context)
                fragment.fragmentView = fView
                if (fView.parent is ViewGroup) (fView.parent as ViewGroup).removeView(fView)
                container.addView(fView, LayoutHelper.createFrame(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT
                ))

                val actionBar = fragment.actionBar
                if (actionBar != null && actionBar.shouldAddToContainer() && actionBar.parent == null) {
                    container.addView(actionBar)
                }
            }

            override fun getItemTitle(position: Int): CharSequence = when (position) {
                TAB_CLANS -> "Clans"
                TAB_MESSAGES -> "Messages"
                TAB_NOTIFICATIONS -> "Notifications"
                TAB_PROFILE -> "Profile"
                else -> ""
            }
        }
    }

    private fun getOrCreateFragmentState(position: Int): FragmentState {
        var state = fragmentStates.get(position)
        if (state == null) {
            val fragment = createFragmentForPosition(position)
            state = FragmentState(fragment)
            fragmentStates.put(position, state)
        }
        return state
    }

    override fun onResume() {
        super.onResume()
        showTabBar(animated = false)
        if (::friendController.isInitialized) {
            friendController.loadFriendRelationsOnForegroundThrottled()
        }
    }

    override fun onBecomeFullyVisible() {
        super.onBecomeFullyVisible()
        connectionController.handleAppForeground()
        schedulePrewarmProfile()
    }

    private fun schedulePrewarmProfile() {
        if (prewarmedProfile || !::contentRoot.isInitialized) return
        contentRoot.postDelayed({
            if (prewarmedProfile) return@postDelayed
            prewarmedProfile = true
            prewarmFragmentAt(TAB_PROFILE)
        }, 600L)
    }

    private fun prewarmFragmentAt(position: Int) {
        val state = getOrCreateFragmentState(position)
        val fragment = state.fragment
        if (!state.onCreateCalled) {
            fragment.themeColors = themeColors
            fragment.notificationCenter = notificationCenter
            fragment.parentLayout = parentLayout
            fragment.inject(contentRoot.context)
            fragment.onFragmentCreate()
            state.onCreateCalled = true
        }
        if (fragment.fragmentView == null) {
            fragment.fragmentView = fragment.createView(contentRoot.context)
        }
    }

    override fun onBecomeFullyHidden() {
        super.onBecomeFullyHidden()
        hideTabBar()
    }

    override fun onBackPressed(): Boolean {
        val currentPos = viewPager.currentPosition
        val currentState = fragmentStates.get(currentPos)
        if (currentState != null && !currentState.fragment.onBackPressed()) {
            return false
        }
        if (currentPos != TAB_CLANS) {
            viewPager.scrollToPosition(TAB_CLANS)
            return false
        }
        return true
    }

    fun hideTabBar() {
        if (::bottomTabBar.isInitialized) bottomTabBar.hideTabBar()
    }

    fun showTabBar(animated: Boolean = true) {
        if (::bottomTabBar.isInitialized) bottomTabBar.showTabBar(animated)
    }

    private fun updateContentRootBackground() {
        if (!::contentRoot.isInitialized) return
        contentRoot.setBackgroundColor(
            if (currentTab == TAB_CLANS) themeColors.serverRailBg else themeColors.background
        )
    }

    private fun applyTheme() {
        if (!::contentRoot.isInitialized) return
        updateContentRootBackground()
        bottomTabBar.applyTheme()
    }

}
