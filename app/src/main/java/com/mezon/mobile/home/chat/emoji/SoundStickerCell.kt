package com.mezon.mobile.home.chat.emoji

import com.mezon.mobile.ui.cells.drawCenteredIcon
import com.mezon.mobile.ui.cells.setIconColor
import com.mezon.mobile.R
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.chat.StickerItem
import com.mezon.mobile.ui.cells.MezonIcon

private val CELL_HEIGHT = LayoutHelper.dp(52f)
private val CELL_INSET = LayoutHelper.dp(2f).toFloat()
private val CELL_CORNER = LayoutHelper.dp(10f).toFloat()
private val HORIZONTAL_PADDING = LayoutHelper.dp(10f)
private val PLAY_SIZE = LayoutHelper.dp(28f)
private val SEND_SIZE = LayoutHelper.dp(32f)
private val CONTENT_GAP = LayoutHelper.dp(8f)

class SoundStickerCell(
    context: Context,
    private val themeColors: ThemeColors,
    private val sendOnItemTap: Boolean = false
) : View(context) {

    var onPreviewTap: (() -> Unit)? = null
    var onSendTap: (() -> Unit)? = null

    private var sticker: StickerItem? = null
    private var isPlaying = false
    private var itemPressed = false
    private val backgroundRect = RectF()
    private val playRect = RectF()
    private val sendRect = RectF()

    private val playDrawable = MezonIcon.loadDrawable(context, R.drawable.mz_play_16, themeColors.iconTint)
    private val pauseDrawable = MezonIcon.loadDrawable(context, R.drawable.mz_pause_16, themeColors.iconTint)
    private val sendDrawable = MezonIcon.loadDrawable(context, R.drawable.mz_send_24, themeColors.iconTint)

    fun bind(sticker: StickerItem, playing: Boolean) {
        this.sticker = sticker
        isPlaying = playing
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), CELL_HEIGHT)
    }

    override fun onDraw(canvas: Canvas) {
        val item = sticker ?: return
        val width = measuredWidth.toFloat()
        val height = measuredHeight.toFloat()

        backgroundRect.set(CELL_INSET, CELL_INSET, width - CELL_INSET, height - CELL_INSET)
        backgroundPaint.color = themeColors.serverRailBg
        if (sendOnItemTap && itemPressed) backgroundPaint.alpha = 210
        canvas.drawRoundRect(backgroundRect, CELL_CORNER, CELL_CORNER, backgroundPaint)

        val playLeft = CELL_INSET + HORIZONTAL_PADDING
        val playTop = (height - PLAY_SIZE) / 2f
        playRect.set(playLeft, playTop, playLeft + PLAY_SIZE, playTop + PLAY_SIZE)
        playBackgroundPaint.color = themeColors.channelPanelBg
        canvas.drawOval(playRect, playBackgroundPaint)

        val actionDrawable = if (isPlaying) pauseDrawable else playDrawable
        actionDrawable.setIconColor(themeColors.blurple)
        actionDrawable.drawCenteredIcon(canvas, playRect.centerX().toInt(), playRect.centerY().toInt())

        val sendLeft = (width - CELL_INSET - HORIZONTAL_PADDING - SEND_SIZE).toInt()
        val sendTop = ((height - SEND_SIZE) / 2f).toInt()
        sendRect.set(
            sendLeft.toFloat(),
            sendTop.toFloat(),
            (sendLeft + SEND_SIZE).toFloat(),
            (sendTop + SEND_SIZE).toFloat()
        )
        sendDrawable.setIconColor(themeColors.textStrong)
        sendDrawable.drawCenteredIcon(canvas, sendRect.centerX().toInt(), sendRect.centerY().toInt())

        textPaint.color = themeColors.textStrong
        val textStart = playRect.right + CONTENT_GAP
        val textEnd = sendRect.left - CONTENT_GAP
        val availableWidth = (textEnd - textStart).coerceAtLeast(0f)
        val text = TextUtils.ellipsize(
            item.shortname,
            textPaint,
            availableWidth,
            TextUtils.TruncateAt.END
        ).toString()
        val baseline = height / 2f - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(text, textStart, baseline, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (sticker == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                itemPressed = true
                if (sendOnItemTap) invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasPressed = itemPressed
                itemPressed = false
                if (sendOnItemTap) invalidate()
                if (event.actionMasked == MotionEvent.ACTION_CANCEL || (sendOnItemTap && !wasPressed)) return true
                when {
                    playRect.contains(event.x, event.y) -> onPreviewTap?.invoke()
                    sendOnItemTap || sendRect.contains(event.x, event.y) -> onSendTap?.invoke()
                }
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    companion object {
        private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val playBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = LayoutHelper.dp(14f).toFloat()
        }
    }
}
