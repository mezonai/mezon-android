package com.mezon.mobile.home.chat.emoji

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.SharedConfig
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.home.chat.StickerItem
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.util.getStickerImageUrl

private val CELL_SIZE = LayoutHelper.dp(72f)
private val IMAGE_SIZE = LayoutHelper.dp(64f)
private val LOCK_SIZE = LayoutHelper.dp(14f)
private val LOCK_PAD = LayoutHelper.dp(4f)

class StickerCell(context: Context, private val themeColors: ThemeColors) : View(context) {

    private var sticker: StickerItem? = null
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

    fun getSticker(): StickerItem? = sticker

    fun setSticker(item: StickerItem) {
        if (sticker?.id == item.id) return
        cancellable?.cancel()
        bitmap = null
        stopAnimation()
        animDrawable = null
        sticker = item
        isLocked = item.isForSale && item.src.isBlank()

        val url = getStickerImageUrl(item.id, item.src)
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
        invalidate()
    }

    override fun invalidate() {
        if (sticker == null) return
        super.invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(size, CELL_SIZE)
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
