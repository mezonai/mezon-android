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
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.view.View
import coil.Coil
import coil.request.ImageRequest
import coil.size.Size

class ImageReceiver(private val parentView: View) {

    private var imageBitmap: Bitmap? = null
    private var thumbBitmap: Bitmap? = null
    private var animatedDrawable: Drawable? = null
    private var currentUrl: String? = null
    private var currentThumbUrl: String? = null
    private var attached = false
    private var pendingLoad: Pair<String?, String?>? = null
    private var currentDisposable: coil.request.Disposable? = null
    private var thumbDisposable: coil.request.Disposable? = null

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

    private val invalidateRunnable = Runnable { parentView.invalidate() }

    fun setImageCoords(x: Float, y: Float, w: Float, h: Float) {
        imageX = x
        imageY = y
        imageW = w
        imageH = h
    }

    fun setRoundRadius(radius: Int) {
        roundRadius.fill(radius)
    }

    fun onAttachedToWindow() {
        if (attached) return
        attached = true
        (animatedDrawable as? Animatable)?.start()
        pendingLoad?.let { (url, thumbUrl) ->
            pendingLoad = null
            currentUrl = null
            currentThumbUrl = null
            setImage(url, thumbUrl, parentView.context)
        }
    }

    fun onDetachedFromWindow() {
        attached = false
        (animatedDrawable as? Animatable)?.stop()
        currentDisposable?.dispose()
        currentDisposable = null
        thumbDisposable?.dispose()
        thumbDisposable = null
        currentUrl = null
        currentThumbUrl = null
    }

    fun setImage(url: String?, thumbUrl: String?, context: Context) {
        val urlChanged = url != currentUrl
        val thumbChanged = thumbUrl != currentThumbUrl

        if (!urlChanged && !thumbChanged) return

        if (!attached) {
            pendingLoad = Pair(url, thumbUrl)
            return
        }

        if (thumbChanged && thumbUrl != null) {
            currentThumbUrl = thumbUrl
            thumbDisposable?.dispose()
            thumbDisposable = loadDrawable(thumbUrl, context, isThumb = true)
        }

        if (urlChanged && url != null) {
            imageBitmap = null
            stopAnimation()
            animatedDrawable = null
            cachedShader = null
            cachedShaderBitmap = null
            currentUrl = url
            currentDisposable?.dispose()
            currentDisposable = loadDrawable(url, context, isThumb = false)
        }
    }

    private fun loadDrawable(url: String, context: Context, isThumb: Boolean): coil.request.Disposable? {
        if (url.isEmpty()) return null
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(Size.ORIGINAL)
            .allowHardware(false)
            .listener(
                onError = { _, result ->
                    if (!isThumb && url.endsWith("@webp")) {
                        loadDrawable(url.removeSuffix("@webp"), context, isThumb)
                    }
                }
            )
            .target(onSuccess = { drawable ->
                if (drawable is Animatable) {
                    if (!isThumb) {
                        imageBitmap = null
                        thumbBitmap = null
                        cachedShader = null
                        cachedShaderBitmap = null
                        animatedDrawable = drawable
                        drawable.callback = animationCallback
                        if (attached) drawable.start()
                        parentView.post { parentView.invalidate() }
                    }
                    return@target
                }
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
                    if (imageBitmap == null && animatedDrawable == null) {
                        thumbBitmap = bmp
                        parentView.post { parentView.invalidate() }
                    }
                } else {
                    imageBitmap = bmp
                    thumbBitmap = null
                    animatedDrawable = null
                    cachedShader = null
                    cachedShaderBitmap = null
                    parentView.post { parentView.invalidate() }
                }
            })
            .build()
        return Coil.imageLoader(context).enqueue(request)
    }

    private val animationCallback = object : Drawable.Callback {
        override fun invalidateDrawable(who: Drawable) {
            if (attached) parentView.post(invalidateRunnable)
        }
        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
            parentView.postDelayed(what, `when` - android.os.SystemClock.uptimeMillis())
        }
        override fun unscheduleDrawable(who: Drawable, what: Runnable) {
            parentView.removeCallbacks(what)
        }
    }

    fun draw(canvas: Canvas): Boolean {
        if (imageW <= 0f || imageH <= 0f) return false

        val anim = animatedDrawable
        if (anim != null) {
            return drawAnimated(canvas, anim)
        }

        val bmp = imageBitmap ?: thumbBitmap ?: return false
        return drawBitmap(canvas, bmp)
    }

    private fun drawAnimated(canvas: Canvas, drawable: Drawable): Boolean {
        val hasRound = roundRadius.any { it > 0 }
        if (hasRound) {
            roundRect.set(imageX, imageY, imageX + imageW, imageY + imageH)
            for (i in roundRadius.indices) {
                radii[i * 2] = roundRadius[i].toFloat()
                radii[i * 2 + 1] = roundRadius[i].toFloat()
            }
            roundPath.reset()
            roundPath.addRoundRect(roundRect, radii, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(roundPath)
        }
        drawable.setBounds(imageX.toInt(), imageY.toInt(), (imageX + imageW).toInt(), (imageY + imageH).toInt())
        drawable.draw(canvas)
        if (hasRound) {
            canvas.restore()
        }
        return true
    }

    private fun drawBitmap(canvas: Canvas, bmp: Bitmap): Boolean {
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

    private fun stopAnimation() {
        (animatedDrawable as? Animatable)?.stop()
        animatedDrawable?.callback = null
    }

    fun hasImage(): Boolean = imageBitmap != null || thumbBitmap != null || animatedDrawable != null

    fun recycle() {
        currentDisposable?.dispose()
        currentDisposable = null
        thumbDisposable?.dispose()
        thumbDisposable = null
        stopAnimation()
        animatedDrawable = null
        imageBitmap = null
        thumbBitmap = null
        cachedShader = null
        cachedShaderBitmap = null
        currentUrl = null
        currentThumbUrl = null
        pendingLoad = null
    }

    fun setBitmapDirectly(bmp: Bitmap) {
        stopAnimation()
        animatedDrawable = null
        imageBitmap = bmp
        thumbBitmap = null
        cachedShader = null
        cachedShaderBitmap = null
    }

    fun getImageX() = imageX
    fun getImageY() = imageY
    fun getImageWidth() = imageW
    fun getImageHeight() = imageH
}
