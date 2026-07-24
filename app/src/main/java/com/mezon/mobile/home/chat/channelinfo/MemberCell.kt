package com.mezon.mobile.home.chat.channelinfo

import android.content.Context
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.text.StaticLayout
import android.text.TextUtils
import com.mezon.mobile.core.AvatarDrawable
import com.mezon.mobile.core.BaseCell
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.ClanMember
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.util.avatarImgproxyUrl

class MemberCell(context: Context, private val theme: ThemeColors) : BaseCell(context) {

    private var member: ClanMember? = null
    private var isDm = false

    private var nameLayout: StaticLayout? = null
    private val avatarDrawable = AvatarDrawable()
    private var currentAvatarUrl: String? = null
    private var avatarDisposable: MezonImageLoader.Cancellable? = null
    private var ownerDrawable: Drawable? = null
    private var isOwner = false
    private var creatorId = 0L

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, CELL_HEIGHT)
        buildLayouts()
    }

    fun setCreatorId(id: Long) {
        creatorId = id
    }

    fun setIsDm(dm: Boolean) {
        isDm = dm
    }

    fun setData(newMember: ClanMember) {
        member = newMember
        update(0)
    }

    fun update(mask: Int, newMember: ClanMember? = null) {
        val m = newMember ?: member ?: return
        if (newMember != null) member = newMember

        if (mask == 0) {
            avatarDrawable.setInfo(m.userId, m.username)
            loadAvatar(resolveAvatar(m))
            isOwner = creatorId != 0L && m.userId == creatorId
            buildLayouts()
            invalidate()
            return
        }

        if (mask and UPDATE_MASK_NAME != 0) buildLayouts()
        if (mask and UPDATE_MASK_AVATAR != 0) loadAvatar(resolveAvatar(m))
    }

    override fun invalidate() {
        if (member == null) return
        super.invalidate()
    }

    private fun resolveName(m: ClanMember): String {
        return if (isDm) {
            m.displayName.ifBlank { m.username }
        } else {
            m.clanNick.ifBlank { m.displayName.ifBlank { m.username } }
        }
    }

    private fun resolveAvatar(m: ClanMember): String {
        return if (isDm) {
            m.avatarUrl
        } else {
            m.clanAvatar.ifBlank { m.avatarUrl }
        }
    }

    private fun loadAvatar(url: String) {
        if (url == currentAvatarUrl && avatarDrawable.hasPhoto()) return
        currentAvatarUrl = url
        avatarDisposable?.cancel()
        avatarDisposable = null

        if (url.isEmpty()) {
            avatarDrawable.setLoadingPlaceholder(false)
            avatarDrawable.setPhoto(null)
            invalidate()
            return
        }

        val proxyUrl = avatarImgproxyUrl(url, AVATAR_SIZE)
        avatarDrawable.setLoadingPlaceholder(true)
        avatarDisposable = MezonImageLoader.getInstance(context).load(
            proxyUrl, AVATAR_SIZE, AVATAR_SIZE,
            onSuccess = { bmp ->
                avatarDisposable = null
                avatarDrawable.setLoadingPlaceholder(false)
                avatarDrawable.setPhoto(bmp)
                invalidate()
            },
            onError = {
                avatarDisposable = null
                avatarDrawable.setLoadingPlaceholder(false)
                invalidate()
            }
        )
    }

    private fun buildLayouts() {
        val m = member ?: return
        val displayName = resolveName(m)
        val maxWidth = measuredWidth - NAME_LEFT - PADDING_RIGHT -
            (if (isOwner) OWNER_ICON_SIZE + LayoutHelper.dp(4) else 0)
        if (maxWidth <= 0) return

        nameLayout = StaticLayout.Builder.obtain(
            displayName, 0, displayName.length, theme.dialogNamePaint, maxWidth
        ).setMaxLines(1).setEllipsize(TextUtils.TruncateAt.END).build()
    }

    override fun onDraw(canvas: Canvas) {
        val m = member ?: return
        val cx = PADDING_LEFT + AVATAR_SIZE / 2
        val cy = CELL_HEIGHT / 2

        avatarDrawable.setBounds(
            cx - AVATAR_SIZE / 2, cy - AVATAR_SIZE / 2,
            cx + AVATAR_SIZE / 2, cy + AVATAR_SIZE / 2
        )
        avatarDrawable.draw(canvas)

        var textX = NAME_LEFT.toFloat()
        val textY = ((CELL_HEIGHT - (nameLayout?.height ?: 0)) / 2).toFloat()
        nameLayout?.let { layout ->
            canvas.save()
            canvas.translate(textX, textY)
            layout.draw(canvas)
            canvas.restore()
            textX += layout.getLineWidth(0) + NAME_OWNER_GAP
        }

        if (isOwner) {
            if (ownerDrawable == null) {
                ownerDrawable = MezonIcon.ownerIcon.getDrawable(context).apply {
                    colorFilter = PorterDuffColorFilter(0xFFFAA61A.toInt(), PorterDuff.Mode.SRC_IN)
                }
            }
            val iconY = (CELL_HEIGHT - OWNER_ICON_SIZE) / 2
            ownerDrawable!!.setBounds(
                textX.toInt(), iconY,
                textX.toInt() + OWNER_ICON_SIZE, iconY + OWNER_ICON_SIZE
            )
            ownerDrawable!!.draw(canvas)
        }

        val dividerStart = NAME_LEFT.toFloat()
        val dividerY = (CELL_HEIGHT - 1).toFloat()
        canvas.drawRect(dividerStart, dividerY, measuredWidth.toFloat(), CELL_HEIGHT.toFloat(), theme.dividerPaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        avatarDisposable?.cancel()
        avatarDisposable = null
    }

    companion object {
        private val CELL_HEIGHT = LayoutHelper.dp(56f)
        private val PADDING_LEFT = LayoutHelper.dp(16f)
        private val PADDING_RIGHT = LayoutHelper.dp(16f)
        private val AVATAR_SIZE = LayoutHelper.dp(36f)
        private val NAME_LEFT = PADDING_LEFT + AVATAR_SIZE + LayoutHelper.dp(12f)
        private val OWNER_ICON_SIZE = LayoutHelper.dp(16f)
        private val NAME_OWNER_GAP = LayoutHelper.dp(4)

        const val UPDATE_MASK_NAME = 1
        const val UPDATE_MASK_AVATAR = 2
    }
}
