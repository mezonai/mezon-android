package com.mezon.mobile.deeplink

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
import com.mezon.mobile.ui.cells.EditTextBoldCursor
import com.mezon.mobile.ui.cells.MezonIcon
import java.text.Normalizer
import java.util.Locale

class InstallPickerSheet(
    context: Context,
    private val themeColors: ThemeColors,
    title: CharSequence,
    items: List<InstallPickerItem>,
    private val onPicked: (InstallPickerItem) -> Unit,
) : BottomSheet(context, needFocusable = true) {

    data class InstallPickerItem(
        val id: String,
        val label: String,
    )

    private val allItems = items
    private var filteredItems = items.toList()
    private lateinit var adapter: RowsAdapter
    private var emptyViewRef: TextView? = null
    private var searchField: EditTextBoldCursor? = null

    init {
        setTitle(title)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val screenH = AndroidUtilities.displaySize.y
        val panelHeight = (screenH * 0.72f).toInt().coerceAtLeast(LayoutHelper.dp(360f))
        adapter = RowsAdapter()

        val list = RecyclerListView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@InstallPickerSheet.adapter
            overScrollMode = RecyclerView.OVER_SCROLL_NEVER
            itemAnimator = null
            setOnItemClickListener { _, pos ->
                if (pos in filteredItems.indices) {
                    onPicked(filteredItems[pos])
                    dismiss()
                }
            }
            setPadding(LayoutHelper.dp(16f), 0, LayoutHelper.dp(16f), LayoutHelper.dp(8f))
            clipToPadding = false
        }

        val emptyView = TextView(context).apply {
            visibility = View.GONE
            gravity = Gravity.CENTER
            text = context.getString(R.string.deeplink_install_no_match)
            textSize = 14f
            setTextColor(themeColors.onSurfaceVariant)
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
            hint = context.getString(R.string.deeplink_install_search_clan)
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
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
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

    override fun dismiss() {
        emptyViewRef = null
        searchField = null
        super.dismiss()
    }

    private fun applyFilter(query: String) {
        filteredItems = if (query.isBlank()) {
            allItems.toList()
        } else {
            val q = normalizeForSearch(query)
            allItems.filter { normalizeForSearch(it.label).contains(q) }
        }
        adapter.notifyDataSetChanged()
        emptyViewRef?.visibility = if (filteredItems.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun normalizeForSearch(value: String): String {
        return Normalizer.normalize(value.trim().lowercase(Locale.getDefault()), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
    }

    private inner class RowsAdapter : RecyclerView.Adapter<RowHolder>() {
        override fun getItemCount(): Int = filteredItems.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
            val row = TextView(parent.context).apply {
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(themeColors.onSurface)
                maxLines = 2
                background = GradientDrawable().apply {
                    cornerRadius = LayoutHelper.dp(8f).toFloat()
                    setColor(themeColors.surfaceVariant)
                }
                setPadding(LayoutHelper.dp(14f), LayoutHelper.dp(12f), LayoutHelper.dp(14f), LayoutHelper.dp(12f))
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    bottomMargin = LayoutHelper.dp(8f)
                }
            }
            return RowHolder(row)
        }

        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            holder.label.text = filteredItems[position].label
        }
    }

    private class RowHolder(val label: TextView) : RecyclerView.ViewHolder(label)
}
