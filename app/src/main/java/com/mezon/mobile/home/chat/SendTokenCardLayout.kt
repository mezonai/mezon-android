package com.mezon.mobile.home.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import com.mezon.mobile.R
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.MezonIcon
import org.json.JSONException
import org.json.JSONObject

class SendTokenCardLayout(private val context: Context) {

    var cardWidth = 0
        private set
    var blockHeight = 0
        private set

    private var titleLayout: StaticLayout? = null
    private var detailLayout: StaticLayout? = null
    private var buttonLayout: StaticLayout? = null
    private var iconDrawable: Drawable? = null
    private var iconTint = 0
    private var rowHeight = 0
    private var textColumnHeight = 0
    private val cardRect = RectF()
    private val buttonRect = RectF()

    private val cardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dividerPaint = Paint()
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val detailPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val buttonPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    fun prepare(content: String, theme: ThemeColors, maxWidth: Int) {
        cardWidth = (maxWidth - TRAILING_INSET).coerceAtLeast(MIN_WIDTH).coerceAtMost(maxWidth.coerceAtLeast(1))
        cardBgPaint.color = theme.secondaryLight
        dividerPaint.color = theme.getColor(ThemeColors.key_divider)

        val rawText = try {
            JSONObject(content).optString("t", content)
        } catch (_: JSONException) {
            content
        }
        val parts = rawText.split(MESSAGE_PART_SEPARATOR, limit = 2)
        val title = parts.getOrElse(0) { rawText }
        val description = parts.getOrElse(1) { "" }

        val textW = (cardWidth - PAD_H * 2 - ICON_SLOT - ICON_GAP).coerceAtLeast(1)
        titlePaint.typeface = Typeface.DEFAULT_BOLD
        titlePaint.textSize = LayoutHelper.sp(14f)
        titlePaint.color = theme.onSurface
        titleLayout = StaticLayout.Builder.obtain(title, 0, title.length, titlePaint, textW)
            .setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

        detailPaint.typeface = Typeface.DEFAULT
        detailPaint.textSize = LayoutHelper.sp(12f)
        detailPaint.color = theme.onSurface
        detailLayout = if (description.isNotEmpty()) {
            val ssb = SpannableStringBuilder()
            val detailLabel = "${context.getString(R.string.common_detail)}: "
            ssb.append(detailLabel)
            ssb.setSpan(ForegroundColorSpan(theme.blurple), 0, detailLabel.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            ssb.setSpan(StyleSpan(Typeface.BOLD), 0, detailLabel.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            ssb.append(description)
            StaticLayout.Builder.obtain(ssb, 0, ssb.length, detailPaint, textW)
                .setMaxLines(3)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
        } else {
            null
        }

        buttonPaint.typeface = Typeface.DEFAULT_BOLD
        buttonPaint.textSize = LayoutHelper.sp(14f)
        buttonPaint.color = theme.blurple
        val buttonText = context.getString(R.string.token_transaction_transfer_button)
        val buttonW = (cardWidth - PAD_H * 2).coerceAtLeast(1)
        buttonLayout = StaticLayout.Builder.obtain(buttonText, 0, buttonText.length, buttonPaint, buttonW)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(1)
            .build()

        if (iconDrawable == null || iconTint != theme.colorSuccess) {
            iconTint = theme.colorSuccess
            iconDrawable = MezonIcon.transactionIcon.getDrawable(context).mutate().apply {
                colorFilter = PorterDuffColorFilter(iconTint, PorterDuff.Mode.SRC_IN)
                setBounds(0, 0, ICON_SIZE, ICON_SIZE)
            }
        }

        val titleH = titleLayout?.height ?: 0
        val detailH = detailLayout?.let { it.height + TITLE_DETAIL_GAP } ?: 0
        textColumnHeight = titleH + detailH
        rowHeight = maxOf(ICON_SIZE, textColumnHeight)
        val dividerY = PAD_TOP + rowHeight + ROW_DIVIDER_GAP
        val buttonTop = dividerY + DIVIDER_HEIGHT
        blockHeight = buttonTop + BUTTON_PAD_TOP + (buttonLayout?.height ?: 0) + BUTTON_PAD_BOTTOM
        cardRect.set(0f, 0f, cardWidth.toFloat(), blockHeight.toFloat())
        buttonRect.set(0f, dividerY.toFloat(), cardWidth.toFloat(), blockHeight.toFloat())
    }

    fun draw(canvas: Canvas, left: Float, top: Float) {
        canvas.save()
        canvas.translate(left, top)
        canvas.drawRoundRect(cardRect, CARD_RADIUS, CARD_RADIUS, cardBgPaint)

        iconDrawable?.let {
            canvas.save()
            canvas.translate(PAD_H.toFloat(), PAD_TOP + (rowHeight - ICON_SIZE) / 2f)
            it.draw(canvas)
            canvas.restore()
        }

        val textLeft = (PAD_H + ICON_SLOT + ICON_GAP).toFloat()
        var textTop = PAD_TOP + (rowHeight - textColumnHeight) / 2f
        titleLayout?.let {
            canvas.save()
            canvas.translate(textLeft, textTop)
            it.draw(canvas)
            canvas.restore()
            textTop += it.height + TITLE_DETAIL_GAP
        }
        detailLayout?.let {
            canvas.save()
            canvas.translate(textLeft, textTop)
            it.draw(canvas)
            canvas.restore()
        }

        val dividerY = buttonRect.top
        canvas.drawRect(PAD_H.toFloat(), dividerY, cardWidth - PAD_H.toFloat(), dividerY + DIVIDER_HEIGHT, dividerPaint)

        buttonLayout?.let {
            canvas.save()
            canvas.translate(PAD_H.toFloat(), dividerY + DIVIDER_HEIGHT + BUTTON_PAD_TOP)
            it.draw(canvas)
            canvas.restore()
        }
        canvas.restore()
    }

    fun buttonBounds(out: RectF, left: Float, top: Float) {
        out.set(buttonRect)
        out.offset(left, top)
    }

    fun clear() {
        titleLayout = null
        detailLayout = null
        buttonLayout = null
        cardWidth = 0
        blockHeight = 0
        cardRect.setEmpty()
        buttonRect.setEmpty()
    }

    companion object {
        private const val MESSAGE_PART_SEPARATOR = " | "
        private val MIN_WIDTH = LayoutHelper.dp(200)
        private val TRAILING_INSET = LayoutHelper.dp(60)
        private val PAD_H = LayoutHelper.dp(12)
        private val PAD_TOP = LayoutHelper.dp(10)
        private val ICON_SIZE = LayoutHelper.dp(34)
        private val ICON_SLOT = LayoutHelper.dp(40)
        private val ICON_GAP = LayoutHelper.dp(8)
        private val TITLE_DETAIL_GAP = LayoutHelper.dp(2)
        private val ROW_DIVIDER_GAP = LayoutHelper.dp(8)
        private const val DIVIDER_HEIGHT = 1
        private val BUTTON_PAD_TOP = LayoutHelper.dp(8)
        private val BUTTON_PAD_BOTTOM = LayoutHelper.dp(10)
        private val CARD_RADIUS = LayoutHelper.dpf(12f)
    }
}
