package com.mezon.mobile.home.voice

import android.app.Activity
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.MezonIcon

class VoiceMorePopup(private val themeColors: ThemeColors) {
    private var popupWindow: PopupWindow? = null

    fun show(
        anchor: View,
        parentActivity: Activity,
        onEmojiClick: () -> Unit,
        onSoundClick: () -> Unit
    ) {
        dismiss()

        val container = LinearLayout(anchor.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, 0)
        }

        val emojiButton = createMoreActionIconButton(anchor.context, MezonIcon.faceIcon) {
            dismiss()
            onEmojiClick()
        }
        val soundButton = createMoreActionIconButton(anchor.context, MezonIcon.activityIcon) {
            dismiss()
            onSoundClick()
        }

        container.addView(emojiButton, LinearLayout.LayoutParams(LayoutHelper.dp(40), LayoutHelper.dp(40)))
        container.addView(soundButton, LinearLayout.LayoutParams(LayoutHelper.dp(40), LayoutHelper.dp(40)).apply {
            topMargin = LayoutHelper.dp(8)
        })

        val decor = parentActivity.window.decorView
        popupWindow = PopupWindow(
            container,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(0))
            elevation = LayoutHelper.dp(8).toFloat()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                isClippingEnabled = false
            }
        }

        anchor.post {
            container.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
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
    }

    private fun createMoreActionIconButton(context: android.content.Context, icon: MezonIcon, onClick: () -> Unit): FrameLayout {
        return FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(themeColors.channelPanelBg)
                setStroke(LayoutHelper.dp(1), themeColors.textDisabled)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(ImageView(context).apply {
                setImageDrawable(icon.getDrawable(context).apply {
                    colorFilter = PorterDuffColorFilter(themeColors.onSurface, PorterDuff.Mode.SRC_IN)
                })
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }, FrameLayout.LayoutParams(LayoutHelper.dp(20), LayoutHelper.dp(20), Gravity.CENTER))
            applyVoiceButtonPressFeedback()
        }
    }
}
