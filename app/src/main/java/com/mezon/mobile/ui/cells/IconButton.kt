package com.mezon.mobile.ui.cells

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.view.View
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

class IconButton(context: Context, private val theme: ThemeColors) : View(context) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var icon: Drawable? = null
    private val btnSize = LayoutHelper.dp(40)
    private val iconSize = LayoutHelper.dp(24)
    var showBackground = true
        set(value) {
            field = value
            invalidate()
        }

    init {
        isClickable = true
        isFocusable = true
    }

    fun setIcon(drawable: Drawable) {
        icon = drawable.mutate()
        icon?.colorFilter = PorterDuffColorFilter(theme.onSurfaceVariant, PorterDuff.Mode.SRC_IN)
        invalidate()
    }

    fun setIcon(@DrawableRes resId: Int) {
        setIcon(ContextCompat.getDrawable(context, resId)!!)
    }

    fun setIcon(mezonIcon: MezonIcon) {
        setIcon(mezonIcon.resId)
    }

    fun setIconTint(color: Int) {
        icon?.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(btnSize, btnSize)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f

        if (showBackground) {
            bgPaint.color = theme.surfaceVariant
            canvas.drawCircle(cx, cy, btnSize / 2f, bgPaint)
        }

        icon?.let { d ->
            val left = (width - iconSize) / 2
            val top = (height - iconSize) / 2
            d.setBounds(left, top, left + iconSize, top + iconSize)
            d.draw(canvas)
        }
    }
}
