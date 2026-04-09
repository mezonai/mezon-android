package com.mezon.mobile.ui.cells

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.text.StaticLayout
import android.text.TextUtils
import com.mezon.mobile.core.BaseCell
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.clans.ChannelItemCell
import com.mezon.mobile.home.clans.ClanChannelEntity

class ChannelSearchCell(context: Context, private val theme: ThemeColors) : BaseCell(context) {

    var channel: ClanChannelEntity? = null
        private set

    private var iconDrawable: Drawable? = null
    private var nameLayout: StaticLayout? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), CELL_HEIGHT)
        buildLayouts()
    }

    override fun invalidate() {
        if (channel == null) return
        super.invalidate()
    }

    fun setData(ch: ClanChannelEntity) {
        update(0, ch)
    }

    fun update(mask: Int, newChannel: ClanChannelEntity? = null) {
        val ch = newChannel ?: channel ?: return
        if (newChannel != null) {
            channel = newChannel
            iconDrawable = ChannelItemCell.resolveChannelIcon(ch.type, ch.isPrivate).getDrawable(context)
        }
        buildLayouts()
        invalidate()
    }

    private fun buildLayouts() {
        val ch = channel ?: return
        val w = measuredWidth
        if (w == 0) return

        val textLeft = ICON_LEFT + ICON_SIZE + ICON_TEXT_GAP
        val textWidth = w - textLeft - PAD_RIGHT
        if (textWidth <= 0) return

        val label = ch.channelLabel
        nameLayout = StaticLayout.Builder.obtain(label, 0, label.length, theme.dialogNamePaint, textWidth)
            .setMaxLines(1)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
    }

    override fun onDraw(canvas: Canvas) {
        val ch = channel ?: return
        val w = measuredWidth
        val h = measuredHeight

        val icon = iconDrawable ?: ChannelItemCell.resolveChannelIcon(ch.type, ch.isPrivate).getDrawable(context).also { iconDrawable = it }
        val iconTop = (h - ICON_SIZE) / 2
        icon.setBounds(ICON_LEFT, iconTop, ICON_LEFT + ICON_SIZE, iconTop + ICON_SIZE)
        icon.draw(canvas)

        val textLeft = (ICON_LEFT + ICON_SIZE + ICON_TEXT_GAP).toFloat()
        nameLayout?.let {
            val nameTop = (h - it.height) / 2f
            canvas.save()
            canvas.translate(textLeft, nameTop)
            it.draw(canvas)
            canvas.restore()
        }

        val dividerLeft = textLeft
        canvas.drawLine(dividerLeft, (h - 1).toFloat(), w.toFloat(), (h - 1).toFloat(), theme.dividerPaint)
    }

    companion object {
        private val CELL_HEIGHT = LayoutHelper.dp(52f)
        private val ICON_SIZE = LayoutHelper.dp(24f)
        private val ICON_LEFT = LayoutHelper.dp(16f)
        private val ICON_TEXT_GAP = LayoutHelper.dp(12f)
        private val PAD_RIGHT = LayoutHelper.dp(16f)
    }
}
