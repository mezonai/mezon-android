package com.mezon.mobile.home.clans

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.R
import com.mezon.mobile.core.BottomSheet
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.RecyclerListView
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.EditTextBoldCursor
import com.mezon.mobile.ui.cells.MezonIcon
import java.util.Locale

private const val CHANNEL_SEARCH_DEBOUNCE_MS = 300L

class ChannelPickerSheet(
    context: Context,
    private val themeColors: ThemeColors,
    channels: List<ClanChannelEntity>,
    private val title: CharSequence,
    private val selectedChannelId: Long = 0L,
    private val onChannelPicked: (ClanChannelEntity) -> Unit,
) : BottomSheet(context, needFocusable = true) {

    private val allSorted = channels
    private var filtered = allSorted.toList()

    private lateinit var adapter: ChannelRowsAdapter
    private var emptyViewRef: TextView? = null
    private val searchHandler = Handler(Looper.getMainLooper())
    private var pendingSearch: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        setCanDismissWithSwipe(false)
        setAllowNestedScroll(false)
        val panelHeight = LayoutHelper.dp(132 + allSorted.size.coerceIn(1, 6) * 60)
            .coerceAtMost((context.resources.displayMetrics.heightPixels * 0.60f).toInt())

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
            setPadding(0, 0, 0, LayoutHelper.dp(4))
            clipToPadding = false
        }

        val emptyView = TextView(context).apply {
            visibility = View.GONE
            gravity = Gravity.CENTER
            text = context.getString(R.string.webhook_pick_channel_no_match)
            textSize = 14f
            setTextColor(CreateClanRnUiTokens.textDisabled(themeColors))
            setPadding(LayoutHelper.dp(24), LayoutHelper.dp(12), LayoutHelper.dp(24), LayoutHelper.dp(12))
        }
        emptyViewRef = emptyView

        val listFrame = FrameLayout(context).apply {
            addView(list, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))
            addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER))
        }

        val searchBar = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(themeColors.secondaryLight)
                cornerRadius = LayoutHelper.dpf(12f)
                setStroke(LayoutHelper.dp(1), themeColors.outlineVariant)
            }
        }
        val searchInput = EditTextBoldCursor(context).apply {
            hint = context.getString(R.string.common_search_placeholder)
            setHintTextColor(themeColors.onSurfaceVariant)
            setTextColor(themeColors.onSurface)
            textSize = 14f
            maxLines = 1
            isSingleLine = true
            typeface = Typeface.DEFAULT
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(44), 0, LayoutHelper.dp(12), 0)
            background = null
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    pendingSearch?.let(searchHandler::removeCallbacks)
                    val query = s?.toString().orEmpty()
                    pendingSearch = Runnable {
                        pendingSearch = null
                        applyFilter(query)
                    }.also { searchHandler.postDelayed(it, CHANNEL_SEARCH_DEBOUNCE_MS) }
                }
            })
        }
        val searchIcon = ImageView(context).apply {
            val d = MezonIcon.magnifyingIcon.getDrawable(context).mutate()
            d.colorFilter = PorterDuffColorFilter(themeColors.onSurfaceVariant, PorterDuff.Mode.SRC_IN)
            setImageDrawable(d)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        searchBar.addView(
            searchInput,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        searchBar.addView(
            searchIcon,
            FrameLayout.LayoutParams(LayoutHelper.dp(20), LayoutHelper.dp(20)).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                marginStart = LayoutHelper.dp(14)
            },
        )

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(20), 0, LayoutHelper.dp(20), LayoutHelper.dp(12))
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = title
                    textSize = 18f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(themeColors.onSurface)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL))
                addView(ImageView(context).apply {
                    setImageDrawable(MezonIcon.closeLargeIcon.getDrawable(context, themeColors.onSurfaceVariant))
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    val pad = LayoutHelper.dp(12)
                    setPadding(pad, pad, pad, pad)
                    contentDescription = context.getString(R.string.common_cancel)
                    setOnClickListener { dismiss() }
                }, LayoutHelper.createLinear(44, 44, 0f, Gravity.CENTER_VERTICAL))
            }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                bottomMargin = LayoutHelper.dp(12)
            })
            addView(searchBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48).apply {
                bottomMargin = LayoutHelper.dp(12)
            })
            addView(listFrame, LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, 0, 1f))
            isFocusableInTouchMode = true
        }

        val panelContainer = object : FrameLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val height = minOf(panelHeight, MeasureSpec.getSize(heightMeasureSpec))
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
            }
        }.apply {
            addView(container, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))
        }

        applyFilter("")

        setCustomView(panelContainer)
        super.onCreate(savedInstanceState)
        setBackgroundColor(themeColors.surface)
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
        pendingSearch?.let(searchHandler::removeCallbacks)
        pendingSearch = null
        emptyViewRef = null
        super.dismiss()
    }

    private inner class ChannelRowsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount(): Int = filtered.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val ctx = parent.context
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = LayoutHelper.dp(52)
                setPadding(LayoutHelper.dp(14), LayoutHelper.dp(12), LayoutHelper.dp(14), LayoutHelper.dp(12))
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
                LayoutHelper.createLinear(20, 20, 0f, Gravity.CENTER_VERTICAL, 0f, 0f, 12f, 0f),
            )

            val label = TextView(ctx).apply {
                textSize = 15f
                typeface = Typeface.DEFAULT
                setTextColor(themeColors.onSurface)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            row.addView(label, LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f))
            row.addView(ImageView(ctx).apply {
                setImageDrawable(MezonIcon.checkmarkSmallIcon.getDrawable(ctx, themeColors.primary))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutHelper.createLinear(20, 20, 0f, Gravity.CENTER_VERTICAL, 12f, 0f, 0f, 0f))

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
            val selected = ch.channelId == selectedChannelId && selectedChannelId != 0L
            row.isSelected = selected
            row.background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dpf(12f)
                setColor(if (selected) ColorUtils.blendARGB(themeColors.surface, themeColors.primary, 0.10f) else themeColors.secondaryLight)
                if (selected) setStroke(LayoutHelper.dp(1), themeColors.primary)
            }
            label.setTextColor(if (selected) themeColors.primary else themeColors.onSurface)
            row.getChildAt(2).visibility = if (selected) View.VISIBLE else View.INVISIBLE
        }
    }
}
