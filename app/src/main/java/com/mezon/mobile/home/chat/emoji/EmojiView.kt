package com.mezon.mobile.home.chat.emoji

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.RecyclerListView
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.home.chat.EmojiController
import com.mezon.mobile.home.chat.EmojiItem
import com.mezon.mobile.home.chat.StickerItem
import com.mezon.mobile.network.KlipyCategory
import com.mezon.mobile.network.KlipyGif
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.R

private const val TAB_EMOJI = 0
private const val TAB_GIF = 1
private const val TAB_STICKER = 2
private const val SEARCH_DEBOUNCE_MS = 300L
private const val EMOJI_COLUMNS = 9
private const val STICKER_COLUMNS = 5

class EmojiView(
    context: Context,
    private val themeColors: ThemeColors,
    private val emojiOnly: Boolean = false
) : FrameLayout(context) {

    interface EmojiViewDelegate {
        fun onEmojiSelected(emoji: EmojiItem)
        fun onStickerSelected(sticker: StickerItem, isAudio: Boolean)
        fun onGifSelected(gifUrl: String, width: Int = 0, height: Int = 0)
        fun onBackspace()
        fun onSearchFocusChanged(focused: Boolean) {}
        fun onDismissRequested() {}
        fun onExpandRequested() {}
        fun onDragY(dy: Float, dragFromExpanded: Boolean = false) {}
        fun onAnimateExpand(expand: Boolean) {}
        fun isExpanded(): Boolean = false
    }

    var delegate: EmojiViewDelegate? = null
    private var currentTab = TAB_EMOJI
    private var ignoreFocusChange = false
    private var ignoreTextChange = false
    private var emojiController: EmojiController? = null

    private val handler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null

    private val tabBar: LinearLayout
    private val tabEmoji: TextView
    private val tabGif: TextView
    private val tabSticker: TextView
    private val searchField: EditText
    private val contentContainer: FrameLayout
    private val backspaceButton: ImageView

    private var dragStartY = 0f
    private var dragging = false
    private var dragTranslation = 0f
    private var dragStartedExpanded = false
    private var velocityTracker: VelocityTracker? = null
    private var emojiGrid: RecyclerListView? = null
    private var stickerGrid: RecyclerListView? = null
    private var gifGrid: RecyclerListView? = null
    private var gifCategoryGrid: RecyclerListView? = null
    private var emojiAdapter: EmojiGridAdapter? = null
    private var stickerAdapter: StickerGridAdapter? = null
    private var gifCategoryAdapter: GifCategoryAdapter? = null
    private var gifSearchActive = false
    private var gifAdapter: GifGridAdapter? = null
    private var gifProgressBar: android.widget.ProgressBar? = null
    private var gifEmptyView: android.widget.LinearLayout? = null
    
    private lateinit var searchBar: FrameLayout
    private lateinit var categoryHeaderContainer: LinearLayout
    private lateinit var categoryTitleText: TextView
    private var currentGifCategory: KlipyCategory? = null

    private val gifScrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
            val stop = newState == RecyclerView.SCROLL_STATE_DRAGGING || newState == RecyclerView.SCROLL_STATE_SETTLING
            for (i in 0 until rv.childCount) {
                val child = rv.getChildAt(i)
                if (child is GifCell) {
                    if (stop) child.stopHeavyOperations() else child.startHeavyOperations()
                } else if (child is GifCategoryCell) {
                    if (stop) child.stopHeavyOperations() else child.startHeavyOperations()
                }
            }
        }
    }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val dismissVelocity = LayoutHelper.dp(800f).toFloat()

    init {
        setBackgroundColor(Color.TRANSPARENT)
        clipChildren = false
        clipToPadding = false

        val sheetRadius = LayoutHelper.dp(16f).toFloat()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(themeColors.surface)
                cornerRadii = floatArrayOf(sheetRadius, sheetRadius, sheetRadius, sheetRadius, 0f, 0f, 0f, 0f)
            }
        }

        val dragHandle = DragHandleView(context, themeColors)
        root.addView(dragHandle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, DRAG_HANDLE_HEIGHT
        ))

        tabBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(themeColors.surface)
            setPadding(0, LayoutHelper.dp(4f), 0, LayoutHelper.dp(4f))
        }

        tabEmoji = createTabButton(context.getString(R.string.emoji_tab_emoji))
        tabGif = createTabButton(context.getString(R.string.emoji_tab_gif))
        tabSticker = createTabButton(context.getString(R.string.emoji_tab_sticker))

        tabEmoji.setOnClickListener { switchTab(TAB_EMOJI) }
        tabGif.setOnClickListener { switchTab(TAB_GIF) }
        tabSticker.setOnClickListener { switchTab(TAB_STICKER) }

        tabBar.addView(tabEmoji, LayoutHelper.createLinear(0, 36, 1f, leftMargin = 4f, rightMargin = 2f))
        tabBar.addView(tabGif, LayoutHelper.createLinear(0, 36, 1f, leftMargin = 2f, rightMargin = 2f))
        tabBar.addView(tabSticker, LayoutHelper.createLinear(0, 36, 1f, leftMargin = 2f, rightMargin = 4f))
        if (emojiOnly) {
            tabBar.visibility = View.GONE
            dragHandle.visibility = View.GONE
        }
        root.addView(tabBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        searchBar = FrameLayout(context).apply {
            setPadding(LayoutHelper.dp(10f), LayoutHelper.dp(10f), LayoutHelper.dp(10f), LayoutHelper.dp(10f))
        }

        searchField = EditText(context).apply {
            hint = context.getString(R.string.common_search)
            setHintTextColor(themeColors.textDisabled)
            setTextColor(themeColors.onSurface)
            textSize = 14f
            maxLines = 1
            isSingleLine = true
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(themeColors.tertiary)
                cornerRadius = LayoutHelper.dp(10f).toFloat()
            }
            setPadding(LayoutHelper.dp(38f), LayoutHelper.dp(8f), LayoutHelper.dp(10f), LayoutHelper.dp(8f))
        }

        val searchIcon = ImageView(context).apply {
            val d = MezonIcon.magnifyingIcon.getDrawable(context).mutate()
            d.colorFilter = PorterDuffColorFilter(themeColors.onSurface, PorterDuff.Mode.SRC_IN)
            setImageDrawable(d)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        searchBar.addView(searchField, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, LayoutHelper.dp(40f)
        ))
        searchBar.addView(searchIcon, FrameLayout.LayoutParams(
            LayoutHelper.dp(18f), LayoutHelper.dp(18f), Gravity.START or Gravity.CENTER_VERTICAL
        ).apply {
            leftMargin = LayoutHelper.dp(10f)
        })
        root.addView(searchBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        categoryHeaderContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(LayoutHelper.dp(16f), LayoutHelper.dp(12f), LayoutHelper.dp(16f), LayoutHelper.dp(12f))
            
            val backBtn = ImageView(context).apply {
                val d = MezonIcon.backArrowLarge.getDrawable(context).mutate()
                d.colorFilter = PorterDuffColorFilter(themeColors.onSurface, PorterDuff.Mode.SRC_IN)
                setImageDrawable(d)
                setOnClickListener {
                    currentGifCategory = null
                    gifSearchActive = false
                    updateGifGridVisibility()
                    searchField.setText("")
                    searchRunnable?.let { handler.removeCallbacks(it) }
                    searchRunnable = null
                }
            }
            addView(backBtn, LayoutHelper.createLinear(24, 24))
            
            categoryTitleText = TextView(context).apply {
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(themeColors.onSurface)
            }
            addView(categoryTitleText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL, 12f, 0f, 0f, 0f))
        }
        root.addView(categoryHeaderContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        searchField.setOnFocusChangeListener { _, hasFocus ->
            if (!ignoreFocusChange) {
                delegate?.onSearchFocusChanged(hasFocus)
            }
        }

        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (ignoreTextChange) return
                val query = s?.toString()?.trim() ?: ""
                searchRunnable?.let { handler.removeCallbacks(it) }
                searchRunnable = Runnable { performSearch(query) }
                handler.postDelayed(searchRunnable!!, SEARCH_DEBOUNCE_MS)
            }
        })

        contentContainer = FrameLayout(context)
        root.addView(contentContainer, LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT, 0, 1f
        ))

        val bottomBar = FrameLayout(context).apply {
            setBackgroundColor(themeColors.surface)
        }
        backspaceButton = ImageView(context).apply {
            val d = MezonIcon.backspaceIcon.getDrawable(context)
            d.colorFilter = PorterDuffColorFilter(themeColors.onSurfaceVariant, PorterDuff.Mode.SRC_IN)
            setImageDrawable(d)
            scaleType = ImageView.ScaleType.CENTER
            setOnClickListener { delegate?.onBackspace() }
        }
        bottomBar.addView(backspaceButton, FrameLayout.LayoutParams(
            LayoutHelper.dp(40f), LayoutHelper.dp(40f), Gravity.END or Gravity.CENTER_VERTICAL
        ).apply { rightMargin = LayoutHelper.dp(8f) })
        if (emojiOnly) bottomBar.visibility = View.GONE
        root.addView(bottomBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44))

        addView(root, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        updateTabSelection()
    }

    fun init(controller: EmojiController) {
        emojiController = controller
        
        controller.notificationCenter.listen(this, NotificationCenter.emojisNeedReload) { _, _ ->
            onEmojisReloaded()
        }
        controller.notificationCenter.listen(this, NotificationCenter.stickersNeedReload) { _, _ ->
            onStickersReloaded()
        }
        controller.notificationCenter.listen(this, NotificationCenter.gifsNeedReload) { _, _ ->
            onGifsReloaded()
        }
    }

    fun onOpen(forceTab: Int = -1) {
        ignoreTextChange = true
        searchField.text?.clear()
        ignoreTextChange = false
        gifSearchActive = false
        currentGifCategory = null

        if (forceTab >= 0 && forceTab != currentTab) {
            switchTab(forceTab)
        } else {
            ensureCurrentTabVisible()
        }
        emojiController?.loadEmojis()
        emojiController?.loadStickers()
    }

    private fun ensureCurrentTabVisible() {
        ensureGrid(currentTab)
        updateGifGridVisibility()
        emojiGrid?.visibility = if (currentTab == TAB_EMOJI) View.VISIBLE else View.GONE
        stickerGrid?.visibility = if (currentTab == TAB_STICKER) View.VISIBLE else View.GONE
        when (currentTab) {
            TAB_EMOJI -> loadEmojiData()
            TAB_STICKER -> loadStickerData()
            TAB_GIF -> loadGifData()
        }
        backspaceButton.visibility = if (currentTab == TAB_EMOJI) View.VISIBLE else View.GONE
    }

    private fun switchTab(tab: Int) {
        if (currentTab == tab) return
        currentTab = tab
        updateTabSelection()
        ignoreTextChange = true
        searchField.text?.clear()
        ignoreTextChange = false
        gifSearchActive = false
        currentGifCategory = null

        ensureGrid(tab)
        updateGifGridVisibility()
        emojiGrid?.visibility = if (tab == TAB_EMOJI) View.VISIBLE else View.GONE
        stickerGrid?.visibility = if (tab == TAB_STICKER) View.VISIBLE else View.GONE

        when (tab) {
            TAB_EMOJI -> loadEmojiData()
            TAB_STICKER -> loadStickerData()
            TAB_GIF -> loadGifData()
        }
        backspaceButton.visibility = if (tab == TAB_EMOJI) View.VISIBLE else View.GONE
    }

    private fun updateGifGridVisibility() {
        val ctrl = emojiController ?: return
        if (currentTab == TAB_GIF) {
            gifCategoryGrid?.visibility = if (gifSearchActive) View.GONE else View.VISIBLE
            gifGrid?.visibility = if (gifSearchActive) View.VISIBLE else View.GONE

            if (gifSearchActive && currentGifCategory != null) {
                if (currentGifCategory!!.isTrending) {
                    ignoreFocusChange = true
                    if (searchField.hasFocus()) {
                        searchField.clearFocus()
                        AndroidUtilities.hideKeyboard(searchField)
                    }
                    searchBar.visibility = View.GONE
                    ignoreFocusChange = false
                    categoryHeaderContainer.visibility = View.VISIBLE
                    categoryTitleText.text = context.getString(R.string.emoji_trending_gifs)
                } else {
                    searchBar.visibility = View.VISIBLE
                    categoryHeaderContainer.visibility = View.GONE
                    searchField.hint = context.getString(R.string.emoji_search_gif_placeholder)
                }
            } else {
                searchBar.visibility = View.VISIBLE
                categoryHeaderContainer.visibility = View.GONE
                searchField.hint = when (currentTab) {
                    TAB_EMOJI -> context.getString(R.string.emoji_search_emoji_placeholder)
                    TAB_GIF -> context.getString(R.string.emoji_search_gif_placeholder)
                    TAB_STICKER -> context.getString(R.string.emoji_search_sticker_placeholder)
                    else -> context.getString(R.string.common_search)
                }
            }
            
            val results = if (gifSearchActive) synchronized(ctrl) { ctrl.searchGifResults.toList() } else emptyList<KlipyGif>()
            if (gifSearchActive && ctrl.isSearchingGifs) {
                gifProgressBar?.visibility = View.VISIBLE
                gifEmptyView?.visibility = View.GONE
            } else {
                gifProgressBar?.visibility = View.GONE
                gifEmptyView?.visibility = if (gifSearchActive && results.isEmpty()) View.VISIBLE else View.GONE
            }
        } else {
            searchBar.visibility = View.VISIBLE
            categoryHeaderContainer.visibility = View.GONE
            gifCategoryGrid?.visibility = View.GONE
            gifGrid?.visibility = View.GONE
            gifProgressBar?.visibility = View.GONE
            gifEmptyView?.visibility = View.GONE
        }
    }

    private fun onGifCategoryTapped(category: KlipyCategory) {
        gifSearchActive = true
        currentGifCategory = category
        updateGifGridVisibility()
        
        ignoreTextChange = true
        if (category.isTrending) {
            searchField.setText("")
        } else {
            searchField.setText(category.name)
            searchField.setSelection(category.name.length)
        }
        ignoreTextChange = false
        
        searchRunnable?.let { handler.removeCallbacks(it) }
        searchRunnable = null
        emojiController?.searchGifs(category.query, isTrending = category.isTrending)
    }

    private fun ensureGrid(tab: Int) {
        val matchLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        )
        when (tab) {
            TAB_EMOJI -> if (emojiGrid == null) {
                emojiAdapter = EmojiGridAdapter(themeColors) { emoji ->
                    if (emoji.isForSale && emoji.src.isBlank()) return@EmojiGridAdapter
                    delegate?.onEmojiSelected(emoji)
                }
                val lm = GridLayoutManager(context, EMOJI_COLUMNS)
                lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int {
                        return if (emojiAdapter?.isHeader(position) == true) EMOJI_COLUMNS else 1
                    }
                }
                emojiGrid = RecyclerListView(context).apply {
                    layoutManager = lm
                    adapter = emojiAdapter
                    setHasFixedSize(true)
                    (itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
                    overScrollMode = OVER_SCROLL_NEVER
                    setPadding(LayoutHelper.dp(11f), 0, LayoutHelper.dp(11f), 0)
                    clipToPadding = false
                }
                contentContainer.addView(emojiGrid, matchLp)
            }
            TAB_STICKER -> if (stickerGrid == null) {
                stickerAdapter = StickerGridAdapter(themeColors) { sticker ->
                    if (sticker.isForSale && sticker.src.isBlank()) return@StickerGridAdapter
                    delegate?.onStickerSelected(sticker, sticker.isAudio)
                }
                val lm = GridLayoutManager(context, STICKER_COLUMNS)
                lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int {
                        return if (stickerAdapter?.isHeader(position) == true) STICKER_COLUMNS else 1
                    }
                }
                stickerGrid = RecyclerListView(context).apply {
                    layoutManager = lm
                    adapter = stickerAdapter
                    setHasFixedSize(true)
                    (itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
                    overScrollMode = OVER_SCROLL_NEVER
                }
                contentContainer.addView(stickerGrid, matchLp)
            }
            TAB_GIF -> {
                val gifSpacing = LayoutHelper.dp(5f)
                if (gifCategoryGrid == null) {
                    gifCategoryAdapter = GifCategoryAdapter(themeColors) { category ->
                        onGifCategoryTapped(category)
                    }
                    gifCategoryGrid = RecyclerListView(context).apply {
                        layoutManager = GridLayoutManager(context, 2)
                        adapter = gifCategoryAdapter
                        setHasFixedSize(true)
                        (itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
                        overScrollMode = OVER_SCROLL_NEVER
                        addItemDecoration(GifSpacingDecoration(gifSpacing))
                        setPadding(gifSpacing, gifSpacing, gifSpacing, gifSpacing)
                        clipToPadding = false
                        addOnScrollListener(gifScrollListener)
                    }
                    contentContainer.addView(gifCategoryGrid, matchLp)
                }
                if (gifGrid == null) {
                    gifAdapter = GifGridAdapter(themeColors) { gif ->
                        delegate?.onGifSelected(gif.gifUrl, gif.width, gif.height)
                    }
                    gifGrid = RecyclerListView(context).apply {
                        layoutManager = androidx.recyclerview.widget.StaggeredGridLayoutManager(2, androidx.recyclerview.widget.StaggeredGridLayoutManager.VERTICAL)
                        adapter = gifAdapter
                        setHasFixedSize(true)
                        (itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
                        overScrollMode = OVER_SCROLL_NEVER
                        addItemDecoration(GifSpacingDecoration(gifSpacing))
                        setPadding(gifSpacing, gifSpacing, gifSpacing, gifSpacing)
                        clipToPadding = false
                        visibility = View.GONE
                        addOnScrollListener(gifScrollListener)
                    }
                    contentContainer.addView(gifGrid, matchLp)
                }
                if (gifProgressBar == null) {
                    gifProgressBar = android.widget.ProgressBar(context).apply {
                        visibility = View.GONE
                    }
                    contentContainer.addView(gifProgressBar, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER
                    ))
                }
                if (gifEmptyView == null) {
                    gifEmptyView = android.widget.LinearLayout(context).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        visibility = View.GONE
                        
                        val icon = ImageView(context).apply {
                            val d = MezonIcon.magnifyingIcon.getDrawable(context).mutate()
                            d.colorFilter = PorterDuffColorFilter(themeColors.textDisabled, PorterDuff.Mode.SRC_IN)
                            setImageDrawable(d)
                            alpha = 0.5f
                        }
                        addView(icon, LayoutHelper.createLinear(48, 48, 0f, Gravity.CENTER_HORIZONTAL))
                        
                        val text = TextView(context).apply {
                            text = context.getString(R.string.emoji_gifs_empty)
                            textSize = 15f
                            setTextColor(themeColors.textDisabled)
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            gravity = Gravity.CENTER
                            alpha = 0.6f
                        }
                        addView(text, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_HORIZONTAL, 0f, 12f, 0f, 0f))
                    }
                    contentContainer.addView(gifEmptyView, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER
                    ))
                }
            }
        }
    }

    private fun loadGifData() {
        val ctrl = emojiController ?: return
        ctrl.loadGifCategories()
        ctrl.loadFeaturedGifs()
        val cats = synchronized(ctrl) { ArrayList(ctrl.gifCategories) }
        if (cats.isNotEmpty()) {
            gifCategoryAdapter?.setData(cats)
        }
    }

    fun onEmojisReloaded() {
        if (currentTab == TAB_EMOJI) loadEmojiData()
    }

    fun onStickersReloaded() {
        if (currentTab == TAB_STICKER) loadStickerData()
    }

    fun onGifsReloaded() {
        if (currentTab != TAB_GIF) return
        val ctrl = emojiController ?: return
        val cats = synchronized(ctrl) { ArrayList(ctrl.gifCategories) }
        if (cats.isNotEmpty()) {
            gifCategoryAdapter?.setData(cats)
        }
        if (gifSearchActive) {
            val results = synchronized(ctrl) { ArrayList(ctrl.searchGifResults) }
            gifAdapter?.setData(results)
        }
        updateGifGridVisibility()
    }

    private fun loadEmojiData() {
        val ctrl = emojiController ?: return
        val categories = ctrl.getCategories()
        val data = categories.map { cat ->
            cat.name to ctrl.getEmojisByCategory(cat.name)
        }
        emojiAdapter?.setData(data)
    }

    private fun loadStickerData() {
        val ctrl = emojiController ?: return
        val visualOnly = synchronized(ctrl) {
            ctrl.stickers.filter { !it.isAudio }
        }
        stickerAdapter?.setData(visualOnly)
    }

    private fun performSearch(query: String) {
        val ctrl = emojiController ?: return
        if (query.isEmpty()) {
            when (currentTab) {
                TAB_EMOJI -> loadEmojiData()
                TAB_STICKER -> loadStickerData()
                TAB_GIF -> {
                    gifSearchActive = false
                    currentGifCategory = null
                    updateGifGridVisibility()
                    loadGifData()
                }
            }
            return
        }
        when (currentTab) {
            TAB_EMOJI -> emojiAdapter?.setSearchResults(ctrl.searchEmojis(query))
            TAB_STICKER -> stickerAdapter?.setSearchResults(ctrl.searchStickers(query))
            TAB_GIF -> {
                gifSearchActive = true
                updateGifGridVisibility()
                ctrl.searchGifs(query)
            }
        }
    }

    private fun createTabButton(text: String): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(LayoutHelper.dp(12f), LayoutHelper.dp(6f), LayoutHelper.dp(12f), LayoutHelper.dp(6f))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(16f).toFloat()
            }
        }
    }

    private fun updateTabSelection() {
        val tabs = listOf(tabEmoji, tabGif, tabSticker)
        for ((i, tab) in tabs.withIndex()) {
            val isSelected = i == currentTab
            tab.setTextColor(if (isSelected) themeColors.onPrimary else themeColors.onSurfaceVariant)
            (tab.background as? android.graphics.drawable.GradientDrawable)?.setColor(
                if (isSelected) themeColors.primary else Color.TRANSPARENT
            )
        }
        searchField.hint = when (currentTab) {
            TAB_EMOJI -> context.getString(R.string.emoji_search_emoji_placeholder)
            TAB_GIF -> context.getString(R.string.emoji_search_gif_placeholder)
            TAB_STICKER -> context.getString(R.string.emoji_search_sticker_placeholder)
            else -> context.getString(R.string.common_search)
        }
    }

    fun clearSearchFocus() {
        searchField.clearFocus()
        AndroidUtilities.hideKeyboard(searchField)
    }

    val isSearchFocused: Boolean get() = searchField.hasFocus()

    private fun getActiveRecyclerView(): androidx.recyclerview.widget.RecyclerView? {
        return when (currentTab) {
            TAB_EMOJI -> emojiGrid
            TAB_GIF -> if (gifSearchActive) gifGrid else gifCategoryGrid
            TAB_STICKER -> stickerGrid
            else -> null
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartY = ev.rawY
                dragTranslation = 0f
                dragging = false
                dragStartedExpanded = delegate?.isExpanded() ?: false
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain()
                velocityTracker?.addMovement(ev)
                if (ev.y < DRAG_HANDLE_HEIGHT) return true
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(ev)
                val dy = ev.rawY - dragStartY
                if (!dragging && Math.abs(dy) > touchSlop) {
                    var shouldIntercept = false
                    if (dy > 0) {
                        if (ev.y < DRAG_HANDLE_HEIGHT + tabBar.height) {
                            shouldIntercept = true
                        } else {
                            val rv = getActiveRecyclerView()
                            if (rv == null || !rv.canScrollVertically(-1)) {
                                shouldIntercept = true
                            }
                        }
                    } else {
                        if (!dragStartedExpanded) {
                            shouldIntercept = true
                        }
                    }
                    if (shouldIntercept) {
                        dragging = true
                        dragStartY = ev.rawY
                        return true
                    }
                }
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        velocityTracker?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartY = ev.rawY
                dragStartedExpanded = delegate?.isExpanded() ?: false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.rawY - dragStartY
                if (!dragging && Math.abs(dy) > touchSlop) {
                    dragging = true
                    dragStartY = ev.rawY
                }
                if (dragging) {
                    dragTranslation = ev.rawY - dragStartY
                    val child = getChildAt(0)
                    if (dragTranslation > 0) {
                        if (dragStartedExpanded) {
                            child?.translationY = 0f
                            delegate?.onDragY(dragTranslation, true)
                        } else {
                            child?.translationY = dragTranslation
                            delegate?.onDragY(0f, false)
                        }
                    } else {
                        child?.translationY = 0f
                        delegate?.onDragY(dragTranslation, dragStartedExpanded)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    velocityTracker?.computeCurrentVelocity(1000)
                    val vy = velocityTracker?.yVelocity ?: 0f
                    val child = getChildAt(0) ?: return true
                    val h = child.height.toFloat()
                    if (dragTranslation > 0) {
                        if (dragStartedExpanded || child.translationY == 0f) {
                            if (vy > dismissVelocity || dragTranslation > h * 0.2f) {
                                delegate?.onAnimateExpand(false)
                            } else {
                                delegate?.onAnimateExpand(true)
                            }
                        } else {
                            if (vy > dismissVelocity || dragTranslation > h * 0.4f) {
                                animateSlide(child, dragTranslation, h, dismiss = true)
                            } else {
                                animateSlide(child, dragTranslation, 0f, dismiss = false)
                            }
                        }
                    } else {
                        if (vy < -dismissVelocity || dragTranslation < -h * 0.2f) {
                            delegate?.onAnimateExpand(true)
                        } else {
                            delegate?.onAnimateExpand(false)
                        }
                    }
                }
                velocityTracker?.recycle()
                velocityTracker = null
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    private fun animateSlide(child: View, from: Float, to: Float, dismiss: Boolean) {
        ValueAnimator.ofFloat(from, to).apply {
            duration = if (dismiss) 200L else 150L
            addUpdateListener { child.translationY = it.animatedValue as Float }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (dismiss) {
                        child.translationY = 0f
                        delegate?.onDismissRequested()
                    }
                }
            })
            start()
        }
    }
    
    fun isGifTrendingActive(): Boolean {
        return gifSearchActive && currentGifCategory?.isTrending == true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        searchRunnable?.let { handler.removeCallbacks(it) }
        velocityTracker?.recycle()
        velocityTracker = null
    }

    private class DragHandleView(context: Context, themeColors: ThemeColors) : View(context) {

        private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = themeColors.onSurfaceVariant
            style = Paint.Style.FILL
        }
        private val handleRect = RectF()

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val hw = HANDLE_W / 2f
            val hh = HANDLE_H / 2f
            handleRect.set(cx - hw, cy - hh, cx + hw, cy + hh)
            canvas.drawRoundRect(handleRect, hh, hh, handlePaint)
        }
    }

    companion object {
        private val DRAG_HANDLE_HEIGHT = LayoutHelper.dp(20f)
        private val HANDLE_W = LayoutHelper.dp(36f).toFloat()
        private val HANDLE_H = LayoutHelper.dp(4f).toFloat()
    }

    private class GifSpacingDecoration(private val spacing: Int) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: android.graphics.Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            outRect.set(spacing, spacing, spacing, spacing)
        }
    }
}
