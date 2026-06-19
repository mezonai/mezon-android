package com.mezon.mobile.home.chat.channelinfo

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
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
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.BuildConfig
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.RecyclerListView
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.clans.ChannelCanvasController
import com.mezon.mobile.home.clans.ChannelCanvasData
import com.mezon.mobile.home.clans.ChannelCanvasFragment
import com.mezon.mobile.ui.MezonToast
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.cells.ToastOverlay

class CanvasTabHelper(
    private val channelId: Long,
    private val clanId: Long,
    private val channelType: Int,
    private val themeColors: ThemeColors,
    private val channelCanvasController: ChannelCanvasController,
    private val hostActivity: Activity,
    private val hostFragment: BaseFragment,
    private val presentFragment: (BaseFragment) -> Unit,
    private val getString: (Int) -> String
) : TabHelper {

    companion object {
        private const val SEARCH_HEIGHT_DP = 40f
        private const val SEARCH_CORNER_RADIUS_DP = 8f
    }

    private var adapter: CanvasListAdapter? = null
    private var recyclerView: RecyclerListView? = null
    private var emptyView: View? = null
    private var loadingView: ProgressBar? = null
    private var searchText: String = ""
    private val debounce = Handler(Looper.getMainLooper())
    private var debounceRun: Runnable? = null
    private var lastRefreshRevision = -1
    private var lastRefreshSearch = ""
    private var lastRefreshFetching = false
    private var lastRefreshFailed = false
    private var lastRefreshPaging = false
    private var emptyLabel: TextView? = null

    override fun buildView(context: Context): View {
        val root = FrameLayout(context)

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(12f), LayoutHelper.dp(10f), LayoutHelper.dp(12f), LayoutHelper.dp(8f))
        }

        val searchContainer = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(themeColors.surfaceVariant)
                cornerRadius = LayoutHelper.dpf(SEARCH_CORNER_RADIUS_DP)
            }
            clipChildren = true
        }
        val searchIconSize = LayoutHelper.dp(18f)
        val searchIconInset = LayoutHelper.dp(8f)
        val searchTextGap = LayoutHelper.dp(8f)
        val clearSize = LayoutHelper.dp(20f)
        val clearInset = LayoutHelper.dp(8f)
        val searchIcon = ImageView(context).apply {
            val d = MezonIcon.magnifyingIcon.getDrawable(context).mutate()
            d.colorFilter = PorterDuffColorFilter(themeColors.textDisabled, PorterDuff.Mode.SRC_IN)
            d.setBounds(0, 0, searchIconSize, searchIconSize)
            setImageDrawable(d)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        var clearButton: ImageView? = null
        val searchInput = EditText(context).apply {
            hint = getString(R.string.channel_canvas_search_placeholder)
            setHintTextColor(themeColors.textDisabled)
            setTextColor(themeColors.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = null
            isSingleLine = true
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            minHeight = 0
            minimumHeight = 0
            setPadding(
                searchIconInset + searchIconSize + searchTextGap,
                0,
                clearInset + clearSize + searchTextGap,
                0
            )
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val txt = s?.toString().orEmpty()
                    clearButton?.visibility = if (txt.isNotEmpty()) View.VISIBLE else View.GONE
                    debounceRun?.let { debounce.removeCallbacks(it) }
                    val r = Runnable {
                        searchText = txt
                        refreshListUi()
                    }
                    debounceRun = r
                    debounce.postDelayed(r, 500)
                }
            })
        }
        searchContainer.addView(
            searchInput,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        searchContainer.addView(
            searchIcon,
            FrameLayout.LayoutParams(searchIconSize, searchIconSize, Gravity.START or Gravity.CENTER_VERTICAL).apply {
                leftMargin = searchIconInset
            }
        )
        clearButton = ImageView(context).apply {
            visibility = View.GONE
            val d = MezonIcon.circleXIcon.getDrawable(context).mutate()
            d.colorFilter = PorterDuffColorFilter(themeColors.onSurface, PorterDuff.Mode.SRC_IN)
            d.setBounds(0, 0, clearSize, clearSize)
            setImageDrawable(d)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnClickListener {
                searchInput.text = null
                searchText = ""
                visibility = View.GONE
                refreshListUi()
            }
        }
        searchContainer.addView(
            clearButton,
            FrameLayout.LayoutParams(clearSize, clearSize, Gravity.END or Gravity.CENTER_VERTICAL).apply {
                rightMargin = clearInset
            }
        )
        headerRow.addView(
            searchContainer,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, SEARCH_HEIGHT_DP.toInt(), 1f)
        )

        root.addView(headerRow, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP))

        adapter = CanvasListAdapter(
            theme = themeColors,
            onViewCanvas = { viewCanvas(it) },
            onCopyLink = { copyCanvasLink(it) },
        )
        recyclerView = RecyclerListView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@CanvasTabHelper.adapter
            clipToPadding = false
            setPadding(0, 0, 0, LayoutHelper.dp(65f))
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        tryLoadMore()
                    }
                }

                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) return
                    val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                    val total = recyclerView.adapter?.itemCount ?: return
                    if (total == 0) return
                    val last = lm.findLastVisibleItemPosition()
                    if (last >= total - 3) {
                        tryLoadMore()
                    }
                }
            })
        }
        root.addView(
            recyclerView,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP, 0f, 58f, 0f, 0f)
        )

        emptyView = buildEmptyView(context)
        root.addView(
            emptyView,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP, 0f, 58f, 0f, 0f)
        )

        loadingView = ProgressBar(context).apply { visibility = View.VISIBLE }
        root.addView(loadingView, LayoutHelper.createFrame(48, 48, Gravity.CENTER))

        channelCanvasController.loadChannelCanvases(channelId, clanId, channelType)
        refreshListUi()
        return root
    }

    override fun reload() {
        refreshListUi()
    }

    fun onRemoteCanvasesLoaded(ch: Long) {
        if (ch != channelId) return
        lastRefreshRevision = -1
        refreshListUi()
    }

    fun onRemoteCanvasesLoadError(ch: Long) {
        if (ch != channelId) return
        lastRefreshRevision = -1
        refreshListUi()
    }

    fun dispose() {
        debounceRun?.let { debounce.removeCallbacks(it) }
        debounceRun = null
        adapter?.dispose()
        adapter = null
    }

    private fun refreshListUi() {
        val revision = channelCanvasController.getCanvasesRevision(channelId)
        val initialLoading = channelCanvasController.isInitialLoading(channelId)
        val fetching = channelCanvasController.isFetching(channelId) || initialLoading
        val paging = channelCanvasController.isPagingLoading(channelId)
        val failed = channelCanvasController.hasLoadFailed(channelId)
        if (revision == lastRefreshRevision &&
            searchText == lastRefreshSearch &&
            fetching == lastRefreshFetching &&
            failed == lastRefreshFailed &&
            paging == lastRefreshPaging
        ) {
            return
        }
        lastRefreshRevision = revision
        lastRefreshSearch = searchText
        lastRefreshFetching = fetching
        lastRefreshFailed = failed
        lastRefreshPaging = paging

        val canvases = channelCanvasController.getCanvases(channelId)
        val filtered = filterCanvases(canvases, searchText)
        val showFooter = paging &&
            searchText.isEmpty() &&
            channelCanvasController.hasMoreCanvases(channelId) &&
            filtered.isNotEmpty()
        adapter?.setItems(filtered, showLoadingFooter = showFooter)
        val showLoading = fetching && filtered.isEmpty()
        loadingView?.visibility = if (showLoading) View.VISIBLE else View.GONE
        if (filtered.isNotEmpty()) {
            emptyView?.visibility = View.GONE
            recyclerView?.visibility = View.VISIBLE
            return
        }
        recyclerView?.visibility = View.GONE
        if (!fetching) {
            emptyLabel?.text = if (failed) {
                getString(R.string.channel_canvas_load_error)
            } else {
                getString(R.string.channel_canvas_empty)
            }
            emptyView?.visibility = View.VISIBLE
        } else {
            emptyView?.visibility = View.GONE
        }
    }

    private fun tryLoadMore() {
        if (searchText.isNotEmpty()) return
        if (!channelCanvasController.hasMoreCanvases(channelId)) return
        if (channelCanvasController.isPagingLoading(channelId)) return
        if (channelCanvasController.isInitialLoading(channelId)) return
        if (channelCanvasController.isFetching(channelId)) return
        channelCanvasController.loadMoreChannelCanvases(channelId)
    }

    private fun filterCanvases(items: List<ChannelCanvasData>, query: String): List<ChannelCanvasData> {
        val q = normalizeSearchQuery(query)
        if (q.isEmpty()) return items
        val untitled = getString(R.string.channel_canvas_untitled)
        return items.filter {
            normalizeSearchQuery(it.title.replace("\n", " ").ifBlank { untitled }).contains(q)
        }
    }

    private fun viewCanvas(item: ChannelCanvasData) {
        val title = item.title.replace("\n", " ").ifBlank { getString(R.string.channel_canvas_untitled) }
        presentFragment(
            ChannelCanvasFragment.newInstance(
                clanId = clanId,
                channelId = channelId,
                channelType = channelType,
                canvasId = item.id,
                initialTitle = title
            )
        )
    }

    private fun copyCanvasLink(item: ChannelCanvasData) {
        val ctx = hostActivity
        val baseUrl = BuildConfig.MEZON_REDIRECT_URI.trimEnd('/')
        val link = "$baseUrl/chat/clans/$clanId/channels/$channelId/canvas/${item.id}"
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("canvas_link", link))
        MezonToast.show(hostFragment, ToastOverlay.ToastType.INFO, getString(R.string.channel_canvas_link_copied))
    }

    private fun buildEmptyView(context: Context): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            setPadding(0, LayoutHelper.dp(60f), 0, 0)
        }
        val icon = ImageView(context).apply {
            setImageDrawable(MezonIcon.emptySearchIcon.getDrawable(context))
        }
        container.addView(icon, LayoutHelper.createLinear(100, 100, 0f, Gravity.CENTER_HORIZONTAL))
        val label = TextView(context).apply {
            text = getString(R.string.channel_canvas_empty)
            setTextColor(themeColors.textDisabled)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
            gravity = Gravity.CENTER
        }
        emptyLabel = label
        container.addView(
            label,
            LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f,
                Gravity.CENTER_HORIZONTAL, 0f, 12f, 0f, 0f
            )
        )
        return container
    }
}
