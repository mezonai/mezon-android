package com.mezon.mobile.home.voice

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import com.mezon.mobile.R
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.MezonIcon

class VoiceHeaderView(
    context: Context,
    private val themeColors: ThemeColors
) : FrameLayout(context) {

    companion object {
        private val BUTTON_SIZE = LayoutHelper.dp(40)
        private val BUTTON_RADIUS = LayoutHelper.dp(40).toFloat()
        private val ICON_SIZE = LayoutHelper.dp(20)
        private val LEFT_GAP = LayoutHelper.dp(12)
        private val RIGHT_GAP = LayoutHelper.dp(10)
        private val H_PADDING = LayoutHelper.dp(10)
    }

    var onMinimizeClick: (() -> Unit)? = null
    var onAgentClick: (() -> Unit)? = null
    var onSwitchCameraClick: (() -> Unit)? = null
    var onAudioOutputClick: ((View) -> Unit)? = null
    var onMoreClick: ((View) -> Unit)? = null

    private val connectionStatusText: TextView
    private val connectionStatusRow: LinearLayout
    private val channelNameText: TextView
    private val agentBtn: FrameLayout
    private val agentIconView: ImageView
    private val agentProgress: ProgressBar
    private val switchCameraBtn: FrameLayout
    private val minimizeBtn: FrameLayout
    private val moreBtn: FrameLayout
    private val audioOutputBtn: FrameLayout
    private var agentBtnBg: GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(themeColors.channelPanelBg)
        setStroke(LayoutHelper.dp(1), themeColors.textDisabled)
    }
    private var channelName: String = ""
    private var reconnecting: Boolean = false
    private var agentActive: Boolean = false

    // Connection state must never resize the header or move the participant grid.
    val preferredHeightDp: Float = 56f

    init {
        setPadding(H_PADDING, 0, H_PADDING, 0)

        val mainRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        addView(mainRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val leftContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        minimizeBtn = createCircleButton(context, MezonIcon.chevronDownSmallIcon) {
            onMinimizeClick?.invoke()
        }
        leftContainer.addView(minimizeBtn, LinearLayout.LayoutParams(BUTTON_SIZE, BUTTON_SIZE))

        channelNameText = TextView(context).apply {
            setTextColor(themeColors.colorText)
            textSize = 20f
            typeface = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Typeface.create(Typeface.DEFAULT, 600, false)
            } else {
                Typeface.DEFAULT_BOLD
            }
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val titleContainer = FrameLayout(context)
        titleContainer.addView(channelNameText, LayoutParams(LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))

        connectionStatusRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.INVISIBLE
            alpha = 0f
        }
        val connectionProgress = ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(themeColors.blurple)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        connectionStatusRow.addView(connectionProgress, LinearLayout.LayoutParams(
            LayoutHelper.dp(10), LayoutHelper.dp(10)).apply { marginEnd = LayoutHelper.dp(4) })
        connectionStatusText = TextView(context).apply {
            text = context.getString(R.string.connection_connecting)
            textSize = 12f
            setTextColor(themeColors.colorText)
            alpha = 0.7f
            includeFontPadding = false
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        connectionStatusRow.addView(connectionStatusText,
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        titleContainer.addView(connectionStatusRow, LayoutParams(LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = LayoutHelper.dp(6) })
        leftContainer.addView(titleContainer, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
            marginStart = LEFT_GAP
        })

        // Measure only visible action buttons; the room name takes the remaining width.
        mainRow.addView(leftContainer, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
            marginEnd = RIGHT_GAP
        })

        val rightContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        agentBtn = FrameLayout(context).apply {
            background = agentBtnBg
            isClickable = true
            isFocusable = true
            setOnClickListener { onAgentClick?.invoke() }
            applyVoiceButtonPressFeedback()
        }
        agentIconView = ImageView(context).apply {
            setImageDrawable(MezonIcon.agentIcon.getDrawable(context).apply {
                colorFilter = PorterDuffColorFilter(themeColors.onSurface, PorterDuff.Mode.SRC_IN)
            })
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        agentBtn.addView(agentIconView, LayoutParams(ICON_SIZE, ICON_SIZE, Gravity.CENTER))
        val progressSize = LayoutHelper.dp(22)
        agentProgress = ProgressBar(context).apply {
            visibility = View.GONE
            isIndeterminate = true
        }
        agentBtn.addView(agentProgress, LayoutParams(progressSize, progressSize, Gravity.CENTER))
        rightContainer.addView(agentBtn, LinearLayout.LayoutParams(BUTTON_SIZE, BUTTON_SIZE))

        switchCameraBtn = createCircleButton(context, MezonIcon.cameraFront) {
            onSwitchCameraClick?.invoke()
        }
        rightContainer.addView(switchCameraBtn, LinearLayout.LayoutParams(BUTTON_SIZE, BUTTON_SIZE).apply {
            marginStart = RIGHT_GAP
        })

        audioOutputBtn = createCircleButton(context, MezonIcon.channelVoice) { v ->
            onAudioOutputClick?.invoke(v)
        }
        val audioLP = LinearLayout.LayoutParams(BUTTON_SIZE, BUTTON_SIZE).apply {
            marginStart = RIGHT_GAP
        }
        rightContainer.addView(audioOutputBtn, audioLP)

        moreBtn = createCircleButton(context, MezonIcon.moreVerticalIcon) { v ->
            onMoreClick?.invoke(v)
        }
        val moreLP = LinearLayout.LayoutParams(BUTTON_SIZE, BUTTON_SIZE).apply {
            marginStart = RIGHT_GAP
        }
        rightContainer.addView(moreBtn, moreLP)

        mainRow.addView(rightContainer, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun setChannelName(name: String) {
        if (channelName == name) return
        channelName = name
        channelNameText.text = name
    }

    fun setReconnecting(value: Boolean) {
        if (reconnecting == value) return
        reconnecting = value
        connectionStatusRow.animate().cancel()
        channelNameText.animate().cancel()
        val titleOffset = if (value) -LayoutHelper.dp(8).toFloat() else 0f
        if (!isLaidOut || !isAttachedToWindow) {
            connectionStatusRow.visibility = if (value) View.VISIBLE else View.INVISIBLE
            connectionStatusRow.alpha = if (value) 1f else 0f
            channelNameText.translationY = titleOffset
            return
        }
        connectionStatusRow.visibility = View.VISIBLE
        connectionStatusRow.animate().alpha(if (value) 1f else 0f).setDuration(160L)
            .withEndAction {
                if (!reconnecting) connectionStatusRow.visibility = View.INVISIBLE
            }.start()
        channelNameText.animate().translationY(titleOffset).setDuration(160L).start()
    }

    override fun onDetachedFromWindow() {
        connectionStatusRow.animate().cancel()
        channelNameText.animate().cancel()
        connectionStatusRow.visibility = if (reconnecting) View.VISIBLE else View.INVISIBLE
        connectionStatusRow.alpha = if (reconnecting) 1f else 0f
        channelNameText.translationY = if (reconnecting) -LayoutHelper.dp(8).toFloat() else 0f
        super.onDetachedFromWindow()
    }

    fun setSwitchCameraVisible(visible: Boolean) {
        switchCameraBtn.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun setAgentVisible(visible: Boolean) {
        agentBtn.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun setAgentActive(active: Boolean) {
        agentActive = active
        agentBtnBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (active) themeColors.blurple else themeColors.channelPanelBg)
            setStroke(
                LayoutHelper.dp(1),
                if (active) themeColors.blurple else themeColors.textDisabled
            )
        }
        agentBtn.background = agentBtnBg
        agentBtn.applyVoiceButtonPressFeedback()
        applyAgentIconTint()
    }

    fun setAgentLoading(loading: Boolean) {
        agentBtn.isClickable = !loading
        agentBtn.isEnabled = !loading
        agentIconView.visibility = if (loading) View.GONE else View.VISIBLE
        agentProgress.visibility = if (loading) View.VISIBLE else View.GONE
        if (!loading) {
            applyAgentIconTint()
        }
    }

    fun setMinimizeVisible(visible: Boolean) {
        minimizeBtn.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun setMoreVisible(visible: Boolean) {
        moreBtn.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun setAudioOutputIcon(icon: MezonIcon) {
        val iconView = audioOutputBtn.getChildAt(0) as? ImageView ?: return
        iconView.setImageDrawable(icon.getDrawable(context).apply {
            colorFilter = PorterDuffColorFilter(themeColors.onSurface, PorterDuff.Mode.SRC_IN)
        })
    }

    private fun applyAgentIconTint() {
        val tint = if (agentActive) themeColors.onPrimary else themeColors.onSurface
        agentIconView.drawable?.mutate()?.colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
    }

    private fun createCircleButton(context: Context, icon: MezonIcon, onClick: (View) -> Unit): FrameLayout {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(themeColors.channelPanelBg)
            setStroke(LayoutHelper.dp(1), themeColors.textDisabled)
        }
        return FrameLayout(context).apply {
            background = bg
            isClickable = true
            isFocusable = true
            setOnClickListener(onClick)
            applyVoiceButtonPressFeedback()

            val iconView = ImageView(context).apply {
                setImageDrawable(icon.getDrawable(context).apply {
                    colorFilter = PorterDuffColorFilter(themeColors.onSurface, PorterDuff.Mode.SRC_IN)
                })
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
            addView(iconView, LayoutParams(ICON_SIZE, ICON_SIZE, Gravity.CENTER))
        }
    }

}
