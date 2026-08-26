package com.mezon.mobile.home.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import com.mezon.mobile.R
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.util.MentionColors
import com.mezon.mobile.util.firstReferenceMessageId
import com.mezon.mobile.util.formatRelativeTime
import com.mezon.mobile.util.parseContentText
import com.mezon.mobile.util.parseContentToSpannable
import com.mezon.mobile.util.parseThreadInfoFromPlainText
import org.json.JSONObject

class SystemMessageCell(context: Context, private val theme: ThemeColors) : LinearLayout(context) {

    interface Delegate {
        fun onOpenThread(threadChannelId: Long, threadTitle: String)
        fun onSeeAllThreads()
        fun onMentionClick(userId: String?, roleId: String?)
        fun onJumpToPinnedMessage(messageRefId: Long)
        fun onSeeAllPins()
        fun onWaveWelcomeClick(message: MessageEntity)
    }

    var delegate: Delegate? = null

    var mentionInteractiveGate: ((userId: String?, roleId: String?, segmentText: String) -> Boolean)? = null
    var creatorNameResolver: ((Long) -> String)? = null

    var messageEntity: MessageEntity? = null
        private set

    private val iconView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    private val highlightTextView = SystemThreadHighlightTextView(context, theme)

    private val plainMessageTextView = SystemMessagePlainTextView(context, theme)

