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
import com.mezon.mobile.util.plainSourceUrlFromImgproxy
import java.util.LinkedHashSet

class ImageReceiver(private val parentView: View) {

    companion object {
    }

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
    private var loadExhausted = false

    private var imageX = 0f
    private var imageY = 0f
    private var imageW = 0f
    private var imageH = 0f
    private var requestW = 0
    private var requestH = 0
    private val roundRadius = IntArray(4)

    private var pendingLocalUri: android.net.Uri? = null

    private var currentAlpha = 1f
    private var lastAlphaUpdateTime = 0L
    private val crossfadeDuration = 200f
    private var allowStartAnimation = true
    private var skipUpdateFrame = false
    private var orientation = 0
    private var invert = 0

    private val roundPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
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

    fun setRoundRadius(topLeft: Int, topRight: Int, bottomRight: Int, bottomLeft: Int) {
        roundRadius[0] = topLeft
        roundRadius[1] = topRight
        roundRadius[2] = bottomRight
        roundRadius[3] = bottomLeft
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
        val hasPending = pendingLoad != null || pendingLocalUri != null
        if (!hasPending && allowStartAnimation) {
            (animatedDrawable as? Animatable)?.start()
        }
        pendingLoad?.let { (url, thumbUrl) ->
            pendingLoad = null
            currentUrl = null
            currentThumbUrl = null
            setImage(url, thumbUrl, parentView.context)
        }
        pendingLocalUri?.let { uri ->
            pendingLocalUri = null
            setLocalUri(uri, parentView.context)
        }
        if (!hasPending && pendingLocalUri == null) {
            val url = currentUrl
            val thumb = currentThumbUrl
            if (url != null && imageBitmap == null && animatedDrawable == null && mainCancellable == null) {
                setImage(url, thumb, parentView.context)
            }
        }
    }

    fun onDetachedFromWindow() {
        attached = false
        (animatedDrawable as? Animatable)?.stop()
        mainCancellable?.cancel()
        mainCancellable = null
        thumbCancellable?.cancel()
        thumbCancellable = null
        pendingLocalUri = null
    }

