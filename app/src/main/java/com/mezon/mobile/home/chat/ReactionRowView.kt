package com.mezon.mobile.home.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.animation.ValueAnimator
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

class ReactionRowView(context: Context, private val theme: ThemeColors) : View(context) {

    var onReactionClick: ((chip: ReactionChip) -> Unit)? = null
    var onReactionChipLongPressed: ((chip: ReactionChip) -> Unit)? = null

    private var chips: List<ReactionChip> = emptyList()
    private var chipBitmaps: Array<Bitmap?> = emptyArray()
    private var chipLoads: Array<MezonImageLoader.Cancellable?> = emptyArray()

    data class ChipLayout(val left: Float, val top: Float, val w: Float, val h: Float)
    private var chipLayouts: List<ChipLayout> = emptyList()
    private var totalHeight = 0

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bgMinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = LayoutHelper.dpf(1.5f)
    }
    private val countPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.dpf(12f)
        typeface = Typeface.DEFAULT_BOLD
    }

    private val chipH = LayoutHelper.dpf(28f)       // height of each chip
    private val imgSize = LayoutHelper.dpf(18f)      // emoji image size
    private val padH = LayoutHelper.dpf(7f)          // horizontal padding inside chip
    private val padV = LayoutHelper.dpf(5f)          // vertical padding inside chip (top/bot)
    private val gapH = LayoutHelper.dpf(6f)          // gap between chips horizontally
    private val gapV = LayoutHelper.dpf(4f)          // gap between rows vertically
    private val cornerR = chipH / 2f                 // fully rounded corners
    private val imgTextGap = LayoutHelper.dpf(4f)    // gap between emoji image and count text
    private val tmpRect = RectF()

    private var pressedIndex = -1

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                val idx = hitTest(e.x, e.y)
                if (idx >= 0) {
                    pressedIndex = -1
                    invalidate()
                    onReactionChipLongPressed?.invoke(chips[idx])
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                }
            }
        }
    )

    fun bind(newChips: List<ReactionChip>) {
        if (newChips == chips) return
        chipLoads.forEach { it?.cancel() }
        chips = newChips
        chipBitmaps = arrayOfNulls(newChips.size)
        chipLoads = arrayOfNulls(newChips.size)

        val loader = MezonImageLoader.getInstance(context)
        val px = imgSize.toInt()
        for ((i, chip) in newChips.withIndex()) {
            val url = chip.emojiSrc.ifBlank { getSrcEmoji(chip.emojiId) }
            val cached = loader.getBitmapFromMemory(url, px, px)
            if (cached != null) {
                chipBitmaps[i] = cached
            } else {
                val idx = i
                chipLoads[idx] = loader.load(url, px, px,
                    onSuccess = { bmp ->
                        chipBitmaps[idx] = bmp
                        post { invalidate() }
                    },
                    onError = {}
                )
            }
        }

        requestLayout()
        invalidate()
    }

    fun clear() {
        chipLoads.forEach { it?.cancel() }
        chips = emptyList()
        chipBitmaps = emptyArray()
        chipLoads = emptyArray()
        chipLayouts = emptyList()
        totalHeight = 0
        requestLayout()
        invalidate()
    }

    private fun calcChipWidth(chip: ReactionChip): Float {
        val count = if (chip.count <= 0) 1 else chip.count
        val countText = "$count"
        val textW = countPaint.measureText(countText)
        return padH + imgSize + imgTextGap + textW + padH
    }

    private fun layoutChips(availableW: Int) {
        if (availableW <= 0 || chips.isEmpty()) {
            chipLayouts = emptyList()
            totalHeight = 0
            return
        }
        val layouts = ArrayList<ChipLayout>(chips.size)
        var x = 0f
        var y = 0f
        for (chip in chips) {
            val cw = calcChipWidth(chip)
            if (x + cw > availableW && x > 0) {
                x = 0f
                y += chipH + gapV
            }
            layouts.add(ChipLayout(x, y, cw, chipH))
            x += cw + gapH
        }
        chipLayouts = layouts
        totalHeight = (y + chipH).toInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        layoutChips(w)
        setMeasuredDimension(w, totalHeight)
    }

    override fun onDraw(canvas: Canvas) {
        val isDark = theme.resolvedMode != com.mezon.mobile.ui.theme.ThemeMode.LIGHT

        // Colors based on theme
        val bgColor   = if (isDark) 0xFF313338.toInt() else 0xFFE9E9EB.toInt()
        val bgMine    = if (isDark) 0xFF4E5057.toInt() else 0xFFD1D3F0.toInt()
        val borderCol = if (isDark) 0xFF8B8FF0.toInt() else 0xFF5865F2.toInt()
        val textColor = if (isDark) 0xFFDCDDDE.toInt() else 0xFF2E3338.toInt()

        bgPaint.color = bgColor
        bgMinePaint.color = bgMine
        borderPaint.color = borderCol
        countPaint.color = textColor

        for (i in chips.indices) {
            val chip = chips[i]
            val cl = chipLayouts.getOrNull(i) ?: continue

            val isPressed = pressedIndex == i

            // Draw background
            tmpRect.set(cl.left, cl.top, cl.left + cl.w, cl.top + cl.h)
            val paint = if (chip.isMine) bgMinePaint else bgPaint
            canvas.drawRoundRect(tmpRect, cornerR, cornerR, paint)

            // Draw border (isMine or pressed)
            if (chip.isMine || isPressed) {
                canvas.drawRoundRect(tmpRect, cornerR, cornerR, borderPaint)
            }

            // Draw emoji image
            val imgLeft = cl.left + padH
            val imgTop  = cl.top + (cl.h - imgSize) / 2f
            val bmp = chipBitmaps.getOrNull(i)
            if (bmp != null && !bmp.isRecycled) {
                tmpRect.set(imgLeft, imgTop, imgLeft + imgSize, imgTop + imgSize)
                canvas.drawBitmap(bmp, null, tmpRect, null)
            } else {
                // Fallback: draw a circle placeholder
                val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (isDark) 0xFF555555.toInt() else 0xFFCCCCCC.toInt()
                    style = Paint.Style.FILL
                }
                canvas.drawCircle(
                    imgLeft + imgSize / 2, imgTop + imgSize / 2,
                    imgSize / 2, placeholderPaint
                )
            }

            // Draw count text
            val count = if (chip.count <= 0) 1 else chip.count
            val countText = "$count"
            val textX = imgLeft + imgSize + imgTextGap
            val textY = cl.top + cl.h / 2f - (countPaint.descent() + countPaint.ascent()) / 2f
            canvas.drawText(countText, textX, textY, countPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Let gesture detector handle long press first
        gestureDetector.onTouchEvent(event)
        val x = event.x
        val y = event.y
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                pressedIndex = hitTest(x, y)
                if (pressedIndex >= 0) {
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                val idx = pressedIndex
                pressedIndex = -1
                invalidate()
                if (idx >= 0 && hitTest(x, y) == idx) {
                    // Hiệu ứng emoji bay lên khi tap reaction
                    launchFloatingEmoji(chips[idx], chipLayouts[idx])
                    onReactionClick?.invoke(chips[idx])
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedIndex = -1
                invalidate()
            }
        }
        return pressedIndex >= 0 || super.onTouchEvent(event)
    }

    private fun hitTest(x: Float, y: Float): Int {
        for (i in chipLayouts.indices) {
            val cl = chipLayouts[i]
            if (x >= cl.left && x <= cl.left + cl.w && y >= cl.top && y <= cl.top + cl.h) {
                return i
            }
        }
        return -1
    }

    private fun launchFloatingEmoji(chip: ReactionChip, cl: ChipLayout) {
        val activity = context as? android.app.Activity ?: return
        val root = activity.window.decorView as? ViewGroup ?: return
        val bmpIdx = chips.indexOf(chip)
        val bmp = chipBitmaps.getOrNull(bmpIdx) ?: return

        val locThis = IntArray(2); getLocationOnScreen(locThis)
        val locRoot = IntArray(2); root.getLocationOnScreen(locRoot)

        val chipCenterX = (locThis[0] - locRoot[0]) + cl.left + cl.w / 2f
        val chipCenterY = (locThis[1] - locRoot[1]) + cl.top + cl.h / 2f

        val emojiPx = LayoutHelper.dp(40f)

        val iv = android.widget.ImageView(context).apply {
            setImageBitmap(bmp)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            isClickable = false
            isFocusable = false
        }
        root.addView(iv, ViewGroup.LayoutParams(emojiPx, emojiPx))
        iv.x = chipCenterX - emojiPx / 2f
        iv.y = chipCenterY - emojiPx / 2f
        iv.translationX = 0f
        iv.translationY = 0f
        iv.alpha = 1f
        iv.scaleX = 1f
        iv.scaleY = 1f

        val randomX1 = (Math.random() * 60 - 30).toFloat()
        val randomX2 = (Math.random() * 40 - 20).toFloat()
        val randomY  = -(LayoutHelper.dpf(180f) + (Math.random() * LayoutHelper.dpf(60f)).toFloat())

        val duration = 1200L

        val animY = ValueAnimator.ofFloat(0f, randomY).apply {
            duration = duration
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { iv.translationY = it.animatedValue as Float }
        }
        val animX1 = ValueAnimator.ofFloat(0f, randomX1).apply {
            duration = 600
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { iv.translationX = it.animatedValue as Float }
        }
        val animX2 = ValueAnimator.ofFloat(randomX1, randomX2).apply {
            duration = 600
            startDelay = 600
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { iv.translationX = it.animatedValue as Float }
        }
        val animS1 = ValueAnimator.ofFloat(1f, 1.3f).apply {
            duration = 400
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { v -> iv.scaleX = v.animatedValue as Float; iv.scaleY = v.animatedValue as Float }
        }
        val animS2 = ValueAnimator.ofFloat(1.3f, 0.6f).apply {
            duration = 800
            startDelay = 400
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { v -> iv.scaleX = v.animatedValue as Float; iv.scaleY = v.animatedValue as Float }
        }
        val animA = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = duration
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { iv.alpha = it.animatedValue as Float }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    root.removeView(iv)
                }
            })
        }
        listOf(animY, animX1, animX2, animS1, animS2, animA).forEach { it.start() }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        chipLoads.forEach { it?.cancel() }
        chipLoads = emptyArray()
    }
}
