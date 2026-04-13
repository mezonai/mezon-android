package com.mezon.mobile.home.chat.channelinfo

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.mezon.mobile.core.BottomSheet
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.MezonIcon

class InviteMembersBottomSheet(
    context: Context,
    private val clanId: Long,
    private val channelId: Long,
    private val channelName: String
) : BottomSheet(context) {

    private val theme = ThemeColors.instance

    init {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padH = LayoutHelper.dp(20)
            val padV = LayoutHelper.dp(16)
            setPadding(padH, padV, padH, padV)
        }

        val titleView = TextView(context).apply {
            text = "Invite Members"
            setTextColor(theme.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        container.addView(titleView, LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f,
            Gravity.CENTER, 0f, 0f, 0f, 16f
        ))

        val inviteLink = buildInviteLink()

        val actionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        actionsRow.addView(
            createActionButton(context, MezonIcon.shareIcon, "Share") {
                shareLink(context, inviteLink)
            },
            LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER, 0f, 0f, 24f, 0f)
        )

        actionsRow.addView(
            createActionButton(context, MezonIcon.copyIcon, "Copy Link") {
                copyToClipboard(context, inviteLink)
            }
        )

        container.addView(actionsRow, LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f,
            Gravity.CENTER, 0f, 0f, 0f, 20f
        ))

        val linkPreview = TextView(context).apply {
            text = inviteLink
            setTextColor(theme.onSurfaceVariant)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            gravity = Gravity.CENTER
            maxLines = 2
            val pad = LayoutHelper.dp(12)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(theme.surfaceVariant)
                cornerRadius = LayoutHelper.dpf(8f)
            }
            setOnClickListener {
                copyToClipboard(context, inviteLink)
            }
        }
        container.addView(linkPreview, LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f,
            Gravity.CENTER, 0f, 0f, 0f, 0f
        ))

        setCustomView(container)
    }

    private fun createActionButton(
        context: Context,
        icon: MezonIcon,
        label: String,
        onClick: () -> Unit
    ): LinearLayout {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setOnClickListener { onClick() }
        }

        val circle = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(theme.surfaceVariant)
            }
        }
        val iconView = ImageView(context).apply {
            val d = icon.getDrawable(context)
            d.colorFilter = PorterDuffColorFilter(theme.onSurface, PorterDuff.Mode.SRC_IN)
            setImageDrawable(d)
        }
        circle.addView(iconView, LayoutHelper.createFrame(22, 22, Gravity.CENTER))
        column.addView(circle, LayoutHelper.createLinear(52, 52, 0f, Gravity.CENTER_HORIZONTAL))

        val tv = TextView(context).apply {
            text = label
            setTextColor(theme.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
            gravity = Gravity.CENTER
        }
        column.addView(tv, LayoutHelper.createLinear(
            LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f,
            Gravity.CENTER_HORIZONTAL, 0f, 6f, 0f, 0f
        ))

        return column
    }

    private fun buildInviteLink(): String {
        return "https://mezon.ai/chat/clans/$clanId/channels/$channelId"
    }

    private fun copyToClipboard(context: Context, text: String) {
        val clip = ClipData.newPlainText("Invite Link", text)
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(clip)
        Toast.makeText(context, "Link copied!", Toast.LENGTH_SHORT).show()
    }

    private fun shareLink(context: Context, text: String) {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, "Join #$channelName on Mezon: $text")
            }
            context.startActivity(android.content.Intent.createChooser(intent, "Share invite"))
        } catch (_: Exception) {
            copyToClipboard(context, text)
        }
    }
}