    fun setImage(url: String?, thumbUrl: String?, context: Context) {
        if (url == null && thumbUrl == null) {
            if (currentUrl == null && currentThumbUrl == null && !hasImage() &&
                mainCancellable == null && thumbCancellable == null) return
            recycle()
            parentView.invalidate()
            return
        }

        val urlChanged = url != currentUrl
        val thumbChanged = thumbUrl != currentThumbUrl
        val hasLoadedImage = imageBitmap != null || thumbBitmap != null || animatedDrawable != null
        val hasActiveRequest = mainCancellable != null || thumbCancellable != null

        val forceReload = !urlChanged && !thumbChanged && !hasLoadedImage && !hasActiveRequest && url != null
        if (!urlChanged && !thumbChanged && (hasLoadedImage || hasActiveRequest)) return

        if (!attached) {
            recycle()
            pendingLoad = Pair(url, thumbUrl)
            return
        }

        val loader = MezonImageLoader.getInstance(context)

        if ((thumbChanged || forceReload) && thumbUrl != null) {
            currentThumbUrl = thumbUrl
            thumbCancellable?.cancel()
            val tw = if (requestW > 0) requestW / 4 else 200
            val th = if (requestH > 0) requestH / 4 else 200
            val expectedThumb = thumbUrl
            thumbCancellable = loader.load(thumbUrl, tw, th, onSuccess = { bmp ->
                if (currentThumbUrl != expectedThumb) return@load
                if (imageBitmap == null && animatedDrawable == null) {
                    thumbBitmap = bmp
                    parentView.invalidate()
                }
            })
        }

        if ((urlChanged || forceReload) && url != null) {
            loadExhausted = false
            mainCancellable?.cancel()
            currentUrl = url
            val rw = if (requestW > 0) requestW else 800
            val rh = if (requestH > 0) requestH else 800

            isAnimatedRequest = url.contains(".gif", true) ||
                (url.contains(".webp", true) && !url.endsWith("@webp"))

            if (isAnimatedRequest) {
                stopAnimation()
                animatedDrawable = null
                imageBitmap = null
                cachedShader = null
                cachedShaderBitmap = null

                val cachedFirstFrame = loader.getBitmapFromMemory(url, rw, rh)
                thumbBitmap = cachedFirstFrame
                parentView.invalidate()

                val expectedUrl = url
                mainCancellable = loader.loadDrawable(url, rw, rh,
                    onSuccess = { drawable ->
                        if (currentUrl != expectedUrl) return@loadDrawable
                        if (drawable is Animatable) {
                            imageBitmap = null
                            cachedShader = null
                            cachedShaderBitmap = null
                            animatedDrawable = drawable
                            (drawable as Drawable).callback = animationCallback
                            if (attached && allowStartAnimation) (drawable as Animatable).start()
                        } else if (drawable is android.graphics.drawable.BitmapDrawable) {
                            imageBitmap = drawable.bitmap
                            cachedShader = null
                            cachedShaderBitmap = null
                            currentAlpha = 1f
                            thumbBitmap = null
                        }
                        parentView.invalidate()
                    },
                    onError = {
                        if (currentUrl == expectedUrl) onLoadError(url, rw, rh, loader)
                    }
                )
            } else {
                val cached = loader.getBitmapFromMemory(url, rw, rh)
                if (cached != null) {
                    imageBitmap = cached
                    stopAnimation()
                    animatedDrawable = null
                    cachedShader = null
                    cachedShaderBitmap = null
                    currentAlpha = 1f
                    thumbBitmap = null
                    parentView.invalidate()
                    return
                }

                stopAnimation()
                animatedDrawable = null
                cachedShader = null
                cachedShaderBitmap = null

                val expectedUrl = url
                mainCancellable = loader.load(url, rw, rh,
                    onSuccess = { bmp ->
                        if (currentUrl != expectedUrl) return@load
                        val hadThumb = thumbBitmap != null
                        imageBitmap = bmp
                        animatedDrawable = null
                        cachedShader = null
                        cachedShaderBitmap = null
                        if (hadThumb) {
                            currentAlpha = 0f
                            lastAlphaUpdateTime = 0L
                        } else {
                            currentAlpha = 1f
                            thumbBitmap = null
                        }
                        parentView.invalidate()
                    },
                    onError = {
                        if (currentUrl == expectedUrl) onLoadError(url, rw, rh, loader)
                    }
                )
            }
        }
    }

    private fun onLoadError(failedUrl: String, rw: Int, rh: Int, loader: MezonImageLoader) {
        val chain = buildMainImageFallbackUrls(failedUrl)
        if (chain.isEmpty()) {
            loadExhausted = true
            mainCancellable = null
            parentView.invalidate()
            return
        }
        loadFallbackUrlFromChain(chain, 0, rw, rh, loader)
    }

    private fun buildMainImageFallbackUrls(failedUrl: String): List<String> {
        val ordered = LinkedHashSet<String>()
        if (failedUrl.endsWith("@webp")) {
            ordered.add(failedUrl.removeSuffix("@webp"))
        }
        plainSourceUrlFromImgproxy(failedUrl)?.let { ordered.add(it) }
        val alt = if (failedUrl.endsWith("@webp")) failedUrl.removeSuffix("@webp") else failedUrl
        plainSourceUrlFromImgproxy(alt)?.let { ordered.add(it) }
        return ordered.filter { it.isNotEmpty() && it != failedUrl }
    }

