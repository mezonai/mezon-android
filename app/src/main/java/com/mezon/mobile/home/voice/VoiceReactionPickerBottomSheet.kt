package com.mezon.mobile.home.voice

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.mezon.mobile.BuildConfig
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BaseCell
import com.mezon.mobile.core.BottomSheet
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.RecyclerListView
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.chat.EmojiController
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.home.chat.StickerItem
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.util.resolveStickerSourceUrl

private val CATEGORY_CELL_SIZE = LayoutHelper.dp(32f)
private val CATEGORY_ICON_SIZE = LayoutHelper.dp(24f)
private val HEADER_HEIGHT = LayoutHelper.dp(32f)
private val SOUND_ITEM_HEIGHT = LayoutHelper.dp(50f)
private val SOUND_ITEM_CORNER = LayoutHelper.dp(10f).toFloat()
private val SOUND_ITEM_PADDING = LayoutHelper.dp(8f)
private val SOUND_ITEM_PLAY_SIZE = LayoutHelper.dp(32f)
private val SOUND_ITEM_PLAY_ICON_SIZE = LayoutHelper.dp(16f)
private val SOUND_ITEM_SEND_ICON_SIZE = LayoutHelper.dp(20f)
private val SOUND_ITEM_GAP = LayoutHelper.dp(8f)
private val SOUND_GRID_ITEM_MARGIN = LayoutHelper.dp(4f)

