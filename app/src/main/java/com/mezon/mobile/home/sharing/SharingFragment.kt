package com.mezon.mobile.home.sharing

import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Editable
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
import com.mezon.mobile.home.chat.AttachmentPickerItem
import com.mezon.mobile.home.clans.CHANNEL_TYPE_VOICE
import com.mezon.mobile.home.clans.ClansController
import com.mezon.mobile.search.LOCAL_PAGE_SIZE
import com.mezon.mobile.search.SearchController
import com.mezon.mobile.ui.cells.AvatarView
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.cells.PopupMenu
import com.mezon.mobile.ui.cells.ToastOverlay
import com.mezon.mobile.util.parseMarkdownAndStrip

class SharingFragment(
    private val sharedUris: List<Uri>,
    private val sharedText: String?,
    private val sharedMimeType: String?
) : BaseFragment() {

    private lateinit var chatController: ChatController
    private lateinit var dialogsController: DialogsController
    private lateinit var searchController: SearchController
    private lateinit var clansController: ClansController

    private lateinit var recyclerView: RecyclerListView
    private lateinit var adapter: SharingTargetAdapter
    private lateinit var searchEditText: EditText
    private lateinit var emptyView: TextView
    private lateinit var chatArea: LinearLayout
    private lateinit var captionInput: EditText
    private lateinit var sendButton: FrameLayout
    private lateinit var sendIcon: ImageView
    private lateinit var sendProgress: ProgressBar
    private lateinit var thumbnailContainer: HorizontalScrollView
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

    private val debounceHandler = Handler(Looper.getMainLooper())
    private var debounceRunnable: Runnable? = null

    override fun onInject(entryPoint: FragmentEntryPoint) {
        chatController = entryPoint.chatController()
        dialogsController = entryPoint.dialogsController()
        searchController = entryPoint.searchController()
        clansController = entryPoint.clansController()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        observe(NotificationCenter.dialogsNeedReload) { _, _, _ ->
            if (fragmentView == null) return@observe
            rebuildTargets()
        }
        observe(NotificationCenter.searchChannelsDidLoad) { _, _, _ ->
            if (fragmentView == null) return@observe
            rebuildTargets()
        }
        observe(NotificationCenter.pendingMessageSent) { _, _, _ ->
            if (fragmentView == null || !isSending) return@observe
            isSending = false
            finishFragment()
        }
        observe(NotificationCenter.pendingMessageError) { _, _, _ ->
            if (fragmentView == null || !isSending) return@observe
            isSending = false
            setSendingState(false)
            showErrorToast()
        }
        searchController.loadChannels()
        return true
    }

    override fun onFragmentDestroy() {
        super.onFragmentDestroy()
        debounceRunnable?.let { debounceHandler.removeCallbacks(it) }
    }

    override fun createView(context: Context): View {
        val content = buildContent(context)
        return wrapWithActionBar(getString(R.string.sharing_title), content)
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
            text = getString(R.string.sharing_no_conversations)
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
        recyclerView.setEmptyView(emptyView)
        recyclerView.setOnItemClickListener(RecyclerListView.OnItemClickListener { view, _ ->
            if (view is SharingTargetCell) {
                val t = view.target ?: return@OnItemClickListener
                selectTarget(t)
            }
        })
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                val total = adapter.itemCount
                val lastVisible = lm.findLastVisibleItemPosition()
                if (lastVisible >= total - 5 && displayLimit < filteredTargets.size) {
                    displayLimit += LOCAL_PAGE_SIZE
                    adapter.setData(filteredTargets.take(displayLimit))
                }
            }
        })
        listFrame.addView(recyclerView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))
        rootView.addView(listFrame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        chatArea = buildChatArea(context)
        chatArea.visibility = View.GONE
        rootView.addView(chatArea, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        adapter = SharingTargetAdapter(themeColors)
        recyclerView.adapter = adapter

        rebuildTargets()

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
            hint = getString(R.string.sharing_select_channel_placeholder)
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
                        displayLimit = LOCAL_PAGE_SIZE
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

        return outer
    }

    private fun buildChatArea(context: Context): LinearLayout {
        val area = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.surface)
        }

        thumbnailContainer = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(LayoutHelper.dp(12), LayoutHelper.dp(8), LayoutHelper.dp(12), 0)
        }
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

            val mime = sharedMimeType ?: getParentActivity()?.contentResolver?.getType(uri)
            if (mime != null && mime.startsWith("video/")) {
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
        thumbnailContainer.addView(thumbRow)
        if (sharedUris.isNotEmpty()) {
            area.addView(thumbnailContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
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
        try {
            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
            val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            if (bmp != null) imageView.setImageBitmap(bmp)
        } catch (_: Exception) {
        }
    }

    private fun selectTarget(t: SharingTarget) {
        selectedTarget = t

        searchEditText.visibility = View.GONE
        searchIcon.visibility = View.GONE
        selectedChipView.visibility = View.VISIBLE
        selectedChipClose.visibility = View.VISIBLE

        selectedChipAvatar.setInfo(t.channelId, t.channelLabel)
        selectedChipAvatar.setImageUrl(t.avatarUrl.ifEmpty { t.clanLogo }.ifEmpty { null })
        selectedChipLabel.text = t.channelLabel

        chatArea.visibility = View.VISIBLE
        sendButton.alpha = 1f
        sendButton.isEnabled = true

        captionInput.requestFocus()
    }

    private fun clearSelection() {
        selectedTarget = null
        searchEditText.visibility = View.VISIBLE
        searchEditText.setText("")
        searchIcon.visibility = View.VISIBLE
        selectedChipView.visibility = View.GONE
        selectedChipClose.visibility = View.GONE

        chatArea.visibility = View.GONE
        sendButton.alpha = 0.5f
        sendButton.isEnabled = false

        searchEditText.requestFocus()
    }

    private fun showFilterPopup(anchor: View) {
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

    private fun applyFilter(query: String) {
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
                    t.clanName.lowercase().contains(lower)
                ) {
                    filteredTargets.add(t)
                }
            }
        }

        adapter.setData(filteredTargets.take(displayLimit))
    }

    private fun setSendingState(sending: Boolean) {
        isSending = sending
        sendButton.isEnabled = !sending
        sendIcon.visibility = if (sending) View.GONE else View.VISIBLE
        sendProgress.visibility = if (sending) View.VISIBLE else View.GONE
        captionInput.isEnabled = !sending
    }

    private fun showErrorToast() {
        val parent = fragmentView as? ViewGroup ?: return
        val toast = ToastOverlay(parent.context, themeColors)
        toast.show(parent, ToastOverlay.ToastType.ERROR, getString(R.string.sharing_failed))
    }

    private fun onSend() {
        val target = selectedTarget ?: return
        if (isSending) return
        AndroidUtilities.hideKeyboard(captionInput)

        val contentResolver = getParentActivity()?.contentResolver ?: run {
            finishFragment()
            return
        }

        val caption = captionInput.text?.toString()?.trim() ?: sharedText ?: ""
        val mdResult = parseMarkdownAndStrip(caption)
        val cleanedText = mdResult.cleanedText
        val mdMarkers = mdResult.markers.ifEmpty { null }

        if (sharedUris.isNotEmpty()) {
            setSendingState(true)
            val attachments = buildAttachments(contentResolver)
            chatController.shareMediaToChannel(
                channelId = target.channelId,
                clanId = target.clanId,
                channelType = target.channelType,
                isChannelPrivate = target.isPrivate,
                text = cleanedText,
                attachments = attachments,
                contentResolver = contentResolver,
                markdownMarkers = mdMarkers
            )
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

    private fun buildAttachments(contentResolver: ContentResolver): List<AttachmentPickerItem> {
        return sharedUris.mapIndexed { index, uri ->
            val mimeType = sharedMimeType ?: contentResolver.getType(uri) ?: "application/octet-stream"
            val filename = resolveFilename(contentResolver, uri, index, mimeType)
            val size = resolveFileSize(contentResolver, uri)
            var width = 0
            var height = 0
            if (mimeType.startsWith("image/")) {
                try {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                    width = opts.outWidth
                    height = opts.outHeight
                } catch (_: Exception) {}
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
                duration = 0,
                isVideo = mimeType.startsWith("video/")
            )
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
        private const val DEBOUNCE_MS = 300L
    }
}
