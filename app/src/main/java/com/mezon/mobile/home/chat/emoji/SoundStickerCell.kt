package com.mezon.mobile.home.chat.emoji

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.drawable.Drawable
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
private val PLAY_ICON_SIZE = LayoutHelper.dp(12f)
private val SEND_SIZE = LayoutHelper.dp(32f)
private val SEND_ICON_SIZE = LayoutHelper.dp(20f)
private val CONTENT_GAP = LayoutHelper.dp(8f)

class SoundStickerCell(
    context: Context,
    private val themeColors: ThemeColors
) : View(context) {

    var onPreviewTap: (() -> Unit)? = null
    var onSendTap: (() -> Unit)? = null

    private var sticker: StickerItem? = null
    private var isPlaying = false
    private val backgroundRect = RectF()
    private val playRect = RectF()
    private val sendRect = RectF()

    private val playDrawable: Drawable = MezonIcon.playIcon.getDrawable(context).mutate().apply {
        colorFilter = PorterDuffColorFilter(themeColors.blurple, PorterDuff.Mode.SRC_IN)
    }
    private val pauseDrawable: Drawable = MezonIcon.pauseIcon.getDrawable(context).mutate().apply {
        colorFilter = PorterDuffColorFilter(themeColors.blurple, PorterDuff.Mode.SRC_IN)
    }
    private val sendDrawable: Drawable = MezonIcon.sendMessageIcon.getDrawable(context).mutate().apply {
        colorFilter = PorterDuffColorFilter(themeColors.textStrong, PorterDuff.Mode.SRC_IN)
    }

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
        canvas.drawRoundRect(backgroundRect, CELL_CORNER, CELL_CORNER, backgroundPaint)

        val playLeft = CELL_INSET + HORIZONTAL_PADDING
        val playTop = (height - PLAY_SIZE) / 2f
        playRect.set(playLeft, playTop, playLeft + PLAY_SIZE, playTop + PLAY_SIZE)
        playBackgroundPaint.color = themeColors.channelPanelBg
        canvas.drawOval(playRect, playBackgroundPaint)

        val actionDrawable = if (isPlaying) pauseDrawable else playDrawable
        val playIconLeft = (playRect.left + (PLAY_SIZE - PLAY_ICON_SIZE) / 2f).toInt()
        val playIconTop = (playRect.top + (PLAY_SIZE - PLAY_ICON_SIZE) / 2f).toInt()
        actionDrawable.setBounds(
            playIconLeft,
            playIconTop,
            playIconLeft + PLAY_ICON_SIZE,
            playIconTop + PLAY_ICON_SIZE
        )
        actionDrawable.draw(canvas)

        val sendLeft = (width - CELL_INSET - HORIZONTAL_PADDING - SEND_SIZE).toInt()
        val sendTop = ((height - SEND_SIZE) / 2f).toInt()
        sendRect.set(
            sendLeft.toFloat(),
            sendTop.toFloat(),
            (sendLeft + SEND_SIZE).toFloat(),
            (sendTop + SEND_SIZE).toFloat()
        )
        val sendIconLeft = sendLeft + (SEND_SIZE - SEND_ICON_SIZE) / 2
        val sendIconTop = sendTop + (SEND_SIZE - SEND_ICON_SIZE) / 2
        sendDrawable.setBounds(
            sendIconLeft,
            sendIconTop,
            sendIconLeft + SEND_ICON_SIZE,
            sendIconTop + SEND_ICON_SIZE
        )
        sendDrawable.draw(canvas)

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
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                when {
                    playRect.contains(event.x, event.y) -> onPreviewTap?.invoke()
                    sendRect.contains(event.x, event.y) -> onSendTap?.invoke()
                }
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
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
