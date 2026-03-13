package com.mezon.mobile.home.clans

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.view.View
import coil.Coil
import coil.request.ImageRequest
import coil.size.Size
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.MezonIcon

class DmLogoCell(
    context: Context,
    private val themeColors: ThemeColors
) : View(context) {

    companion object {
        private const val DEFAULT_LOGO_URL = "https://cdn.mezon.ai/landing-page-mezon/logodefault.webp"
    }

    private val iconSizePx = LayoutHelper.dp(40)
    private val fallbackDrawable: Drawable = MezonIcon.logoMezon.getDrawable(context).mutate()
    private var logoBitmap: Bitmap? = null
    private var currentLogoUrl: String = ""

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
        val effectiveUrl = url.ifEmpty { DEFAULT_LOGO_URL }
        if (currentLogoUrl == effectiveUrl) return
        currentLogoUrl = effectiveUrl
        logoBitmap = null
        val request = ImageRequest.Builder(context)
            .data(effectiveUrl)
            .size(Size(iconSizePx, iconSizePx))
            .allowHardware(false)
            .target(onSuccess = { d ->
                logoBitmap = (d as? BitmapDrawable)?.bitmap
                post { invalidate() }
            })
            .build()
        Coil.imageLoader(context).enqueue(request)
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

        val bmp = logoBitmap
        if (bmp != null && !bmp.isRecycled) {
            canvas.drawBitmap(bmp, null, android.graphics.Rect(left, top, right, bottom), null)
        } else {
            fallbackDrawable.setBounds(left, top, right, bottom)
            fallbackDrawable.draw(canvas)
        }

        if (pendingCount > 0) {
            badgeBgPaint.color = themeColors.badgeRed
            val badgeH = LayoutHelper.dp(16f).toFloat()
            val textW = badgeTextPaint.measureText(badgeText)
            val padH = LayoutHelper.dp(4f).toFloat()
            val badgeW = (textW + padH * 2).coerceAtLeast(badgeH)
            val badgeRadius = badgeH / 2f

            val badgeRight = right.toFloat() + LayoutHelper.dp(2f)
            val badgeLeft = badgeRight - badgeW
            val badgeTop = top.toFloat() - LayoutHelper.dp(2f)
            badgeRect.set(badgeLeft, badgeTop, badgeRight, badgeTop + badgeH)

            canvas.drawRoundRect(badgeRect, badgeRadius, badgeRadius, badgeBgPaint)
            val textY = badgeRect.centerY() - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2
            canvas.drawText(badgeText, badgeRect.centerX(), textY, badgeTextPaint)
        }
    }
}
