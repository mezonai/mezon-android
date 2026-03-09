package com.mezon.mobile.ui.cells

import android.content.Context
import android.graphics.Canvas
import android.text.Layout
import android.text.StaticLayout
import android.text.TextUtils
import android.view.Gravity
import android.widget.FrameLayout
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

class ProfileHeaderCell(context: Context, private val theme: ThemeColors) : FrameLayout(context) {

    private val avatarView = AvatarView(context)
    private var nameText = ""
    private var subtitleText = ""
    private var nameLayout: StaticLayout? = null
    private var subtitleLayout: StaticLayout? = null

    private val avatarSizeDp = 72
    private val cellPadTop = LayoutHelper.dp(24)
    private val avatarNameGap = LayoutHelper.dp(16)
    private val nameSubGap = LayoutHelper.dp(4)
    private val cellPadBottom = LayoutHelper.dp(24)

    init {
        setWillNotDraw(false)
        avatarView.setSizeDp(avatarSizeDp)
        addView(avatarView, LayoutHelper.createFrame(
            avatarSizeDp, avatarSizeDp, Gravity.CENTER_HORIZONTAL or Gravity.TOP,
            topMargin = cellPadTop.toFloat() / LayoutHelper.dpf(1f)
        ))
    }

    fun setInfo(userId: Long, name: String, subtitle: String) {
        nameText = name
        subtitleText = subtitle
        avatarView.setInfo(userId, name)
        nameLayout = null
        subtitleLayout = null
        requestLayout()
    }

    fun setAvatarUrl(url: String?, loader: coil.ImageLoader) {
        avatarView.setImageUrl(url, loader)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val textWidth = w - LayoutHelper.dp(32)

        if (nameLayout == null && textWidth > 0 && nameText.isNotEmpty()) {
            nameLayout = StaticLayout.Builder
                .obtain(nameText, 0, nameText.length, theme.dialogNameBoldPaint, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1f)
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
        }
        if (subtitleLayout == null && textWidth > 0 && subtitleText.isNotEmpty()) {
            subtitleLayout = StaticLayout.Builder
                .obtain(subtitleText, 0, subtitleText.length, theme.settingsValuePaint, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1f)
                .setMaxLines(1)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
        }

        var h = cellPadTop + LayoutHelper.dp(avatarSizeDp) + avatarNameGap
        nameLayout?.let { h += it.height }
        subtitleLayout?.let { h += nameSubGap + it.height }
        h += cellPadBottom

        val avatarLp = avatarView.layoutParams as LayoutParams
        avatarLp.topMargin = cellPadTop
        avatarView.layoutParams = avatarLp

        super.onMeasure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val startY = (cellPadTop + LayoutHelper.dp(avatarSizeDp) + avatarNameGap).toFloat()

        nameLayout?.let {
            canvas.save()
            canvas.translate(LayoutHelper.dp(16).toFloat(), startY)
            it.draw(canvas)
            canvas.restore()

            subtitleLayout?.let { sub ->
                canvas.save()
                canvas.translate(LayoutHelper.dp(16).toFloat(), startY + it.height + nameSubGap)
                sub.draw(canvas)
                canvas.restore()
            }
        }
    }
}
