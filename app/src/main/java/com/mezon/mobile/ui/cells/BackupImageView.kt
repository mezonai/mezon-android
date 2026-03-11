package com.mezon.mobile.ui.cells

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.drawable.Drawable
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import coil.dispose
import coil.request.Disposable
import coil.size.Scale
import coil.size.Size
import coil.transform.CircleCropTransformation
import coil.transform.RoundedCornersTransformation
import com.mezon.mobile.core.AvatarDrawable
import com.mezon.mobile.core.LayoutHelper
import kotlin.math.min

class BackupImageView(context: Context) : View(context) {

    private val avatarDrawable = AvatarDrawable()
    private var imageUrl: String? = null
    private var loadedDrawable: Drawable? = null
    private var disposable: Disposable? = null
    private var roundImage = false
    private var attached = false
    private var decodeWidth = -1
    private var decodeHeight = -1
    private var aspectFit = false
    private var roundRadius = 0
    private var roundRadiusTL = 0
    private var roundRadiusTR = 0
    private var roundRadiusBR = 0
    private var roundRadiusBL = 0
    private val clipPath = Path()

    init {
        setWillNotDraw(false)
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
        invalidate()
    }

    fun setRoundRadius(topLeft: Int, topRight: Int, bottomRight: Int, bottomLeft: Int) {
        roundRadius = 0
        roundRadiusTL = topLeft
        roundRadiusTR = topRight
        roundRadiusBR = bottomRight
        roundRadiusBL = bottomLeft
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

    fun setAvatarInfo(id: Long, name: String) {
        avatarDrawable.setInfo(id, name)
        invalidate()
    }

    fun setImage(url: String?, filter: String?, placeholder: Drawable?) {
        imageUrl = url
        loadedDrawable = placeholder
        if (placeholder != null) invalidate()
        if (attached && url != null) loadImage(url, filter, placeholder)
        invalidate()
    }

    fun setImage(url: String?, id: Long = 0, name: String = "") {
        if (id != 0L) avatarDrawable.setInfo(id, name)
        imageUrl = url
        loadedDrawable = null
        if (attached && url != null) loadImage(url, null, null)
        invalidate()
    }

    fun setImageResource(resId: Int) {
        disposable?.dispose()
        disposable = null
        imageUrl = null
        loadedDrawable = context.resources.getDrawable(resId, null)
        invalidate()
    }

    fun setImageDrawable(drawable: Drawable?) {
        disposable?.dispose()
        disposable = null
        imageUrl = null
        loadedDrawable = drawable
        invalidate()
    }

    fun getAvatarDrawable(): AvatarDrawable = avatarDrawable

    fun getLoadedDrawable(): Drawable? = loadedDrawable

    private fun loadImage(url: String, filter: String?, placeholder: Drawable?) {
        disposable?.dispose()
        val w = if (decodeWidth > 0) decodeWidth else measuredWidth.coerceAtLeast(LayoutHelper.dp(48))
        val h = if (decodeHeight > 0) decodeHeight else measuredHeight.coerceAtLeast(LayoutHelper.dp(48))
        val scale = if (aspectFit) Scale.FIT else Scale.FILL
        val transformations = mutableListOf<coil.transform.Transformation>()
        when {
            roundImage -> transformations.add(CircleCropTransformation())
            roundRadius > 0 -> transformations.add(RoundedCornersTransformation(roundRadius.toFloat()))
            roundRadiusTL != 0 || roundRadiusTR != 0 || roundRadiusBR != 0 || roundRadiusBL != 0 -> {
                val tl = roundRadiusTL.toFloat()
                val tr = roundRadiusTR.toFloat()
                val bl = roundRadiusBL.toFloat()
                val br = roundRadiusBR.toFloat()
                transformations.add(RoundedCornersTransformation(tl, tr, bl, br))
            }
        }
        disposable = coil.Coil.imageLoader(context).enqueue(
            coil.request.ImageRequest.Builder(context)
                .data(url)
                .apply { if (transformations.isNotEmpty()) transformations(transformations) }
                .size(Size(w, h))
                .scale(scale)
                .placeholder(placeholder)
                .target(
                    onSuccess = { result ->
                        loadedDrawable = result
                        invalidate()
                    },
                    onError = {
                        loadedDrawable = null
                        invalidate()
                    }
                )
                .build()
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        imageUrl?.let { loadImage(it, null, null) }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        attached = false
        disposable?.dispose()
        disposable = null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        avatarDrawable.setBounds(0, 0, w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val hasRoundClip = roundRadius > 0 || roundRadiusTL != 0 || roundRadiusTR != 0 || roundRadiusBR != 0 || roundRadiusBL != 0
        if (hasRoundClip) {
            clipPath.reset()
            val radii = if (roundRadius > 0) floatArrayOf(
                roundRadius.toFloat(), roundRadius.toFloat(),
                roundRadius.toFloat(), roundRadius.toFloat(),
                roundRadius.toFloat(), roundRadius.toFloat(),
                roundRadius.toFloat(), roundRadius.toFloat()
            ) else floatArrayOf(
                roundRadiusTL.toFloat(), roundRadiusTL.toFloat(),
                roundRadiusTR.toFloat(), roundRadiusTR.toFloat(),
                roundRadiusBR.toFloat(), roundRadiusBR.toFloat(),
                roundRadiusBL.toFloat(), roundRadiusBL.toFloat()
            )
            clipPath.addRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radii, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clipPath)
        }
        val d = loadedDrawable
        if (d != null) {
            val drawW = if (decodeWidth > 0 && decodeHeight > 0) {
                if (aspectFit) {
                    val scale = min(width.toFloat() / decodeWidth, height.toFloat() / decodeHeight)
                    (decodeWidth * scale).toInt()
                } else width
            } else width
            val drawH = if (decodeWidth > 0 && decodeHeight > 0) {
                if (aspectFit) {
                    val scale = min(width.toFloat() / decodeWidth, height.toFloat() / decodeHeight)
                    (decodeHeight * scale).toInt()
                } else height
            } else height
            val drawX = if (decodeWidth > 0 && decodeHeight > 0 && aspectFit) (width - drawW) / 2 else 0
            val drawY = if (decodeWidth > 0 && decodeHeight > 0 && aspectFit) (height - drawH) / 2 else 0
            d.setBounds(drawX, drawY, drawX + drawW, drawY + drawH)
            d.draw(canvas)
        } else {
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
}
