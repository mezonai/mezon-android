package com.mezon.mobile.home.clans.discover

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
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

    private var item: DiscoverClanItem? = null
    private var bannerCancellable: MezonImageLoader.Cancellable? = null
    private var avatarCancellable: MezonImageLoader.Cancellable? = null
    private var bannerBmp: Bitmap? = null
    private var avatarBmp: Bitmap? = null

    private val cardRect = RectF()
    private val bannerRect = RectF()
    private val tmpPath = Path()
    private val clipPath = Path()

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
    private val verifyIconDrawable = MezonIcon.verifyIcon.getDrawable(context).apply {
        setTint(0xFFFFFFFF.toInt())
    }

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
        color = 0xFFFFFFFF.toInt()
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
        tmpPath.rewind()
        tmpPath.addRoundRect(cardRect, cardRadius, cardRadius, Path.Direction.CW)

        canvas.save()
        canvas.clipPath(tmpPath)

        bgPaint.color = themeColors.secondaryLight
        canvas.drawPath(tmpPath, bgPaint)

        bannerRect.set(left, top, right, top + bannerH)
        val b = bannerBmp
        if (b != null && !b.isRecycled) {
            canvas.drawBitmap(b, null, bannerRect, bannerDrawPaint)
        } else {
            bannerPlaceholderPaint.color = themeColors.surfaceVariant
            canvas.drawRect(bannerRect, bannerPlaceholderPaint)
        }

        val contentTop = top + bannerH + contentPad
        val ax = left + contentPad
        val av = avatarBmp
        val avatarRx = LayoutHelper.dp(12f).toFloat()
        val avatarRy = LayoutHelper.dp(12f).toFloat()
        if (av != null && !av.isRecycled) {
            clipPath.reset()
            clipPath.addRoundRect(
                ax, contentTop, ax + avatarSize, contentTop + avatarSize,
                avatarRx, avatarRy,
                Path.Direction.CW
            )
            canvas.save()
            canvas.clipPath(clipPath)
            canvas.drawBitmap(av, null, RectF(ax, contentTop, ax + avatarSize, contentTop + avatarSize), bannerDrawPaint)
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
        val dotRadius = LayoutHelper.dp(4).toFloat()
        val rowCenterY = footerY + LayoutHelper.dp(9)
        val dotCx = left + contentPad + dotRadius
        val dotCy = rowCenterY
        memberDotPaint.color = themeColors.connectedColor
        canvas.drawCircle(dotCx, dotCy, dotRadius, memberDotPaint)

        val memberFm = memberPaint.fontMetrics
        val memberBaseline = rowCenterY - (memberFm.ascent + memberFm.descent) / 2f

        canvas.drawText(
            memberLabel,
            left + contentPad + LayoutHelper.dp(4) + footerGap + LayoutHelper.dp(4),
            memberBaseline,
            memberPaint
        )

        val verified = context.getString(R.string.discover_verified).uppercase()
        val iconS = LayoutHelper.dp(14)
        val badgePadH = LayoutHelper.dp(4).toFloat()
        val vw = verifiedPaint.measureText(verified)
        val badgeW = badgePadH * 2 + iconS + LayoutHelper.dp(4) + vw
        val bx = right - contentPad - badgeW
        val by = footerY
        verifiedBgPaint.color = themeColors.connectedColor
        canvas.drawRoundRect(
            bx, by, bx + badgeW, by + LayoutHelper.dp(18),
            LayoutHelper.dp(4f).toFloat(),
            LayoutHelper.dp(4f).toFloat(),
            verifiedBgPaint
        )
        MezonIcon.drawIcon(canvas, verifyIconDrawable, (bx + badgePadH + iconS / 2).toInt(), (by + LayoutHelper.dp(9)).toInt(), iconS)
        canvas.drawText(verified, bx + badgePadH + iconS + LayoutHelper.dp(4), by + LayoutHelper.dp(12), verifiedPaint)

        canvas.restore()

        borderPaint.color = themeColors.borderDim
        canvas.drawPath(tmpPath, borderPaint)
    }
}
