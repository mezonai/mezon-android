package com.mezon.mobile.ui.cells

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseCell
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.core.theme.MobileThemeColors
import com.mezon.mobile.home.clans.ChannelItemCell
import com.mezon.mobile.home.clans.ClanChannelEntity
import com.mezon.mobile.search.ChannelSearchDisplay
import com.mezon.mobile.ui.theme.ThemeMode
import kotlin.math.max

class ChannelSearchCell(context: Context, private val theme: ThemeColors) : BaseCell(context) {

    var display: ChannelSearchDisplay? = null
        private set

    val channel: ClanChannelEntity? get() = display?.channel

    private var iconDrawable: Drawable? = null
    private val channelIconColor: Int
        get() = when {
            theme.resolvedMode == ThemeMode.PURPLE_HAZE -> 0xFFA9ACB7.toInt()
            theme.resolvedMode.isLight -> 0xFF6C7077.toInt()
            else -> 0xFFAEAEAE.toInt()
        }
    private var nameLayout: StaticLayout? = null
    private var clanLayout: StaticLayout? = null
    private var joinTextLayout: StaticLayout? = null

    private val clanLinePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val joinTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var cardInset = 0
    private var showJoin = false
    private var showDivider = true
    private var joinClickListener: ((ChannelSearchDisplay) -> Unit)? = null
    var isJoinTouch = false
        private set
    private var joinLabelW = 0
    private var joinLabelH = 0
    private val joinButton = object : View(context) {
        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = "android.widget.Button"
        }
    }.apply {
        contentDescription = context.getString(R.string.search_join_channel)
        isClickable = true
        isFocusable = true
        visibility = GONE
        background = createJoinRipple()
        setOnClickListener { this@ChannelSearchCell.display?.let { joinClickListener?.invoke(it) } }
    }

    init {
        joinTextPaint.typeface = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(Typeface.DEFAULT, 600, false)
        } else {
            Typeface.DEFAULT_BOLD
        }
        clanLinePaint.typeface = Typeface.DEFAULT
        addView(joinButton)
    }

    fun setJoinClickListener(listener: ((ChannelSearchDisplay) -> Unit)?) {
        joinClickListener = listener
        joinButton.visibility = if (showJoin && listener != null) VISIBLE else GONE
    }

    fun isJoinButtonHit(x: Float, y: Float): Boolean =
        joinButton.visibility == VISIBLE && x >= joinButton.left && x < joinButton.right &&
            y >= joinButton.top && y < joinButton.bottom

    private fun createJoinRipple(): RippleDrawable {
        val mask = GradientDrawable().apply {
            cornerRadius = LayoutHelper.dp(6f).toFloat()
            setColor(android.graphics.Color.WHITE)
        }
        val color = (MobileThemeColors.accent and 0x00FFFFFF) or 0x26000000
        return RippleDrawable(ColorStateList.valueOf(color), null, mask)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) isJoinTouch = isJoinButtonHit(event.x, event.y)
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            isJoinTouch = false
        }
        return handled
    }

    override fun dispatchSetPressed(pressed: Boolean) {
        
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val d = display
        if (d == null) {
            setMeasuredDimension(w, CELL_HEIGHT_MIN)
            return
        }
        measureJoinLabel()
        joinButton.measure(
            MeasureSpec.makeMeasureSpec(joinLabelW, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(joinLabelH, MeasureSpec.EXACTLY)
        )
        val textLeft = cardInset + ICON_LEFT + ICON_SIZE + ICON_TEXT_GAP
        val joinSlot = if (showJoin) joinLabelW + JOIN_GAP_FROM_TEXT else 0
        val textWidth = max(0, w - cardInset - textLeft - PAD_RIGHT - joinSlot)
        buildNameAndClanLayouts(d, textWidth)
        val hasClan = !d.hideClanName && d.clanName.isNotEmpty()
        val nameH = nameLayout?.height ?: 0
        val clanH = if (hasClan) (clanLayout?.height ?: 0) + NAME_CLAN_GAP_INT else 0
        val textBlock = nameH + clanH
        val contentH = max(ICON_SIZE, textBlock) + PAD_TOP + PAD_BOTTOM
        val rowH = if (showJoin) max(contentH, joinLabelH + PAD_TOP + PAD_BOTTOM) else contentH
        setMeasuredDimension(w, rowH)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val buttonLeft = measuredWidth - cardInset - PAD_RIGHT - joinLabelW
        val buttonTop = (measuredHeight - joinLabelH) / 2
        joinButton.layout(buttonLeft, buttonTop, buttonLeft + joinLabelW, buttonTop + joinLabelH)
    }

    override fun invalidate() {
        if (display == null) return
        super.invalidate()
    }

    fun update(
        mask: Int,
        newDisplay: ChannelSearchDisplay? = null,
        showDivider: Boolean = true,
        grouped: Boolean = false,
        isFirst: Boolean = false,
        isLast: Boolean = false,
    ) {
        val d = newDisplay ?: display ?: return
        this.showDivider = showDivider
        if (newDisplay != null) {
            display = newDisplay
            val ch = d.channel
            iconDrawable = MezonIcon.loadDrawable(
                context,
                ChannelItemCell.resolveChannelIcon(ch.type, ch.isPrivate, ch.isAgeRestricted),
                channelIconColor,
            )
            showJoin = d.showJoinUi
            isJoinTouch = false
            joinButton.isPressed = false
            joinButton.visibility = if (showJoin && joinClickListener != null) VISIBLE else GONE
        }
        cardInset = if (grouped) LayoutHelper.dp(12) else 0
        if (grouped) {
            val topRadius = if (isFirst) LayoutHelper.dpf(10f) else 0f
            val bottomRadius = if (isLast) LayoutHelper.dpf(10f) else 0f
            background = InsetDrawable(GradientDrawable().apply {
                setColor(theme.background)
                cornerRadii = floatArrayOf(
                    topRadius, topRadius, topRadius, topRadius,
                    bottomRadius, bottomRadius, bottomRadius, bottomRadius
                )
            }, cardInset, 0, cardInset, 0)
        } else {
            setBackgroundColor(theme.background)
        }
        namePaint.textSize = LayoutHelper.sp(15f)
        namePaint.color = theme.onSurface
        joinTextPaint.textSize = LayoutHelper.sp(14f)
        joinTextPaint.color = MobileThemeColors.accent
        clanLinePaint.textSize = LayoutHelper.sp(13f)
        clanLinePaint.color = theme.textDisabled
        requestLayout()
        invalidate()
    }

    private fun measureJoinLabel() {
        if (!showJoin) {
            joinLabelW = 0
            joinLabelH = 0
            joinTextLayout = null
            return
        }
        val joinLabel = context.getString(R.string.search_join_channel)
        joinTextLayout = StaticLayout.Builder.obtain(
            joinLabel, 0, joinLabel.length, joinTextPaint,
            LayoutHelper.dp(200f)
        ).setMaxLines(1).setEllipsize(TextUtils.TruncateAt.END).build()
        val tw = joinTextLayout?.getLineWidth(0)?.toInt() ?: 0
        val th = joinTextLayout?.height ?: LayoutHelper.dp(16f)
        joinLabelW = tw + JOIN_HIT_PAD_H * 2
        joinLabelH = th + JOIN_HIT_PAD_V * 2
    }

    private fun buildNameAndClanLayouts(d: ChannelSearchDisplay, textWidth: Int) {
        val ch = d.channel
        if (textWidth <= 0) {
            nameLayout = null
            clanLayout = null
            return
        }
        val primary = if (d.parentChannelLabel.isNotEmpty()) {
            "${ch.channelLabel} (${d.parentChannelLabel})"
        } else {
            ch.channelLabel
        }
        nameLayout = StaticLayout.Builder.obtain(
            primary, 0, primary.length, namePaint, textWidth
        ).setMaxLines(1).setEllipsize(TextUtils.TruncateAt.END).build()

        if (!d.hideClanName && d.clanName.isNotEmpty()) {
            clanLayout = StaticLayout.Builder.obtain(
                d.clanName, 0, d.clanName.length, clanLinePaint, textWidth
            ).setMaxLines(1).setEllipsize(TextUtils.TruncateAt.END).build()
        } else {
            clanLayout = null
        }
    }

    override fun onDraw(canvas: Canvas) {
        val d = display ?: return
        val ch = d.channel
        val w = measuredWidth
        val h = measuredHeight

        val icon = iconDrawable
            ?: MezonIcon.loadDrawable(
                context,
                ChannelItemCell.resolveChannelIcon(ch.type, ch.isPrivate, ch.isAgeRestricted),
                channelIconColor,
            ).also { iconDrawable = it }
        val iconTop = (h - ICON_SIZE) / 2
        val iconLeft = cardInset + ICON_LEFT
        icon.setBounds(iconLeft, iconTop, iconLeft + ICON_SIZE, iconTop + ICON_SIZE)
        icon.draw(canvas)

        val textLeft = (iconLeft + ICON_SIZE + ICON_TEXT_GAP).toFloat()
        val hasClan = clanLayout != null
        val nameH = nameLayout?.height?.toFloat() ?: 0f
        val clanH = if (hasClan) (clanLayout?.height?.toFloat() ?: 0f) + NAME_CLAN_GAP_FLOAT else 0f
        val blockH = nameH + clanH
        var textTop = (h - blockH) / 2f

        nameLayout?.let { layout ->
            canvas.save()
            canvas.translate(textLeft, textTop)
            layout.draw(canvas)
            canvas.restore()
            textTop += nameH + if (hasClan) NAME_CLAN_GAP_FLOAT else 0f
        }

        clanLayout?.let { layout ->
            canvas.save()
            canvas.translate(textLeft, textTop)
            layout.draw(canvas)
            canvas.restore()
        }

        if (showJoin && joinLabelW > 0) {
            val labelLeft = w - cardInset - PAD_RIGHT - joinLabelW
            val labelTop = ((h - joinLabelH) / 2).toFloat()
            joinTextLayout?.let { jl ->
                val jtx = labelLeft + JOIN_HIT_PAD_H
                val jty = labelTop + (joinLabelH - jl.height) / 2f
                canvas.save()
                canvas.translate(jtx.toFloat(), jty)
                jl.draw(canvas)
                canvas.restore()
            }
        }

        if (showDivider) {
            val dividerY = (h - 1).toFloat()
            canvas.drawLine(cardInset.toFloat(), dividerY, (w - cardInset).toFloat(), dividerY, theme.dividerPaint)
        }
    }

    companion object {
        private val CELL_HEIGHT_MIN = LayoutHelper.dp(52f)
        private val ICON_SIZE = LayoutHelper.dp(16f)
        private val ICON_LEFT = LayoutHelper.dp(16f)
        private val ICON_TEXT_GAP = LayoutHelper.dp(11f)
        private val PAD_RIGHT = LayoutHelper.dp(24f)
        private val PAD_TOP = LayoutHelper.dp(8f)
        private val PAD_BOTTOM = LayoutHelper.dp(8f)
        private val NAME_CLAN_GAP_INT = LayoutHelper.dp(2f)
        private val NAME_CLAN_GAP_FLOAT = NAME_CLAN_GAP_INT.toFloat()
        private val JOIN_HIT_PAD_H = LayoutHelper.dp(8f)
        private val JOIN_HIT_PAD_V = LayoutHelper.dp(6f)
        private val JOIN_GAP_FROM_TEXT = LayoutHelper.dp(14f)
    }
}
