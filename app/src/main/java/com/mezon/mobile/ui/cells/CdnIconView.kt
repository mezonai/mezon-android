package com.mezon.mobile.ui.cells

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.chat.MezonImageLoader

class CdnIconView(context: Context, private val theme: ThemeColors) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var bitmap: Bitmap? = null
    private var sizeDp = 24
    private var isCircular = false
    private var currentUrl: String? = null
    private var cancellable: MezonImageLoader.Cancellable? = null
    private var attachedToWindow = false

    fun setSizeDp(dp: Int) {
        sizeDp = dp
        requestLayout()
    }

    fun setCircular(circular: Boolean) {
        isCircular = circular
        invalidate()
    }

    fun setImageUrl(url: String?) {
        if (url == currentUrl) return
        currentUrl = url
        cancellable?.cancel()
        cancellable = null
        bitmap = null
        if (url.isNullOrEmpty()) {
            invalidate()
            return
        }
        if (!attachedToWindow) return
        loadImage(url)
    }

    private fun loadImage(url: String) {
        val px = LayoutHelper.dp(sizeDp)
        cancellable = MezonImageLoader.getInstance(context).load(
            url, px, px,
            onSuccess = { bmp ->
                bitmap = bmp
                invalidate()
            },
            onError = {
                bitmap = null
                invalidate()
            }
        )
    }

    fun setBitmap(bmp: Bitmap?) {
        bitmap = bmp
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachedToWindow = true
        val url = currentUrl
        if (url != null && bitmap == null && cancellable == null) {
            loadImage(url)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        attachedToWindow = false
        cancellable?.cancel()
        cancellable = null
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = LayoutHelper.dp(sizeDp)
        setMeasuredDimension(size, size)
    }

    override fun hasOverlappingRendering(): Boolean = false

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap
        if (bmp != null && !bmp.isRecycled) {
            if (isCircular) {
                val scale = width.toFloat() / bmp.width
                val shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                val matrix = Matrix()
                matrix.setScale(scale, scale)
                shader.setLocalMatrix(matrix)
                paint.shader = shader
                canvas.drawCircle(width / 2f, height / 2f, width / 2f, paint)
                paint.shader = null
            } else {
                val src = android.graphics.Rect(0, 0, bmp.width, bmp.height)
                val dst = android.graphics.Rect(0, 0, width, height)
                canvas.drawBitmap(bmp, src, dst, paint)
            }
        } else {
            placeholderPaint.color = theme.surfaceVariant
            if (isCircular) {
                canvas.drawCircle(width / 2f, height / 2f, width / 2f, placeholderPaint)
            } else {
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), placeholderPaint)
            }
        }
    }
}
