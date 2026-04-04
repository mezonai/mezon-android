package com.mezon.mobile.home.voice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import com.mezon.mobile.core.AvatarDrawable
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

class VoiceUserAvatarCell(
    context: Context,
    private val themeColors: ThemeColors
) : View(context) {

    companion object {
        private val avatarSize = LayoutHelper.dp(28)
        private val cellHeight = LayoutHelper.dp(32)
        private val paddingLeft = LayoutHelper.dp(52)
        private val avatarTextGap = LayoutHelper.dp(8)
        private val nameTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = LayoutHelper.sp(13f)
        }
        private val overflowBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val overflowTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = LayoutHelper.sp(10f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            color = 0xFFFFFFFF.toInt()
        }
    }

    private val avatarDrawable = AvatarDrawable()
    private val avatarRect = RectF()
    private var nameLayout: StaticLayout? = null

    private var userId: Long = 0
    private var displayName: String = ""
    private var avatarUrl: String? = null
    private var overflowCount: Int = 0
    private var isOverflowItem = false

    fun setUser(userId: Long, name: String, avatarUrl: String?) {
        this.userId = userId
        this.displayName = name
        this.avatarUrl = avatarUrl
        this.isOverflowItem = false
        this.overflowCount = 0
        avatarDrawable.setInfo(userId, name)
        buildNameLayout()
        invalidate()
    }

    fun setOverflow(count: Int) {
        this.isOverflowItem = true
        this.overflowCount = count
        this.nameLayout = null
        invalidate()
    }

    private fun buildNameLayout() {
        if (displayName.isEmpty()) {
            nameLayout = null
            return
        }
        val availWidth = measuredWidth - paddingLeft - avatarSize - avatarTextGap - LayoutHelper.dp(16)
        if (availWidth <= 0) {
            nameLayout = null
            return
        }
        nameTextPaint.color = themeColors.onSurfaceVariant
        val ellipsized = TextUtils.ellipsize(displayName, nameTextPaint, availWidth.toFloat(), TextUtils.TruncateAt.END)
        nameLayout = StaticLayout.Builder.obtain(ellipsized, 0, ellipsized.length, nameTextPaint, availWidth)
            .setMaxLines(1)
            .setIncludePad(false)
            .build()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), cellHeight)
        if (!isOverflowItem) buildNameLayout()
    }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val avatarLeft = paddingLeft.toFloat()
        val avatarTop = cy - avatarSize / 2f

        if (isOverflowItem) {
            overflowBgPaint.color = themeColors.surfaceVariant
            canvas.drawCircle(
                avatarLeft + avatarSize / 2f,
                cy,
                avatarSize / 2f,
                overflowBgPaint
            )
            val text = "+$overflowCount"
            val textY = cy - (overflowTextPaint.descent() + overflowTextPaint.ascent()) / 2
            canvas.drawText(text, avatarLeft + avatarSize / 2f, textY, overflowTextPaint)
            return
        }

        avatarRect.set(avatarLeft, avatarTop, avatarLeft + avatarSize, avatarTop + avatarSize)
        avatarDrawable.setBounds(avatarRect.left.toInt(), avatarRect.top.toInt(), avatarRect.right.toInt(), avatarRect.bottom.toInt())
        avatarDrawable.draw(canvas)

        nameLayout?.let {
            canvas.save()
            val textX = avatarLeft + avatarSize + avatarTextGap
            val textY = cy - it.height / 2f
            canvas.translate(textX, textY)
            it.draw(canvas)
            canvas.restore()
        }
    }
}
