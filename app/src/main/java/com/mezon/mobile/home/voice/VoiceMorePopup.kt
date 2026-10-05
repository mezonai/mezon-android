package com.mezon.mobile.home.voice

import android.app.Activity
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.TextView
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.home.voice.sfu.NoiseSuppressionState

class VoiceMorePopup(private val themeColors: ThemeColors) {
    private var popupWindow: PopupWindow? = null
    private var noiseStatus: TextView? = null
    private var noiseProgress: ProgressBar? = null
    private var noiseAction: View? = null

    companion object {
        private const val RAISE_HAND_ACTIVE = 0xFFEFBC39.toInt()
    }

    fun show(
        anchor: View,
        parentActivity: Activity,
        showAudienceActions: Boolean,
        raiseHandActive: Boolean,
        onRaiseHandClick: () -> Unit,
        onMessageClick: () -> Unit,
        onEmojiClick: () -> Unit,
        onSoundClick: () -> Unit,
        noiseState: NoiseSuppressionState,
        noiseCaptureConfirmed: Boolean,
        onNoiseClick: () -> Unit,
    ) {
        dismiss()

        val container = LinearLayout(anchor.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(16).toFloat()
                setColor(themeColors.secondaryLight)
                setStroke(LayoutHelper.dp(1), themeColors.textDisabled)
            }
            clipToOutline = true
        }

        val emojiButton = createMoreActionIconButton(anchor.context, MezonIcon.faceIcon) {
            dismiss()
            onEmojiClick()
        }
        val soundButton = createMoreActionIconButton(anchor.context, MezonIcon.activityIcon) {
            dismiss()
            onSoundClick()
        }

