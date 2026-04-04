package com.mezon.mobile.home.voice

import android.content.Context
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.core.AvatarDrawable
import com.mezon.mobile.core.BottomSheet
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.clans.VoiceMemberDisplay
import com.mezon.mobile.ui.cells.MezonIcon

class JoinVoiceBottomSheet(
    context: Context,
    private val themeColors: ThemeColors,
    private val channelLabel: String,
    private val channelId: Long,
    private val clanId: Long,
    private val members: List<VoiceMemberDisplay>
) : BottomSheet(context) {

    var onJoinVoice: (() -> Unit)? = null
    var onOpenChat: (() -> Unit)? = null

    init {
        setCustomView(buildContent(context))
    }

    private fun buildContent(context: Context): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(24), LayoutHelper.dp(20), LayoutHelper.dp(24), LayoutHelper.dp(24))
        }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleText = TextView(context).apply {
            text = channelLabel
            setTextColor(themeColors.onSurface)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        headerRow.addView(titleText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(headerRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val avatarContainer = FrameLayout(context).apply {
            setPadding(0, LayoutHelper.dp(24), 0, LayoutHelper.dp(8))
        }
        val avatarRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val maxDisplay = 3
        val displayMembers = members.take(maxDisplay)
        val avatarSizePx = LayoutHelper.dp(48)
        val overlapPx = LayoutHelper.dp(12)

        for ((i, member) in displayMembers.withIndex()) {
            val avatarDrawable = AvatarDrawable().apply {
                setInfo(member.userId, member.displayName)
            }
            val avatarView = ImageView(context).apply {
                setImageDrawable(avatarDrawable)
            }
            val lp = LinearLayout.LayoutParams(avatarSizePx, avatarSizePx)
            if (i > 0) lp.marginStart = -overlapPx
            avatarRow.addView(avatarView, lp)
        }

        if (members.size > maxDisplay) {
            val overflowText = TextView(context).apply {
                text = "+${members.size - maxDisplay}"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFFFFFFFF.toInt())
                gravity = Gravity.CENTER
                val bgDrawable = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(themeColors.surfaceVariant)
                }
                background = bgDrawable
            }
            val lp = LinearLayout.LayoutParams(avatarSizePx, avatarSizePx)
            lp.marginStart = -overlapPx
            avatarRow.addView(overflowText, lp)
        }
        avatarContainer.addView(avatarRow, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))
        root.addView(avatarContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val roomLabel = TextView(context).apply {
            text = "Voice Room"
            setTextColor(themeColors.onSurface)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        root.addView(roomLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            bottomMargin = LayoutHelper.dp(4)
        })

        val statusLabel = TextView(context).apply {
            text = when {
                members.size >= 2 -> "Everyone is waiting inside"
                members.size == 1 -> "1 person is in the voice room"
                else -> "No one is in the voice room"
            }
            setTextColor(themeColors.onSurfaceVariant)
            textSize = 14f
            gravity = Gravity.CENTER
        }
        root.addView(statusLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            bottomMargin = LayoutHelper.dp(24)
        })

        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val joinButton = TextView(context).apply {
            text = "Join Voice"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            val bgDrawable = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(24).toFloat()
                setColor(0xFF43B581.toInt())
            }
            background = bgDrawable
            setPadding(LayoutHelper.dp(32), LayoutHelper.dp(12), LayoutHelper.dp(32), LayoutHelper.dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                onJoinVoice?.invoke()
                dismiss()
            }
        }
        buttonRow.addView(joinButton, LinearLayout.LayoutParams(
            0, LayoutHelper.dp(48), 1f
        ))

        val chatButton = FrameLayout(context).apply {
            val bgDrawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(themeColors.surfaceVariant)
            }
            background = bgDrawable
            isClickable = true
            isFocusable = true
            setOnClickListener {
                onOpenChat?.invoke()
                dismiss()
            }
        }
        val chatIcon = ImageView(context).apply {
            setImageDrawable(MezonIcon.chatIcon.getDrawable(context).apply {
                colorFilter = PorterDuffColorFilter(themeColors.onSurface, PorterDuff.Mode.SRC_IN)
            })
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        chatButton.addView(chatIcon, FrameLayout.LayoutParams(
            LayoutHelper.dp(24), LayoutHelper.dp(24), Gravity.CENTER
        ))
        buttonRow.addView(chatButton, LinearLayout.LayoutParams(
            LayoutHelper.dp(48), LayoutHelper.dp(48)
        ).apply { marginStart = LayoutHelper.dp(12) })

        root.addView(buttonRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        return root
    }
}
