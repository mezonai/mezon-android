package com.mezon.mobile.home.friends

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import com.mezon.mezon.api.Friend
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.RecyclerListView
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.chat.UserProfileBottomSheet

class FriendRequestsFragment : BaseFragment() {

    private lateinit var friendController: FriendController

    private lateinit var receivedTab: TextView
    private lateinit var sentTab: TextView
    private lateinit var recyclerView: RecyclerListView
    private lateinit var emptyTitle: TextView
    private lateinit var emptyDescription: TextView
    private lateinit var adapter: FriendRelationAdapter

    private var selectedTab = FRIEND_STATE_INVITE_RECEIVED

    override fun onInject(entryPoint: FragmentEntryPoint) {
        friendController = entryPoint.friendController()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        observe(NotificationCenter.friendsLoaded) { _, _, _ ->
            if (fragmentView != null) {
                updateTheme()
                reloadList()
            }
        }
        observe(NotificationCenter.themeChanged) { _, _, _ ->
            if (fragmentView != null) {
                updateTheme()
                adapter.notifyDataSetChanged()
            }
        }
        friendController.loadFriendRelations(noCache = true)
        return true
    }

    override fun createView(context: Context): View {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.background)
            setPadding(LayoutHelper.dp(16), LayoutHelper.dp(16), LayoutHelper.dp(16), LayoutHelper.dp(12))
        }

        val tabContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(12f).toFloat()
                setColor(themeColors.surfaceVariant)
            }
            setPadding(LayoutHelper.dp(4), LayoutHelper.dp(4), LayoutHelper.dp(4), LayoutHelper.dp(4))
        }
        content.addView(tabContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        receivedTab = createTabButton(context, getString(R.string.friends_request_received)) {
            selectedTab = FRIEND_STATE_INVITE_RECEIVED
            updateTheme()
            reloadList()
        }
        sentTab = createTabButton(context, getString(R.string.friends_request_sent)) {
            selectedTab = FRIEND_STATE_INVITE_SENT
            updateTheme()
            reloadList()
        }
        tabContainer.addView(receivedTab, LinearLayout.LayoutParams(0, LayoutHelper.dp(34), 1f))
        tabContainer.addView(sentTab, LinearLayout.LayoutParams(0, LayoutHelper.dp(34), 1f))

        val listContainer = FrameLayout(context).apply {
            setPadding(0, LayoutHelper.dp(12), 0, 0)
        }
        content.addView(listContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        adapter = FriendRelationAdapter(context, themeColors) { friend, action ->
            handleAction(friend, action)
        }

        recyclerView = RecyclerListView(context).apply {
            layoutManager = LinearLayoutManager(context)
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            adapter = this@FriendRequestsFragment.adapter
        }
        listContainer.addView(recyclerView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        val emptyContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(LayoutHelper.dp(20), LayoutHelper.dp(30), LayoutHelper.dp(20), 0)
        }
        listContainer.addView(emptyContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP))

        emptyTitle = TextView(context).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(themeColors.onSurface)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        emptyContainer.addView(emptyTitle)

        emptyDescription = TextView(context).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(themeColors.onSurfaceVariant)
            gravity = Gravity.CENTER
            setPadding(0, LayoutHelper.dp(8), 0, 0)
        }
        emptyContainer.addView(emptyDescription)

        recyclerView.setEmptyView(emptyContainer)
        updateTheme()
        reloadList()
        return wrapWithActionBar(getString(R.string.friends_request_title), content)
    }

    private fun createTabButton(context: Context, text: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setOnClickListener { onClick() }
        }
    }

    private fun updateTheme() {
        val selectedText = themeColors.onPrimary
        val unselectedText = themeColors.onSurface
        val selectedBg = themeColors.primary
        val clearBg = android.graphics.Color.TRANSPARENT
        if (selectedTab == FRIEND_STATE_INVITE_RECEIVED) {
            receivedTab.setTextColor(selectedText)
            sentTab.setTextColor(unselectedText)
            receivedTab.background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(10f).toFloat()
                setColor(selectedBg)
            }
            sentTab.background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(10f).toFloat()
                setColor(clearBg)
            }
        } else {
            receivedTab.setTextColor(unselectedText)
            sentTab.setTextColor(selectedText)
            sentTab.background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(10f).toFloat()
                setColor(selectedBg)
            }
            receivedTab.background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(10f).toFloat()
                setColor(clearBg)
            }
        }
    }

    private fun reloadList() {
        val list = if (selectedTab == FRIEND_STATE_INVITE_RECEIVED) {
            incomingFriendRequestsForUi(
                friendController.receivedFriendRequests.value,
                friendController.friends.value
            )
        } else {
            sentFriendInvitesForUi(friendController.sentFriendRequests.value)
        }
        adapter.submitItems(list)
        val isReceived = selectedTab == FRIEND_STATE_INVITE_RECEIVED
        emptyTitle.text = getString(if (isReceived) R.string.friends_empty_received_title else R.string.friends_empty_sent_title)
        emptyDescription.text = getString(if (isReceived) R.string.friends_empty_received_description else R.string.friends_empty_sent_description)
    }

    private fun handleAction(friend: Friend, action: FriendRowAction) {
        when (action) {
            FriendRowAction.APPROVE -> {
                friendController.acceptFriendRequest(friend.user.id, friend.user.username) {}
            }
            FriendRowAction.DELETE -> {
                friendController.deleteFriendRelation(friend.user.id, friend.user.username) {}
            }
            FriendRowAction.OPEN_PROFILE -> {
                showUserInfo(friend)
            }
            else -> Unit
        }
    }

    private fun showUserInfo(friend: Friend) {
        val sheet = UserProfileBottomSheet(
            context = requireContext(),
            userId = friend.user.id,
            displayName = friend.user.displayName,
            username = friend.user.username,
            avatarUrl = friend.user.avatarUrl,
            isOwnProfile = false,
            isDM = true,
            listener = object : UserProfileBottomSheet.UserProfileListener {
                override fun onAddFriend(userId: Long) {
                    friendController.sendFriendRequest(userId, friend.user.username) {}
                }
                override fun onTransferFunds(userId: Long) {
                    openProfileTransferFunds(userId, friend.user.username)
                }
            }
        )
        sheet.setDrawNavigationBar(true)
        sheet.show()
    }
}
