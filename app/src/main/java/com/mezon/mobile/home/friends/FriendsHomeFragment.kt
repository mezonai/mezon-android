package com.mezon.mobile.home.friends

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import com.mezon.mezon.api.Friend
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.RecyclerListView
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.chat.UserProfileBottomSheet
import com.mezon.mobile.ui.cells.MezonIcon
import java.util.Locale

class FriendsHomeFragment : BaseFragment() {

    private lateinit var friendController: FriendController

    private lateinit var searchInput: EditText
    private lateinit var requestRow: LinearLayout
    private lateinit var requestCountText: TextView
    private lateinit var recyclerView: RecyclerListView
    private lateinit var emptyText: TextView
    private lateinit var adapter: FriendRelationAdapter

    private val debounceHandler = Handler(Looper.getMainLooper())
    private var query = ""
    private var allFriends = emptyList<Friend>()

    override fun onInject(entryPoint: FragmentEntryPoint) {
        friendController = entryPoint.friendController()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        observe(NotificationCenter.friendsLoaded) { _, _, _ ->
            if (fragmentView != null) {
                allFriends = friendController.friends.value
                reloadList()
            }
        }
        observe(NotificationCenter.themeChanged) { _, _, _ ->
            if (fragmentView != null) {
                applyTheme()
                adapter.notifyDataSetChanged()
            }
        }
        friendController.loadFriendRelations(noCache = true)
        return true
    }

    override fun createView(context: Context): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.background)
            setPadding(LayoutHelper.dp(16), LayoutHelper.dp(12), LayoutHelper.dp(16), LayoutHelper.dp(12))
        }

        val searchRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(12f).toFloat()
                setColor(themeColors.surfaceVariant)
            }
            setPadding(LayoutHelper.dp(12), LayoutHelper.dp(10), LayoutHelper.dp(12), LayoutHelper.dp(10))
        }
        root.addView(searchRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val searchIcon = ImageView(context).apply {
            setImageDrawable(MezonIcon.searchIcon.getDrawable(context))
            colorFilter = PorterDuffColorFilter(themeColors.onSurfaceVariant, PorterDuff.Mode.SRC_IN)
        }
        searchRow.addView(searchIcon, LayoutHelper.createLinear(18, 18, rightMargin = 8f))

        searchInput = EditText(context).apply {
            hint = getString(R.string.common_search_placeholder)
            setHintTextColor(themeColors.onSurfaceVariant)
            setTextColor(themeColors.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = null
            maxLines = 1
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    debounceHandler.removeCallbacksAndMessages(null)
                    debounceHandler.postDelayed({
                        query = s?.toString().orEmpty()
                        reloadList()
                    }, 500L)
                }
            })
        }
        searchRow.addView(searchInput, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))

        requestRow = buildRequestRow(context)
        root.addView(
            requestRow,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                topMargin = LayoutHelper.dp(12)
                bottomMargin = LayoutHelper.dp(12)
            }
        )

        val contentFrame = FrameLayout(context)
        root.addView(contentFrame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        recyclerView = RecyclerListView(context).apply {
            layoutManager = LinearLayoutManager(context)
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
        }
        contentFrame.addView(recyclerView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        emptyText = TextView(context).apply {
            text = getString(R.string.friends_no_results)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(themeColors.onSurfaceVariant)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        contentFrame.addView(emptyText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))
        recyclerView.setEmptyView(emptyText)

        adapter = FriendRelationAdapter(context, themeColors) { friend, action ->
            handleAction(friend, action)
        }
        recyclerView.adapter = adapter

        allFriends = friendController.friends.value
        applyTheme()
        reloadList()
        val wrapped = wrapWithActionBar(getString(R.string.friends_title), root)
        actionBar?.setMenuOnItemClick(object : com.mezon.mobile.ui.cells.ActionBarView.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                when (id) {
                    -1 -> finishFragment()
                    MENU_ADD_FRIEND -> presentFragment(AddFriendFragment())
                }
            }
        })
        actionBar?.createMenu()?.addItem(MENU_ADD_FRIEND, MezonIcon.userPlusIcon.resId)
        return wrapped
    }

    private fun buildRequestRow(context: Context): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(12f).toFloat()
                setColor(themeColors.surfaceVariant)
            }
            setPadding(LayoutHelper.dp(12), LayoutHelper.dp(12), LayoutHelper.dp(12), LayoutHelper.dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener { presentFragment(FriendRequestsFragment()) }
        }

        val icon = ImageView(context).apply {
            setImageDrawable(MezonIcon.paperPlaneIcon.getDrawable(context))
            colorFilter = PorterDuffColorFilter(themeColors.onSurface, PorterDuff.Mode.SRC_IN)
        }
        row.addView(icon, LayoutHelper.createLinear(22, 22, rightMargin = 10f))

        val textWrap = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        row.addView(textWrap, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))

        val title = TextView(context).apply {
            text = getString(R.string.friends_request_title)
            setTextColor(themeColors.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }
        textWrap.addView(title)

        requestCountText = TextView(context).apply {
            setTextColor(themeColors.onSurfaceVariant)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        textWrap.addView(requestCountText)

        val chevron = ImageView(context).apply {
            setImageDrawable(MezonIcon.chevronSmallRightIcon.getDrawable(context))
            colorFilter = PorterDuffColorFilter(themeColors.onSurfaceVariant, PorterDuff.Mode.SRC_IN)
        }
        row.addView(chevron, LayoutHelper.createLinear(16, 16))

        return row
    }

    private fun applyTheme() {
        requestRow.background = GradientDrawable().apply {
            cornerRadius = LayoutHelper.dp(12f).toFloat()
            setColor(themeColors.surfaceVariant)
        }
        emptyText.setTextColor(themeColors.onSurfaceVariant)
        requestCountText.setTextColor(themeColors.onSurfaceVariant)
    }

    private fun reloadList() {
        val normalized = query.trim().lowercase(Locale.getDefault())
        val filtered = allFriends
            .filter { it.state == FRIEND_STATE_FRIEND }
            .filter {
                if (normalized.isEmpty()) {
                    true
                } else {
                    it.user.displayName.lowercase(Locale.getDefault()).contains(normalized) ||
                        it.user.username.lowercase(Locale.getDefault()).contains(normalized)
                }
            }
            .sortedBy { it.user.displayName.ifBlank { it.user.username }.lowercase(Locale.getDefault()) }

        adapter.submitItems(filtered)

        val receivedCount = incomingFriendRequestsForUi(
            friendController.receivedFriendRequests.value,
            friendController.friends.value
        ).size
        val sentCount = sentFriendInvitesForUi(friendController.sentFriendRequests.value).size
        requestCountText.text = getString(R.string.friends_request_counts, receivedCount, sentCount)
        val shouldShowRequest = normalized.isEmpty() || filtered.isEmpty()
        requestRow.visibility = if (shouldShowRequest) View.VISIBLE else View.GONE
        emptyText.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun handleAction(friend: Friend, action: FriendRowAction) {
        when (action) {
            FriendRowAction.CALL -> {
                Toast.makeText(requireContext(), getString(R.string.feature_coming_soon), Toast.LENGTH_SHORT).show()
            }
            FriendRowAction.MESSAGE -> {
                Toast.makeText(requireContext(), getString(R.string.feature_coming_soon), Toast.LENGTH_SHORT).show()
            }
            FriendRowAction.OPEN_PROFILE -> {
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
                    }
                )
                sheet.setDrawNavigationBar(true)
                sheet.show()
            }
            else -> Unit
        }
    }

    companion object {
        private const val MENU_ADD_FRIEND = 1001
    }
}
