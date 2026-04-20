package com.mezon.mobile.home.qr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import com.mezon.mobile.core.BaseCell
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.AvatarView

/**
 * QR invite card drawn entirely via Canvas (no external libs, no Compose).
 *
 * Layout inside white rounded card:
 *
 *   ┌────────────────────────────────┐
 *   │  ○ Mezon                       │  ← circle logo (pink stroke) + bold text
 *   │  ┌──────────────────────────┐  │
 *   │  │        QR bitmap         │  │
 *   │  │      ┌─────────┐         │  │
 *   │  │      │  avatar │         │  │  ← avatar overlay in center of QR
 *   │  │      └─────────┘         │  │
 *   │  └──────────────────────────┘  │
 *   │  ─────────────────────────     │  ← horizontal divider line
 *   │  Powered by Mezon              │  ← footer text centered
 *   └────────────────────────────────┘
 */
class QrInviteCardCell(
    context: Context,
    private val theme: ThemeColors
) : BaseCell(context) {

    data class Model(
        val title: String,        // username shown next to logo (not used in layout here, kept for compat)
        val subtitle: String,     // hint text below buttons — not drawn in card
        val qrBitmap: Bitmap,
        val avatarUrl: String,
        val avatarName: String
    )

    private var model: Model? = null

    // ── Paints ────────────────────────────────────────────────────────────────

    // Card background
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    // Logo circle — pink/magenta stroke only (matching image: ~#E91E8C pink-purple)
    private val logoCirPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = LayoutHelper.dp(2.5f).toFloat()
        color = 0xFFE91E8C.toInt()   // hot pink matching the image
    }

    // Logo "Mezon" text
    private val logoTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(22f)
        color = Color.BLACK
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    // Divider line
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFDDDDDD.toInt()
        strokeWidth = LayoutHelper.dp(1f).toFloat()
    }

    // "Powered by Mezon" footer
    private val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(13f)
        color = 0xFF333333.toInt()
        textAlign = Paint.Align.CENTER
    }

    // White background behind avatar (so avatar is visible over dark QR pixels)
    private val avatarBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    // Avatar view
    private val avatarView = AvatarView(context).apply {
        setSizeDp(40)
        setRoundRadius(6f)
    }

    init {
        setWillNotDraw(false)
        addView(avatarView)
    }

    fun bind(model: Model) {
        this.model = model
        avatarView.setInfo(model.avatarName.hashCode().toLong(), model.avatarName)
        if (model.avatarUrl.isNotEmpty()) avatarView.setImageUrl(model.avatarUrl)
        requestLayout()
        invalidate()
    }

    // ── Computed layout values ─────────────────────────────────────────────────
    private var cardPad = 0         // card horizontal padding from cell edge
    private var cardTop = 0
    private var logoRowY = 0        // center Y of the ○ Mezon row
    private var qrLeft = 0
    private var qrTop = 0
    private var qrSize = 0
    private var avatarSizePx = 0
    private var avatarLeft = 0
    private var avatarTop = 0
    private var dividerY = 0f
    private var footerY = 0f
    private var totalH = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)

        cardPad   = LayoutHelper.dp(0)   // card spans full cell width
        cardTop   = LayoutHelper.dp(0)

        // Logo row
        val logoRowH = LayoutHelper.dp(48)
        val logoAreaTop = LayoutHelper.dp(20)
        logoRowY = logoAreaTop + logoRowH / 2

        // QR dimensions — centered horizontally
        qrSize = w - LayoutHelper.dp(48)   // 24dp margin each side
        qrLeft = (w - qrSize) / 2           // explicit center
        qrTop  = logoAreaTop + logoRowH + LayoutHelper.dp(16)

        // Avatar in center of QR
        avatarSizePx = LayoutHelper.dp(40)
        avatarLeft = qrLeft + (qrSize - avatarSizePx) / 2
        avatarTop  = qrTop  + (qrSize - avatarSizePx) / 2

        // Divider
        dividerY = (qrTop + qrSize + LayoutHelper.dp(20)).toFloat()

        // Footer text
        footerY = dividerY + LayoutHelper.dp(28)

        // Total height
        totalH = (footerY + LayoutHelper.dp(20)).toInt()

        setMeasuredDimension(w, totalH)

        avatarView.measure(
            MeasureSpec.makeMeasureSpec(avatarSizePx, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(avatarSizePx, MeasureSpec.EXACTLY)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        avatarView.layout(avatarLeft, avatarTop, avatarLeft + avatarSizePx, avatarTop + avatarSizePx)
    }

    override fun onDraw(canvas: Canvas) {
        val m = model ?: return
        val w = width.toFloat()

        // ── White card background ──────────────────────────────────────────────
        val cardRadius = LayoutHelper.dp(16f).toFloat()
        val cardRect = RectF(cardPad.toFloat(), cardTop.toFloat(),
            w - cardPad, totalH.toFloat())
        canvas.drawRoundRect(cardRect, cardRadius, cardRadius, cardPaint)

        // ── Logo row: "○  Mezon" ───────────────────────────────────────────────
        val cirRadius = LayoutHelper.dp(13f).toFloat()
        val logoStartX = w / 2f - (cirRadius * 2 + LayoutHelper.dp(8) + logoTextPaint.measureText("Mezon")) / 2f
        val cirCx = logoStartX + cirRadius
        val cirCy = logoRowY.toFloat()
        canvas.drawCircle(cirCx, cirCy, cirRadius, logoCirPaint)

        val textX = cirCx + cirRadius + LayoutHelper.dp(8)
        val textY = cirCy - (logoTextPaint.descent() + logoTextPaint.ascent()) / 2f
        canvas.drawText("Mezon", textX, textY, logoTextPaint)

        // ── QR bitmap ─────────────────────────────────────────────────────────
        canvas.drawBitmap(
            m.qrBitmap, null,
            RectF(qrLeft.toFloat(), qrTop.toFloat(),
                (qrLeft + qrSize).toFloat(), (qrTop + qrSize).toFloat()),
            null
        )

        // ── White bg behind avatar (drawn over QR) ────────────────────────────
        val avPad = LayoutHelper.dp(3f).toFloat()
        canvas.drawRoundRect(
            RectF(avatarLeft - avPad, avatarTop - avPad,
                avatarLeft + avatarSizePx + avPad, avatarTop + avatarSizePx + avPad),
            LayoutHelper.dp(8f).toFloat(), LayoutHelper.dp(8f).toFloat(),
            avatarBgPaint
        )
        // AvatarView draws itself on top via normal View drawing

        // ── Divider ────────────────────────────────────────────────────────────
        val divMargin = LayoutHelper.dp(24f).toFloat()
        canvas.drawLine(divMargin, dividerY, w - divMargin, dividerY, dividerPaint)

        // ── "Powered by Mezon" ─────────────────────────────────────────────────
        canvas.drawText("Powered by Mezon", w / 2f, footerY, footerPaint)
    }
}
