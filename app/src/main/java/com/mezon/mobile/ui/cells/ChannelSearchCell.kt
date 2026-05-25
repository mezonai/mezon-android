package com.mezon.mobile.ui.cells

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseCell
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.clans.ChannelItemCell
import com.mezon.mobile.home.clans.ClanChannelEntity
import com.mezon.mobile.search.ChannelSearchDisplay
import kotlin.math.max

class ChannelSearchCell(context: Context, private val theme: ThemeColors) : BaseCell(context) {

    var display: ChannelSearchDisplay? = null
        private set

    val channel: ClanChannelEntity? get() = display?.channel

    private var iconDrawable: Drawable? = null
    private var nameLayout: StaticLayout? = null
    private var clanLayout: StaticLayout? = null
    private var joinTextLayout: StaticLayout? = null

    private val clanLinePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val joinTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val joinHitRect = RectF()
    private var showJoin = false
    private var joinClickListener: ((ChannelSearchDisplay) -> Unit)? = null
    private var joinLabelW = 0
    private var joinLabelH = 0

    init {
        joinTextPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        clanLinePaint.typeface = Typeface.DEFAULT
    }

    fun setJoinClickListener(listener: ((ChannelSearchDisplay) -> Unit)?) {
        joinClickListener = listener
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val d = display
        if (d == null) {
            setMeasuredDimension(w, CELL_HEIGHT_MIN + ROW_MARGIN_BOTTOM)
            return
        }
        measureJoinLabel()
        val textLeft = ICON_LEFT + ICON_SIZE + ICON_TEXT_GAP
        val joinSlot = if (showJoin) joinLabelW + JOIN_GAP_FROM_TEXT else 0
        val textWidth = max(0, w - textLeft - PAD_RIGHT - joinSlot)
        buildNameAndClanLayouts(d, textWidth)
        val hasClan = !d.hideClanName && d.clanName.isNotEmpty()
        val nameH = nameLayout?.height ?: 0
        val clanH = if (hasClan) (clanLayout?.height ?: 0) + NAME_CLAN_GAP_INT else 0
        val textBlock = nameH + clanH
        val contentH = max(ICON_SIZE, textBlock) + PAD_TOP + PAD_BOTTOM
        val rowH = if (showJoin) max(contentH, joinLabelH + PAD_TOP + PAD_BOTTOM) else contentH
        setMeasuredDimension(w, rowH + ROW_MARGIN_BOTTOM)
    }

    override fun invalidate() {
        if (display == null) return
        super.invalidate()
    }

    fun update(mask: Int, newDisplay: ChannelSearchDisplay? = null) {
        val d = newDisplay ?: display ?: return
        if (newDisplay != null) {
            display = newDisplay
            val ch = d.channel
            iconDrawable = ChannelItemCell.resolveChannelIcon(ch.type, ch.isPrivate, ch.isAgeRestricted).getDrawable(context)
            showJoin = d.showJoinUi
        }
        joinTextPaint.textSize = theme.dialogNamePaint.textSize
        joinTextPaint.color = theme.primary
        clanLinePaint.textSize = theme.dialogNamePaint.textSize
        clanLinePaint.color = theme.dialogNamePaint.color
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
            primary, 0, primary.length, theme.dialogNameBoldPaint, textWidth
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
        val h = measuredHeight - ROW_MARGIN_BOTTOM

        val icon = iconDrawable
            ?: ChannelItemCell.resolveChannelIcon(ch.type, ch.isPrivate, ch.isAgeRestricted).getDrawable(context).also { iconDrawable = it }
        val iconTop = (h - ICON_SIZE) / 2
        icon.setBounds(ICON_LEFT, iconTop, ICON_LEFT + ICON_SIZE, iconTop + ICON_SIZE)
        icon.draw(canvas)

        val textLeft = (ICON_LEFT + ICON_SIZE + ICON_TEXT_GAP).toFloat()
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
            val labelLeft = w - PAD_RIGHT - joinLabelW
            val labelTop = ((h - joinLabelH) / 2).toFloat()
            joinTextLayout?.let { jl ->
                val jtx = labelLeft + JOIN_HIT_PAD_H
                val jty = labelTop + (joinLabelH - jl.height) / 2f
                canvas.save()
                canvas.translate(jtx.toFloat(), jty)
                jl.draw(canvas)
                canvas.restore()
            }
            joinHitRect.set(
                labelLeft.toFloat(),
                labelTop,
                (labelLeft + joinLabelW).toFloat(),
                labelTop + joinLabelH
            )
        } else {
            joinHitRect.setEmpty()
        }

        val dividerLeft = textLeft
        canvas.drawLine(dividerLeft, (h - 1).toFloat(), w.toFloat(), (h - 1).toFloat(), theme.dividerPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (showJoin && !joinHitRect.isEmpty && joinHitRect.contains(event.x, event.y)) {
            if (event.action == MotionEvent.ACTION_UP) {
                val disp = display
                if (disp != null) {
                    joinClickListener?.invoke(disp)
                    playSoundEffect(android.view.SoundEffectConstants.CLICK)
                }
            }
            return true
        }
        return super.onTouchEvent(event)
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
        private val ROW_MARGIN_BOTTOM = LayoutHelper.dp(12f)
        private val JOIN_HIT_PAD_H = LayoutHelper.dp(8f)
        private val JOIN_HIT_PAD_V = LayoutHelper.dp(6f)
        private val JOIN_GAP_FROM_TEXT = LayoutHelper.dp(14f)
    }
}
