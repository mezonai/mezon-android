package com.mezon.mobile.home.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import coil.Coil
import coil.request.ImageRequest
import coil.size.Size

class ImageReceiver(private val parentView: View) {

    private var imageBitmap: Bitmap? = null
    private var thumbBitmap: Bitmap? = null
    private var currentUrl: String? = null
    private var currentThumbUrl: String? = null

    private var imageX = 0f
    private var imageY = 0f
    private var imageW = 0f
    private var imageH = 0f
    private val roundRadius = IntArray(4)

    private val roundPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shaderMatrix = Matrix()
    private val roundPath = Path()
    private val roundRect = RectF()
    private val drawRegion = RectF()
    private val radii = FloatArray(8)

    private var cachedShader: BitmapShader? = null
    private var cachedShaderBitmap: Bitmap? = null

    fun setImageCoords(x: Float, y: Float, w: Float, h: Float) {
        imageX = x
        imageY = y
        imageW = w
        imageH = h
    }

    fun setRoundRadius(radius: Int) {
        roundRadius.fill(radius)
    }

    fun setImage(url: String?, thumbUrl: String?, context: Context) {
        val urlChanged = url != currentUrl
        val thumbChanged = thumbUrl != currentThumbUrl

        if (!urlChanged && !thumbChanged) return

        if (thumbChanged && thumbUrl != null) {
            currentThumbUrl = thumbUrl
            loadBitmap(thumbUrl, context, isThumb = true)
        }

        if (urlChanged && url != null) {
            if (urlChanged) {
                imageBitmap = null
                cachedShader = null
                cachedShaderBitmap = null
            }
            currentUrl = url
            loadBitmap(url, context, isThumb = false)
        }
    }

    private fun loadBitmap(url: String, context: Context, isThumb: Boolean) {
        if (url.isEmpty()) return
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(Size.ORIGINAL)
            .allowHardware(false)
            .target(onSuccess = { drawable ->
                val bmp = when (drawable) {
                    is android.graphics.drawable.BitmapDrawable -> drawable.bitmap
                    else -> {
                        val w = drawable.intrinsicWidth.coerceAtLeast(1)
                        val h = drawable.intrinsicHeight.coerceAtLeast(1)
                        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        val c = Canvas(b)
                        drawable.setBounds(0, 0, w, h)
                        drawable.draw(c)
                        b
                    }
                }
                if (isThumb) {
                    if (imageBitmap == null) {
                        thumbBitmap = bmp
                        parentView.post { parentView.invalidate() }
                    }
                } else {
                    imageBitmap = bmp
                    thumbBitmap = null
                    cachedShader = null
                    cachedShaderBitmap = null
                    parentView.post { parentView.invalidate() }
                }
            })
            .build()
        Coil.imageLoader(context).enqueue(request)
    }

    fun draw(canvas: Canvas): Boolean {
        val bmp = imageBitmap ?: thumbBitmap ?: return false
        if (imageW <= 0f || imageH <= 0f) return false

        val bmpW = bmp.width.toFloat()
        val bmpH = bmp.height.toFloat()
        if (bmpW <= 0f || bmpH <= 0f) return false

        val hasRound = roundRadius.any { it > 0 }

        val scaleW = bmpW / imageW
        val scaleH = bmpH / imageH
        val scale = 1f / minOf(scaleW, scaleH)

        val scaledW = bmpW * scale
        val scaledH = bmpH * scale
        drawRegion.set(
            imageX - (scaledW - imageW) / 2f,
            imageY - (scaledH - imageH) / 2f,
            imageX + (scaledW + imageW) / 2f,
            imageY + (scaledH + imageH) / 2f
        )

        if (hasRound) {
            val shader = getOrCreateShader(bmp)
            shaderMatrix.reset()
            shaderMatrix.setTranslate(drawRegion.left, drawRegion.top)
            shaderMatrix.preScale(scale, scale)
            shader.setLocalMatrix(shaderMatrix)
            roundPaint.shader = shader

            roundRect.set(imageX, imageY, imageX + imageW, imageY + imageH)
            for (i in roundRadius.indices) {
                radii[i * 2] = roundRadius[i].toFloat()
                radii[i * 2 + 1] = roundRadius[i].toFloat()
            }
            roundPath.reset()
            roundPath.addRoundRect(roundRect, radii, Path.Direction.CW)
            roundPath.close()
            canvas.drawPath(roundPath, roundPaint)
        } else {
            canvas.save()
            canvas.clipRect(imageX, imageY, imageX + imageW, imageY + imageH)
            shaderMatrix.reset()
            shaderMatrix.setTranslate(drawRegion.left, drawRegion.top)
            shaderMatrix.preScale(scale, scale)
            val shader = getOrCreateShader(bmp)
            shader.setLocalMatrix(shaderMatrix)
            roundPaint.shader = shader
            canvas.drawRect(imageX, imageY, imageX + imageW, imageY + imageH, roundPaint)
            canvas.restore()
        }

        roundPaint.shader = null
        return true
    }

    private fun getOrCreateShader(bmp: Bitmap): BitmapShader {
        if (cachedShader != null && cachedShaderBitmap === bmp) {
            return cachedShader!!
        }
        val shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        cachedShader = shader
        cachedShaderBitmap = bmp
        return shader
    }

    fun hasImage(): Boolean = imageBitmap != null || thumbBitmap != null

    fun recycle() {
        imageBitmap = null
        thumbBitmap = null
        cachedShader = null
        cachedShaderBitmap = null
        currentUrl = null
        currentThumbUrl = null
    }

    fun getImageX() = imageX
    fun getImageY() = imageY
    fun getImageWidth() = imageW
    fun getImageHeight() = imageH
}
