package com.mezon.mobile.core

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout

object LayoutHelper {

    const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT

    fun dp(value: Float): Int =
        if (value == 0f) 0
        else Math.ceil((Resources.getSystem().displayMetrics.density * value).toDouble()).toInt()

    fun dp(value: Int): Int = dp(value.toFloat())

    fun dpf(value: Float): Float = Resources.getSystem().displayMetrics.density * value

    fun sp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value,
        Resources.getSystem().displayMetrics
    )

    fun createFrame(
        width: Int,
        height: Int,
        gravity: Int = Gravity.NO_GRAVITY,
        leftMargin: Float = 0f,
        topMargin: Float = 0f,
        rightMargin: Float = 0f,
        bottomMargin: Float = 0f
    ): FrameLayout.LayoutParams {
        val lp = FrameLayout.LayoutParams(
            if (width >= 0) dp(width) else width,
            if (height >= 0) dp(height) else height,
            gravity
        )
        lp.setMargins(dp(leftMargin), dp(topMargin), dp(rightMargin), dp(bottomMargin))
        return lp
    }

    fun createLinear(
        width: Int,
        height: Int,
        weight: Float = 0f,
        gravity: Int = Gravity.NO_GRAVITY,
        leftMargin: Float = 0f,
        topMargin: Float = 0f,
        rightMargin: Float = 0f,
        bottomMargin: Float = 0f
    ): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(
            if (width >= 0) dp(width) else width,
            if (height >= 0) dp(height) else height,
            weight
        )
        lp.gravity = gravity
        lp.setMargins(dp(leftMargin), dp(topMargin), dp(rightMargin), dp(bottomMargin))
        return lp
    }

    fun newTextPaint(textSizeSp: Float, color: Int, typeface: Typeface = Typeface.DEFAULT): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = sp(textSizeSp)
            this.color = color
            this.typeface = typeface
        }

    fun View.setPaddingDp(left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0) {
        setPadding(dp(left), dp(top), dp(right), dp(bottom))
    }

    fun drawableToBitmap(drawable: Drawable, size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(c)
        return bitmap
    }

    fun drawableToBitmap(drawable: Drawable, width: Int, height: Int): Bitmap {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(c)
        return bitmap
    }
}
