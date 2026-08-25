package com.mezon.mobile.ui.cells

import android.content.Context
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import androidx.annotation.DrawableRes
import com.mezon.mobile.core.BaseCell
import androidx.core.content.ContextCompat
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

class PopupMenu(
    private val context: Context,
    private val theme: ThemeColors,
    private val fullWidthDividers: Boolean = false
) {

    private val items = ArrayList<MenuItem>()
    private var popupWindow: PopupWindow? = null
    private var onItemClick: ((Int) -> Unit)? = null
    private var onDismissListener: (() -> Unit)? = null

    data class MenuItem(
        val text: String,
        val icon: Drawable? = null,
        val destructive: Boolean = false
    )

    fun addItem(text: String, icon: Drawable? = null, destructive: Boolean = false) {
        items.add(MenuItem(text, icon, destructive))
    }

    fun addItem(text: String, @DrawableRes iconResId: Int, destructive: Boolean = false) {
        val drawable = if (iconResId != 0) ContextCompat.getDrawable(context, iconResId)?.mutate() else null
        items.add(MenuItem(text, drawable, destructive))
    }

    fun addItem(text: String, mezonIcon: MezonIcon, destructive: Boolean = false) {
        addItem(text, mezonIcon.resId, destructive)
    }

    fun setOnItemClickListener(listener: (Int) -> Unit) {
        onItemClick = listener
    }

    fun show(anchorView: View) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = true
        }

        items.forEachIndexed { index, item ->
            val cell = PopupItemCell(context, theme)
            cell.bind(item, index < items.size - 1, fullWidthDividers)
            cell.setOnClickListener {
                onItemClick?.invoke(index)
                dismiss()
            }
            container.addView(cell, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT
            ))
        }

        val scrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(container, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        val root = FrameLayout(context).apply {
            background = createMenuBackground()
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            elevation = LayoutHelper.dpf(8f)
            addView(scrollView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        val menuWidth = LayoutHelper.dp(200)
        popupWindow = PopupWindow(root, menuWidth, ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
            isOutsideTouchable = true
            isFocusable = false
            setBackgroundDrawable(ColorDrawable(0))
            setOnDismissListener {
                onDismissListener?.invoke()
            }
            showAsDropDown(anchorView, 0, 0, Gravity.END or Gravity.TOP)
        }
    }

    private fun createMenuBackground(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = MENU_CORNER_RADIUS
        setColor(theme.surface)
        setStroke(LayoutHelper.dp(1), theme.borderDim)
    }

    fun setOnDismissListener(listener: () -> Unit) {
        onDismissListener = listener
    }

    fun dismiss() {
        popupWindow?.dismiss()
        popupWindow = null
    }

    private class PopupItemCell(context: Context, private val theme: ThemeColors) : BaseCell(context) {

        private var menuItem: MenuItem? = null
        private var needDivider = false
        private var fullWidthDivider = false
        private val cellHeight = LayoutHelper.dp(44)
        private val iconSize = LayoutHelper.dp(16)
        private val iconTextGap = LayoutHelper.dp(12)

        init {
            minimumHeight = cellHeight
            isClickable = true
            isFocusable = true

            val outValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            if (outValue.resourceId != 0) {
                foreground = androidx.core.content.ContextCompat.getDrawable(context, outValue.resourceId)
            }
        }

        fun bind(item: MenuItem, divider: Boolean, fullWidthDivider: Boolean) {
            menuItem = item
            needDivider = divider
            this.fullWidthDivider = fullWidthDivider
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            setMeasuredDimension(w, if (needDivider) cellHeight + 1 else cellHeight)
        }

        override fun onDraw(canvas: Canvas) {
            val item = menuItem ?: return
            val leftPad = LayoutHelper.dp(16)
            val textPaint = theme.settingsNamePaint

            if (item.destructive) {
                textPaint.color = theme.error
            } else {
                textPaint.color = theme.onSurface
            }

            item.icon?.let { d ->
                d.mutate()
                d.colorFilter = PorterDuffColorFilter(
                    if (item.destructive) theme.error else theme.onSurfaceVariant,
                    PorterDuff.Mode.SRC_IN
                )
                val iconTop = (cellHeight - iconSize) / 2
                d.setBounds(leftPad, iconTop, leftPad + iconSize, iconTop + iconSize)
                d.draw(canvas)
            }

            val textX = if (item.icon != null) leftPad + iconSize + iconTextGap else leftPad
            val textY = cellHeight / 2f - (textPaint.descent() + textPaint.ascent()) / 2
            val availableTextWidth = (width - textX - leftPad).coerceAtLeast(0)
            val displayText = TextUtils.ellipsize(
                item.text,
                textPaint,
                availableTextWidth.toFloat(),
                TextUtils.TruncateAt.END
            )
            canvas.drawText(displayText, 0, displayText.length, textX.toFloat(), textY, textPaint)

            textPaint.color = theme.onSurface

            if (needDivider) {
                canvas.drawRect(
                    if (fullWidthDivider) 0f else leftPad.toFloat(),
                    cellHeight.toFloat(),
                    width.toFloat(), (cellHeight + 1).toFloat(),
                    theme.dividerPaint
                )
            }
        }
    }

    companion object {
        private val MENU_CORNER_RADIUS = LayoutHelper.dp(10f).toFloat()
    }
}
