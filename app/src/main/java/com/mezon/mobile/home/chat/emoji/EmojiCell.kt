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
import android.view.MotionEvent
import android.widget.FrameLayout
import com.mezon.mobile.core.BaseCell
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.SharedConfig
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.chat.EmojiItem
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.util.getEmojiDirectUrl
import com.mezon.mobile.util.getEmojiUrl

private val CELL_SIZE = LayoutHelper.dp(50f)
private val IMAGE_SIZE = LayoutHelper.dp(30f)
private val LOCK_SIZE = LayoutHelper.dp(12f)
private val LOCK_PAD = LayoutHelper.dp(2f)

class EmojiCell(context: Context, private val themeColors: ThemeColors) : BaseCell(context) {

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
        setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> alpha = 0.7f
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> alpha = 1f
            }
            false
        }
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
                        },
                        onError = errLow@{
                            val direct = getEmojiDirectUrl(item.id) ?: return@errLow
                            if (direct == url) return@errLow
                            cancellable = loader.load(direct, IMAGE_SIZE, IMAGE_SIZE,
                                onSuccess = { bmp ->
                                    bitmap = bmp
                                    invalidate()
                                },
                                onError = { invalidate() })
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
                        },
                        onError = errDrawable@{
                            val direct = getEmojiDirectUrl(item.id) ?: run {
                                invalidate()
                                return@errDrawable
                            }
                            if (direct == url) {
                                invalidate()
                                return@errDrawable
                            }
                            cancellable = loader.loadDrawable(direct, IMAGE_SIZE, IMAGE_SIZE,
                                onSuccess = { d ->
                                    animDrawable = d
                                    d.callback = drawableCallback
                                    if (d is android.graphics.drawable.AnimatedImageDrawable) {
                                        d.start()
                                    }
                                    invalidate()
                                },
                                onError = {
                                    cancellable = loader.load(direct, IMAGE_SIZE, IMAGE_SIZE,
                                        onSuccess = { bmp ->
                                            bitmap = bmp
                                            invalidate()
                                        },
                                        onError = { invalidate() })
                                })
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
        val specMode = MeasureSpec.getMode(widthMeasureSpec)
        val w = if (specMode == MeasureSpec.EXACTLY) MeasureSpec.getSize(widthMeasureSpec) else CELL_SIZE
        setMeasuredDimension(w, w)
    }

    override fun onDraw(canvas: Canvas) {
        val d = animDrawable
        val bmp = bitmap
        
        var imgW = IMAGE_SIZE
        var imgH = IMAGE_SIZE
        
        if (d != null) {
            val iw = d.intrinsicWidth.toFloat()
            val ih = d.intrinsicHeight.toFloat()
            if (iw > 0 && ih > 0) {
                val ratio = iw / ih
                imgW = if (ratio > 1f) IMAGE_SIZE else (IMAGE_SIZE * ratio).toInt()
                imgH = if (ratio < 1f) IMAGE_SIZE else (IMAGE_SIZE / ratio).toInt()
            }
        } else if (bmp != null) {
            val iw = bmp.width.toFloat()
            val ih = bmp.height.toFloat()
            if (iw > 0 && ih > 0) {
                val ratio = iw / ih
                imgW = if (ratio > 1f) IMAGE_SIZE else (IMAGE_SIZE * ratio).toInt()
                imgH = if (ratio < 1f) IMAGE_SIZE else (IMAGE_SIZE / ratio).toInt()
            }
        }

        val left = (measuredWidth - imgW) / 2
        val top = (measuredHeight - imgH) / 2

        if (d != null) {
            canvas.save()
            canvas.translate(left.toFloat(), top.toFloat())
            d.setBounds(0, 0, imgW, imgH)
            d.draw(canvas)
            canvas.restore()
        } else if (bmp != null) {
            srcRect.set(0, 0, bmp.width, bmp.height)
            dstRect.set(left, top, left + imgW, top + imgH)
            canvas.drawBitmap(bmp, srcRect, dstRect, bitmapPaint)
        }

        if (isLocked) {
            val ld = lockDrawable ?: run {
                MezonIcon.lockIcon.getDrawable(context).mutate().also {
                    it.colorFilter = PorterDuffColorFilter(themeColors.onSurfaceVariant, PorterDuff.Mode.SRC_IN)
                    lockDrawable = it
                }
            }
            val lx = left + imgW - LOCK_SIZE - LOCK_PAD
            val ly = top + imgH - LOCK_SIZE - LOCK_PAD
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

    private fun restartAnimation() {
        val d = animDrawable ?: return
        d.callback = drawableCallback
        if (d is android.graphics.drawable.AnimatedImageDrawable && !d.isRunning) {
            d.start()
        }
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        restartAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancellable?.cancel()
        cancellable = null
        stopAnimation()
        animDrawable = null
    }

    override fun allowCaching(): Boolean = false

    companion object {
        private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    }
}
