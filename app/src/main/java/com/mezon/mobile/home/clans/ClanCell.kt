package com.mezon.mobile.home.clans

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.LongSparseArray
import com.mezon.mobile.core.AvatarDrawable
import com.mezon.mobile.core.BaseCell
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.util.createImgproxyUrl

class ClanCell(
    context: Context,
    private val themeColors: ThemeColors
) : BaseCell(context) {

    companion object {
        private val cachedAvatars = LongSparseArray<AvatarDrawable>(30)
    }

    var currentClan: ClanEntity? = null
        private set
    private var isSelected = false
    private var cornerRadius = LayoutHelper.dp(24).toFloat()
    private var logoCancellable: MezonImageLoader.Cancellable? = null
    private var currentLogoUrl: String? = null

    private val shapeRectF = RectF()
    private val selectedBarRect = RectF()
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val unreadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val selectedBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val clipPath = Path()

    private val iconSizePx = LayoutHelper.dp(40)
    private val unreadDotPx = LayoutHelper.dp(8)
    private val selectedBarPx = LayoutHelper.dp(4)
    private val selectedBarHeightSmall = LayoutHelper.dp(8).toFloat()
    private val selectedBarHeightLarge = LayoutHelper.dp(20).toFloat()
    private val paddingHPx = LayoutHelper.dp(6)
    private val paddingVPx = LayoutHelper.dp(6)



    fun bind(clan: ClanEntity, selected: Boolean) {
        currentClan = clan
        isSelected = selected
        update(0)
    }

    fun update(mask: Int, newClan: ClanEntity? = null, newSelected: Boolean? = null): Boolean {
        val c = newClan ?: currentClan ?: return false
        var needInvalidate = false

        if (mask == 0) {
            if (newClan != null) currentClan = newClan
            if (newSelected != null) isSelected = newSelected
            ensureAvatar(c)
            loadLogoIfNeeded(c)
            invalidate()
            return true
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

    private fun loadLogoIfNeeded(clan: ClanEntity) {
        if (clan.logo.isEmpty()) return
        val avatar = cachedAvatars.get(clan.clanId) ?: return
        val url = createImgproxyUrl(clan.logo, iconSizePx * 2, iconSizePx * 2, "fill")
        if (url == currentLogoUrl && avatar.hasPhoto()) return
        currentLogoUrl = url

        logoCancellable?.cancel()
        logoCancellable = null

        if (avatar.hasPhoto()) return

        val loader = MezonImageLoader.getInstance(context)
        val cached = loader.getBitmapFromMemory(url, iconSizePx, iconSizePx)
        if (cached != null) {
            avatar.setPhoto(cached)
            return
        }

        logoCancellable = loader.load(
            url, iconSizePx, iconSizePx,
            onSuccess = { bmp ->
                avatar.setPhoto(bmp)
                post { invalidate() }
            }
        )
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

        val targetRadius = if (isSelected) LayoutHelper.dp(16).toFloat() else LayoutHelper.dp(24).toFloat()
        if (cornerRadius != targetRadius) {
            cornerRadius = targetRadius
        }

        shapeRectF.set(left, top, right, bottom)
        clipPath.reset()
        clipPath.addRoundRect(shapeRectF, cornerRadius, cornerRadius, Path.Direction.CW)

        canvas.save()
        canvas.clipPath(clipPath)

        val avatar = cachedAvatars.get(clan.clanId)
        if (avatar != null && (avatar.hasPhoto() || clan.logo.isEmpty())) {
            avatar.setBounds(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
            avatar.draw(canvas)
        } else {
            bgPaint.color = AvatarDrawable.getColorForId(clan.clanId)
            canvas.drawRoundRect(shapeRectF, cornerRadius, cornerRadius, bgPaint)
        }

        canvas.restore()

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
            val dotR = unreadDotPx.toFloat()
            canvas.drawCircle(right - dotR / 2, bottom - dotR / 2, dotR, unreadPaint)
        }
    }
}
