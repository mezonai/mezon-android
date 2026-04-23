package com.mezon.mobile.home.sharing

import android.content.Context
import android.graphics.Canvas
import android.text.Layout
import android.text.StaticLayout
import android.text.TextUtils
import android.view.View
import com.mezon.mobile.core.AvatarDrawable
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.util.avatarImgproxyUrl

class SharingTargetCell(context: Context, private val theme: ThemeColors) : View(context) {

    private val avatarDrawable = AvatarDrawable()
    private var avatarCancellable: MezonImageLoader.Cancellable? = null

    var target: SharingTarget? = null
        private set

    private var nameLayout: StaticLayout? = null
    private var subtitleLayout: StaticLayout? = null

    fun setData(t: SharingTarget) {
        target = t
        avatarDrawable.setInfo(t.channelId, t.channelLabel)
        loadAvatar(t.avatarUrl.ifEmpty { t.clanLogo })
        buildLayouts()
        invalidate()
    }

    private fun loadAvatar(url: String) {
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
        val availableWidth = measuredWidth - LEFT_PAD - RIGHT_PAD
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
    }
}
