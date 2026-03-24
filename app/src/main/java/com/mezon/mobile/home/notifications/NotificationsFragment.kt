package com.mezon.mobile.home.notifications

import android.content.Context
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.RecyclerListView
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.clans.ChannelController
import com.mezon.mobile.home.clans.ClansController

private data class TabDef(val category: Int, val labelRes: Int)

class NotificationsFragment : BaseFragment() {

    private lateinit var store: NotificationStore
    private lateinit var clansController: ClansController
    private lateinit var channelController: ChannelController

    var onOpenChat: ((channelId: Long, channelName: String, clanId: Long, channelType: Int, messageId: Long) -> Unit)? = null

    private lateinit var root: LinearLayout
    private lateinit var tabContainer: LinearLayout
    private lateinit var recyclerView: RecyclerListView
    private lateinit var loadingView: ProgressBar
    private lateinit var emptyView: TextView
    private lateinit var adapter: NotificationAdapter

    private val tabs = listOf(
        TabDef(NOTIF_CATEGORY_MENTIONS, R.string.notif_tab_mentions),
        TabDef(NOTIF_CATEGORY_MESSAGES, R.string.notif_tab_messages),
        TabDef(NOTIF_CATEGORY_FOR_YOU, R.string.notif_tab_for_you)
    )
    private var currentCategory = NOTIF_CATEGORY_MENTIONS
    private val tabViews = mutableListOf<TextView>()

    private val isLoadingMoreMap = mutableMapOf(
        NOTIF_CATEGORY_MENTIONS to false,
        NOTIF_CATEGORY_MESSAGES to false,
        NOTIF_CATEGORY_FOR_YOU to false
    )

    private val scrollStates = mutableMapOf<Int, android.os.Parcelable?>()

    override fun onInject(entryPoint: FragmentEntryPoint) {
        store = entryPoint.notificationStore()
        clansController = entryPoint.clansController()
        channelController = entryPoint.channelController()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()

        observe(NotificationCenter.clansDidLoad) { _, _, _ ->
            if (fragmentView == null || isPaused) return@observe
            val id = clansController.selectedClanId.value
            if (id != 0L) store.setCurrentClan(id)
        }
        observe(NotificationCenter.notificationsDidLoad) { _, _, args ->
            if (fragmentView == null || isPaused) return@observe
            val category = args.firstOrNull() as? Int ?: return@observe
            isLoadingMoreMap[category] = false
            if (category == currentCategory) refreshList()
        }
        observe(NotificationCenter.notificationsLoadError) { _, _, args ->
            if (fragmentView == null || isPaused) return@observe
            val category = args.firstOrNull() as? Int ?: return@observe
            isLoadingMoreMap[category] = false
            if (category == currentCategory) showEmpty()
        }
        observe(NotificationCenter.updateInterfaces) { _, _, args ->
            if (fragmentView == null || isPaused) return@observe
            val mask = args.firstOrNull() as? Int ?: 0
            updateVisibleRows(mask)
        }
        observe(NotificationCenter.themeChanged) { _, _, _ ->
            if (fragmentView == null) return@observe
            root.setBackgroundColor(themeColors.background)
            emptyView.setTextColor(themeColors.onSurfaceVariant)
            rebuildTabChipColors()
            adapter.notifyDataSetChanged()
        }

        return true
    }

