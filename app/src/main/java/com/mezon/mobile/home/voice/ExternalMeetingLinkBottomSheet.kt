package com.mezon.mobile.home.voice

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.BottomSheet
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.MezonIcon

class ExternalMeetingLinkBottomSheet(
    context: Context,
    private val themeColors: ThemeColors,
    private val meeting: ExternalMeetingRoom,
    private val url: String,
) : BottomSheet(context) {

    companion object {
        const val JOIN_IN_APP_COLOR = 0xFF6361E8.toInt()
        private const val EXTERNAL_COLOR = 0xFFEF4444.toInt()
        private const val EXTERNAL_ICON_BG = 0x29EF4444
    }

    var onJoinInApp: (() -> Unit)? = null
    var onOpenInBrowser: (() -> Unit)? = null

    init {
        setCustomView(buildContent(context))
    }

    private fun buildContent(context: Context): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(10), LayoutHelper.dp(10), LayoutHelper.dp(10), LayoutHelper.dp(24))
        }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val closeButton = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(themeColors.surfaceVariant)
            }
            isClickable = true
            isFocusable = true
            contentDescription = context.getString(R.string.common_close)
            applyVoiceButtonPressFeedback()
            setOnClickListener { dismiss() }
        }
        closeButton.addView(ImageView(context).apply {
            setImageDrawable(MezonIcon.chevronDownSmallIcon.getDrawable(context).apply {
                colorFilter = PorterDuffColorFilter(themeColors.onSurface, PorterDuff.Mode.SRC_IN)
            })
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }, FrameLayout.LayoutParams(LayoutHelper.dp(20), LayoutHelper.dp(20), Gravity.CENTER))
        headerRow.addView(closeButton, LinearLayout.LayoutParams(LayoutHelper.dp(44), LayoutHelper.dp(44)))
        headerRow.addView(TextView(context).apply {
            text = context.getString(R.string.external_meeting_call)
            setTextColor(themeColors.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = LayoutHelper.dp(10)
            marginEnd = LayoutHelper.dp(10)
        })
        root.addView(headerRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val iconContainer = FrameLayout(context).apply {
            setPadding(0, LayoutHelper.dp(16), 0, LayoutHelper.dp(12))
        }
        val iconCircle = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(EXTERNAL_ICON_BG)
            }
        }
        iconCircle.addView(ImageView(context).apply {
            setImageDrawable(MezonIcon.channelVoice.getDrawable(context).apply {
                colorFilter = PorterDuffColorFilter(EXTERNAL_COLOR, PorterDuff.Mode.SRC_IN)
            })
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }, FrameLayout.LayoutParams(LayoutHelper.dp(30), LayoutHelper.dp(30), Gravity.CENTER))
        iconContainer.addView(iconCircle, FrameLayout.LayoutParams(LayoutHelper.dp(64), LayoutHelper.dp(64), Gravity.CENTER))
        root.addView(iconContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        root.addView(TextView(context).apply {
            text = meeting.title.ifBlank { displayLink() }
            setTextColor(themeColors.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = if (meeting.title.isBlank()) TextUtils.TruncateAt.MIDDLE else TextUtils.TruncateAt.END
        }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            leftMargin = LayoutHelper.dp(14)
            rightMargin = LayoutHelper.dp(14)
            bottomMargin = LayoutHelper.dp(6)
        })

        root.addView(TextView(context).apply {
            text = context.getString(R.string.external_meeting_link_prompt)
            setTextColor(themeColors.onSurfaceVariant)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            leftMargin = LayoutHelper.dp(14)
            rightMargin = LayoutHelper.dp(14)
            bottomMargin = LayoutHelper.dp(24)
        })

        root.addView(
            buildActionButton(
                context,
                context.getString(R.string.external_meeting_join_in_app),
                MezonIcon.channelVoice,
                JOIN_IN_APP_COLOR,
                0xFFFFFFFF.toInt(),
            ) {
                onJoinInApp?.invoke()
                dismissWithoutAnimation()
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50).apply {
                leftMargin = LayoutHelper.dp(6)
                rightMargin = LayoutHelper.dp(6)
            },
        )
        root.addView(
            buildActionButton(
                context,
                context.getString(R.string.external_meeting_open_in_browser),
                MezonIcon.linkIcon,
                themeColors.surfaceVariant,
                themeColors.onSurface,
            ) {
                onOpenInBrowser?.invoke()
                dismiss()
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50).apply {
                leftMargin = LayoutHelper.dp(6)
                rightMargin = LayoutHelper.dp(6)
                topMargin = LayoutHelper.dp(10)
            },
        )

        return root
    }

    private fun displayLink(): String {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        val host = uri?.host.orEmpty()
        val path = uri?.path.orEmpty()
        return (host + path).ifBlank { url }
    }

    private fun buildActionButton(
        context: Context,
        label: String,
        icon: MezonIcon,
        backgroundColor: Int,
        contentColor: Int,
        onClick: () -> Unit,
    ): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(LayoutHelper.dp(16), 0, LayoutHelper.dp(16), 0)
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(25).toFloat()
                setColor(backgroundColor)
            }
            isClickable = true
            isFocusable = true
            applyVoiceButtonPressFeedback()
            setOnClickListener { onClick() }
            addView(ImageView(context).apply {
                setImageDrawable(icon.getDrawable(context).apply {
                    colorFilter = PorterDuffColorFilter(contentColor, PorterDuff.Mode.SRC_IN)
                })
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }, LinearLayout.LayoutParams(LayoutHelper.dp(18), LayoutHelper.dp(18)))
            addView(TextView(context).apply {
                text = label
                setTextColor(contentColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = LayoutHelper.dp(8)
            })
        }
    }
}
