package com.mezon.mobile.ui.cells

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import com.mezon.mobile.core.AvatarDrawable
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.home.chat.MezonImageLoader
import kotlin.math.min

class BackupImageView(context: Context) : View(context) {

    private val avatarDrawable = AvatarDrawable()
    private var imageUrl: String? = null
    private var loadedDrawable: Drawable? = null
    private var cancellable: MezonImageLoader.Cancellable? = null
    private var roundImage = false
    private var attached = false
    private var decodeWidth = -1
    private var decodeHeight = -1
    private var aspectFit = false
    private var aspectFill = false
    private var roundRadius = 0
    private var roundRadiusTL = 0
    private var roundRadiusTR = 0
    private var roundRadiusBR = 0
    private var roundRadiusBL = 0
    private val clipPath = Path()
    private val radiiArray = FloatArray(8)
    private var radiiDirty = true

    private var omitEmptyPlaceholder = false

    init {
        setWillNotDraw(false)
    }

    fun setOmitEmptyPlaceholder(value: Boolean) {
        omitEmptyPlaceholder = value
        invalidate()
    }

    fun setRoundImage(round: Boolean) {
        roundImage = round
    }

    fun setRoundRadius(radius: Int) {
        roundRadius = radius
        roundRadiusTL = 0
        roundRadiusTR = 0
        roundRadiusBR = 0
        roundRadiusBL = 0
        radiiDirty = true
        invalidate()
    }

    fun setRoundRadius(topLeft: Int, topRight: Int, bottomRight: Int, bottomLeft: Int) {
        roundRadius = 0
        roundRadiusTL = topLeft
        roundRadiusTR = topRight
        roundRadiusBR = bottomRight
        roundRadiusBL = bottomLeft
        radiiDirty = true
        invalidate()
    }

    fun setSize(width: Int, height: Int) {
        decodeWidth = width
        decodeHeight = height
        invalidate()
    }

    fun setAspectFit(value: Boolean) {
        aspectFit = value
        invalidate()
    }

    fun setAspectFill(value: Boolean) {
        aspectFill = value
        invalidate()
    }

    fun setAvatarInfo(id: Long, username: String) {
        avatarDrawable.setInfo(id, username)
        invalidate()
    }

    fun setImage(url: String?, filter: String?, placeholder: Drawable?) {
        if (url != null && url == imageUrl && (cancellable != null || (loadedDrawable != null && loadedDrawable !== placeholder))) return
        imageUrl = url
        loadedDrawable = placeholder
        if (placeholder != null) invalidate()
        if (attached && url != null) loadImage(url)
        invalidate()
    }

    fun setImage(url: String?, id: Long = 0, username: String = "") {
        if (id != 0L) avatarDrawable.setInfo(id, username)
        if (url != null && url == imageUrl && (cancellable != null || loadedDrawable != null)) return
        imageUrl = url
        loadedDrawable = null
        if (attached && url != null) loadImage(url)
        invalidate()
    }

    fun setImageResource(resId: Int) {
        cancellable?.cancel()
        cancellable = null
        imageUrl = null
        loadedDrawable?.callback = null
        loadedDrawable = context.resources.getDrawable(resId, null)
        invalidate()
    }

    fun setImageDrawable(drawable: Drawable?) {
        cancellable?.cancel()
        cancellable = null
        imageUrl = null
        loadedDrawable?.callback = null
        loadedDrawable = drawable
        invalidate()
    }

    fun getAvatarDrawable(): AvatarDrawable = avatarDrawable

    fun getLoadedDrawable(): Drawable? = loadedDrawable

    private fun loadImage(url: String) {
        cancellable?.cancel()
        val w = if (decodeWidth > 0) decodeWidth else measuredWidth.coerceAtLeast(LayoutHelper.dp(48))
        val h = if (decodeHeight > 0) decodeHeight else measuredHeight.coerceAtLeast(LayoutHelper.dp(48))
        val proxyUrl = url
        val absoluteUrlFallback = com.mezon.mobile.util.plainSourceUrlFromImgproxy(proxyUrl) ?: proxyUrl
        val isAnimated = com.mezon.mobile.util.isAnimatedImageUrl(absoluteUrlFallback)
        val loader = MezonImageLoader.getInstance(context)

        val successCallback: (Any) -> Unit = { result ->
            loadedDrawable?.callback = null
            if (result is android.graphics.drawable.Animatable) {
                loadedDrawable = result as android.graphics.drawable.Drawable
                loadedDrawable?.callback = drawableCallback
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P && loadedDrawable is android.graphics.drawable.AnimatedImageDrawable) {
                    (loadedDrawable as android.graphics.drawable.AnimatedImageDrawable).start()
                }
            } else if (result is android.graphics.drawable.BitmapDrawable) {
                loadedDrawable = result
            } else if (result is android.graphics.Bitmap) {
                loadedDrawable = BitmapDrawable(context.resources, result)
            }
            invalidate()
        }

