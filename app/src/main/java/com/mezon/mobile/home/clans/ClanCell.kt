package com.mezon.mobile.home.clans

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import com.mezon.mobile.core.AvatarDrawable
import com.mezon.mobile.core.BaseCell
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.util.avatarImgproxyUrl

class ClanCell(
    context: Context,
    private val themeColors: ThemeColors
) : BaseCell(context) {

    companion object {
        val ICON_SIZE_PX = LayoutHelper.dp(42)

        private val cachedAvatars = object : android.util.LruCache<Long, AvatarDrawable>(64) {
            override fun entryRemoved(
                evicted: Boolean,
                key: Long,
                oldValue: AvatarDrawable,
                newValue: AvatarDrawable?
            ) {
                oldValue.setPhoto(null)
            }
        }

        fun clearAvatarCache() {
            cachedAvatars.evictAll()
        }

        private val BADGE_HEIGHT = LayoutHelper.dp(16f).toFloat()
        private val BADGE_PAD_H = LayoutHelper.dp(4f).toFloat()
        private val BADGE_OFFSET_RIGHT = LayoutHelper.dp(2f).toFloat()
        private val BADGE_OFFSET_TOP = LayoutHelper.dp(2f).toFloat()
    }

    var currentClan: ClanEntity? = null
        private set
    private var isSelected = false
    private var cornerRadius = LayoutHelper.dp(8).toFloat()
    private var logoCancellable: MezonImageLoader.Cancellable? = null
    private var currentLogoUrl: String? = null

    private val shapeRectF = RectF()
    private val selectedBarRect = RectF()
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(9f)
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        color = android.graphics.Color.WHITE
    }
    private val badgeRect = RectF()
    private val selectedBarPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clipPath = Path()
    private var clipPathLeft = Float.NaN
    private var clipPathTop = Float.NaN
    private var clipPathRight = Float.NaN
    private var clipPathBottom = Float.NaN
    private var clipPathRadius = Float.NaN

    private val iconSizePx = ICON_SIZE_PX
    private val selectedBarPx = LayoutHelper.dp(4)
    private val selectedBarHeightSmall = LayoutHelper.dp(8).toFloat()
    private val selectedBarHeightLarge = LayoutHelper.dp(20).toFloat()
    private val paddingHPx = LayoutHelper.dp(6)
    private val paddingVPx = LayoutHelper.dp(8)



    fun bind(clan: ClanEntity, selected: Boolean) {
        currentClan = clan
        isSelected = selected
        update(0)
    }

    fun update(mask: Int, newClan: ClanEntity? = null, newSelected: Boolean? = null): Boolean {
        val c = newClan ?: currentClan ?: return false
        var needInvalidate = false

        if (mask == 0) {
            val changed = newClan != null && newClan != currentClan ||
                newSelected != null && newSelected != isSelected
            if (newClan != null) currentClan = newClan
            if (newSelected != null) isSelected = newSelected
            ensureAvatar(c)
            loadLogoIfNeeded(c)
            if (changed) invalidate()
            return changed
        }

        if ((mask and NotificationCenter.UPDATE_MASK_BADGE) != 0) {
            if (currentClan?.badgeCount != c.badgeCount || currentClan?.hasUnread != c.hasUnread) {
                needInvalidate = true
            }
        }

        if ((mask and NotificationCenter.UPDATE_MASK_CHAT_NAME) != 0) {
            if (currentClan?.clanName != c.clanName) {
                needInvalidate = true
            }
        }

        if ((mask and NotificationCenter.UPDATE_MASK_CHAT_AVATAR) != 0) {
            if (currentClan?.logo != c.logo) {
                ensureAvatar(c)
                loadLogoIfNeeded(c)
                needInvalidate = true
            }
        }

        if ((mask and NotificationCenter.UPDATE_MASK_SELECT_DIALOG) != 0) {
            val sel = newSelected ?: isSelected
            if (isSelected != sel) {
                isSelected = sel
                needInvalidate = true
            }
        }

        if (newClan != null) currentClan = newClan
        if (newSelected != null) isSelected = newSelected

        if (needInvalidate) {
            invalidate()
        }
        return false
    }

    private fun ensureAvatar(clan: ClanEntity) {
        if (cachedAvatars.get(clan.clanId) == null) {
            cachedAvatars.put(clan.clanId, AvatarDrawable().apply {
                setInfo(clan.clanId, clan.clanName)
            })
        }
    }

    override fun invalidate() {
        if (currentClan == null) return
        super.invalidate()
    }

    private fun loadLogoIfNeeded(clan: ClanEntity) {
        val avatar = cachedAvatars.get(clan.clanId) ?: return
        if (clan.logo.isEmpty()) {
            if (currentLogoUrl != null || avatar.hasPhoto()) {
                currentLogoUrl = null
                logoCancellable?.cancel()
                logoCancellable = null
                avatar.setPhoto(null)
                invalidate()
            }
            return
        }
        val url = avatarImgproxyUrl(clan.logo, iconSizePx)
        if (url == currentLogoUrl && avatar.hasPhoto()) return
        currentLogoUrl = url

        logoCancellable?.cancel()
        logoCancellable = null

        val loader = MezonImageLoader.getInstance(context)
        val cached = loader.getBitmapFromMemory(url, iconSizePx, iconSizePx)
        if (cached != null) {
            avatar.setPhoto(cached)
            return
        }

        avatar.setPhoto(null)

        logoCancellable = loader.load(
            url, iconSizePx, iconSizePx,
            onSuccess = { bmp ->
                avatar.setPhoto(bmp)
                invalidate()
            }
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        currentClan?.let { loadLogoIfNeeded(it) }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        logoCancellable?.cancel()
        logoCancellable = null
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            iconSizePx + paddingVPx * 2
        )
    }

    override fun onDraw(canvas: Canvas) {
        val clan = currentClan ?: return
        val cx = (width / 2).toFloat()
        val cy = (height / 2).toFloat()
        val left = cx - iconSizePx / 2f
        val top = cy - iconSizePx / 2f
        val right = cx + iconSizePx / 2f
        val bottom = cy + iconSizePx / 2f

        val targetRadius = cornerRadius
        if (cornerRadius != targetRadius) {
            cornerRadius = targetRadius
        }

        shapeRectF.set(left, top, right, bottom)
        if (clipPathLeft != left || clipPathTop != top || clipPathRight != right ||
            clipPathBottom != bottom || clipPathRadius != cornerRadius
        ) {
            clipPathLeft = left
            clipPathTop = top
            clipPathRight = right
            clipPathBottom = bottom
            clipPathRadius = cornerRadius
            clipPath.reset()
            clipPath.addRoundRect(shapeRectF, cornerRadius, cornerRadius, Path.Direction.CW)
        }

        canvas.save()
        canvas.clipPath(clipPath)

        val avatar = cachedAvatars.get(clan.clanId)
        if (avatar != null && (avatar.hasPhoto() || clan.logo.isEmpty())) {
            avatar.cornerRadius = cornerRadius
            avatar.setBounds(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
            avatar.draw(canvas)
        } else {
            bgPaint.color = AvatarDrawable.getColorForId(clan.clanId)
            canvas.drawRoundRect(shapeRectF, cornerRadius, cornerRadius, bgPaint)
        }

        canvas.restore()

        selectedBarPaint.color = themeColors.onSurface
        if (isSelected) {
            val barHeight = selectedBarHeightLarge
            val barLeft = 0f
            val barTop = cy - barHeight / 2
            selectedBarRect.set(barLeft, barTop, selectedBarPx.toFloat(), barTop + barHeight)
            canvas.drawRoundRect(selectedBarRect, selectedBarPx / 2f, selectedBarPx / 2f, selectedBarPaint)
        } else if (clan.hasUnread) {
            val barHeight = selectedBarHeightSmall
            val barLeft = 0f
            val barTop = cy - barHeight / 2
            selectedBarRect.set(barLeft, barTop, selectedBarPx.toFloat(), barTop + barHeight)
            canvas.drawRoundRect(selectedBarRect, selectedBarPx / 2f, selectedBarPx / 2f, selectedBarPaint)
        }

        if (clan.badgeCount > 0) {
            badgeBgPaint.color = themeColors.badgeRed
            val text = if (clan.badgeCount > 99) "99+" else clan.badgeCount.toString()
            val textW = badgeTextPaint.measureText(text)
            val badgeW = (textW + BADGE_PAD_H * 2).coerceAtLeast(BADGE_HEIGHT)
            val badgeRadius = BADGE_HEIGHT / 2f

            val badgeRight = right + BADGE_OFFSET_RIGHT
            val badgeLeft = badgeRight - badgeW
            val badgeTop = top - BADGE_OFFSET_TOP
            badgeRect.set(badgeLeft, badgeTop, badgeRight, badgeTop + BADGE_HEIGHT)

            canvas.drawRoundRect(badgeRect, badgeRadius, badgeRadius, badgeBgPaint)
            val textY = badgeRect.centerY() - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2
            canvas.drawText(text, badgeRect.centerX(), textY, badgeTextPaint)
        }
    }
}
