package com.mezon.mobile.home.chat

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BottomSheet
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.RecyclerListView
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.chat.emoji.EmojiGridAdapter
import com.mezon.mobile.ui.cells.EditTextBoldCursor
import com.mezon.mobile.ui.cells.MezonIcon

class ReactionEmojiPickerSheet(
    context: Context,
    private val themeColors: ThemeColors,
    private val emojiController: EmojiController,
    private val notificationCenter: NotificationCenter,
    private val autoDismiss: Boolean = true,
    private val onEmojiPicked: (emojiId: Long, emojiShortname: String) -> Unit
) : BottomSheet(context, needFocusable = true) {

    private var emojiAdapter: EmojiGridAdapter? = null
    private var searchField: EditTextBoldCursor? = null

    private val reloadObserver = object : NotificationCenter.NotificationCenterDelegate {
        override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
            if (id == NotificationCenter.emojisNeedReload) {
                loadEmojiData()
            }
        }
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        val screenH = AndroidUtilities.displaySize.y
        val panelHeight = (screenH * 0.55f).toInt().coerceAtLeast(LayoutHelper.dp(350f))
        val columns = 9

        val adapter = EmojiGridAdapter(themeColors) { emoji ->
            if (emoji.isForSale && emoji.src.isBlank()) return@EmojiGridAdapter
            val id = emoji.id.toLongOrNull() ?: return@EmojiGridAdapter
            onEmojiPicked(id, emoji.shortname)
            if (autoDismiss) {
                dismiss()
            }
        }
        emojiAdapter = adapter
        val layoutManager = GridLayoutManager(context, columns).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return if (adapter.isHeader(position)) columns else 1
                }
            }
        }
        val list = RecyclerListView(context).apply {
            this.layoutManager = layoutManager
            this.adapter = adapter
            setHasFixedSize(true)
            (itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
            overScrollMode = RecyclerView.OVER_SCROLL_NEVER
            setPadding(LayoutHelper.dp(11f), 0, LayoutHelper.dp(11f), 0)
            clipToPadding = false
        }

        val searchBar = FrameLayout(context).apply {
            setPadding(LayoutHelper.dp(10f), LayoutHelper.dp(10f), LayoutHelper.dp(10f), LayoutHelper.dp(8f))
        }
        val searchInput = EditTextBoldCursor(context).apply {
            hint = "Search"
            setHintTextColor(themeColors.textDisabled)
            setTextColor(themeColors.onSurface)
            textSize = 14f
            maxLines = 1
            isSingleLine = true
            setBackgroundColor(themeColors.tertiary)
            setPadding(LayoutHelper.dp(38f), LayoutHelper.dp(8f), LayoutHelper.dp(10f), LayoutHelper.dp(8f))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(themeColors.tertiary)
                cornerRadius = LayoutHelper.dp(10f).toFloat()
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val query = s?.toString().orEmpty().trim()
                    if (query.isEmpty()) {
                        loadEmojiData()
                    } else {
                        adapter.setSearchResults(emojiController.searchEmojis(query))
                    }
                }
            })
        }
        searchField = searchInput
        val searchIcon = ImageView(context).apply {
            setImageDrawable(MezonIcon.magnifyingIcon.getDrawable(context).apply {
                colorFilter = android.graphics.PorterDuffColorFilter(
                    themeColors.onSurface,
                    android.graphics.PorterDuff.Mode.SRC_IN
                )
            })
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        searchBar.addView(
            searchInput,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                LayoutHelper.dp(40f)
            )
        )
        searchBar.addView(
            searchIcon,
            FrameLayout.LayoutParams(
                LayoutHelper.dp(18f),
                LayoutHelper.dp(18f),
                Gravity.START or Gravity.CENTER_VERTICAL
            ).apply {
                leftMargin = LayoutHelper.dp(10f)
            }
        )

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(searchBar, LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            addView(list, LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, 0, 1f))
        }
        val panelContainer = FrameLayout(context).apply {
            addView(container, FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, panelHeight))
        }

        notificationCenter.addObserver(reloadObserver, NotificationCenter.emojisNeedReload)
        loadEmojiData()
        emojiController.loadEmojis()

        setCustomView(panelContainer)
        super.onCreate(savedInstanceState)
    }

    private fun loadEmojiData() {
        val categories = emojiController.getCategories()
        val data = categories.map { cat ->
            cat.name to emojiController.getEmojisByCategory(cat.name)
        }
        emojiAdapter?.setData(data)
    }

    override fun dismiss() {
        notificationCenter.removeObserver(reloadObserver, NotificationCenter.emojisNeedReload)
        searchField = null
        emojiAdapter = null
        super.dismiss()
    }
}
