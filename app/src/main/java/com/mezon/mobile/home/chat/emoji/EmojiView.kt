package com.mezon.mobile.home.chat.emoji

import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
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
import com.mezon.mobile.home.chat.EmojiController
import com.mezon.mobile.home.chat.EmojiItem
import com.mezon.mobile.home.chat.StickerItem
import com.mezon.mobile.network.TenorCategory
import com.mezon.mobile.network.TenorGif
import com.mezon.mobile.ui.cells.MezonIcon

private const val TAB_EMOJI = 0
private const val TAB_GIF = 1
private const val TAB_STICKER = 2
private const val SEARCH_DEBOUNCE_MS = 300L
private const val EMOJI_COLUMNS = 9
private const val STICKER_COLUMNS = 5

class EmojiView(context: Context, private val themeColors: ThemeColors) : FrameLayout(context) {

    interface EmojiViewDelegate {
        fun onEmojiSelected(emoji: EmojiItem)
        fun onStickerSelected(sticker: StickerItem, isAudio: Boolean)
        fun onGifSelected(gifUrl: String)
        fun onBackspace()
        fun onSearchFocusChanged(focused: Boolean) {}
    }

    var delegate: EmojiViewDelegate? = null
    private var currentTab = TAB_EMOJI
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

    private var emojiGrid: RecyclerListView? = null
    private var stickerGrid: RecyclerListView? = null
    private var gifGrid: RecyclerListView? = null
    private var gifCategoryGrid: RecyclerListView? = null
    private var emojiAdapter: EmojiGridAdapter? = null
    private var stickerAdapter: StickerGridAdapter? = null
    private var gifCategoryAdapter: GifCategoryAdapter? = null
    private var gifSearchActive = false
    private var gifAdapter: GifGridAdapter? = null

    init {
        setBackgroundColor(themeColors.surface)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        tabBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(themeColors.surface)
            setPadding(0, LayoutHelper.dp(4f), 0, LayoutHelper.dp(4f))
        }

        tabEmoji = createTabButton("Emoji")
        tabGif = createTabButton("GIF")
        tabSticker = createTabButton("Sticker")

        tabEmoji.setOnClickListener { switchTab(TAB_EMOJI) }
        tabGif.setOnClickListener { switchTab(TAB_GIF) }
        tabSticker.setOnClickListener { switchTab(TAB_STICKER) }

        tabBar.addView(tabEmoji, LayoutHelper.createLinear(0, 36, 1f, leftMargin = 4f, rightMargin = 2f))
        tabBar.addView(tabGif, LayoutHelper.createLinear(0, 36, 1f, leftMargin = 2f, rightMargin = 2f))
        tabBar.addView(tabSticker, LayoutHelper.createLinear(0, 36, 1f, leftMargin = 2f, rightMargin = 4f))
        root.addView(tabBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val searchBar = FrameLayout(context).apply {
            setPadding(LayoutHelper.dp(8f), LayoutHelper.dp(4f), LayoutHelper.dp(8f), LayoutHelper.dp(4f))
        }

        searchField = EditText(context).apply {
            hint = "Search"
            setHintTextColor(themeColors.onSurfaceVariant)
            setTextColor(themeColors.onSurface)
            textSize = 14f
            maxLines = 1
            isSingleLine = true
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(themeColors.tertiary)
                cornerRadius = LayoutHelper.dp(16f).toFloat()
            }
            setPadding(LayoutHelper.dp(36f), LayoutHelper.dp(6f), LayoutHelper.dp(12f), LayoutHelper.dp(6f))
        }

        val searchIcon = ImageView(context).apply {
            val d = MezonIcon.searchIcon.getDrawable(context)
            d.colorFilter = PorterDuffColorFilter(themeColors.onSurfaceVariant, PorterDuff.Mode.SRC_IN)
            setImageDrawable(d)
            scaleType = ImageView.ScaleType.CENTER
        }
        searchBar.addView(searchField, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, LayoutHelper.dp(36f)
        ))
        searchBar.addView(searchIcon, FrameLayout.LayoutParams(
            LayoutHelper.dp(36f), LayoutHelper.dp(36f), Gravity.START or Gravity.CENTER_VERTICAL
        ))
        root.addView(searchBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        searchField.setOnFocusChangeListener { _, hasFocus ->
            delegate?.onSearchFocusChanged(hasFocus)
        }

        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
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
        root.addView(bottomBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44))

        addView(root, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        updateTabSelection()
    }

    fun init(controller: EmojiController) {
        emojiController = controller
    }

    fun onOpen(forceTab: Int = -1) {
        if (forceTab >= 0) {
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
        searchField.text?.clear()
        gifSearchActive = false

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
        if (currentTab == TAB_GIF) {
            gifCategoryGrid?.visibility = if (gifSearchActive) View.GONE else View.VISIBLE
            gifGrid?.visibility = if (gifSearchActive) View.VISIBLE else View.GONE
        } else {
            gifCategoryGrid?.visibility = View.GONE
            gifGrid?.visibility = View.GONE
        }
    }

    private fun onGifCategoryTapped(category: TenorCategory) {
        gifSearchActive = true
        updateGifGridVisibility()
        emojiController?.searchGifs(category.name)
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
                    }
                    contentContainer.addView(gifCategoryGrid, matchLp)
                }
                if (gifGrid == null) {
                    gifAdapter = GifGridAdapter(themeColors) { gif ->
                        delegate?.onGifSelected(gif.gifUrl)
                    }
                    gifGrid = RecyclerListView(context).apply {
                        layoutManager = GridLayoutManager(context, 2)
                        adapter = gifAdapter
                        setHasFixedSize(true)
                        (itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
                        overScrollMode = OVER_SCROLL_NEVER
                        addItemDecoration(GifSpacingDecoration(gifSpacing))
                        setPadding(gifSpacing, gifSpacing, gifSpacing, gifSpacing)
                        clipToPadding = false
                        visibility = View.GONE
                    }
                    contentContainer.addView(gifGrid, matchLp)
                }
            }
        }
    }

    private fun loadGifData() {
        val ctrl = emojiController ?: return
        ctrl.loadGifCategories()
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
        if (query.isBlank()) {
            when (currentTab) {
                TAB_EMOJI -> loadEmojiData()
                TAB_STICKER -> loadStickerData()
                TAB_GIF -> {
                    gifSearchActive = false
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
    }

    fun clearSearchFocus() {
        searchField.clearFocus()
        AndroidUtilities.hideKeyboard(searchField)
    }

    val isSearchFocused: Boolean get() = searchField.hasFocus()

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        searchRunnable?.let { handler.removeCallbacks(it) }
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