        val buttons = if (showAudienceActions) {
            val raiseHandButton = createMoreActionIconButton(
                anchor.context,
                MezonIcon.raiseHandIcon,
                if (raiseHandActive) RAISE_HAND_ACTIVE else null
            ) {
                dismiss()
                onRaiseHandClick()
            }
            val messageButton = createMoreActionIconButton(anchor.context, MezonIcon.notificationTabMessages, applyTint = false) {
                dismiss()
                onMessageClick()
            }
            listOf(raiseHandButton, messageButton, emojiButton, soundButton)
        } else {
            listOf(emojiButton, soundButton)
        }
        val width = minOf(LayoutHelper.dp(216), parentActivity.resources.displayMetrics.widthPixels - LayoutHelper.dp(16))
        container.setPadding(LayoutHelper.dp(8), LayoutHelper.dp(8), LayoutHelper.dp(8), LayoutHelper.dp(8))
        buttons.chunked(2).forEach { pair ->
            val row = LinearLayout(anchor.context).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { index, button ->
                row.addView(button, LinearLayout.LayoutParams(0, LayoutHelper.dp(40), 1f).apply {
                    if (index > 0) leftMargin = LayoutHelper.dp(8)
                })
            }
            container.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (container.childCount > 0) topMargin = LayoutHelper.dp(8)
            })
        }

        val noiseRow = LinearLayout(anchor.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(10), 0, LayoutHelper.dp(10), 0)
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(11).toFloat()
                setColor(themeColors.surfaceVariant)
            }
            isClickable = true
            isFocusable = true
            contentDescription = "Noise suppression"
            setOnClickListener { onNoiseClick() }
            applyVoiceButtonPressFeedback()
        }
        noiseAction = noiseRow
        noiseRow.addView(ImageView(anchor.context).apply {
            setImageDrawable(MezonIcon.activityIcon.getDrawable(anchor.context).apply {
                colorFilter = PorterDuffColorFilter(themeColors.onSurface, PorterDuff.Mode.SRC_IN)
            })
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }, LinearLayout.LayoutParams(LayoutHelper.dp(16), LayoutHelper.dp(16)))
        noiseRow.addView(TextView(anchor.context).apply {
            text = "Noise suppression"
            textSize = 12f
            setTextColor(themeColors.onSurface)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = LayoutHelper.dp(7)
        })
        noiseStatus = TextView(anchor.context).apply {
            textSize = 11f
            setTextColor(themeColors.textDisabled)
        }.also { noiseRow.addView(it) }
        noiseProgress = ProgressBar(anchor.context, null, android.R.attr.progressBarStyleSmall).apply {
            visibility = View.GONE
        }.also { noiseRow.addView(it, LinearLayout.LayoutParams(LayoutHelper.dp(16), LayoutHelper.dp(16))) }
        container.addView(noiseRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, LayoutHelper.dp(44)).apply {
            topMargin = LayoutHelper.dp(8)
        })
        updateNoiseState(noiseState, noiseCaptureConfirmed, showApplied = false)

        val decor = parentActivity.window.decorView
        popupWindow = PopupWindow(
            container,
            width,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(16).toFloat()
                setColor(themeColors.secondaryLight)
            })
            elevation = LayoutHelper.dp(16).toFloat()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                isClippingEnabled = false
            }
        }

        anchor.post {
            container.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val popupWidth = container.measuredWidth
            val popupHeight = container.measuredHeight
            val anchorLocation = IntArray(2)
            val decorLocation = IntArray(2)
            anchor.getLocationOnScreen(anchorLocation)
            decor.getLocationOnScreen(decorLocation)

            val anchorX = anchorLocation[0] - decorLocation[0]
            val anchorY = anchorLocation[1] - decorLocation[1]
            val gap = LayoutHelper.dp(10)
            var x = anchorX + (anchor.width - popupWidth) / 2 + LayoutHelper.dp(18)
            val minY = AndroidUtilities.statusBarHeight + LayoutHelper.dp(4)
            val aboveY = anchorY - popupHeight - gap
            val y = if (aboveY >= minY) {
                aboveY
            } else {
                anchorY + anchor.height + gap
            }
            val margin = LayoutHelper.dp(8)
            val displayWidth = parentActivity.resources.displayMetrics.widthPixels
            if (x + popupWidth > displayWidth - margin) {
                x = displayWidth - popupWidth - margin
            }
            if (x < margin) {
                x = margin
            }
            popupWindow?.showAtLocation(decor, Gravity.NO_GRAVITY, x, y)
        }
    }

    fun dismiss() {
        popupWindow?.dismiss()
        popupWindow = null
        noiseStatus = null
        noiseProgress = null
        noiseAction = null
    }

    fun updateNoiseState(state: NoiseSuppressionState, captureConfirmed: Boolean, showApplied: Boolean = true) {
        val status = noiseStatus ?: return
        noiseAction?.isEnabled = state != NoiseSuppressionState.APPLYING
        noiseProgress?.visibility = if (state == NoiseSuppressionState.APPLYING) View.VISIBLE else View.GONE
        status.text = when (state) {
            NoiseSuppressionState.OFF -> "Off"
            NoiseSuppressionState.APPLYING -> ""
            NoiseSuppressionState.ON -> if (captureConfirmed && showApplied) "Applied" else if (captureConfirmed) "On" else "Ready"
            NoiseSuppressionState.ERROR -> "Error"
        }
        noiseAction?.contentDescription = "Noise suppression, ${status.text.ifEmpty { "Applying" }}"
        if (state == NoiseSuppressionState.ON && captureConfirmed && showApplied) {
            status.postDelayed({
                if (noiseStatus === status && status.text == "Applied") status.text = "On"
            }, 1_300L)
        }
    }

    private fun createMoreActionIconButton(
        context: android.content.Context,
        icon: MezonIcon,
        tint: Int? = null,
        applyTint: Boolean = true,
        onClick: () -> Unit
    ): FrameLayout {
        return FrameLayout(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(11).toFloat()
                setColor(themeColors.surfaceVariant)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(ImageView(context).apply {
                setImageDrawable(icon.getDrawable(context).apply {
                    if (applyTint) {
                        colorFilter = PorterDuffColorFilter(tint ?: themeColors.onSurface, PorterDuff.Mode.SRC_IN)
                    }
                })
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }, FrameLayout.LayoutParams(LayoutHelper.dp(20), LayoutHelper.dp(20), Gravity.CENTER))
            applyVoiceButtonPressFeedback()
        }
    }
}
