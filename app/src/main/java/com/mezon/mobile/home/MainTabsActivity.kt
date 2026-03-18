package com.mezon.mobile.home

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.ViewPagerActivity
import com.mezon.mobile.core.ViewPagerFixed
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.ChatController
import com.mezon.mobile.home.clans.ClansFragment
import com.mezon.mobile.home.messages.MessagesFragment
import com.mezon.mobile.home.notifications.NotificationStore
import com.mezon.mobile.home.notifications.NotificationsFragment
import com.mezon.mobile.home.profile.AccountController
import com.mezon.mobile.home.profile.ProfileFragment
import com.mezon.mobile.network.ConnectionState
import com.mezon.mobile.ui.cells.BottomTabBar

class MainTabsActivity : ViewPagerActivity() {

    companion object {
        private const val TAB_CLANS = 0
        private const val TAB_MESSAGES = 1
        private const val TAB_NOTIFICATIONS = 2
        private const val TAB_PROFILE = 3
    }

    private lateinit var connectionController: ConnectionController
    private lateinit var messagesController: MessagesController
    @Suppress("unused")
    private lateinit var accountController: AccountController

    var onLogout: (() -> Unit)? = null
    var onOpenChat: ((channelId: Long, channelName: String, clanId: Long, channelType: Int) -> Unit)? = null

    private lateinit var contentRoot: FrameLayout
    private lateinit var bottomTabBar: BottomTabBar
    private lateinit var connectionIndicator: View
    private lateinit var connectionLabel: TextView
    private var currentTab = TAB_CLANS

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
                messagesController.disconnect()
                onLogout?.invoke()
            }
        }
        else -> ClansFragment()
    }

    override fun onInject(entryPoint: FragmentEntryPoint) {
        connectionController = entryPoint.connectionController()
        messagesController = entryPoint.messagesController()
        AndroidUtilities.runOnUIThread({
            accountController = entryPoint.accountController()
            entryPoint.notificationStore()
        }, 0)
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()

        observe(NotificationCenter.connectionStateChanged) { _, _, args ->
            if (fragmentView == null) return@observe
            val state = args.firstOrNull() as? ConnectionState ?: return@observe
            updateConnectionIndicator(state)
        }

        observe(NotificationCenter.sessionExpired) { _, _, _ ->
            onLogout?.invoke()
        }

        observe(NotificationCenter.themeChanged) { _, _, _ ->
            if (fragmentView == null) return@observe
            applyTheme()
        }

        return true
    }

    override fun createView(context: Context): View {
        contentRoot = FrameLayout(context).apply {
            setBackgroundColor(themeColors.background)
            setPadding(0, AndroidUtilities.statusBarHeight, 0, 0)
            clipToPadding = false
        }

        val header = FrameLayout(context)
        contentRoot.addView(header, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT, 28, Gravity.TOP
        ))

        val indicatorRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            val pad = LayoutHelper.dp(8)
            setPadding(pad, 0, pad, 0)
        }
        header.addView(indicatorRow, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT
        ))

        connectionIndicator = View(context).apply {
            setBackgroundColor(themeColors.connectedColor)
        }
        indicatorRow.addView(connectionIndicator, LayoutHelper.createLinear(8, 8, 0f, Gravity.CENTER_VERTICAL, 0f, 0f, 4f, 0f))

        connectionLabel = TextView(context).apply {
            textSize = 11f
            setTextColor(themeColors.onSurfaceVariant)
        }
        indicatorRow.addView(connectionLabel, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))

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
            Gravity.TOP, 0f, 28f, 0f, 56f
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
    }

    override fun onBecomeFullyVisible() {
        super.onBecomeFullyVisible()
        updateConnectionIndicator(connectionController.connectionState)
        connectionController.handleAppForeground()
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

    private fun applyTheme() {
        if (!::contentRoot.isInitialized) return
        contentRoot.setBackgroundColor(themeColors.background)
        connectionLabel.setTextColor(themeColors.onSurfaceVariant)
        bottomTabBar.applyTheme()
        updateConnectionIndicator(connectionController.connectionState)
    }

    private fun updateConnectionIndicator(state: ConnectionState) {
        val color = when (state) {
            ConnectionState.CONNECTED -> themeColors.connectedColor
            ConnectionState.CONNECTING -> themeColors.connectingColor
            ConnectionState.DISCONNECTED -> themeColors.disconnectedColor
        }
        val label = when (state) {
            ConnectionState.CONNECTED -> getString(R.string.connection_connected)
            ConnectionState.CONNECTING -> getString(R.string.connection_connecting)
            ConnectionState.DISCONNECTED -> getString(R.string.connection_disconnected)
        }
        connectionIndicator.setBackgroundColor(color)
        connectionLabel.text = label
    }
}
