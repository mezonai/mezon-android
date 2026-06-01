package com.mezon.mobile.home.clans.settings

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BottomSheet
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.RecyclerListView
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.clans.ChannelItemCell
import com.mezon.mobile.home.clans.ClanChannelEntity
import com.mezon.mobile.home.clans.CreateClanRnUiTokens
import com.mezon.mobile.ui.cells.EditTextBoldCursor
import com.mezon.mobile.ui.cells.MezonIcon
import java.util.Locale

class ChannelPickerSheet(
    context: Context,
    private val themeColors: ThemeColors,
    channels: List<ClanChannelEntity>,
    title: CharSequence,
    private val onChannelPicked: (ClanChannelEntity) -> Unit,
) : BottomSheet(context, needFocusable = true) {

    private val allSorted = channels
    private var filtered = allSorted.toList()

    private lateinit var adapter: ChannelRowsAdapter
    private var emptyViewRef: TextView? = null
    private var searchField: EditTextBoldCursor? = null

    init {
        setTitle(title)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val screenH = AndroidUtilities.displaySize.y
        val panelHeight = (screenH * 0.52f).toInt().coerceAtLeast(LayoutHelper.dp(340f))

        adapter = ChannelRowsAdapter()

        val list = RecyclerListView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@ChannelPickerSheet.adapter
            overScrollMode = RecyclerView.OVER_SCROLL_NEVER
            itemAnimator = null
            setOnItemClickListener { _, pos ->
                if (pos in filtered.indices) {
                    onChannelPicked(filtered[pos])
                    dismiss()
                }
            }
            setPadding(LayoutHelper.dp(12f), 0, LayoutHelper.dp(12f), LayoutHelper.dp(8f))
            clipToPadding = false
        }

        val emptyView = TextView(context).apply {
            visibility = View.GONE
            gravity = Gravity.CENTER
            text = context.getString(R.string.webhook_pick_channel_no_match)
            textSize = 14f
            setTextColor(CreateClanRnUiTokens.textDisabled(themeColors))
            setPadding(LayoutHelper.dp(24f), LayoutHelper.dp(32f), LayoutHelper.dp(24f), LayoutHelper.dp(32f))
        }
        emptyViewRef = emptyView

        val listFrame = FrameLayout(context).apply {
            addView(list, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))
            addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER))
        }

        val searchBar = FrameLayout(context).apply {
            setPadding(LayoutHelper.dp(16f), LayoutHelper.dp(4f), LayoutHelper.dp(16f), LayoutHelper.dp(12f))
        }
        val searchInput = EditTextBoldCursor(context).apply {
            hint = context.getString(R.string.common_search_placeholder)
            setHintTextColor(themeColors.textDisabled)
            setTextColor(themeColors.onSurface)
            textSize = 14f
            maxLines = 1
            isSingleLine = true
            typeface = Typeface.DEFAULT
            setPadding(LayoutHelper.dp(38f), LayoutHelper.dp(10f), LayoutHelper.dp(12f), LayoutHelper.dp(10f))
            background = GradientDrawable().apply {
                setColor(themeColors.surfaceVariant)
                cornerRadius = LayoutHelper.dp(10f).toFloat()
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    applyFilter(s?.toString().orEmpty())
                }
            })
        }
        searchField = searchInput
        val searchIcon = ImageView(context).apply {
            val d = MezonIcon.magnifyingIcon.getDrawable(context).mutate()
            d.colorFilter = PorterDuffColorFilter(themeColors.onSurfaceVariant, PorterDuff.Mode.SRC_IN)
            setImageDrawable(d)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        searchBar.addView(
            searchInput,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, LayoutHelper.dp(42f)),
        )
        searchBar.addView(
            searchIcon,
            FrameLayout.LayoutParams(LayoutHelper.dp(18f), LayoutHelper.dp(18f)).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                leftMargin = LayoutHelper.dp(12f)
            },
        )

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.background)
            addView(searchBar, LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            addView(listFrame, LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, 0, 1f))
        }

        val panelContainer = FrameLayout(context).apply {
            addView(container, FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, panelHeight))
        }

        applyFilter("")

        setCustomView(panelContainer)
        super.onCreate(savedInstanceState)
    }

    private fun applyFilter(query: String) {
        filtered = if (query.isBlank()) {
            allSorted.toList()
        } else {
            val q = query.trim().lowercase(Locale.getDefault())
            allSorted.filter { it.channelLabel.lowercase(Locale.getDefault()).contains(q) }
        }
        adapter.notifyDataSetChanged()
        emptyViewRef?.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun dismiss() {
        emptyViewRef = null
        searchField = null
        super.dismiss()
    }

    private inner class ChannelRowsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount(): Int = filtered.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val ctx = parent.context
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = CreateClanRnUiTokens.clanSettingsMenuCornerPx()
                    setColor(themeColors.surfaceVariant)
                }
                setPadding(LayoutHelper.dp(14f), LayoutHelper.dp(10f), LayoutHelper.dp(14f), LayoutHelper.dp(10f))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = LayoutHelper.dp(8f)
                }
            }

            row.addView(
                ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    val d = MezonIcon.channelText.getDrawable(ctx).mutate()
                    d.colorFilter = PorterDuffColorFilter(CreateClanRnUiTokens.menuText(themeColors), PorterDuff.Mode.SRC_IN)
                    setImageDrawable(d)
                },
                LayoutHelper.createLinear(14, 14, 0f, Gravity.CENTER_VERTICAL, 0f, 0f, 8f, 0f),
            )

            val label = TextView(ctx).apply {
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(CreateClanRnUiTokens.menuText(themeColors))
                maxLines = 2
            }
            row.addView(label, LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f))

            return object : RecyclerView.ViewHolder(row) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val row = holder.itemView as LinearLayout
            val ch = filtered[position]
            val iconView = row.getChildAt(0) as ImageView
            val iconEnum = ChannelItemCell.resolveChannelIcon(ch.type, ch.isPrivate, ch.isAgeRestricted)
            val d = iconEnum.getDrawable(row.context).mutate()
            d.colorFilter = PorterDuffColorFilter(CreateClanRnUiTokens.menuText(themeColors), PorterDuff.Mode.SRC_IN)
            iconView.setImageDrawable(d)
            val label = row.getChildAt(1) as TextView
            label.text = ch.channelLabel
        }
    }
}
