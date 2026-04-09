package com.mezon.mobile.home.notifications

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseCell
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.AvatarView
import com.mezon.mobile.util.convertTimestampToTimeAgo

class TopicCell(context: Context, private val theme: ThemeColors) : BaseCell(context) {

    init {
        isClickable = true
        val outValue = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        setBackgroundResource(outValue.resourceId)
    }

    var entity: TopicEntity? = null
        private set

    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = LayoutHelper.dp(1).toFloat()
    }

    private val avatarView = AvatarView(context).apply {
        setSizeDp(44)
    }

    private val avatarWrap = FrameLayout(context).apply {
        addView(
            avatarView,
            FrameLayout.LayoutParams(LayoutHelper.dp(44), LayoutHelper.dp(44)).apply {
                gravity = Gravity.CENTER
            }
        )
    }

    private val contentLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.START
    }

    private val titleLabel = TextView(context).apply {
        textSize = 16f
        gravity = Gravity.START
        setSingleLine()
        ellipsize = TextUtils.TruncateAt.END
        typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
    }

    private val replyLabel = TextView(context).apply {
        textSize = 15f
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
    }

    private val lastMessageLabel = TextView(context).apply {
        textSize = 15f
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
    }

    private val timeLabel = TextView(context).apply {
        textSize = 12f
        gravity = Gravity.CENTER_VERTICAL
    }

    private val rowContainer = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isBaselineAligned = false
    }

    init {
        setPadding(LayoutHelper.dp(16), LayoutHelper.dp(12), LayoutHelper.dp(16), LayoutHelper.dp(12))

        contentLayout.addView(
            titleLabel,
            LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                bottomMargin = LayoutHelper.dp(4)
            }
        )
        contentLayout.addView(
            replyLabel,
            LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                bottomMargin = LayoutHelper.dp(4)
            }
        )
        contentLayout.addView(lastMessageLabel, LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        rowContainer.addView(
            avatarWrap,
            LinearLayout.LayoutParams(LayoutHelper.dp(44), LayoutHelper.MATCH_PARENT).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
        )
        rowContainer.addView(
            contentLayout,
            LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f).apply {
                marginStart = LayoutHelper.dp(12)
                topMargin = LayoutHelper.dp(2)
                gravity = Gravity.CENTER_VERTICAL
            }
        )
        rowContainer.addView(
            timeLabel,
            LinearLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT).apply {
                marginStart = LayoutHelper.dp(10)
                gravity = Gravity.CENTER_VERTICAL
            }
        )

        addView(rowContainer, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val innerW = (w - paddingLeft - paddingRight).coerceAtLeast(0)
        rowContainer.measure(
            MeasureSpec.makeMeasureSpec(innerW, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        val h = rowContainer.measuredHeight + paddingTop + paddingBottom
        setMeasuredDimension(w, h.coerceAtLeast(LayoutHelper.dp(64)))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        rowContainer.layout(paddingLeft, paddingTop, r - l - paddingRight, b - t - paddingBottom)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        dividerPaint.color = theme.outlineVariant
        val y = height - dividerPaint.strokeWidth * 0.5f
        canvas.drawLine(
            paddingLeft.toFloat(),
            y,
            (width - paddingRight).toFloat(),
            y,
            dividerPaint
        )
    }

    fun setData(topic: TopicEntity) {
        entity = topic
        updateContent()
    }

    private fun updateContent() {
        val topic = entity ?: return

        val replyBody = formatTopicPreview(topic.topicContentRaw).ifEmpty {
            "[${context.getString(R.string.common_forwarded)}]"
        }
        replyLabel.text = repliedToWithBody(replyBody)

        val lastSentText = formatTopicPreview(topic.lastSentMessageContentRaw).ifEmpty { "" }
        if (lastSentText.isNotEmpty()) {
            val name = if (topic.lastSentMessageSenderId == ANONYMOUS_USER_ID_LONG) "Anonymous" else topic.senderName
            if (name.isNotBlank()) {
                lastMessageLabel.text = nameColonWithBody(name, lastSentText)
                lastMessageLabel.visibility = View.VISIBLE
            } else {
                lastMessageLabel.text = lastSentText
                lastMessageLabel.setTextColor(theme.onSurfaceVariant)
                lastMessageLabel.visibility = View.VISIBLE
            }
        } else {
            lastMessageLabel.visibility = View.GONE
        }

        timeLabel.text = convertTimestampToTimeAgo(context, topic.lastSentMessageTimestampSeconds)
        titleLabel.text = context.getString(R.string.notif_topic_and_you).uppercase()

        avatarView.setInfo(
            topic.lastSentMessageSenderId,
            topic.senderName.ifEmpty { formatTopicPreview(topic.topicContentRaw) }
        )
        avatarView.setImageUrl(topic.senderAvatar.ifEmpty { null })

        titleLabel.setTextColor(theme.tabLabelActive)
        timeLabel.setTextColor(theme.tabLabelActive)
    }

    private fun formatTopicPreview(content: String): String = topicPreviewDisplayText(
        content,
        context.getString(R.string.message_attachment_file_bracket),
        context.getString(R.string.message_attachment_contact_bracket)
    )

    private fun repliedToWithBody(body: String): CharSequence {
        val prefix = context.getString(R.string.notif_topic_replied_to).trimEnd()
        val sb = SpannableStringBuilder(prefix)
        sb.setSpan(StyleSpan(Typeface.BOLD), 0, prefix.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(ForegroundColorSpan(theme.tabLabelActive), 0, prefix.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append(' ')
        val start = sb.length
        sb.append(body)
        sb.setSpan(ForegroundColorSpan(theme.onSurfaceVariant), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    private fun nameColonWithBody(name: String, body: String): CharSequence {
        val prefix = "$name: "
        val sb = SpannableStringBuilder(prefix)
        sb.setSpan(StyleSpan(Typeface.BOLD), 0, prefix.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(ForegroundColorSpan(theme.tabLabelActive), 0, prefix.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val start = sb.length
        sb.append(body)
        sb.setSpan(ForegroundColorSpan(theme.onSurfaceVariant), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    companion object {
        private val ANONYMOUS_USER_ID_LONG = 1767478432163172999L
    }
}
