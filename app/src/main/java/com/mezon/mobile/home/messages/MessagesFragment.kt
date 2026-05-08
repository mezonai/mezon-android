package com.mezon.mobile.home.messages

import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.RecyclerListView
import com.mezon.mobile.core.StartupCache
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.DialogsController
import com.mezon.mobile.home.friends.AddFriendFragment
import com.mezon.mobile.home.friends.FriendController
import com.mezon.mobile.network.CHANNEL_TYPE_DM
import com.mezon.mobile.search.GlobalSearchFragment
import com.mezon.mobile.ui.cells.MezonIcon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val TAG = "MessagesFragment"

class MessagesFragment : BaseFragment() {

    private lateinit var controller: DialogsController
    private lateinit var friendController: FriendController
    private lateinit var messageActivitiesController: MessageActivitiesController
    private lateinit var appScope: CoroutineScope

    private lateinit var headerTitle: TextView
    private lateinit var addFriendBadgeText: TextView
    private lateinit var recyclerView: RecyclerListView
    private lateinit var loadingView: ProgressBar
    private lateinit var emptyView: TextView
    private lateinit var errorView: TextView
    private lateinit var adapter: DmListAdapter
    private lateinit var headerStripAdapter: MessageActivitiesStripHeaderAdapter
    private var scrollingManually = false
    private var dialogsListFrozen = false
    private var frozenDialogsList: List<DirectMessage>? = null
    private var viewJustCreated = false

    var onOpenChat: ((channelId: Long, channelName: String, clanId: Long, channelType: Int) -> Unit)? = null

    override fun onInject(entryPoint: FragmentEntryPoint) {
        controller = entryPoint.dialogsController()
        friendController = entryPoint.friendController()
        messageActivitiesController = entryPoint.messageActivitiesController()
        appScope = entryPoint.applicationScope()
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
            if (::headerStripAdapter.isInitialized) headerStripAdapter.notifyDataSetChanged()
        }
        observe(NotificationCenter.dialogsNeedReload) { _, _, _ ->
            Log.d(TAG, "dialogsNeedReload received: fragmentView=${fragmentView != null} isPaused=$isPaused frozen=$dialogsListFrozen")
            if (fragmentView == null || dialogsListFrozen) return@observe
            updateDialogsList()
        }
        observe(NotificationCenter.updateInterfaces) { _, _, args ->
            if (fragmentView == null || isPaused) return@observe
            if (dialogsListFrozen) return@observe
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
        observe(NotificationCenter.friendsLoaded) { _, _, _ ->
            if (fragmentView == null) return@observe
            updateAddFriendBadge()
        }
        observe(NotificationCenter.messageActivitiesRowsUpdated) { _, _, _ ->
            if (fragmentView == null) return@observe
            syncMessageActivitiesStrip()
        }

        if (!StartupCache.suppressHomeListApiForIncomingCallWake) {
            controller.loadDialogs()
        }
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

        adapter = DmListAdapter(themeColors) { channelId -> controller.isBuzzActive(channelId) }
        headerStripAdapter = MessageActivitiesStripHeaderAdapter(themeColors) { row ->
            appScope.launch {
                val channelId = controller.getOrCreateDm(row.userId)
                if (channelId != 0L) {
                    AndroidUtilities.runOnUIThread {
                        onOpenChat?.invoke(
                            channelId,
                            row.displayName.ifBlank { row.username },
                            0L,
                            CHANNEL_TYPE_DM
                        )
                    }
                }
            }
        }
        recyclerView.adapter = ConcatAdapter(headerStripAdapter, adapter)

        syncMessageActivitiesStrip()

        val dialogs = controller.getDialogs()
        if (dialogs.isNotEmpty()) {
            showList(dialogs)
            viewJustCreated = true
        } else {
            showLoading()
        }

        return root
    }

    private fun syncMessageActivitiesStrip() {
        if (!::headerStripAdapter.isInitialized) return
        val items = messageActivitiesController.rows.value
        headerStripAdapter.setStripItems(items)
        if (items.isNotEmpty() && controller.dialogsLoaded && controller.getDialogs().isEmpty() &&
            ::recyclerView.isInitialized && recyclerView.visibility != View.VISIBLE) {
            recyclerView.visibility = View.VISIBLE
            emptyView.visibility = View.GONE
            adapter.setData(emptyList())
        }
    }

    private fun scrollActivityStripToStart() {
        if (!::headerStripAdapter.isInitialized) return
        headerStripAdapter.scrollStripToStart()
    }