    private fun loadFallbackUrlFromChain(
        chain: List<String>,
        index: Int,
        rw: Int,
        rh: Int,
        loader: MezonImageLoader
    ) {
        if (index >= chain.size) {
            loadExhausted = true
            mainCancellable = null
            parentView.invalidate()
            return
        }
        val nextUrl = chain[index]
        val expected = nextUrl
        if (isAnimatedRequest) {
            stopAnimation()
            animatedDrawable = null
        }
        currentUrl = nextUrl
        mainCancellable = loader.load(nextUrl, rw, rh,
            onSuccess = { bmp ->
                if (currentUrl != expected) return@load
                imageBitmap = bmp
                animatedDrawable = null
                cachedShader = null
                cachedShaderBitmap = null
                currentAlpha = 1f
                thumbBitmap = null
                loadExhausted = false
                parentView.invalidate()
            },
            onError = {
                if (currentUrl != expected) return@load
                loadFallbackUrlFromChain(chain, index + 1, rw, rh, loader)
            }
        )
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

        val mainBmp = imageBitmap?.takeIf { !it.isRecycled }
        val thumbBmp = thumbBitmap?.takeIf { !it.isRecycled }

        if (mainBmp != null && currentAlpha < 1f) {
            val now = System.currentTimeMillis()
            val dt = if (lastAlphaUpdateTime == 0L) 16L
                     else (now - lastAlphaUpdateTime).coerceIn(0, 30)
            lastAlphaUpdateTime = now
            currentAlpha = (currentAlpha + dt / crossfadeDuration).coerceAtMost(1f)

            if (thumbBmp != null) {
                drawBitmap(canvas, thumbBmp, 255)
            }
            drawBitmap(canvas, mainBmp, (currentAlpha * 255).toInt())

            if (currentAlpha < 1f) {
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
        if (skipUpdateFrame) {
            val fallback = imageBitmap ?: thumbBitmap
            if (fallback != null && !fallback.isRecycled) return drawBitmap(canvas, fallback, 255)
            return false
        }
        canvas.save()
        val hasRound = roundRadius.any { it > 0 }

        if (hasRound) {
            roundRect.set(imageX, imageY, imageX + imageW, imageY + imageH)
            val r0 = roundRadius[0]
            if (r0 == roundRadius[1] && r0 == roundRadius[2] && r0 == roundRadius[3]) {
                roundPath.reset()
                roundPath.addRoundRect(roundRect, r0.toFloat(), r0.toFloat(), Path.Direction.CW)
            } else {
                for (i in roundRadius.indices) {
                    radii[i * 2] = roundRadius[i].toFloat()
                    radii[i * 2 + 1] = roundRadius[i].toFloat()
                }
                roundPath.reset()
                roundPath.addRoundRect(roundRect, radii, Path.Direction.CW)
            }
            canvas.clipPath(roundPath)
        } else {
            canvas.clipRect(imageX, imageY, imageX + imageW, imageY + imageH)
        }

        if (orientation != 0 || invert != 0) {
            val cx = imageX + imageW / 2f
            val cy = imageY + imageH / 2f
            if (invert == 1) canvas.scale(-1f, 1f, cx, cy)
            else if (invert == 2) canvas.scale(1f, -1f, cx, cy)
            if (orientation != 0) canvas.rotate(orientation.toFloat(), cx, cy)
        }

        val iw = drawable.intrinsicWidth
        val ih = drawable.intrinsicHeight
        if (iw > 0 && ih > 0) {
            val scale = maxOf(imageW / iw.toFloat(), imageH / ih.toFloat())
            val dx = imageX + (imageW - iw * scale) / 2f
            val dy = imageY + (imageH - ih * scale) / 2f
            canvas.translate(dx, dy)
            canvas.scale(scale, scale)
            drawable.setBounds(0, 0, iw, ih)
        } else {
            drawable.setBounds(imageX.toInt(), imageY.toInt(),
                (imageX + imageW).toInt(), (imageY + imageH).toInt())
        }

        drawable.draw(canvas)
        canvas.restore()
        return true
    }

    private fun drawBitmap(canvas: Canvas, bmp: Bitmap, alpha: Int = 255): Boolean {
        if (bmp.isRecycled) return false
        val bmpW = bmp.width.toFloat()
        val bmpH = bmp.height.toFloat()
        if (bmpW <= 0f || bmpH <= 0f) return false

        try {
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
                if (invert != 0) {
                    shaderMatrix.preScale(
                        if (invert == 1) -1f else 1f,
                        if (invert == 2) -1f else 1f,
                        drawRegion.width() / 2f, drawRegion.height() / 2f
                    )
                }
                when (orientation) {
                    90 -> { shaderMatrix.preRotate(90f); shaderMatrix.preTranslate(0f, -drawRegion.width()) }
                    180 -> { shaderMatrix.preRotate(180f); shaderMatrix.preTranslate(-drawRegion.width(), -drawRegion.height()) }
                    270 -> { shaderMatrix.preRotate(270f); shaderMatrix.preTranslate(-drawRegion.height(), 0f) }
                }
                shaderMatrix.preScale(scale, scale)
                shader.setLocalMatrix(shaderMatrix)
                roundPaint.shader = shader
                roundPaint.alpha = alpha

                roundRect.set(imageX, imageY, imageX + imageW, imageY + imageH)
                val r0 = roundRadius[0]
                if (r0 == roundRadius[1] && r0 == roundRadius[2] && r0 == roundRadius[3]) {
                    if (r0 == 0) {
                        canvas.drawRect(roundRect, roundPaint)
                    } else {
                        canvas.drawRoundRect(roundRect, r0.toFloat(), r0.toFloat(), roundPaint)
                    }
                } else {
                    for (i in roundRadius.indices) {
                        radii[i * 2] = roundRadius[i].toFloat()
                        radii[i * 2 + 1] = roundRadius[i].toFloat()
                    }
                    roundPath.reset()
                    roundPath.addRoundRect(roundRect, radii, Path.Direction.CW)
                    roundPath.close()
                    canvas.drawPath(roundPath, roundPaint)
                }

                roundPaint.shader = null
                roundPaint.alpha = 255
            } else {
                bitmapPaint.alpha = alpha
                canvas.save()
                canvas.clipRect(imageX, imageY, imageX + imageW, imageY + imageH)
                if (invert == 1) canvas.scale(-1f, 1f, imageX + imageW / 2f, imageY + imageH / 2f)
                else if (invert == 2) canvas.scale(1f, -1f, imageX + imageW / 2f, imageY + imageH / 2f)
                if (orientation != 0) canvas.rotate(orientation.toFloat(), imageX + imageW / 2f, imageY + imageH / 2f)
                canvas.drawBitmap(bmp, null, drawRegion, bitmapPaint)
                canvas.restore()
            }

            return true
        } catch (e: Exception) {
            onBitmapException()
            return false
        }
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

    private fun onBitmapException() {
        val url = currentUrl ?: return
        val ctx = parentView.context ?: return
        val rw = if (requestW > 0) requestW else 800
        val rh = if (requestH > 0) requestH else 800
        MezonImageLoader.getInstance(ctx).removeFromCache(url, rw, rh)
        imageBitmap = null
        cachedShader = null
        cachedShaderBitmap = null
    }

    fun setOrientation(degrees: Int, flip: Int = 0) {
        orientation = degrees
        invert = flip
    }

    fun hasImage(): Boolean = imageBitmap != null || thumbBitmap != null || animatedDrawable != null
    fun hasMainImage(): Boolean = imageBitmap != null || animatedDrawable != null

    fun shouldAnimateLoadingPlaceholder(): Boolean {
        if (loadExhausted) return false
        if (hasImage()) return false
        return currentUrl != null || currentThumbUrl != null || pendingLoad != null || pendingLocalUri != null
    }

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
        pendingLocalUri = null
        loadExhausted = false
    }

    fun setLocalUri(uri: android.net.Uri, context: Context) {
        val uriString = uri.toString()
        if (!attached) {
            recycle()
            pendingLocalUri = uri
            currentUrl = uriString
            return
        }
        if (uriString == currentUrl && hasMainImage()) return

        recycle()
        currentUrl = uriString

        val loader = MezonImageLoader.getInstance(context)
        val rw = if (requestW > 0) requestW else 800
        val rh = if (requestH > 0) requestH else 800

        val cached = loader.getBitmapFromMemory(uriString, rw, rh)
        if (cached != null) {
            imageBitmap = cached
            currentAlpha = 1f
            parentView.invalidate()
            return
        }

        parentView.invalidate()

        mainCancellable = loader.loadFromUri(uri, rw, rh,
            onSuccess = { bmp ->
                if (currentUrl != uriString) return@loadFromUri
                imageBitmap = bmp
                cachedShader = null
                cachedShaderBitmap = null
                currentAlpha = 1f
                parentView.invalidate()
            }
        )
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
