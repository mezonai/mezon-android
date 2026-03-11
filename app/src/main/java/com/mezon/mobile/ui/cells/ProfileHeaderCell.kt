package com.mezon.mobile.ui.cells

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

class ProfileHeaderCell(context: Context, private val theme: ThemeColors) : FrameLayout(context) {

    private val avatarView = AvatarView(context)
    val nameTextView: TextView
    val subtitleTextView: TextView
    private val textContainer: LinearLayout

    private val avatarSizeDp = 72
    private val cellPadTop = LayoutHelper.dp(24)
    private val avatarNameGap = LayoutHelper.dp(16)
    private val cellPadBottom = LayoutHelper.dp(24)

    init {
        avatarView.setSizeDp(avatarSizeDp)
        addView(avatarView, LayoutHelper.createFrame(
            avatarSizeDp, avatarSizeDp,
            Gravity.CENTER_HORIZONTAL or Gravity.TOP,
            topMargin = 24f
        ))

        textContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        nameTextView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(theme.onSurface)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
        }
        textContainer.addView(nameTextView, LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT
        ))

        subtitleTextView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(theme.onSurfaceVariant)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
        }
        textContainer.addView(subtitleTextView, LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
            topMargin = 4f
        ))

        addView(textContainer, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            leftMargin = 16f, rightMargin = 16f
        ))
    }

    fun setInfo(userId: Long, name: String, subtitle: String) {
        avatarView.setInfo(userId, name)
        nameTextView.text = name
        subtitleTextView.text = subtitle
        subtitleTextView.visibility = if (subtitle.isNotEmpty()) VISIBLE else GONE
        requestLayout()
    }

    fun setAvatarUrl(url: String?, loader: coil.ImageLoader) {
        avatarView.setImageUrl(url, loader)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)

        textContainer.measure(
            MeasureSpec.makeMeasureSpec(w - LayoutHelper.dp(32), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        avatarView.measure(
            MeasureSpec.makeMeasureSpec(LayoutHelper.dp(avatarSizeDp), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(LayoutHelper.dp(avatarSizeDp), MeasureSpec.EXACTLY)
        )

        val h = cellPadTop + LayoutHelper.dp(avatarSizeDp) + avatarNameGap +
                textContainer.measuredHeight + cellPadBottom

        val containerLp = textContainer.layoutParams as LayoutParams
        containerLp.topMargin = cellPadTop + LayoutHelper.dp(avatarSizeDp) + avatarNameGap
        textContainer.layoutParams = containerLp

        setMeasuredDimension(w, h)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        )
    }

    override fun hasOverlappingRendering(): Boolean = false

    fun updateColors() {
        nameTextView.setTextColor(theme.onSurface)
        subtitleTextView.setTextColor(theme.onSurfaceVariant)
    }
}
