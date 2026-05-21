package com.mezon.mobile.home.messages

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
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
import com.mezon.mobile.home.friends.FRIEND_STATE_FRIEND
import com.mezon.mobile.home.friends.FriendController
import com.mezon.mobile.home.profile.UserController
import com.mezon.mobile.network.CHANNEL_TYPE_DM
import com.mezon.mobile.network.CHANNEL_TYPE_GROUP
import com.mezon.mobile.search.LOCAL_PAGE_SIZE
import com.mezon.mobile.search.SearchController
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class NewGroupFragment : BaseFragment() {

    companion object {
        private const val ARG_MODE = "mode"
        private const val ARG_CHANNEL_ID = "channelId"
        private const val ARG_CHANNEL_NAME = "channelName"
        private const val MODE_CREATE = 0
        private const val MODE_ADD_MEMBERS = 1
        private const val LOAD_MORE_THRESHOLD = 10

        fun newCreate(): NewGroupFragment = NewGroupFragment().apply {
            arguments = Bundle().apply { putInt(ARG_MODE, MODE_CREATE) }
        }

        fun newAddMembers(channelId: Long, channelName: String): NewGroupFragment = NewGroupFragment().apply {
            arguments = Bundle().apply {
                putInt(ARG_MODE, MODE_ADD_MEMBERS)
                putLong(ARG_CHANNEL_ID, channelId)
                putString(ARG_CHANNEL_NAME, channelName)
            }
        }
    }

    private lateinit var friendController: FriendController
    private lateinit var dialogsController: DialogsController
    private lateinit var userController: UserController
    private lateinit var recyclerView: RecyclerListView
    private lateinit var adapter: FriendPickerAdapter
    private lateinit var emptyView: TextView
    private lateinit var actionText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var loadingOverlay: FrameLayout
    private lateinit var rootFrame: FrameLayout

    private var mode = MODE_CREATE
    private var channelId = 0L
    private var channelName = ""
    private var searchText = ""
    private var allFriends = emptyList<Friend>()
    private var filteredFriends = emptyList<Friend>()
    private var friendById = emptyMap<Long, Friend>()
    private var friendsDisplayLimit = LOCAL_PAGE_SIZE
    private var isLoadingMoreFriends = false
    private var selectedIds = emptySet<Long>()
    private var defaultSelectedIds = emptySet<Long>()
    private var submitting = false

    var onOpenChat: ((channelId: Long, channelName: String, clanId: Long, channelType: Int) -> Unit)? = null

    override fun onInject(entryPoint: FragmentEntryPoint) {
        friendController = entryPoint.friendController()
        dialogsController = entryPoint.dialogsController()
        userController = entryPoint.userController()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        mode = arguments?.getInt(ARG_MODE) ?: MODE_CREATE
        channelId = arguments?.getLong(ARG_CHANNEL_ID) ?: 0L
        channelName = arguments?.getString(ARG_CHANNEL_NAME).orEmpty()

        observe(NotificationCenter.friendsLoaded) { _, _, _ ->
            if (isPaused || fragmentView == null) return@observe
            reloadFriends()
        }
        observe(NotificationCenter.userDataLoaded) { _, _, _ ->
            if (isPaused || fragmentView == null || mode != MODE_CREATE) return@observe
            syncDefaultSelectedIds()
        }
        observe(NotificationCenter.channelMembersDidLoad) { _, _, args ->
            if (isPaused || fragmentView == null || mode != MODE_ADD_MEMBERS) return@observe
            val changedChannelId = args.firstOrNull() as? Long ?: return@observe
            if (changedChannelId == channelId) syncDefaultSelectedIds()
        }
        observe(NotificationCenter.themeChanged) { _, _, _ ->
            if (isPaused || fragmentView == null) return@observe
            fragmentView?.setBackgroundColor(themeColors.serverRailBg)
            if (::adapter.isInitialized) adapter.notifyDataSetChanged()
        }

        friendController.loadFriendRelations(noCache = true)
        if (mode == MODE_ADD_MEMBERS) dialogsController.loadDmParticipants(channelId, force = true)
        return true
    }

    override fun createView(context: Context): View {
        rootFrame = FrameLayout(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.serverRailBg)
        }
        rootFrame.addView(root, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))
        root.addView(buildHeader(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(18), LayoutHelper.dp(18), LayoutHelper.dp(18), 0)
        }
        root.addView(content, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        content.addView(buildSearchRow(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40, bottomMargin = 10f))

        val frame = FrameLayout(context)
        content.addView(frame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        recyclerView = RecyclerListView(context).apply {
            layoutManager = LinearLayoutManager(context)
            setHasFixedSize(true)
            itemAnimator = null
        }
        adapter = FriendPickerAdapter(
            context = context,
            themeColors = themeColors,
            selectMode = true,
            onFriendClick = {},
            onSelectionChanged = { ids ->
                selectedIds = ids.toSet()
                updateHeaderState()
            }
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
        frame.addView(recyclerView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        emptyView = TextView(context).apply {
            text = getString(R.string.dm_no_friends)
            setTextColor(themeColors.onSurfaceVariant)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        frame.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        reloadFriends()
        syncDefaultSelectedIds()

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

        fragmentView = rootFrame
        return rootFrame
    }

    private fun buildHeader(context: Context): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, AndroidUtilities.statusBarHeight, 0, 0)
            setBackgroundColor(themeColors.serverRailBg)
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(14), LayoutHelper.dp(14), LayoutHelper.dp(14), LayoutHelper.dp(14))
        }
        container.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val back = FrameLayout(context).apply {
            isClickable = true
            isFocusable = true
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
            if (outValue.resourceId != 0) {
                foreground = ContextCompat.getDrawable(context, outValue.resourceId)
            }
            setOnClickListener { finishFragment() }
        }
        val backIcon = ImageView(context).apply {
            val d = MezonIcon.arrowLargeLeftIcon.getDrawable(context)
            d.colorFilter = PorterDuffColorFilter(themeColors.colorText, PorterDuff.Mode.SRC_IN)
            setImageDrawable(d)
        }
        back.addView(backIcon, LayoutHelper.createFrame(20, 20, Gravity.CENTER_VERTICAL or Gravity.START))
        row.addView(back, LayoutHelper.createLinear(70, 33))

        val titleColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val title = TextView(context).apply {
            text = if (mode == MODE_ADD_MEMBERS) getString(R.string.dm_add_members) else getString(R.string.dm_new_group)
            setTextColor(themeColors.colorText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        titleColumn.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))
        subtitleText = TextView(context).apply {
            setTextColor(rnTextDisabled())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            includeFontPadding = false
            setPadding(0, LayoutHelper.dp(4), 0, 0)
        }
        titleColumn.addView(subtitleText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))
        row.addView(titleColumn, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))

        val actionWrap = FrameLayout(context)
        actionText = TextView(context).apply {
            text = if (mode == MODE_ADD_MEMBERS) getString(R.string.dm_add) else getString(R.string.dm_create)
            setTextColor(themeColors.blurple)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER or Gravity.END
            includeFontPadding = false
            setOnClickListener { submit() }
        }
        actionWrap.addView(actionText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))
        row.addView(actionWrap, LayoutHelper.createLinear(70, 33))

        return container
    }

    private fun buildSearchRow(context: Context): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(12), 0, LayoutHelper.dp(12), 0)
            background = roundedBg(themeColors.secondaryInputBackground, 40f)
        }
        val searchIconSlot = FrameLayout(context)
        val searchIcon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setImageResource(R.drawable.ic_magnifying)
            colorFilter = PorterDuffColorFilter(themeColors.colorText, PorterDuff.Mode.SRC_IN)
        }
        searchIconSlot.addView(searchIcon, LayoutHelper.createFrame(18, 18, Gravity.CENTER))
        row.addView(searchIconSlot, LayoutHelper.createLinear(18, LayoutHelper.MATCH_PARENT, rightMargin = 5f))

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
            setPadding(0, 0, 0, 0)
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
        return row
    }

    private fun reloadFriends() {
        allFriends = friendController.friends.value
            .filter { it.state == FRIEND_STATE_FRIEND }
            .sortedBy { friend ->
                friend.user.displayName.ifBlank { friend.user.username }.lowercase(Locale.getDefault())
            }
        friendById = allFriends.associateBy { it.user.id }
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

    private fun syncDefaultSelectedIds() {
        if (!::adapter.isInitialized) return
        val ids = if (mode == MODE_ADD_MEMBERS) {
            dialogsController.getParticipants(channelId)
                .map { it.userId }
                .filter { it != 0L }
                .toSet()
        } else {
            userController.userId.takeIf { it != 0L }?.let { setOf(it) } ?: emptySet()
        }
        if (ids == defaultSelectedIds) {
            updateHeaderState()
            return
        }
        defaultSelectedIds = ids
        adapter.setDefaultSelected(ids)
        selectedIds = adapter.getSelectedIds().toSet()
        updateHeaderState()
    }

    private fun updateHeaderState() {
        if (!::actionText.isInitialized || !::subtitleText.isInitialized) return
        val memberCount = selectedIds.size
        subtitleText.text = getString(R.string.dm_group_members, memberCount, FriendPickerAdapter.GROUP_CHAT_MAX_MEMBERS)
        val enabled = newMemberIds().isNotEmpty() && !submitting
        actionText.isEnabled = enabled
        actionText.alpha = if (enabled) 1f else 0.6f
    }

    private fun submit() {
        if (submitting) return
        val ids = newMemberIds()
        if (ids.isEmpty()) return
        submitting = true
        setSubmittingLoading(true)
        updateHeaderState()
        fragmentScope.launch {
            if (mode == MODE_ADD_MEMBERS) {
                val success = dialogsController.addMembersToGroup(channelId, ids, buildParticipantHints(ids))
                withContext(Dispatchers.Main) {
                    submitting = false
                    setSubmittingLoading(false)
                    updateHeaderState()
                    if (success) {
                        finishFragment()
                    } else {
                        showToast(R.string.dm_add_members_failed)
                    }
                }
                return@launch
            }

            if (ids.size == 1) {
                val friend = friendById[ids.first()]
                val dmChannelId = dialogsController.getOrCreateDm(ids.first())
                withContext(Dispatchers.Main) {
                    submitting = false
                    setSubmittingLoading(false)
                    updateHeaderState()
                    if (dmChannelId == 0L || friend == null) {
                        showToast(R.string.dm_new_dm_failed)
                    } else {
                        val name = friend.user.displayName.ifBlank { friend.user.username }
                        openChatFromPicker(
                            dmChannelId,
                            name,
                            0L,
                            CHANNEL_TYPE_DM,
                            popSelfBeforeOpen = true,
                            onOpenChat = onOpenChat
                        )
                    }
                }
                return@launch
            }

            val group = dialogsController.createGroup(ids, buildParticipantHints(selectedIds.toList()))
            withContext(Dispatchers.Main) {
                submitting = false
                setSubmittingLoading(false)
                updateHeaderState()
                if (group == null) {
                    showToast(R.string.dm_create_group_failed)
                } else {
                    openChatFromPicker(
                        group.channelId,
                        group.displayName.ifBlank { group.label },
                        0L,
                        CHANNEL_TYPE_GROUP,
                        popSelfBeforeOpen = true,
                        onOpenChat = onOpenChat
                    )
                }
            }
        }
    }

    private fun setSubmittingLoading(loading: Boolean) {
        if (!::loadingOverlay.isInitialized) return
        loadingOverlay.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun buildParticipantHints(ids: List<Long>): List<DmParticipant> {
        val result = ArrayList<DmParticipant>(ids.size)
        for (id in ids.distinct()) {
            if (id == userController.userId) {
                result.add(DmParticipant(
                    userId = id,
                    username = userController.username,
                    displayName = userController.displayName.ifBlank { userController.username },
                    avatarUrl = userController.avatarUrl
                ))
                continue
            }
            val friend = friendById[id] ?: continue
            result.add(DmParticipant(
                userId = id,
                username = friend.user.username,
                displayName = friend.user.displayName.ifBlank { friend.user.username },
                avatarUrl = friend.user.avatarUrl
            ))
        }
        return result
    }

    private fun newMemberIds(): List<Long> =
        selectedIds.filter { it != 0L && it !in defaultSelectedIds }

    private fun showToast(messageRes: Int) {
        val ctx = fragmentView?.context ?: return
        Toast.makeText(ctx, getString(messageRes), Toast.LENGTH_SHORT).show()
    }

    private fun normalize(value: String): String =
        SearchController.removeDiacritics(value.trim().lowercase(Locale.getDefault()))

    private fun rnTextDisabled(): Int = when (themeColors.resolvedMode) {
        ThemeMode.LIGHT -> 0xFF606065.toInt()
        ThemeMode.ABYSS -> 0xFFBDB4DC.toInt()
        else -> 0xFF7B7B83.toInt()
    }

    private fun roundedBg(color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = LayoutHelper.dpf(radiusDp)
        }
}
