package com.mezon.mobile.home.chat.thread

import android.content.Context
import com.mezon.mobile.R
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
import androidx.recyclerview.widget.LinearLayoutManager
import com.mezon.mobile.MainActivity
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.RecyclerListView
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.MemberResolver
import com.mezon.mobile.home.clans.ChannelController
import com.mezon.mobile.home.clans.ClanChannelEntity
import com.mezon.mobile.home.clans.PermissionPolicy
import com.mezon.mobile.network.CHANNEL_TYPE_THREAD
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.session.SessionManager
import com.mezon.mobile.ui.cells.ActionBarMenuItem
import com.mezon.mobile.ui.cells.ActionBarView
import com.mezon.mobile.ui.cells.MezonIcon
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ThreadListFragment : BaseFragment() {

    companion object {
        private const val TAG = "ThreadList"
        private const val CREATE_MENU_ICON_DP = 22f
        private const val ARG_CHANNEL_ID = "channelId"
        private const val ARG_CHANNEL_NAME = "channelName"
        private const val ARG_CLAN_ID = "clanId"
        private const val LIMIT = 50

        fun newInstance(
            channelId: Long,
            channelName: String,
            clanId: Long
        ): ThreadListFragment = ThreadListFragment().apply {
            arguments = Bundle().apply {
                putLong(ARG_CHANNEL_ID, channelId)
                putString(ARG_CHANNEL_NAME, channelName)
                putLong(ARG_CLAN_ID, clanId)
            }
        }
    }

    private var channelId = 0L
    private var channelName = ""
    private var clanId = 0L

    private lateinit var api: MezonApi
    private lateinit var sessionManager: SessionManager
    private lateinit var memberResolver: MemberResolver
    private lateinit var channelController: ChannelController
    private lateinit var permissionPolicy: PermissionPolicy
    private lateinit var ioDispatcher: CoroutineDispatcher

    private var adapter: ThreadListAdapter? = null
    private var createMenuItem: ActionBarMenuItem? = null
    private var emptyCreateButton: TextView? = null
    private var recyclerView: RecyclerListView? = null
    private var emptyView: View? = null
    private var loadingView: ProgressBar? = null
    private var paginationBar: View? = null
    private var prevButton: ImageView? = null
    private var nextButton: ImageView? = null
    private var pageText: TextView? = null
    private var searchInput: EditText? = null

    private var currentPage = 1
    private var isNextDisabled = false
    private var isPaginationVisible = false
    private var isSearchMode = false
    private var searchJob: Job? = null
    private var fetchJob: Job? = null
    private var allThreads = ArrayList<ThreadInfo>()

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        channelId = arguments?.getLong(ARG_CHANNEL_ID) ?: 0L
        channelName = arguments?.getString(ARG_CHANNEL_NAME) ?: ""
        clanId = arguments?.getLong(ARG_CLAN_ID) ?: 0L
        permissionPolicy.ensurePermissionChecker(
            listOf(PermissionPolicy.CLAN_OWNER, PermissionPolicy.MANAGE_THREAD, PermissionPolicy.MANAGE_CHANNEL),
            channelId,
            clanId
        )
        observe(NotificationCenter.channelPermissionOverridesDidLoad) { _, _, args ->
            val changedChannelId = args.firstOrNull() as? Long ?: return@observe
            if (changedChannelId == channelId) updateCreateThreadActions()
        }
        observe(NotificationCenter.channelPermissionsDidLoad) { _, _, args ->
            val changedChannelId = args.firstOrNull() as? Long ?: return@observe
            if (changedChannelId == channelId) updateCreateThreadActions()
        }
        observe(NotificationCenter.clanRolesDidLoad) { _, _, args ->
            val changedClanId = args.firstOrNull() as? Long ?: return@observe
            if (changedClanId == clanId) updateCreateThreadActions()
        }
        observe(NotificationCenter.channelsDidLoad) { _, _, args ->
            val changedClanId = args.firstOrNull() as? Long ?: return@observe
            if (changedClanId == clanId) refreshCachedThreadList()
        }
        return true
    }

    override fun onInject(entryPoint: FragmentEntryPoint) {
        api = entryPoint.mezonApi()
        sessionManager = entryPoint.sessionManager()
        memberResolver = entryPoint.memberResolver()
        channelController = entryPoint.channelController()
        permissionPolicy = entryPoint.permissionPolicy()
        ioDispatcher = entryPoint.ioDispatcher()
    }

    override fun createView(context: Context): View {
        val root = FrameLayout(context).apply {
            setBackgroundColor(themeColors.surface)
        }

        val actionBarView = ActionBarView(context, themeColors).apply {
            setBackButtonImage(R.drawable.ic_arrow_back)
            setBackgroundColor(themeColors.surface)
            setTitle(getString(R.string.thread_list_title))
            setCenterTitle(true)
        }
        actionBar = actionBarView

        val createMenuItem = actionBarView.createMenu().addItem(1, MezonIcon.plusLargeIcon.getDrawable(context).apply {
            colorFilter = PorterDuffColorFilter(themeColors.onSurface, PorterDuff.Mode.SRC_IN)
        })
        this.createMenuItem = createMenuItem
        val createIconPx = LayoutHelper.dp(CREATE_MENU_ICON_DP)
        createMenuItem.iconView.scaleType = ImageView.ScaleType.FIT_CENTER
        createMenuItem.iconView.layoutParams = FrameLayout.LayoutParams(createIconPx, createIconPx, Gravity.CENTER)
        actionBarView.setMenuOnItemClick(object : ActionBarView.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                if (id == -1) finishFragment()
                else if (id == 1) {
                    openCreateThread()
                }
            }
        })

        val contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(12), LayoutHelper.dp(16), LayoutHelper.dp(12), 0)
        }

        contentLayout.addView(buildSearchBar(context), LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT
        ))

        recyclerView = RecyclerListView(context).apply {
            layoutManager = LinearLayoutManager(context)
            clipToPadding = false
            setPadding(0, LayoutHelper.dp(10), 0, LayoutHelper.dp(60))
            itemAnimator = null
        }

        adapter = ThreadListAdapter(
            theme = themeColors,
            senderNameResolver = { senderId -> resolveSenderName(senderId) },
            onThreadClick = { thread -> navigateToThread(thread) }
        )
        recyclerView!!.adapter = adapter
        contentLayout.addView(recyclerView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        root.addView(actionBarView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 56))
        root.addView(contentLayout, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT
        ))

        emptyView = buildEmptyView(context)
        emptyView!!.visibility = View.GONE
        root.addView(emptyView, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT
        ))

        loadingView = ProgressBar(context).apply { visibility = View.VISIBLE }
        root.addView(loadingView, LayoutHelper.createFrame(48, 48, Gravity.CENTER))

        val pagination = buildPaginationBar(context)
        pagination.visibility = View.GONE
        paginationBar = pagination
        root.addView(pagination, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
            Gravity.BOTTOM, 12f, 0f, 12f, 0f
        ))

        fragmentView = root
        updateCreateThreadActions()
        fetchThreads(1)
        return root
    }

    override fun onResume() {
        super.onResume()
        if (fragmentView != null) refreshCachedThreadList(animateChanges = false)
    }

    private fun updateCreateThreadActions() {
        val visibility = if (permissionPolicy.canCreateThreadFromThreadList(channelId, clanId)) View.VISIBLE else View.GONE
        createMenuItem?.visibility = visibility
        emptyCreateButton?.visibility = visibility
    }

    private fun openCreateThread() {
        if (!permissionPolicy.canCreateThreadFromThreadList(channelId, clanId)) {
            val ctx = getContext() ?: return
            Toast.makeText(ctx, R.string.channel_permissions_no_access, Toast.LENGTH_SHORT).show()
            return
        }
        presentFragment(CreateThreadFragment.newInstance(channelId, channelName, clanId))
    }

    private fun buildSearchBar(context: Context): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = LayoutHelper.dp(50)
            background = GradientDrawable().apply {
                setColor(themeColors.channelPanelBg)
                cornerRadius = LayoutHelper.dpf(8f)
            }
            val padH = LayoutHelper.dp(12)
            setPadding(padH, 0, padH, 0)
        }

        searchInput = EditText(context).apply {
            setTextColor(themeColors.onSurface)
            setHintTextColor(themeColors.textDisabled)
            hint = getString(R.string.thread_list_search_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = null
            setPadding(0, 0, 0, 0)
            isSingleLine = true
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        container.addView(searchInput, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f))

        val clearButton = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(themeColors.textDisabled)
            visibility = View.GONE
        }
        container.addView(clearButton, LayoutHelper.createLinear(20, 20, 0f, Gravity.CENTER_VERTICAL, 8f, 0f, 0f, 0f))

        searchInput!!.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString() ?: ""
                clearButton.visibility = if (text.isNotEmpty()) View.VISIBLE else View.GONE
                onSearchTextChanged(text)
            }
        })

        clearButton.setOnClickListener {
            searchInput!!.text?.clear()
        }

        return container
    }

    private fun buildEmptyView(context: Context): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(LayoutHelper.dp(10), 0, LayoutHelper.dp(10), 0)
        }

        val spacer = View(context)
        container.addView(spacer, LayoutHelper.createLinear(0, 0, 0.3f))

        val iconCircle = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(themeColors.channelPanelBg)
            }
        }
        val iconView = ImageView(context).apply {
            setImageDrawable(MezonIcon.threadPlusIcon.getDrawable(context))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        iconCircle.addView(iconView, LayoutHelper.createFrame(22, 22, Gravity.CENTER))
        container.addView(iconCircle, LayoutHelper.createLinear(50, 50, 0f, Gravity.CENTER_HORIZONTAL, 0f, 0f, 0f, 16f))

        val titleText = TextView(context).apply {
            text = getString(R.string.thread_list_empty_title)
            setTextColor(themeColors.tabLabelActive)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        container.addView(titleText, LayoutHelper.createLinear(
            LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f,
            Gravity.CENTER_HORIZONTAL, 0f, 0f, 0f, 8f
        ))

        val descText = TextView(context).apply {
            text = getString(R.string.thread_list_empty_description)
            setTextColor(themeColors.textDisabled)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            gravity = Gravity.CENTER
            maxWidth = LayoutHelper.dp(300)
        }
        container.addView(descText, LayoutHelper.createLinear(
            LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f,
            Gravity.CENTER_HORIZONTAL, 0f, 0f, 0f, 8f
        ))

        val createButton = TextView(context).apply {
            text = context.getString(R.string.create_thread_empty_cta)
            setTextColor(themeColors.onPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(themeColors.blurple)
                cornerRadius = LayoutHelper.dpf(50f)
            }
            val padH = LayoutHelper.dp(16)
            val padV = LayoutHelper.dp(2)
            setPadding(padH, padV, padH, padV)
            setOnClickListener {
                openCreateThread()
            }
        }
        emptyCreateButton = createButton
        createButton.visibility = if (permissionPolicy.canCreateThreadFromThreadList(channelId, clanId)) {
            View.VISIBLE
        } else {
            View.GONE
        }
        container.addView(createButton, LayoutHelper.createLinear(150, 50, 0f, Gravity.CENTER_HORIZONTAL, 0f, 20f, 0f, 0f))

        return container
    }

    private fun buildPaginationBar(context: Context): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(themeColors.surface)
        }

        prevButton = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_media_previous)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(themeColors.surfaceVariant)
            }
            val pad = LayoutHelper.dp(5)
            setPadding(pad, pad, pad, pad)
            setOnClickListener {
                if (currentPage > 1) {
                    currentPage--
                    fetchThreads(currentPage)
                }
            }
        }
        row.addView(prevButton, LayoutHelper.createLinear(30, 30, 0f, Gravity.CENTER_VERTICAL, 4f, 10f, 4f, 10f))

        val spacerLeft = View(context)
        row.addView(spacerLeft, LayoutHelper.createLinear(0, 0, 1f))

        pageText = TextView(context).apply {
            text = "1"
            setTextColor(themeColors.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        row.addView(pageText, LayoutHelper.createLinear(
            LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL
        ))

        val spacerRight = View(context)
        row.addView(spacerRight, LayoutHelper.createLinear(0, 0, 1f))

        nextButton = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_media_next)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(themeColors.surfaceVariant)
            }
            val pad = LayoutHelper.dp(5)
            setPadding(pad, pad, pad, pad)
            setOnClickListener {
                if (!isNextDisabled) {
                    currentPage++
                    fetchThreads(currentPage)
                }
            }
        }
        row.addView(nextButton, LayoutHelper.createLinear(30, 30, 0f, Gravity.CENTER_VERTICAL, 4f, 10f, 4f, 10f))

        return row
    }

    private fun onSearchTextChanged(text: String) {
        searchJob?.cancel()
        if (text.isBlank()) {
            isSearchMode = false
            showThreadList(allThreads)
            return
        }
        isSearchMode = true
        searchJob = fragmentScope.launch(Dispatchers.Main) {
            delay(300)
            searchThreads(text.lowercase())
        }
    }

    private fun fetchThreads(page: Int) {
        Log.d(TAG, "fetchThreads page=$page channelId=$channelId clanId=$clanId")
        fetchJob?.cancel()
        val showBlockingLoad = allThreads.isEmpty() && !isSearchMode
        if (showBlockingLoad) {
            loadingView?.visibility = View.VISIBLE
            emptyView?.visibility = View.GONE
            recyclerView?.visibility = View.GONE
            paginationBar?.visibility = View.GONE
        } else {
            loadingView?.visibility = View.GONE
            setPaginationLoading(true)
        }

        fetchJob = fragmentScope.launch(Dispatchers.Main) {
            try {
                val response = withContext(ioDispatcher) {
                    sessionManager.withAutoRefresh { session ->
                        Log.d(TAG, "calling API apiUrl=${session.apiUrl}")
                        api.listThreadDescs(session.apiUrl, session.token, channelId, clanId, page)
                    }
                }
                val rawList = response.channeldescList
                Log.d(TAG, "API returned ${rawList.size} channels")
                rawList.forEachIndexed { i, ch ->
                    Log.d(TAG, "[$i] id=${ch.channelId} label=${ch.channelLabel} active=${ch.active} hasLastMsg=${ch.hasLastSentMessage()} ts=${if (ch.hasLastSentMessage()) ch.lastSentMessage.timestampSeconds else 0}")
                }
                val threads = mergeCachedThreads(rawList.map { it.toThreadInfo() })
                allThreads.clear()
                allThreads.addAll(threads)

                isNextDisabled = rawList.size < LIMIT
                isPaginationVisible = !(page == 1 && rawList.size < LIMIT)

                loadingView?.visibility = View.GONE
                setPaginationLoading(false)

                if (threads.isEmpty()) {
                    emptyView?.visibility = View.VISIBLE
                    recyclerView?.visibility = View.GONE
                    paginationBar?.visibility = View.GONE
                } else {
                    showThreadList(threads, scrollToTop = !showBlockingLoad)
                }

                updatePaginationState()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) return@launch
                Log.e(TAG, "fetchThreads failed", e)
                loadingView?.visibility = View.GONE
                setPaginationLoading(false)
                if (allThreads.isEmpty()) {
                    emptyView?.visibility = View.VISIBLE
                    recyclerView?.visibility = View.GONE
                }
            }
        }
    }

    private fun refreshCachedThreadList(animateChanges: Boolean = true) {
        if (!::channelController.isInitialized) return
        val merged = mergeCachedThreads(allThreads)
        if (merged == allThreads) return
        allThreads.clear()
        allThreads.addAll(merged)
        loadingView?.visibility = View.GONE
        setPaginationLoading(false)
        val query = searchInput?.text?.toString().orEmpty()
        if (isSearchMode && query.isNotBlank()) {
            searchThreads(query.lowercase())
        } else {
            showThreadList(merged, animateChanges = animateChanges)
            updatePaginationState()
        }
    }

    private fun mergeCachedThreads(remoteThreads: List<ThreadInfo>): List<ThreadInfo> {
        val byId = LinkedHashMap<Long, ThreadInfo>()
        for (thread in remoteThreads) {
            byId[thread.channelId] = thread
        }
        val cachedThreads = channelController.getChannels(clanId)
            .filter { it.parentId == channelId && (it.isThread || it.type == CHANNEL_TYPE_THREAD) }
        for (channel in cachedThreads) {
            val local = channel.toThreadInfoFromCache()
            val current = byId[channel.channelId]
            byId[channel.channelId] = if (current == null) {
                local
            } else {
                current.copy(
                    parentId = current.parentId.takeIf { it != 0L } ?: local.parentId,
                    channelLabel = current.channelLabel.ifBlank { local.channelLabel },
                    active = if (current.active != 0) current.active else local.active,
                    isPrivate = current.isPrivate || local.isPrivate,
                    lastMessageTs = maxOf(current.lastMessageTs, local.lastMessageTs)
                )
            }
        }
        return byId.values.sortedWith(
            compareByDescending<ThreadInfo> { it.lastMessageTs }
                .thenByDescending { it.channelId }
        )
    }

    private fun ClanChannelEntity.toThreadInfoFromCache(): ThreadInfo {
        val threadActive = when {
            active != 0 -> active
            isPrivate -> ThreadStatus.ACTIVE_PRIVATE
            else -> ThreadStatus.ACTIVE_PUBLIC
        }
        return ThreadInfo(
            channelId = channelId,
            clanId = clanId,
            parentId = parentId,
            channelLabel = channelLabel,
            active = threadActive,
            isPrivate = isPrivate,
            creatorId = 0L,
            creatorName = "",
            lastSenderId = 0L,
            lastMessageContent = "",
            lastMessageTs = lastSentMessageTs
        )
    }

    private fun setPaginationLoading(loading: Boolean) {
        val alpha = if (loading) 0.45f else 1f
        prevButton?.alpha = if (loading) alpha else if (currentPage <= 1) 0.3f else 1f
        nextButton?.alpha = if (loading) alpha else if (isNextDisabled) 0.3f else 1f
        pageText?.alpha = alpha
        prevButton?.isEnabled = !loading && currentPage > 1
        nextButton?.isEnabled = !loading && !isNextDisabled
    }

    private fun searchThreads(label: String) {
        Log.d(TAG, "searchThreads label=$label in ${allThreads.size} threads")
        val filtered = allThreads.filter {
            it.channelLabel.lowercase().contains(label)
        }
        Log.d(TAG, "search matched ${filtered.size} results")

        if (filtered.isEmpty()) {
            emptyView?.visibility = View.VISIBLE
            recyclerView?.visibility = View.GONE
            paginationBar?.visibility = View.GONE
        } else {
            emptyView?.visibility = View.GONE
            recyclerView?.visibility = View.VISIBLE
            paginationBar?.visibility = View.GONE
            val title = if (filtered.size > 1) "${filtered.size} Search Results" else "1 Search Result"
            adapter?.setData(listOf(ThreadSection(title, filtered)), animateChanges = false)
        }
    }

    private fun showThreadList(
        threads: List<ThreadInfo>,
        animateChanges: Boolean = false,
        scrollToTop: Boolean = false
    ) {
        val sections = buildThreadSections(
            getString(R.string.thread_list_joined_threads),
            getString(R.string.thread_list_active_threads),
            getString(R.string.thread_list_archived_threads),
            threads
        )
        Log.d(TAG, "showThreadList threads=${threads.size} sections=${sections.size}")
        sections.forEach { s -> Log.d(TAG, "section '${s.title}' count=${s.threads.size}") }
        if (sections.isEmpty()) {
            emptyView?.visibility = View.VISIBLE
            recyclerView?.visibility = View.GONE
            paginationBar?.visibility = View.GONE
        } else {
            emptyView?.visibility = View.GONE
            recyclerView?.visibility = View.VISIBLE
            adapter?.setData(sections, animateChanges)
            if (scrollToTop) {
                recyclerView?.scrollToPosition(0)
            }
        }
    }

    private fun updatePaginationState() {
        if (isPaginationVisible) {
            paginationBar?.visibility = View.VISIBLE
            pageText?.text = currentPage.toString()
            prevButton?.alpha = if (currentPage <= 1) 0.3f else 1.0f
            prevButton?.isEnabled = currentPage > 1
            nextButton?.alpha = if (isNextDisabled) 0.3f else 1.0f
            nextButton?.isEnabled = !isNextDisabled
        } else {
            paginationBar?.visibility = View.GONE
        }
    }

    private fun resolveSenderName(senderId: Long): String {
        if (senderId == 0L) return ""
        val member = memberResolver.resolveMember(senderId, clanId, channelId, CHANNEL_TYPE_THREAD)
        return member?.let {
            it.clanNick.ifBlank { it.displayName.ifBlank { it.username } }
        } ?: ""
    }

    private fun navigateToThread(thread: ThreadInfo) {
        val existing = channelController.findChannelById(thread.channelId, thread.clanId)
        val parent = if (thread.parentId != 0L) {
            channelController.findChannelById(thread.parentId, thread.clanId)
        } else null
        channelController.upsertChannel(thread.toClanChannelEntity(existing, parent))
        (getParentActivity() as? MainActivity)?.openChat(
            thread.channelId, thread.channelLabel, thread.clanId, CHANNEL_TYPE_THREAD
        )
    }
}
