package com.mezon.mobile.home.sharing

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.text.Layout
import android.text.StaticLayout
import android.text.TextUtils
import android.view.View
import com.mezon.mobile.core.AvatarDrawable
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.home.messages.GroupAvatar
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.util.avatarImgproxyUrl

class SharingTargetCell(context: Context, private val theme: ThemeColors) : View(context) {

    private val avatarDrawable = AvatarDrawable()
    private var avatarCancellable: MezonImageLoader.Cancellable? = null

    var target: SharingTarget? = null
        private set

    private var showForwardCheckbox: Boolean = false
    private var forwardSelected: Boolean = false

    private var nameLayout: StaticLayout? = null
    private var subtitleLayout: StaticLayout? = null
    private val checkBoxRect = RectF()
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = LayoutHelper.dpf(1.5f)
    }
    private val checkFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val checkMarkDrawable = MezonIcon.checkmarkSmallIcon.getDrawable(context).mutate()

    fun setData(t: SharingTarget, forwardCheckbox: Boolean = false, isForwardSelected: Boolean = false) {
        showForwardCheckbox = forwardCheckbox
        forwardSelected = isForwardSelected
        target = t
        avatarDrawable.setInfo(t.channelId, t.channelLabel)
        loadAvatar(t)
        buildLayouts()
        invalidate()
    }

    private fun loadAvatar(t: SharingTarget) {
        if (t.isGroup && !t.hasCustomAvatar()) {
            avatarCancellable?.cancel()
            avatarCancellable = null
            avatarDrawable.setPhoto(GroupAvatar.bitmap(context))
            return
        }
        loadAvatarFromUrl(t.avatarUrl.ifEmpty { t.clanLogo })
    }

    private fun loadAvatarFromUrl(url: String) {
        avatarCancellable?.cancel()
        avatarCancellable = null
        if (url.isEmpty()) {
            avatarDrawable.setPhoto(null)
            return
        }
        val proxyUrl = avatarImgproxyUrl(url, AVATAR_SIZE)
        avatarCancellable = MezonImageLoader.getInstance(context).load(
            proxyUrl, AVATAR_SIZE, AVATAR_SIZE,
            onSuccess = { bmp ->
                avatarCancellable = null
                avatarDrawable.setPhoto(bmp)
                invalidate()
            },
            onError = {
                avatarCancellable = null
                avatarDrawable.setPhoto(null)
                invalidate()
            }
        )
    }

    private fun buildLayouts() {
        val t = target ?: return
        val reserveRight = if (showForwardCheckbox) LayoutHelper.dp(28f) else 0
        val availableWidth = measuredWidth - LEFT_PAD - RIGHT_PAD - reserveRight
        if (availableWidth <= 0) return
        val primaryText = if (t.isThread && t.parentChannelLabel.isNotEmpty()) {
            "${t.channelLabel} (${t.parentChannelLabel})"
        } else {
            t.channelLabel
        }
        nameLayout = StaticLayout.Builder
            .obtain(primaryText, 0, primaryText.length, theme.dialogNamePaint, availableWidth)
            .setMaxLines(1)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .build()
        subtitleLayout = if (t.isClanChannel && t.clanName.isNotEmpty()) {
            StaticLayout.Builder
                .obtain(t.clanName, 0, t.clanName.length, theme.dialogMessagePaint, availableWidth)
                .setMaxLines(1)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .build()
        } else {
            null
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val parentWidth = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(parentWidth, CELL_HEIGHT)
        buildLayouts()
    }

    override fun onDraw(canvas: Canvas) {
        if (target == null) return

        val avatarLeft = H_PAD.toFloat()
        val avatarTop = (CELL_HEIGHT - AVATAR_SIZE) / 2f

        avatarDrawable.setBounds(
            avatarLeft.toInt(), avatarTop.toInt(),
            (avatarLeft + AVATAR_SIZE).toInt(), (avatarTop + AVATAR_SIZE).toInt()
        )
        avatarDrawable.draw(canvas)

        val name = nameLayout ?: return
        val sub = subtitleLayout
        val totalH = name.height + if (sub != null) sub.height + SUBTITLE_GAP else 0
        var top = (CELL_HEIGHT - totalH) / 2f
        canvas.save()
        canvas.translate(LEFT_PAD.toFloat(), top)
        name.draw(canvas)
        canvas.restore()
        if (sub != null) {
            top += name.height + SUBTITLE_GAP
            canvas.save()
            canvas.translate(LEFT_PAD.toFloat(), top)
            sub.draw(canvas)
            canvas.restore()
        }

        if (showForwardCheckbox) {
            val box = CHECK_BOX_SIZE.toFloat()
            val left = (measuredWidth - RIGHT_PAD - CHECK_BOX_SIZE).toFloat()
            val top = (CELL_HEIGHT - CHECK_BOX_SIZE) / 2f
            checkBoxRect.set(left, top, left + box, top + box)
            checkFillPaint.color = theme.primary
            checkPaint.color = if (forwardSelected) theme.primary else theme.outline
            if (forwardSelected) {
                canvas.drawRoundRect(checkBoxRect, CHECK_CORNER, CHECK_CORNER, checkFillPaint)
                checkMarkDrawable.colorFilter = PorterDuffColorFilter(0xFFFFFFFF.toInt(), PorterDuff.Mode.SRC_IN)
                val cx = checkBoxRect.centerX()
                val cy = checkBoxRect.centerY()
                val half = CHECK_ICON / 2f
                checkMarkDrawable.setBounds(
                    (cx - half).toInt(),
                    (cy - half).toInt(),
                    (cx + half).toInt(),
                    (cy + half).toInt()
                )
                checkMarkDrawable.draw(canvas)
            } else {
                canvas.drawRoundRect(checkBoxRect, CHECK_CORNER, CHECK_CORNER, checkPaint)
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        avatarCancellable?.cancel()
        avatarCancellable = null
    }

    companion object {
        private val CELL_HEIGHT = LayoutHelper.dp(60)
        private val AVATAR_SIZE = LayoutHelper.dp(40)
        private val H_PAD = LayoutHelper.dp(16)
        private val LEFT_PAD = H_PAD + AVATAR_SIZE + LayoutHelper.dp(12)
        private val RIGHT_PAD = LayoutHelper.dp(16)
        private val SUBTITLE_GAP = LayoutHelper.dp(2)
        private val CHECK_BOX_SIZE = LayoutHelper.dp(20f)
        private val CHECK_CORNER = LayoutHelper.dpf(5f)
        private val CHECK_ICON = LayoutHelper.dp(12f)
    }
}
