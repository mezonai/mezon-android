package com.mezon.mobile.home.chat

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.R
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.cells.AvatarView
import com.mezon.mobile.util.formatRelativeTime
import org.json.JSONException
import org.json.JSONObject

class SendTokenMessageCell(context: Context, private val theme: ThemeColors) : FrameLayout(context) {

    interface Delegate {
        fun onMezonTransferClick()
    }

    var delegate: Delegate? = null

    var messageEntity: MessageEntity? = null
        private set

    private val avatarView: AvatarView
    private val senderNameView: TextView
    private val timeView: TextView
    private val cardView: LinearLayout
    private val transactionIconView: ImageView
    private val titleView: TextView
    private val detailView: TextView
    private val dividerView: View
    private val transferButton: TextView

    companion object {
        private const val MESSAGE_PART_SEPARATOR = " | "
    }

    init {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(LayoutHelper.dp(12), LayoutHelper.dp(4), LayoutHelper.dp(12), LayoutHelper.dp(4))
        }

        avatarView = AvatarView(context).apply {
            clipToOutline = true
        }
        row.addView(avatarView, LinearLayout.LayoutParams(
            LayoutHelper.dp(36), LayoutHelper.dp(36)
        ).apply { rightMargin = LayoutHelper.dp(8); gravity = Gravity.TOP; topMargin = LayoutHelper.dp(2) })

        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        senderNameView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(theme.onSurface)
            maxLines = 1
        }
        timeView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11f)
            setTextColor(theme.onSurfaceVariant)
            maxLines = 1
            setPadding(LayoutHelper.dp(6), 0, 0, 0)
        }
        headerRow.addView(senderNameView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        headerRow.addView(timeView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        col.addView(headerRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = LayoutHelper.dp(4) })

        cardView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(theme.secondaryLight)
                cornerRadius = LayoutHelper.dp(12).toFloat()
            }
            setPadding(LayoutHelper.dp(12), LayoutHelper.dp(10), LayoutHelper.dp(12), LayoutHelper.dp(4))
            clipToOutline = true
        }

        val infoRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val iconBg = FrameLayout(context)
        transactionIconView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            val d = MezonIcon.transactionIcon.getDrawable(context)
            d.colorFilter = PorterDuffColorFilter(theme.colorSuccess, PorterDuff.Mode.SRC_IN)
            setImageDrawable(d)
        }
        iconBg.addView(transactionIconView, LayoutHelper.createFrame(34, 34))
        infoRow.addView(iconBg, LinearLayout.LayoutParams(
            LayoutHelper.dp(40), LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = LayoutHelper.dp(8) })

        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        titleView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(theme.onSurface)
            maxLines = 2
        }
        detailView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
            setTextColor(theme.onSurface)
            maxLines = 3
        }
        textCol.addView(titleView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        textCol.addView(detailView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        infoRow.addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        cardView.addView(infoRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = LayoutHelper.dp(8) })

        dividerView = View(context).apply {
            setBackgroundColor(theme.getColor(ThemeColors.key_divider))
        }
        cardView.addView(dividerView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1
        ))

        transferButton = TextView(context).apply {
            text = context.getString(R.string.token_transaction_transfer_button)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(theme.blurple)
            gravity = Gravity.CENTER
            setPadding(0, LayoutHelper.dp(8), 0, LayoutHelper.dp(6))
            setOnClickListener { delegate?.onMezonTransferClick() }
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            foreground = context.getDrawable(outValue.resourceId)
            isClickable = true
            isFocusable = true
        }
        cardView.addView(transferButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        col.addView(cardView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = LayoutHelper.dp(80) })

        row.addView(col, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        addView(row, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
    }

    fun update(msg: MessageEntity) {
        messageEntity = msg
        senderNameView.text = msg.senderName
        timeView.text = formatRelativeTime(msg.timestampSeconds)

        avatarView.setInfo(msg.senderId, msg.senderUsername)
        avatarView.setImageUrl(msg.senderAvatar.ifEmpty { null })

        val rawText = try {
            JSONObject(msg.content).optString("t", msg.content)
        } catch (_: JSONException) {
            msg.content
        }

        val parts = rawText.split(MESSAGE_PART_SEPARATOR, limit = 2)
        val title = parts.getOrElse(0) { rawText }
        val description = parts.getOrElse(1) { "" }

        titleView.text = title

        if (description.isNotEmpty()) {
            val ssb = SpannableStringBuilder()
            val detailLabel = "${context.getString(R.string.common_detail)}: "
            ssb.append(detailLabel)
            ssb.setSpan(
                ForegroundColorSpan(theme.blurple),
                0, detailLabel.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            ssb.setSpan(
                StyleSpan(Typeface.BOLD),
                0, detailLabel.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            ssb.append(description)
            detailView.text = ssb
            detailView.visibility = View.VISIBLE
        } else {
            detailView.visibility = View.GONE
        }
    }
}
