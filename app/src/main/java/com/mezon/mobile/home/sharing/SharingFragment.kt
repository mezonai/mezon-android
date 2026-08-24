package com.mezon.mobile.home.sharing

import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.content.res.ColorStateList
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.RecyclerListView
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.ChatController
import com.mezon.mobile.home.DialogsController
import com.mezon.mobile.home.ForwardTargetUsageStore
import com.mezon.mobile.home.chat.AttachmentInfo
import com.mezon.mobile.home.chat.AttachmentPickerItem
import com.mezon.mobile.home.chat.ForwardDestination
import com.mezon.mobile.home.chat.ForwardNavigationStash
import com.mezon.mobile.home.chat.MessageEntity
import com.mezon.mobile.home.chat.isGifAttachment
import com.mezon.mobile.home.chat.isImageAttachmentType
import com.mezon.mobile.home.chat.isMediaAttachment
import com.mezon.mobile.home.chat.isVideoAttachmentType
import com.mezon.mobile.home.clans.CHANNEL_TYPE_VOICE
import com.mezon.mobile.home.clans.ChannelController
import com.mezon.mobile.home.clans.ClansController
import com.mezon.mobile.home.clans.PermissionPolicy
import com.mezon.mobile.home.friends.FriendController
import com.mezon.mobile.network.CHANNEL_TYPE_CHANNEL
import com.mezon.mobile.network.CHANNEL_TYPE_DM
import com.mezon.mobile.network.CHANNEL_TYPE_GROUP
import com.mezon.mobile.network.CHANNEL_TYPE_THREAD
import com.mezon.mobile.search.LOCAL_PAGE_SIZE
import com.mezon.mobile.search.SearchController
import com.mezon.mobile.session.SessionManager
import com.mezon.mobile.ui.MezonToast
import com.mezon.mobile.ui.cells.AvatarView
import com.mezon.mobile.ui.cells.BackupImageView
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.cells.PopupMenu
import com.mezon.mobile.ui.cells.ToastOverlay
import com.mezon.mobile.util.parseContentPreview
import com.mezon.mobile.util.parseMarkdownAndStrip
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class SharingFragment(
    private val payload: SharingPayload
) : BaseFragment() {

    private lateinit var chatController: ChatController
    private lateinit var dialogsController: DialogsController
    private lateinit var searchController: SearchController
    private lateinit var clansController: ClansController
    private lateinit var sessionManager: SessionManager
    private lateinit var appScope: CoroutineScope
    private lateinit var ioDispatcher: CoroutineDispatcher
    private lateinit var mainDispatcher: CoroutineDispatcher
    private lateinit var channelController: ChannelController
    private lateinit var friendController: FriendController
    private lateinit var permissionPolicy: PermissionPolicy
    private lateinit var forwardTargetUsageStore: ForwardTargetUsageStore

    private val isForwardMode: Boolean
        get() = payload is SharingPayload.ForwardFromChat

    private val forwardPayload: SharingPayload.ForwardFromChat?
        get() = payload as? SharingPayload.ForwardFromChat

    private var forwardMessages = ArrayList<MessageEntity>()
    private val forwardSelectedKeys = HashSet<String>()

    private var forwardPreviewContentText: TextView? = null
    private var forwardPreviewEmbedText: TextView? = null
    private var forwardPreviewImagesRow: LinearLayout? = null
    private var forwardPreviewVideosRow: LinearLayout? = null
    private var forwardPreviewFilesRow: LinearLayout? = null
    private var forwardPreviewImagesLabel: TextView? = null
    private var forwardPreviewVideosLabel: TextView? = null
    private var forwardPreviewFilesLabel: TextView? = null
    private var forwardPreviewThumbHost: ForwardPreviewThumbHost? = null
    private var forwardPreviewPlus: TextView? = null

    private val rebuildForwardDebounced = Runnable {
        if (fragmentView != null && !isPaused && isForwardMode) rebuildTargets()
    }

    private val sharedUris: List<Uri>
        get() = (payload as? SharingPayload.FromDevice)?.uris ?: emptyList()

    private val existingAttachment: AttachmentInfo?
        get() = (payload as? SharingPayload.FromExistingAttachment)?.attachment

    private val sharedText: String?
        get() = (payload as? SharingPayload.FromDevice)?.text

    private val sharedMimeType: String?
        get() = (payload as? SharingPayload.FromDevice)?.mimeType

    private lateinit var recyclerView: RecyclerListView
    private lateinit var adapter: SharingTargetAdapter
    private lateinit var searchEditText: EditText
    private lateinit var emptyView: TextView
    private lateinit var chatArea: LinearLayout
    private lateinit var captionInput: EditText
    private lateinit var sendButton: FrameLayout
    private lateinit var sendIcon: ImageView
    private lateinit var sendProgress: ProgressBar
    private var thumbnailContainer: HorizontalScrollView? = null
    private lateinit var filterButton: FrameLayout
    private lateinit var searchBarContainer: LinearLayout
    private lateinit var selectedChipView: LinearLayout
    private lateinit var selectedChipAvatar: AvatarView
    private lateinit var selectedChipLabel: TextView
    private lateinit var selectedChipClose: ImageView
    private lateinit var searchIcon: ImageView
    private lateinit var rootView: LinearLayout

    private val allTargets = ArrayList<SharingTarget>()
    private val filteredTargets = ArrayList<SharingTarget>()

    private var selectedTarget: SharingTarget? = null
    private var currentFilter = FilterType.ALL
    private var displayLimit = LOCAL_PAGE_SIZE
    private var isSending = false
    private var pendingDeviceShareKey: Pair<Long, Long>? = null

    private val debounceHandler = Handler(Looper.getMainLooper())
    private var debounceRunnable: Runnable? = null

    override fun onInject(entryPoint: FragmentEntryPoint) {
        chatController = entryPoint.chatController()
        dialogsController = entryPoint.dialogsController()
        searchController = entryPoint.searchController()
        clansController = entryPoint.clansController()
        sessionManager = entryPoint.sessionManager()
        appScope = entryPoint.applicationScope()
        ioDispatcher = entryPoint.ioDispatcher()
        mainDispatcher = entryPoint.mainDispatcher()
        channelController = entryPoint.channelController()
        friendController = entryPoint.friendController()
        permissionPolicy = entryPoint.permissionPolicy()
        forwardTargetUsageStore = entryPoint.forwardTargetUsageStore()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        if (isForwardMode) {
            friendController.loadBlockedUsers()
        }
        observe(NotificationCenter.dialogsNeedReload) { _, _, _ ->
            if (fragmentView == null) return@observe
            if (isForwardMode) scheduleRebuildForwardTargets() else rebuildTargets()
        }
        observe(NotificationCenter.searchChannelsDidLoad) { _, _, _ ->
            if (fragmentView == null || isForwardMode) return@observe
            rebuildTargets()
        }
        observe(NotificationCenter.channelsDidLoad) { _, _, _ ->
            if (fragmentView == null || !isForwardMode) return@observe
            scheduleRebuildForwardTargets()
        }
        observe(NotificationCenter.clansDidLoad) { _, _, _ ->
            if (fragmentView == null || !isForwardMode) return@observe
            scheduleRebuildForwardTargets()
        }
        observe(NotificationCenter.blockedUsersLoaded) { _, _, _ ->
            if (fragmentView == null || !isForwardMode) return@observe
            scheduleRebuildForwardTargets()
        }
        observe(NotificationCenter.channelPermissionOverridesDidLoad) { _, _, args ->
            if (fragmentView == null) return@observe
            val changedChannelId = args.firstOrNull() as? Long ?: return@observe
            if (hasTargetForChannel(changedChannelId)) refreshSendButtonEnabled()
        }
        observe(NotificationCenter.channelPermissionsDidLoad) { _, _, args ->
            if (fragmentView == null) return@observe
            val changedChannelId = args.firstOrNull() as? Long ?: return@observe
            if (hasTargetForChannel(changedChannelId)) refreshSendButtonEnabled()
        }
        observe(NotificationCenter.clanRolesDidLoad) { _, _, args ->
            if (fragmentView == null) return@observe
            val changedClanId = args.firstOrNull() as? Long ?: return@observe
            if (hasTargetForClan(changedClanId)) refreshSendButtonEnabled()
        }
        observe(NotificationCenter.pendingMessageSent) { _, _, args ->
            if (fragmentView == null || !isSending || payload !is SharingPayload.FromDevice) return@observe
            val expected = pendingDeviceShareKey ?: return@observe
            val channelId = args.getOrNull(0) as? Long ?: return@observe
            val tempId = args.getOrNull(1) as? Long ?: return@observe
            if (expected.first != channelId || expected.second != tempId) return@observe
            pendingDeviceShareKey = null
            isSending = false
            finishFragment()
        }
        observe(NotificationCenter.pendingMessageError) { _, _, args ->
            if (fragmentView == null || !isSending || payload !is SharingPayload.FromDevice) return@observe
            val expected = pendingDeviceShareKey ?: return@observe
            val channelId = args.getOrNull(0) as? Long ?: return@observe
            val tempId = args.getOrNull(1) as? Long ?: return@observe
            if (expected.first != channelId || expected.second != tempId) return@observe
            pendingDeviceShareKey = null
            isSending = false
            setSendingState(false)
            showErrorToast()
        }
        searchController.loadChannels()
        appScope.launch(ioDispatcher) {
            runCatching { sessionManager.requireValidSession() }
                .onFailure { Log.w(TAG, "requireValidSession", it) }
        }
        return true
    }

    private fun scheduleRebuildForwardTargets() {
        val v = fragmentView ?: return
        v.removeCallbacks(rebuildForwardDebounced)
        v.postDelayed(rebuildForwardDebounced, 120L)
    }

    private fun targetKey(t: SharingTarget): String = "${t.channelId}_${t.channelType}"

    private fun hasTargetForChannel(channelId: Long): Boolean {
        if (selectedTarget?.channelId == channelId) return true
        return allTargets.any { it.channelId == channelId && forwardSelectedKeys.contains(targetKey(it)) }
    }

    private fun hasTargetForClan(clanId: Long): Boolean {
        if (clanId == 0L) return false
        if (selectedTarget?.clanId == clanId) return true
        return allTargets.any { it.clanId == clanId && forwardSelectedKeys.contains(targetKey(it)) }
    }

    private fun ensureSendPermission(t: SharingTarget) {
        if (t.clanId == 0L || t.isDm || t.isGroup) return
        permissionPolicy.ensurePermissionChecker(
            listOf(PermissionPolicy.SEND_MESSAGE),
            t.channelId,
            t.clanId,
        )
    }

    private fun canSendToTarget(t: SharingTarget): Boolean {
        if (t.channelId == 0L) return false
        if (t.clanId == 0L || t.isDm || t.isGroup) return true
        ensureSendPermission(t)
        return permissionPolicy.checkPermission(PermissionPolicy.SEND_MESSAGE, t.channelId, t.clanId)
    }

    private fun selectedForwardTargets(): List<SharingTarget> {
        if (!isForwardMode || forwardSelectedKeys.isEmpty()) return emptyList()
        return allTargets.filter { forwardSelectedKeys.contains(targetKey(it)) }
    }

    private fun showNoSendPermissionToast() {
        MezonToast.show(this, ToastOverlay.ToastType.ERROR, getString(R.string.message_no_send_permission))
    }

    override fun onFragmentDestroy() {
        fragmentView?.removeCallbacks(rebuildForwardDebounced)
        super.onFragmentDestroy()
        debounceRunnable?.let { debounceHandler.removeCallbacks(it) }
    }

    override fun createView(context: Context): View {
        if (isForwardMode) {
            forwardMessages = ForwardNavigationStash.takeMessages() ?: ArrayList()
            if (forwardMessages.isEmpty()) {
                val placeholder = FrameLayout(context)
                placeholder.post { finishFragment() }
                return wrapWithActionBar(getString(R.string.forward_screen_title), placeholder)
            }
        }
        val content = buildContent(context)
        val title = if (isForwardMode) R.string.forward_screen_title else R.string.sharing_title
        return wrapWithActionBar(getString(title), content)
    }

    private fun buildContent(context: Context): View {
        rootView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.background)
        }

        rootView.addView(buildSearchBar(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val suggestionsLabel = TextView(context).apply {
            text = getString(R.string.sharing_suggestions)
            setTextColor(themeColors.onSurfaceVariant)
            textSize = 13f
            setPadding(LayoutHelper.dp(16), LayoutHelper.dp(8), LayoutHelper.dp(16), LayoutHelper.dp(4))
        }
        rootView.addView(suggestionsLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val listFrame = FrameLayout(context)
        emptyView = TextView(context).apply {
            text = getString(R.string.common_no_results_found)
            setTextColor(themeColors.onSurfaceVariant)
            textSize = 16f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        listFrame.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        recyclerView = RecyclerListView(context).apply {
            layoutManager = LinearLayoutManager(context)
            setHasFixedSize(true)
        }
        (recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        recyclerView.itemAnimator = null
        if (!isForwardMode) {
            recyclerView.setEmptyView(emptyView)
        }
        recyclerView.setOnItemClickListener(RecyclerListView.OnItemClickListener { view, _ ->
            if (view is SharingTargetCell) {
                val t = view.target ?: return@OnItemClickListener
                if (isForwardMode) {
                    val key = targetKey(t)
                    if (forwardSelectedKeys.contains(key)) {
                        forwardSelectedKeys.remove(key)
                    } else {
                        forwardSelectedKeys.add(key)
                        ensureSendPermission(t)
                    }
                    adapter.updateForwardSelection(forwardSelectedKeys)
                    refreshForwardSelectionUi()
                } else {
                    selectTarget(t)
                }
            }
        })
        if (!isForwardMode) {
            recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) return
                    val lm = rv.layoutManager as? LinearLayoutManager ?: return
                    val total = adapter.itemCount
                    val lastVisible = lm.findLastVisibleItemPosition()
                    if (lastVisible >= total - 5 && displayLimit < filteredTargets.size) {
                        displayLimit += LOCAL_PAGE_SIZE
                        adapter.setData(filteredTargets.take(displayLimit), false, emptySet())
                    }
                }
            })
        }
        listFrame.addView(recyclerView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))
        rootView.addView(listFrame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        chatArea = if (isForwardMode) buildForwardChatArea(context) else buildDeviceChatArea(context)
        chatArea.visibility = if (isForwardMode) View.VISIBLE else View.GONE
        rootView.addView(chatArea, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        adapter = SharingTargetAdapter(themeColors)
        recyclerView.adapter = adapter

        if (isForwardMode) {
            prefetchChannelCachesIfNeeded()
            refreshForwardPreviewContent()
        }
        rebuildTargets()
        refreshSendButtonEnabled()

        return rootView
    }

    private fun buildSearchBar(context: Context): View {
        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(themeColors.surface)
            setPadding(LayoutHelper.dp(12), LayoutHelper.dp(8), LayoutHelper.dp(8), LayoutHelper.dp(8))
            gravity = Gravity.CENTER_VERTICAL
        }

        searchBarContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(themeColors.surfaceVariant)
                cornerRadius = LayoutHelper.dpf(20f)
            }
            setPadding(LayoutHelper.dp(12), LayoutHelper.dp(8), LayoutHelper.dp(12), LayoutHelper.dp(8))
        }

        searchIcon = ImageView(context).apply {
            val d = MezonIcon.magnifyingIcon.getDrawable(context)
            d.colorFilter = PorterDuffColorFilter(themeColors.onSurfaceVariant, PorterDuff.Mode.SRC_IN)
            setImageDrawable(d)
        }
        searchBarContainer.addView(searchIcon, LayoutHelper.createLinear(18, 18, 0f, Gravity.CENTER_VERTICAL, 0f, 0f, 8f, 0f))

        selectedChipView = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        selectedChipAvatar = AvatarView(context).apply { setSizeDp(20) }
        selectedChipView.addView(selectedChipAvatar, LayoutHelper.createLinear(20, 20))
        selectedChipLabel = TextView(context).apply {
            setTextColor(themeColors.onSurface)
            textSize = 14f
            maxLines = 1
            setPadding(LayoutHelper.dp(6), 0, 0, 0)
        }
        selectedChipView.addView(selectedChipLabel, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))
        searchBarContainer.addView(selectedChipView, LayoutHelper.createLinear(
            LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f,
            Gravity.CENTER_VERTICAL, 0f, 0f, 6f, 0f
        ))

        searchEditText = EditText(context).apply {
            hint = getString(
                if (isForwardMode) R.string.common_search_placeholder else R.string.sharing_select_channel_placeholder
            )
            setHintTextColor(themeColors.onSurfaceVariant)
            setTextColor(themeColors.onSurface)
            background = null
            textSize = 14f
            maxLines = 1
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(0, 0, 0, 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    debounceRunnable?.let { debounceHandler.removeCallbacks(it) }
                    val query = s?.toString() ?: ""
                    debounceRunnable = Runnable {
                        if (!isForwardMode) {
                            displayLimit = LOCAL_PAGE_SIZE
                        }
                        applyFilter(query)
                    }
                    debounceHandler.postDelayed(debounceRunnable!!, DEBOUNCE_MS)
                }
            })
        }
        searchBarContainer.addView(searchEditText, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))

        selectedChipClose = ImageView(context).apply {
            val d = MezonIcon.closeSmallBold.getDrawable(context)
            d.colorFilter = PorterDuffColorFilter(themeColors.onSurfaceVariant, PorterDuff.Mode.SRC_IN)
            setImageDrawable(d)
            visibility = View.GONE
            setOnClickListener { clearSelection() }
            setPadding(LayoutHelper.dp(4), LayoutHelper.dp(4), LayoutHelper.dp(4), LayoutHelper.dp(4))
        }
        searchBarContainer.addView(selectedChipClose, LayoutHelper.createLinear(24, 24, 0f, Gravity.CENTER_VERTICAL))

        outer.addView(searchBarContainer, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))

        val rippleMask = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
        }
        val filterBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(themeColors.surfaceVariant)
        }
        filterButton = FrameLayout(context).apply {
            background = RippleDrawable(
                ColorStateList.valueOf(themeColors.onSurfaceVariant and 0x33FFFFFF),
                filterBg,
                rippleMask
            )
            setOnClickListener { showFilterPopup(it) }
        }
        val filterIcon = ImageView(context).apply {
            val d = MezonIcon.filterHorizontalIcon.getDrawable(context)
            d.colorFilter = PorterDuffColorFilter(themeColors.onSurface, PorterDuff.Mode.SRC_IN)
            setImageDrawable(d)
        }
        filterButton.addView(filterIcon, LayoutHelper.createFrame(18, 18, Gravity.CENTER))
        outer.addView(filterButton, LayoutHelper.createLinear(36, 36, 0f, Gravity.CENTER_VERTICAL, 6f, 0f, 0f, 0f))

        if (isForwardMode) {
            filterButton.visibility = View.GONE
        }

        return outer
    }

    private fun buildForwardChatArea(context: Context): LinearLayout {
        val area = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.surface)
        }

        val previewStrip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(themeColors.channelPanelBg)
            setPadding(LayoutHelper.dp(12f), LayoutHelper.dp(4f), LayoutHelper.dp(12f), LayoutHelper.dp(4f))
        }
        val borderAccent = View(context).apply {
            setBackgroundColor(themeColors.outline)
        }
        previewStrip.addView(
            borderAccent,
            LinearLayout.LayoutParams(LayoutHelper.dp(2f), ViewGroup.LayoutParams.MATCH_PARENT)
        )
        val previewInner = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(LayoutHelper.dp(6f), LayoutHelper.dp(2f), LayoutHelper.dp(6f), LayoutHelper.dp(2f))
            gravity = Gravity.CENTER_VERTICAL
        }
        val previewCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        forwardPreviewContentText = TextView(context).apply {
            setTextColor(themeColors.onSurfaceVariant)
            textSize = 13f
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
        }
        forwardPreviewEmbedText = TextView(context).apply {
            setTextColor(themeColors.onSurfaceVariant)
            textSize = 12f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        fun metaRow(icon: MezonIcon): Pair<LinearLayout, TextView> {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                visibility = View.GONE
            }
            val iv = ImageView(context).apply {
                setImageDrawable(icon.getDrawable(context))
                colorFilter = PorterDuffColorFilter(themeColors.onSurfaceVariant, PorterDuff.Mode.SRC_IN)
            }
            row.addView(iv, LayoutHelper.createLinear(16, 16, 0f, Gravity.CENTER_VERTICAL, 0f, 0f, 6f, 0f))
            val tv = TextView(context).apply {
                setTextColor(themeColors.onSurfaceVariant)
                textSize = 12f
            }
            row.addView(tv, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))
            return row to tv
        }
        val pImg = metaRow(MezonIcon.imageIcon)
        forwardPreviewImagesRow = pImg.first
        forwardPreviewImagesLabel = pImg.second
        val pVid = metaRow(MezonIcon.playCircleIcon)
        forwardPreviewVideosRow = pVid.first
        forwardPreviewVideosLabel = pVid.second
        val pFil = metaRow(MezonIcon.attachmentIcon)
        forwardPreviewFilesRow = pFil.first
        forwardPreviewFilesLabel = pFil.second
        previewCol.addView(forwardPreviewContentText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        previewCol.addView(forwardPreviewEmbedText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        previewCol.addView(forwardPreviewImagesRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        previewCol.addView(forwardPreviewVideosRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        previewCol.addView(forwardPreviewFilesRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val thumbSize = LayoutHelper.dp(50f)
        val thumbFrame = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(thumbSize, thumbSize).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = LayoutHelper.dp(6f)
            }
        }
        forwardPreviewThumbHost = ForwardPreviewThumbHost(context)
        thumbFrame.addView(forwardPreviewThumbHost, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))
        forwardPreviewPlus = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(LayoutHelper.dp(4f), LayoutHelper.dp(2f), LayoutHelper.dp(4f), LayoutHelper.dp(4f))
            background = GradientDrawable().apply {
                setColor(0x99000000.toInt())
                cornerRadius = LayoutHelper.dpf(4f)
            }
            visibility = View.GONE
        }
        thumbFrame.addView(
            forwardPreviewPlus,
            FrameLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(0, 0, LayoutHelper.dp(4f), LayoutHelper.dp(4f))
            }
        )

        previewInner.addView(previewCol, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL))
        previewInner.addView(thumbFrame)
        previewStrip.addView(previewInner, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))
        area.addView(previewStrip, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val inputRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(12), LayoutHelper.dp(8), LayoutHelper.dp(8), LayoutHelper.dp(8))
        }

        captionInput = EditText(context).apply {
            hint = getString(R.string.forward_extra_hint)
            setHintTextColor(themeColors.onSurfaceVariant)
            setTextColor(themeColors.onSurface)
            background = GradientDrawable().apply {
                setColor(themeColors.tertiary)
                cornerRadius = LayoutHelper.dpf(20f)
            }
            textSize = 14f
            maxLines = 4
            isSingleLine = false
            filters = arrayOf(InputFilter.LengthFilter(MAX_FORWARD_COMMENT_CHARS))
            val padH = LayoutHelper.dp(14)
            val padV = LayoutHelper.dp(8)
            setPadding(padH, padV, padH, padV)
        }
        inputRow.addView(captionInput, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))

        sendButton = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(themeColors.primary)
            }
            alpha = 0.5f
            isEnabled = false
            setOnClickListener { onSend() }
        }
        sendIcon = ImageView(context).apply {
            val d = MezonIcon.sendMessageIcon.getDrawable(context)
            d.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            setImageDrawable(d)
        }
        sendButton.addView(sendIcon, LayoutHelper.createFrame(20, 20, Gravity.CENTER))
        sendProgress = ProgressBar(context).apply {
            visibility = View.GONE
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
        }
        sendButton.addView(sendProgress, LayoutHelper.createFrame(20, 20, Gravity.CENTER))
        inputRow.addView(sendButton, LayoutHelper.createLinear(40, 40, 0f, Gravity.CENTER_VERTICAL, 8f, 0f, 0f, 0f))

        area.addView(inputRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        return area
    }

    private fun buildDeviceChatArea(context: Context): LinearLayout {
        val area = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.surface)
        }

        val thumbScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(LayoutHelper.dp(12), LayoutHelper.dp(8), LayoutHelper.dp(12), 0)
        }
        thumbnailContainer = thumbScroll
        val thumbRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        for ((index, uri) in sharedUris.withIndex()) {
            val frame = FrameLayout(context)
            val img = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(themeColors.tertiary)
            }
            frame.addView(img, LayoutHelper.createFrame(60, 60))
            frame.clipToOutline = true
            frame.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, LayoutHelper.dpf(8f))
                }
            }
            loadThumbnail(img, uri)

            val mime = resolveSharedMimeType(context.contentResolver, uri)
            if (mime.startsWith("video/", ignoreCase = true)) {
                val playOverlay = ImageView(context).apply {
                    val d = MezonIcon.playIcon.getDrawable(context)
                    d.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
                    setImageDrawable(d)
                }
                frame.addView(playOverlay, LayoutHelper.createFrame(20, 20, Gravity.CENTER))
            }

            val lp = LinearLayout.LayoutParams(LayoutHelper.dp(60), LayoutHelper.dp(60))
            if (index > 0) lp.leftMargin = LayoutHelper.dp(8)
            thumbRow.addView(frame, lp)
        }
        existingAttachment?.let { attachment ->
            val frame = FrameLayout(context)
            val image = BackupImageView(context).apply {
                setAspectFill(true)
                setRoundRadius(LayoutHelper.dp(8))
                setBackgroundColor(themeColors.tertiary)
                attachment.thumb.takeIf { it.isNotBlank() && it != attachment.url }?.let {
                    setImage(it)
                }
            }
            frame.addView(image, LayoutHelper.createFrame(60, 60))
            val playOverlay = ImageView(context).apply {
                val drawable = MezonIcon.playIcon.getDrawable(context)
                drawable.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
                setImageDrawable(drawable)
            }
            frame.addView(playOverlay, LayoutHelper.createFrame(20, 20, Gravity.CENTER))
            thumbRow.addView(frame, LinearLayout.LayoutParams(LayoutHelper.dp(60), LayoutHelper.dp(60)))
        }
        thumbScroll.addView(thumbRow)
        if (sharedUris.isNotEmpty() || existingAttachment != null) {
            area.addView(thumbScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        }

        val inputRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(12), LayoutHelper.dp(8), LayoutHelper.dp(8), LayoutHelper.dp(8))
        }

        captionInput = EditText(context).apply {
            hint = getString(R.string.sharing_add_comment)
            setHintTextColor(themeColors.onSurfaceVariant)
            setTextColor(themeColors.onSurface)
            background = GradientDrawable().apply {
                setColor(themeColors.tertiary)
                cornerRadius = LayoutHelper.dpf(20f)
            }
            textSize = 14f
            maxLines = 3
            isSingleLine = false
            val padH = LayoutHelper.dp(14)
            val padV = LayoutHelper.dp(8)
            setPadding(padH, padV, padH, padV)
            if (!sharedText.isNullOrBlank()) {
                setText(sharedText)
            }
        }
        inputRow.addView(captionInput, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))

        sendButton = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(themeColors.primary)
            }
            alpha = 0.5f
            isEnabled = false
            setOnClickListener { onSend() }
        }
        sendIcon = ImageView(context).apply {
            val d = MezonIcon.sendMessageIcon.getDrawable(context)
            d.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            setImageDrawable(d)
        }
        sendButton.addView(sendIcon, LayoutHelper.createFrame(20, 20, Gravity.CENTER))
        sendProgress = ProgressBar(context).apply {
            visibility = View.GONE
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
        }
        sendButton.addView(sendProgress, LayoutHelper.createFrame(20, 20, Gravity.CENTER))
        inputRow.addView(sendButton, LayoutHelper.createLinear(40, 40, 0f, Gravity.CENTER_VERTICAL, 8f, 0f, 0f, 0f))

        area.addView(inputRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        return area
    }

    private fun loadThumbnail(imageView: ImageView, uri: Uri) {
        val cr = getParentActivity()?.contentResolver ?: return
        fragmentScope.launch {
            val bmp = try {
                kotlinx.coroutines.withContext(ioDispatcher) {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                }
            } catch (_: Exception) {
                null
            }
            if (bmp != null) imageView.setImageBitmap(bmp)
        }
    }

    private fun selectTarget(t: SharingTarget) {
        selectedTarget = t
        ensureSendPermission(t)

        searchEditText.visibility = View.GONE
        searchIcon.visibility = View.GONE
        selectedChipView.visibility = View.VISIBLE
        selectedChipClose.visibility = View.VISIBLE

        selectedChipAvatar.setInfo(t.channelId, t.channelLabel)
        selectedChipAvatar.setImageUrl(t.avatarUrl.ifEmpty { t.clanLogo }.ifEmpty { null })
        selectedChipLabel.text = t.channelLabel

        chatArea.visibility = View.VISIBLE
        refreshSendButtonEnabled()

        captionInput.requestFocus()
    }

    private fun clearSelection() {
        if (isForwardMode) return
        selectedTarget = null
        searchEditText.visibility = View.VISIBLE
        searchEditText.setText("")
        searchIcon.visibility = View.VISIBLE
        selectedChipView.visibility = View.GONE
        selectedChipClose.visibility = View.GONE

        chatArea.visibility = View.GONE
        refreshSendButtonEnabled()

        searchEditText.requestFocus()
    }

    private fun showFilterPopup(anchor: View) {
        if (isForwardMode) return
        AndroidUtilities.hideKeyboard(searchEditText)

        val popup = PopupMenu(anchor.context, themeColors)
        popup.addItem(getString(R.string.sharing_filter_all), MezonIcon.communityIcon)
        popup.addItem(getString(R.string.sharing_filter_users), MezonIcon.userIcon)
        popup.addItem(getString(R.string.sharing_filter_channels), MezonIcon.channelText)

        popup.setOnItemClickListener { index ->
            currentFilter = when (index) {
                1 -> FilterType.USER
                2 -> FilterType.CHANNEL
                else -> FilterType.ALL
            }
            updateSearchHint()
            displayLimit = LOCAL_PAGE_SIZE
            applyFilter(searchEditText.text?.toString() ?: "")
        }

        popup.show(anchor)
    }

    private fun updateSearchHint() {
        searchEditText.hint = when (currentFilter) {
            FilterType.ALL -> getString(R.string.sharing_select_channel_placeholder)
            FilterType.USER -> getString(R.string.sharing_select_user)
            FilterType.CHANNEL -> getString(R.string.sharing_select_channel)
        }
    }

    private fun rebuildTargets() {
        if (isForwardMode) rebuildForwardTargets() else rebuildDeviceTargets()
    }

    private fun rebuildDeviceTargets() {
        allTargets.clear()

        val dms = dialogsController.getDialogs()
        for (dm in dms) {
            allTargets.add(dm.toSharingTarget())
        }

        val clans = clansController.clans.value
        val clanMap = clans.associateBy { it.clanId }
        val channels = searchController.getChannels()
        val channelLabelById = HashMap<Long, String>(channels.size)
        for (ch in channels) {
            channelLabelById[ch.channelId] = ch.channelLabel
        }
        for (ch in channels) {
            if (ch.type == CHANNEL_TYPE_VOICE) continue
            val clan = clanMap[ch.clanId]
            val parentLabel = if (ch.parentId != 0L) channelLabelById[ch.parentId].orEmpty() else ""
            allTargets.add(ch.toSharingTarget(clan?.clanName ?: "", clan?.logo ?: "", parentLabel))
        }

        allTargets.sortByDescending { it.lastActivityTs }
        displayLimit = LOCAL_PAGE_SIZE
        applyFilter(searchEditText.text?.toString() ?: "")
    }

    private fun prefetchChannelCachesIfNeeded() {
        for (clan in clansController.clans.value) {
            if (channelController.getChannels(clan.clanId).isEmpty()) {
                channelController.loadChannelsForClan(clan.clanId)
            }
        }
    }

    private fun rebuildForwardTargets() {
        allTargets.clear()
        val blocked = friendController.blockedUsers.value.asSequence().map { it.user.id }.toSet()
        val clans = clansController.clans.value
        val channelLabelById = HashMap<Long, String>()
        for (clan in clans) {
            for (ch in channelController.getChannels(clan.clanId)) {
                channelLabelById[ch.channelId] = ch.channelLabel
            }
        }
        for (clan in clans) {
            for (ch in channelController.getChannels(clan.clanId)) {
                val t = ch.type
                if (t != CHANNEL_TYPE_CHANNEL && t != CHANNEL_TYPE_THREAD) continue
                if (ch.channelLabel.isBlank()) continue
                val parentLabel = if (ch.parentId != 0L) channelLabelById[ch.parentId].orEmpty() else ""
                allTargets.add(ch.toSharingTarget(clan.clanName, clan.logo, parentLabel))
            }
        }
        for (dm in dialogsController.getDialogs()) {
            val t = dm.type
            if (t != CHANNEL_TYPE_DM && t != CHANNEL_TYPE_GROUP) continue
            if (dm.label.isBlank()) continue
            if (t == CHANNEL_TYPE_DM && dm.otherUserId != 0L && dm.otherUserId in blocked) continue
            allTargets.add(dm.toSharingTarget())
        }
        allTargets.sortWith(
            compareByDescending<SharingTarget> { forwardTargetOwnSentTs(it) }
        )
        displayLimit = maxOf(allTargets.size, LOCAL_PAGE_SIZE)
        applyFilter(searchEditText.text?.toString() ?: "")
    }

    private fun forwardTargetOwnSentTs(target: SharingTarget): Long {
        return forwardTargetUsageStore.getLastSent(target.channelId, target.channelType)
    }

    private fun applyFilter(query: String) {
        if (isForwardMode) {
            filteredTargets.clear()
            val qTrim = query.trim()
            when {
                qTrim.isEmpty() -> filteredTargets.addAll(allTargets)
                qTrim.startsWith("#") -> {
                    val needle = qTrim.drop(1).trim().lowercase()
                    for (t in allTargets) {
                        if (t.channelType != CHANNEL_TYPE_CHANNEL && t.channelType != CHANNEL_TYPE_THREAD) continue
                        if (t.matchesForwardQuery(needle)) filteredTargets.add(t)
                    }
                }
                else -> {
                    val needle = qTrim.lowercase()
                    for (t in allTargets) {
                        if (t.matchesForwardQuery(needle)) filteredTargets.add(t)
                    }
                }
            }
            val empty = filteredTargets.isEmpty()
            emptyView.visibility = if (empty) View.VISIBLE else View.GONE
            recyclerView.visibility = if (empty) View.GONE else View.VISIBLE
            adapter.setData(filteredTargets, true, forwardSelectedKeys)
            return
        }

        filteredTargets.clear()

        val source = when (currentFilter) {
            FilterType.ALL -> allTargets
            FilterType.USER -> allTargets.filter { it.isDm || it.isGroup }
            FilterType.CHANNEL -> allTargets.filter { it.isClanChannel }
        }

        if (query.isBlank()) {
            filteredTargets.addAll(source)
        } else {
            val lower = query.lowercase()
            for (t in source) {
                if (t.channelLabel.lowercase().contains(lower) ||
                    t.clanName.lowercase().contains(lower) ||
                    t.username.lowercase().contains(lower.removePrefix("@"))
                ) {
                    filteredTargets.add(t)
                }
            }
        }

        adapter.setData(filteredTargets.take(displayLimit), false, emptySet())
    }

    private fun refreshForwardSelectionUi() {
        if (!isForwardMode) return
        chatArea.visibility = View.VISIBLE
        refreshSendButtonEnabled()
    }

    private fun SharingTarget.matchesForwardQuery(needle: String): Boolean {
        val cleanNeedle = needle.removePrefix("@")
        return channelLabel.lowercase().contains(needle) ||
            clanName.lowercase().contains(needle) ||
            username.lowercase().contains(cleanNeedle)
    }

    private fun refreshSendButtonEnabled() {
        if (isForwardMode) {
            val targets = selectedForwardTargets()
            val ok = targets.isNotEmpty() && targets.all { canSendToTarget(it) } && !isSending
            sendButton.isEnabled = ok
            sendButton.alpha = if (ok) 1f else 0.5f
            return
        }
        val target = selectedTarget
        sendButton.isEnabled = target != null && canSendToTarget(target) && !isSending
        sendButton.alpha = if (sendButton.isEnabled) 1f else 0.5f
    }

    private fun refreshForwardPreviewContent() {
        val previewContentText = forwardPreviewContentText ?: return
        val previewEmbedText = forwardPreviewEmbedText ?: return
        val previewImagesRow = forwardPreviewImagesRow ?: return
        val previewVideosRow = forwardPreviewVideosRow ?: return
        val previewFilesRow = forwardPreviewFilesRow ?: return
        val previewImagesLabel = forwardPreviewImagesLabel ?: return
        val previewVideosLabel = forwardPreviewVideosLabel ?: return
        val previewFilesLabel = forwardPreviewFilesLabel ?: return
        val previewThumbHost = forwardPreviewThumbHost ?: return
        val previewPlus = forwardPreviewPlus ?: return

        val first = forwardMessages.firstOrNull()
        if (forwardMessages.size > 1) {
            previewContentText.text = getString(R.string.forward_preview_many, forwardMessages.size)
        } else {
            previewContentText.text = parseContentPreview(first?.content.orEmpty())
        }
        previewContentText.visibility = if (previewContentText.text.isNullOrBlank()) View.GONE else View.VISIBLE

        var embedTitle = ""
        if (first != null && forwardMessages.size <= 1) {
            try {
                val embeds = JSONObject(first.content).optJSONArray("embed")
                embedTitle = embeds?.optJSONObject(0)?.optString("title").orEmpty()
            } catch (_: Exception) {}
        }
        if (embedTitle.isNotEmpty()) {
            previewEmbedText.text = embedTitle
            previewEmbedText.visibility = View.VISIBLE
        } else {
            previewEmbedText.visibility = View.GONE
        }

        val mediaMsg = first
        if (mediaMsg == null || forwardMessages.size > 1) {
            previewImagesRow.visibility = View.GONE
            previewVideosRow.visibility = View.GONE
            previewFilesRow.visibility = View.GONE
            previewThumbHost.bind(null, null)
            previewPlus.visibility = View.GONE
            return
        }

        val all = mutableListOf<AttachmentInfo>()
        if (mediaMsg.attachmentUrl.isNotEmpty()) {
            all.add(
                AttachmentInfo(
                    mediaMsg.attachmentUrl,
                    mediaMsg.attachmentThumb,
                    mediaMsg.attachmentWidth,
                    mediaMsg.attachmentHeight,
                    mediaMsg.attachmentFilename,
                    mediaMsg.attachmentFiletype,
                    mediaMsg.attachmentSize,
                    mediaMsg.attachmentDuration
                )
            )
        }
        all.addAll(mediaMsg.extraAttachments)

        var img = 0
        var vid = 0
        var fil = 0
        for (a in all) {
            when {
                isGifAttachment(a.filetype, a.filename, a.url) ||
                    isImageAttachmentType(a.filetype) -> img++
                isVideoAttachmentType(a.filetype) -> vid++
                else -> fil++
            }
        }

        if (img > 0) {
            previewImagesRow.visibility = View.VISIBLE
            previewImagesLabel.text = if (img == 1) getString(R.string.forward_meta_photo, img) else getString(R.string.forward_meta_photos, img)
        } else {
            previewImagesRow.visibility = View.GONE
        }
        if (vid > 0) {
            previewVideosRow.visibility = View.VISIBLE
            previewVideosLabel.text = if (vid == 1) getString(R.string.forward_meta_video, vid) else getString(R.string.forward_meta_videos, vid)
        } else {
            previewVideosRow.visibility = View.GONE
        }
        if (fil > 0) {
            previewFilesRow.visibility = View.VISIBLE
            previewFilesLabel.text = if (fil == 1) getString(R.string.forward_meta_file, fil) else getString(R.string.forward_meta_files, fil)
        } else {
            previewFilesRow.visibility = View.GONE
        }

        val mediaOnly = all.filter { isMediaAttachment(it.filetype, it.url, it.filename) }
        if (mediaOnly.isNotEmpty()) {
            val a0 = mediaOnly[0]
            previewThumbHost.bind(a0.url, a0.thumb.takeIf { it.isNotBlank() } ?: a0.url)
            val extra = mediaOnly.size - 1
            if (extra > 0) {
                previewPlus.text = getString(R.string.forward_thumb_more, extra)
                previewPlus.visibility = View.VISIBLE
            } else {
                previewPlus.visibility = View.GONE
            }
        } else {
            previewThumbHost.bind(null, null)
            previewPlus.visibility = View.GONE
        }
    }

    private fun setSendingState(sending: Boolean) {
        isSending = sending
        captionInput.isEnabled = !sending
        sendIcon.visibility = if (sending) View.GONE else View.VISIBLE
        sendProgress.visibility = if (sending) View.VISIBLE else View.GONE
        refreshSendButtonEnabled()
    }

    private fun showErrorToast() {
        val parent = fragmentView as? ViewGroup ?: return
        val toast = ToastOverlay(parent.context, themeColors)
        toast.show(parent, ToastOverlay.ToastType.ERROR, getString(R.string.sharing_failed))
    }

    private fun onSend() {
        if (isSending) return
        if (isForwardMode) {
            val fp = forwardPayload ?: return
            val targets = selectedForwardTargets()
            if (targets.isEmpty()) {
                MezonToast.show(this, ToastOverlay.ToastType.INFO, getString(R.string.forward_pick_dest))
                return
            }
            if (targets.any { !canSendToTarget(it) }) {
                showNoSendPermissionToast()
                return
            }
            val picks = targets.map { it.toForwardDestination() }
            AndroidUtilities.hideKeyboard(captionInput)
            setSendingState(true)
            chatController.forwardMessages(
                fp.sourceChannelId,
                forwardMessages,
                picks,
                captionInput.text?.toString().orEmpty()
            ) { ok ->
                setSendingState(false)
                if (ok) {
                    MezonToast.show(this, ToastOverlay.ToastType.INFO, getString(R.string.forward_messages_success))
                    finishFragment()
                } else {
                    MezonToast.show(this, ToastOverlay.ToastType.ERROR, getString(R.string.forward_messages_error))
                }
            }
            return
        }

        val target = selectedTarget ?: return
        if (!canSendToTarget(target)) {
            showNoSendPermissionToast()
            return
        }
        AndroidUtilities.hideKeyboard(captionInput)

        val caption = captionInput.text?.toString()?.trim() ?: sharedText ?: ""
        val mdResult = parseMarkdownAndStrip(caption)
        val cleanedText = mdResult.cleanedText
        val mdMarkers = mdResult.markers.ifEmpty { null }

        existingAttachment?.let { attachment ->
            setSendingState(true)
            chatController.shareExistingMediaToChannel(
                channelId = target.channelId,
                clanId = target.clanId,
                channelType = target.channelType,
                isChannelPrivate = target.isPrivate,
                text = cleanedText,
                attachment = attachment,
                markdownMarkers = mdMarkers,
                parentId = target.parentId
            ) { ok ->
                if (fragmentView == null) return@shareExistingMediaToChannel
                setSendingState(false)
                if (ok) {
                    finishFragment()
                } else {
                    showErrorToast()
                }
            }
            return
        }

        if (sharedUris.isNotEmpty()) {
            val activity = getParentActivity() ?: run {
                showErrorToast()
                return
            }
            pendingDeviceShareKey = null
            setSendingState(true)
            val contentResolver = activity.contentResolver
            val metadataContext = activity.applicationContext
            fragmentScope.launch(mainDispatcher) {
                val attachments = try {
                    withContext(ioDispatcher) {
                        buildAttachments(metadataContext, contentResolver)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if (fragmentView != null) {
                        pendingDeviceShareKey = null
                        setSendingState(false)
                        showErrorToast()
                    }
                    return@launch
                }
                if (fragmentView == null || !isSending) return@launch
                val tempId = chatController.shareMediaToChannel(
                    channelId = target.channelId,
                    clanId = target.clanId,
                    channelType = target.channelType,
                    isChannelPrivate = target.isPrivate,
                    text = cleanedText,
                    attachments = attachments,
                    contentResolver = contentResolver,
                    markdownMarkers = mdMarkers,
                    parentId = target.parentId
                )
                pendingDeviceShareKey = target.channelId to tempId
            }
        } else if (cleanedText.isNotBlank()) {
            if (target.isClanChannel) {
                chatController.openChannel(target.channelId, target.clanId, target.channelType, target.isPrivate)
            }
            chatController.sendMessage(
                channelId = target.channelId,
                clanId = target.clanId,
                channelType = target.channelType,
                isChannelPrivate = target.isPrivate,
                text = cleanedText,
                markdownMarkers = mdMarkers
            )
            finishFragment()
        }
    }

    private fun buildAttachments(context: Context, contentResolver: ContentResolver): List<AttachmentPickerItem> {
        return sharedUris.mapIndexed { index, uri ->
            val mimeType = resolveSharedMimeType(contentResolver, uri)
            val filename = resolveFilename(contentResolver, uri, index, mimeType)
            val size = resolveFileSize(contentResolver, uri)
            var width = 0
            var height = 0
            var duration = 0
            if (mimeType.startsWith("image/", ignoreCase = true)) {
                try {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                    width = opts.outWidth
                    height = opts.outHeight
                } catch (_: Exception) {}
            } else if (mimeType.startsWith("video/", ignoreCase = true)) {
                resolveVideoMetadata(context, uri)?.let { metadata ->
                    width = metadata.width
                    height = metadata.height
                    duration = metadata.durationSeconds
                }
            }
            AttachmentPickerItem(
                id = index.toLong(),
                uri = uri,
                path = uri.toString(),
                filename = filename,
                mimeType = mimeType,
                width = width,
                height = height,
                size = size,
                duration = duration,
                isVideo = mimeType.startsWith("video/", ignoreCase = true)
            )
        }
    }

    private fun resolveSharedMimeType(contentResolver: ContentResolver, uri: Uri): String {
        val uriMimeType = runCatching { contentResolver.getType(uri) }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val intentMimeType = sharedMimeType
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        return listOfNotNull(uriMimeType, intentMimeType)
            .maxByOrNull(::mimeTypeSpecificity)
            ?.takeIf { mimeTypeSpecificity(it) > 0 }
            ?: "application/octet-stream"
    }

    private fun mimeTypeSpecificity(mimeType: String): Int {
        if (mimeType.equals("application/octet-stream", ignoreCase = true)) return 0
        val type = mimeType.substringBefore('/', "").trim()
        val subtype = mimeType.substringAfter('/', "").trim()
        if (type.isEmpty() || type == "*" || subtype.isEmpty()) return 0
        return if (subtype == "*") 1 else 2
    }

    private data class VideoMetadata(
        val width: Int,
        val height: Int,
        val durationSeconds: Int
    )

    private fun resolveVideoMetadata(context: Context, uri: Uri): VideoMetadata? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val rawWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val rawHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val swapDimensions = rotation % 180 != 0
            VideoMetadata(
                width = if (swapDimensions) rawHeight else rawWidth,
                height = if (swapDimensions) rawWidth else rawHeight,
                durationSeconds = (durationMs / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            )
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun resolveFilename(
        contentResolver: ContentResolver,
        uri: Uri,
        index: Int,
        mimeType: String
    ): String {
        return try {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) it.getString(nameIndex) else null
                } else null
            } ?: "file_${index}_${System.currentTimeMillis()}.${mimeType.substringAfterLast('/')}"
        } catch (_: Exception) {
            "file_${index}_${System.currentTimeMillis()}.${mimeType.substringAfterLast('/')}"
        }
    }

    private fun resolveFileSize(contentResolver: ContentResolver, uri: Uri): Long {
        return try {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val sizeIndex = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (sizeIndex >= 0) it.getLong(sizeIndex) else 0L
                } else 0L
            } ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    enum class FilterType { ALL, USER, CHANNEL }

    companion object {
        private const val TAG = "SharingFragment"
        private const val DEBOUNCE_MS = 300L
        private const val MAX_FORWARD_COMMENT_CHARS = 2000

        fun fromDevice(uris: List<Uri>, text: String?, mimeType: String?) =
            SharingFragment(SharingPayload.FromDevice(uris, text, mimeType))

        fun fromExistingAttachment(attachment: AttachmentInfo) =
            SharingFragment(SharingPayload.FromExistingAttachment(attachment))
    }
}
