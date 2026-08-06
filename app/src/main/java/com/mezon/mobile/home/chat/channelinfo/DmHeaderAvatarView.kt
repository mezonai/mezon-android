package com.mezon.mobile.home.chat.channelinfo

import android.content.Context
import android.graphics.Canvas
import android.view.View
import com.mezon.mobile.core.AvatarDrawable
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.home.messages.ChannelAvatarLoadState
import com.mezon.mobile.home.messages.ChannelAvatarRequest
import com.mezon.mobile.home.messages.loadChannelAvatar

class DmHeaderAvatarView(context: Context) : View(context) {

    private val avatarDrawable = AvatarDrawable()
    private val loadState = ChannelAvatarLoadState()
    private var attached = false
    private var sizeDp = 50
    private var channelType = 0
    private var channelId = 0L
    private var avatarUrl = ""
    private var placeholderKey = ""

    fun setSizeDp(dp: Int) {
        if (sizeDp == dp) return
        sizeDp = dp
        requestLayout()
        bindAvatar()
    }

    fun bind(channelType: Int, channelId: Long, avatarUrl: String, placeholderKey: String) {
        this.channelType = channelType
        this.channelId = channelId
        this.avatarUrl = avatarUrl
        this.placeholderKey = placeholderKey
        bindAvatar()
    }

    private fun bindAvatar() {
        loadChannelAvatar(
            this,
            avatarDrawable,
            ChannelAvatarRequest(
                channelType = channelType,
                avatarUrl = avatarUrl,
                avatarId = channelId,
                placeholderKey = placeholderKey,
                sizePx = LayoutHelper.dp(sizeDp)
            ),
            loadState,
            attached
        ) { invalidate() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        bindAvatar()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        attached = false
        loadState.cancel()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = LayoutHelper.dp(sizeDp)
        setMeasuredDimension(size, size)
    }

    override fun hasOverlappingRendering(): Boolean = false

    override fun onDraw(canvas: Canvas) {
        avatarDrawable.setBounds(0, 0, width, height)
        avatarDrawable.draw(canvas)
    }
}
