package com.mezon.mobile.home.notifications

import android.content.Context
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
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
import com.mezon.mobile.home.DialogsController
import com.mezon.mobile.home.clans.ChannelController
import com.mezon.mobile.home.clans.ClansController
import com.mezon.mobile.network.CHANNEL_TYPE_CHANNEL
import com.mezon.mobile.network.CHANNEL_TYPE_DM
import com.mezon.mobile.network.CHANNEL_TYPE_GROUP
import com.mezon.mobile.ui.cells.MezonIcon

private data class TabDef(val category: Int, val labelRes: Int, val icon: MezonIcon)

class NotificationsFragment : BaseFragment() {

    private lateinit var store: NotificationStore
    private lateinit var clansController: ClansController
    private lateinit var channelController: ChannelController
    private lateinit var dialogsController: DialogsController

    var onOpenChat: ((channelId: Long, channelName: String, clanId: Long, channelType: Int) -> Unit)? = null

    private lateinit var root: LinearLayout
    private lateinit var tabContainer: LinearLayout
    private lateinit var recyclerView: RecyclerListView
    private lateinit var loadingView: ProgressBar
    private lateinit var emptyView: TextView
    private lateinit var adapter: NotificationAdapter

    private val tabs = listOf(
        TabDef(NOTIF_CATEGORY_MENTIONS, R.string.notif_tab_mentions, MezonIcon.notificationTabMention),
        TabDef(NOTIF_CATEGORY_MESSAGES, R.string.notif_tab_messages, MezonIcon.notificationTabMessages),
        TabDef(NOTIF_TAB_TOPICS_UI, R.string.notif_tab_topics, MezonIcon.notificationTabTopic),
        TabDef(NOTIF_CATEGORY_FOR_YOU, R.string.notif_tab_for_you, MezonIcon.notificationTabForYou)
    )
    private var currentCategory = NOTIF_CATEGORY_MENTIONS
    private val tabChipViews = mutableListOf<LinearLayout>()

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
        dialogsController = entryPoint.dialogsController()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()

