package com.mezon.mobile.home.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import com.mezon.mobile.R
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.network.LinkInvitePreview
import com.mezon.mobile.ui.theme.ThemeMode
import com.mezon.mobile.util.createImgproxyUrl
import com.mezon.mobile.util.parseInviteLinkMessageId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

private sealed class LinkInviteUiState {
    data object Hidden : LinkInviteUiState()
    data object Loading : LinkInviteUiState()
    data object Invalid : LinkInviteUiState()
    data class Ready(val preview: LinkInvitePreview) : LinkInviteUiState()
}

class LinkInviteBlock(
    private val host: View,
    private val theme: ThemeColors,
    private val currentMessageId: () -> Long?
) {
    var loadLinkInvitePreview: (suspend (Long) -> LinkInvitePreview?)? = null

    private val logo = ImageReceiver(host)
    private var state: LinkInviteUiState = LinkInviteUiState.Hidden
    private var activeInviteId = 0L
    private var previewJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var titleLayout: StaticLayout? = null
    private var clanLayout: StaticLayout? = null
    private var channelLayout: StaticLayout? = null
    private var invalidTitleLayout: StaticLayout? = null
    private var invalidMsgLayout: StaticLayout? = null
    private var joinLayout: StaticLayout? = null
    private var cardW = 0
    var blockHeight: Int = 0
        private set
    var cachedWidth: Float = 0f
        private set
    val joinRect = RectF()
    private val cardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val joinBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val titleTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val clanTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val channelTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val invalidTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val joinTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val cardRect = RectF()
    private val defaultClanFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val defaultClanTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, 600, false)
        textAlign = Paint.Align.CENTER
    }
    private val invalidPhPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val loadingStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val tmpAvatarRect = RectF()
    private val tmpLoadingArc = RectF()
    private var loadingAngleDeg = 0f
    private var loadingBodyRowHeight = 0

    val isVisible: Boolean
        get() = state !is LinkInviteUiState.Hidden
    val activeIdForJoin: Long
        get() = if (state is LinkInviteUiState.Ready) activeInviteId else 0L

    private val context: Context
        get() = host.context

    private val cardRadius: Float
        get() = LayoutHelper.dpf(12f)
    private val joinRadius: Float
        get() = LayoutHelper.dpf(4f)
    private val defaultAvatarCorner: Float
        get() = LayoutHelper.dpf(8f)
    private val loadingSize: Int
        get() = LayoutHelper.dp(20)

    fun onAttachedToWindow() {
        logo.onAttachedToWindow()
    }

    fun onDetachedFromWindow() {
        logo.onDetachedFromWindow()
    }

    fun clear() {
        previewJob?.cancel()
        previewJob = null
        state = LinkInviteUiState.Hidden
        activeInviteId = 0L
        titleLayout = null
        clanLayout = null
        channelLayout = null
        invalidTitleLayout = null
        invalidMsgLayout = null
        joinLayout = null
        cardW = 0
        blockHeight = 0
        cachedWidth = 0f
        joinRect.setEmpty()
        logo.setImage(null, null, context)
        loadingAngleDeg = 0f
        loadingBodyRowHeight = 0
    }

    fun build(msg: MessageEntity, bubbleMaxW: Int, onPreviewLayoutChanged: () -> Unit) {
        val inviteId = parseInviteLinkMessageId(msg.content)
        if (inviteId == null) {
            state = LinkInviteUiState.Hidden
            blockHeight = 0
            cachedWidth = 0f
            titleLayout = null
            clanLayout = null
            channelLayout = null
            invalidTitleLayout = null
            invalidMsgLayout = null
            joinLayout = null
            logo.setImage(null, null, context)
            return
        }
        if (activeInviteId != inviteId) {
            previewJob?.cancel()
            previewJob = null
            state = LinkInviteUiState.Loading
            activeInviteId = inviteId
        } else if (state is LinkInviteUiState.Hidden) {
            state = LinkInviteUiState.Loading
        }
        cardBgPaint.color = theme.channelPanelBg
        joinBgPaint.color = theme.blurple
        titleTextPaint.set(theme.chatTimePaint)
        titleTextPaint.textSize = LayoutHelper.dpf(CARD_TITLE_SP)
        titleTextPaint.typeface = Typeface.create(Typeface.DEFAULT, 600, false)
        titleTextPaint.color = theme.textDisabled
        titleTextPaint.letterSpacing = letterSpacingEmFromRnHalf(titleTextPaint)
        clanTextPaint.set(theme.chatContentPaint)
        clanTextPaint.typeface = Typeface.create(Typeface.DEFAULT, 700, false)
        clanTextPaint.color = theme.colorText
        channelTextPaint.set(theme.chatTimePaint)
        channelTextPaint.textSize = LayoutHelper.dpf(CHANNEL_TEXT_SP)
        channelTextPaint.typeface = Typeface.create(Typeface.DEFAULT, 500, false)
        channelTextPaint.color = theme.textDisabled
        channelTextPaint.letterSpacing = letterSpacingEmFromRnHalf(channelTextPaint)
        invalidTitlePaint.set(clanTextPaint)
        invalidTitlePaint.color = theme.error
        invalidTitlePaint.letterSpacing = 0f
        joinTextPaint.set(clanTextPaint)
        joinTextPaint.color = 0xFFFFFFFF.toInt()
        joinTextPaint.textSize = LayoutHelper.dpf(14f)
        joinTextPaint.typeface = Typeface.create(Typeface.DEFAULT, 600, false)
        joinTextPaint.letterSpacing = letterSpacingEmFromRnHalf(joinTextPaint)
        loadingStrokePaint.color = theme.colorText
        loadingStrokePaint.strokeWidth = LayoutHelper.dpf(2f)
        invalidPhPaint.color = invalidPlaceholderBackground()

        if (loadLinkInvitePreview == null && state is LinkInviteUiState.Loading) {
            state = LinkInviteUiState.Ready(LinkInvitePreview("", "", ""))
        }
        cardW = min(bubbleMaxW, CARD_MAX_W).coerceAtLeast(1)
        val innerW = (cardW - INNER_PAD * 2).coerceAtLeast(1)
        val titleText = context.getString(R.string.clan_link_invite_title)
        titleLayout = StaticLayout.Builder.obtain(titleText, 0, titleText.length, titleTextPaint, innerW)
            .setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

        if (state is LinkInviteUiState.Loading && loadLinkInvitePreview != null && previewJob?.isActive != true) {
            requestLoad(msg, inviteId, onPreviewLayoutChanged)
        }
        val textColW = (innerW - AVATAR - ROW_GAP)
        val nameW = max(1, (textColW * 0.91f).toInt().coerceAtMost(textColW))
        when (val st = state) {
            is LinkInviteUiState.Hidden -> {}
            is LinkInviteUiState.Loading -> {
                clanLayout = null
                channelLayout = null
                invalidTitleLayout = null
                invalidMsgLayout = null
                joinLayout = null
                logo.setImage(null, null, context)
            }
            is LinkInviteUiState.Invalid -> {
                val invT = context.getString(R.string.clan_link_invite_invalid_title)
                invalidTitleLayout = StaticLayout.Builder.obtain(invT, 0, invT.length, invalidTitlePaint, nameW)
                    .setMaxLines(1)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .build()
                val invM = context.getString(R.string.clan_link_invite_invalid_message)
                val invPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
                invPaint.set(channelTextPaint)
                invalidMsgLayout = StaticLayout.Builder.obtain(invM, 0, invM.length, invPaint, nameW)
                    .setMaxLines(2)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .build()
                clanLayout = null
                channelLayout = null
                joinLayout = null
                logo.setImage(null, null, context)
            }
            is LinkInviteUiState.Ready -> {
                val p = st.preview
                invalidTitleLayout = null
                invalidMsgLayout = null
                val clan = p.clanName.trim().ifEmpty { context.getString(R.string.clan_link_invite_unknown_clan) }
                clanLayout = StaticLayout.Builder.obtain(clan, 0, clan.length, clanTextPaint, nameW)
                    .setMaxLines(1)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .build()
                if (p.channelLabel.isNotEmpty()) {
                    val chT = context.getString(R.string.clan_link_invite_channel_prefix, p.channelLabel)
                    channelLayout = StaticLayout.Builder.obtain(chT, 0, chT.length, channelTextPaint, nameW)
                        .setMaxLines(1)
                        .setEllipsize(TextUtils.TruncateAt.END)
                        .build()
                } else {
                    channelLayout = null
                }
                val joinT = context.getString(R.string.clan_link_invite_join)
                joinLayout = StaticLayout.Builder.obtain(joinT, 0, joinT.length, joinTextPaint, innerW)
                    .setMaxLines(1)
                    .build()
                if (p.logoUrl.isNotEmpty()) {
                    val av = LayoutHelper.dp(48)
                    val prox = createImgproxyUrl(p.logoUrl, av, av, "fit")
                    logo.setRoundRadius(LayoutHelper.dp(14))
                    logo.setImage(prox, null, context)
                } else {
                    logo.setImage(null, null, context)
                }
            }
        }
        var h = INNER_PAD
        titleLayout?.let { h += it.height + TITLE_TO_BODY_GAP }
        loadingBodyRowHeight = 0
        when (state) {
            is LinkInviteUiState.Loading -> {
                val (rowH, joinBlockH) = measureReservedReadyBlockHeight(innerW, nameW)
                loadingBodyRowHeight = rowH
                h += rowH + BLOCK_GAP + joinBlockH
            }
            is LinkInviteUiState.Invalid -> {
                val colH = (invalidTitleLayout?.height ?: 0) + (invalidMsgLayout?.let { it.height + LayoutHelper.dp(2) } ?: 0)
                h += max(AVATAR, colH)
            }
            is LinkInviteUiState.Ready -> {
                val colH = (clanLayout?.height ?: 0) + (channelLayout?.let { it.height + LayoutHelper.dp(2) } ?: 0)
                val rowH = max(AVATAR, colH)
                h += rowH
                h += BLOCK_GAP
                h += JOIN_V * 2 + (joinLayout?.height ?: LayoutHelper.dp(14))
            }
            else -> {}
        }
        h += INNER_PAD
        blockHeight = h
        cachedWidth = cardW.toFloat()
    }

    private fun measureReservedReadyBlockHeight(innerW: Int, nameW: Int): Pair<Int, Int> {
        val joinT = context.getString(R.string.clan_link_invite_join)
        val joinLay = StaticLayout.Builder.obtain(joinT, 0, joinT.length, joinTextPaint, innerW)
            .setMaxLines(1)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        val joinBlockH = JOIN_V * 2 + joinLay.height
        val clanPl = context.getString(R.string.clan_link_invite_unknown_clan)
        val clanLay = StaticLayout.Builder.obtain(clanPl, 0, clanPl.length, clanTextPaint, nameW)
            .setMaxLines(1)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        val chPl = context.getString(R.string.clan_link_invite_channel_prefix, "\u2014")
        val chLay = StaticLayout.Builder.obtain(chPl, 0, chPl.length, channelTextPaint, nameW)
            .setMaxLines(1)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        val colH = clanLay.height + LayoutHelper.dp(2) + chLay.height
        val rowH = max(AVATAR, colH)
        return Pair(rowH, joinBlockH)
    }

    private fun invalidPlaceholderBackground(): Int {
        return when (theme.resolvedMode) {
            ThemeMode.LIGHT -> 0xFFF0F0F0.toInt()
            else -> theme.secondaryLight
        }
    }

    private fun letterSpacingEmFromRnHalf(paint: TextPaint): Float {
        return LayoutHelper.dpf(0.5f) / paint.textSize.coerceAtLeast(1e-3f)
    }

    private fun requestLoad(msg: MessageEntity, inviteId: Long, onPreviewLayoutChanged: () -> Unit) {
        val loader = loadLinkInvitePreview ?: return
        previewJob?.cancel()
        previewJob = scope.launch {
            val result = try {
                withContext(Dispatchers.IO) { loader(inviteId) }
            } catch (_: Exception) {
                null
            }
            if (currentMessageId() != msg.id) return@launch
            val preview = result ?: LinkInvitePreview("", "", "")
            state = LinkInviteUiState.Ready(preview)
            if (currentMessageId() == msg.id) {
                onPreviewLayoutChanged()
            }
        }
    }

    fun draw(canvas: Canvas, contentLeft: Float, yTop: Float): Float {
        if (state is LinkInviteUiState.Hidden) return yTop
        var y = yTop
        val cLeft = contentLeft
        val cW = cardW.toFloat()
        cardRect.set(cLeft, y, cLeft + cW, y + blockHeight)
        canvas.drawRoundRect(cardRect, cardRadius, cardRadius, cardBgPaint)
        y += INNER_PAD
        val innerL = contentLeft + INNER_PAD
        val innerW = (cardW - INNER_PAD * 2).coerceAtLeast(1)
        titleLayout?.let {
            canvas.save()
            canvas.translate(innerL, y)
            it.draw(canvas)
            canvas.restore()
            y += it.height + TITLE_TO_BODY_GAP
        }
        when (val st = state) {
            is LinkInviteUiState.Loading -> {
                val rowH = loadingBodyRowHeight.coerceAtLeast(AVATAR)
                val cx = innerL + innerW / 2f
                val cy = y + rowH / 2f
                val r = loadingSize / 2f
                tmpLoadingArc.set(cx - r, cy - r, cx + r, cy + r)
                canvas.drawArc(tmpLoadingArc, loadingAngleDeg, 270f, false, loadingStrokePaint)
                loadingAngleDeg = (loadingAngleDeg + 6f) % 360f
                if (host.isAttachedToWindow) {
                    host.postInvalidateOnAnimation()
                }
            }
            is LinkInviteUiState.Invalid -> {
                val avSize = AVATAR.toFloat()
                val avLeft = innerL
                val avTop = y
                tmpAvatarRect.set(avLeft, avTop, avLeft + avSize, avTop + avSize)
                canvas.drawRoundRect(tmpAvatarRect, defaultAvatarCorner, defaultAvatarCorner, invalidPhPaint)
                val textLeft = avLeft + avSize + ROW_GAP
                var iy = avTop
                invalidTitleLayout?.let {
                    canvas.save()
                    canvas.translate(textLeft, iy)
                    it.draw(canvas)
                    canvas.restore()
                    iy += it.height + LayoutHelper.dp(2)
                }
                invalidMsgLayout?.let {
                    canvas.save()
                    canvas.translate(textLeft, iy)
                    it.draw(canvas)
                    canvas.restore()
                }
            }
            is LinkInviteUiState.Ready -> {
                val p = st.preview
                val displayClan = p.clanName.trim().ifEmpty { context.getString(R.string.clan_link_invite_unknown_clan) }
                val avSize = AVATAR.toFloat()
                val avLeft = innerL
                val avTop = y
                if (p.logoUrl.isNotEmpty()) {
                    tmpAvatarRect.set(avLeft, avTop, avLeft + avSize, avTop + avSize)
                    canvas.drawRoundRect(tmpAvatarRect, CLAN_LOGO_AVATAR_R, CLAN_LOGO_AVATAR_R, cardBgPaint)
                    logo.setImageCoords(avLeft, avTop, avSize, avSize)
                    logo.setRoundRadius(LayoutHelper.dp(14))
                    logo.draw(canvas)
                } else {
                    drawDefaultClanInitial(canvas, avLeft, avTop, avSize, displayClan)
                }
                val textTop = avTop
                clanLayout?.let {
                    val tx = avLeft + avSize + ROW_GAP
                    canvas.save()
                    canvas.translate(tx, textTop)
                    it.draw(canvas)
                    canvas.restore()
                }
                val chY = textTop + (clanLayout?.height ?: 0) + LayoutHelper.dp(2)
                channelLayout?.let {
                    val tx = avLeft + avSize + ROW_GAP
                    canvas.save()
                    canvas.translate(tx, chY)
                    it.draw(canvas)
                    canvas.restore()
                }
                val textColH = (clanLayout?.height ?: 0) +
                    (channelLayout?.let { it.height + LayoutHelper.dp(2) } ?: 0)
                y += max(avSize, textColH.toFloat())
                y += BLOCK_GAP
                val jh = (joinLayout?.height ?: LayoutHelper.dp(14)) + JOIN_V * 2
                val joinW = innerW.toFloat()
                joinRect.set(innerL, y, innerL + joinW, y + jh)
                canvas.drawRoundRect(joinRect, joinRadius, joinRadius, joinBgPaint)
                joinLayout?.let { jl ->
                    val jx = joinRect.left + (joinRect.width() - staticLayoutMaxWidth(jl)) / 2f
                    val jy = joinRect.top + (joinRect.height() - jl.height) / 2f
                    canvas.save()
                    canvas.translate(jx, jy)
                    jl.draw(canvas)
                    canvas.restore()
                }
            }
            else -> {}
        }
        return yTop + blockHeight
    }

    private fun drawDefaultClanInitial(canvas: Canvas, left: Float, top: Float, size: Float, clanName: String) {
        defaultClanFillPaint.color = theme.blurple
        tmpAvatarRect.set(left, top, left + size, top + size)
        canvas.drawRoundRect(tmpAvatarRect, defaultAvatarCorner, defaultAvatarCorner, defaultClanFillPaint)
        val initial = (clanName.firstOrNull { !it.isWhitespace() }?.uppercaseChar()?.toString()) ?: "?"
        defaultClanTextPaint.textSize = LayoutHelper.dpf(26f)
        val cx = left + size / 2f
        val cy = top + size / 2f - (defaultClanTextPaint.ascent() + defaultClanTextPaint.descent()) / 2f
        canvas.drawText(initial, 0, 1, cx, cy, defaultClanTextPaint)
    }

    fun hitTestJoin(x: Float, y: Float): Boolean {
        if (state !is LinkInviteUiState.Ready) return false
        return x >= joinRect.left && x <= joinRect.right && y >= joinRect.top && y <= joinRect.bottom
    }

    companion object {
        private val CARD_MAX_W = LayoutHelper.dp(260)
        private val INNER_PAD = LayoutHelper.dp(12)
        private val TITLE_TO_BODY_GAP = LayoutHelper.dp(10)
        private val CLAN_LOGO_AVATAR_R = LayoutHelper.dpf(14f)
        private val BLOCK_GAP = LayoutHelper.dp(16)
        private val AVATAR = LayoutHelper.dp(48)
        private val ROW_GAP = LayoutHelper.dp(10)
        private val JOIN_V = LayoutHelper.dp(6)
        private const val CARD_TITLE_SP = 11f
        private const val CHANNEL_TEXT_SP = 12f
    }
}

private fun staticLayoutMaxWidth(layout: StaticLayout): Float {
    var maxW = 0f
    for (i in 0 until layout.lineCount) {
        maxW = maxOf(maxW, layout.getLineWidth(i))
    }
    return maxW
}
