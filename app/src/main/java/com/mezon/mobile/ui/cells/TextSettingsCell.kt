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
    private var backgroundType = BG_TYPE_NONE 
    private val backgroundPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val backgroundRect = android.graphics.RectF()
    private var cardMarginDp = 16f
    private var cardMarginPx = LayoutHelper.dp(16f).toFloat()
    private val cardCornerRadius = LayoutHelper.dp(12f).toFloat()
    private var dividerLeftPad = 0f
    private var pendingMarginUpdate = true

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
            leftMargin = 21f, rightMargin = 21f
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
        setWillNotDraw(backgroundType == BG_TYPE_NONE && !needDivider)
        backgroundPaint.color = theme.getColor(ThemeColors.key_sheetItemBackground)
    }

    fun setBackgroundType(type: Int) {
        if (backgroundType != type) {
            backgroundType = type
            setWillNotDraw(backgroundType == BG_TYPE_NONE && !needDivider)
            pendingMarginUpdate = true
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
        if (needDivider != divider) {
            needDivider = divider
            setWillNotDraw(backgroundType == BG_TYPE_NONE && !needDivider)
        }
        pendingMarginUpdate = true
        invalidate()
    }

    fun setIcon(drawable: Drawable?) {
        if (drawable != null) {
            imageView.setImageDrawable(drawable.mutate())
            imageView.colorFilter = PorterDuffColorFilter(theme.onSurfaceVariant, PorterDuff.Mode.SRC_IN)
            imageView.visibility = VISIBLE
        } else {
            imageView.visibility = GONE
        }
        pendingMarginUpdate = true
        invalidate()
    }

    fun setIcon(@DrawableRes resId: Int) {
        if (resId == 0) {
            imageView.visibility = GONE
            pendingMarginUpdate = true
            invalidate()
            return
        }
        setIcon(ContextCompat.getDrawable(context, resId))
    }

    fun setIcon(mezonIcon: MezonIcon) {
        setIcon(mezonIcon.resId)
    }

    fun setWarn(warn: Boolean) {
        warnImageView.visibility = if (warn) VISIBLE else GONE
        pendingMarginUpdate = true
        invalidate()
    }

    fun setTextAndIcon(title: String, @DrawableRes iconResId: Int, divider: Boolean = false) {
        setTextAndValue(title, "", divider)
        setIcon(iconResId)
    }

    fun setTitleColor(color: Int) {
        titleColorOverride = color
        textView.setTextColor(if (color != 0) color else theme.onSurface)
    }

    fun setCardMargin(dp: Float) {
        if (cardMarginDp != dp) {
            cardMarginDp = dp
            cardMarginPx = LayoutHelper.dp(dp).toFloat()
            pendingMarginUpdate = true
            invalidate()
        }
    }

    private fun updateTextMargins() {
        if (!pendingMarginUpdate) return
        val hasIcon = imageView.visibility == VISIBLE
        val isCard = backgroundType != BG_TYPE_NONE
        val leftDp = if (hasIcon) 64f else (if (isCard) 32f else 21f)
        val hasValue = valueTextView.visibility == VISIBLE
        val hasWarn = warnImageView.visibility == VISIBLE
        
        val textLeftPad = LayoutHelper.dp(leftDp).toFloat()
        dividerLeftPad = LayoutHelper.dp(if (isCard) 16f else leftDp).toFloat()

        val lpTitle = textView.layoutParams as LayoutParams
        lpTitle.leftMargin = textLeftPad.toInt()
        textView.layoutParams = lpTitle

        val lpValue = valueContainer.layoutParams as LayoutParams
        lpValue.rightMargin = LayoutHelper.dp(if (isCard) 58f else 42f)
        valueContainer.layoutParams = lpValue

        val lpChevron = chevronImageView.layoutParams as LayoutParams
        lpChevron.rightMargin = LayoutHelper.dp(if (isCard) 32f else 16f)
        chevronImageView.layoutParams = lpChevron

        valueContainer.visibility = if (hasValue || hasWarn) VISIBLE else GONE
        pendingMarginUpdate = false
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
        updateTextMargins()
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = LayoutHelper.dp(if (backgroundType != BG_TYPE_NONE) 56 else 50) + (if (needDivider) 1 else 0)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        )
    }

    override fun hasOverlappingRendering(): Boolean = false

    override fun onDraw(canvas: Canvas) {
        if (backgroundType != BG_TYPE_NONE) {
            val r = cardCornerRadius
            val m = cardMarginPx
            backgroundRect.set(m, 0f, width.toFloat() - m, height.toFloat())
            
            if (backgroundType == BG_TYPE_ISOLATED) { 
                canvas.drawRoundRect(backgroundRect, r, r, backgroundPaint)
            } else if (backgroundType == BG_TYPE_TOP) { 
                canvas.drawRoundRect(backgroundRect, r, r, backgroundPaint)
                canvas.drawRect(m, height / 2f, width.toFloat() - m, height.toFloat(), backgroundPaint)
            } else if (backgroundType == BG_TYPE_BOTTOM) { 
                canvas.drawRoundRect(backgroundRect, r, r, backgroundPaint)
                canvas.drawRect(m, 0f, width.toFloat() - m, height / 2f, backgroundPaint)
            } else { 
                canvas.drawRect(backgroundRect, backgroundPaint)
            }
        }
        if (needDivider) {
            val y = (height - 1).toFloat()
            val rightPad = if (backgroundType != BG_TYPE_NONE) cardMarginPx else 0f
            canvas.drawRect(dividerLeftPad, y, width.toFloat() - rightPad, y + 1f, theme.dividerPaint)
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
        backgroundPaint.color = theme.getColor(ThemeColors.key_sheetItemBackground)
        textView.setTextColor(if (titleColorOverride != 0) titleColorOverride else theme.onSurface)
        valueTextView.setTextColor(theme.onSurfaceVariant)
        imageView.drawable?.colorFilter = PorterDuffColorFilter(theme.onSurfaceVariant, PorterDuff.Mode.SRC_IN)
    }

    companion object {
        const val BG_TYPE_NONE = 0
        const val BG_TYPE_TOP = 1
        const val BG_TYPE_MIDDLE = 2
        const val BG_TYPE_BOTTOM = 3
        const val BG_TYPE_ISOLATED = 4
    }
}