        observe(NotificationCenter.clansDidLoad) { _, _, _ ->
            if (fragmentView == null || isPaused) return@observe
            val id = clansController.selectedClanId.value
            if (id != 0L) {
                if (store.setCurrentClan(id)) {
                    selectTab(currentCategory, forceRefresh = true)
                }
            }
        }
        observe(NotificationCenter.notificationsDidLoad) { _, _, args ->
            if (fragmentView == null || isPaused) return@observe
            val category = args.firstOrNull() as? Int ?: return@observe
            isLoadingMoreMap[category] = false
            if (category == currentCategory) refreshList()
        }
        observe(NotificationCenter.notificationsLoadError) { _, _, args ->
            if (fragmentView == null || isPaused) return@observe
            if (currentCategory == NOTIF_TAB_TOPICS_UI) return@observe
            val category = args.firstOrNull() as? Int ?: return@observe
            isLoadingMoreMap[category] = false
            if (category == currentCategory) {
                val items = store.getForCategory(currentCategory).value
                if (items.isNotEmpty()) {
                    showList(items)
                } else {
                    showEmpty()
                }
            }
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
            isFillViewport = true
        }
        tabContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val padH = LayoutHelper.dp(16)
            val padV = LayoutHelper.dp(10)
            setPaddingRelative(padH, padV, padH, padV)
            setBackgroundColor(themeColors.surface)
        }
        tabScrollView.addView(
            tabContainer,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)
        )
        root.addView(
            tabScrollView,
            LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT,
                bottomMargin = 4f
            )
        )

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
            if (currentCategory == NOTIF_TAB_TOPICS_UI) return@OnItemClickListener
            if (view is NotificationCell) {
                val entity = view.entity ?: return@OnItemClickListener
                handleNotificationPress(entity)
            }
        })
        recyclerView.setOnItemLongClickListener(RecyclerListView.OnItemLongClickListener { view, _ ->
            if (currentCategory == NOTIF_TAB_TOPICS_UI) return@OnItemLongClickListener false
            if (view is NotificationCell) {
                val entity = view.entity ?: return@OnItemLongClickListener false
                store.deleteNotification(entity.id, currentCategory)
                true
            } else false
        })

        recyclerView.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                if (currentCategory == NOTIF_TAB_TOPICS_UI) return
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
        val isClanChanged = store.setCurrentClan(clanId)

        selectTab(currentCategory, forceRefresh = isClanChanged)
    }

    private fun handleNotificationPress(entity: NotificationEntity) {
        val channelId = entity.channelId
        if (channelId == 0L) { Log.w("NotifNav", "channelId == 0, skip"); return }
        val rawClanId = entity.clanId
        val dm = dialogsController.getDialog(channelId)
        val isDmDialog = dm?.type == CHANNEL_TYPE_DM || dm?.type == CHANNEL_TYPE_GROUP
        val clanId = if (isDmDialog) 0L else rawClanId
        val channelName = if (clanId == 0L) {
            dm?.displayName
                ?.ifEmpty { dm.label }
                .orEmpty()
                .ifEmpty { entity.channelLabel }
                .ifEmpty { entity.senderName }
        } else {
            entity.channelLabel
                .ifEmpty { channelController.findChannelById(channelId)?.channelLabel ?: "" }
                .ifEmpty { entity.subject.substringAfterLast("#").trimEnd(')').trim() }
                .ifEmpty { entity.clanName }
        }
        val channelType = if (clanId == 0L) {
            dm?.type?.takeIf { it != 0 }
                ?: entity.channelType.takeIf { it == CHANNEL_TYPE_DM || it == CHANNEL_TYPE_GROUP }
                ?: CHANNEL_TYPE_DM
        } else {
            entity.channelType.takeIf { it != 0 } ?: CHANNEL_TYPE_CHANNEL
        }

        onOpenChat?.invoke(channelId, channelName, clanId, channelType)
    }

    private fun buildHeader(context: Context): View {
        val header = FrameLayout(context).apply {
            setBackgroundColor(themeColors.surface)
            val pad = LayoutHelper.dp(16)
            setPadding(pad, LayoutHelper.dp(16), pad, LayoutHelper.dp(12))
        }
        val title = TextView(context).apply {
            text = getString(R.string.notification_header)
            setTextColor(themeColors.onSurface)
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        header.addView(title, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL))
        return header
    }

    private fun buildTabChips(context: Context) {
        tabContainer.removeAllViews()
        tabChipViews.clear()
        tabs.forEachIndexed { index, tab ->
            val chip = buildChip(context, getString(tab.labelRes), tab.icon, tab.category == currentCategory)
            chip.setOnClickListener {
                val isSameTab = tab.category == currentCategory
                selectTab(tab.category, forceRefresh = isSameTab)
            }
            val rowWeight = when (tab.category) {
                NOTIF_CATEGORY_MESSAGES -> 1.2f
                NOTIF_CATEGORY_MENTIONS -> 1.12f
                else -> 1f
            }
            val lp = LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, rowWeight).apply {
                gravity = Gravity.CENTER_VERTICAL
                if (index < tabs.size - 1) {
                    marginEnd = LayoutHelper.dp(6)
                }
            }
            tabContainer.addView(chip, lp)
            tabChipViews.add(chip)
        }
    }

    private fun buildChip(context: Context, label: String, icon: MezonIcon, active: Boolean): LinearLayout {
        val iconPx = LayoutHelper.dp(16)
        val gap = LayoutHelper.dp(4)
        val padStart = LayoutHelper.dp(8)
        val padEnd = LayoutHelper.dp(4)
        val vPad = LayoutHelper.dp(6)
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(padStart, vPad, padEnd, vPad)
            background = buildChipBackground(active)
            addView(ImageView(context).apply {
                setImageDrawable(icon.getDrawable(context))
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(iconPx, iconPx).apply { marginEnd = gap }
            })
            addView(TextView(context).apply {
                text = label
                textSize = 12f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                includeFontPadding = false
                setTextColor(if (active) android.graphics.Color.WHITE else themeColors.onSurface)
            }, LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f))
        }
    }

    private fun buildChipBackground(active: Boolean): android.graphics.drawable.Drawable {
        val fill = if (active) themeColors.blurple else themeColors.secondaryLight
        val stroke = if (active) themeColors.blurple else themeColors.outlineVariant
        val bg = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = LayoutHelper.dp(8).toFloat()
            setColor(fill)
            setStroke(LayoutHelper.dp(1), stroke)
        }
        return android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(themeColors.onSurface and 0x33FFFFFF.toInt()),
            bg, null
        )
    }

    private fun selectTab(category: Int, forceRefresh: Boolean = false) {
        if (category == NOTIF_TAB_TOPICS_UI) {
            val isTabChanged = currentCategory != category
            if (isTabChanged) {
                if (currentCategory != NOTIF_TAB_TOPICS_UI) {
                    scrollStates[currentCategory] = recyclerView.layoutManager?.onSaveInstanceState()
                }
                if (currentCategory in isLoadingMoreMap.keys) {
                    isLoadingMoreMap[currentCategory] = false
                }
            }
            currentCategory = category
            rebuildTabChipColors()
            showTopicsPlaceholder()
            return
        }

        val isTabChanged = currentCategory != category
        if (isTabChanged) {
            if (currentCategory != NOTIF_TAB_TOPICS_UI) {
                scrollStates[currentCategory] = recyclerView.layoutManager?.onSaveInstanceState()
                isLoadingMoreMap[currentCategory] = false
            }
        } else if (forceRefresh) {
            recyclerView.scrollToPosition(0)
        }
        currentCategory = category
        rebuildTabChipColors()

        val cached = store.getForCategory(category).value
        if (cached.isNotEmpty()) {
            showList(cached, isTabChanged)
        } else {
            if (isTabChanged || forceRefresh) {
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
            val chip = tabChipViews.getOrNull(index) ?: return@forEachIndexed
            val active = tab.category == currentCategory
            chip.background = buildChipBackground(active)
            val labelTv = chip.getChildAt(1) as TextView
            labelTv.setTextColor(if (active) android.graphics.Color.WHITE else themeColors.onSurface)
        }
    }

    private fun updateVisibleRows(mask: Int) {
        if (isPaused) return
        if (currentCategory == NOTIF_TAB_TOPICS_UI) return
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
        if (currentCategory == NOTIF_TAB_TOPICS_UI) {
            showTopicsPlaceholder()
            return
        }
        val items = store.getForCategory(currentCategory).value
        if (items.isEmpty()) showEmpty() else showList(items)
    }

    private fun showLoading() {
        loadingView.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE
        emptyView.visibility = View.GONE
    }

    private fun showEmpty() {
        emptyView.text = getString(R.string.notif_empty)
        loadingView.visibility = View.GONE
        recyclerView.visibility = View.GONE
        emptyView.visibility = View.VISIBLE
    }

    private fun showTopicsPlaceholder() {
        loadingView.visibility = View.GONE
        recyclerView.visibility = View.GONE
        emptyView.text = getString(R.string.feature_coming_soon)
        emptyView.visibility = View.VISIBLE
    }

    private fun showList(items: List<NotificationEntity>, isTabChange: Boolean = false) {
        emptyView.text = getString(R.string.notif_empty)
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
