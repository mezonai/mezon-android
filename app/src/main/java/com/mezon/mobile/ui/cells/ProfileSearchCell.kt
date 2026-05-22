package com.mezon.mobile.ui.cells

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.text.StaticLayout
import android.text.TextUtils
import com.mezon.mobile.core.AvatarDrawable
import com.mezon.mobile.core.BaseCell
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.messages.ChannelAvatarLoadState
import com.mezon.mobile.home.messages.ChannelAvatarRequest
import com.mezon.mobile.home.messages.loadChannelAvatar
import com.mezon.mobile.network.CHANNEL_TYPE_DM
import com.mezon.mobile.search.SearchMember
import com.mezon.mobile.search.avatarEntityId
import com.mezon.mobile.search.avatarPlaceholderKey

class ProfileSearchCell(context: Context, private val theme: ThemeColors) : BaseCell(context) {

    var member: SearchMember? = null
        private set

    private val avatarDrawable = AvatarDrawable()
    private val avatarLoadState = ChannelAvatarLoadState()
    private var attachedToWindow = false
    private val tmpRect = RectF()

    private var nameLayout: StaticLayout? = null
    private var statusLayout: StaticLayout? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachedToWindow = true
        member?.let { bindAvatar(it) }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        attachedToWindow = false
        avatarLoadState.cancel()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), CELL_HEIGHT)
        buildLayouts()
    }

    override fun invalidate() {
        if (member == null) return
        super.invalidate()
    }

    fun setData(m: SearchMember) {
        member = m
        update(0)
    }

    fun update(mask: Int, newMember: SearchMember? = null) {
        val m = newMember ?: member ?: return
        if (newMember != null) member = newMember
        bindAvatar(m)
        buildLayouts()
        invalidate()
    }

    private fun bindAvatar(m: SearchMember) {
        loadAvatar(m, m.avatarEntityId(), m.avatarPlaceholderKey())
    }

    private fun buildLayouts() {
        val m = member ?: return
        val w = measuredWidth
        if (w == 0) return

        val textLeft = AVATAR_LEFT + AVATAR_SIZE + TEXT_LEFT_MARGIN
        val textWidth = w - textLeft - PAD_RIGHT

        if (textWidth <= 0) return

        val name = m.displayName.ifEmpty { m.username }
        nameLayout = StaticLayout.Builder.obtain(name, 0, name.length, theme.dialogNamePaint, textWidth)
            .setMaxLines(1)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

        val status = if (m.username.isNotEmpty() && m.channelType == CHANNEL_TYPE_DM) {
            "@${m.username}"
        } else {
            ""
        }
        if (status.isNotEmpty()) {
            statusLayout = StaticLayout.Builder.obtain(status, 0, status.length, theme.dialogMessagePaint, textWidth)
                .setMaxLines(1)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
        } else {
            statusLayout = null
        }
    }

    private fun loadAvatar(m: SearchMember, avatarId: Long, placeholderKey: String) {
        loadChannelAvatar(
            context,
            avatarDrawable,
            ChannelAvatarRequest(
                channelType = if (m.isDm) m.channelType else 0,
                avatarUrl = m.avatarUrl,
                avatarId = avatarId,
                placeholderKey = placeholderKey,
                sizePx = AVATAR_SIZE
            ),
            avatarLoadState,
            attachedToWindow
        ) { invalidate() }
    }

    override fun onDraw(canvas: Canvas) {
        val m = member ?: return
        val w = measuredWidth
        val h = measuredHeight

        val avatarTop = (h - AVATAR_SIZE) / 2f
        tmpRect.set(
            AVATAR_LEFT.toFloat(), avatarTop,
            (AVATAR_LEFT + AVATAR_SIZE).toFloat(), avatarTop + AVATAR_SIZE
        )
        avatarDrawable.setBounds(tmpRect.left.toInt(), tmpRect.top.toInt(), tmpRect.right.toInt(), tmpRect.bottom.toInt())
        avatarDrawable.draw(canvas)

        val textLeft = (AVATAR_LEFT + AVATAR_SIZE + TEXT_LEFT_MARGIN).toFloat()

        val hasStatus = statusLayout != null
        val nameTop = if (hasStatus) {
            (h - (nameLayout?.height ?: 0) - (statusLayout?.height ?: 0) - NAME_STATUS_GAP) / 2f
        } else {
            (h - (nameLayout?.height ?: 0)) / 2f
        }

        nameLayout?.let {
            canvas.save()
            canvas.translate(textLeft, nameTop)
            it.draw(canvas)
            canvas.restore()
        }

        statusLayout?.let {
            canvas.save()
            canvas.translate(textLeft, nameTop + (nameLayout?.height ?: 0) + NAME_STATUS_GAP)
            it.draw(canvas)
            canvas.restore()
        }

        val dividerLeft = textLeft
        canvas.drawLine(dividerLeft, (h - 1).toFloat(), w.toFloat(), (h - 1).toFloat(), theme.dividerPaint)
    }

    companion object {
        private val CELL_HEIGHT = LayoutHelper.dp(60f)
        private val AVATAR_SIZE = LayoutHelper.dp(46f)
        private val AVATAR_LEFT = LayoutHelper.dp(16f)
        private val TEXT_LEFT_MARGIN = LayoutHelper.dp(12f)
        private val PAD_RIGHT = LayoutHelper.dp(16f)
        private val NAME_STATUS_GAP = LayoutHelper.dp(2f).toFloat()
    }
}
