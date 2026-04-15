package com.mezon.mobile.home.voice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.view.View
import com.mezon.mobile.core.AvatarDrawable
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.home.clans.VoiceMemberDisplay
import com.mezon.mobile.util.createImgproxyUrl

class VoiceCollapsedMembersCell(
    context: Context,
    private val themeColors: ThemeColors
) : View(context) {

    companion object {
        private const val MAX_VISIBLE_AVATARS = 5
        private val AVATAR_SIZE = LayoutHelper.dp(18)
        private val AVATAR_OVERLAP = LayoutHelper.dp(4)
        private val CELL_HEIGHT = LayoutHelper.dp(22)
        private val PADDING_LEFT = LayoutHelper.dp(30)
        private val BADGE_SIZE = LayoutHelper.dp(20)
        private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val badgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = LayoutHelper.dp(1).toFloat()
        }
        private val badgeTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = LayoutHelper.sp(10f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
    }

    private val avatarDrawables = Array(MAX_VISIBLE_AVATARS) { AvatarDrawable() }
    private val avatarCancellables = arrayOfNulls<MezonImageLoader.Cancellable>(MAX_VISIBLE_AVATARS)
    private val currentAvatarUrls = arrayOfNulls<String>(MAX_VISIBLE_AVATARS)
    private val avatarRect = RectF()
    private var memberCount = 0
    private var visibleCount = 0
    private var overflowCount = 0

    fun setMembers(members: List<VoiceMemberDisplay>) {
        memberCount = members.size
        visibleCount = members.size.coerceAtMost(MAX_VISIBLE_AVATARS)
        overflowCount = if (members.size > MAX_VISIBLE_AVATARS) members.size - MAX_VISIBLE_AVATARS else 0

        for (i in 0 until MAX_VISIBLE_AVATARS) {
            if (i < visibleCount) {
                val m = members[i]
                avatarDrawables[i].setInfo(m.userId, m.displayName)
                loadAvatar(m.avatarUrl, i)
            } else {
                cancelAvatarLoad(i)
                avatarDrawables[i].setInfo(0L, "")
                currentAvatarUrls[i] = null
            }
        }
        invalidate()
    }

    private fun loadAvatar(url: String?, index: Int) {
        if (url == currentAvatarUrls[index] && avatarDrawables[index].hasPhoto()) return
        currentAvatarUrls[index] = url
        avatarDrawables[index].setPhoto(null)
        cancelAvatarLoad(index)

        if (url.isNullOrEmpty()) return
        val proxyUrl = createImgproxyUrl(url, AVATAR_SIZE * 2, AVATAR_SIZE * 2, "fill")
        avatarCancellables[index] = MezonImageLoader.getInstance(context).load(
            proxyUrl, AVATAR_SIZE, AVATAR_SIZE,
            onSuccess = { bmp ->
                avatarDrawables[index].setPhoto(bmp)
                invalidate()
            }
        )
    }

    private fun cancelAvatarLoad(index: Int) {
        avatarCancellables[index]?.cancel()
        avatarCancellables[index] = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        for (i in 0 until MAX_VISIBLE_AVATARS) cancelAvatarLoad(i)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), CELL_HEIGHT)
    }

    override fun onDraw(canvas: Canvas) {
        if (memberCount == 0) return
        val cy = height / 2f
        val step = AVATAR_SIZE - AVATAR_OVERLAP

        for (i in 0 until visibleCount) {
            val left = PADDING_LEFT + i * step
            val top = cy - AVATAR_SIZE / 2f
            avatarRect.set(left.toFloat(), top, (left + AVATAR_SIZE).toFloat(), top + AVATAR_SIZE)
            avatarDrawables[i].setBounds(
                avatarRect.left.toInt(), avatarRect.top.toInt(),
                avatarRect.right.toInt(), avatarRect.bottom.toInt()
            )
            avatarDrawables[i].draw(canvas)
        }

        if (overflowCount > 0) {
            val badgeLeft = PADDING_LEFT + visibleCount * step
            val badgeCx = badgeLeft + BADGE_SIZE / 2f
            val badgeR = BADGE_SIZE / 2f

            badgeBgPaint.color = themeColors.primary
            canvas.drawCircle(badgeCx, cy, badgeR, badgeBgPaint)

            badgeBorderPaint.color = themeColors.onSurface
            canvas.drawCircle(badgeCx, cy, badgeR - badgeBorderPaint.strokeWidth / 2f, badgeBorderPaint)

            badgeTextPaint.color = themeColors.onSurface
            val text = "+$overflowCount"
            val textY = cy - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2
            canvas.drawText(text, badgeCx, textY, badgeTextPaint)
        }
    }
}
