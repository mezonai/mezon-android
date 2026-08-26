package com.mezon.mobile.home.qr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseCell
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.AvatarView
import com.mezon.mobile.ui.cells.MezonIcon
import kotlin.math.ceil

internal object MyQrSnapshotStyle {
    const val NAME_TEXT_SIZE_SP = 18f
    const val BRAND_TEXT_SIZE_SP = 14f
    const val QR_TYPE_TEXT_SIZE_SP = 10f

    val gradientColors = intArrayOf(
        0xFFF0EDFD.toInt(),
        0xFFBEB5F8.toInt(),
        0xFF9774FA.toInt()
    )
    val primaryText = 0xFF070709.toInt()
    val badgeBackground = Color.WHITE
    val badgeIcon = 0xFF2F80ED.toInt()
    val qrTypeBackground = 0xFFF0EEFF.toInt()
    val qrAccent = 0xFF6657F5.toInt()
}

class QrInviteCardCell(
    context: Context,
    private val theme: ThemeColors
) : BaseCell(context) {

    companion object {
        private val INVITE_CARD_RADIUS = LayoutHelper.dp(16f).toFloat()
        private val INVITE_LOGO_CIRCLE_RADIUS = LayoutHelper.dp(13f).toFloat()
        private val INVITE_LOGO_TEXT_GAP = LayoutHelper.dp(8)
        private val INVITE_AVATAR_BACKGROUND_PADDING = LayoutHelper.dp(3f).toFloat()
        private val INVITE_AVATAR_BACKGROUND_RADIUS = LayoutHelper.dp(8f).toFloat()
        private val INVITE_DIVIDER_MARGIN = LayoutHelper.dp(24f).toFloat()
        private val INVITE_AVATAR_SIZE = LayoutHelper.dp(40)

        private val PANEL_SIDE_MARGIN = LayoutHelper.dp(16f).toFloat()
        private val PANEL_HORIZONTAL_PADDING = LayoutHelper.dp(16f).toFloat()
        private val PANEL_VERTICAL_CONTENT_GAP = LayoutHelper.dp(8f).toFloat()
        private val PANEL_RADIUS = LayoutHelper.dp(16f).toFloat()
        private val PANEL_HEADER_TOP_PADDING = LayoutHelper.dp(14f).toFloat()
        private val PANEL_HEADER_HEIGHT = LayoutHelper.dp(20f).toFloat()
        private val PANEL_BOTTOM_PADDING = PANEL_HEADER_TOP_PADDING
        private val LOGO_ICON_SIZE = LayoutHelper.dp(20)
        private val LOGO_TEXT_GAP = LayoutHelper.dp(6)
        private val AVATAR_BG_PAD = LayoutHelper.dp(2f).toFloat()
        private val AVATAR_BG_RADIUS = LayoutHelper.dp(8f).toFloat()
        private val QR_TARGET_SIZE = LayoutHelper.dp(264)
        private val QR_AVATAR_SIZE = LayoutHelper.dp(48)
        private val TYPE_BADGE_HEIGHT = LayoutHelper.dp(20f).toFloat()
        private val TYPE_BADGE_HORIZONTAL_PADDING = LayoutHelper.dp(8f).toFloat()
        private val VERIFIED_ICON_SIZE = LayoutHelper.dp(14)
        private val VERIFIED_ICON_TEXT_GAP = LayoutHelper.dp(4f).toFloat()
    }

    enum class Appearance {
        INVITE,
        PERSONAL
    }

    data class Model(
        val qrBitmap: Bitmap,
        val avatarUrl: String,
        val avatarName: String,
        val appearance: Appearance,
        val qrTypeLabel: String = "",
        val centerBitmap: Bitmap? = null
    )

    private var model: Model? = null

    private val logoTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(MyQrSnapshotStyle.BRAND_TEXT_SIZE_SP)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = MyQrSnapshotStyle.primaryText
    }
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }
    private val typeBadgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MyQrSnapshotStyle.qrTypeBackground
    }
    private val typeTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(MyQrSnapshotStyle.QR_TYPE_TEXT_SIZE_SP)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = MyQrSnapshotStyle.qrAccent
    }
    private val avatarBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.qrAvatarBackground
    }
    private val inviteCardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.surface
    }
    private val inviteLogoCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = LayoutHelper.dp(2.5f).toFloat()
        color = theme.qrBrandAccent
    }
    private val inviteLogoTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(22f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = theme.onSurface
    }
    private val inviteDividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = LayoutHelper.dp(1f).toFloat()
        color = theme.outlineVariant
    }
    private val inviteFooterPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(13f)
        textAlign = Paint.Align.CENTER
        color = theme.onSurfaceVariant
    }
    private val inviteBrandText = context.getString(R.string.qr_brand_title)
    private val brandText = context.getString(R.string.qr_brand_title).uppercase()
    private val verifiedText = context.getString(R.string.qr_verified_by).uppercase()
    private val mezonLogo = MezonIcon.logoMezon.getDrawable(context)
    private val verifyIcon = MezonIcon.verifyIcon.getDrawable(context).apply {
        setTint(MyQrSnapshotStyle.badgeIcon)
    }

    private val avatarView = AvatarView(context).apply {
        setSizeDp(48)
        setRoundRadius(8f)
    }

    private var panelLeft = 0f
    private var panelRight = 0f
    private var panelBottom = 0f
    private var headerCenterY = 0f
    private var footerCenterY = 0f
    private var qrLeft = 0
    private var qrTop = 0
    private var qrSize = 0
    private var avatarSize = QR_AVATAR_SIZE
    private var avatarLeft = 0
    private var avatarTop = 0
    private var inviteDividerY = 0f
    private var inviteFooterY = 0f

    private val panelRect = RectF()
    private val avatarBackgroundRect = RectF()
    private val qrBitmapRect = RectF()

    init {
        setWillNotDraw(false)
        addView(avatarView)
    }

    fun bind(model: Model) {
        this.model = model
        if (model.appearance == Appearance.PERSONAL) {
            avatarView.setSizeDp(48)
            avatarView.setRoundRadius(8f)
        } else {
            avatarView.setSizeDp(40)
            avatarView.setRoundRadius(6f)
        }
        avatarView.setInfo(model.avatarName.hashCode().toLong(), model.avatarName)
        avatarView.setPhoto(null)
        avatarView.setImageUrl(null)
        if (model.centerBitmap != null) {
            avatarView.setPhoto(model.centerBitmap)
        } else if (model.avatarUrl.isNotEmpty()) {
            avatarView.setImageUrl(model.avatarUrl)
        }
        requestLayout()
        invalidate()
    }

    fun getQrSizePx(): Int = qrSize

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)

        when (model?.appearance ?: Appearance.INVITE) {
            Appearance.INVITE -> measureInvite(width)
            Appearance.PERSONAL -> measurePersonal(width)
        }

        setMeasuredDimension(width, ceil(panelBottom).toInt())
        avatarView.measure(
            MeasureSpec.makeMeasureSpec(avatarSize, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(avatarSize, MeasureSpec.EXACTLY)
        )
    }

    private fun measurePersonal(width: Int) {
        avatarSize = QR_AVATAR_SIZE

        qrSize = minOf(
            QR_TARGET_SIZE,
            width - (PANEL_SIDE_MARGIN + PANEL_HORIZONTAL_PADDING).toInt() * 2
        ).coerceAtLeast(0)
        qrLeft = (width - qrSize) / 2
        panelLeft = qrLeft - PANEL_HORIZONTAL_PADDING
        panelRight = qrLeft + qrSize + PANEL_HORIZONTAL_PADDING

        headerCenterY = PANEL_HEADER_TOP_PADDING + PANEL_HEADER_HEIGHT / 2f
        qrTop = ceil(
            PANEL_HEADER_TOP_PADDING + PANEL_HEADER_HEIGHT + PANEL_VERTICAL_CONTENT_GAP
        ).toInt()

        avatarLeft = qrLeft + (qrSize - QR_AVATAR_SIZE) / 2
        avatarTop = qrTop + (qrSize - QR_AVATAR_SIZE) / 2

        footerCenterY = qrTop + qrSize + PANEL_VERTICAL_CONTENT_GAP + TYPE_BADGE_HEIGHT / 2f
        panelBottom = footerCenterY + TYPE_BADGE_HEIGHT / 2f + PANEL_BOTTOM_PADDING
    }

    private fun measureInvite(width: Int) {
        val logoAreaTop = LayoutHelper.dp(20)
        val logoRowHeight = LayoutHelper.dp(48)
        avatarSize = INVITE_AVATAR_SIZE
        panelLeft = 0f
        panelRight = width.toFloat()
        headerCenterY = (logoAreaTop + logoRowHeight / 2).toFloat()
        qrSize = (width - LayoutHelper.dp(48)).coerceAtLeast(0)
        qrLeft = (width - qrSize) / 2
        qrTop = logoAreaTop + logoRowHeight + LayoutHelper.dp(16)
        avatarLeft = qrLeft + (qrSize - avatarSize) / 2
        avatarTop = qrTop + (qrSize - avatarSize) / 2
        inviteDividerY = (qrTop + qrSize + LayoutHelper.dp(20)).toFloat()
        inviteFooterY = inviteDividerY + LayoutHelper.dp(28)
        panelBottom = inviteFooterY + LayoutHelper.dp(20)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        avatarView.layout(
            avatarLeft,
            avatarTop,
            avatarLeft + avatarSize,
            avatarTop + avatarSize
        )
    }

    override fun onDraw(canvas: Canvas) {
        val currentModel = model ?: return
        when (currentModel.appearance) {
            Appearance.INVITE -> drawInvite(canvas, currentModel)
            Appearance.PERSONAL -> drawPersonal(canvas, currentModel)
        }
    }

    private fun drawPersonal(canvas: Canvas, currentModel: Model) {
        panelRect.set(panelLeft, 0f, panelRight, panelBottom)
        canvas.drawRoundRect(panelRect, PANEL_RADIUS, PANEL_RADIUS, panelPaint)

        val logoStartX = panelLeft + PANEL_HORIZONTAL_PADDING
        val logoTop = headerCenterY - LOGO_ICON_SIZE / 2f
        MezonIcon.drawIcon(
            canvas,
            mezonLogo,
            logoStartX.toInt(),
            logoTop.toInt(),
            (logoStartX + LOGO_ICON_SIZE).toInt(),
            (logoTop + LOGO_ICON_SIZE).toInt()
        )

        val logoTextX = logoStartX + LOGO_ICON_SIZE + LOGO_TEXT_GAP
        val logoTextY = headerCenterY - (logoTextPaint.descent() + logoTextPaint.ascent()) / 2f
        canvas.drawText(brandText, logoTextX, logoTextY, logoTextPaint)

        val typeTextWidth = typeTextPaint.measureText(currentModel.qrTypeLabel)
        val typeBadgeWidth = typeTextWidth + TYPE_BADGE_HORIZONTAL_PADDING * 2f
        val typeBadgeRight = panelRight - PANEL_HORIZONTAL_PADDING
        val typeBadgeLeft = typeBadgeRight - typeBadgeWidth
        canvas.drawRoundRect(
            typeBadgeLeft,
            headerCenterY - TYPE_BADGE_HEIGHT / 2f,
            typeBadgeRight,
            headerCenterY + TYPE_BADGE_HEIGHT / 2f,
            TYPE_BADGE_HEIGHT / 2f,
            TYPE_BADGE_HEIGHT / 2f,
            typeBadgePaint
        )
        val typeTextY = headerCenterY - (typeTextPaint.descent() + typeTextPaint.ascent()) / 2f
        canvas.drawText(
            currentModel.qrTypeLabel,
            typeBadgeLeft + TYPE_BADGE_HORIZONTAL_PADDING,
            typeTextY,
            typeTextPaint
        )

        drawQrAndAvatarBackground(
            canvas,
            currentModel,
            AVATAR_BG_PAD,
            AVATAR_BG_RADIUS,
            avatarBackgroundPaint
        )

        val verifiedTextWidth = typeTextPaint.measureText(verifiedText)
        val verifiedBadgeWidth = TYPE_BADGE_HORIZONTAL_PADDING * 2f +
            VERIFIED_ICON_SIZE + VERIFIED_ICON_TEXT_GAP + verifiedTextWidth
        val verifiedBadgeLeft = (width - verifiedBadgeWidth) / 2f
        canvas.drawRoundRect(
            verifiedBadgeLeft,
            footerCenterY - TYPE_BADGE_HEIGHT / 2f,
            verifiedBadgeLeft + verifiedBadgeWidth,
            footerCenterY + TYPE_BADGE_HEIGHT / 2f,
            TYPE_BADGE_HEIGHT / 2f,
            TYPE_BADGE_HEIGHT / 2f,
            typeBadgePaint
        )

        val verifiedIconCenterX = verifiedBadgeLeft +
            TYPE_BADGE_HORIZONTAL_PADDING + VERIFIED_ICON_SIZE / 2f
        MezonIcon.drawIcon(
            canvas,
            verifyIcon,
            verifiedIconCenterX.toInt(),
            footerCenterY.toInt(),
            VERIFIED_ICON_SIZE
        )
        val verifiedTextX = verifiedBadgeLeft + TYPE_BADGE_HORIZONTAL_PADDING +
            VERIFIED_ICON_SIZE + VERIFIED_ICON_TEXT_GAP
        val verifiedTextY = footerCenterY -
            (typeTextPaint.descent() + typeTextPaint.ascent()) / 2f
        canvas.drawText(verifiedText, verifiedTextX, verifiedTextY, typeTextPaint)
    }

    private fun drawInvite(canvas: Canvas, currentModel: Model) {
        panelRect.set(0f, 0f, width.toFloat(), panelBottom)
        canvas.drawRoundRect(
            panelRect,
            INVITE_CARD_RADIUS,
            INVITE_CARD_RADIUS,
            inviteCardPaint
        )

        val logoStartX = width / 2f - (
            INVITE_LOGO_CIRCLE_RADIUS * 2 +
                INVITE_LOGO_TEXT_GAP +
                inviteLogoTextPaint.measureText(inviteBrandText)
            ) / 2f
        val logoCenterX = logoStartX + INVITE_LOGO_CIRCLE_RADIUS
        canvas.drawCircle(
            logoCenterX,
            headerCenterY,
            INVITE_LOGO_CIRCLE_RADIUS,
            inviteLogoCirclePaint
        )
        val logoTextX = logoCenterX + INVITE_LOGO_CIRCLE_RADIUS + INVITE_LOGO_TEXT_GAP
        val logoTextY = headerCenterY -
            (inviteLogoTextPaint.descent() + inviteLogoTextPaint.ascent()) / 2f
        canvas.drawText(inviteBrandText, logoTextX, logoTextY, inviteLogoTextPaint)

        drawQrAndAvatarBackground(
            canvas,
            currentModel,
            INVITE_AVATAR_BACKGROUND_PADDING,
            INVITE_AVATAR_BACKGROUND_RADIUS,
            avatarBackgroundPaint
        )

        canvas.drawLine(
            INVITE_DIVIDER_MARGIN,
            inviteDividerY,
            width - INVITE_DIVIDER_MARGIN,
            inviteDividerY,
            inviteDividerPaint
        )
        canvas.drawText(
            "Powered by Mezon",
            width / 2f,
            inviteFooterY,
            inviteFooterPaint
        )
    }

    private fun drawQrAndAvatarBackground(
        canvas: Canvas,
        currentModel: Model,
        avatarBackgroundPadding: Float,
        avatarBackgroundRadius: Float,
        backgroundPaint: Paint
    ) {
        qrBitmapRect.set(
            qrLeft.toFloat(),
            qrTop.toFloat(),
            (qrLeft + qrSize).toFloat(),
            (qrTop + qrSize).toFloat()
        )
        canvas.drawBitmap(currentModel.qrBitmap, null, qrBitmapRect, null)

        avatarBackgroundRect.set(
            avatarLeft - avatarBackgroundPadding,
            avatarTop - avatarBackgroundPadding,
            avatarLeft + avatarSize + avatarBackgroundPadding,
            avatarTop + avatarSize + avatarBackgroundPadding
        )
        canvas.drawRoundRect(
            avatarBackgroundRect,
            avatarBackgroundRadius,
            avatarBackgroundRadius,
            backgroundPaint
        )
    }
}
