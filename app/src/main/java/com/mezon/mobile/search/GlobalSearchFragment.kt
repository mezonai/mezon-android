package com.mezon.mobile.search

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.RecyclerListView
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.ChatController
import com.mezon.mobile.home.DialogsController
import com.mezon.mobile.home.MemberResolver
import com.mezon.mobile.home.clans.ClanChannelEntity
import com.mezon.mobile.ui.cells.ChannelSearchCell
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.cells.PopupMenu
import com.mezon.mobile.ui.cells.ProfileSearchCell
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.network.CHANNEL_TYPE_DM
import com.mezon.mobile.ui.cells.SearchCell
import com.mezon.mobile.ui.cells.SearchTabHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
    private lateinit var chatController: ChatController

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
    private var scrollingManually = false
    private var filterChannelId = 0L
    private var filterChannelName = ""
    private var hideChannelsTab = false
    private var visibleTabs = listOf(TAB_MEMBERS, TAB_CHANNELS, TAB_MESSAGES)
    private var argClanId = 0L
    private var argChannelType = 0
    private var channelScopedMembers: List<SearchMember>? = null
    private var membersRequested = false
    private var channelsRequested = false
    private var isChannelPickerMode = false
    private var pickerQuery = ""
    private var pickerDisplayLimit = LOCAL_PAGE_SIZE
    private var pickerFilterRunnable: Runnable? = null

    var onOpenChat: ((channelId: Long, channelName: String, clanId: Long, channelType: Int) -> Unit)? = null

    override fun onInject(entryPoint: FragmentEntryPoint) {
        searchController = entryPoint.searchController()
        memberResolver = entryPoint.memberResolver()
        dialogsController = entryPoint.dialogsController()
        chatController = entryPoint.chatController()
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
            if (channelScopedMembers == null) return@observe
            buildChannelScopedMembers()
            if (currentTab == TAB_MEMBERS) updateMembersList()
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
        return true
    }

    private fun buildChannelScopedMembers() {
        val resolver = memberResolver ?: return
        val members = resolver.resolveChannelMembers(argClanId, filterChannelId, argChannelType)
        channelScopedMembers = members.map { m ->
            SearchMember(
                id = m.userId,
                username = m.username,
                displayName = m.displayName.ifEmpty { m.username },
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
            setPlaceholder("Search")
            onTextChanged = { text ->
                if (isChannelPickerMode) {
                    pickerQuery = text
                    schedulePickerFilter()
                } else {
                    searchText = text
                    scheduleSearch()
                }
            }
            onBadgeRemoved = { clearChannelFilter() }
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
                TAB_MEMBERS -> "Members"
                TAB_CHANNELS -> "Channels"
                TAB_MESSAGES -> "Messages"
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
            searchCell.setBadge("in: $filterChannelName")
            searchCell.setPlaceholder("Search messages...")
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
            text = "No results found"
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

        adapter = GlobalSearchAdapter(themeColors)
        adapter.onChannelJoinClick = { d ->
            onOpenChat?.invoke(d.channel.channelId, d.channel.channelLabel, d.channel.clanId, d.channel.type)
        }
        recyclerView.adapter = adapter

        recyclerView.setOnItemClickListener(RecyclerListView.OnItemClickListener { view, _ ->
            when (view) {
                is ProfileSearchCell -> {
                    val member = view.member ?: return@OnItemClickListener
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
                    onOpenChat?.invoke(channelId, doc.channelLabel, clanId, doc.channelType)
                }
            }
        })

        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                scrollingManually = newState != RecyclerView.SCROLL_STATE_IDLE
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
                    loadingView.visibility = View.VISIBLE
                    recyclerView.visibility = View.GONE
                    searchController.loadChannels()
                } else {
                    loadingView.visibility = View.GONE
                    updateChannelsList()
                }
            }
            TAB_MESSAGES -> {
                if (searchText.isNotBlank()) {
                    loadingView.visibility = View.VISIBLE
                    recyclerView.visibility = View.GONE
                    searchController.searchMessagesApi(
                        channelId = filterChannelId,
                        content = searchText
                    )
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
        updateEmptyState(filtered.isEmpty())
    }

    private fun updateMessagesList() {
        val messages = searchController.getMessages()
        adapter.setMessages(messages)
        adapter.hasMore = searchController.hasMoreMessages && messages.isNotEmpty()
        updateEmptyState(messages.isEmpty() && searchText.isNotBlank())
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
                if (!searchController.hasMoreMessages || searchText.isBlank()) return
                isLoadingMore = true
                searchController.loadMoreMessages(
                    channelId = filterChannelId,
                    content = searchText
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
        val messagesCount = searchController.searchMessagesTotal
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
        val popup = PopupMenu(anchor.context, themeColors)
        popup.addItem("in: filter by channel", MezonIcon.channelText)
        popup.setOnItemClickListener { _ ->
            enterChannelPicker()
        }
        popup.show(anchor)
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
        searchCell.setPlaceholder("Search channels...")
        searchCell.editText.requestFocus()
        AndroidUtilities.showKeyboard(searchCell.editText)
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
        searchCell.setPlaceholder("Search")
        AndroidUtilities.hideKeyboard(searchCell.editText)

        updateCurrentTab()
    }

    private fun applyChannelFilter(channel: ClanChannelEntity) {
        filterChannelId = channel.channelId
        filterChannelName = channel.channelLabel
        searchCell.setBadge("in: ${channel.channelLabel}")

        isChannelPickerMode = false
        channelPickerView?.jumpDrawablesToCurrentState()
        channelPickerView?.visibility = View.GONE
        tabHeader.visibility = View.VISIBLE
        recyclerView.visibility = View.VISIBLE
        updateFilterButtonVisibility()

        searchCell.editText.text?.clear()
        searchRunnable?.let { handler.removeCallbacks(it) }
        searchCell.setPlaceholder("Search messages...")
        AndroidUtilities.hideKeyboard(searchCell.editText)

        if (currentTab == TAB_MESSAGES) {
            loadingView.visibility = View.VISIBLE
            searchController.searchMessagesApi(
                channelId = filterChannelId,
                content = searchText
            )
        }
    }

    private fun clearChannelFilter() {
        filterChannelId = 0L
        filterChannelName = ""
        searchCell.removeBadge()
        searchCell.setPlaceholder("Search")
        if (currentTab == TAB_MESSAGES) {
            if (searchText.isNotBlank()) {
                loadingView.visibility = View.VISIBLE
                searchController.searchMessagesApi(content = searchText)
            } else {
                searchController.clearSearchMessages()
                updateMessagesList()
            }
        }
    }

    private fun navigateToDm(member: SearchMember) {
        fragmentScope.launch {
            val dmChannelId = dialogsController.getOrCreateDm(member.id)
            if (dmChannelId != 0L) {
                chatController.openChannel(dmChannelId, 0L, CHANNEL_TYPE_DM)
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
