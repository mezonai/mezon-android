package com.mezon.mobile.home

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.home.clans.ClansFragment
import com.mezon.mobile.home.messages.MessagesFragment
import com.mezon.mobile.home.notifications.NotificationsFragment
import com.mezon.mobile.home.profile.ProfileFragment
import com.mezon.mobile.network.ConnectionState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class HomeFragment : BaseFragment() {

    companion object {
        private const val TAB_CONTAINER_ID = 0x7F010001
        private const val KEY_SELECTED_TAB = "selected_tab"
    }

    @Inject lateinit var connectionController: ConnectionController
    @Inject lateinit var messagesController: MessagesController

    var onLogout: (() -> Unit)? = null
    var onOpenChat: ((channelId: Long, channelName: String, clanId: Long, channelType: Int) -> Unit)? = null

    private lateinit var root: LinearLayout
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var connectionIndicator: View
    private lateinit var connectionLabel: TextView
    private var currentTabId = R.id.tab_clans

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.background)
        }

        val header = FrameLayout(requireContext())
        root.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 28))

        val indicatorRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            val pad = LayoutHelper.dp(8)
            setPadding(pad, 0, pad, 0)
        }
        header.addView(indicatorRow, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT
        ))

        connectionIndicator = View(requireContext()).apply {
            setBackgroundColor(themeColors.connectedColor)
        }
        indicatorRow.addView(connectionIndicator, LayoutHelper.createLinear(8, 8, 0f, Gravity.CENTER_VERTICAL, 0f, 0f, 4f, 0f))

        connectionLabel = TextView(requireContext()).apply {
            textSize = 11f
            setTextColor(themeColors.onSurfaceVariant)
        }
        indicatorRow.addView(connectionLabel, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))

        val tabContent = FrameLayout(requireContext()).apply {
            id = TAB_CONTAINER_ID
        }
        root.addView(tabContent, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        bottomNav = BottomNavigationView(requireContext()).apply {
            setBackgroundColor(themeColors.surface)
            inflateMenu(R.menu.bottom_navigation)
            itemIconTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
                intArrayOf(themeColors.blurple, themeColors.onSurfaceVariant)
            )
            itemTextColor = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
                intArrayOf(themeColors.blurple, themeColors.onSurfaceVariant)
            )
        }
        root.addView(bottomNav, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val restoredTab = savedInstanceState?.getInt(KEY_SELECTED_TAB) ?: currentTabId
        currentTabId = restoredTab
        bottomNav.selectedItemId = restoredTab
        showTab(restoredTab)
        rewireFragmentCallbacks()

        bottomNav.setOnItemSelectedListener { item ->
            if (isRestoringTab) return@setOnItemSelectedListener true
            showTab(item.itemId)
            true
        }

        updateConnectionIndicator(connectionController.connectionState)

        observe(NotificationCenter.connectionStateChanged) { _, args ->
            val state = args.firstOrNull() as? ConnectionState ?: return@observe
            updateConnectionIndicator(state)
        }

        observe(NotificationCenter.sessionExpired) { _, _ ->
            onLogout?.invoke()
        }

        observe(NotificationCenter.themeChanged) { _, _ ->
            updateThemeColors()
            updateConnectionIndicator(connectionController.connectionState)
            reloadCurrentTab()
        }

        connectionController.handleAppForeground()
    }

    private var isRestoringTab = false

    override fun onResume() {
        super.onResume()
        isRestoringTab = true
        bottomNav.selectedItemId = currentTabId
        isRestoringTab = false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_SELECTED_TAB, currentTabId)
    }

    private fun showTab(itemId: Int) {
        if (currentTabId == itemId && childFragmentManager.findFragmentByTag(tagFor(itemId)) != null) return
        currentTabId = itemId

        val transaction = childFragmentManager.beginTransaction()

        val allTabIds = listOf(R.id.tab_clans, R.id.tab_messages, R.id.tab_notifications, R.id.tab_profile)
        for (tabId in allTabIds) {
            val tag = tagFor(tabId)
            val existing = childFragmentManager.findFragmentByTag(tag)
            if (tabId == itemId) {
                if (existing == null) {
                    transaction.add(TAB_CONTAINER_ID, createFragment(tabId), tag)
                } else {
                    transaction.show(existing)
                }
            } else {
                if (existing != null) transaction.hide(existing)
            }
        }

        transaction.commitNow()
    }

    private fun tagFor(tabId: Int) = "tab_$tabId"

    private fun createFragment(tabId: Int): Fragment = when (tabId) {
        R.id.tab_clans -> ClansFragment().also { f ->
            f.onOpenChat = { channelId, channelName, clanId, channelType ->
                onOpenChat?.invoke(channelId, channelName, clanId, channelType)
            }
        }
        R.id.tab_messages -> MessagesFragment().also { f ->
            f.onOpenChat = { channelId, channelName, clanId, channelType ->
                onOpenChat?.invoke(channelId, channelName, clanId, channelType)
            }
        }
        R.id.tab_notifications -> NotificationsFragment().also { f ->
            f.onOpenChat = { channelId, channelName, clanId, channelType ->
                onOpenChat?.invoke(channelId, channelName, clanId, channelType)
            }
        }
        R.id.tab_profile -> ProfileFragment().also { f ->
            f.onLogout = {
                messagesController.disconnect()
                onLogout?.invoke()
            }
        }
        else -> ClansFragment()
    }

    private fun rewireFragmentCallbacks() {
        (childFragmentManager.findFragmentByTag(tagFor(R.id.tab_clans)) as? ClansFragment)
            ?.also { it.onOpenChat = { channelId, channelName, clanId, channelType ->
                onOpenChat?.invoke(channelId, channelName, clanId, channelType)
            }}
        (childFragmentManager.findFragmentByTag(tagFor(R.id.tab_messages)) as? MessagesFragment)
            ?.also { it.onOpenChat = { channelId, channelName, clanId, channelType ->
                onOpenChat?.invoke(channelId, channelName, clanId, channelType)
            }}
        (childFragmentManager.findFragmentByTag(tagFor(R.id.tab_notifications)) as? NotificationsFragment)
            ?.also { it.onOpenChat = { channelId, channelName, clanId, channelType ->
                onOpenChat?.invoke(channelId, channelName, clanId, channelType)
            }}
        (childFragmentManager.findFragmentByTag(tagFor(R.id.tab_profile)) as? ProfileFragment)
            ?.also { it.onLogout = { messagesController.disconnect(); onLogout?.invoke() } }
    }

    private fun updateThemeColors() {
        root.setBackgroundColor(themeColors.background)
        bottomNav.setBackgroundColor(themeColors.surface)
        val tintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
            intArrayOf(themeColors.blurple, themeColors.onSurfaceVariant)
        )
        bottomNav.itemIconTintList = tintList
        bottomNav.itemTextColor = tintList
        connectionLabel.setTextColor(themeColors.onSurfaceVariant)
    }

    private fun reloadCurrentTab() {
        // Fragments observe NotificationCenter.themeChanged themselves — no need to recreate
    }

    private fun updateConnectionIndicator(state: ConnectionState) {
        val (color, label) = when (state) {
            ConnectionState.CONNECTED -> themeColors.connectedColor to getString(R.string.connection_connected)
            ConnectionState.CONNECTING -> themeColors.connectingColor to getString(R.string.connection_connecting)
            ConnectionState.DISCONNECTED -> themeColors.disconnectedColor to getString(R.string.connection_disconnected)
        }
        connectionIndicator.setBackgroundColor(color)
        connectionLabel.text = label
    }
}
