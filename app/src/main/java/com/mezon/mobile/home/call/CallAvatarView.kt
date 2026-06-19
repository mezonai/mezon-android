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
    private val nameGapBelowAvatar = LayoutHelper.dp(36)
    private val statusGapBelowName = LayoutHelper.dp(24)
    private val belowAvatarContentDp = 92
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = LayoutHelper.dp(2).toFloat()
    }
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
    private var ringAnimProgress = 0f
    private var ringAnimator: ValueAnimator? = null
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

    fun setData(name: String, username: String, avatarUrl: String?) {
        peerName = name
        avatarDrawable.setInfo(0L, username.ifEmpty { name })
        if (currentAvatarUrl != avatarUrl) {
            currentAvatarUrl = avatarUrl
            cancellable?.cancel()
            cancellable = null
            if (avatarUrl.isNullOrBlank()) {
                avatarDrawable.setPhoto(null)
            } else if (attachedToWindow) {
                loadAvatar(avatarUrl)
            }
        }
        invalidate()
    }

    fun setStatus(status: String) {
        statusText = status
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
            stopRingAnimation()
            setConnectingLoading(false)
        } else {
            if (!connectingSpinner) {
                startRingAnimation()
            }
        }
        invalidate()
    }

    fun startRingAnimation() {
        if (ringAnimator != null) return
        ringAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3500
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                ringAnimProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stopRingAnimation() {
        ringAnimator?.cancel()
        ringAnimator = null
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, avatarSize + LayoutHelper.dp(belowAvatarContentDp))
    }

    override fun onDraw(canvas: Canvas) {
        val theme = themeColors
        ringPaint.color = theme.borderDim and 0x00FFFFFF or (0x55 shl 24)
        connectedBorderPaint.color = theme.connectedColor
        namePaint.color = theme.colorText
        statusPaint.color = theme.onSurfaceVariant

        val cx = width / 2f
        val avatarTop = LayoutHelper.dp(10).toFloat()
        val avatarRadius = avatarSize / 2f

        if (!isConnected) {
            drawRingCircles(canvas, cx, avatarTop + avatarRadius)
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

    private fun drawRingCircles(canvas: Canvas, cx: Float, cy: Float) {
        val baseSizes = floatArrayOf(
            LayoutHelper.dp(50).toFloat(),
            LayoutHelper.dp(160).toFloat(),
            LayoutHelper.dp(180).toFloat()
        )
        val delays = floatArrayOf(0f, 0.25f, 0.5f)

        for (i in 0..2) {
            val localProgress = (ringAnimProgress + delays[i]) % 1f
            val scale = 1f + localProgress * 1.8f
            val alpha = when {
                localProgress < 0.2f -> localProgress / 0.2f * 0.8f
                localProgress < 0.6f -> 0.8f - (localProgress - 0.2f) / 0.4f * 0.5f
                else -> 0.3f - (localProgress - 0.6f) / 0.4f * 0.3f
            }.coerceIn(0f, 1f)

            ringPaint.alpha = (alpha * 255).toInt()
            val radius = baseSizes[i] / 2f * scale
            canvas.drawCircle(cx, cy, radius, ringPaint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        attachedToWindow = false
        cancellable?.cancel()
        cancellable = null
        stopRingAnimation()
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
        cancellable = MezonImageLoader.getInstance(context).load(
            proxyUrl,
            sizePx,
            sizePx,
            onSuccess = { bmp: Bitmap ->
                cancellable = null
                avatarDrawable.setPhoto(bmp)
                invalidate()
            },
            onError = {
                cancellable = null
                avatarDrawable.setPhoto(null)
                invalidate()
            }
        )
    }
}
