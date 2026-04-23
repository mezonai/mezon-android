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
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.util.avatarImgproxyUrl

class VoiceUserAvatarCell(
    context: Context,
    private val themeColors: ThemeColors
) : View(context) {

    companion object {
        private val AVATAR_SIZE = LayoutHelper.dp(18)
        private val CELL_HEIGHT = LayoutHelper.dp(26)
        private val PADDING_LEFT = LayoutHelper.dp(40)
        private val AVATAR_TEXT_GAP = LayoutHelper.dp(10)
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
    private var avatarCancellable: MezonImageLoader.Cancellable? = null
    private var currentAvatarUrl: String? = null

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
        loadAvatar(avatarUrl)
        buildNameLayout()
        invalidate()
    }

    fun setOverflow(count: Int) {
        this.isOverflowItem = true
        this.overflowCount = count
        this.nameLayout = null
        cancelAvatarLoad()
        invalidate()
    }

    private fun loadAvatar(url: String?) {
        if (url == currentAvatarUrl && avatarDrawable.hasPhoto()) return
        currentAvatarUrl = url
        avatarDrawable.setPhoto(null)
        cancelAvatarLoad()

        if (url.isNullOrEmpty()) return
        val proxyUrl = avatarImgproxyUrl(url, AVATAR_SIZE)
        avatarCancellable = MezonImageLoader.getInstance(context).load(
            proxyUrl, AVATAR_SIZE, AVATAR_SIZE,
            onSuccess = { bmp ->
                avatarDrawable.setPhoto(bmp)
                invalidate()
            }
        )
    }

    private fun cancelAvatarLoad() {
        avatarCancellable?.cancel()
        avatarCancellable = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelAvatarLoad()
    }

    private fun buildNameLayout() {
        if (displayName.isEmpty()) {
            nameLayout = null
            return
        }
        val availWidth = measuredWidth - PADDING_LEFT - AVATAR_SIZE - AVATAR_TEXT_GAP - LayoutHelper.dp(16)
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
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), CELL_HEIGHT)
        if (!isOverflowItem) buildNameLayout()
    }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val avatarLeft = PADDING_LEFT.toFloat()
        val avatarTop = cy - AVATAR_SIZE / 2f

        if (isOverflowItem) {
            overflowBgPaint.color = themeColors.surfaceVariant
            canvas.drawCircle(
                avatarLeft + AVATAR_SIZE / 2f,
                cy,
                AVATAR_SIZE / 2f,
                overflowBgPaint
            )
            val text = "+$overflowCount"
            val textY = cy - (overflowTextPaint.descent() + overflowTextPaint.ascent()) / 2
            canvas.drawText(text, avatarLeft + AVATAR_SIZE / 2f, textY, overflowTextPaint)
            return
        }

        avatarRect.set(avatarLeft, avatarTop, avatarLeft + AVATAR_SIZE, avatarTop + AVATAR_SIZE)
        avatarDrawable.setBounds(avatarRect.left.toInt(), avatarRect.top.toInt(), avatarRect.right.toInt(), avatarRect.bottom.toInt())
        avatarDrawable.draw(canvas)

        nameLayout?.let {
            canvas.save()
            val textX = avatarLeft + AVATAR_SIZE + AVATAR_TEXT_GAP
            val textY = cy - it.height / 2f
            canvas.translate(textX, textY)
            it.draw(canvas)
            canvas.restore()
        }
    }
}
