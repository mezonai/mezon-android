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
import com.mezon.mobile.core.AlertDialog
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.RecyclerListView
import com.mezon.mobile.core.StartupCache
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.DialogsController
import com.mezon.mobile.home.friends.AddFriendFragment
import com.mezon.mobile.home.friends.FRIEND_STATE_BLOCKED
import com.mezon.mobile.home.friends.FRIEND_STATE_FRIEND
import com.mezon.mobile.home.friends.FRIEND_STATE_INVITE_RECEIVED
import com.mezon.mobile.home.friends.FRIEND_STATE_INVITE_SENT
import com.mezon.mobile.home.friends.FriendController
import com.mezon.mobile.home.friends.createFriendRequestBadgeView
import com.mezon.mobile.home.friends.updateFriendRequestBadge
import com.mezon.mobile.home.profile.UserController
import com.mezon.mobile.network.CHANNEL_TYPE_DM
import com.mezon.mobile.network.CHANNEL_TYPE_GROUP
import com.mezon.mobile.search.GlobalSearchFragment
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.MezonToast
import com.mezon.mobile.ui.cells.ToastOverlay
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "MessagesFragment"

class MessagesFragment : BaseFragment() {

    private lateinit var controller: DialogsController
    private lateinit var friendController: FriendController
    private lateinit var userController: UserController
    private lateinit var dmPinStorage: DmPinStorage
    private lateinit var messageActivitiesController: MessageActivitiesController
    private lateinit var appScope: CoroutineScope
    private lateinit var ioDispatcher: CoroutineDispatcher
    private lateinit var mainDispatcher: CoroutineDispatcher

    private lateinit var headerTitle: TextView
    private lateinit var addFriendBadgeText: TextView
    private lateinit var recyclerView: RecyclerListView
    private lateinit var loadingView: ProgressBar
    private lateinit var emptyView: TextView
    private lateinit var errorView: TextView
    private lateinit var adapter: DmListAdapter
    private lateinit var headerStripAdapter: MessageActivitiesStripHeaderAdapter
    private lateinit var addMessageFab: FrameLayout
    private lateinit var addMessageIcon: ImageView
    private var scrollingManually = false
    private var pendingPartialUpdateMask = 0
    private var dialogsListFrozen = false
    private var frozenDialogsList: List<DirectMessage>? = null
    private var viewJustCreated = false

    var onOpenChat: ((channelId: Long, channelName: String, clanId: Long, channelType: Int) -> Unit)? = null

