package com.mezon.mobile.ui.cells

import android.content.Context
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.text.Layout
import android.text.StaticLayout
import android.view.View
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

class TextSettingsCell(context: Context, private val theme: ThemeColors) : View(context) {

    private var titleText = ""
    private var valueText = ""
    private var icon: Drawable? = null
    private var titleLayout: StaticLayout? = null
    private var valueLayout: StaticLayout? = null
    private var needDivider = false
    private var titleColorOverride = 0
    private val cellHeight = LayoutHelper.dp(48)
    private val iconSize = LayoutHelper.dp(24)

    init {
        minimumHeight = cellHeight
        isClickable = true
        isFocusable = true
    }

    fun setTextAndValue(title: String, value: String = "", divider: Boolean = false) {
        titleText = title
        valueText = value
        needDivider = divider
        titleLayout = null
        valueLayout = null
        requestLayout()
    }

    fun setIcon(drawable: Drawable?) {
        icon = drawable?.mutate()
        icon?.colorFilter = PorterDuffColorFilter(theme.onSurfaceVariant, PorterDuff.Mode.SRC_IN)
        invalidate()
    }

    fun setIcon(@DrawableRes resId: Int) {
        if (resId == 0) {
            icon = null
            titleLayout = null
            requestLayout()
            return
        }
        setIcon(ContextCompat.getDrawable(context, resId))
    }

    fun setIcon(mezonIcon: MezonIcon) {
        setIcon(mezonIcon.resId)
    }

    fun setTextAndIcon(title: String, @DrawableRes iconResId: Int, divider: Boolean = false) {
        setTextAndValue(title, "", divider)
        setIcon(iconResId)
    }

    fun setTitleColor(color: Int) {
        titleColorOverride = color
        titleLayout = null
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val leftPad = if (icon != null) LayoutHelper.dp(16 + 24 + 16) else LayoutHelper.dp(16)
        val rightPad = LayoutHelper.dp(16)

        val valueWidth = if (valueText.isNotEmpty()) {
            theme.settingsValuePaint.measureText(valueText).toInt() + LayoutHelper.dp(8)
        } else 0
        val titleWidth = w - leftPad - rightPad - valueWidth

        if (titleLayout == null && titleWidth > 0) {
            titleLayout = StaticLayout.Builder
                .obtain(titleText, 0, titleText.length, theme.settingsNamePaint, titleWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1f)
                .setMaxLines(1)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .build()
        }
        if (valueText.isNotEmpty() && valueLayout == null) {
            valueLayout = StaticLayout.Builder
                .obtain(valueText, 0, valueText.length, theme.settingsValuePaint, valueWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1f)
                .setMaxLines(1)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .build()
        }

        val h = if (needDivider) cellHeight + 1 else cellHeight
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val leftPad = if (icon != null) LayoutHelper.dp(16 + 24 + 16) else LayoutHelper.dp(16)

        icon?.let { d ->
            val iconLeft = LayoutHelper.dp(16)
            val iconTop = (cellHeight - iconSize) / 2
            d.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
            d.draw(canvas)
        }

        val savedTitleColor = theme.settingsNamePaint.color
        if (titleColorOverride != 0) theme.settingsNamePaint.color = titleColorOverride

        titleLayout?.let {
            val titleY = (cellHeight - it.height) / 2f
            canvas.save()
            canvas.translate(leftPad.toFloat(), titleY)
            it.draw(canvas)
            canvas.restore()
        }

        if (titleColorOverride != 0) theme.settingsNamePaint.color = savedTitleColor

        valueLayout?.let {
            val valueX = width - LayoutHelper.dp(16) - it.width
            val valueY = (cellHeight - it.height) / 2f
            canvas.save()
            canvas.translate(valueX.toFloat(), valueY)
            it.draw(canvas)
            canvas.restore()
        }

        if (needDivider) {
            canvas.drawRect(
                leftPad.toFloat(), (cellHeight).toFloat(),
                width.toFloat(), (cellHeight + 1).toFloat(),
                theme.dividerPaint
            )
        }
    }
}
