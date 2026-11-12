package com.mezon.mobile.home.call

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator
import com.mezon.mobile.core.AvatarDrawable
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.util.avatarImgproxyUrl

class CallAvatarView(
    context: Context,
    private val themeColors: ThemeColors
) : View(context) {

    private val avatarDrawable = AvatarDrawable()
    private val avatarSize = LayoutHelper.dp(100)
    private val avatarTopPadding = LayoutHelper.dp(57)
    private val nameGapBelowAvatar = LayoutHelper.dp(36)
    private val statusGapBelowName = LayoutHelper.dp(24)
    private val ringingRipples = RingingRipples(themeColors, ::invalidate)
    private val connectedBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = LayoutHelper.dp(4).toFloat()
    }
    private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 20f, resources.displayMetrics)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val statusPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, resources.displayMetrics)
        textAlign = Paint.Align.CENTER
    }

    private val tmpRect = RectF()
    private var peerName = ""
    private var statusText = ""
    private var isConnected = false
    private var ringing = false
    private var connectingSpinner = false
    private var loadingSpinRotation = 0f
    private var loadingSpinnerAnimator: ValueAnimator? = null
    private val loadingSpinnerRadius = LayoutHelper.dpf(11f)
    private val loadingArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = LayoutHelper.dpf(1.5f)
        strokeCap = Paint.Cap.ROUND
    }
    private var currentAvatarUrl: String? = null
    private var cancellable: MezonImageLoader.Cancellable? = null
    private var attachedToWindow = false

    fun setData(name: String, avatarUrl: String?) {
        peerName = name
        avatarDrawable.setUserId(0L)
        
        val actualAvatarUrl = if (avatarUrl == "null") null else avatarUrl
        
        if (currentAvatarUrl != actualAvatarUrl) {
            currentAvatarUrl = actualAvatarUrl
            cancellable?.cancel()
            cancellable = null
            if (actualAvatarUrl.isNullOrBlank()) {
                avatarDrawable.setLoadingPlaceholder(false)
                avatarDrawable.setPhoto(null)
            } else if (attachedToWindow) {
                loadAvatar(actualAvatarUrl)
            }
        }
        invalidate()
    }

    fun setStatus(status: String, ringing: Boolean = false) {
        statusText = status
        if (this.ringing != ringing) {
            this.ringing = ringing
            ringingRipples.setVisible(ringing && isAttachedToWindow && isShown && windowVisibility == VISIBLE)
        }
        invalidate()
    }

    fun setConnectingLoading(show: Boolean) {
        if (connectingSpinner == show) return
        connectingSpinner = show
        if (show) {
            if (loadingSpinnerAnimator == null) {
                loadingSpinnerAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
                    duration = 900L
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    addUpdateListener {
                        loadingSpinRotation = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
            }
        } else {
            loadingSpinnerAnimator?.cancel()
            loadingSpinnerAnimator = null
            loadingSpinRotation = 0f
        }
        invalidate()
    }

    fun setConnected(connected: Boolean) {
        isConnected = connected
        if (connected) {
            setConnectingLoading(false)
        }
        invalidate()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        ringingRipples.setVisible(isVisible && ringing)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, avatarSize + 2 * avatarTopPadding + LayoutHelper.dp(72))
    }

    override fun onDraw(canvas: Canvas) {
        val theme = themeColors
        connectedBorderPaint.color = theme.connectedColor
        namePaint.color = theme.colorText
        statusPaint.color = theme.onSurfaceVariant

        val cx = width / 2f
        val avatarTop = avatarTopPadding.toFloat()
        val avatarRadius = avatarSize / 2f

        if (ringing) {
            val cy = avatarTop + avatarRadius
            ringingRipples.draw(canvas, cx, cy, avatarRadius, minOf(cx, cy, height - cy))
        }

        tmpRect.set(cx - avatarRadius, avatarTop, cx + avatarRadius, avatarTop + avatarSize)
        canvas.save()
        canvas.clipRect(tmpRect)
        avatarDrawable.setBounds(tmpRect.left.toInt(), tmpRect.top.toInt(), tmpRect.right.toInt(), tmpRect.bottom.toInt())
        avatarDrawable.draw(canvas)
        canvas.restore()

        if (isConnected) {
            canvas.drawCircle(cx, avatarTop + avatarRadius, avatarRadius + LayoutHelper.dp(2), connectedBorderPaint)
        }

        val nameY = avatarTop + avatarSize + nameGapBelowAvatar
        canvas.drawText(peerName, cx, nameY, namePaint)

        if (connectingSpinner) {
            val r = loadingSpinnerRadius
            val cy = nameY + statusGapBelowName + r
            tmpRect.set(cx - r, cy - r, cx + r, cy + r)
            loadingArcPaint.color = theme.onSurfaceVariant
            canvas.drawArc(tmpRect, loadingSpinRotation, 75f, false, loadingArcPaint)
        } else if (statusText.isNotEmpty()) {
            canvas.drawText(statusText, cx, nameY + statusGapBelowName, statusPaint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        attachedToWindow = false
        cancellable?.cancel()
        cancellable = null
        ringingRipples.setVisible(false)
        setConnectingLoading(false)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachedToWindow = true
        val url = currentAvatarUrl
        if (!url.isNullOrBlank() && cancellable == null && !avatarDrawable.hasPhoto()) {
            loadAvatar(url)
        }
    }

    private fun loadAvatar(url: String) {
        val sizePx = avatarSize
        val proxyUrl = avatarImgproxyUrl(url, sizePx)
        avatarDrawable.setLoadingPlaceholder(true)
        cancellable = MezonImageLoader.getInstance(context).load(
            proxyUrl,
            sizePx,
            sizePx,
            onSuccess = { bmp: Bitmap ->
                cancellable = null
                avatarDrawable.setLoadingPlaceholder(false)
                avatarDrawable.setPhoto(bmp)
                invalidate()
            },
            onError = {
                cancellable = null
                avatarDrawable.setLoadingPlaceholder(false)
                invalidate()
            }
        )
    }
}
