package com.mezon.mobile.search

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.PopupWindow
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mezon.api.SearchMessageDocument
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.RecyclerListView
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.MainActivity
import com.mezon.mobile.home.ClanMember
import com.mezon.mobile.home.DialogsController
import com.mezon.mobile.home.MemberResolver
import com.mezon.mobile.home.UserClanController
import com.mezon.mobile.home.clans.ClanChannelEntity
import com.mezon.mobile.home.clans.CHANNEL_TYPE_STREAMING
import com.mezon.mobile.home.clans.VoiceMemberDisplay
import com.mezon.mobile.home.voice.JoinVoiceBottomSheet
import com.mezon.mobile.home.voice.VoiceController
import com.mezon.mobile.home.stream.JoinMediaSheetKind
import com.mezon.mobile.home.stream.StreamingController
import com.mezon.mobile.ui.cells.ChannelSearchCell
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.cells.ProfileSearchCell
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.network.CHANNEL_TYPE_DM
import com.mezon.mobile.ui.cells.SearchCell
import com.mezon.mobile.ui.cells.SearchTabHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private enum class MessageUserFilter {
    FROM,
    MENTIONS
}

class GlobalSearchFragment : BaseFragment() {

    companion object {
        const val TAB_MEMBERS = 0
        const val TAB_CHANNELS = 1
        const val TAB_MESSAGES = 2
        private const val DEBOUNCE_MS = 300L
        private const val LOAD_MORE_THRESHOLD = 10
        private val HEADER_PAD_H = LayoutHelper.dp(16f)
        private val HEADER_PAD_V = LayoutHelper.dp(10f)
        private val BACK_BUTTON_SIZE = LayoutHelper.dp(44f)
        private val BACK_BUTTON_MARGIN_END = LayoutHelper.dp(0f)
        private val FILTER_ICON_SIZE = LayoutHelper.dp(20f)
        private val FILTER_BUTTON_PADDING = LayoutHelper.dp(10f)
        private val FILTER_BUTTON_MARGIN_START = LayoutHelper.dp(8f)

        private const val ARG_FILTER_CHANNEL_ID = "filterChannelId"
        private const val ARG_FILTER_CHANNEL_NAME = "filterChannelName"
        private const val ARG_CLAN_ID = "clanId"
        private const val ARG_CHANNEL_TYPE = "channelType"

        fun newInstance(
            filterChannelId: Long,
            filterChannelName: String,
            clanId: Long = 0L,
            channelType: Int = 0
        ): GlobalSearchFragment {
            return GlobalSearchFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_FILTER_CHANNEL_ID, filterChannelId)
                    putString(ARG_FILTER_CHANNEL_NAME, filterChannelName)
                    putLong(ARG_CLAN_ID, clanId)
                    putInt(ARG_CHANNEL_TYPE, channelType)
                }
            }
        }
    }

    private lateinit var searchController: SearchController
    private var memberResolver: MemberResolver? = null
    private lateinit var dialogsController: DialogsController
    private lateinit var voiceController: VoiceController
    private lateinit var streamingController: StreamingController
    private lateinit var userClanController: UserClanController

    private lateinit var searchCell: SearchCell
    private lateinit var tabHeader: SearchTabHeader
    private lateinit var recyclerView: RecyclerListView
    private lateinit var loadingView: ProgressBar
    private lateinit var emptyView: TextView
    private lateinit var adapter: GlobalSearchAdapter
    private var filterButton: ImageView? = null

    private var channelPickerView: RecyclerListView? = null
    private var channelPickerAdapter: ChannelPickerAdapter? = null

    private var contentFrame: FrameLayout? = null
    private var currentTab = TAB_MEMBERS
    private var searchText = ""
    private val handler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null
    private var membersDisplayLimit = LOCAL_PAGE_SIZE
    private var channelsDisplayLimit = LOCAL_PAGE_SIZE
    private var isLoadingMore = false
    private var filterChannelId = 0L
    private var filterChannelName = ""
    private var hideChannelsTab = false
    private var visibleTabs = listOf(TAB_MEMBERS, TAB_CHANNELS, TAB_MESSAGES)
    private var argClanId = 0L
    private var argChannelType = 0
    private var channelScopedMembers: List<SearchMember>? = null
    private var membersRequested = false
    private var channelsRequested = false
    private var channelsFirstLoadPending = false
    private var isChannelPickerMode = false
    private var pickerQuery = ""
    private var pickerDisplayLimit = LOCAL_PAGE_SIZE
    private var pickerFilterRunnable: Runnable? = null
    private var activeUserFilter: MessageUserFilter? = null
    private var filterUser: SearchMember? = null
    private var isPickingFilterUser = false

    var onOpenChat: ((channelId: Long, channelName: String, clanId: Long, channelType: Int) -> Unit)? = null

    override fun onInject(entryPoint: FragmentEntryPoint) {
        searchController = entryPoint.searchController()
        memberResolver = entryPoint.memberResolver()
        dialogsController = entryPoint.dialogsController()
        voiceController = entryPoint.voiceController()
        streamingController = entryPoint.streamingController()
        userClanController = entryPoint.userClanController()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()

        val argChannelId = arguments?.getLong(ARG_FILTER_CHANNEL_ID) ?: 0L
        val argChannelName = arguments?.getString(ARG_FILTER_CHANNEL_NAME) ?: ""
        argClanId = arguments?.getLong(ARG_CLAN_ID) ?: 0L
        argChannelType = arguments?.getInt(ARG_CHANNEL_TYPE) ?: 0
        if (argChannelId != 0L) {
            filterChannelId = argChannelId
            filterChannelName = argChannelName
            hideChannelsTab = true
            buildChannelScopedMembers()
        }

        visibleTabs = if (hideChannelsTab) {
            listOf(TAB_MEMBERS, TAB_MESSAGES)
        } else {
            listOf(TAB_MEMBERS, TAB_CHANNELS, TAB_MESSAGES)
        }

        observe(NotificationCenter.searchMembersDidLoad) { _, _, _ ->
            if (fragmentView == null || isPaused) return@observe
            if (channelScopedMembers != null) return@observe
            if (currentTab == TAB_MEMBERS) {
                loadingView.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
                updateMembersList()
            }
            updateTabCounts()
        }
        observe(NotificationCenter.userClansDidLoad) { _, _, _ ->
            if (fragmentView == null || isPaused) return@observe
            if (channelScopedMembers != null) return@observe
            searchController.rebuildMembers()
        }
        observe(NotificationCenter.clanMembersDidLoad) { _, _, _ ->
            if (fragmentView == null || isPaused) return@observe
            if (channelScopedMembers != null) {
                buildChannelScopedMembers()
                if (currentTab == TAB_MEMBERS) updateMembersList()
            }
            if (currentTab == TAB_MESSAGES) {
                updateMessagesList()
                adapter.refreshMessageSenders()
            }
            updateTabCounts()
        }
        observe(NotificationCenter.channelMembersDidLoad) { _, _, _ ->
            if (fragmentView == null || isPaused) return@observe
            if (channelScopedMembers == null) return@observe
            buildChannelScopedMembers()
            if (currentTab == TAB_MEMBERS) updateMembersList()
            updateTabCounts()
        }
        observe(NotificationCenter.searchChannelsDidLoad) { _, _, _ ->
            if (fragmentView == null || isPaused) return@observe
            channelsFirstLoadPending = false
            if (currentTab == TAB_CHANNELS) {
                loadingView.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
                updateChannelsList()
            }
            updateTabCounts()
        }
        observe(NotificationCenter.clansDidLoad) { _, _, _ ->
            if (fragmentView == null || isPaused) return@observe
            searchController.invalidateFilterCache()
            if (currentTab == TAB_CHANNELS) {
                updateChannelsList()
            }
            updateTabCounts()
        }
        observe(NotificationCenter.searchMessagesDidLoad) { _, _, _ ->
            if (fragmentView == null || isPaused) return@observe
            if (currentTab == TAB_MESSAGES) {
                recyclerView.visibility = View.VISIBLE
                updateMessagesList()
            }
            updateTabCounts()
            loadingView.visibility = View.GONE
            isLoadingMore = false
        }

        if (channelScopedMembers != null) {
            membersRequested = true
        } else {
            membersRequested = true
            searchController.loadMembers()
        }
        if (argClanId != 0L) userClanController.loadClanMembers(argClanId)
        return true
    }

    private fun buildChannelScopedMembers() {
        val resolver = memberResolver ?: return
        val members = resolver.resolveChannelMembers(argClanId, filterChannelId, argChannelType)
        channelScopedMembers = members.map { m ->
            SearchMember(
                id = m.userId,
                username = m.username,
                displayName = m.clanNick.ifEmpty { m.displayName }.ifEmpty { m.username },
                avatarUrl = m.clanAvatar.ifEmpty { m.avatarUrl },
                isOnline = m.isOnline,
                isDm = argClanId == 0L,
                channelId = filterChannelId,
                channelType = argChannelType
            )
        }
    }

    override fun createView(context: Context): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.background)
            setPadding(0, AndroidUtilities.statusBarHeight, 0, 0)
        }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(4f), HEADER_PAD_V, HEADER_PAD_H, HEADER_PAD_V)
        }

        val backButton = ImageView(context).apply {
            setImageResource(R.drawable.ic_arrow_back)
            scaleType = ImageView.ScaleType.CENTER
            colorFilter = PorterDuffColorFilter(themeColors.onSurface, PorterDuff.Mode.SRC_IN)
            val rippleMask = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xFFFFFFFF.toInt())
            }
            val rippleColor = android.content.res.ColorStateList.valueOf(themeColors.onSurface and 0x1A_FFFFFF)
            foreground = android.graphics.drawable.RippleDrawable(rippleColor, null, rippleMask)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (isChannelPickerMode) {
                    exitChannelPicker()
                } else {
                    finishFragment()
                }
            }
        }
        headerRow.addView(backButton, LinearLayout.LayoutParams(
            BACK_BUTTON_SIZE, BACK_BUTTON_SIZE
        ).apply {
            marginEnd = BACK_BUTTON_MARGIN_END
        })

        searchCell = SearchCell(context, themeColors).apply {
            setPlaceholder(context.getString(R.string.common_search))
            onTextChanged = { text ->
                if (isChannelPickerMode) {
                    pickerQuery = text
                    schedulePickerFilter()
                } else {
                    searchText = text
                    if (text.isBlank() && filterUser == null) {
                        searchController.clearSearchMessages()
                    }
                    scheduleSearch()
                }
            }
            onBadgeRemoved = { clearActiveBadgeFilter() }
        }
        headerRow.addView(searchCell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        filterButton = ImageView(context).apply {
            val drawable = MezonIcon.filterHorizontalIcon.getDrawable(context)
            drawable.colorFilter = PorterDuffColorFilter(themeColors.onSurface, PorterDuff.Mode.SRC_IN)
            setImageDrawable(drawable)
            val ovalBg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(themeColors.surfaceVariant)
            }
            val rippleMask = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xFFFFFFFF.toInt())
            }
            val rippleColor = android.content.res.ColorStateList.valueOf(themeColors.onSurface and 0x1A_FFFFFF)
            background = android.graphics.drawable.RippleDrawable(rippleColor, ovalBg, rippleMask)
            alpha = 0.7f
            setPadding(FILTER_BUTTON_PADDING, FILTER_BUTTON_PADDING, FILTER_BUTTON_PADDING, FILTER_BUTTON_PADDING)
            isClickable = true
            isFocusable = true
            setOnClickListener { showFilterOptions(it) }
            visibility = View.GONE
        }
        val filterSize = FILTER_ICON_SIZE + FILTER_BUTTON_PADDING * 2
        headerRow.addView(filterButton, LinearLayout.LayoutParams(
            filterSize, filterSize
        ).apply {
            marginStart = FILTER_BUTTON_MARGIN_START
        })

        root.addView(headerRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val tabLabels = visibleTabs.map { tab ->
            when (tab) {
                TAB_MEMBERS -> context.getString(R.string.search_tab_members)
                TAB_CHANNELS -> context.getString(R.string.search_tab_channels)
                TAB_MESSAGES -> context.getString(R.string.search_tab_messages)
                else -> ""
            }
        }
        tabHeader = SearchTabHeader(context, themeColors).apply {
            setTabs(tabLabels)
            onTabSelected = { visualIndex ->
                searchRunnable?.let { handler.removeCallbacks(it) }
                currentTab = visibleTabs.getOrElse(visualIndex) { TAB_MEMBERS }
                membersDisplayLimit = LOCAL_PAGE_SIZE
                channelsDisplayLimit = LOCAL_PAGE_SIZE
                updateFilterButtonVisibility()
                updateCurrentTab()
            }
        }

        if (filterChannelId != 0L) {
            searchCell.setBadge(context.getString(R.string.search_filter_channel_badge, filterChannelName))
            searchCell.setPlaceholder(context.getString(R.string.search_messages_placeholder))
        }
        root.addView(tabHeader, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        contentFrame = FrameLayout(context)
        root.addView(contentFrame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        emptyView = TextView(context).apply {
            text = getString(R.string.common_no_results_found)
            setTextColor(themeColors.onSurfaceVariant)
            textSize = 15f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        contentFrame!!.addView(emptyView, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT
        ))

        recyclerView = RecyclerListView(context).apply {
            layoutManager = LinearLayoutManager(context)
            setHasFixedSize(false)
            itemAnimator = null
        }
        contentFrame!!.addView(recyclerView, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT
        ))

        loadingView = ProgressBar(context).apply { visibility = View.GONE }
        contentFrame!!.addView(loadingView, LayoutHelper.createFrame(48, 48, Gravity.CENTER))

        adapter = GlobalSearchAdapter(themeColors, ::resolveMessageSenderName)
        adapter.onChannelJoinClick = { d ->
            showJoinVoiceBottomSheet(d.channel)
        }
        recyclerView.adapter = adapter

        recyclerView.setOnItemClickListener(RecyclerListView.OnItemClickListener { view, _ ->
            when (view) {
                is ProfileSearchCell -> {
                    val member = view.member ?: return@OnItemClickListener
                    if (isPickingFilterUser) {
                        applyUserFilter(member)
                        return@OnItemClickListener
                    }
                    if (member.isDm && member.channelId != 0L) {
                        onOpenChat?.invoke(member.channelId, member.displayName, 0L, member.channelType)
                    } else {
                        navigateToDm(member)
                    }
                }
                is ChannelSearchCell -> {
                    val ch = view.channel ?: return@OnItemClickListener
                    onOpenChat?.invoke(ch.channelId, ch.channelLabel, ch.clanId, ch.type)
                }
                is MessageSearchCell -> {
                    val doc = view.document ?: return@OnItemClickListener
                    val channelId = doc.channelId.toLongOrNull() ?: return@OnItemClickListener
                    val clanId = doc.clanId.toLongOrNull() ?: 0L
                    val messageId = doc.messageId.toLongOrNull() ?: return@OnItemClickListener
                    val activity = getParentActivity() as? MainActivity
                    if (activity != null) {
                        activity.openChat(
                            channelId = channelId,
                            channelName = doc.channelLabel,
                            clanId = clanId,
                            channelType = doc.channelType,
                            messageId = messageId
                        )
                    } else {
                        onOpenChat?.invoke(channelId, doc.channelLabel, clanId, doc.channelType)
                    }
                }
            }
        })

        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    AndroidUtilities.hideKeyboard(searchCell.editText)
                }
            }

            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || isLoadingMore) return
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                val totalCount = lm.itemCount
                val lastVisible = lm.findLastVisibleItemPosition()
                if (lastVisible >= totalCount - LOAD_MORE_THRESHOLD) {
                    loadMoreResults()
                }
            }
        })

        updateFilterButtonVisibility()
        if (channelScopedMembers != null) {
            loadingView.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
            updateMembersList()
            updateTabCounts()
        } else {
            loadingView.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        }

        whenFullyVisible {
            if (!isChannelPickerMode) {
                searchCell.focusInput()
            }
        }

        fragmentView = root
        return root
    }

    private fun scheduleSearch() {
        searchRunnable?.let { handler.removeCallbacks(it) }
        searchRunnable = Runnable {
            membersDisplayLimit = LOCAL_PAGE_SIZE
            channelsDisplayLimit = LOCAL_PAGE_SIZE
            updateCurrentTab()
            updateTabCounts()
        }
        handler.postDelayed(searchRunnable!!, DEBOUNCE_MS)
    }

    private fun schedulePickerFilter() {
        pickerFilterRunnable?.let { handler.removeCallbacks(it) }
        pickerFilterRunnable = Runnable {
            pickerDisplayLimit = LOCAL_PAGE_SIZE
            channelPickerAdapter?.filter(pickerQuery, pickerDisplayLimit)
        }
        handler.postDelayed(pickerFilterRunnable!!, DEBOUNCE_MS)
    }

    private fun updateCurrentTab() {
        when (currentTab) {
            TAB_MEMBERS -> {
                if (channelScopedMembers != null) {
                    loadingView.visibility = View.GONE
                    recyclerView.visibility = View.VISIBLE
                    updateMembersList()
                } else if (!membersRequested) {
                    membersRequested = true
                    loadingView.visibility = View.VISIBLE
                    recyclerView.visibility = View.GONE
                    searchController.loadMembers()
                } else {
                    loadingView.visibility = View.GONE
                    updateMembersList()
                }
            }
            TAB_CHANNELS -> {
                if (!channelsRequested) {
                    channelsRequested = true
                    channelsFirstLoadPending = true
                    emptyView.visibility = View.GONE
                    loadingView.visibility = View.VISIBLE
                    recyclerView.visibility = View.VISIBLE
                    updateChannelsList()
                    searchController.loadChannels()
                } else {
                    loadingView.visibility =
                        if (channelsFirstLoadPending) View.VISIBLE else View.GONE
                    recyclerView.visibility = View.VISIBLE
                    updateChannelsList()
                }
            }
            TAB_MESSAGES -> {
                if (searchText.isNotBlank() || filterUser != null) {
                    loadingView.visibility = View.VISIBLE
                    recyclerView.visibility = View.GONE
                    searchMessages()
                } else {
                    loadingView.visibility = View.GONE
                    searchController.clearSearchMessages()
                    updateMessagesList()
                }
            }
        }
    }

    private fun updateMembersList() {
        val scoped = channelScopedMembers
        if (scoped != null) {
            val all = if (searchText.isBlank()) {
                scoped
            } else {
                val q = SearchController.removeDiacritics(searchText.trim().lowercase())
                scoped.filter { m ->
                    val dn = SearchController.removeDiacritics(m.displayName.lowercase())
                    val un = SearchController.removeDiacritics(m.username.lowercase())
                    dn.contains(q) || un.contains(q)
                }
            }
            val page = all.take(membersDisplayLimit)
            adapter.setMembers(page)
            adapter.hasMore = page.size < all.size
            updateEmptyState(page.isEmpty())
            return
        }
        val filtered = searchController.filterMembers(searchText, membersDisplayLimit)
        adapter.setMembers(filtered)
        val totalCount = searchController.totalMembersForQuery(searchText)
        adapter.hasMore = filtered.size < totalCount
        updateEmptyState(filtered.isEmpty())
    }

    private fun updateChannelsList() {
        val ctx = fragmentView?.context ?: return
        val filtered = searchController.filterChannelDisplays(
            searchText,
            channelsDisplayLimit,
            hideClanName = false
        )
        adapter.setChannelSearchItems(
            filtered,
            ctx.getString(R.string.search_section_text_channels),
            ctx.getString(R.string.search_section_voice_channels),
            ctx.getString(R.string.search_section_streaming_channels)
        )
        val totalCount = searchController.totalChannelsForQuery(searchText)
        adapter.hasMore = filtered.size < totalCount
        val isEmpty = filtered.isEmpty()
        if (channelsFirstLoadPending && isEmpty) {
            emptyView.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        } else {
            updateEmptyState(isEmpty)
        }
    }

    private fun updateMessagesList() {
        val messages = searchController.getMessages()
        adapter.setMessages(messages)
        adapter.hasMore = searchController.hasMoreMessages && messages.isNotEmpty()
        updateEmptyState(messages.isEmpty() && (searchText.isNotBlank() || filterUser != null))
    }

    private fun loadMoreResults() {
        when (currentTab) {
            TAB_MEMBERS -> {
                val scoped = channelScopedMembers
                val total = scoped?.size ?: searchController.totalMembersForQuery(searchText)
                if (membersDisplayLimit >= total) return
                isLoadingMore = true
                membersDisplayLimit += LOCAL_PAGE_SIZE
                updateMembersList()
                isLoadingMore = false
            }
            TAB_CHANNELS -> {
                val total = searchController.totalChannelsForQuery(searchText)
                if (channelsDisplayLimit >= total) return
                isLoadingMore = true
                channelsDisplayLimit += LOCAL_PAGE_SIZE
                updateChannelsList()
                isLoadingMore = false
            }
            TAB_MESSAGES -> {
                if (!searchController.hasMoreMessages ||
                    (searchText.isBlank() && filterUser == null)) return
                isLoadingMore = true
                searchController.loadMoreMessages(
                    channelId = filterChannelId,
                    content = searchText,
                    username = filterUsername(),
                    mentionUserId = filterMentionUserId()
                )
            }
        }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        if (isEmpty) {
            emptyView.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyView.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }
    }

    private fun updateTabCounts() {
        val scoped = channelScopedMembers
        val membersCount = if (scoped != null) {
            if (searchText.isBlank()) scoped.size else {
                val q = SearchController.removeDiacritics(searchText.trim().lowercase())
                scoped.count { m ->
                    val dn = SearchController.removeDiacritics(m.displayName.lowercase())
                    val un = SearchController.removeDiacritics(m.username.lowercase())
                    dn.contains(q) || un.contains(q)
                }
            }
        } else {
            searchController.filterMembersCount(searchText)
        }
        val channelsCount = searchController.filterChannelsCount(searchText)
        val messagesCount = if (searchText.isBlank() && filterUser == null) {
            0
        } else {
            searchController.searchMessagesTotal
        }
        val counts = visibleTabs.map { tab ->
            when (tab) {
                TAB_MEMBERS -> membersCount
                TAB_CHANNELS -> channelsCount
                TAB_MESSAGES -> messagesCount
                else -> 0
            }
        }
        tabHeader.updateCounts(counts)
    }

    private fun updateFilterButtonVisibility() {
        val showFilter = currentTab == TAB_MESSAGES && !isChannelPickerMode
        filterButton?.visibility = if (showFilter) View.VISIBLE else View.GONE
    }

    private fun showFilterOptions(anchor: View) {
        val context = anchor.context
        val menuWidth = LayoutHelper.dp(220f)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dpf(10f)
                setColor(themeColors.surface)
                setStroke(LayoutHelper.dp(1f), themeColors.borderDim)
            }
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            elevation = LayoutHelper.dpf(8f)
        }

        container.addView(TextView(context).apply {
            text = context.getString(R.string.search_filter_results)
            setTextColor(themeColors.onSurface)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(14f), 0, LayoutHelper.dp(14f), 0)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LayoutHelper.dp(36f)
        ))
        container.addView(createFilterDivider(context))

        lateinit var popup: PopupWindow
        container.addView(createFilterOptionRow(
            context = context,
            label = context.getString(R.string.search_filter_from_label),
            description = context.getString(R.string.search_filter_from_description),
            icon = MezonIcon.userIcon,
            onClick = {
                popup.dismiss()
                selectUserFilter(MessageUserFilter.FROM)
            }
        ))
        container.addView(createFilterDivider(context, horizontalInset = 10f))
        container.addView(createFilterOptionRow(
            context = context,
            label = context.getString(R.string.search_filter_mentions_label),
            description = context.getString(R.string.search_filter_mentions_description),
            icon = MezonIcon.atIcon,
            onClick = {
                popup.dismiss()
                selectUserFilter(MessageUserFilter.MENTIONS)
            }
        ))

        popup = PopupWindow(
            container,
            menuWidth,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            elevation = LayoutHelper.dpf(8f)
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        }
        popup.showAsDropDown(anchor, 0, LayoutHelper.dp(6f), Gravity.END)
    }

    private fun createFilterOptionRow(
        context: Context,
        label: String,
        description: String,
        icon: MezonIcon,
        onClick: () -> Unit
    ): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(14f), 0, LayoutHelper.dp(12f), 0)
            isClickable = true
            isFocusable = true
            background = context.getDrawable(android.R.drawable.list_selector_background)
            setOnClickListener { onClick() }

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = label
                    setTextColor(themeColors.onSurface)
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                    includeFontPadding = false
                })
                addView(TextView(context).apply {
                    text = description
                    setTextColor(themeColors.onSurfaceVariant)
                    textSize = 13f
                    includeFontPadding = false
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = LayoutHelper.dp(2f) })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            addView(ImageView(context).apply {
                setImageDrawable(icon.getDrawable(context, themeColors.onSurfaceVariant))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }, LinearLayout.LayoutParams(LayoutHelper.dp(20f), LayoutHelper.dp(20f)))
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LayoutHelper.dp(52f)
            )
        }
    }

    private fun createFilterDivider(context: Context, horizontalInset: Float = 0f): View {
        return View(context).apply {
            setBackgroundColor(themeColors.borderDim)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LayoutHelper.dp(1f)
            ).apply {
                marginStart = LayoutHelper.dp(horizontalInset)
                marginEnd = LayoutHelper.dp(horizontalInset)
            }
        }
    }

    private fun selectUserFilter(filter: MessageUserFilter) {
        activeUserFilter = filter
        filterUser = null
        isPickingFilterUser = true
        currentTab = TAB_MEMBERS
        tabHeader.visibility = View.GONE
        updateFilterButtonVisibility()
        searchCell.setBadge(filterLabel(filter))
        searchCell.editText.text?.clear()
        searchRunnable?.let { handler.removeCallbacks(it) }
        searchText = ""
        updateMembersList()
        searchCell.focusInput()
    }

    private fun applyUserFilter(member: SearchMember) {
        filterUser = member
        isPickingFilterUser = false
        tabHeader.visibility = View.VISIBLE
        currentTab = TAB_MESSAGES
        visibleTabs.indexOf(TAB_MESSAGES).takeIf { it >= 0 }?.let(tabHeader::selectTab)
        updateFilterButtonVisibility()

        searchCell.editText.text?.clear()
        searchRunnable?.let { handler.removeCallbacks(it) }
        searchText = ""
        updateSearchBadge()
        loadingView.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE
        searchMessages()
    }

    private fun clearActiveBadgeFilter() {
        if (activeUserFilter != null || filterUser != null || isPickingFilterUser) {
            activeUserFilter = null
            filterUser = null
            isPickingFilterUser = false
            tabHeader.visibility = View.VISIBLE
            currentTab = TAB_MEMBERS
            visibleTabs.indexOf(TAB_MEMBERS).takeIf { it >= 0 }?.let(tabHeader::selectTab)
            updateFilterButtonVisibility()
            updateSearchBadge()
            searchController.clearSearchMessages()
            updateCurrentTab()
            updateTabCounts()
        } else {
            clearChannelFilter()
        }
    }

    private fun updateSearchBadge() {
        val user = filterUser
        if (user != null && activeUserFilter != null) {
            searchCell.setBadge(getString(
                R.string.search_filter_user_badge,
                filterLabel(activeUserFilter!!),
                filterDisplayName(user)
            ))
        } else if (filterChannelId != 0L) {
            searchCell.setBadge(getString(R.string.search_filter_channel_badge, filterChannelName))
        } else {
            searchCell.removeBadge()
        }
    }

    private fun filterDisplayName(member: SearchMember): String {
        val clanId = argClanId.takeIf { it != 0L }
        val clanNick = clanId?.let { id ->
            userClanController.getClanMembers(id)
                .firstOrNull { it.userId == member.id }
                ?.clanNick
        }
        return clanNick?.takeIf { it.isNotBlank() }
            ?: member.displayName.ifEmpty { member.username }
    }

    private fun filterLabel(filter: MessageUserFilter): String = getString(
        if (filter == MessageUserFilter.FROM) {
            R.string.search_filter_from_label
        } else {
            R.string.search_filter_mentions_label
        }
    )

    private fun filterUsername(): String =
        if (activeUserFilter == MessageUserFilter.FROM) {
            filterUser?.let(::filterDisplayName).orEmpty()
        } else ""

    private fun filterMentionUserId(): Long =
        if (activeUserFilter == MessageUserFilter.MENTIONS) filterUser?.id ?: 0L else 0L

    private fun searchMessages() {
        searchController.searchMessagesApi(
            channelId = filterChannelId,
            content = searchText,
            username = filterUsername(),
            mentionUserId = filterMentionUserId()
        )
    }

    private fun ensureChannelPicker() {
        if (channelPickerView != null) return
        val ctx = fragmentView?.context ?: return
        val pickerAdapter = ChannelPickerAdapter(themeColors, searchController)
        channelPickerAdapter = pickerAdapter
        channelPickerView = RecyclerListView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            setHasFixedSize(false)
            itemAnimator = null
            visibility = View.GONE
            adapter = pickerAdapter
            setOnItemClickListener(RecyclerListView.OnItemClickListener { view, _ ->
                if (view is ChannelSearchCell) {
                    val ch = view.channel ?: return@OnItemClickListener
                    applyChannelFilter(ch)
                }
            })
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0 || !pickerAdapter.hasMore) return
                    val lm = rv.layoutManager as? LinearLayoutManager ?: return
                    val lastVisible = lm.findLastVisibleItemPosition()
                    if (lastVisible >= lm.itemCount - LOAD_MORE_THRESHOLD) {
                        pickerDisplayLimit += LOCAL_PAGE_SIZE
                        pickerAdapter.filter(pickerQuery, pickerDisplayLimit)
                    }
                }
            })
        }
        contentFrame?.addView(channelPickerView, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT
        ))
    }

    private fun enterChannelPicker() {
        isChannelPickerMode = true
        pickerQuery = ""
        pickerDisplayLimit = LOCAL_PAGE_SIZE

        ensureChannelPicker()
        val channels = searchController.getChannels()
        channelPickerAdapter?.setAllChannels(channels)
        channelPickerAdapter?.filter(pickerQuery, pickerDisplayLimit)

        recyclerView.visibility = View.GONE
        emptyView.visibility = View.GONE
        tabHeader.visibility = View.GONE
        channelPickerView?.visibility = View.VISIBLE
        updateFilterButtonVisibility()

        searchCell.editText.text?.clear()
        searchRunnable?.let { handler.removeCallbacks(it) }
        searchCell.setPlaceholder(getString(R.string.search_channels_placeholder))
        searchCell.focusInput()
    }

    private fun exitChannelPicker() {
        isChannelPickerMode = false
        channelPickerView?.jumpDrawablesToCurrentState()
        channelPickerView?.visibility = View.GONE
        tabHeader.visibility = View.VISIBLE
        recyclerView.visibility = View.VISIBLE
        updateFilterButtonVisibility()

        searchCell.editText.text?.clear()
        searchRunnable?.let { handler.removeCallbacks(it) }
        searchCell.setPlaceholder(getString(R.string.common_search))
        AndroidUtilities.hideKeyboard(searchCell.editText)

        updateCurrentTab()
    }

    private fun applyChannelFilter(channel: ClanChannelEntity) {
        filterChannelId = channel.channelId
        filterChannelName = channel.channelLabel
        searchCell.setBadge(getString(R.string.search_filter_channel_badge, channel.channelLabel))

        isChannelPickerMode = false
        channelPickerView?.jumpDrawablesToCurrentState()
        channelPickerView?.visibility = View.GONE
        tabHeader.visibility = View.VISIBLE
        recyclerView.visibility = View.VISIBLE
        updateFilterButtonVisibility()

        searchCell.editText.text?.clear()
        searchRunnable?.let { handler.removeCallbacks(it) }
        searchCell.setPlaceholder(getString(R.string.search_messages_placeholder))
        AndroidUtilities.hideKeyboard(searchCell.editText)

        if (currentTab == TAB_MESSAGES) {
            loadingView.visibility = View.VISIBLE
            searchMessages()
        }
    }

    private fun clearChannelFilter() {
        filterChannelId = 0L
        filterChannelName = ""
        searchCell.removeBadge()
        searchCell.setPlaceholder(getString(R.string.common_search))
        if (currentTab == TAB_MESSAGES) {
            if (searchText.isNotBlank() || filterUser != null) {
                loadingView.visibility = View.VISIBLE
                searchMessages()
            } else {
                searchController.clearSearchMessages()
                updateMessagesList()
            }
        }
    }

    private fun showJoinVoiceBottomSheet(channel: ClanChannelEntity) {
        when (channel.type) {
            CHANNEL_TYPE_STREAMING -> showJoinStreamBottomSheet(channel)
            else -> showJoinVoiceMediaBottomSheet(channel)
        }
    }

    private fun showJoinStreamBottomSheet(channel: ClanChannelEntity) {
        val activity = getParentActivity() ?: return
        val targetClanId = channel.clanId
        val memberIds = streamingController.getStreamMembersForChannel(channel.channelId, targetClanId)
        val displays = buildMemberDisplays(memberIds, targetClanId)
        val sheet = JoinVoiceBottomSheet(
            activity, themeColors, channel.channelLabel, channel.channelId, targetClanId, displays, channel.unreadCount,
            JoinMediaSheetKind.STREAMING
        )
        sheet.onJoinVoice = {
            (activity as? MainActivity)?.showStreamingRoom(channel.channelId, targetClanId, channel.channelLabel)
        }
        sheet.onOpenChat = {
            onOpenChat?.invoke(channel.channelId, channel.channelLabel, targetClanId, channel.type)
        }
        sheet.show()
    }

    private fun showJoinVoiceMediaBottomSheet(channel: ClanChannelEntity) {
        val activity = getParentActivity() ?: return
        val targetClanId = channel.clanId
        val memberIds = voiceController.getVoiceMembersForChannel(channel.channelId, targetClanId)
        val displays = buildMemberDisplays(memberIds, targetClanId)
        val sheet = JoinVoiceBottomSheet(
            activity, themeColors, channel.channelLabel, channel.channelId, targetClanId, displays, channel.unreadCount
        )
        sheet.onJoinVoice = {
            (activity as? MainActivity)?.showVoiceRoom(channel.channelId, targetClanId, channel.channelLabel)
        }
        sheet.onOpenChat = {
            onOpenChat?.invoke(channel.channelId, channel.channelLabel, targetClanId, channel.type)
        }
        sheet.show()
    }

    private fun buildMemberDisplays(memberIds: List<Long>, targetClanId: Long): List<VoiceMemberDisplay> {
        val clanMembers = userClanController.getClanMembers(targetClanId)
        val memberMap = HashMap<Long, ClanMember>(clanMembers.size)
        for (m in clanMembers) memberMap[m.userId] = m
        return memberIds.map { uid ->
            val m = memberMap[uid]
            val name = m?.clanNick?.ifEmpty { null }
                ?: m?.displayName?.ifEmpty { null }
                ?: m?.username
                ?: getString(R.string.search_user_fallback)
            val username = m?.username.orEmpty()
            val avatar = m?.clanAvatar?.ifEmpty { null } ?: m?.avatarUrl
            VoiceMemberDisplay(uid, name, username, avatar)
        }
    }

    private fun resolveMessageSenderName(document: SearchMessageDocument): String? {
        val clanId = document.clanId.toLongOrNull() ?: argClanId
        val senderId = document.senderId.toLongOrNull() ?: return null
        val member = userClanController.getClanMembers(clanId)
            .firstOrNull { it.userId == senderId }
        return member?.clanNick?.takeIf { it.isNotBlank() }
            ?: member?.displayName?.takeIf { it.isNotBlank() }
            ?: document.displayName.takeIf { it.isNotBlank() }
            ?: document.username
    }

    private fun navigateToDm(member: SearchMember) {
        fragmentScope.launch {
            val dmChannelId = dialogsController.getOrCreateDm(member.id)
            if (dmChannelId != 0L) {
                launch(Dispatchers.Main.immediate) {
                    onOpenChat?.invoke(dmChannelId, member.displayName, 0L, CHANNEL_TYPE_DM)
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        AndroidUtilities.hideKeyboard(searchCell.editText)
    }

    override fun onFragmentDestroy() {
        searchRunnable?.let { handler.removeCallbacks(it) }
        pickerFilterRunnable?.let { handler.removeCallbacks(it) }
        AndroidUtilities.hideKeyboard(searchCell.editText)
        searchCell.editText.clearFocus()
        searchController.invalidateFilterCache()
        super.onFragmentDestroy()
    }
}

private class ChannelPickerAdapter(
    private val themeColors: ThemeColors,
    private val searchController: SearchController
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val allChannels = ArrayList<ClanChannelEntity>()
    private val displayed = ArrayList<ChannelSearchDisplay>()
    private var lastFilteredAll = ArrayList<ClanChannelEntity>()
    var hasMore = false
        private set

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long =
        if (position in displayed.indices) displayed[position].channel.channelId else RecyclerView.NO_ID

    override fun getItemCount(): Int = displayed.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val cell = ChannelSearchCell(parent.context, themeColors)
        cell.layoutParams = RecyclerView.LayoutParams(
            RecyclerView.LayoutParams.MATCH_PARENT,
            RecyclerView.LayoutParams.WRAP_CONTENT
        )
        return object : RecyclerView.ViewHolder(cell) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (position !in displayed.indices) return
        val cell = holder.itemView as? ChannelSearchCell ?: return
        cell.setJoinClickListener(null)
        cell.update(0, displayed[position])
    }

    fun setAllChannels(channels: List<ClanChannelEntity>) {
        allChannels.clear()
        allChannels.addAll(channels)
    }

    fun filter(query: String, limit: Int) {
        val all = if (query.isBlank()) {
            ArrayList(allChannels)
        } else {
            val q = query.trim().lowercase()
            val result = ArrayList<ClanChannelEntity>()
            for (ch in allChannels) {
                if (ch.channelLabel.lowercase().contains(q)) {
                    result.add(ch)
                }
            }
            result
        }
        lastFilteredAll = all
        val page = if (limit < all.size) ArrayList(all.subList(0, limit)) else all
        hasMore = page.size < all.size

        val pageDisplays = searchController.channelDisplaysForPicker(page)
        val oldList = ArrayList(displayed)
        displayed.clear()
        displayed.addAll(pageDisplays)

        val diff = DiffUtil.calculateDiff(ChannelPickerDiffCallback(oldList, displayed))
        diff.dispatchUpdatesTo(this)
    }
}

private class ChannelPickerDiffCallback(
    private val oldList: List<ChannelSearchDisplay>,
    private val newList: List<ChannelSearchDisplay>
) : DiffUtil.Callback() {
    override fun getOldListSize() = oldList.size
    override fun getNewListSize() = newList.size
    override fun areItemsTheSame(oldPos: Int, newPos: Int) =
        oldList[oldPos].channel.channelId == newList[newPos].channel.channelId
    override fun areContentsTheSame(oldPos: Int, newPos: Int) =
        oldList[oldPos] == newList[newPos]
}
