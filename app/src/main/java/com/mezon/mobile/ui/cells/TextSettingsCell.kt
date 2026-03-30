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
    private val chevronImageView: ImageView
    private val warnImageView: ImageView
    private val valueContainer: android.widget.LinearLayout
    private var needDivider = false
    private var titleColorOverride = 0
    private var backgroundType = 0 
    private val backgroundPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val backgroundRect = android.graphics.RectF()

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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(theme.onSurface)
            maxLines = 1
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL or LayoutHelper.getAbsoluteGravityStart()
        }
        addView(textView, LayoutHelper.createFrame(
            LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
            Gravity.CENTER_VERTICAL or LayoutHelper.getAbsoluteGravityStart(),
            leftMargin = 16f, rightMargin = 16f
        ))

        valueContainer = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }
        addView(valueContainer, LayoutHelper.createFrame(
            LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
            Gravity.CENTER_VERTICAL or LayoutHelper.getAbsoluteGravityEnd(),
            rightMargin = 42f
        ))

        warnImageView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(MezonIcon.circleExlaimionIcon.resId)
            colorFilter = PorterDuffColorFilter(theme.onSurfaceVariant, PorterDuff.Mode.SRC_IN)
            visibility = GONE
        }
        valueContainer.addView(warnImageView, LayoutHelper.createLinear(16, 16, 0f, Gravity.CENTER_VERTICAL, 0f, 0f, 6f, 0f))

        valueTextView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(theme.onSurfaceVariant)
            maxLines = 1
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL or LayoutHelper.getAbsoluteGravityEnd()
            visibility = GONE
        }
        valueContainer.addView(valueTextView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL))

        chevronImageView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(MezonIcon.chevronSmallRightIcon.resId)
            colorFilter = PorterDuffColorFilter(theme.onSurfaceVariant, PorterDuff.Mode.SRC_IN)
            visibility = GONE
        }
        addView(chevronImageView, LayoutHelper.createFrame(
            16, 16,
            Gravity.CENTER_VERTICAL or LayoutHelper.getAbsoluteGravityEnd(),
            rightMargin = 16f
        ))

        val outValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        foreground = ContextCompat.getDrawable(context, outValue.resourceId)

        minimumHeight = LayoutHelper.dp(56)
        setWillNotDraw(false)
        backgroundPaint.color = theme.surfaceVariant
    }

    fun setBackgroundType(type: Int) {
        if (backgroundType != type) {
            backgroundType = type
            setWillNotDraw(backgroundType == 0 && !needDivider)
            updateTextMargins()
            invalidate()
        }
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

    fun setWarn(warn: Boolean) {
        warnImageView.visibility = if (warn) VISIBLE else GONE
        updateTextMargins()
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
        val leftDp = if (hasIcon) 64f else 16f
        val hasValue = valueTextView.visibility == VISIBLE
        val hasWarn = warnImageView.visibility == VISIBLE
        
        val lpTitle = textView.layoutParams as LayoutParams
        lpTitle.leftMargin = LayoutHelper.dp(leftDp)
        textView.layoutParams = lpTitle

        valueContainer.visibility = if (hasValue || hasWarn) VISIBLE else GONE
    }

    fun setTitleBold(bold: Boolean) {
        textView.typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.SANS_SERIF
    }

    fun setCanClick(canClick: Boolean) {
        chevronImageView.visibility = if (canClick) VISIBLE else GONE
        isClickable = canClick
        isFocusable = canClick
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = LayoutHelper.dp(if (backgroundType != 0) 56 else 50) + (if (needDivider) 1 else 0)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        )
    }

    override fun hasOverlappingRendering(): Boolean = false

    override fun onDraw(canvas: Canvas) {
        if (backgroundType != 0) {
            val r = LayoutHelper.dp(12).toFloat()
            val m = 0f
            backgroundRect.set(m, 0f, width.toFloat() - m, height.toFloat())
            
            if (backgroundType == 4) {
                canvas.drawRoundRect(backgroundRect, r, r, backgroundPaint)
            } else if (backgroundType == 1) {
                canvas.drawRoundRect(backgroundRect, r, r, backgroundPaint)
                canvas.drawRect(m, height / 2f, width.toFloat() - m, height.toFloat(), backgroundPaint)
            } else if (backgroundType == 3) { 
                canvas.drawRoundRect(backgroundRect, r, r, backgroundPaint)
                canvas.drawRect(m, 0f, width.toFloat() - m, height / 2f, backgroundPaint)
            } else { 
                canvas.drawRect(backgroundRect, backgroundPaint)
            }
        }
        if (needDivider) {
            val hasIcon = imageView.visibility == VISIBLE
            val leftPad = LayoutHelper.dp(if (hasIcon) 64 else 16).toFloat()
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
