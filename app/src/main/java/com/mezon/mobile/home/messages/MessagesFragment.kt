package com.mezon.mobile.home.messages

import android.content.Context
import android.graphics.Typeface
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.RecyclerListView
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.DialogsController

private const val TAG = "MessagesFragment"

class MessagesFragment : BaseFragment() {

    private lateinit var controller: DialogsController

    private lateinit var headerTitle: TextView
    private lateinit var recyclerView: RecyclerListView
    private lateinit var loadingView: ProgressBar
    private lateinit var emptyView: TextView
    private lateinit var errorView: TextView
    private lateinit var adapter: DmListAdapter
    private var scrollingManually = false

    var onOpenChat: ((channelId: Long, channelName: String, clanId: Long, channelType: Int) -> Unit)? = null

    override fun onInject(entryPoint: FragmentEntryPoint) {
        controller = entryPoint.dialogsController()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()

        observe(NotificationCenter.themeChanged) { _, _, _ ->
            if (fragmentView == null) return@observe
            fragmentView?.setBackgroundColor(themeColors.background)
            headerTitle.setTextColor(themeColors.onSurface)
            (headerTitle.parent as? View)?.setBackgroundColor(themeColors.surface)
            emptyView.setTextColor(themeColors.onSurfaceVariant)
            adapter.notifyDataSetChanged()
        }
        observe(NotificationCenter.dialogsNeedReload) { _, _, _ ->
            Log.d(TAG, "dialogsNeedReload received: fragmentView=${fragmentView != null} isPaused=$isPaused")
            if (fragmentView == null) return@observe
            updateDialogsList()
        }
        observe(NotificationCenter.updateInterfaces) { _, _, args ->
            if (fragmentView == null || isPaused) return@observe
            val mask = args.firstOrNull() as? Int ?: 0
            updateVisibleRows(mask)
        }
        observe(NotificationCenter.onlineStatusChanged) { _, _, _ ->
            if (fragmentView == null || isPaused) return@observe
            updateVisibleRows(NotificationCenter.UPDATE_MASK_STATUS)
        }
        observe(NotificationCenter.dialogsLoadError) { _, _, args ->
            if (fragmentView == null) return@observe
            if (controller.getDialogs().isEmpty()) {
                showError(args.firstOrNull() as? String ?: "Failed to load")
            }
        }

        controller.loadDialogs()
        return true
    }

    override fun createView(context: Context): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.background)
        }

        root.addView(buildHeader(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val contentFrame = FrameLayout(context)
        root.addView(contentFrame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        emptyView = TextView(context).apply {
            text = getString(R.string.dm_no_messages)
            setTextColor(themeColors.onSurfaceVariant)
            textSize = 16f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        contentFrame.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        recyclerView = RecyclerListView(context).apply {
            layoutManager = LinearLayoutManager(context)
            setHasFixedSize(true)
            visibility = View.GONE
        }
        contentFrame.addView(recyclerView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))
        recyclerView.setEmptyView(emptyView)
        recyclerView.setOnItemClickListener(RecyclerListView.OnItemClickListener { view, _ ->
            if (view is DialogCell) {
                val dm = view.directMessage ?: return@OnItemClickListener
                onOpenChat?.invoke(dm.channelId, dm.displayName.ifEmpty { dm.label }, 0L, dm.type)
            }
        })
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                scrollingManually = newState != RecyclerView.SCROLL_STATE_IDLE
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    updateCellVisibility()
                }
            }
        })

        loadingView = ProgressBar(context).apply { visibility = View.GONE }
        contentFrame.addView(loadingView, LayoutHelper.createFrame(48, 48, Gravity.CENTER))

        errorView = TextView(context).apply {
            setTextColor(themeColors.error)
            textSize = 14f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        contentFrame.addView(errorView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        adapter = DmListAdapter(themeColors)
        recyclerView.adapter = adapter

        showLoading()

        return root
    }

    private fun buildHeader(context: Context): View {
        val header = FrameLayout(context).apply {
            setBackgroundColor(themeColors.surface)
            val pad = LayoutHelper.dp(16)
            setPadding(pad, LayoutHelper.dp(16), pad, LayoutHelper.dp(12))
        }
        headerTitle = TextView(context).apply {
            text = getString(R.string.dm_title)
            setTextColor(themeColors.onSurface)
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        }
        header.addView(headerTitle, LayoutHelper.createFrame(
            LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
            Gravity.START or Gravity.CENTER_VERTICAL
        ))
        return header
    }

    override fun onBecomeFullyVisible() {
        super.onBecomeFullyVisible()
        Log.d(TAG, "onBecomeFullyVisible isPaused=$isPaused")
        updateDialogsList()
    }

    private fun updateVisibleRows(mask: Int) {
        if (scrollingManually) return
        if ((mask and NotificationCenter.UPDATE_MASK_NEW_MESSAGE) != 0 || mask == 0) {
            updateDialogsList()
            return
        }
        adapter.updateVisibleRows(recyclerView, mask, controller.getDialogs())
    }

    private fun updateCellVisibility() {
        val count = recyclerView.childCount
        for (i in 0 until count) {
            val child = recyclerView.getChildAt(i)
            if (child is DialogCell) {
                val top = child.top
                val bottom = child.bottom
                val visible = bottom > 0 && top < recyclerView.height
                child.setVisibleOnScreen(visible)
            }
        }
    }

    private fun updateDialogsList() {
        val list = controller.getDialogs()
        val loaded = controller.dialogsLoaded
        Log.d(TAG, "updateDialogsList: size=${list.size} dialogsLoaded=$loaded isPaused=$isPaused")
        when {
            list.isNotEmpty() -> { Log.d(TAG, "→ showList(${list.size})"); showList(list) }
            !loaded -> { Log.d(TAG, "→ showLoading (not loaded yet)"); showLoading() }
            else -> { Log.d(TAG, "→ showEmpty (loaded=true but list empty)"); showEmpty() }
        }
    }

    private fun showLoading() {
        loadingView.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE
        emptyView.visibility = View.GONE
        errorView.visibility = View.GONE
    }

    private fun showEmpty() {
        loadingView.visibility = View.GONE
        recyclerView.visibility = View.GONE
        emptyView.visibility = View.VISIBLE
        errorView.visibility = View.GONE
    }

    private fun showError(message: String) {
        loadingView.visibility = View.GONE
        recyclerView.visibility = View.GONE
        emptyView.visibility = View.GONE
        errorView.visibility = View.VISIBLE
        errorView.text = message
    }

    private fun showList(messages: List<DirectMessage>) {
        loadingView.visibility = View.GONE
        recyclerView.visibility = View.VISIBLE
        emptyView.visibility = View.GONE
        errorView.visibility = View.GONE
        adapter.setData(messages)
    }
}
