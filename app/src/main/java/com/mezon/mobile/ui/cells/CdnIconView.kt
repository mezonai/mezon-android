package com.mezon.mobile.ui.cells

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.view.View
import coil.ImageLoader
import coil.request.ImageRequest
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

class CdnIconView(context: Context, private val theme: ThemeColors) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var bitmap: Bitmap? = null
    private var sizeDp = 24
    private var isCircular = false
    private var currentUrl: String? = null

    fun setSizeDp(dp: Int) {
        sizeDp = dp
        requestLayout()
    }

    fun setCircular(circular: Boolean) {
        isCircular = circular
        invalidate()
    }

    fun setImageUrl(url: String?, loader: ImageLoader) {
        if (url == currentUrl) return
        currentUrl = url
        bitmap = null
        if (url.isNullOrEmpty()) {
            invalidate()
            return
        }
        val px = LayoutHelper.dp(sizeDp)
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(px)
            .target(
                onSuccess = { result ->
                    bitmap = (result as? BitmapDrawable)?.bitmap
                    invalidate()
                },
                onError = {
                    bitmap = null
                    invalidate()
                }
            )
            .build()
        loader.enqueue(request)
    }

    fun setBitmap(bmp: Bitmap?) {
        bitmap = bmp
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = LayoutHelper.dp(sizeDp)
        setMeasuredDimension(size, size)
    }

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
