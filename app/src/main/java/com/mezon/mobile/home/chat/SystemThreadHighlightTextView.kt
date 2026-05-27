package com.mezon.mobile.home.chat

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.util.MentionColors

class SystemThreadHighlightTextView(
    context: Context,
    themeColors: ThemeColors
) : TextView(context) {

    interface Listener {
        fun onThreadTitleClick(threadChannelId: Long, threadTitle: String)
        fun onAllThreadsClick()
    }

    init {
        maxLines = 3
        ellipsize = android.text.TextUtils.TruncateAt.END
        movementMethod = LinkMovementMethod.getInstance()
        linksClickable = true
        isFocusable = true
        isClickable = true
        highlightColor = 0x335865F2
        includeFontPadding = false
        val paint = themeColors.systemMessageTextPaint
        setTextSize(TypedValue.COMPLEX_UNIT_PX, paint.textSize)
        setTextColor(themeColors.primary)
        typeface = paint.typeface
        setLineSpacing(LayoutHelper.dpf(2f), 1f)
    }

    fun setThreadCreatedHighlight(
        listener: Listener,
        label: String,
        threadChannelId: Long,
        mentionColors: MentionColors,
        creatorName: String = ""
    ) {
        val sb = SpannableStringBuilder()
        val creator = creatorName.trim()
        if (creator.isNotEmpty()) {
            sb.append(creator)
            sb.append(' ')
        }
        sb.append(context.getString(R.string.system_msg_started_thread_lead))
        sb.append(' ')
        val titleStart = sb.length
        sb.append(label)
        val tc = mentionColors.userText
        val bg = mentionColors.userBg
        sb.setSpan(
            ThreadCreatedTitleSpan(listener, threadChannelId, label, tc, bg),
            titleStart,
            sb.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        sb.setSpan(StyleSpan(Typeface.BOLD), titleStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append('.')
        sb.append(' ')
        sb.append(context.getString(R.string.system_msg_thread_sentence_see))
        sb.append(' ')
        val allStart = sb.length
        sb.append(context.getString(R.string.system_msg_all_threads))
        sb.setSpan(ThreadCreatedAllThreadsSpan(listener, tc, bg), allStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(StyleSpan(Typeface.BOLD), allStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text = sb
    }
}

private class ThreadCreatedTitleSpan(
    private val listener: SystemThreadHighlightTextView.Listener,
    private val threadChannelId: Long,
    private val threadTitle: String,
    private val textColor: Int,
    private val bgColor: Int
) : ClickableSpan() {
    override fun onClick(widget: android.view.View) {
        listener.onThreadTitleClick(threadChannelId, threadTitle)
    }

    override fun updateDrawState(ds: android.text.TextPaint) {
        ds.color = textColor
        ds.bgColor = bgColor
        ds.isUnderlineText = false
    }
}

private class ThreadCreatedAllThreadsSpan(
    private val listener: SystemThreadHighlightTextView.Listener,
    private val textColor: Int,
    private val bgColor: Int
) : ClickableSpan() {
    override fun onClick(widget: android.view.View) {
        listener.onAllThreadsClick()
    }

    override fun updateDrawState(ds: android.text.TextPaint) {
        ds.color = textColor
        ds.bgColor = bgColor
        ds.isUnderlineText = false
    }
}
