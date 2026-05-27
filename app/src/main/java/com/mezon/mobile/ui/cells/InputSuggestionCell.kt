package com.mezon.mobile.ui.cells

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.mezon.mobile.core.AvatarDrawable
import com.mezon.mobile.core.BaseCell
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.ClanMember
import com.mezon.mobile.home.chat.EmojiItem
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.home.chat.input.InputSuggestionItem
import com.mezon.mobile.home.clans.ChannelItemCell
import com.mezon.mobile.home.clans.ClanChannelEntity
import com.mezon.mobile.home.clans.ClanRole
import com.mezon.mobile.network.CHANNEL_TYPE_THREAD
import com.mezon.mobile.util.avatarImgproxyUrl
import com.mezon.mobile.util.getEmojiUrl

class InputSuggestionCell(
    context: Context,
    private val theme: ThemeColors
) : BaseCell(context) {

    private var item: InputSuggestionItem? = null
    private var needsDivider = true

    private val avatarDrawable = AvatarDrawable()
    private var leadingDrawable: Drawable? = null
    private var leadingBitmap: Bitmap? = null
    private var leadingMode = LEADING_NONE
    private var leadingEffectiveDp = 0
    private var showLeadingSlot = true

    private var imageDisposable: MezonImageLoader.Cancellable? = null
    private var currentImageUrl: String? = null

    private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(16f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(12f)
    }

    private var nameLayout: StaticLayout? = null
    private var subLayout: StaticLayout? = null

    fun bind(newItem: InputSuggestionItem) {
        item = newItem
        when (newItem) {
            is InputSuggestionItem.Here -> configureHere()
            is InputSuggestionItem.Member -> configureMember(newItem.member)
            is InputSuggestionItem.Role -> configureRole(newItem.role)
            is InputSuggestionItem.Channel -> configureChannel(newItem.entity)
            is InputSuggestionItem.Emoji -> configureEmoji(newItem.item)
        }
        requestLayout()
        invalidate()
    }

    fun setDivider(enabled: Boolean) {
        if (enabled != needsDivider) {
            needsDivider = enabled
            invalidate()
        }
    }

    fun applyColors() {
        refreshTextColors()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        buildLayouts(w)
        setMeasuredDimension(w, ROW_HEIGHT)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelImage()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = ROW_HEIGHT

        drawLeading(canvas, h)

        val textLeft = textStartX().toFloat()
        nameLayout?.let { layout ->
            val nameTop = (h - layout.height) / 2f
            canvas.save()
            canvas.translate(textLeft, nameTop)
            layout.draw(canvas)
            canvas.restore()
        }

        subLayout?.let { layout ->
            val subW = layout.getLineWidth(0).toInt()
            val subLeft = (w - PAD_H - subW).toFloat()
            val subTop = (h - layout.height) / 2f
            canvas.save()
            canvas.translate(subLeft, subTop)
            layout.draw(canvas)
            canvas.restore()
        }

        if (needsDivider) {
            val y = (h - 1).toFloat()
            canvas.drawLine(textLeft, y, (w - PAD_H).toFloat(), y, theme.dividerPaint)
        }
    }

    private fun drawLeading(canvas: Canvas, h: Int) {
        val slotLeft = PAD_H
        val slotTop = (h - SLOT) / 2
        when (leadingMode) {
            LEADING_AVATAR -> {
                avatarDrawable.setBounds(slotLeft, slotTop, slotLeft + SLOT, slotTop + SLOT)
                avatarDrawable.draw(canvas)
            }
            LEADING_ICON -> {
                val iconSize = LayoutHelper.dp(leadingEffectiveDp.toFloat())
                val ix = slotLeft + (SLOT - iconSize) / 2
                val iy = slotTop + (SLOT - iconSize) / 2
                leadingDrawable?.let { d ->
                    d.setBounds(ix, iy, ix + iconSize, iy + iconSize)
                    d.draw(canvas)
                }
            }
            LEADING_BITMAP -> {
                val bmp = leadingBitmap ?: return
                val iconSize = LayoutHelper.dp(leadingEffectiveDp.toFloat())
                val ix = slotLeft + (SLOT - iconSize) / 2
                val iy = slotTop + (SLOT - iconSize) / 2
                tmpRect.set(
                    ix.toFloat(), iy.toFloat(),
                    (ix + iconSize).toFloat(), (iy + iconSize).toFloat()
                )
                canvas.drawBitmap(bmp, null, tmpRect, bitmapPaint)
            }
        }
    }

    private fun buildLayouts(width: Int) {
        val it = item
        if (it == null) {
            nameLayout = null
            subLayout = null
            return
        }
        val nameText = nameTextFor(it)
        val subText = subTextFor(it)

        val leftPad = textStartX()
        val rightPad = PAD_H

        val subMax = if (subText.isEmpty()) 0 else minOf(LayoutHelper.dp(160f), width / 2)
        val subMeasured = if (subText.isEmpty()) 0 else subPaint.measureText(subText).toInt()
        val subW = minOf(subMeasured, subMax)

        val nameMax = (width - leftPad - rightPad - (if (subW > 0) subW + MIN_GAP else 0)).coerceAtLeast(0)
        nameLayout = if (nameMax > 0 && nameText.isNotEmpty()) {
            StaticLayout.Builder.obtain(nameText, 0, nameText.length, namePaint, nameMax)
                .setMaxLines(1)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
        } else null

        subLayout = if (subW > 0) {
            StaticLayout.Builder.obtain(subText, 0, subText.length, subPaint, subW + LayoutHelper.dp(2f))
                .setMaxLines(1)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
        } else null
    }

    private fun nameTextFor(it: InputSuggestionItem): String = when (it) {
        is InputSuggestionItem.Here -> "@here"
        is InputSuggestionItem.Member -> it.member.clanNick.ifBlank {
            it.member.displayName.ifBlank { it.member.username }
        }
        is InputSuggestionItem.Role -> it.role.title
        is InputSuggestionItem.Channel -> it.entity.channelLabel
        is InputSuggestionItem.Emoji -> ":${it.item.shortname.replace(":", "")}:"
    }

    private fun subTextFor(it: InputSuggestionItem): String = when (it) {
        is InputSuggestionItem.Here -> "Notify everyone online"
        is InputSuggestionItem.Member ->
            if (it.member.username.isNotBlank()) "@${it.member.username}" else ""
        is InputSuggestionItem.Role -> ""
        is InputSuggestionItem.Channel -> it.subText.uppercase()
        is InputSuggestionItem.Emoji -> ""
    }

    private fun configureHere() {
        cancelImage()
        leadingMode = LEADING_NONE
        leadingDrawable = null
        showLeadingSlot = false
        namePaint.color = theme.onSurface
        subPaint.color = theme.textDisabled
    }

    private fun configureMember(member: ClanMember) {
        cancelImage()
        showLeadingSlot = true
        leadingMode = LEADING_AVATAR
        leadingDrawable = null
        val displayName = member.clanNick.ifBlank {
            member.displayName.ifBlank { member.username }
        }
        avatarDrawable.setInfo(member.userId, member.username)
        avatarDrawable.setPhoto(null)
        namePaint.color = theme.onSurface
        subPaint.color = theme.textDisabled
        val url = member.clanAvatar.ifBlank { member.avatarUrl }
        loadMemberPhoto(url)
    }

    private fun configureRole(role: ClanRole) {
        cancelImage()
        showLeadingSlot = true
        val color = if (role.color != 0) role.color else theme.textRoleLink
        namePaint.color = color
        subPaint.color = theme.textDisabled
        if (role.iconUrl.isBlank()) {
            leadingMode = LEADING_ICON
            leadingEffectiveDp = 20
            leadingDrawable = MezonIcon.shieldUserIcon.getDrawable(context).apply {
                colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
            }
        } else {
            leadingMode = LEADING_ICON
            leadingEffectiveDp = 20
            leadingDrawable = MezonIcon.shieldUserIcon.getDrawable(context).apply {
                colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
            }
            loadLeadingBitmap(role.iconUrl, 20)
        }
    }

    private fun configureChannel(entity: ClanChannelEntity) {
        cancelImage()
        showLeadingSlot = true
        leadingMode = LEADING_ICON
        leadingEffectiveDp = 16
        val iconEnum = resolveChannelIcon(entity)
        leadingDrawable = if (entity.isThread) {
            iconEnum.getDrawable(context, theme)
        } else {
            iconEnum.getDrawable(context).apply {
                colorFilter = PorterDuffColorFilter(theme.onSurface, PorterDuff.Mode.SRC_IN)
            }
        }
        namePaint.color = theme.onSurface
        subPaint.color = theme.textDisabled
    }

    private fun configureEmoji(emoji: EmojiItem) {
        cancelImage()
        showLeadingSlot = true
        leadingMode = LEADING_BITMAP
        leadingEffectiveDp = 22
        leadingDrawable = null
        namePaint.color = theme.onSurface
        subPaint.color = theme.textDisabled
        val url = emoji.src.ifBlank { getEmojiUrl(emoji.id) ?: "" }
        if (url.isNotBlank()) loadLeadingBitmap(url, 22)
    }

    private fun refreshTextColors() {
        val it = item ?: return
        namePaint.color = when (it) {
            is InputSuggestionItem.Role ->
                if (it.role.color != 0) it.role.color else theme.textRoleLink
            else -> theme.onSurface
        }
        subPaint.color = theme.textDisabled
    }

    private fun loadMemberPhoto(url: String) {
        if (url.isBlank()) return
        val size = SLOT
        val proxy = avatarImgproxyUrl(url, size)
        currentImageUrl = proxy
        val loader = MezonImageLoader.getInstance(context)
        val cached = loader.getBitmapFromMemory(proxy, size, size)
        if (cached != null) {
            avatarDrawable.setPhoto(cached)
            invalidate()
            return
        }
        imageDisposable = loader.load(proxy, size, size, onSuccess = { bmp ->
            if (currentImageUrl == proxy) {
                avatarDrawable.setPhoto(bmp)
                invalidate()
            }
        })
    }

    private fun loadLeadingBitmap(url: String, effectiveDp: Int) {
        if (url.isBlank()) return
        val size = LayoutHelper.dp(effectiveDp.toFloat())
        currentImageUrl = url
        val loader = MezonImageLoader.getInstance(context)
        val cached = loader.getBitmapFromMemory(url, size, size)
        if (cached != null) {
            leadingBitmap = cached
            leadingMode = LEADING_BITMAP
            invalidate()
            return
        }
        imageDisposable = loader.load(url, size, size, onSuccess = { bmp ->
            if (currentImageUrl == url) {
                leadingBitmap = bmp
                leadingMode = LEADING_BITMAP
                invalidate()
            }
        })
    }

    private fun textStartX(): Int =
        if (showLeadingSlot) PAD_H + SLOT + TEXT_GAP else PAD_H

    private fun cancelImage() {
        imageDisposable?.cancel()
        imageDisposable = null
        leadingBitmap = null
        currentImageUrl = null
    }

    private fun resolveChannelIcon(entity: ClanChannelEntity): MezonIcon {
        if (entity.isAgeRestricted) {
            return MezonIcon.ageRestrictedIcon
        }
        val type = if (entity.isThread) CHANNEL_TYPE_THREAD else entity.type
        return ChannelItemCell.resolveChannelIcon(type, entity.isPrivate)
    }

    companion object {
        private const val LEADING_NONE = 0
        private const val LEADING_AVATAR = 1
        private const val LEADING_ICON = 2
        private const val LEADING_BITMAP = 3

        private val ROW_HEIGHT = LayoutHelper.dp(50f)
        private val SLOT = LayoutHelper.dp(30f)
        private val PAD_H = LayoutHelper.dp(12f)
        private val TEXT_GAP = LayoutHelper.dp(10f)
        private val MIN_GAP = LayoutHelper.dp(12f)

        private val tmpRect = RectF()
        private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
            isDither = true
        }
    }
}
