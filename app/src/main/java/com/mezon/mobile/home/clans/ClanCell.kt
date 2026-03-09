package com.mezon.mobile.home.clans

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.LongSparseArray
import android.view.View
import coil.Coil
import coil.request.ImageRequest
import com.mezon.mobile.core.AvatarDrawable
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.util.createImgproxyUrl

class ClanCell(
    context: Context,
    private val themeColors: ThemeColors
) : View(context) {

    companion object {
        private const val ICON_SIZE_DP = 48
        private const val UNREAD_DOT_DP = 8
        private const val SELECTED_BAR_DP = 4
        private val shapeRectF = RectF()
        private val selectedBarRect = RectF()
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val unreadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        private val selectedBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        private val clipPath = Path()
        private val cachedAvatars = LongSparseArray<AvatarDrawable>(30)
    }

    private var clan: ClanEntity? = null
    private var isSelected = false
    private var cornerRadius = LayoutHelper.dp(24).toFloat()

    private val iconSizePx = LayoutHelper.dp(40)
    private val unreadDotPx = LayoutHelper.dp(8)
    private val selectedBarPx = LayoutHelper.dp(4)
    private val selectedBarHeightSmall = LayoutHelper.dp(8).toFloat()
    private val selectedBarHeightLarge = LayoutHelper.dp(20).toFloat()
    private val paddingHPx = LayoutHelper.dp(6)
    private val paddingVPx = LayoutHelper.dp(6)

    init {
        setWillNotDraw(false)
    }

    fun bind(clan: ClanEntity, selected: Boolean) {
        val changed = this.clan?.clanId != clan.clanId || this.isSelected != selected
        this.clan = clan
        this.isSelected = selected
        if (changed) {
            ensureAvatar(clan)
            loadLogoIfNeeded(clan)
            invalidate()
        }
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
        if (avatar.hasPhoto()) return
        val url = createImgproxyUrl(clan.logo, iconSizePx * 2, iconSizePx * 2, "fill")
        val request = ImageRequest.Builder(context)
            .data(url)
            .allowHardware(false)
            .target(
                onSuccess = { drawable ->
                    val bmp = android.graphics.Bitmap.createBitmap(iconSizePx, iconSizePx, android.graphics.Bitmap.Config.ARGB_8888)
                    val c = android.graphics.Canvas(bmp)
                    drawable.setBounds(0, 0, iconSizePx, iconSizePx)
                    drawable.draw(c)
                    avatar.setPhoto(bmp)
                    invalidate()
                }
            ).build()
        Coil.imageLoader(context).enqueue(request)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            iconSizePx + paddingVPx * 2
        )
    }

    override fun onDraw(canvas: Canvas) {
        val clan = this.clan ?: return
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
        if (avatar != null) {
            avatar.setBounds(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
            avatar.draw(canvas)
        } else {
            bgPaint.color = AvatarDrawable.colorForId(clan.clanId)
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
