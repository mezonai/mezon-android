package com.mezon.mobile.home.friends

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import com.mezon.mezon.api.Friend
import com.mezon.mobile.R
import com.mezon.mobile.MainActivity
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BottomSheet
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.RecyclerListView
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.chat.UserProfileBottomSheet
import com.mezon.mobile.home.wallet.SendTokenFragment
import com.mezon.mobile.ui.cells.MezonIcon

class AddFriendBottomSheet(
    context: android.content.Context,
    private val friendController: FriendController,
    private val notificationCenter: NotificationCenter
) : BottomSheet(context) {

    private val themeColors = ThemeColors.instance

    private lateinit var recyclerView: RecyclerListView
    private lateinit var emptyTitle: TextView
    private lateinit var emptyDescription: TextView
    private lateinit var incomingTitle: TextView
    private lateinit var adapter: FriendRelationAdapter

    private val friendsLoadedObserver = object : NotificationCenter.NotificationCenterDelegate {
        override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
            reloadData()
        }
    }

    private val themeObserver = object : NotificationCenter.NotificationCenterDelegate {
        override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
            adapter.notifyDataSetChanged()
        }
    }

    init {
        containerHeight = (AndroidUtilities.displaySize.y * 0.88f).toInt()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTitle(context.getString(R.string.friends_add_screen_title))

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.background)
            setPadding(LayoutHelper.dp(16), LayoutHelper.dp(4), LayoutHelper.dp(16), LayoutHelper.dp(12))
        }

        val addByUsernameRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(14), LayoutHelper.dp(12), LayoutHelper.dp(14), LayoutHelper.dp(12))
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(12f).toFloat()
                setColor(themeColors.surfaceVariant)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { openAddByIdentifierModal() }
        }
        content.addView(addByUsernameRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val addLabel = TextView(context).apply {
            text = context.getString(R.string.friends_add_by_username)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(themeColors.onSurface)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }
        addByUsernameRow.addView(addLabel, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))

        val chevron = ImageView(context).apply {
            setImageDrawable(MezonIcon.chevronSmallRightIcon.getDrawable(context))
            setColorFilter(themeColors.onSurfaceVariant)
        }
        addByUsernameRow.addView(chevron, LayoutHelper.createLinear(18, 18))

        incomingTitle = TextView(context).apply {
            text = context.getString(R.string.friends_add_incoming_friend_request)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(themeColors.onSurface)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            visibility = View.GONE
        }
        content.addView(
            incomingTitle,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                topMargin = LayoutHelper.dp(14)
                bottomMargin = LayoutHelper.dp(6)
            }
        )

        val listContainer = FrameLayout(context)
        content.addView(listContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        adapter = FriendRelationAdapter(context, themeColors) { friend, action ->
            when (action) {
                FriendRowAction.DELETE -> friendController.deleteFriendRelation(friend.user.id, friend.user.username) {}
                FriendRowAction.APPROVE -> friendController.acceptFriendRequest(friend.user.id, friend.user.username) {}
                FriendRowAction.OPEN_PROFILE -> showUserInfo(friend)
                else -> Unit
            }
        }

        recyclerView = RecyclerListView(context).apply {
            layoutManager = LinearLayoutManager(context)
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            adapter = this@AddFriendBottomSheet.adapter
        }
        listContainer.addView(recyclerView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        val emptyContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(LayoutHelper.dp(20), LayoutHelper.dp(30), LayoutHelper.dp(20), 0)
        }
        listContainer.addView(emptyContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP))

        emptyTitle = TextView(context).apply {
            text = context.getString(R.string.friends_empty_received_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(themeColors.onSurface)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        emptyContainer.addView(emptyTitle)

        emptyDescription = TextView(context).apply {
            text = context.getString(R.string.friends_empty_received_description)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(themeColors.onSurfaceVariant)
            gravity = Gravity.CENTER
            setPadding(0, LayoutHelper.dp(8), 0, 0)
        }
        emptyContainer.addView(emptyDescription)

        recyclerView.setEmptyView(emptyContainer)

        setCustomView(content)
        super.onCreate(savedInstanceState)

        setOnDismissListener {
            notificationCenter.removeObserver(friendsLoadedObserver, NotificationCenter.friendsLoaded)
            notificationCenter.removeObserver(themeObserver, NotificationCenter.themeChanged)
        }
        notificationCenter.addObserver(friendsLoadedObserver, NotificationCenter.friendsLoaded)
        notificationCenter.addObserver(themeObserver, NotificationCenter.themeChanged)
        friendController.loadFriendRelations(noCache = true)
        reloadData()
    }

    private fun reloadData() {
        val incoming = incomingFriendRequestsForUi(
            friendController.receivedFriendRequests.value,
            friendController.friends.value
        )
        adapter.submitItems(incoming)
        incomingTitle.visibility = if (incoming.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun openAddByIdentifierModal() {
        AddFriendIdentifierModal(
            context = context,
            friendController = friendController,
            currentUsername = friendController.currentUsername
        ).show()
    }

    private fun showUserInfo(friend: Friend) {
        val sheet = UserProfileBottomSheet(
            context = context,
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
                    SendTokenFragment.presentForProfile(
                        context,
                        (AndroidUtilities.findActivity(context) as? MainActivity)?.actionBarLayout,
                        userId,
                        friend.user.username
                    )
                }
            }
        )
        sheet.setDrawNavigationBar(true)
        sheet.show()
    }
}
