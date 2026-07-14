package com.mezon.mobile.home.clans

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.view.View
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.util.avatarImgproxyUrl

class DmLogoCell(
    context: Context,
    private val themeColors: ThemeColors
) : View(context) {

    companion object {
        private const val DEFAULT_LOGO_URL = "https://cdn.komu.vn/landing-page-mezon/logodefault.webp"

        private val BADGE_HEIGHT = LayoutHelper.dp(16f).toFloat()
        private val BADGE_PAD_H = LayoutHelper.dp(4f).toFloat()
        private val BADGE_OFFSET = LayoutHelper.dp(2f).toFloat()
    }

    private val iconSizePx = LayoutHelper.dp(42)
    private val cornerRadius = LayoutHelper.dp(8).toFloat()
    private val fallbackDrawable: Drawable = MezonIcon.logoMezon.getDrawable(context).mutate()
    private var logoBitmap: Bitmap? = null
    private var currentLogoUrl: String = ""
    private var logoCancellable: MezonImageLoader.Cancellable? = null
    private val clipPath = Path()
    private val clipRect = RectF()
    private var clipPathLeft = Int.MIN_VALUE
    private var clipPathTop = Int.MIN_VALUE
    private var clipPathRight = Int.MIN_VALUE
    private var clipPathBottom = Int.MIN_VALUE
    private val bitmapDestRect = android.graphics.Rect()

    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(9f)
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        color = android.graphics.Color.WHITE
    }
    private val badgeRect = RectF()
    private var pendingCount = 0
    private var badgeText = ""

    fun setLogoUrl(url: String) {
        val raw = url.ifEmpty { DEFAULT_LOGO_URL }
        val loadUrl = avatarImgproxyUrl(raw, iconSizePx).ifEmpty { raw }
        if (currentLogoUrl == loadUrl && logoBitmap != null) return
        currentLogoUrl = loadUrl

        logoCancellable?.cancel()
        logoCancellable = null

        val loader = MezonImageLoader.getInstance(context)
        val cached = loader.getBitmapFromMemory(loadUrl, iconSizePx, iconSizePx)
        if (cached != null) {
            logoBitmap = cached
            invalidate()
            return
        }

        logoBitmap = null
        logoCancellable = loader.load(
            loadUrl, iconSizePx, iconSizePx,
            onSuccess = { bmp ->
                logoBitmap = bmp
                invalidate()
            }
        )
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        logoCancellable?.cancel()
        logoCancellable = null
    }

    fun setPendingFriendCount(count: Int) {
        if (pendingCount == count) return
        pendingCount = count
        badgeText = when {
            count <= 0 -> ""
            count > 99 -> "99+"
            else -> count.toString()
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, iconSizePx + LayoutHelper.dp(16))
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val half = iconSizePx / 2

        val left = (cx - half).toInt()
        val top = (cy - half).toInt()
        val right = (cx + half).toInt()
        val bottom = (cy + half).toInt()

        clipRect.set(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
        if (clipPathLeft != left || clipPathTop != top || clipPathRight != right || clipPathBottom != bottom) {
            clipPathLeft = left
            clipPathTop = top
            clipPathRight = right
            clipPathBottom = bottom
            clipPath.reset()
            clipPath.addRoundRect(clipRect, cornerRadius, cornerRadius, Path.Direction.CW)
        }

        canvas.save()
        canvas.clipPath(clipPath)

        val bmp = logoBitmap
        if (bmp != null && !bmp.isRecycled) {
            bitmapDestRect.set(left, top, right, bottom)
            canvas.drawBitmap(bmp, null, bitmapDestRect, null)
        } else {
            fallbackDrawable.setBounds(left, top, right, bottom)
            fallbackDrawable.draw(canvas)
        }

        canvas.restore()

        if (pendingCount > 0) {
            badgeBgPaint.color = themeColors.badgeRed
            val textW = badgeTextPaint.measureText(badgeText)
            val badgeW = (textW + BADGE_PAD_H * 2).coerceAtLeast(BADGE_HEIGHT)
            val badgeRadius = BADGE_HEIGHT / 2f

            val badgeRight = right.toFloat() + BADGE_OFFSET
            val badgeLeft = badgeRight - badgeW
            val badgeTop = top.toFloat() - BADGE_OFFSET
            badgeRect.set(badgeLeft, badgeTop, badgeRight, badgeTop + BADGE_HEIGHT)

            canvas.drawRoundRect(badgeRect, badgeRadius, badgeRadius, badgeBgPaint)
            val textY = badgeRect.centerY() - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2
            canvas.drawText(badgeText, badgeRect.centerX(), textY, badgeTextPaint)
        }
    }
}
