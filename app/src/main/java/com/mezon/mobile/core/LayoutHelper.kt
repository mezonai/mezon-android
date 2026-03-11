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
import android.widget.ScrollView
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

object LayoutHelper {

    const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT

    private val density: Float get() = AndroidUtilities.density

    fun dp(value: Float): Int =
        if (value == 0f) 0
        else ceil(density * value.toDouble()).toInt()

    fun dp(value: Int): Int = dp(value.toFloat())

    fun dpr(value: Float): Int =
        if (value == 0f) 0
        else (density * value).roundToInt()

    fun dp2(value: Float): Int =
        if (value == 0f) 0
        else floor(density * value.toDouble()).toInt()

    fun dpf(value: Float): Float =
        if (value == 0f) 0f
        else density * value

    fun sp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value,
        Resources.getSystem().displayMetrics
    )

    private fun getSize(size: Float): Int =
        if (size < 0) size.toInt() else dp(size)

    fun getAbsoluteGravityStart(): Int =
        if (AndroidUtilities.isRTL) Gravity.RIGHT else Gravity.LEFT

    fun getAbsoluteGravityEnd(): Int =
        if (AndroidUtilities.isRTL) Gravity.LEFT else Gravity.RIGHT

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
            getSize(width.toFloat()),
            getSize(height.toFloat()),
            gravity
        )
        lp.setMargins(dp(leftMargin), dp(topMargin), dp(rightMargin), dp(bottomMargin))
        return lp
    }

    fun createFrameRelatively(
        width: Float,
        height: Float,
        gravity: Int = Gravity.NO_GRAVITY,
        startMargin: Float = 0f,
        topMargin: Float = 0f,
        endMargin: Float = 0f,
        bottomMargin: Float = 0f
    ): FrameLayout.LayoutParams {
        val lp = FrameLayout.LayoutParams(getSize(width), getSize(height), gravity)
        if (AndroidUtilities.isRTL) {
            lp.setMargins(dp(endMargin), dp(topMargin), dp(startMargin), dp(bottomMargin))
        } else {
            lp.setMargins(dp(startMargin), dp(topMargin), dp(endMargin), dp(bottomMargin))
        }
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
            getSize(width.toFloat()),
            getSize(height.toFloat()),
            weight
        )
        lp.gravity = gravity
        lp.setMargins(dp(leftMargin), dp(topMargin), dp(rightMargin), dp(bottomMargin))
        return lp
    }

    fun createLinearRelatively(
        width: Float,
        height: Float,
        weight: Float = 0f,
        gravity: Int = Gravity.NO_GRAVITY,
        startMargin: Float = 0f,
        topMargin: Float = 0f,
        endMargin: Float = 0f,
        bottomMargin: Float = 0f
    ): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(getSize(width), getSize(height), weight)
        lp.gravity = gravity
        if (AndroidUtilities.isRTL) {
            lp.setMargins(dp(endMargin), dp(topMargin), dp(startMargin), dp(bottomMargin))
        } else {
            lp.setMargins(dp(startMargin), dp(topMargin), dp(endMargin), dp(bottomMargin))
        }
        return lp
    }

    fun createScroll(
        width: Int,
        height: Int,
        gravity: Int = Gravity.NO_GRAVITY
    ): FrameLayout.LayoutParams {
        val lp = FrameLayout.LayoutParams(getSize(width.toFloat()), getSize(height.toFloat()))
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

    fun View.setPaddingRelativeDp(start: Int = 0, top: Int = 0, end: Int = 0, bottom: Int = 0) {
        if (AndroidUtilities.isRTL) {
            setPadding(dp(end), dp(top), dp(start), dp(bottom))
        } else {
            setPadding(dp(start), dp(top), dp(end), dp(bottom))
        }
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

    fun createFrame(
        width: Float,
        height: Float,
        gravity: Int = Gravity.NO_GRAVITY,
        leftMargin: Float = 0f,
        topMargin: Float = 0f,
        rightMargin: Float = 0f,
        bottomMargin: Float = 0f
    ): FrameLayout.LayoutParams {
        return createFrame(width.toInt(), height.toInt(), gravity, leftMargin, topMargin, rightMargin, bottomMargin)
    }

    fun createLinear(
        width: Float,
        height: Float,
        weight: Float = 0f,
        gravity: Int = Gravity.NO_GRAVITY,
        leftMargin: Float = 0f,
        topMargin: Float = 0f,
        rightMargin: Float = 0f,
        bottomMargin: Float = 0f
    ): LinearLayout.LayoutParams {
        return createLinear(width.toInt(), height.toInt(), weight, gravity, leftMargin, topMargin, rightMargin, bottomMargin)
    }

    fun measureSpecExactly(px: Int): Int =
        View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY)

    fun measureSpecExactlyDp(dp: Int): Int =
        View.MeasureSpec.makeMeasureSpec(dp(dp), View.MeasureSpec.EXACTLY)

    fun measureSpecAtMost(px: Int): Int =
        View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.AT_MOST)

    fun measureSpecUnspecified(): Int =
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
}
