package com.mezon.mobile.home.chat

import android.text.TextPaint
import android.text.style.ClickableSpan
import android.view.View

class MentionSpan(
    private val userId: String?,
    private val roleId: String?,
    private val textColor: Int,
    private val bgColor: Int
) : ClickableSpan() {
    override fun onClick(widget: View) {
        (widget as? ChatMessageCell)?.onMentionClicked(userId, roleId)
    }

    override fun updateDrawState(ds: TextPaint) {
        ds.color = textColor
        ds.bgColor = bgColor
        ds.isUnderlineText = false
    }
}

class SystemMessageMentionSpan(
    private val userId: String?,
    private val roleId: String?,
    private val textColor: Int,
    private val bgColor: Int
) : ClickableSpan() {
    override fun onClick(widget: View) {
        (widget as? SystemMessagePlainTextView)?.onMentionClick?.invoke(userId, roleId)
    }

    override fun updateDrawState(ds: TextPaint) {
        ds.color = textColor
        ds.bgColor = bgColor
        ds.isUnderlineText = false
    }
}

class HashtagSpan(
    private val channelId: String?,
    private val linkColor: Int,
    private val clanId: String? = null
) : ClickableSpan() {
    override fun onClick(widget: View) {
        (widget as? ChatMessageCell)?.onHashtagClicked(channelId, clanId)
    }

    override fun updateDrawState(ds: TextPaint) {
        ds.color = linkColor
        ds.isUnderlineText = false
    }
}

class LinkSpan(
    private val url: String,
    private val linkColor: Int
) : ClickableSpan() {
    override fun onClick(widget: View) {
        (widget as? ChatMessageCell)?.onLinkClicked(url)
    }

    override fun updateDrawState(ds: TextPaint) {
        ds.color = linkColor
        ds.isUnderlineText = false
    }
}
