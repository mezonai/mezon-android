package com.mezon.mobile.home.chat.emoji

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.widget.FrameLayout
import com.mezon.mobile.core.BaseCell
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.MezonIcon

private val CELL_HEIGHT = LayoutHelper.dp(36f)
private val TEXT_LEFT = LayoutHelper.dp(12f)
private val CHEVRON_SIZE = LayoutHelper.dp(16f)
private val CHEVRON_RIGHT = LayoutHelper.dp(12f)

class CollapsibleHeaderCell(context: Context, private val themeColors: ThemeColors) : BaseCell(context) {

    var title: String = ""
        private set
    var isExpanded: Boolean = true
        private set
    var onToggle: (() -> Unit)? = null

    private var chevronDown: Drawable? = null
    private var chevronRight: Drawable? = null

    init {
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, CELL_HEIGHT, Gravity.TOP
        )
        setOnClickListener { onToggle?.invoke() }
    }

    fun bind(title: String, expanded: Boolean) {
        this.title = title
        this.isExpanded = expanded
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), CELL_HEIGHT)
    }

    override fun onDraw(canvas: Canvas) {
        val cy = measuredHeight / 2f
        canvas.drawText(title.uppercase(), TEXT_LEFT.toFloat(), cy - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)

        val chevron = if (isExpanded) {
            chevronDown ?: MezonIcon.chevronDownSmallIcon.getDrawable(context).mutate().also {
                it.colorFilter = PorterDuffColorFilter(themeColors.onSurfaceVariant, PorterDuff.Mode.SRC_IN)
                chevronDown = it
            }
        } else {
            chevronRight ?: MezonIcon.chevronSmallRightIcon.getDrawable(context).mutate().also {
                it.colorFilter = PorterDuffColorFilter(themeColors.onSurfaceVariant, PorterDuff.Mode.SRC_IN)
                chevronRight = it
            }
        }
        val cx = measuredWidth - CHEVRON_RIGHT - CHEVRON_SIZE
        val ct = (measuredHeight - CHEVRON_SIZE) / 2
        chevron.setBounds(cx, ct, cx + CHEVRON_SIZE, ct + CHEVRON_SIZE)
        chevron.draw(canvas)
    }

    companion object {
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = LayoutHelper.dp(14f).toFloat()
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        fun applyTheme(themeColors: ThemeColors) {
            textPaint.color = themeColors.onSurface
        }
    }
}