class VoiceReactionPickerBottomSheet(
    context: Context,
    private val themeColors: ThemeColors,
    private val emojiController: EmojiController,
    private val notificationCenter: NotificationCenter,
    private val onSelect: (String) -> Unit
) : BottomSheet(context, needFocusable = true) {

    private data class SoundCategory(
        val clanId: String,
        val name: String,
        val logoUrl: String
    )

    private sealed class SoundListItem {
        data class Header(val title: String, val expanded: Boolean) : SoundListItem()
        data class Sound(val sticker: StickerItem) : SoundListItem()
        data class Empty(val text: String) : SoundListItem()
    }

    private val categories = ArrayList<SoundCategory>()
    private val allSounds = ArrayList<StickerItem>()
    private val listItems = ArrayList<SoundListItem>()
    private val collapsedSections = HashSet<String>()

    private var selectedCategoryName: String? = null
    private var currentPlayer: MediaPlayer? = null
    private var currentPlayingSrc: String? = null

    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var soundAdapter: SoundAdapter

    private val stickerReloadObserver = object : NotificationCenter.NotificationCenterDelegate {
        override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
            if (id == NotificationCenter.stickersNeedReload) {
                reloadData()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        categoryAdapter = CategoryAdapter(themeColors) { category ->
            selectedCategoryName = if (selectedCategoryName == category.name) null else category.name
            rebuildListItems()
            categoryAdapter.setData(categories, selectedCategoryName)
            soundAdapter.setData(listItems, currentPlayingSrc)
        }

        soundAdapter = SoundAdapter(
            themeColors = themeColors,
            onPreview = { sticker ->
                val src = normalizeSoundSource(sticker)
                if (src.isBlank()) return@SoundAdapter
                if (src == currentPlayingSrc) {
                    stopPreview()
                } else {
                    startPreview(src)
                }
            },
            onSend = { sticker ->
                val src = normalizeSoundSource(sticker)
                if (src.isBlank()) return@SoundAdapter
                onSelect("sound:$src")
                dismiss()
            },
            onToggleHeader = { title, expanded ->
                if (expanded) {
                    collapsedSections.add(title)
                } else {
                    collapsedSections.remove(title)
                }
                rebuildListItems()
                soundAdapter.setData(listItems, currentPlayingSrc)
            }
        )

        setCustomView(buildContent(context))
        notificationCenter.addObserver(stickerReloadObserver, NotificationCenter.stickersNeedReload)
        reloadData()
        emojiController.loadStickers()
        super.onCreate(savedInstanceState)
    }

    private fun buildContent(context: Context): View {
        val panelHeight = (AndroidUtilities.displaySize.y * 0.7f).toInt().coerceAtLeast(LayoutHelper.dp(360f))

        val categoryList = RecyclerListView(context).apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = categoryAdapter
            setHasFixedSize(true)
            overScrollMode = RecyclerView.OVER_SCROLL_NEVER
            (itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
            setPadding(LayoutHelper.dp(10f), LayoutHelper.dp(10f), LayoutHelper.dp(10f), LayoutHelper.dp(8f))
            clipToPadding = false
        }

        val soundList = RecyclerListView(context).apply {
            layoutManager = GridLayoutManager(context, 2).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int {
                        return if (soundAdapter.isFullSpan(position)) 2 else 1
                    }
                }
            }
            adapter = soundAdapter
            setHasFixedSize(true)
            overScrollMode = RecyclerView.OVER_SCROLL_NEVER
            (itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
            setPadding(LayoutHelper.dp(10f), 0, LayoutHelper.dp(10f), LayoutHelper.dp(14f))
            clipToPadding = false
            addItemDecoration(SoundGridSpacingDecoration(soundAdapter))
        }

        val body = FrameLayout(context)
        val vertical = androidx.appcompat.widget.LinearLayoutCompat(context).apply {
            orientation = androidx.appcompat.widget.LinearLayoutCompat.VERTICAL
            addView(categoryList, androidx.appcompat.widget.LinearLayoutCompat.LayoutParams(LayoutHelper.MATCH_PARENT, CATEGORY_CELL_SIZE + LayoutHelper.dp(18f)))
            addView(soundList, androidx.appcompat.widget.LinearLayoutCompat.LayoutParams(LayoutHelper.MATCH_PARENT, 0, 1f))
        }

        body.addView(vertical, FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, panelHeight))
        return body
    }

    private fun reloadData() {
        val sounds = synchronized(emojiController) {
            emojiController.stickers.filter { it.isAudio && it.src.isNotBlank() && !it.isForSale }
        }
        allSounds.clear()
        allSounds.addAll(sounds)

        categories.clear()
        val seen = HashSet<String>()
        for (item in allSounds) {
            if (seen.add(item.nameKey())) {
                categories.add(
                    SoundCategory(
                        clanId = item.clanId,
                        name = item.nameKey(),
                        logoUrl = item.clanLogo
                    )
                )
            }
        }

        if (selectedCategoryName != null && categories.none { it.name == selectedCategoryName }) {
            selectedCategoryName = null
        }

        rebuildListItems()
        categoryAdapter.setData(categories, selectedCategoryName)
        soundAdapter.setData(listItems, currentPlayingSrc)
    }

    private fun rebuildListItems() {
        listItems.clear()
        if (allSounds.isEmpty()) {
            listItems.add(SoundListItem.Empty("No sound effects"))
            return
        }

        val sectionCategories = if (selectedCategoryName == null) {
            categories
        } else {
            categories.filter { it.name == selectedCategoryName }
        }

        for (category in sectionCategories) {
            val sounds = allSounds.filter { it.nameKey() == category.name }
            if (sounds.isEmpty()) continue
            val expanded = !collapsedSections.contains(category.name)
            listItems.add(SoundListItem.Header(category.name, expanded))
            if (expanded) {
                for (sound in sounds) {
                    listItems.add(SoundListItem.Sound(sound))
                }
            }
        }

        if (listItems.isEmpty()) {
            listItems.add(SoundListItem.Empty("No sound effects"))
        }
    }

    private fun startPreview(src: String) {
        stopPreview(updateUi = false)
        currentPlayingSrc = src
        soundAdapter.setPlayingSrc(src)
        try {
            currentPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setOnPreparedListener { start() }
                setOnCompletionListener { stopPreview() }
                setOnErrorListener { _, _, _ ->
                    stopPreview()
                    true
                }
                setDataSource(src)
                prepareAsync()
            }
        } catch (_: Exception) {
            stopPreview()
        }
    }

    private fun stopPreview(updateUi: Boolean = true) {
        currentPlayer?.setOnCompletionListener(null)
        currentPlayer?.setOnPreparedListener(null)
        currentPlayer?.setOnErrorListener(null)
        currentPlayer?.release()
        currentPlayer = null
        currentPlayingSrc = null
        if (updateUi) {
            soundAdapter.setPlayingSrc(null)
        }
    }

    override fun dismiss() {
        stopPreview()
        notificationCenter.removeObserver(stickerReloadObserver, NotificationCenter.stickersNeedReload)
        super.dismiss()
    }

    private fun normalizeSoundSource(sticker: StickerItem): String {
        val raw = resolveStickerSourceUrl(sticker.id, sticker.src).trim()
        if (raw.isBlank()) return ""
        return when {
            raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
            raw.startsWith("/") -> BuildConfig.MEZON_BASE_IMG_URL + raw
            else -> raw
        }
    }

    private fun StickerItem.nameKey(): String {
        return clanName.ifBlank { "Sounds" }
    }

    private class CategoryAdapter(
        private val themeColors: ThemeColors,
        private val onCategoryTap: (SoundCategory) -> Unit
    ) : RecyclerView.Adapter<CategoryAdapter.Holder>() {

        private val items = ArrayList<SoundCategory>()
        private var selectedName: String? = null

        init {
            setHasStableIds(true)
        }

        fun setData(newItems: List<SoundCategory>, selected: String?) {
            items.clear()
            items.addAll(newItems)
            selectedName = selected
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun getItemId(position: Int): Long = items[position].name.hashCode().toLong()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val cell = SoundCategoryCell(parent.context, themeColors)
            val holder = Holder(cell)
            cell.onTap = {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onCategoryTap(items[pos])
                }
            }
            return holder
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val category = items[position]
            holder.cell.bind(category, category.name == selectedName)
        }

        class Holder(val cell: SoundCategoryCell) : RecyclerView.ViewHolder(cell)
    }

    private class SoundAdapter(
        private val themeColors: ThemeColors,
        private val onPreview: (StickerItem) -> Unit,
        private val onSend: (StickerItem) -> Unit,
        private val onToggleHeader: (title: String, expanded: Boolean) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        companion object {
            private const val TYPE_HEADER = 0
            private const val TYPE_SOUND = 1
            private const val TYPE_EMPTY = 2
        }

        private val items = ArrayList<SoundListItem>()
        private var playingSrc: String? = null

        init {
            setHasStableIds(true)
        }

        fun setData(newItems: List<SoundListItem>, playingSrc: String?) {
            items.clear()
            items.addAll(newItems)
            this.playingSrc = playingSrc
            notifyDataSetChanged()
        }

        fun setPlayingSrc(src: String?) {
            if (playingSrc == src) return
            playingSrc = src
            notifyDataSetChanged()
        }

        fun isFullSpan(position: Int): Boolean {
            return when (items.getOrNull(position)) {
                is SoundListItem.Header, is SoundListItem.Empty -> true
                else -> false
            }
        }

        override fun getItemCount(): Int = items.size

        override fun getItemId(position: Int): Long {
            return when (val item = items[position]) {
                is SoundListItem.Header -> item.title.hashCode().toLong() or (1L shl 62)
                is SoundListItem.Sound -> item.sticker.id.hashCode().toLong()
                is SoundListItem.Empty -> item.text.hashCode().toLong() or (1L shl 61)
            }
        }

        override fun getItemViewType(position: Int): Int {
            return when (items[position]) {
                is SoundListItem.Header -> TYPE_HEADER
                is SoundListItem.Sound -> TYPE_SOUND
                is SoundListItem.Empty -> TYPE_EMPTY
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return when (viewType) {
                TYPE_HEADER -> {
                    val cell = SoundHeaderCell(parent.context, themeColors)
                    val holder = HeaderHolder(cell)
                    cell.onTap = {
                        val pos = holder.bindingAdapterPosition
                        val item = items.getOrNull(pos)
                        if (item is SoundListItem.Header) {
                            onToggleHeader(item.title, item.expanded)
                        }
                    }
                    holder
                }
                TYPE_EMPTY -> EmptyHolder(SoundEmptyCell(parent.context, themeColors))
                else -> {
                    val cell = SoundItemCell(parent.context, themeColors)
                    val holder = SoundHolder(cell)
                    cell.onPreviewTap = {
                        val pos = holder.bindingAdapterPosition
                        val item = items.getOrNull(pos)
                        if (item is SoundListItem.Sound) onPreview(item.sticker)
                    }
                    cell.onSendTap = {
                        val pos = holder.bindingAdapterPosition
                        val item = items.getOrNull(pos)
                        if (item is SoundListItem.Sound) onSend(item.sticker)
                    }
                    holder
                }
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is SoundListItem.Header -> (holder as HeaderHolder).cell.bind(item.title, item.expanded)
                is SoundListItem.Empty -> (holder as EmptyHolder).cell.bind(item.text)
                is SoundListItem.Sound -> (holder as SoundHolder).cell.bind(item.sticker, item.sticker.src == playingSrc)
            }
        }

        class HeaderHolder(val cell: SoundHeaderCell) : RecyclerView.ViewHolder(cell)
        class SoundHolder(val cell: SoundItemCell) : RecyclerView.ViewHolder(cell)
        class EmptyHolder(val cell: SoundEmptyCell) : RecyclerView.ViewHolder(cell)
    }

    private class SoundGridSpacingDecoration(
        private val adapter: SoundAdapter
    ) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            val position = parent.getChildAdapterPosition(view)
            if (position == RecyclerView.NO_POSITION) return
            if (adapter.isFullSpan(position)) {
                outRect.set(0, LayoutHelper.dp(6f), 0, LayoutHelper.dp(2f))
                return
            }
            outRect.left = SOUND_GRID_ITEM_MARGIN
            outRect.right = SOUND_GRID_ITEM_MARGIN
            outRect.top = SOUND_GRID_ITEM_MARGIN
            outRect.bottom = SOUND_GRID_ITEM_MARGIN
        }
    }

    private class SoundCategoryCell(context: Context, private val themeColors: ThemeColors) : BaseCell(context) {

        var onTap: (() -> Unit)? = null

        private var category: SoundCategory? = null
        private var selected = false
        private var logoUrl: String? = null
        private var logoBitmap: Bitmap? = null
        private var logoLoad: MezonImageLoader.Cancellable? = null
        private val loader = MezonImageLoader.getInstance(context)
        private val srcRect = Rect()
        private val dstRect = Rect()
        private val tmpRectF = RectF()
        private val logoClipPath = Path()

        init {
            isClickable = true
            isFocusable = true
            setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> alpha = 0.6f
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> alpha = 1f
                }
                false
            }
            setOnClickListener { onTap?.invoke() }
        }

        fun bind(category: SoundCategory, selected: Boolean) {
            val logoChanged = logoUrl != category.logoUrl
            this.category = category
            this.selected = selected
            if (logoChanged) {
                logoUrl = category.logoUrl
                loadLogo(category.logoUrl)
            }
            invalidate()
        }

        private fun loadLogo(url: String?) {
            logoLoad?.cancel()
            logoLoad = null
            logoBitmap = null
            if (url.isNullOrBlank()) return
            val cached = loader.getBitmapFromMemory(url, CATEGORY_ICON_SIZE, CATEGORY_ICON_SIZE)
            if (cached != null) {
                logoBitmap = cached
                return
            }
            logoLoad = loader.load(
                url,
                CATEGORY_ICON_SIZE,
                CATEGORY_ICON_SIZE,
                onSuccess = { bmp ->
                    logoBitmap = bmp
                    logoLoad = null
                    invalidate()
                },
                onError = {
                    logoLoad = null
                }
            )
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(CATEGORY_CELL_SIZE, CATEGORY_CELL_SIZE)
        }

        override fun onDraw(canvas: Canvas) {
            val item = category ?: return
            val w = measuredWidth.toFloat()
            val h = measuredHeight.toFloat()
            val cx = w / 2f
            val cy = h / 2f
            if (selected) {
                bgPaint.color = themeColors.blurple
                tmpRectF.set(0f, 0f, w, h)
                canvas.drawRoundRect(tmpRectF, LayoutHelper.dp(10f).toFloat(), LayoutHelper.dp(10f).toFloat(), bgPaint)
            }
            circlePaint.color = themeColors.secondaryLight
            canvas.drawCircle(cx, cy, CATEGORY_ICON_SIZE / 2f, circlePaint)

            val bmp = logoBitmap
            if (bmp != null) {
                srcRect.set(0, 0, bmp.width, bmp.height)
                val left = ((w - CATEGORY_ICON_SIZE) / 2f).toInt()
                val top = ((h - CATEGORY_ICON_SIZE) / 2f).toInt()
                dstRect.set(left, top, left + CATEGORY_ICON_SIZE, top + CATEGORY_ICON_SIZE)
                logoClipPath.reset()
                logoClipPath.addCircle(cx, cy, CATEGORY_ICON_SIZE / 2f, Path.Direction.CW)
                canvas.save()
                canvas.clipPath(logoClipPath)
                canvas.drawBitmap(bmp, srcRect, dstRect, bitmapPaint)
                canvas.restore()
            } else {
                val text = item.name.firstOrNull()?.uppercase() ?: "S"
                textPaint.color = themeColors.onSurface
                val baseline = cy - (textPaint.ascent() + textPaint.descent()) / 2f
                canvas.drawText(text, cx, baseline, textPaint)
            }
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            logoLoad?.cancel()
            logoLoad = null
        }

        override fun allowCaching(): Boolean = false

        companion object {
            private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
            private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = LayoutHelper.dp(12f).toFloat()
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }
        }
    }

    private class SoundHeaderCell(context: Context, private val themeColors: ThemeColors) : BaseCell(context) {

        private var title: String = ""
        private var expanded = true
        var onTap: (() -> Unit)? = null
        private val chevronDown = MezonIcon.chevronDownSmallIcon.getDrawable(context).mutate().apply {
            colorFilter = PorterDuffColorFilter(themeColors.onSurfaceVariant, PorterDuff.Mode.SRC_IN)
        }
        private val chevronRight = MezonIcon.chevronSmallRightIcon.getDrawable(context).mutate().apply {
            colorFilter = PorterDuffColorFilter(themeColors.onSurfaceVariant, PorterDuff.Mode.SRC_IN)
        }

        init {
            isClickable = true
            isFocusable = true
            setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> alpha = 0.7f
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> alpha = 1f
                }
                false
            }
            setOnClickListener { onTap?.invoke() }
        }

        fun bind(value: String, expanded: Boolean) {
            title = value.uppercase()
            this.expanded = expanded
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), HEADER_HEIGHT)
        }

        override fun onDraw(canvas: Canvas) {
            textPaint.color = themeColors.onSurface
            val baseline = measuredHeight / 2f - (textPaint.ascent() + textPaint.descent()) / 2f
            canvas.drawText(title, LayoutHelper.dp(2f).toFloat(), baseline, textPaint)
            val icon = if (expanded) chevronDown else chevronRight
            val size = LayoutHelper.dp(16f)
            val left = measuredWidth - LayoutHelper.dp(18f) - size
            val top = (measuredHeight - size) / 2
            icon.setBounds(left, top, left + size, top + size)
            icon.draw(canvas)
        }

        override fun allowCaching(): Boolean = false

        companion object {
            private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = LayoutHelper.dp(14f).toFloat()
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.LEFT
            }
        }
    }

    private class SoundEmptyCell(context: Context, private val themeColors: ThemeColors) : BaseCell(context) {

        private var text: String = ""

        fun bind(value: String) {
            text = value
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), LayoutHelper.dp(100f))
        }

        override fun onDraw(canvas: Canvas) {
            textPaint.color = themeColors.onSurfaceVariant
            val cx = measuredWidth / 2f
            val baseline = measuredHeight / 2f - (textPaint.ascent() + textPaint.descent()) / 2f
            canvas.drawText(text, cx, baseline, textPaint)
        }

        override fun allowCaching(): Boolean = false

        companion object {
            private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = LayoutHelper.dp(14f).toFloat()
                textAlign = Paint.Align.CENTER
            }
        }
    }

    private class SoundItemCell(context: Context, private val themeColors: ThemeColors) : BaseCell(context) {

        var onPreviewTap: (() -> Unit)? = null
        var onSendTap: (() -> Unit)? = null

        private var sticker: StickerItem? = null
        private var isPlaying = false
        private var isPressed = false
        private val bgRect = RectF()
        private val playRect = RectF()
        private val sendRect = RectF()

        private val playDrawable: Drawable = MezonIcon.playIcon.getDrawable(context).mutate().apply {
            colorFilter = PorterDuffColorFilter(themeColors.blurple, PorterDuff.Mode.SRC_IN)
        }
        private val pauseDrawable: Drawable = MezonIcon.pauseIcon.getDrawable(context).mutate().apply {
            colorFilter = PorterDuffColorFilter(themeColors.blurple, PorterDuff.Mode.SRC_IN)
        }
        private val sendDrawable: Drawable = MezonIcon.sendMessageIcon.getDrawable(context).mutate().apply {
            colorFilter = PorterDuffColorFilter(themeColors.onSurface, PorterDuff.Mode.SRC_IN)
        }

        fun bind(sticker: StickerItem, isPlaying: Boolean) {
            this.sticker = sticker
            this.isPlaying = isPlaying
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), SOUND_ITEM_HEIGHT)
        }

        override fun onDraw(canvas: Canvas) {
            val item = sticker ?: return
            val w = measuredWidth.toFloat()
            val h = measuredHeight.toFloat()
            bgRect.set(0f, 0f, w, h)
            bgPaint.color = themeColors.secondaryLight
            bgPaint.alpha = if (isPressed) 210 else 255
            canvas.drawRoundRect(bgRect, SOUND_ITEM_CORNER, SOUND_ITEM_CORNER, bgPaint)

            val playLeft = SOUND_ITEM_PADDING.toFloat()
            val playTop = (h - SOUND_ITEM_PLAY_SIZE) / 2f
            playRect.set(playLeft, playTop, playLeft + SOUND_ITEM_PLAY_SIZE, playTop + SOUND_ITEM_PLAY_SIZE)
            playCirclePaint.color = 0xFFFFFFFF.toInt()
            canvas.drawOval(playRect, playCirclePaint)

            val actionDrawable = if (isPlaying) pauseDrawable else playDrawable
            val iconLeft = (playRect.left + (SOUND_ITEM_PLAY_SIZE - SOUND_ITEM_PLAY_ICON_SIZE) / 2f).toInt()
            val iconTop = (playRect.top + (SOUND_ITEM_PLAY_SIZE - SOUND_ITEM_PLAY_ICON_SIZE) / 2f).toInt()
            actionDrawable.setBounds(iconLeft, iconTop, iconLeft + SOUND_ITEM_PLAY_ICON_SIZE, iconTop + SOUND_ITEM_PLAY_ICON_SIZE)
            actionDrawable.draw(canvas)

            val sendLeft = (w - SOUND_ITEM_PADDING - SOUND_ITEM_SEND_ICON_SIZE).toInt()
            val sendTop = ((h - SOUND_ITEM_SEND_ICON_SIZE) / 2f).toInt()
            sendRect.set(sendLeft.toFloat(), sendTop.toFloat(), (sendLeft + SOUND_ITEM_SEND_ICON_SIZE).toFloat(), (sendTop + SOUND_ITEM_SEND_ICON_SIZE).toFloat())
            sendDrawable.setBounds(sendLeft, sendTop, sendLeft + SOUND_ITEM_SEND_ICON_SIZE, sendTop + SOUND_ITEM_SEND_ICON_SIZE)
            sendDrawable.draw(canvas)

            textPaint.color = themeColors.onSurface
            val textStart = playRect.right + SOUND_ITEM_GAP
            val textEnd = sendRect.left - SOUND_ITEM_GAP
            val available = (textEnd - textStart).coerceAtLeast(0f)
            val text = TextUtils.ellipsize(item.shortname, textPaint, available, TextUtils.TruncateAt.END).toString()
            val textBaseline = h / 2f - (textPaint.ascent() + textPaint.descent()) / 2f
            canvas.drawText(text, textStart + available / 2f, textBaseline, textPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (sticker == null) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isPressed = true
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val wasPressed = isPressed
                    isPressed = false
                    invalidate()
                    if (wasPressed) {
                        if (playRect.contains(event.x, event.y)) {
                            onPreviewTap?.invoke()
                        } else {
                            onSendTap?.invoke()
                        }
                        performClick()
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    isPressed = false
                    invalidate()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        override fun allowCaching(): Boolean = false

        companion object {
            private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            private val playCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG)
            private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = LayoutHelper.dp(14f).toFloat()
                textAlign = Paint.Align.CENTER
            }
        }
    }
}
