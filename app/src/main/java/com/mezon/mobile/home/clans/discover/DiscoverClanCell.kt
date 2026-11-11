package com.mezon.mobile.home.clans.discover

import com.mezon.mobile.ui.cells.drawCenteredIcon
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import com.mezon.mobile.R
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.util.avatarImgproxyUrl
import com.mezon.mobile.util.createImgproxyUrl
import kotlin.math.max

class DiscoverClanCell(
    context: Context,
    private val themeColors: ThemeColors
) : View(context) {

    companion object {
        private val AVATAR_RADIUS = LayoutHelper.dp(12f).toFloat()
        private val DOT_RADIUS = LayoutHelper.dp(4).toFloat()
        private val FOOTER_ROW_CENTER = LayoutHelper.dp(9)
        private val MEMBER_TEXT_GAP = LayoutHelper.dp(4)
        private val VERIFY_ICON_SIZE = LayoutHelper.dp(16)
        private val VERIFY_BADGE_PAD_H = LayoutHelper.dp(4).toFloat()
        private val VERIFY_BADGE_HEIGHT = LayoutHelper.dp(18)
        private val VERIFY_BADGE_RADIUS = LayoutHelper.dp(4f).toFloat()
        private val VERIFY_ICON_CENTER_Y = LayoutHelper.dp(9)
        private val VERIFY_TEXT_GAP = LayoutHelper.dp(4)
        private val VERIFY_TEXT_BASELINE = LayoutHelper.dp(12)
    }

    private var item: DiscoverClanItem? = null
    private var bannerCancellable: MezonImageLoader.Cancellable? = null
    private var avatarCancellable: MezonImageLoader.Cancellable? = null
    private var bannerBmp: Bitmap? = null
    private var avatarBmp: Bitmap? = null

    private val cardRect = RectF()
    private val bannerRect = RectF()
    private val bannerSrcRect = Rect()
    private val avatarDstRect = RectF()
    private val tmpPath = Path()
    private val clipPath = Path()
    private var cardPathWidth = Float.NaN
    private var cardPathTop = Float.NaN
    private var cardPathBottom = Float.NaN
    private var avatarPathLeft = Float.NaN
    private var avatarPathTop = Float.NaN

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = LayoutHelper.dp(1f).toFloat()
    }
    private val memberDotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val verifiedBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bannerPlaceholderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bannerDrawPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        isDither = true
    }
    private val verifyIconDrawable = MezonIcon.loadDrawable(context, R.drawable.mz_verified_16, Color.WHITE)

    private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(13f)
        typeface = Typeface.DEFAULT_BOLD
    }
    private val descPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(11f)
    }
    private val memberPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(10f)
    }
    private val verifiedPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(10f)
        typeface = Typeface.DEFAULT_BOLD
        color = Color.WHITE
    }

    private var nameLayout: StaticLayout? = null
    private var descLayout: StaticLayout? = null
    private var memberLabel: String = ""
    private var lastBannerLoadWidthPx = -1

    private val bannerH = LayoutHelper.dp(124)
    private val cardRadius = LayoutHelper.dp(12f).toFloat()
    private val marginBottom = LayoutHelper.dp(16)
    private val contentPad = LayoutHelper.dp(10)
    private val avatarSize = LayoutHelper.dp(20)
    private val nameGap = LayoutHelper.dp(8)
    private val descGapBottom = LayoutHelper.dp(8)
    private val footerGap = LayoutHelper.dp(6)

    init {
        applyThemeColors()
    }

    private fun applyThemeColors() {
        namePaint.color = themeColors.onSurface
        descPaint.color = themeColors.onSurfaceVariant
        memberPaint.color = themeColors.onSurfaceVariant
    }

    fun setData(data: DiscoverClanItem) {
        if (item?.clanId == data.clanId &&
            item?.banner == data.banner &&
            item?.clanLogo == data.clanLogo &&
            item?.clanName == data.clanName &&
            item?.description == data.description &&
            item?.totalMembers == data.totalMembers &&
            item?.verified == data.verified
        ) {
            return
        }
        item = data
        lastBannerLoadWidthPx = -1
        memberLabel = "${data.totalMembers} ${context.getString(R.string.discover_members)}"
        loadImages(data)
        if (width > 0) {
            lastBannerLoadWidthPx = width
        }
        requestLayout()
        invalidate()
    }

    private fun loadImages(data: DiscoverClanItem) {
        bannerCancellable?.cancel()
        avatarCancellable?.cancel()
        bannerBmp = null
        avatarBmp = null
        val wPx = width.takeIf { it > 0 } ?: LayoutHelper.dp(360)
        val reqBannerW = max(wPx * 4, LayoutHelper.dp(1280))
        val reqBannerH = max(bannerH * 4, LayoutHelper.dp(512))
        val loader = MezonImageLoader.getInstance(context)
        if (data.banner.isNotEmpty()) {
            val bannerUrl = createImgproxyUrl(data.banner, reqBannerW, reqBannerH, "fit")
            bannerCancellable = loader.load(
                bannerUrl, reqBannerW, reqBannerH,
                onSuccess = { bmp ->
                    bannerBmp = bmp
                    invalidate()
                }
            )
        }
        val av = LayoutHelper.dp(60)
        if (data.clanLogo.isNotEmpty()) {
            val logoUrl = avatarImgproxyUrl(data.clanLogo, av)
            avatarCancellable = loader.load(
                logoUrl, av, av,
                onSuccess = { bmp ->
                    avatarBmp = bmp
                    invalidate()
                }
            )
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        bannerCancellable?.cancel()
        avatarCancellable?.cancel()
    }

    private fun buildLayouts(contentWidth: Int) {
        val it = item ?: return
        namePaint.color = themeColors.onSurface
        descPaint.color = themeColors.onSurfaceVariant
        memberPaint.color = themeColors.onSurfaceVariant
        val nameW = contentWidth - contentPad * 2 - avatarSize - nameGap
        nameLayout = StaticLayout.Builder.obtain(it.clanName, 0, it.clanName.length, namePaint, max(1, nameW))
            .setMaxLines(1)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        val descW = contentWidth - contentPad * 2
        descLayout = StaticLayout.Builder.obtain(it.description, 0, it.description.length, descPaint, max(1, descW))
            .setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val it = item
        if (it != null) {
            buildLayouts(width)
            val nameH = nameLayout?.height ?: LayoutHelper.dp(14)
            val descH = descLayout?.height ?: 0
            val rowH = max(avatarSize, nameH)
            val footerH = LayoutHelper.dp(14)
            val h = marginBottom + bannerH + contentPad + rowH + descGapBottom + descH + descGapBottom + footerH + contentPad
            setMeasuredDimension(width, h)
        } else {
            setMeasuredDimension(width, LayoutHelper.dp(120))
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (item != null) buildLayouts(w)
        val it = item ?: return
        if (w <= 0) return
        if (w != lastBannerLoadWidthPx) {
            lastBannerLoadWidthPx = w
            loadImages(it)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val it = item ?: return
        val w = width.toFloat()
        val left = 0f
        val top = marginBottom.toFloat()
        val right = w
        val bottom = height.toFloat()

        cardRect.set(left, top, right, bottom)
        if (cardPathWidth != right || cardPathTop != top || cardPathBottom != bottom) {
            cardPathWidth = right
            cardPathTop = top
            cardPathBottom = bottom
            tmpPath.rewind()
            tmpPath.addRoundRect(cardRect, cardRadius, cardRadius, Path.Direction.CW)
        }

        canvas.save()
        canvas.clipPath(tmpPath)

        bgPaint.color = themeColors.secondaryLight
        canvas.drawPath(tmpPath, bgPaint)

        bannerRect.set(left, top, right, top + bannerH)
        val b = bannerBmp
        if (b != null && !b.isRecycled) {
            val bmpW = b.width
            val bmpH = b.height
            if (bmpW > 0 && bmpH > 0) {
                val dstAspect = bannerRect.width() / bannerRect.height()
                val bmpAspect = bmpW.toFloat() / bmpH.toFloat()
                if (bmpAspect > dstAspect) {
                    val srcW = (bmpH * dstAspect).toInt().coerceAtLeast(1)
                    val srcL = (bmpW - srcW) / 2
                    bannerSrcRect.set(srcL, 0, srcL + srcW, bmpH)
                } else {
                    val srcH = (bmpW / dstAspect).toInt().coerceAtLeast(1)
                    val srcT = (bmpH - srcH) / 2
                    bannerSrcRect.set(0, srcT, bmpW, srcT + srcH)
                }
                canvas.drawBitmap(b, bannerSrcRect, bannerRect, bannerDrawPaint)
            }
        } else {
            bannerPlaceholderPaint.color = themeColors.surfaceVariant
            canvas.drawRect(bannerRect, bannerPlaceholderPaint)
        }

        val contentTop = top + bannerH + contentPad
        val ax = left + contentPad
        val av = avatarBmp
        if (av != null && !av.isRecycled) {
            avatarDstRect.set(ax, contentTop, ax + avatarSize, contentTop + avatarSize)
            if (avatarPathLeft != ax || avatarPathTop != contentTop) {
                avatarPathLeft = ax
                avatarPathTop = contentTop
                clipPath.reset()
                clipPath.addRoundRect(avatarDstRect, AVATAR_RADIUS, AVATAR_RADIUS, Path.Direction.CW)
            }
            canvas.save()
            canvas.clipPath(clipPath)
            canvas.drawBitmap(av, null, avatarDstRect, bannerDrawPaint)
            canvas.restore()
        }

        val nameX = ax + avatarSize + nameGap
        val nl = nameLayout
        if (nl != null) {
            canvas.save()
            canvas.translate(nameX, contentTop.toFloat())
            nl.draw(canvas)
            canvas.restore()
        }

        val descY = contentTop + max(avatarSize, nl?.height ?: 0) + descGapBottom
        val dl = descLayout
        if (dl != null) {
            canvas.save()
            canvas.translate(left + contentPad, descY)
            dl.draw(canvas)
            canvas.restore()
        }

        val footerY = descY + (dl?.height ?: 0) + descGapBottom
        val rowCenterY = footerY + FOOTER_ROW_CENTER
        val dotCx = left + contentPad + DOT_RADIUS
        val dotCy = rowCenterY
        memberDotPaint.color = themeColors.connectedColor
        canvas.drawCircle(dotCx, dotCy, DOT_RADIUS, memberDotPaint)

        val memberFm = memberPaint.fontMetrics
        val memberBaseline = rowCenterY - (memberFm.ascent + memberFm.descent) / 2f

        canvas.drawText(
            memberLabel,
            left + contentPad + MEMBER_TEXT_GAP + footerGap + MEMBER_TEXT_GAP,
            memberBaseline,
            memberPaint
        )

        val verified = context.getString(R.string.discover_verified).uppercase()
        val vw = verifiedPaint.measureText(verified)
        val badgeW = VERIFY_BADGE_PAD_H * 2 + VERIFY_ICON_SIZE + VERIFY_TEXT_GAP + vw
        val bx = right - contentPad - badgeW
        val by = footerY
        verifiedBgPaint.color = themeColors.connectedColor
        canvas.drawRoundRect(
            bx, by, bx + badgeW, by + VERIFY_BADGE_HEIGHT,
            VERIFY_BADGE_RADIUS,
            VERIFY_BADGE_RADIUS,
            verifiedBgPaint
        )
        verifyIconDrawable.drawCenteredIcon(canvas, (bx + VERIFY_BADGE_PAD_H + VERIFY_ICON_SIZE / 2).toInt(), (by + VERIFY_ICON_CENTER_Y).toInt())
        canvas.drawText(verified, bx + VERIFY_BADGE_PAD_H + VERIFY_ICON_SIZE + VERIFY_TEXT_GAP, by + VERIFY_TEXT_BASELINE, verifiedPaint)

        canvas.restore()

        borderPaint.color = themeColors.borderDim
        canvas.drawPath(tmpPath, borderPaint)
    }
}
