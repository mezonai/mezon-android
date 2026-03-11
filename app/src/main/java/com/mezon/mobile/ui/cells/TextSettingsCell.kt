package com.mezon.mobile.ui.cells

import android.content.Context
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

class TextSettingsCell(context: Context, private val theme: ThemeColors) : FrameLayout(context) {

    val textView: TextView
    val valueTextView: TextView
    private val imageView: ImageView
    private var needDivider = false
    private var titleColorOverride = 0

    init {
        imageView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            visibility = GONE
        }
        addView(imageView, LayoutHelper.createFrame(
            24, 24,
            Gravity.CENTER_VERTICAL or LayoutHelper.getAbsoluteGravityStart(),
            leftMargin = 21f
        ))

        textView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(theme.onSurface)
            maxLines = 1
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL or LayoutHelper.getAbsoluteGravityStart()
        }
        addView(textView, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
            Gravity.CENTER_VERTICAL or LayoutHelper.getAbsoluteGravityStart(),
            leftMargin = 16f, rightMargin = 16f
        ))

        valueTextView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(theme.onSurfaceVariant)
            maxLines = 1
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL or LayoutHelper.getAbsoluteGravityEnd()
            visibility = GONE
        }
        addView(valueTextView, LayoutHelper.createFrame(
            LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
            Gravity.CENTER_VERTICAL or LayoutHelper.getAbsoluteGravityEnd(),
            rightMargin = 16f
        ))

        val outValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        foreground = ContextCompat.getDrawable(context, outValue.resourceId)

        minimumHeight = LayoutHelper.dp(50)
        setWillNotDraw(true)
    }

    fun setTextAndValue(title: String, value: String = "", divider: Boolean = false) {
        textView.text = title
        if (value.isNotEmpty()) {
            valueTextView.text = value
            valueTextView.visibility = VISIBLE
        } else {
            valueTextView.visibility = GONE
        }
        needDivider = divider
        setWillNotDraw(!divider)
        updateTextMargins()
    }

    fun setIcon(drawable: Drawable?) {
        if (drawable != null) {
            imageView.setImageDrawable(drawable.mutate())
            imageView.colorFilter = PorterDuffColorFilter(theme.onSurfaceVariant, PorterDuff.Mode.SRC_IN)
            imageView.visibility = VISIBLE
        } else {
            imageView.visibility = GONE
        }
        updateTextMargins()
    }

    fun setIcon(@DrawableRes resId: Int) {
        if (resId == 0) {
            imageView.visibility = GONE
            updateTextMargins()
            return
        }
        setIcon(ContextCompat.getDrawable(context, resId))
    }

    fun setIcon(mezonIcon: MezonIcon) {
        setIcon(mezonIcon.resId)
    }

    fun setTextAndIcon(title: String, @DrawableRes iconResId: Int, divider: Boolean = false) {
        setTextAndValue(title, "", divider)
        setIcon(iconResId)
    }

    fun setTitleColor(color: Int) {
        titleColorOverride = color
        textView.setTextColor(if (color != 0) color else theme.onSurface)
    }

    private fun updateTextMargins() {
        val hasIcon = imageView.visibility == VISIBLE
        val leftDp = if (hasIcon) 71f else 20f
        val hasValue = valueTextView.visibility == VISIBLE
        val rightDp = if (hasValue) 80f else 20f

        val lp = textView.layoutParams as LayoutParams
        lp.leftMargin = LayoutHelper.dp(leftDp)
        lp.rightMargin = LayoutHelper.dp(rightDp)
        textView.layoutParams = lp
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = if (needDivider) LayoutHelper.dp(50) + 1 else LayoutHelper.dp(50)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        )
    }

    override fun hasOverlappingRendering(): Boolean = false

    override fun onDraw(canvas: Canvas) {
        if (needDivider) {
            val hasIcon = imageView.visibility == VISIBLE
            val leftPad = LayoutHelper.dp(if (hasIcon) 71 else 20).toFloat()
            val y = (height - 1).toFloat()
            canvas.drawRect(leftPad, y, width.toFloat(), y + 1f, theme.dividerPaint)
        }
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        val value = if (valueTextView.visibility == VISIBLE) valueTextView.text else null
        if (value != null) {
            info.text = "${textView.text} $value"
        }
    }

    fun updateColors() {
        textView.setTextColor(if (titleColorOverride != 0) titleColorOverride else theme.onSurface)
        valueTextView.setTextColor(theme.onSurfaceVariant)
        imageView.drawable?.colorFilter = PorterDuffColorFilter(theme.onSurfaceVariant, PorterDuff.Mode.SRC_IN)
    }
}