        val errorCallback: (Throwable) -> Unit = {
            if (absoluteUrlFallback != proxyUrl) {
                cancellable = loader.loadDrawable(
                    absoluteUrlFallback, w, h, successCallback,
                    onError = {
                        loadedDrawable?.callback = null
                        loadedDrawable = null
                        invalidate()
                    }
                )
            } else {
                loadedDrawable?.callback = null
                loadedDrawable = null
                invalidate()
            }
        }

        if (isAnimated) {
            cancellable = loader.loadDrawable(absoluteUrlFallback, w, h, successCallback, {
                loadedDrawable?.callback = null
                loadedDrawable = null
                invalidate()
            })
        } else {
            cancellable = loader.load(proxyUrl, w, h, { bmp -> successCallback(bmp) }, errorCallback)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        if (loadedDrawable == null && cancellable == null) {
            imageUrl?.let { loadImage(it) }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        attached = false
        cancellable?.cancel()
        cancellable = null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        avatarDrawable.setBounds(0, 0, w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val hasRoundClip = roundRadius > 0 || roundRadiusTL != 0 || roundRadiusTR != 0 || roundRadiusBR != 0 || roundRadiusBL != 0
        if (hasRoundClip) {
            if (radiiDirty) {
                if (roundRadius > 0) {
                    val r = roundRadius.toFloat()
                    radiiArray[0] = r; radiiArray[1] = r
                    radiiArray[2] = r; radiiArray[3] = r
                    radiiArray[4] = r; radiiArray[5] = r
                    radiiArray[6] = r; radiiArray[7] = r
                } else {
                    radiiArray[0] = roundRadiusTL.toFloat(); radiiArray[1] = roundRadiusTL.toFloat()
                    radiiArray[2] = roundRadiusTR.toFloat(); radiiArray[3] = roundRadiusTR.toFloat()
                    radiiArray[4] = roundRadiusBR.toFloat(); radiiArray[5] = roundRadiusBR.toFloat()
                    radiiArray[6] = roundRadiusBL.toFloat(); radiiArray[7] = roundRadiusBL.toFloat()
                }
                radiiDirty = false
            }
            clipPath.reset()
            clipPath.addRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radiiArray, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clipPath)
        }
        val d = loadedDrawable
        if (d != null) {
            val drawW: Int
            val drawH: Int
            val drawX: Int
            val drawY: Int

            val iW = d.intrinsicWidth
            val iH = d.intrinsicHeight

            if (aspectFill && iW > 0 && iH > 0) {
                val scale = kotlin.math.max(width.toFloat() / iW, height.toFloat() / iH)
                drawW = (iW * scale).toInt()
                drawH = (iH * scale).toInt()
                drawX = (width - drawW) / 2
                drawY = (height - drawH) / 2
            } else {
                drawW = if (decodeWidth > 0 && decodeHeight > 0) {
                    if (aspectFit) {
                        val scale = kotlin.math.min(width.toFloat() / decodeWidth, height.toFloat() / decodeHeight)
                        (decodeWidth * scale).toInt()
                    } else width
                } else width
                
                drawH = if (decodeWidth > 0 && decodeHeight > 0) {
                    if (aspectFit) {
                        val scale = kotlin.math.min(width.toFloat() / decodeWidth, height.toFloat() / decodeHeight)
                        (decodeHeight * scale).toInt()
                    } else height
                } else height
                
                drawX = if (decodeWidth > 0 && decodeHeight > 0 && aspectFit) (width - drawW) / 2 else 0
                drawY = if (decodeWidth > 0 && decodeHeight > 0 && aspectFit) (height - drawH) / 2 else 0
            }

            if (!hasRoundClip && aspectFill) {
                canvas.save()
                canvas.clipRect(0, 0, width, height)
            }
            d.setBounds(drawX, drawY, drawX + drawW, drawY + drawH)
            d.draw(canvas)
            if (!hasRoundClip && aspectFill) {
                canvas.restore()
            }
        } else if (!omitEmptyPlaceholder) {
            avatarDrawable.setBounds(0, 0, width, height)
            avatarDrawable.draw(canvas)
        }
        if (hasRoundClip) canvas.restore()
    }

    override fun hasOverlappingRendering(): Boolean = false

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.ImageView"
        contentDescription?.let { info.contentDescription = it }
    }

    private val drawableCallback = object : android.graphics.drawable.Drawable.Callback {
        override fun invalidateDrawable(who: android.graphics.drawable.Drawable) {
            invalidate()
        }
        override fun scheduleDrawable(who: android.graphics.drawable.Drawable, what: Runnable, `when`: Long) {
            postDelayed(what, `when` - android.os.SystemClock.uptimeMillis())
        }
        override fun unscheduleDrawable(who: android.graphics.drawable.Drawable, what: Runnable) {
            removeCallbacks(what)
        }
    }

    override fun verifyDrawable(who: android.graphics.drawable.Drawable): Boolean {
        return who == loadedDrawable || super.verifyDrawable(who)
    }
}
