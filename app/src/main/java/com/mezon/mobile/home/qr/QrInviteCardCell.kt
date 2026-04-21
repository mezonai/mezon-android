package com.mezon.mobile.home.qr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import com.mezon.mobile.core.BaseCell
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.AvatarView
class QrInviteCardCell(
    context: Context,
    private val theme: ThemeColors
) : BaseCell(context) {

    data class Model(
        val title: String,
        val subtitle: String,
        val qrBitmap: Bitmap,
        val avatarUrl: String,
        val avatarName: String
    )

    private var model: Model? = null


    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.surface }

    private val logoCirPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = LayoutHelper.dp(2.5f).toFloat()
        color = theme.qrBrandAccent
    }

    private val logoTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(22f)
        color = theme.onSurface
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.outlineVariant
        strokeWidth = LayoutHelper.dp(1f).toFloat()
    }

    private val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(13f)
        color = theme.onSurfaceVariant
        textAlign = Paint.Align.CENTER
    }

    private val avatarBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.qrAvatarBackground }

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
        cardPaint.color = theme.surface
        logoTextPaint.color = theme.onSurface
        logoCirPaint.color = theme.qrBrandAccent
        dividerPaint.color = theme.outlineVariant
        footerPaint.color = theme.onSurfaceVariant
        avatarBgPaint.color = theme.qrAvatarBackground
        avatarView.setInfo(model.avatarName.hashCode().toLong(), model.avatarName)
        if (model.avatarUrl.isNotEmpty()) avatarView.setImageUrl(model.avatarUrl)
        requestLayout()
        invalidate()
    }

    private var cardPad = 0      
    private var cardTop = 0
    private var logoRowY = 0      
    private var qrLeft = 0
    private var qrTop = 0
    private var qrSize = 0
    private var avatarSizePx = 0
    private var avatarLeft = 0
    private var avatarTop = 0
    private var dividerY = 0f
    private var footerY = 0f
    private var totalH = 0

    private val cardRect = RectF()
    private val avatarBgRect = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)

        cardPad   = LayoutHelper.dp(0)  
        cardTop   = LayoutHelper.dp(0)

        val logoRowH = LayoutHelper.dp(48)
        val logoAreaTop = LayoutHelper.dp(20)
        logoRowY = logoAreaTop + logoRowH / 2

        qrSize = w - LayoutHelper.dp(48)  
        qrLeft = (w - qrSize) / 2           
        qrTop  = logoAreaTop + logoRowH + LayoutHelper.dp(16)

        avatarSizePx = LayoutHelper.dp(40)
        avatarLeft = qrLeft + (qrSize - avatarSizePx) / 2
        avatarTop  = qrTop  + (qrSize - avatarSizePx) / 2

        dividerY = (qrTop + qrSize + LayoutHelper.dp(20)).toFloat()

        footerY = dividerY + LayoutHelper.dp(28)

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

        val cardRadius = LayoutHelper.dp(16f).toFloat()
        cardRect.set(
            cardPad.toFloat(),
            cardTop.toFloat(),
            w - cardPad,
            totalH.toFloat()
        )
        canvas.drawRoundRect(cardRect, cardRadius, cardRadius, cardPaint)

        val cirRadius = LayoutHelper.dp(13f).toFloat()
        val logoStartX = w / 2f - (cirRadius * 2 + LayoutHelper.dp(8) + logoTextPaint.measureText("Mezon")) / 2f
        val cirCx = logoStartX + cirRadius
        val cirCy = logoRowY.toFloat()
        canvas.drawCircle(cirCx, cirCy, cirRadius, logoCirPaint)

        val textX = cirCx + cirRadius + LayoutHelper.dp(8)
        val textY = cirCy - (logoTextPaint.descent() + logoTextPaint.ascent()) / 2f
        canvas.drawText("Mezon", textX, textY, logoTextPaint)

        canvas.drawBitmap(
            m.qrBitmap, null,
            RectF(qrLeft.toFloat(), qrTop.toFloat(),
                (qrLeft + qrSize).toFloat(), (qrTop + qrSize).toFloat()),
            null
        )

        val avPad = LayoutHelper.dp(3f).toFloat()
        avatarBgRect.set(
            avatarLeft - avPad,
            avatarTop - avPad,
            avatarLeft + avatarSizePx + avPad,
            avatarTop + avatarSizePx + avPad
        )
        canvas.drawRoundRect(
            avatarBgRect,
            LayoutHelper.dp(8f).toFloat(),
            LayoutHelper.dp(8f).toFloat(),
            avatarBgPaint
        )

        val divMargin = LayoutHelper.dp(24f).toFloat()
        canvas.drawLine(divMargin, dividerY, w - divMargin, dividerY, dividerPaint)

        canvas.drawText("Powered by Mezon", w / 2f, footerY, footerPaint)
    }
}
