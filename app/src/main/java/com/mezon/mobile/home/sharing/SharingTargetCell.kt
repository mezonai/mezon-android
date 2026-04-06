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
import com.mezon.mobile.util.createImgproxyUrl

class SharingTargetCell(context: Context, private val theme: ThemeColors) : View(context) {

    private val avatarDrawable = AvatarDrawable()
    private var avatarCancellable: MezonImageLoader.Cancellable? = null

    var target: SharingTarget? = null
        private set

    private var nameLayout: StaticLayout? = null

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
        val sizePx = AVATAR_SIZE * 2
        val proxyUrl = createImgproxyUrl(url, sizePx, sizePx, "fill")
        avatarCancellable = MezonImageLoader.getInstance(context).load(
            proxyUrl, sizePx, sizePx,
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
        nameLayout = StaticLayout.Builder
            .obtain(t.channelLabel, 0, t.channelLabel.length, theme.dialogNamePaint, availableWidth)
            .setMaxLines(1)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .build()
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

        val layout = nameLayout ?: return
        canvas.save()
        canvas.translate(LEFT_PAD.toFloat(), (CELL_HEIGHT - layout.height) / 2f)
        layout.draw(canvas)
        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        avatarCancellable?.cancel()
        avatarCancellable = null
    }

    companion object {
        private val CELL_HEIGHT = LayoutHelper.dp(52)
        private val AVATAR_SIZE = LayoutHelper.dp(40)
        private val H_PAD = LayoutHelper.dp(16)
        private val LEFT_PAD = H_PAD + AVATAR_SIZE + LayoutHelper.dp(12)
        private val RIGHT_PAD = LayoutHelper.dp(16)
    }
}