    override fun createView(context: Context): View {
        root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.background)
        }

        root.addView(buildHeader(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val tabScrollView = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(themeColors.surface)
        }
        tabContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = LayoutHelper.dp(12)
            setPadding(pad, LayoutHelper.dp(8), pad, LayoutHelper.dp(8))
        }
        tabScrollView.addView(tabContainer)
        root.addView(tabScrollView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        buildTabChips(context)

        val contentFrame = FrameLayout(context)
        root.addView(contentFrame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        recyclerView = RecyclerListView(context).apply {
            layoutManager = LinearLayoutManager(context)
            setHasFixedSize(false)
            visibility = View.GONE
        }
        contentFrame.addView(recyclerView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        loadingView = ProgressBar(context).apply { visibility = View.GONE }
        contentFrame.addView(loadingView, LayoutHelper.createFrame(48, 48, Gravity.CENTER))

        emptyView = TextView(context).apply {
            text = getString(R.string.notif_empty)
            setTextColor(themeColors.onSurfaceVariant)
            textSize = 16f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        contentFrame.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        adapter = NotificationAdapter(theme = themeColors)
        recyclerView.adapter = adapter
        recyclerView.setOnItemClickListener(RecyclerListView.OnItemClickListener { view, _ ->
            if (view is NotificationCell) {
                val entity = view.entity ?: return@OnItemClickListener
                handleNotificationPress(entity)
            }
        })
        recyclerView.setOnItemLongClickListener(RecyclerListView.OnItemLongClickListener { view, _ ->
            if (view is NotificationCell) {
                val entity = view.entity ?: return@OnItemLongClickListener false
                store.deleteNotification(entity.id, currentCategory)
                true
            } else false
        })

        recyclerView.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && isLoadingMoreMap[currentCategory] != true) {
                    if (recyclerView.scrollState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE) {
                        return
                    }

                    val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                    val visibleItemCount = layoutManager.childCount
                    val totalItemCount = layoutManager.itemCount
                    val firstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition()

                    if ((visibleItemCount + firstVisibleItemPosition) >= totalItemCount && firstVisibleItemPosition >= 0) {
                        if (store.hasMoreForCategory(currentCategory)) {
                            isLoadingMoreMap[currentCategory] = true
                            store.loadMore(currentCategory)
                        }
                    }
                }
            }
        })

        return root
    }

    override fun onBecomeFullyVisible() {
        super.onBecomeFullyVisible()

        val clanId = clansController.selectedClanId.value
        store.setCurrentClan(clanId)

        selectTab(currentCategory, forceRefresh = false)
    }

    private fun handleNotificationPress(entity: NotificationEntity) {
        Log.d("NotifNav", "press entity: id=${entity.id} channelId=${entity.channelId}")
        val channelId = entity.channelId
        if (channelId == 0L) { Log.w("NotifNav", "channelId == 0, skip"); return }
        val channelName = if (entity.clanId == 0L) {
            entity.senderName.ifEmpty { entity.channelLabel }
        } else {
            entity.channelLabel
                .ifEmpty { channelController.findChannelById(channelId)?.channelLabel ?: "" }
                .ifEmpty { entity.subject.substringAfterLast("#").trimEnd(')').trim() }
                .ifEmpty { entity.clanName }
        }
        val clanId = entity.clanId
        val channelType = entity.channelType.takeIf { it != 0 } ?: 1
        onOpenChat?.invoke(channelId, channelName, clanId, channelType, entity.messageId)
    }

    private fun buildHeader(context: Context): View {
        val header = FrameLayout(context).apply {
            setBackgroundColor(themeColors.surface)
            val pad = LayoutHelper.dp(16)
            setPadding(pad, LayoutHelper.dp(16), pad, LayoutHelper.dp(12))
        }
        val title = TextView(context).apply {
            text = getString(R.string.notif_inbox_title)
            setTextColor(themeColors.onSurface)
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        header.addView(title, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL))
        return header
    }

    private fun buildTabChips(context: Context) {
        tabContainer.removeAllViews()
        tabViews.clear()
        tabs.forEach { tab ->
            val chip = buildChip(context, getString(tab.labelRes), tab.category == currentCategory)
            chip.setOnClickListener { 
                val isSameTab = tab.category == currentCategory
                selectTab(tab.category, forceRefresh = isSameTab) 
            }
            val margin = LayoutHelper.dp(6)
            tabContainer.addView(chip, LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                0f, Gravity.CENTER_VERTICAL, 0f, 0f, margin.toFloat(), 0f
            ))
            tabViews.add(chip)
        }
    }

    private fun buildChip(context: Context, label: String, active: Boolean): TextView {
        return TextView(context).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            val hPad = LayoutHelper.dp(14)
            val vPad = LayoutHelper.dp(6)
            setPadding(hPad, vPad, hPad, vPad)
            setTextColor(if (active) android.graphics.Color.WHITE else themeColors.onSurface)
            background = buildChipBackground(active)
        }
    }

    private fun buildChipBackground(active: Boolean): android.graphics.drawable.Drawable {
        val bg = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = LayoutHelper.dp(20).toFloat()
            setColor(if (active) themeColors.blurple else themeColors.surfaceVariant)
        }
        return android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(themeColors.onSurface and 0x33FFFFFF.toInt()),
            bg, null
        )
    }

    private fun selectTab(category: Int, forceRefresh: Boolean = false) {
        val isTabChanged = currentCategory != category
        if (isTabChanged) {
            scrollStates[currentCategory] = recyclerView.layoutManager?.onSaveInstanceState()
            isLoadingMoreMap[currentCategory] = false
        } else if (forceRefresh) {
            recyclerView.scrollToPosition(0)
        }
        currentCategory = category
        rebuildTabChipColors()
        
        val cached = store.getForCategory(category).value
        if (cached.isNotEmpty()) {
            showList(cached, isTabChanged)
        } else {
            if (isTabChanged) {
                adapter.setData(emptyList(), false, true)
            }
            showLoading()
            isLoadingMoreMap[category] = true 
        } 
        
        if (cached.isEmpty() || forceRefresh) {
            store.loadCategory(category)
        }
    }


    private fun rebuildTabChipColors() {
        tabs.forEachIndexed { index, tab ->
            val chip = tabViews.getOrNull(index) ?: return@forEachIndexed
            val active = tab.category == currentCategory
            chip.setTextColor(if (active) android.graphics.Color.WHITE else themeColors.onSurface)
            chip.background = buildChipBackground(active)
        }
    }

    private fun updateVisibleRows(mask: Int) {
        if (isPaused) return
        if (mask == 0) {
            refreshList()
            return
        }
        val count = recyclerView.childCount
        for (i in 0 until count) {
            val child = recyclerView.getChildAt(i)
            if (child is NotificationCell) {
                child.update(mask)
            }
        }
    }

    private fun refreshList() {
        val items = store.getForCategory(currentCategory).value
        if (items.isEmpty()) showEmpty() else showList(items)
    }

    private fun showLoading() {
        loadingView.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE
        emptyView.visibility = View.GONE
    }

    private fun showEmpty() {
        loadingView.visibility = View.GONE
        recyclerView.visibility = View.GONE
        emptyView.visibility = View.VISIBLE
    }

    private fun showList(items: List<NotificationEntity>, isTabChange: Boolean = false) {
        loadingView.visibility = View.GONE
        emptyView.visibility = View.GONE
        recyclerView.visibility = View.VISIBLE
        val hasMore = store.hasMoreForCategory(currentCategory)
        adapter.setData(items, hasMore, isTabChange)
        
        if (isTabChange) {
            val savedState = scrollStates[currentCategory]
            if (savedState != null) {
                recyclerView.layoutManager?.onRestoreInstanceState(savedState)
            } else {
                recyclerView.scrollToPosition(0)
            }
        }
    }
}
