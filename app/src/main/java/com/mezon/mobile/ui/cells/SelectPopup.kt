package com.mezon.mobile.ui.cells

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

class SelectPopup(private val context: Context, private val theme: ThemeColors) {

    data class SelectItem(
        val id: String,
        val label: String
    )

    private val items = ArrayList<SelectItem>()
    private var selectedId: String? = null
    private var popupWindow: PopupWindow? = null
    var onItemSelected: ((SelectItem) -> Unit)? = null

    fun setItems(list: List<SelectItem>, selected: String? = null) {
        items.clear()
        items.addAll(list)
        selectedId = selected
    }

    fun setItems(labels: List<String>, selectedIndex: Int = -1) {
        items.clear()
        labels.forEachIndexed { i, label ->
            items.add(SelectItem(i.toString(), label))
        }
        selectedId = if (selectedIndex in labels.indices) selectedIndex.toString() else null
    }

    fun setOnItemSelectedListener(listener: (Int) -> Unit) {
        onItemSelected = { item -> listener(item.id.toIntOrNull() ?: 0) }
    }

    fun show(anchorView: View) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.surface)
            elevation = LayoutHelper.dpf(8f)
        }

        items.forEachIndexed { index, item ->
            val row = SelectRowCell(context, theme)
            row.bind(item.label, item.id == selectedId, index < items.size - 1)
            row.setOnClickListener {
                selectedId = item.id
                onItemSelected?.invoke(item)
                dismiss()
            }
            container.addView(row, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT
            ))
        }

        val scrollView = ScrollView(context).apply {
            addView(container, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        val menuWidth = LayoutHelper.dp(220)
        val maxHeight = LayoutHelper.dp(300)
        popupWindow = PopupWindow(scrollView, menuWidth, maxHeight, true).apply {
            elevation = LayoutHelper.dpf(8f)
            isOutsideTouchable = true
            isFocusable = true
            showAsDropDown(anchorView, 0, 0, Gravity.END or Gravity.TOP)
        }
    }

    fun dismiss() {
        popupWindow?.dismiss()
        popupWindow = null
    }

    private class SelectRowCell(context: Context, private val theme: ThemeColors) : FrameLayout(context) {

        private val radioCell = RadioCell(context, theme)
        private val textCell = android.widget.TextView(context).apply {
            setTextColor(theme.onSurface)
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
        }
        private var needDivider = false
        private var isSelected = false

        init {
            setWillNotDraw(false)
            minimumHeight = LayoutHelper.dp(48)

            val outValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            if (outValue.resourceId != 0) {
                foreground = androidx.core.content.ContextCompat.getDrawable(context, outValue.resourceId)
            }

            addView(radioCell, LayoutHelper.createFrame(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL or Gravity.START,
                leftMargin = 16f
            ))
            addView(textCell, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL,
                leftMargin = 50f, rightMargin = 16f
            ))
        }

        fun bind(label: String, selected: Boolean, divider: Boolean) {
            textCell.text = label
            isSelected = selected
            radioCell.setChecked(selected, animated = false)
            needDivider = divider
        }

        override fun hasOverlappingRendering(): Boolean = false

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            if (needDivider) {
                val left = LayoutHelper.dp(16).toFloat()
                canvas.drawRect(left, (height - 1).toFloat(), width.toFloat(), height.toFloat(), theme.dividerPaint)
            }
        }

        override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = "android.widget.RadioButton"
            info.isCheckable = true
            info.isChecked = isSelected
            info.text = textCell.text
        }
    }
}
