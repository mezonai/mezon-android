package com.mezon.mobile.home.voice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.ui.cells.MezonIcon
import kotlin.math.min

class VoiceNetworkWarningHintView(context: Context, message: String) : LinearLayout(context) {

    companion object {
        private const val FILL_COLOR = 0xFFFDE8D7.toInt()
        private const val TEXT_COLOR = 0xFF202124.toInt()
        private const val CLOSE_TINT = 0xB3202124.toInt()
        private val SHADOW_INSET = LayoutHelper.dp(8)
        private val MAX_BUBBLE_WIDTH = LayoutHelper.dp(360)
        private val ARROW_WIDTH = LayoutHelper.dp(12)
        private val ARROW_HEIGHT = LayoutHelper.dp(6)
        private val ARROW_EDGE = LayoutHelper.dp(20)
    }

    var onDismiss: (() -> Unit)? = null

    private val arrowView = ArrowView(context)
    private var arrowCenterXInParent = Float.NaN

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        setPadding(SHADOW_INSET, SHADOW_INSET, SHADOW_INSET, 0)

        val bubble = LinearLayout(context).apply {
            orientation = HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(16).toFloat()
                setColor(FILL_COLOR)
            }
            elevation = LayoutHelper.dp(6).toFloat()
            setPadding(LayoutHelper.dp(16), LayoutHelper.dp(16), LayoutHelper.dp(12), LayoutHelper.dp(16))
        }
        val messageView = TextView(context).apply {
            text = message
            textSize = 14f
            setTextColor(TEXT_COLOR)
        }
        bubble.addView(messageView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            gravity = Gravity.CENTER_VERTICAL
        })
        val closeButton = ImageView(context).apply {
            setImageDrawable(MezonIcon.closeSmallBold.getDrawable(context).mutate().apply {
                colorFilter = PorterDuffColorFilter(CLOSE_TINT, PorterDuff.Mode.SRC_IN)
            })
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val inset = LayoutHelper.dp(4)
            setPadding(inset, inset, inset, inset)
            contentDescription = context.getString(R.string.common_close)
            isClickable = true
            isFocusable = true
            setOnClickListener { onDismiss?.invoke() }
        }
        bubble.addView(closeButton, LayoutParams(LayoutHelper.dp(24), LayoutHelper.dp(24)).apply {
            marginStart = LayoutHelper.dp(12)
            gravity = Gravity.TOP
        })
        addView(bubble, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(arrowView, LayoutParams(ARROW_WIDTH, ARROW_HEIGHT))
    }

    fun setArrowCenterX(xInParent: Float) {
        arrowCenterXInParent = xInParent
        applyArrowPosition()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = min(MeasureSpec.getSize(widthMeasureSpec), MAX_BUBBLE_WIDTH + SHADOW_INSET * 2)
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        applyArrowPosition()
    }

    private fun applyArrowPosition() {
        if (arrowCenterXInParent.isNaN()) return
        val minX = (paddingLeft + ARROW_EDGE).toFloat()
        val maxX = (width - paddingRight - ARROW_EDGE - ARROW_WIDTH).toFloat()
        if (maxX < minX) return
        val x = (arrowCenterXInParent - left - ARROW_WIDTH / 2f).coerceIn(minX, maxX)
        arrowView.translationX = x - arrowView.left
    }

    private class ArrowView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FILL_COLOR }
        private val path = Path()

        override fun onDraw(canvas: Canvas) {
            path.reset()
            path.moveTo(0f, 0f)
            path.lineTo(width.toFloat(), 0f)
            path.lineTo(width / 2f, height.toFloat())
            path.close()
            canvas.drawPath(path, paint)
        }
    }
}
