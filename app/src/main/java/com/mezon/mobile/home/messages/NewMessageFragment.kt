package com.mezon.mobile.home.messages

import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.View
import androidx.core.content.ContextCompat
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mezon.api.Friend
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.RecyclerListView
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.DialogsController
import com.mezon.mobile.home.friends.AddFriendFragment
import com.mezon.mobile.home.friends.FRIEND_STATE_FRIEND
import com.mezon.mobile.home.friends.FriendController
import com.mezon.mobile.network.CHANNEL_TYPE_DM
import com.mezon.mobile.search.LOCAL_PAGE_SIZE
import com.mezon.mobile.search.SearchController
import com.mezon.mobile.ui.cells.ActionBarView
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class NewMessageFragment : BaseFragment() {

    companion object {
        private const val LOAD_MORE_THRESHOLD = 10
    }

    private lateinit var friendController: FriendController
    private lateinit var dialogsController: DialogsController
    private lateinit var recyclerView: RecyclerListView
    private lateinit var adapter: FriendPickerAdapter
    private lateinit var emptyView: TextView
    private var allFriends = emptyList<Friend>()
    private var filteredFriends = emptyList<Friend>()
    private var searchText = ""
    private var friendsDisplayLimit = LOCAL_PAGE_SIZE
    private var isLoadingMoreFriends = false
    private var creatingDm = false
    private lateinit var loadingOverlay: FrameLayout
    private lateinit var rootFrame: FrameLayout

    var onOpenChat: ((channelId: Long, channelName: String, clanId: Long, channelType: Int) -> Unit)? = null

    override fun onInject(entryPoint: FragmentEntryPoint) {
        friendController = entryPoint.friendController()
        dialogsController = entryPoint.dialogsController()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        observe(NotificationCenter.friendsLoaded) { _, _, _ ->
            if (isPaused || fragmentView == null) return@observe
            reloadFriends()
        }
        observe(NotificationCenter.themeChanged) { _, _, _ ->
            if (isPaused || fragmentView == null) return@observe
            fragmentView?.setBackgroundColor(themeColors.serverRailBg)
            if (::adapter.isInitialized) adapter.notifyDataSetChanged()
        }
        friendController.loadFriendRelations(noCache = true)
        return true
    }

    override fun createView(context: Context): View {
        rootFrame = FrameLayout(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.serverRailBg)
        }
        rootFrame.addView(root, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        actionBar = ActionBarView(context, themeColors).apply {
            setBackClickListener { finishFragment() }
            setTitle(getString(R.string.screen_header_new_message))
            setCenterTitle(true)
            setTitleColor(themeColors.colorText)
            setItemsColor(themeColors.colorText)
            setBackgroundColor(themeColors.serverRailBg)
        }
        root.addView(actionBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56))

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(18), LayoutHelper.dp(18), LayoutHelper.dp(18), 0)
        }
        root.addView(content, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        content.addView(buildSearchRow(context), LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT, 40, bottomMargin = 18f
        ))
        content.addView(buildActionsCard(context), LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, bottomMargin = 18f
        ))

        val listFrame = FrameLayout(context)
        content.addView(listFrame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        recyclerView = RecyclerListView(context).apply {
            layoutManager = LinearLayoutManager(context)
            setHasFixedSize(true)
            itemAnimator = null
        }
        adapter = FriendPickerAdapter(
            context = context,
            themeColors = themeColors,
            selectMode = false,
            onFriendClick = { openDm(it) },
            onSelectionChanged = {}
        )
        recyclerView.adapter = adapter
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || isLoadingMoreFriends) return
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                val lastVisible = lm.findLastVisibleItemPosition()
                if (lastVisible >= adapter.itemCount - LOAD_MORE_THRESHOLD) {
                    loadMoreFriends()
                }
            }
        })
        listFrame.addView(recyclerView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        emptyView = TextView(context).apply {
            text = getString(R.string.dm_no_friends)
            setTextColor(themeColors.onSurfaceVariant)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        listFrame.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        loadingOverlay = FrameLayout(context).apply {
            setBackgroundColor(0x66000000)
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            addView(ProgressBar(context), LayoutHelper.createFrame(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER
            ))
        }
        rootFrame.addView(loadingOverlay, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        reloadFriends()
        fragmentView = rootFrame
        return rootFrame
    }

    private fun buildSearchRow(context: Context): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(12), 0, LayoutHelper.dp(12), 0)
            background = roundedBg(themeColors.secondaryInputBackground, 40f)
        }
        val label = TextView(context).apply {
            text = getString(R.string.dm_new_message_to)
            setTextColor(themeColors.colorText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(label, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT))

        val input = EditText(context).apply {
            hint = getString(R.string.common_search_placeholder)
            setHintTextColor(rnTextDisabled())
            setTextColor(themeColors.textStrong)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = null
            isSingleLine = true
            maxLines = 1
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            minHeight = 0
            minimumHeight = 0
            setPadding(LayoutHelper.dp(6), 0, 0, 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    searchText = s?.toString().orEmpty()
                    applyFilter()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        row.addView(input, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f))
        row.post {
            input.requestFocus()
            AndroidUtilities.showKeyboard(input)
        }
        return row
    }

    private fun buildActionsCard(context: Context): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipToOutline = true
            background = roundedBg(themeColors.secondaryInputBackground, 12f)
        }
        card.addView(buildActionRow(
            context = context,
            icon = MezonIcon.userGroupIcon,
            iconBg = themeColors.blurple,
            title = getString(R.string.dm_new_group),
            onClick = { openNewGroup() }
        ))
        card.addView(
            View(context).apply { setBackgroundColor(themeColors.tertiary) },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT.toFloat(), 1.5f)
        )
        card.addView(buildActionRow(
            context = context,
            icon = MezonIcon.userIcon,
            iconBg = 0xFFE148C7.toInt(),
            title = getString(R.string.dm_add_friend),
            onClick = { presentFragment(AddFriendFragment()) }
        ))
        return card
    }

    private fun buildActionRow(
        context: Context,
        icon: MezonIcon,
        iconBg: Int,
        title: String,
        onClick: () -> Unit
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(10), LayoutHelper.dp(10), LayoutHelper.dp(10), LayoutHelper.dp(10))
            isClickable = true
            isFocusable = true
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            if (outValue.resourceId != 0) {
                foreground = ContextCompat.getDrawable(context, outValue.resourceId)
            }
            setOnClickListener { onClick() }
        }

        val circle = FrameLayout(context).apply {
            background = roundedBg(iconBg, 50f, oval = true)
        }
        val iconView = ImageView(context).apply {
            val d = icon.getDrawable(context)
            d.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            setImageDrawable(d)
        }
        circle.addView(iconView, LayoutHelper.createFrame(20, 20, Gravity.CENTER))
        row.addView(circle, LayoutHelper.createLinear(36, 36, gravity = Gravity.CENTER_VERTICAL, rightMargin = 12f))

        val titleView = TextView(context).apply {
            text = title
            setTextColor(themeColors.colorText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(titleView, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f, Gravity.CENTER_VERTICAL))

        val chevron = ImageView(context).apply {
            val d = MezonIcon.chevronSmallRightIcon.getDrawable(context)
            d.colorFilter = PorterDuffColorFilter(themeColors.colorText, PorterDuff.Mode.SRC_IN)
            setImageDrawable(d)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        row.addView(chevron, LayoutHelper.createLinear(15, 15, gravity = Gravity.CENTER_VERTICAL))
        return row
    }

    private fun openNewGroup() {
        val fragment = NewGroupFragment.newCreate().apply {
            onOpenChat = this@NewMessageFragment.onOpenChat
        }
        presentFragment(fragment)
    }

    private fun openDm(friend: Friend) {
        if (creatingDm) return
        creatingDm = true
        setCreatingLoading(true)
        fragmentScope.launch {
            val channelId = dialogsController.getOrCreateDm(friend.user.id)
            withContext(Dispatchers.Main) {
                creatingDm = false
                setCreatingLoading(false)
                if (channelId == 0L) {
                    showToast(R.string.dm_new_dm_failed)
                    return@withContext
                }
                val name = friend.user.displayName.ifBlank { friend.user.username }
                openChatFromPicker(
                    channelId,
                    name,
                    0L,
                    CHANNEL_TYPE_DM,
                    onOpenChat = onOpenChat
                )
            }
        }
    }

    private fun setCreatingLoading(loading: Boolean) {
        if (!::loadingOverlay.isInitialized) return
        loadingOverlay.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun showToast(messageRes: Int) {
        val ctx = fragmentView?.context ?: return
        Toast.makeText(ctx, getString(messageRes), Toast.LENGTH_SHORT).show()
    }

    private fun reloadFriends() {
        allFriends = friendController.friends.value
            .filter { it.state == FRIEND_STATE_FRIEND }
            .sortedBy { friend ->
                friend.user.displayName.ifBlank { friend.user.username }.lowercase(Locale.getDefault())
            }
        applyFilter()
    }

    private fun applyFilter(resetPage: Boolean = true) {
        if (!::adapter.isInitialized) return
        val query = normalize(searchText)
        if (resetPage) {
            friendsDisplayLimit = LOCAL_PAGE_SIZE
        }
        filteredFriends = if (query.isEmpty()) {
            allFriends
        } else {
            allFriends.filter {
                normalize(it.user.username).contains(query) ||
                    normalize(it.user.displayName).contains(query)
            }
        }
        val page = filteredFriends.take(friendsDisplayLimit)
        adapter.submitFriends(page, query.isNotEmpty())
        emptyView.visibility = if (filteredFriends.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (filteredFriends.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun loadMoreFriends() {
        if (isLoadingMoreFriends || friendsDisplayLimit >= filteredFriends.size) return
        isLoadingMoreFriends = true
        friendsDisplayLimit += LOCAL_PAGE_SIZE
        applyFilter(resetPage = false)
        isLoadingMoreFriends = false
    }

    private fun normalize(value: String): String =
        SearchController.removeDiacritics(value.trim().lowercase(Locale.getDefault()))

    private fun rnTextDisabled(): Int = when (themeColors.resolvedMode) {
        ThemeMode.LIGHT -> 0xFF606065.toInt()
        ThemeMode.ABYSS -> 0xFFBDB4DC.toInt()
        else -> 0xFF7B7B83.toInt()
    }

    private fun roundedBg(color: Int, radiusDp: Float, oval: Boolean = false): GradientDrawable =
        GradientDrawable().apply {
            shape = if (oval) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = LayoutHelper.dpf(radiusDp)
        }

}
