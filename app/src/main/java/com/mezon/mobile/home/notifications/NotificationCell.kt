package com.mezon.mobile.home.notifications

import android.content.Context
import android.graphics.Canvas
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import android.view.View
import coil.Coil
import coil.request.ImageRequest
import coil.size.Size
import com.mezon.mobile.core.AvatarDrawable
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.util.formatRelativeTime

private const val TAG = "NotificationCell"

class NotificationCell(context: Context, private val theme: ThemeColors) : View(context) {

    init {
        isClickable = true
        isFocusable = true
        val outValue = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        setBackgroundResource(outValue.resourceId)
    }

    var entity: NotificationEntity? = null
        private set

    private val avatarDrawable = AvatarDrawable()
    private var currentAvatarUrl: String? = null

    private var subjectLayout: StaticLayout? = null
    private var bodyLayout: StaticLayout? = null
    private var timeLayout: StaticLayout? = null

    private val subjectPaint = TextPaint(theme.dialogNameBoldPaint)
    private val bodyPaint = TextPaint(theme.dialogMessagePaint)
    private val timePaint = TextPaint(theme.dialogTimePaint)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), CELL_HEIGHT)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildLayouts()
    }

    fun setData(n: NotificationEntity) {
        entity = n
        val displayName = n.senderName.ifEmpty { n.subject }
        avatarDrawable.setInfo(n.id, displayName)
        buildLayouts()
        loadAvatar(n.avatarUrl)
        invalidate()
    }

    private fun buildLayouts() {
        val n = entity ?: return
        val contentWidth = width - PADDING_H * 2 - AVATAR_SIZE - GAP_H
        if (contentWidth <= 0) return

        val timeText = formatRelativeTime(n.createTimeSeconds)
        timePaint.color = theme.onSurfaceVariant
        timeLayout = StaticLayout.Builder
            .obtain(timeText, 0, timeText.length, timePaint, contentWidth)
            .setMaxLines(1)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

        val timeWidth = timeLayout?.let { it.getLineWidth(0).toInt() + LayoutHelper.dp(8) } ?: 0
        val firstLineWidth = (contentWidth - timeWidth).coerceAtLeast(1)

        val subjectText = buildSubjectText(n)
        subjectPaint.color = theme.onSurface
        subjectLayout = StaticLayout.Builder
            .obtain(subjectText, 0, subjectText.length, subjectPaint, firstLineWidth)
            .setMaxLines(1)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

        val bodyText = n.messageText.ifEmpty { n.subject }
        bodyPaint.color = theme.onSurfaceVariant
        bodyLayout = StaticLayout.Builder
            .obtain(bodyText, 0, bodyText.length, bodyPaint, contentWidth)
            .setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
    }

    private fun buildSubjectText(n: NotificationEntity): String {
        val sender = n.senderName.ifEmpty { n.subject }
        return when {
            n.clanName.isNotEmpty() && n.channelLabel.isNotEmpty() ->
                "$sender · ${n.clanName} # ${n.channelLabel}"
            n.clanName.isNotEmpty() -> "$sender · ${n.clanName}"
            n.channelLabel.isNotEmpty() -> "$sender · #${n.channelLabel}"
            else -> sender
        }
    }

    private fun loadAvatar(url: String) {
        if (url == currentAvatarUrl && avatarDrawable.hasPhoto()) return
        currentAvatarUrl = url
        avatarDrawable.setPhoto(null)
        if (url.isNotEmpty()) {
            val request = ImageRequest.Builder(context)
                .data(url)
                .size(Size(AVATAR_SIZE, AVATAR_SIZE))
                .allowHardware(false)
                .target(onSuccess = { d ->
                    avatarDrawable.setPhoto(LayoutHelper.drawableToBitmap(d, AVATAR_SIZE))
                    post { invalidate() }
                })
                .build()
            Coil.imageLoader(context).enqueue(request)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val avatarLeft = PADDING_H
        val avatarTop = (height - AVATAR_SIZE) / 2
        avatarDrawable.setBounds(avatarLeft, avatarTop, avatarLeft + AVATAR_SIZE, avatarTop + AVATAR_SIZE)
        avatarDrawable.draw(canvas)

        val textLeft = (avatarLeft + AVATAR_SIZE + GAP_H).toFloat()
        var textTop = PADDING_V.toFloat()

        subjectLayout?.let {
            canvas.save()
            canvas.translate(textLeft, textTop)
            it.draw(canvas)
            canvas.restore()
        }

        timeLayout?.let {
            val tx = width - PADDING_H - it.getLineWidth(0)
            canvas.save()
            canvas.translate(tx, textTop + (subjectPaint.textSize - timePaint.textSize) / 2)
            it.draw(canvas)
            canvas.restore()
        }

        textTop += (subjectLayout?.height ?: 0) + LayoutHelper.dp(2).toFloat()

        bodyLayout?.let {
            canvas.save()
            canvas.translate(textLeft, textTop)
            it.draw(canvas)
            canvas.restore()
        }

        val divLeft = (PADDING_H + AVATAR_SIZE + GAP_H).toFloat()
        canvas.drawRect(divLeft, height - 1f, width.toFloat(), height.toFloat(), theme.dividerPaint)
    }

    companion object {
        private val AVATAR_SIZE = LayoutHelper.dp(44)
        private val PADDING_H = LayoutHelper.dp(16)
        private val PADDING_V = LayoutHelper.dp(14)
        private val GAP_H = LayoutHelper.dp(12)
        private val CELL_HEIGHT = LayoutHelper.dp(76)
    }
}
