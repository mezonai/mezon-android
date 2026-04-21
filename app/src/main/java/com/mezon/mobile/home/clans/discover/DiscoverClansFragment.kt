package com.mezon.mobile.home.clans.discover

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.RecyclerListView
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.session.SessionManager
import com.mezon.mobile.ui.cells.MezonIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DiscoverClansFragment : BaseFragment() {

    companion object {
        private const val FOOTER_TYPE = 1
        private const val ROW_TYPE = 0
    }

    private lateinit var api: MezonApi
    private lateinit var sessionManager: SessionManager

    private lateinit var recyclerView: RecyclerListView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var listAdapter: DiscoverAdapter

    private val allItems = ArrayList<DiscoverClanItem>()
    private var currentPage = 1
    private var serverPageCount = 1
    private var loading = false
    private var loadingMore = false
    private var loadError: String? = null
    private var initialLoadFinished = false
    private lateinit var emptyStateView: TextView

    private val searchDebounceHandler = Handler(Looper.getMainLooper())
    private var searchDebounceRunnable: Runnable? = null

    override fun onInject(entryPoint: FragmentEntryPoint) {
        api = entryPoint.mezonApi()
        sessionManager = entryPoint.sessionManager()
    }

    override fun createView(context: Context): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.chatBackground)
        }

        actionBar = createActionBar(context).apply {
            setTitle(context.getString(R.string.discover_community_on_mezon))
        }
        root.addView(actionBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        listAdapter = DiscoverAdapter()

        val discoverHeader = buildDiscoverSearchHeader(context)
        root.addView(discoverHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val headerDivider = View(context).apply {
            setBackgroundColor(themeColors.outlineVariant)
        }
        root.addView(headerDivider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1))

        swipeRefresh = SwipeRefreshLayout(context).apply {
            setColorSchemeColors(themeColors.blurple)
            setProgressBackgroundColorSchemeColor(themeColors.secondaryLight)
            setOnRefreshListener { refresh() }
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
            setOnClickListener {
                if (loadError != null) {
                    loadError = null
                    refresh()
                }
            }
        }

        recyclerView = RecyclerListView(context).apply {
            layoutManager = LinearLayoutManager(context)
            itemAnimator = null
            isVerticalScrollBarEnabled = false
            setPadding(LayoutHelper.dp(12), LayoutHelper.dp(12), LayoutHelper.dp(12), LayoutHelper.dp(100))
            clipToPadding = false
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
                val list = listAdapter.filteredList()
                if (position in list.indices) {
                    presentFragment(DiscoverClanDetailFragment.newInstance(list[position]))
                }
            }
        })

        swipeRefresh.addView(recyclerView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        val contentWrap = FrameLayout(context).apply {
            addView(swipeRefresh, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(
                emptyStateView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply { gravity = Gravity.CENTER }
            )
        }
        root.addView(contentWrap, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        fragmentView = root
        loadInitial()
        return root
    }

    private fun buildDiscoverSearchHeader(context: Context): LinearLayout {
        val wrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                LayoutHelper.dp(12),
                LayoutHelper.dp(10),
                LayoutHelper.dp(12),
                LayoutHelper.dp(14)
            )
        }
        val navBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val searchWrap = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val innerPad = LayoutHelper.dp(10)
            setPadding(innerPad, 0, innerPad, 0)
            background = GradientDrawable().apply {
                setColor(themeColors.secondaryLight)
                cornerRadius = LayoutHelper.dp(10f).toFloat()
                setStroke(LayoutHelper.dp(1), themeColors.outlineVariant)
            }
            layoutParams = LinearLayout.LayoutParams(0, LayoutHelper.dp(36), 1f)
        }
        searchWrap.addView(ImageView(context).apply {
            setImageDrawable(MezonIcon.magnifyingIcon.getDrawable(context, themeColors.onSurface))
            val p = LayoutHelper.dp(18)
            layoutParams = LinearLayout.LayoutParams(p, p)
        })
        val edit = EditText(context).apply {
            hint = context.getString(R.string.discover_explore_communities)
            setHintTextColor(themeColors.onSurfaceVariant)
            setTextColor(themeColors.onSurface)
            textSize = 14f
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            setPadding(0, 0, LayoutHelper.dp(10), 0)
            setLineSpacing(0f, 1f)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(0, LayoutHelper.MATCH_PARENT, 1f)
            setText(DiscoverFilterHolder.searchQuery)
        }
        searchDebounceRunnable = Runnable {
            DiscoverFilterHolder.searchQuery = edit.text?.toString().orEmpty()
            listAdapter.notifyDataSetChanged()
            updateEmptyState()
        }
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val r = searchDebounceRunnable ?: return
                searchDebounceHandler.removeCallbacks(r)
                searchDebounceHandler.postDelayed(r, 300)
            }
        })
        searchWrap.addView(edit)
        navBar.addView(searchWrap)

        fun iconButton(icon: MezonIcon): ImageView {
            return ImageView(context).apply {
                val circleBg = GradientDrawable().apply {
                    cornerRadius = LayoutHelper.dp(10f).toFloat()
                    setColor(themeColors.secondaryLight)
                }
                background = RippleDrawable(
                    ColorStateList.valueOf(themeColors.onSurface and 0x1AFFFFFF),
                    circleBg,
                    GradientDrawable().apply {
                        setColor(0xFFFFFFFF.toInt())
                        cornerRadius = LayoutHelper.dp(10f).toFloat()
                    }
                )
                setImageDrawable(icon.getDrawable(context, themeColors.onSurface))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                val p = LayoutHelper.dp(8)
                setPadding(p, p, p, p)
                layoutParams = LinearLayout.LayoutParams(LayoutHelper.dp(36), LayoutHelper.dp(36)).apply {
                    leftMargin = LayoutHelper.dp(8)
                }
            }
        }
        val qrBtn = iconButton(MezonIcon.scanQR)
        qrBtn.setOnClickListener {
            Toast.makeText(context, context.getString(R.string.feature_coming_soon), Toast.LENGTH_SHORT).show()
        }
        navBar.addView(qrBtn)
        val addFriendBtn = iconButton(MezonIcon.userPlusIcon)
        addFriendBtn.setOnClickListener {
            Toast.makeText(context, context.getString(R.string.feature_coming_soon), Toast.LENGTH_SHORT).show()
        }
        navBar.addView(addFriendBtn)

        wrap.addView(navBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        return wrap
    }

    override fun onBecomeFullyVisible() {
        super.onBecomeFullyVisible()
        updateEmptyState()
        listAdapter.notifyDataSetChanged()
    }

    private fun refresh() {
        if (loading) {
            swipeRefresh.isRefreshing = false
            return
        }
        currentPage = 1
        allItems.clear()
        fragmentScope.launch { loadPage(1, isRefresh = true) }
    }

    private fun loadInitial() {
        fragmentScope.launch { loadPage(1, isRefresh = false) }
    }

    private fun loadMore() {
        if (loadingMore || loading || currentPage >= serverPageCount) return
        fragmentScope.launch { loadPage(currentPage + 1, isRefresh = false) }
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
            swipeRefresh.isRefreshing = false
            updateEmptyState()
            listAdapter.notifyDataSetChanged()
        }
    }

    private fun resolveLoadErrorMessage(e: Throwable): String {
        val msg = e.message.orEmpty()
        val ctx = fragmentView?.context ?: return msg.ifBlank { "Error" }
        return when {
            msg.contains("404") -> ctx.getString(R.string.discover_list_load_error)
            msg.isNotBlank() -> msg
            else -> ctx.getString(R.string.discover_list_load_error)
        }
    }

    private fun updateEmptyState() {
        val filtered = filteredItems()
        val q = DiscoverFilterHolder.searchQuery.trim()
        when {
            loadError != null -> {
                emptyStateView.visibility = View.VISIBLE
                emptyStateView.text = loadError
            }
            q.isNotEmpty() && filtered.isEmpty() && !loading -> {
                emptyStateView.visibility = View.VISIBLE
                emptyStateView.setText(R.string.discover_list_empty_search)
            }
            filtered.isEmpty() && !loading && initialLoadFinished -> {
                emptyStateView.visibility = View.GONE
            }
            else -> {
                emptyStateView.visibility = View.GONE
            }
        }
    }

    private fun filteredItems(): List<DiscoverClanItem> {
        val q = DiscoverFilterHolder.searchQuery.trim().lowercase()
        if (q.isEmpty()) return allItems.toList()
        return allItems.filter {
            it.clanName.lowercase().contains(q) || it.description.lowercase().contains(q)
        }
    }

    private inner class DiscoverAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        fun filteredList(): List<DiscoverClanItem> = filteredItems()

        override fun getItemViewType(position: Int): Int {
            val rows = filteredItems().size
            if (loadingMore && position == rows) return FOOTER_TYPE
            return ROW_TYPE
        }

        override fun getItemCount(): Int {
            val rows = filteredItems().size
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
                        gravity = android.view.Gravity.CENTER
                    })
                }
                return object : RecyclerView.ViewHolder(wrap) {}
            }
            val cell = DiscoverClanCell(parent.context, themeColors)
            return object : RecyclerView.ViewHolder(cell) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder.itemView is DiscoverClanCell) {
                val list = filteredItems()
                if (position in list.indices) {
                    holder.itemView.setData(list[position])
                }
            }
        }
    }

    private fun View.setData(item: DiscoverClanItem) {
        (this as DiscoverClanCell).setData(item)
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        return true
    }
}
