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

class ImageReceiver(private val parentView: View) {

    private var imageBitmap: Bitmap? = null
    private var thumbBitmap: Bitmap? = null
    private var animatedDrawable: Drawable? = null
    private var currentUrl: String? = null
    private var currentThumbUrl: String? = null
    private var attached = false
    private var pendingLoad: Pair<String?, String?>? = null
    private var mainCancellable: MezonImageLoader.Cancellable? = null
    private var thumbCancellable: MezonImageLoader.Cancellable? = null
    private var isAnimatedRequest = false

    private var imageX = 0f
    private var imageY = 0f
    private var imageW = 0f
    private var imageH = 0f
    private var requestW = 0
    private var requestH = 0
    private val roundRadius = IntArray(4)

    private var crossfadeAlpha = 255
    private var crossfadeStartTime = 0L
    private val crossfadeDuration = 200L
    private var allowStartAnimation = true
    private var skipUpdateFrame = false

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

    fun setRequestedSize(w: Int, h: Int) {
        requestW = w
        requestH = h
    }

    fun setAllowStartAnimation(allow: Boolean) {
        allowStartAnimation = allow
        if (!allow) {
            (animatedDrawable as? Animatable)?.stop()
        } else if (attached) {
            (animatedDrawable as? Animatable)?.start()
        }
    }

    fun setSkipUpdateFrame(skip: Boolean) {
        skipUpdateFrame = skip
    }

    fun onAttachedToWindow() {
        if (attached) return
        attached = true
        if (allowStartAnimation) (animatedDrawable as? Animatable)?.start()
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
        mainCancellable?.cancel()
        mainCancellable = null
        thumbCancellable?.cancel()
        thumbCancellable = null
    }

    fun setImage(url: String?, thumbUrl: String?, context: Context) {
        val urlChanged = url != currentUrl
        val thumbChanged = thumbUrl != currentThumbUrl

        if (!urlChanged && !thumbChanged) return

        if (!attached) {
            pendingLoad = Pair(url, thumbUrl)
            return
        }

        val loader = MezonImageLoader.getInstance(context)

        if (thumbChanged && thumbUrl != null) {
            currentThumbUrl = thumbUrl
            thumbCancellable?.cancel()
            val tw = if (requestW > 0) requestW / 4 else 200
            val th = if (requestH > 0) requestH / 4 else 200
            thumbCancellable = loader.load(thumbUrl, tw, th, onSuccess = { bmp ->
                if (imageBitmap == null && animatedDrawable == null) {
                    thumbBitmap = bmp
                    parentView.invalidate()
                }
            })
        }

        if (urlChanged && url != null) {
            currentUrl = url
            mainCancellable?.cancel()
            val rw = if (requestW > 0) requestW else 800
            val rh = if (requestH > 0) requestH else 800

            val cached = loader.getBitmapFromMemory(url, rw, rh)
            if (cached != null) {
                imageBitmap = cached
                stopAnimation()
                animatedDrawable = null
                cachedShader = null
                cachedShaderBitmap = null
                crossfadeAlpha = 255
                thumbBitmap = null
                parentView.invalidate()
                return
            }

            stopAnimation()
            animatedDrawable = null
            cachedShader = null
            cachedShaderBitmap = null

            isAnimatedRequest = url.contains(".gif", true) ||
                (url.contains(".webp", true) && !url.endsWith("@webp"))

            if (isAnimatedRequest) {
                mainCancellable = loader.loadDrawable(url, rw, rh,
                    onSuccess = { drawable ->
                        if (drawable is Animatable) {
                            imageBitmap = null
                            thumbBitmap = null
                            cachedShader = null
                            cachedShaderBitmap = null
                            animatedDrawable = drawable
                            (drawable as Drawable).callback = animationCallback
                            if (attached && allowStartAnimation) (drawable as Animatable).start()
                        } else if (drawable is android.graphics.drawable.BitmapDrawable) {
                            imageBitmap = drawable.bitmap
                            cachedShader = null
                            cachedShaderBitmap = null
                            crossfadeAlpha = 255
                            thumbBitmap = null
                        }
                        parentView.invalidate()
                    },
                    onError = { onLoadError(url, rw, rh, loader) }
                )
            } else {
                mainCancellable = loader.load(url, rw, rh,
                    onSuccess = { bmp ->
                        val hadThumb = thumbBitmap != null
                        imageBitmap = bmp
                        animatedDrawable = null
                        cachedShader = null
                        cachedShaderBitmap = null
                        if (hadThumb) {
                            crossfadeAlpha = 0
                            crossfadeStartTime = System.currentTimeMillis()
                        } else {
                            crossfadeAlpha = 255
                            thumbBitmap = null
                        }
                        parentView.invalidate()
                    },
                    onError = { onLoadError(url, rw, rh, loader) }
                )
            }
        }
    }

    private fun onLoadError(url: String, rw: Int, rh: Int, loader: MezonImageLoader) {
        if (url.endsWith("@webp")) {
            val fallbackUrl = url.removeSuffix("@webp")
            currentUrl = fallbackUrl
            mainCancellable = loader.load(fallbackUrl, rw, rh,
                onSuccess = { bmp ->
                    imageBitmap = bmp
                    cachedShader = null
                    cachedShaderBitmap = null
                    crossfadeAlpha = 255
                    thumbBitmap = null
                    parentView.invalidate()
                }
            )
        }
    }

    private val animationCallback = object : Drawable.Callback {
        override fun invalidateDrawable(who: Drawable) {
            if (attached) parentView.post { parentView.invalidate() }
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

        val mainBmp = imageBitmap
        val thumbBmp = thumbBitmap

        if (mainBmp != null && crossfadeAlpha < 255) {
            val elapsed = System.currentTimeMillis() - crossfadeStartTime
            crossfadeAlpha = ((elapsed.toFloat() / crossfadeDuration) * 255).toInt().coerceIn(0, 255)

            if (thumbBmp != null) {
                drawBitmap(canvas, thumbBmp, 255)
            }
            drawBitmap(canvas, mainBmp, crossfadeAlpha)

            if (crossfadeAlpha < 255) {
                parentView.postInvalidateOnAnimation()
            } else {
                thumbBitmap = null
            }
            return true
        }

        val bmp = mainBmp ?: thumbBmp ?: return false
        return drawBitmap(canvas, bmp, 255)
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

    private fun drawBitmap(canvas: Canvas, bmp: Bitmap, alpha: Int = 255): Boolean {
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

        roundPaint.alpha = alpha

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
        roundPaint.alpha = 255
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
    fun hasMainImage(): Boolean = imageBitmap != null || animatedDrawable != null

    fun recycle() {
        mainCancellable?.cancel()
        mainCancellable = null
        thumbCancellable?.cancel()
        thumbCancellable = null
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
    fun getBitmap(): Bitmap? = imageBitmap ?: thumbBitmap
}
