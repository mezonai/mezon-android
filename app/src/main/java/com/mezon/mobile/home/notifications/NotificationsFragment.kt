package com.mezon.mobile.home.notifications

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.home.clans.ClansController
import com.mezon.mobile.home.clans.ChannelController
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

private data class TabDef(val category: Int, val labelRes: Int)

@AndroidEntryPoint
class NotificationsFragment : BaseFragment() {

    @Inject lateinit var store: NotificationStore
    @Inject lateinit var clansController: ClansController
    @Inject lateinit var channelController: ChannelController

    var onOpenChat: ((channelId: Long, channelName: String, clanId: Long, channelType: Int) -> Unit)? = null

    private lateinit var root: LinearLayout
    private lateinit var tabContainer: LinearLayout
    private lateinit var recyclerView: RecyclerView
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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.background)
        }

        root.addView(buildHeader(), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val tabScrollView = HorizontalScrollView(requireContext()).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(themeColors.surface)
        }
        tabContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = LayoutHelper.dp(12)
            setPadding(pad, LayoutHelper.dp(8), pad, LayoutHelper.dp(8))
        }
        tabScrollView.addView(tabContainer)
        root.addView(tabScrollView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        buildTabChips()

        val contentFrame = FrameLayout(requireContext())
        root.addView(contentFrame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        recyclerView = RecyclerView(requireContext()).apply {
            layoutManager = LinearLayoutManager(context)
            setHasFixedSize(false)
            visibility = View.GONE
        }
        contentFrame.addView(recyclerView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        loadingView = ProgressBar(requireContext()).apply { visibility = View.GONE }
        contentFrame.addView(loadingView, LayoutHelper.createFrame(48, 48, Gravity.CENTER))

        emptyView = TextView(requireContext()).apply {
            text = getString(R.string.notif_empty)
            setTextColor(themeColors.onSurfaceVariant)
            textSize = 16f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        contentFrame.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        adapter = NotificationAdapter(
            theme = themeColors,
            onPress = { entity -> handleNotificationPress(entity) },
            onLongPress = { entity -> store.deleteNotification(entity.id, currentCategory) }
        )
        recyclerView.adapter = adapter

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val clanId = clansController.selectedClanId.value
        store.setCurrentClan(clanId)

        observe(NotificationCenter.clansDidLoad) { _, _ ->
            val id = clansController.selectedClanId.value
            if (id != 0L) {
                store.setCurrentClan(id)
            }
        }

        observe(NotificationCenter.notificationsDidLoad) { _, args ->
            val category = args.firstOrNull() as? Int ?: return@observe
            if (category == currentCategory) refreshList()
        }

        observe(NotificationCenter.notificationsLoadError) { _, args ->
            val category = args.firstOrNull() as? Int ?: return@observe
            if (category == currentCategory) showEmpty()
        }

        observe(NotificationCenter.themeChanged) { _, _ ->
            root.setBackgroundColor(themeColors.background)
            emptyView.setTextColor(themeColors.onSurfaceVariant)
            rebuildTabChipColors()
            adapter.notifyDataSetChanged()
        }

        selectTab(currentCategory)
    }

    private fun handleNotificationPress(entity: NotificationEntity) {
        Log.d("NotifNav", "press entity: id=${entity.id} channelId=${entity.channelId} channelType=${entity.channelType} clanId=${entity.clanId} channelLabel='${entity.channelLabel}' senderName='${entity.senderName}'")
        val channelId = entity.channelId
        if (channelId == 0L) {
            Log.w("NotifNav", "channelId == 0, skip navigate")
            return
        }
        val channelName = entity.channelLabel
            .ifEmpty { channelController.findChannelById(channelId)?.channelLabel ?: "" }
            .ifEmpty { entity.subject.substringAfterLast("#").trimEnd(')').trim() }
            .ifEmpty { entity.clanName }
        val clanId = entity.clanId
        val channelType = entity.channelType.takeIf { it != 0 } ?: 1
        Log.d("NotifNav", "invoking onOpenChat: channelId=$channelId name='$channelName' clanId=$clanId type=$channelType")
        onOpenChat?.invoke(channelId, channelName, clanId, channelType)
    }

    private fun buildHeader(): View {
        val header = FrameLayout(requireContext()).apply {
            setBackgroundColor(themeColors.surface)
            val pad = LayoutHelper.dp(16)
            setPadding(pad, LayoutHelper.dp(16), pad, LayoutHelper.dp(12))
        }
        val title = TextView(requireContext()).apply {
            text = getString(R.string.notif_inbox_title)
            setTextColor(themeColors.onSurface)
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        header.addView(title, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL))
        return header
    }

    private fun buildTabChips() {
        tabContainer.removeAllViews()
        tabViews.clear()
        tabs.forEachIndexed { _, tab ->
            val chip = buildChip(requireContext(), getString(tab.labelRes), tab.category == currentCategory)
            chip.setOnClickListener { selectTab(tab.category) }
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
        val ripple = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(themeColors.onSurface and 0x33FFFFFF.toInt()),
            bg, null
        )
        return ripple
    }

    private fun selectTab(category: Int) {
        currentCategory = category
        rebuildTabChipColors()
        store.loadCategory(category)
        showLoading()
        refreshList()
    }

    private fun rebuildTabChipColors() {
        tabs.forEachIndexed { index, tab ->
            val chip = tabViews.getOrNull(index) ?: return@forEachIndexed
            val active = tab.category == currentCategory
            chip.setTextColor(if (active) android.graphics.Color.WHITE else themeColors.onSurface)
            chip.background = buildChipBackground(active)
        }
    }

    private fun refreshList() {
        val items = store.getForCategory(currentCategory).value
        if (items.isEmpty()) {
            showEmpty()
        } else {
            showList(items)
        }
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

    private fun showList(items: List<NotificationEntity>) {
        loadingView.visibility = View.GONE
        emptyView.visibility = View.GONE
        recyclerView.visibility = View.VISIBLE
        adapter.setData(items)
    }
}
