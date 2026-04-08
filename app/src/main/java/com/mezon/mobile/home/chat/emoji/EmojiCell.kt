package com.mezon.mobile.home.chat.emoji

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.SharedConfig
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.chat.EmojiItem
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.util.getEmojiUrl

private val CELL_SIZE = LayoutHelper.dp(50f)
private val IMAGE_SIZE = LayoutHelper.dp(30f)
private val LOCK_SIZE = LayoutHelper.dp(12f)
private val LOCK_PAD = LayoutHelper.dp(2f)

class EmojiCell(context: Context, private val themeColors: ThemeColors) : View(context) {

    private var emoji: EmojiItem? = null
    private var bitmap: Bitmap? = null
    private var animDrawable: Drawable? = null
    private var isLocked = false
    private var cancellable: MezonImageLoader.Cancellable? = null
    private var lockDrawable: Drawable? = null
    private val loader = MezonImageLoader.getInstance(context)

    private val srcRect = Rect()
    private val dstRect = Rect()

    private val drawableCallback = object : Drawable.Callback {
        override fun invalidateDrawable(who: Drawable) { invalidate() }
        override fun scheduleDrawable(who: Drawable, what: Runnable, w: Long) {}
        override fun unscheduleDrawable(who: Drawable, what: Runnable) {}
    }

    init {
        layoutParams = FrameLayout.LayoutParams(CELL_SIZE, CELL_SIZE, Gravity.CENTER)
        isClickable = true
        isFocusable = true
        val attrs = intArrayOf(android.R.attr.selectableItemBackgroundBorderless)
        val ta = context.obtainStyledAttributes(attrs)
        foreground = ta.getDrawable(0)
        ta.recycle()
    }

    fun setEmoji(item: EmojiItem) {
        if (emoji?.id == item.id) return
        cancellable?.cancel()
        bitmap = null
        stopAnimation()
        animDrawable = null
        emoji = item
        isLocked = item.isForSale && item.src.isBlank()

        if (item.id.isNotBlank()) {
            val url = getEmojiUrl(item.id)
            if (url != null) {
                val cached = loader.getBitmapFromMemory(url, IMAGE_SIZE, IMAGE_SIZE)
                if (cached != null) {
                    bitmap = cached
                }
                if (SharedConfig.deviceIsLow()) {
                    cancellable = loader.load(url, IMAGE_SIZE, IMAGE_SIZE,
                        onSuccess = { bmp ->
                            bitmap = bmp
                            invalidate()
                        })
                } else {
                    cancellable = loader.loadDrawable(url, IMAGE_SIZE, IMAGE_SIZE,
                        onSuccess = { d ->
                            animDrawable = d
                            d.callback = drawableCallback
                            if (d is android.graphics.drawable.AnimatedImageDrawable) {
                                d.start()
                            }
                            invalidate()
                        })
                }
            }
        }
        invalidate()
    }

    override fun invalidate() {
        if (emoji == null) return
        super.invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(CELL_SIZE, CELL_SIZE)
    }

    override fun onDraw(canvas: Canvas) {
        val left = (measuredWidth - IMAGE_SIZE) / 2
        val top = (measuredHeight - IMAGE_SIZE) / 2

        val d = animDrawable
        if (d != null) {
            canvas.save()
            canvas.translate(left.toFloat(), top.toFloat())
            d.setBounds(0, 0, IMAGE_SIZE, IMAGE_SIZE)
            d.draw(canvas)
            canvas.restore()
        } else {
            val bmp = bitmap ?: return
            srcRect.set(0, 0, bmp.width, bmp.height)
            dstRect.set(left, top, left + IMAGE_SIZE, top + IMAGE_SIZE)
            canvas.drawBitmap(bmp, srcRect, dstRect, bitmapPaint)
        }

        if (isLocked) {
            val ld = lockDrawable ?: run {
                MezonIcon.lockIcon.getDrawable(context).mutate().also {
                    it.colorFilter = PorterDuffColorFilter(themeColors.onSurfaceVariant, PorterDuff.Mode.SRC_IN)
                    lockDrawable = it
                }
            }
            val lx = left + IMAGE_SIZE - LOCK_SIZE - LOCK_PAD
            val ly = top + IMAGE_SIZE - LOCK_SIZE - LOCK_PAD
            ld.setBounds(lx, ly, lx + LOCK_SIZE, ly + LOCK_SIZE)
            ld.draw(canvas)
        }
    }

    private fun stopAnimation() {
        val d = animDrawable
        if (d is android.graphics.drawable.AnimatedImageDrawable) {
            d.stop()
        }
        d?.callback = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancellable?.cancel()
        cancellable = null
        stopAnimation()
        animDrawable = null
    }

    companion object {
        private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    }
}