    override fun onInject(entryPoint: FragmentEntryPoint) {
        controller = entryPoint.dialogsController()
        friendController = entryPoint.friendController()
        userController = entryPoint.userController()
        dmPinStorage = entryPoint.dmPinStorage()
        messageActivitiesController = entryPoint.messageActivitiesController()
        appScope = entryPoint.applicationScope()
        ioDispatcher = entryPoint.ioDispatcher()
        mainDispatcher = entryPoint.mainDispatcher()
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
            if (::addMessageFab.isInitialized) {
                addMessageFab.background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(themeColors.blurple)
                }
                addMessageIcon.setImageDrawable(MezonIcon.messagePlusIcon.getDrawable(fragmentView!!.context).apply {
                    colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
                })
            }
        }
        observe(NotificationCenter.dialogsNeedReload) { _, _, _ ->
            Log.d(TAG, "dialogsNeedReload received: fragmentView=${fragmentView != null} isPaused=$isPaused frozen=$dialogsListFrozen")
            if (fragmentView == null || dialogsListFrozen || isPaused) return@observe
            updateDialogsList()
        }
        observe(NotificationCenter.updateInterfaces) { _, _, args ->
            if (fragmentView == null || isPaused) return@observe
            if (dialogsListFrozen) return@observe
            val mask = args.firstOrNull() as? Int ?: 0
            updateVisibleRows(mask)
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
        recyclerView.setOnItemLongClickListener(RecyclerListView.OnItemLongClickListener { view, _ ->
            if (view !is DialogCell) return@OnItemLongClickListener false
            val dm = view.directMessage ?: return@OnItemLongClickListener false
            showDmMenu(dm)
            true
        })
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                scrollingManually = newState != RecyclerView.SCROLL_STATE_IDLE
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    updateCellVisibility()
                    if (pendingPartialUpdateMask != 0) {
                        val mask = pendingPartialUpdateMask
                        pendingPartialUpdateMask = 0
                        updateVisibleRows(mask)
                    }
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
        adapter.setEntryBuilder { messages ->
            buildSectionedDmEntries(
                messages,
                dmPinStorage.getPinnedIds(),
                getString(R.string.dm_pin_section),
                getString(R.string.dm_all_messages_section),
            )
        }
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

        contentFrame.addView(buildAddMessageFab(context), LayoutHelper.createFrame(
            50, 50, Gravity.BOTTOM or Gravity.RIGHT, 0f, 0f, 10f, 24f
        ))

        val dialogs = controller.getDialogs()
        if (dialogs.isNotEmpty()) {
            showList(dialogs)
            viewJustCreated = true
        } else {
            showLoading()
        }

        return root
    }

    private fun buildAddMessageFab(context: Context): View {
        addMessageFab = FrameLayout(context).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(themeColors.blurple)
            }
            isClickable = true
            isFocusable = true
            elevation = LayoutHelper.dpf(6f)
            setOnClickListener { openNewMessage() }
        }
        addMessageIcon = ImageView(context).apply {
            setImageDrawable(MezonIcon.messagePlusIcon.getDrawable(context).apply {
                colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            })
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        addMessageFab.addView(addMessageIcon, LayoutHelper.createFrame(28, 28, Gravity.CENTER))
        return addMessageFab
    }

    private fun syncMessageActivitiesStrip() {
        if (!::headerStripAdapter.isInitialized) return
        val items = messageActivitiesController.rows.value
        headerStripAdapter.setStripItems(items)
        if (items.isNotEmpty() && controller.dialogsLoaded && controller.getDialogs().isEmpty() &&
            ::recyclerView.isInitialized && recyclerView.visibility != View.VISIBLE) {
            recyclerView.visibility = View.VISIBLE
            emptyView.visibility = View.GONE
            adapter.setMessages(emptyList())
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

        addFriendBadgeText = createFriendRequestBadgeView(context, themeColors)
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

        pill.addView(addFriendBadgeText, LinearLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.dp(18)).apply {
            gravity = Gravity.CENTER_VERTICAL
        })

        updateAddFriendBadge()
        return pill
    }

    private fun updateAddFriendBadge() {
        if (!::addFriendBadgeText.isInitialized) return
        val pending = friendController.pendingReceivedCount.value
        if (pending > 0) {
            addFriendBadgeText.updateFriendRequestBadge(pending, themeColors)
        } else {
            addFriendBadgeText.updateFriendRequestBadge(0, themeColors)
        }
    }

    private fun onAddFriendClicked() {
        presentFragment(AddFriendFragment())
    }

    private fun openNewMessage() {
        val fragment = NewMessageFragment().apply {
            onOpenChat = this@MessagesFragment.onOpenChat
        }
        presentFragment(fragment)
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
        if (scrollingManually) {
            pendingPartialUpdateMask = pendingPartialUpdateMask or mask
            return
        }
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
            adapter.setMessages(emptyList())
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
        adapter.setMessages(messages)
    }

    private fun showDmMenu(dm: DirectMessage) {
        appScope.launch {
            if (dm.type == CHANNEL_TYPE_GROUP) {
                controller.ensureDmParticipantsLoaded(dm.channelId)
            }
            controller.refreshDmNotificationSetting(dm.channelId)
            withContext(mainDispatcher) {
                val menuDm = controller.getDialog(dm.channelId) ?: dm
                presentDmMenu(menuDm)
            }
        }
    }

    private fun presentDmMenu(dm: DirectMessage) {
        val ctx = fragmentView?.context ?: return
        val options = buildDmMenuOptions(dm)
        DmMenuBottomSheet(
            context = ctx,
            dm = dm,
            options = options,
            onLeaveGroup = { confirmLeaveGroup(dm, deleteIfLastMember = false) },
            onDeleteGroup = { confirmLeaveGroup(dm, deleteIfLastMember = true) },
            onCloseDm = { confirmCloseDm(dm) },
            onAddFriend = { performAddFriend(dm) },
            onRemoveFriend = { performRemoveFriend(dm) },
            onBlockUser = { performBlockUser(dm, block = true) },
            onUnblockUser = { performBlockUser(dm, block = false) },
            onMarkAsRead = { performMarkAsRead(dm.channelId) },
            onTogglePin = { confirmTogglePin(dm.channelId, options.isPinned) },
            onMute = { handleDmMute(dm) },
        ).show()
    }

    private fun buildDmMenuOptions(dm: DirectMessage): DmMenuOptions {
        val isGroup = dm.type == CHANNEL_TYPE_GROUP
        val isChatWithMyself = dm.type == CHANNEL_TYPE_DM && dm.otherUserId == userController.userId
        val memberCount = controller.getGroupMemberCount(dm.channelId)
        val lastOne = isGroup && memberCount == 1
        val friend = if (!isGroup && dm.otherUserId != 0L) {
            friendController.findFriendByUserId(dm.otherUserId)
        } else {
            null
        }
        val friendState = friend?.state
        val didIBlockUser = !isGroup && friendController.isUserBlockedByMe(dm.otherUserId)
        val showFriendActions = !isGroup && !isChatWithMyself &&
            friendState != FRIEND_STATE_BLOCKED &&
            friendState != FRIEND_STATE_INVITE_SENT &&
            friendState != FRIEND_STATE_INVITE_RECEIVED
        return DmMenuOptions(
            showLeaveGroup = isGroup && memberCount > 1,
            showDeleteGroup = lastOne,
            showCloseDm = !isGroup,
            showAddFriend = showFriendActions && friendState != FRIEND_STATE_FRIEND,
            showRemoveFriend = showFriendActions && friendState == FRIEND_STATE_FRIEND,
            showBlockUser = !isGroup && !isChatWithMyself &&
                (friendState == FRIEND_STATE_FRIEND || didIBlockUser) && !didIBlockUser,
            showUnblockUser = !isGroup && didIBlockUser,
            showMarkAsRead = !isChatWithMyself,
            showPin = true,
            isPinned = dmPinStorage.isPinned(dm.channelId),
            showMute = !isChatWithMyself,
            isMuted = controller.isDmMuted(dm.channelId).also { muted ->
                if (com.mezon.mobile.BuildConfig.DEBUG) {
                    android.util.Log.d(
                        "DialogsController:Mute",
                        "menu ch=${dm.channelId} isMuted=$muted mem=${dm.isMute}",
                    )
                }
            },
        )
    }

    private fun confirmLeaveGroup(dm: DirectMessage, deleteIfLastMember: Boolean) {
        val act = getParentActivity() ?: return
        val name = dm.displayName.ifBlank { dm.label }
        val titleRes = if (deleteIfLastMember) R.string.dm_delete_group_confirm_title else R.string.dm_leave_group_confirm_title
        val messageRes = if (deleteIfLastMember) R.string.dm_delete_group_confirm_message else R.string.dm_leave_group_confirm_message
        AlertDialog.Builder(act)
            .setTitle(getString(titleRes, name))
            .setMessage(getString(messageRes, name))
            .setNegativeButton(getString(R.string.common_cancel), null)
            .setPositiveButton(getString(R.string.common_yes)) { _, _ ->
                performLeaveGroup(dm, deleteIfLastMember)
            }
            .show()
    }

    private fun confirmCloseDm(dm: DirectMessage) {
        val act = getParentActivity() ?: return
        val name = dm.displayName.ifBlank { dm.label }
        AlertDialog.Builder(act)
            .setTitle(getString(R.string.dm_close_confirm_title, name))
            .setMessage(getString(R.string.dm_close_confirm_message, name))
            .setNegativeButton(getString(R.string.common_cancel), null)
            .setPositiveButton(getString(R.string.common_yes)) { _, _ ->
                performCloseDm(dm.channelId)
            }
            .show()
    }

    private fun performLeaveGroup(dm: DirectMessage, deleteIfLastMember: Boolean) {
        val currentUserId = userController.userId
        if (currentUserId == 0L) return
        appScope.launch {
            val result = controller.leaveDmGroup(dm.channelId, dm.type, currentUserId, deleteIfLastMember)
            withContext(mainDispatcher) {
                if (result.isFailure) {
                    MezonToast.show(
                        this@MessagesFragment,
                        ToastOverlay.ToastType.ERROR,
                        getString(R.string.common_something_went_wrong),
                    )
                }
            }
        }
    }

    private fun performCloseDm(channelId: Long) {
        appScope.launch {
            val result = controller.closeDirectMessage(channelId)
            withContext(mainDispatcher) {
                if (result.isFailure) {
                    MezonToast.show(
                        this@MessagesFragment,
                        ToastOverlay.ToastType.ERROR,
                        getString(R.string.common_something_went_wrong),
                    )
                }
            }
        }
    }

    private fun performMarkAsRead(channelId: Long) {
        appScope.launch {
            val result = controller.markDialogAsReadFromMenu(channelId)
            withContext(mainDispatcher) {
                if (result.isFailure) {
                    MezonToast.show(
                        this@MessagesFragment,
                        ToastOverlay.ToastType.ERROR,
                        getString(R.string.common_something_went_wrong),
                    )
                }
            }
        }
    }

    private fun performAddFriend(dm: DirectMessage) {
        friendController.sendFriendRequest(dm.otherUserId, dm.username) { success ->
            if (!success) {
                MezonToast.show(this, ToastOverlay.ToastType.ERROR, getString(R.string.common_something_went_wrong))
            }
        }
    }

    private fun performRemoveFriend(dm: DirectMessage) {
        friendController.deleteFriendRelation(dm.otherUserId, dm.username) { success ->
            if (!success) {
                MezonToast.show(this, ToastOverlay.ToastType.ERROR, getString(R.string.common_something_went_wrong))
            }
        }
    }

    private fun performBlockUser(dm: DirectMessage, block: Boolean) {
        val onResult: (Boolean) -> Unit = { success ->
            val msg = when {
                block && success -> getString(R.string.dm_block_user_success)
                block && !success -> getString(R.string.dm_block_user_failed)
                !block && success -> getString(R.string.dm_unblock_user_success)
                else -> getString(R.string.dm_unblock_user_failed)
            }
            MezonToast.show(
                this,
                if (success) ToastOverlay.ToastType.SUCCESS else ToastOverlay.ToastType.ERROR,
                msg,
            )
        }
        if (block) {
            friendController.blockUser(dm.otherUserId, dm.username, onResult)
        } else {
            friendController.unblockUser(dm.otherUserId, dm.username, onResult)
        }
    }

    private fun confirmTogglePin(channelId: Long, isPinned: Boolean) {
        val act = getParentActivity() ?: return
        val titleRes = if (isPinned) R.string.dm_unpin_confirm_title else R.string.dm_pin_confirm_title
        val messageRes = if (isPinned) R.string.dm_unpin_confirm_message else R.string.dm_pin_confirm_message
        val actionRes = if (isPinned) R.string.dm_unpin_confirm_action else R.string.dm_pin_confirm_action
        AlertDialog.Builder(act)
            .setTitle(getString(titleRes))
            .setMessage(getString(messageRes))
            .setNegativeButton(getString(R.string.common_cancel), null)
            .setPositiveButton(getString(actionRes)) { _, _ ->
                if (isPinned) {
                    dmPinStorage.unpin(channelId)
                    updateDialogsList()
                } else {
                    when (dmPinStorage.pin(channelId)) {
                        DmPinResult.Success -> updateDialogsList()
                        DmPinResult.MaxReached -> MezonToast.show(
                            this,
                            ToastOverlay.ToastType.ERROR,
                            getString(R.string.dm_pin_max_reached, DmPinStorage.MAX_PINNED),
                        )
                    }
                }
            }
            .show()
    }

    private fun handleDmMute(dm: DirectMessage) {
        val ctx = fragmentView?.context ?: return
        if (controller.isDmMuted(dm.channelId)) {
            appScope.launch {
                val result = controller.setDialogMuted(dm.channelId, muteTimeSeconds = 0, active = 0)
                withContext(mainDispatcher) {
                    if (result.isSuccess) {
                        MezonToast.show(this@MessagesFragment, ToastOverlay.ToastType.SUCCESS, getString(R.string.dm_unmute_success))
                    } else {
                        MezonToast.show(this@MessagesFragment, ToastOverlay.ToastType.ERROR, getString(R.string.dm_unmute_failed))
                    }
                }
            }
            return
        }
        val label = dm.displayName.ifBlank { dm.label }
        DmMuteBottomSheet(ctx, label) { muteTimeSeconds, active ->
            appScope.launch {
                val result = controller.setDialogMuted(dm.channelId, muteTimeSeconds, active)
                withContext(mainDispatcher) {
                    if (result.isSuccess) {
                        MezonToast.show(this@MessagesFragment, ToastOverlay.ToastType.SUCCESS, getString(R.string.dm_mute_success))
                    } else {
                        MezonToast.show(this@MessagesFragment, ToastOverlay.ToastType.ERROR, getString(R.string.dm_mute_failed))
                    }
                }
            }
        }.show()
    }
}