    private val timeTextView = TextView(context).apply {
        maxLines = 1
        includeFontPadding = false
        val paint = theme.chatTimePaint
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, paint.textSize)
        setTextColor(paint.color)
        typeface = paint.typeface
    }

    private val waveStickerView = WaveStickerImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private val waveWelcomeView = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        visibility = View.GONE
        setPadding(WAVE_H_PAD, WAVE_V_PAD, WAVE_H_PAD, WAVE_V_PAD)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = WAVE_CORNER_RADIUS.toFloat()
            setColor(theme.tertiary)
        }

        addView(waveStickerView, LayoutParams(WAVE_IMAGE_SIZE, WAVE_IMAGE_SIZE))
        addView(
            TextView(context).apply {
                text = context.getString(R.string.dm_wave_welcome)
                maxLines = 1
                includeFontPadding = false
                setTextColor(theme.onSurface)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT).apply {
                marginStart = WAVE_INNER_GAP
            }
        )

        contentDescription = context.getString(R.string.dm_wave_welcome)
        setOnClickListener {
            messageEntity
                ?.takeIf { it.code == MessageEntity.CODE_WELCOME }
                ?.let { delegate?.onWaveWelcomeClick(it) }
        }
    }

    private val textColumn: LinearLayout

    private val highlightBridge = object : SystemThreadHighlightTextView.Listener {
        override fun onThreadTitleClick(threadChannelId: Long, threadTitle: String) {
            delegate?.onOpenThread(threadChannelId, threadTitle)
        }

        override fun onAllThreadsClick() {
            delegate?.onSeeAllThreads()
        }

        override fun onJumpToPinnedMessage(messageRefId: Long) {
            delegate?.onJumpToPinnedMessage(messageRefId)
        }

        override fun onAllPinsClick() {
            delegate?.onSeeAllPins()
        }

        override fun onMentionClick(userId: String?, roleId: String?) {
            delegate?.onMentionClick(userId, roleId)
        }
    }

    var channelName: String = ""
    private var jumpHighlightStartedAtMs = 0L

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        setPadding(PAD_H, PAD_V, PAD_H, PAD_V)

        plainMessageTextView.onMentionClick = { uid, rid ->
            delegate?.onMentionClick(uid, rid)
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            layoutParams = LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)
        }

        row.addView(iconView, LayoutParams(ICON_SIZE_SMALL, ICON_SIZE_SMALL))

        textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f).apply {
                marginStart = ICON_GAP
            }
        }

        textColumn.addView(highlightTextView, LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        textColumn.addView(plainMessageTextView, LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        plainMessageTextView.visibility = View.GONE
        highlightTextView.visibility = View.GONE

        textColumn.addView(
            timeTextView,
            LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                topMargin = GAP_V
            }
        )
        textColumn.addView(
            waveWelcomeView,
            LayoutParams(LayoutHelper.WRAP_CONTENT, WAVE_BUTTON_HEIGHT).apply {
                topMargin = WAVE_TOP_GAP
            }
        )

        row.addView(textColumn)
        addView(row)
    }

    fun setHighlight() {
        jumpHighlightStartedAtMs = android.os.SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (jumpHighlightStartedAtMs == 0L) return
        val elapsed = android.os.SystemClock.uptimeMillis() - jumpHighlightStartedAtMs
        val progress = when {
            elapsed <= HIGHLIGHT_HOLD_MS -> 1f
            elapsed < HIGHLIGHT_HOLD_MS + HIGHLIGHT_FADE_MS ->
                1f - (elapsed - HIGHLIGHT_HOLD_MS).toFloat() / HIGHLIGHT_FADE_MS
            else -> 0f
        }
        val alpha = (progress * HIGHLIGHT_MAX_ALPHA).toInt().coerceIn(0, 255)
        JUMP_HIGHLIGHT_PAINT.color = theme.blurple and 0x00FFFFFF or (alpha shl 24)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), JUMP_HIGHLIGHT_PAINT)
        JUMP_HIGHLIGHT_BORDER_PAINT.color = theme.blurple
        JUMP_HIGHLIGHT_BORDER_PAINT.alpha = (progress * 255).toInt().coerceIn(0, 255)
        canvas.drawRect(
            0f,
            0f,
            HIGHLIGHT_BORDER_WIDTH.toFloat(),
            height.toFloat(),
            JUMP_HIGHLIGHT_BORDER_PAINT
        )
        if (progress > 0f) {
            postInvalidateDelayed(16)
        } else {
            jumpHighlightStartedAtMs = 0L
        }
    }

    fun update(mask: Int, newMsg: MessageEntity? = null): Boolean {
        val msg = newMsg ?: messageEntity ?: return false
        if (newMsg != null) messageEntity = newMsg
        if (mask != 0) return false

        bindIcon(msg, resolveIcon(msg))
        bindBody(msg)
        return true
    }

    fun recycle() {
        waveStickerView.clearImage()
    }

    private fun bindIcon(msg: MessageEntity, d: Drawable?) {
        val lpIcon = iconView.layoutParams as LayoutParams
        val side = iconSizeFor(msg.code)
        lpIcon.width = side
        lpIcon.height = side
        iconView.layoutParams = lpIcon

        val lpCol = textColumn.layoutParams as LayoutParams
        if (d == null) {
            iconView.visibility = View.GONE
            iconView.setImageDrawable(null)
            lpCol.marginStart = 0
        } else {
            iconView.visibility = View.VISIBLE
            iconView.setImageDrawable(d)
            lpCol.marginStart = ICON_GAP
        }
        textColumn.layoutParams = lpCol
    }

    private fun bindBody(msg: MessageEntity) {
        val mentionColors = MentionColors(
            theme.textLink,
            theme.midnightBlue,
            theme.textRoleLink,
            theme.darkMossGreen
        )

        val textStr = parseContentText(msg.content)
        val threadInfo = if (msg.code == MessageEntity.CODE_CREATE_THREAD) {
            parseThreadInfoFromPlainText(textStr)
        } else {
            null
        }

        val timeStr = formatRelativeTime(msg.timestampSeconds)

        if (threadInfo != null) {
            highlightTextView.visibility = View.VISIBLE
            plainMessageTextView.visibility = View.GONE
            timeTextView.visibility = View.VISIBLE
            highlightTextView.setThreadCreatedHighlight(
                highlightBridge,
                threadInfo.label,
                threadInfo.channelId,
                mentionColors,
                resolveThreadCreatorName(msg)
            )
            timeTextView.text = timeStr
        } else if (msg.code == MessageEntity.CODE_CREATE_PIN) {
            highlightTextView.visibility = View.VISIBLE
            plainMessageTextView.visibility = View.GONE
            timeTextView.visibility = View.GONE
            val pinCreator = resolvePinCreatorMention(msg, textStr)
            highlightTextView.setPinCreatedHighlight(
                highlightBridge,
                pinCreator.label,
                pinCreator.userId,
                firstReferenceMessageId(msg.content),
                mentionColors,
                theme
            )
            highlightTextView.text = appendInlineTime(highlightTextView.text, timeStr)
        } else {
            highlightTextView.visibility = View.GONE
            plainMessageTextView.visibility = View.VISIBLE

            val bodyCore: CharSequence = when {
                msg.code == MessageEntity.CODE_CREATE_THREAD && textStr.isBlank() -> systemFallbackText(msg)
                textStr.isBlank() -> systemFallbackText(msg)
                else -> parseContentToSpannable(
                    msg.content,
                    theme.primary,
                    plainMessageTextView,
                    mentionColors,
                    theme,
                    systemPlainHost = plainMessageTextView,
                    systemMentionGate = mentionInteractiveGate
                )
            }
            if (msg.code == MessageEntity.CODE_WELCOME) {
                timeTextView.visibility = View.VISIBLE
                timeTextView.text = timeStr
                plainMessageTextView.text = bodyCore
            } else {
                timeTextView.visibility = View.GONE
                plainMessageTextView.text = appendInlineTime(bodyCore, timeStr)
            }
        }

        bindWaveWelcome(msg)
    }

    private fun bindWaveWelcome(msg: MessageEntity) {
        if (msg.code != MessageEntity.CODE_WELCOME) {
            waveWelcomeView.visibility = View.GONE
            waveStickerView.clearImage()
            return
        }
        waveWelcomeView.visibility = View.VISIBLE
        waveStickerView.bind(WaveWelcome.stickerUrl(msg.timestampSeconds))
    }

    private fun appendInlineTime(body: CharSequence, timeStr: String): CharSequence {
        if (timeStr.isEmpty()) return body
        val sb = SpannableStringBuilder(body)
        val gap = "  "
        sb.append(gap)
        val t0 = sb.length
        sb.append(timeStr)
        val rel = theme.chatTimePaint.textSize / theme.systemMessageTextPaint.textSize
        sb.setSpan(RelativeSizeSpan(rel), t0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(ForegroundColorSpan(theme.chatTimePaint.color), t0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    private fun resolveIcon(msg: MessageEntity): Drawable? {
        val icon = when (msg.code) {
            MessageEntity.CODE_FIRST_MESSAGE -> MezonIcon.auditLog
            MessageEntity.CODE_WELCOME -> MezonIcon.auditLog
            MessageEntity.CODE_CREATE_THREAD -> MezonIcon.threadIcon
            MessageEntity.CODE_CREATE_PIN -> MezonIcon.pinIcon
            MessageEntity.CODE_AUDIT_LOG -> MezonIcon.auditLog
            MessageEntity.CODE_UPCOMING_EVENT -> MezonIcon.auditLog
            else -> null
        } ?: return null

        val d = icon.getDrawable(context).mutate()
        if (msg.code == MessageEntity.CODE_CREATE_THREAD) {
            return d
        }
        val tint = when (msg.code) {
            MessageEntity.CODE_FIRST_MESSAGE -> theme.success
            MessageEntity.CODE_WELCOME -> theme.success
            MessageEntity.CODE_AUDIT_LOG -> theme.blurple
            MessageEntity.CODE_UPCOMING_EVENT -> theme.error
            else -> theme.onSurfaceVariant
        }
        d.setTint(tint)
        return d
    }

    private fun resolveThreadCreatorName(msg: MessageEntity): String {
        val base = msg.senderName.ifBlank { msg.senderUsername }.trim()
        if (base.isNotEmpty() && !base.equals("system", ignoreCase = true)) {
            return formatCreatorName(base)
        }
        if (msg.senderId != 0L) {
            val resolved = creatorNameResolver?.invoke(msg.senderId)?.trim().orEmpty()
            if (resolved.isNotEmpty()) return formatCreatorName(resolved)
        }
        val fallbackUsername = msg.senderUsername.trim()
        if (fallbackUsername.isNotEmpty() && !fallbackUsername.equals("system", ignoreCase = true)) {
            return formatCreatorName(fallbackUsername)
        }
        val fromContent = resolveThreadCreatorNameFromContent(msg)
        if (fromContent.isNotEmpty()) return formatCreatorName(fromContent)
        return ""
    }

    private fun resolveThreadCreatorNameFromContent(msg: MessageEntity): String {
        return runCatching {
            val obj = JSONObject(msg.content)
            val mentions = obj.optJSONArray("mentions")
            if (mentions != null) {
                for (i in 0 until mentions.length()) {
                    val item = mentions.optJSONObject(i) ?: continue
                    val userId = item.optString("user_id").toLongOrNull() ?: 0L
                    if (userId != 0L) {
                        val resolved = creatorNameResolver?.invoke(userId)?.trim().orEmpty()
                        if (resolved.isNotEmpty()) return@runCatching resolved
                    }
                    val username = item.optString("username")
                        .ifBlank { item.optString("display") }
                        .trim()
                    if (username.isNotEmpty() && !username.equals("system", ignoreCase = true)) {
                        return@runCatching username
                    }
                }
            }
            val cid = obj.optString("cid").toLongOrNull() ?: 0L
            if (cid != 0L) {
                val resolved = creatorNameResolver?.invoke(cid)?.trim().orEmpty()
                if (resolved.isNotEmpty()) return@runCatching resolved
            }
            ""
        }.getOrDefault("")
    }

    private fun formatCreatorName(value: String): String {
        return value.removePrefix("@").trim()
    }

    private data class PinCreatorMention(val label: String, val userId: String?)

    private fun resolvePinCreatorMention(msg: MessageEntity, textStr: String): PinCreatorMention {
        val fromContent = resolvePinCreatorMentionFromContent(msg, textStr)
        if (fromContent != null) return fromContent
        val resolvedName = resolveThreadCreatorName(msg)
        if (resolvedName.isNotEmpty()) {
            val userId = msg.senderId.takeIf { it != 0L }?.toString()
            return PinCreatorMention("@$resolvedName", userId)
        }
        return PinCreatorMention("", null)
    }

    private fun resolvePinCreatorMentionFromContent(msg: MessageEntity, textStr: String): PinCreatorMention? {
        return runCatching {
            val obj = JSONObject(msg.content)
            val mentions = obj.optJSONArray("mentions") ?: return@runCatching null
            if (mentions.length() == 0) return@runCatching null
            val item = mentions.optJSONObject(0) ?: return@runCatching null
            val userId = item.optString("user_id").takeIf { it.isNotBlank() }
            val s = item.optInt("s", -1)
            val e = item.optInt("e", -1)
            val label = when {
                s >= 0 && e > s && e <= textStr.length -> textStr.substring(s, e).trim()
                else -> item.optString("username")
                    .ifBlank { item.optString("display") }
                    .trim()
            }
            if (label.isEmpty()) return@runCatching null
            val display = if (label.startsWith("@")) label else "@$label"
            PinCreatorMention(display, userId)
        }.getOrNull()
    }

    private fun systemFallbackText(msg: MessageEntity): String = when (msg.code) {
        MessageEntity.CODE_FIRST_MESSAGE -> if (channelName.isNotEmpty()) "Welcome to #$channelName" else "Welcome!"
        MessageEntity.CODE_WELCOME -> "Welcome!"
        MessageEntity.CODE_CREATE_THREAD -> context.getString(R.string.system_msg_started_thread_lead) + " …"
        MessageEntity.CODE_CREATE_PIN -> "pinned a message"
        MessageEntity.CODE_AUDIT_LOG -> "audit log"
        MessageEntity.CODE_UPCOMING_EVENT -> "upcoming event"
        else -> ""
    }

    companion object {
        private val JUMP_HIGHLIGHT_PAINT = Paint()
        private val JUMP_HIGHLIGHT_BORDER_PAINT = Paint()
        private val HIGHLIGHT_BORDER_WIDTH = LayoutHelper.dp(2f)
        private const val HIGHLIGHT_HOLD_MS = 1_500L
        private const val HIGHLIGHT_FADE_MS = 300L
        private const val HIGHLIGHT_MAX_ALPHA = 0x30
        private val ICON_SIZE_SMALL = LayoutHelper.dp(20)
        private val ICON_GAP = LayoutHelper.dp(8)
        private val PAD_H = LayoutHelper.dp(16)
        private val PAD_V = LayoutHelper.dp(8)
        private val GAP_V = LayoutHelper.dp(2)
        private val WAVE_IMAGE_SIZE = LayoutHelper.dp(30)
        private val WAVE_H_PAD = LayoutHelper.dp(10)
        private val WAVE_V_PAD = LayoutHelper.dp(4)
        private val WAVE_INNER_GAP = LayoutHelper.dp(6)
        private val WAVE_TOP_GAP = LayoutHelper.dp(8)
        private val WAVE_CORNER_RADIUS = LayoutHelper.dp(6)
        private val WAVE_BUTTON_HEIGHT = WAVE_IMAGE_SIZE + WAVE_V_PAD * 2

        private fun iconSizeFor(code: Int): Int = when (code) {
            MessageEntity.CODE_FIRST_MESSAGE,
            MessageEntity.CODE_WELCOME,
            MessageEntity.CODE_AUDIT_LOG,
            MessageEntity.CODE_UPCOMING_EVENT -> LayoutHelper.dp(24)
            else -> ICON_SIZE_SMALL
        }
    }
}

private class WaveStickerImageView(context: Context) : AppCompatImageView(context) {

    private val imageLoader = MezonImageLoader.getInstance(context)
    private var imageRequest: MezonImageLoader.Cancellable? = null
    private var imageUrl: String? = null
    private var bindGeneration = 0

    fun bind(url: String) {
        if (url == imageUrl && (drawable != null || imageRequest != null)) return
        clearImage()
        imageUrl = url
        loadIfNeeded()
    }

    fun clearImage() {
        bindGeneration++
        imageRequest?.cancel()
        imageRequest = null
        imageUrl = null
        (drawable as? Animatable)?.stop()
        setImageDrawable(null)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        (drawable as? Animatable)?.start() ?: loadIfNeeded()
    }

    override fun onDetachedFromWindow() {
        imageRequest?.cancel()
        imageRequest = null
        (drawable as? Animatable)?.stop()
        super.onDetachedFromWindow()
    }

    private fun loadIfNeeded() {
        val expectedUrl = imageUrl ?: return
        if (!isAttachedToWindow || drawable != null || imageRequest != null) return
        val expectedGeneration = bindGeneration
        val request = imageLoader.loadDrawable(
            expectedUrl,
            WAVE_DECODE_SIZE,
            WAVE_DECODE_SIZE,
            onSuccess = { loadedDrawable ->
                if (expectedGeneration != bindGeneration || imageUrl != expectedUrl) return@loadDrawable
                imageRequest = null
                setImageDrawable(loadedDrawable)
                if (isAttachedToWindow) (loadedDrawable as? Animatable)?.start()
            },
            onError = {
                if (expectedGeneration == bindGeneration && imageUrl == expectedUrl) {
                    imageRequest = null
                }
            }
        )
        if (expectedGeneration == bindGeneration && imageUrl == expectedUrl && drawable == null) {
            imageRequest = request
        } else {
            request.cancel()
        }
    }

    private companion object {
        val WAVE_DECODE_SIZE = LayoutHelper.dp(30)
    }
}