    private fun buildHeader(context: Context): View {
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.surface)
            setPadding(0, LayoutHelper.dp(8), 0, LayoutHelper.dp(4))
        }

        // Row 1: Messages icon (purple circle avatar) + title
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(16), 0, LayoutHelper.dp(16), 0)
        }

        // Purple circle avatar with chat icon inside
        val messagesIcon = FrameLayout(context).apply {
            val circleBg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(themeColors.blurple) // purple like RN IconMessagesIcon
            }
            background = circleBg
        }
        val chatIconView = ImageView(context).apply {
            setImageDrawable(MezonIcon.chatIcon.getDrawable(context).apply {
                colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            })
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        messagesIcon.addView(chatIconView, LayoutHelper.createFrame(
            20, 20, Gravity.CENTER
        ))
        titleRow.addView(messagesIcon, LayoutHelper.createLinear(34, 34))

        headerTitle = TextView(context).apply {
            text = getString(R.string.dm_title)
            setTextColor(themeColors.onSurface)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(LayoutHelper.dp(10), 0, 0, 0)
        }
        titleRow.addView(headerTitle, LayoutHelper.createLinear(
            LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT
        ))
        header.addView(titleRow, LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT
        ))

        // Row 2: Add Friend pill (flex:1) + Search circle button
        val buttonsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, LayoutHelper.dp(6), 0, 0)
        }

        // Add Friend pill — flex:1 via weight
        val addFriendPill = buildAddFriendPill(context)
        buttonsRow.addView(addFriendPill, LinearLayout.LayoutParams(
            0, LayoutHelper.dp(32), 1f
        ).apply {
            leftMargin = LayoutHelper.dp(16)
            rightMargin = LayoutHelper.dp(8)
        })

        val searchButton = ImageView(context).apply {
            val circleBg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(themeColors.tertiary)
            }
            val rippleMask = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xFFFFFFFF.toInt())
            }
            val rippleColor = android.content.res.ColorStateList.valueOf(themeColors.onSurface and 0x1A_FFFFFF)
            background = android.graphics.drawable.RippleDrawable(rippleColor, circleBg, rippleMask)
            setImageDrawable(MezonIcon.searchIcon.getDrawable(context))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val iconPad = LayoutHelper.dp(7)
            setPadding(iconPad, iconPad, iconPad, iconPad)
            isClickable = true
            isFocusable = true
            setOnClickListener { openSearch() }
        }
        buttonsRow.addView(searchButton, LayoutHelper.createLinear(
            34, 34, rightMargin = 16f
        ))

        header.addView(buttonsRow, LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT
        ))

        return header
    }

    private fun buildAddFriendPill(context: Context): View {
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
            gravity = Gravity.CENTER_VERTICAL
            val bg = android.graphics.drawable.GradientDrawable().apply {
                setColor(themeColors.tertiary)
                cornerRadius = LayoutHelper.dp(20).toFloat()
            }
            background = bg
            setPadding(LayoutHelper.dp(10), LayoutHelper.dp(6), LayoutHelper.dp(10), LayoutHelper.dp(6))
            isClickable = true
            isFocusable = true
            val outValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
            if (outValue.resourceId != 0) {
                foreground = androidx.core.content.ContextCompat.getDrawable(context, outValue.resourceId)
            }
            setOnClickListener { onAddFriendClicked() }
        }

        val buttonContent = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
            gravity = Gravity.CENTER
        }
        pill.addView(buttonContent, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val icon = ImageView(context).apply {
            setImageDrawable(MezonIcon.userPlusIcon.getDrawable(context))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val iconWrap = FrameLayout(context)
        iconWrap.addView(icon, LayoutHelper.createFrame(14, 14, Gravity.CENTER))

        addFriendBadgeText = TextView(context).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            minWidth = LayoutHelper.dp(16)
            setPadding(LayoutHelper.dp(4), 0, LayoutHelper.dp(4), 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = LayoutHelper.dp(8f).toFloat()
                setColor(themeColors.error)
            }
            visibility = View.GONE
        }
        buttonContent.addView(iconWrap, LinearLayout.LayoutParams(
            LayoutHelper.dp(18), LayoutHelper.dp(18)
        ).apply {
            gravity = Gravity.CENTER_VERTICAL
            rightMargin = LayoutHelper.dp(4)
        })

        val label = TextView(context).apply {
            text = getString(R.string.dm_add_friend)
            setTextColor(themeColors.onSurface)
            textSize = 15f
        }
        buttonContent.addView(label, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_VERTICAL
        })

        pill.addView(addFriendBadgeText, LinearLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.dp(16)).apply {
            gravity = Gravity.CENTER_VERTICAL
        })

        updateAddFriendBadge()
        return pill
    }

    private fun updateAddFriendBadge() {
        if (!::addFriendBadgeText.isInitialized) return
        val pending = friendController.pendingReceivedCount.value
        if (pending > 0) {
            addFriendBadgeText.text = pending.toString()
            addFriendBadgeText.visibility = View.VISIBLE
        } else {
            addFriendBadgeText.visibility = View.GONE
        }
    }

    private fun onAddFriendClicked() {
        presentFragment(AddFriendFragment())
    }

    private fun openSearch() {
        val fragment = GlobalSearchFragment().apply {
            this.onOpenChat = this@MessagesFragment.onOpenChat
        }
        presentFragment(fragment)
    }

    override fun onBecomeFullyVisible() {
        super.onBecomeFullyVisible()
        scrollActivityStripToStart()
        if (viewJustCreated) {
            viewJustCreated = false
            return
        }
        Log.d(TAG, "onBecomeFullyVisible isPaused=$isPaused")
        updateDialogsList()
    }

    fun setDialogsListFrozen(frozen: Boolean) {
        if (dialogsListFrozen == frozen) return
        dialogsListFrozen = frozen
        if (frozen) {
            frozenDialogsList = ArrayList(controller.getDialogs())
        } else {
            frozenDialogsList = null
            if (fragmentView != null) updateDialogsList()
        }
    }

    private fun updateVisibleRows(mask: Int) {
        if (scrollingManually) return
        if ((mask and NotificationCenter.UPDATE_MASK_NEW_MESSAGE) != 0 || mask == 0) {
            updateDialogsList()
            return
        }
        val source = frozenDialogsList ?: controller.getDialogs()
        adapter.updateVisibleRows(recyclerView, mask, source)
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
        errorView.visibility = View.GONE
        val hasActivities = messageActivitiesController.rows.value.isNotEmpty()
        if (hasActivities) {
            recyclerView.visibility = View.VISIBLE
            emptyView.visibility = View.GONE
            adapter.setData(emptyList())
            syncMessageActivitiesStrip()
        } else {
            recyclerView.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
        }
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
