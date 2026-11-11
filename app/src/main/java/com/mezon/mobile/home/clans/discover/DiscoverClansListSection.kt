package com.mezon.mobile.home.clans.discover

import com.mezon.mobile.util.NetworkErrorMessages
import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.RecyclerListView
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.session.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DiscoverClansListSection(
    private val host: BaseFragment,
    private val themeColors: ThemeColors,
    private val embeddedInClanPanel: Boolean
) {

    companion object {
        private const val FOOTER_TYPE = 1
        private const val ROW_TYPE = 0
    }

    lateinit var rootView: FrameLayout

    private lateinit var api: MezonApi
    private lateinit var sessionManager: SessionManager

    private lateinit var recyclerView: RecyclerListView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var listAdapter: DiscoverAdapter

    private val allItems = ArrayList<DiscoverClanItem>()
    private val displayItems = ArrayList<DiscoverClanItem>()
    private var currentPage = 1
    private var serverPageCount = 1
    private var loading = false
    private var loadingMore = false
    private var loadError: String? = null
    private var initialLoadFinished = false
    private lateinit var emptyStateView: TextView
    private var loadStarted = false

    fun inject(entryPoint: FragmentEntryPoint) {
        api = entryPoint.mezonApi()
        sessionManager = entryPoint.sessionManager()
    }

    fun buildViews(context: Context) {
        listAdapter = DiscoverAdapter()
        val contentBg = if (embeddedInClanPanel) Color.TRANSPARENT else themeColors.chatBackground
        swipeRefresh = SwipeRefreshLayout(context).apply {
            setColorSchemeColors(themeColors.blurple)
            setProgressBackgroundColorSchemeColor(themeColors.secondaryLight)
            setOnRefreshListener { refresh() }
            setBackgroundColor(if (embeddedInClanPanel) Color.TRANSPARENT else themeColors.secondaryLight)
        }
        emptyStateView = TextView(context).apply {
            visibility = View.GONE
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(
                LayoutHelper.dp(40),
                LayoutHelper.dp(40),
                LayoutHelper.dp(40),
                LayoutHelper.dp(40)
            )
            setTextColor(themeColors.textDisabled)
            textSize = 14f
            setBackgroundColor(if (embeddedInClanPanel) Color.TRANSPARENT else contentBg)
            setOnClickListener {
                if (loadError != null) {
                    loadError = null
                    refresh()
                }
            }
        }
        recyclerView = RecyclerListView(context).apply {
            layoutManager = LinearLayoutManager(context).apply {
                isItemPrefetchEnabled = false
                initialPrefetchItemCount = 0
            }
            itemAnimator = null
            isVerticalScrollBarEnabled = false
            setPadding(LayoutHelper.dp(12), LayoutHelper.dp(12), LayoutHelper.dp(12), LayoutHelper.dp(100))
            clipToPadding = false
            setBackgroundColor(if (embeddedInClanPanel) Color.TRANSPARENT else contentBg)
            setSelectorType(RecyclerListView.SELECTOR_ROUNDRECT)
            setSelectorRadius(LayoutHelper.dp(6))
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    val lm = rv.layoutManager as? LinearLayoutManager ?: return
                    val last = lm.findLastVisibleItemPosition()
                    val total = listAdapter.itemCount
                    if (last >= total - 3 && dy > 0 && !loading && !loadingMore && currentPage < serverPageCount) {
                        loadMore()
                    }
                }
            })
        }
        recyclerView.adapter = listAdapter
        recyclerView.setOnItemClickListener(object : RecyclerListView.OnItemClickListener {
            override fun onItemClick(view: View, position: Int) {
                if (view !is DiscoverClanCell) return
                if (position in displayItems.indices) {
                    host.presentFragment(DiscoverClanDetailFragment.newInstance(displayItems[position]))
                }
            }
        })
        swipeRefresh.addView(recyclerView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        rootView = FrameLayout(context).apply {
            setBackgroundColor(if (embeddedInClanPanel) Color.TRANSPARENT else contentBg)
            addView(swipeRefresh, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))
            addView(
                emptyStateView,
                LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER)
            )
        }
    }

    fun ensureLoadsIfEmbedded() {
        if (!embeddedInClanPanel) return
        if (!loadStarted) {
            loadStarted = true
            host.fragmentScope.launch { loadPage(1, isRefresh = false) }
        }
    }

    fun onEmbeddedVisibilityChanged(active: Boolean) {
        if (!embeddedInClanPanel) return
        if (active && !loadStarted) ensureLoadsIfEmbedded()
        else if (active && loadStarted) {
            rebuildDisplayItems()
            updateEmptyState()
            listAdapter.notifyDataSetChanged()
        }
    }

    fun onSearchFilterChangedFromParent() {
        rebuildDisplayItems()
        listAdapter.notifyDataSetChanged()
        updateEmptyState()
    }

    private fun refresh() {
        if (loading) {
            swipeRefresh.isRefreshing = false
            return
        }
        currentPage = 1
        allItems.clear()
        rebuildDisplayItems()
        listAdapter.notifyDataSetChanged()
        host.fragmentScope.launch { loadPage(1, isRefresh = true) }
    }

    private fun loadMore() {
        if (loadingMore || loading || currentPage >= serverPageCount) return
        host.fragmentScope.launch { loadPage(currentPage + 1, isRefresh = false) }
    }

    private suspend fun loadPage(page: Int, isRefresh: Boolean) {
        if (page == 1) {
            loadError = null
        }
        withContext(Dispatchers.Main) {
            if (page == 1) {
                swipeRefresh.isRefreshing = true
                updateEmptyState()
            }
        }
        if (page == 1 && !isRefresh) {
            loading = true
        } else if (page > 1) {
            loadingMore = true
            withContext(Dispatchers.Main) { listAdapter.notifyDataSetChanged() }
        } else if (isRefresh) {
            loading = true
        }

        val result = runCatching {
            sessionManager.withAutoRefresh { _ ->
                withContext(Dispatchers.IO) {
                    api.listClanDiscover(page = page)
                }
            }
        }

        result.onSuccess { response ->
            loadError = null
            serverPageCount = response.pageCount.coerceAtLeast(1)
            currentPage = response.pageNumber.takeIf { it > 0 } ?: page
            val newItems = response.clanDiscoverList.map { DiscoverClanItem.fromProto(it) }
            if (page == 1) {
                allItems.clear()
            }
            val existing = allItems.map { it.clanId }.toHashSet()
            for (n in newItems) {
                if (!existing.contains(n.clanId)) {
                    allItems.add(n)
                    existing.add(n.clanId)
                }
            }
        }

        result.onFailure { e ->
            if (page == 1) {
                loadError = resolveLoadErrorMessage(e)
            }
        }

        loading = false
        loadingMore = false
        initialLoadFinished = true
        withContext(Dispatchers.Main) {
            rebuildDisplayItems()
            swipeRefresh.isRefreshing = false
            updateEmptyState()
            listAdapter.notifyDataSetChanged()
        }
    }

    private fun resolveLoadErrorMessage(e: Throwable): String {
        val ctx = host.fragmentView?.context ?: return ""
        val fallback = ctx.getString(R.string.discover_list_load_error)
        if (e.message.orEmpty().contains("404")) return fallback
        return NetworkErrorMessages.userMessage(ctx, e, fallback)
    }

    private fun updateEmptyState() {
        val q = DiscoverFilterHolder.searchQuery.trim()
        when {
            loadError != null -> {
                emptyStateView.visibility = View.VISIBLE
                emptyStateView.text = loadError
            }
            q.isNotEmpty() && displayItems.isEmpty() && !loading -> {
                emptyStateView.visibility = View.VISIBLE
                emptyStateView.setText(R.string.discover_list_empty_search)
            }
            displayItems.isEmpty() && !loading && initialLoadFinished -> {
                emptyStateView.visibility = View.GONE
            }
            else -> {
                emptyStateView.visibility = View.GONE
            }
        }
    }

    private fun rebuildDisplayItems() {
        displayItems.clear()
        displayItems.addAll(filteredItems())
    }

    private fun filteredItems(): List<DiscoverClanItem> {
        val q = DiscoverFilterHolder.searchQuery.trim().lowercase()
        if (q.isEmpty()) return allItems.toList()
        return allItems.filter {
            it.clanName.lowercase().contains(q) || it.description.lowercase().contains(q)
        }
    }

    fun loadInitialForFullscreen() {
        if (embeddedInClanPanel) return
        loadStarted = true
        host.fragmentScope.launch { loadPage(1, isRefresh = false) }
    }

    fun onFullscreenBecomeVisible() {
        if (embeddedInClanPanel) return
        rebuildDisplayItems()
        updateEmptyState()
        listAdapter.notifyDataSetChanged()
    }

    private inner class DiscoverAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        init {
            setHasStableIds(true)
        }

        override fun getItemViewType(position: Int): Int {
            val rows = displayItems.size
            if (loadingMore && position == rows) return FOOTER_TYPE
            return ROW_TYPE
        }

        override fun getItemId(position: Int): Long {
            val rows = displayItems.size
            if (loadingMore && position == rows) return Long.MIN_VALUE
            return displayItems.getOrNull(position)?.clanId ?: RecyclerView.NO_ID
        }

        override fun getItemCount(): Int {
            val rows = displayItems.size
            return rows + if (loadingMore) 1 else 0
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == FOOTER_TYPE) {
                val pb = ProgressBar(parent.context).apply {
                    indeterminateDrawable?.colorFilter = PorterDuffColorFilter(themeColors.blurple, PorterDuff.Mode.SRC_IN)
                }
                val wrap = FrameLayout(parent.context).apply {
                    setPadding(0, LayoutHelper.dp(20), 0, LayoutHelper.dp(20))
                    addView(pb, FrameLayout.LayoutParams(LayoutHelper.dp(32), LayoutHelper.dp(32)).apply {
                        gravity = Gravity.CENTER
                    })
                }
                return object : RecyclerView.ViewHolder(wrap) {}
            }
            val cell = DiscoverClanCell(parent.context, themeColors)
            return object : RecyclerView.ViewHolder(cell) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder.itemView is DiscoverClanCell) {
                if (position in displayItems.indices) {
                    (holder.itemView as DiscoverClanCell).setData(displayItems[position])
                }
            }
        }
    }
}
