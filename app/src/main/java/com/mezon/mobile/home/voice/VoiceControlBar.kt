package com.mezon.mobile.home.voice

import com.mezon.mobile.ui.cells.withDefaultIconTint
import com.mezon.mobile.ui.cells.setIconColor
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.R
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.theme.ThemeMode

class VoiceControlBar(
    context: Context,
    private val themeColors: ThemeColors
) : FrameLayout(context) {

    companion object {
        private val BUTTON_SIZE = LayoutHelper.dp(50)
        private val BUTTON_GAP = LayoutHelper.dp(10)
        private val PILL_H_PADDING = LayoutHelper.dp(10)
        private val PILL_V_PADDING = LayoutHelper.dp(4)
        private val INNER_PADDING = LayoutHelper.dp(6)
        private val PILL_RADIUS = LayoutHelper.dp(80).toFloat()
        private val RAISE_HAND_ACTIVE = 0xFFEFBC39.toInt()
        private val SCREEN_SHARE_ACTIVE = 0xFF16A34A.toInt()
        private val LEAVE_ROOM_COLOR = 0xFFE32E2E.toInt()
        private val WEAK_DOT_SIZE = LayoutHelper.dp(14)
        private val WEAK_DOT_BORDER = LayoutHelper.dp(2)

        private val PTT_LAYOUT_H_PADDING = LayoutHelper.dp(14)
        private val PTT_BIG_HEIGHT = LayoutHelper.dp(168)
        private val PTT_BIG_HEIGHT_LAND = LayoutHelper.dp(76)
        private val PTT_BIG_ICON = LayoutHelper.dp(40)
        private val PTT_BIG_RADIUS = LayoutHelper.dp(30).toFloat()
        private val PTT_LABEL_GAP = LayoutHelper.dp(8)
        private val PTT_LABEL_SIZE = 16f
        private val PTT_COMPACT_HEIGHT = LayoutHelper.dp(54)
        private val PTT_COMPACT_ICON = LayoutHelper.dp(24)
        private val PTT_COMPACT_H_PADDING = LayoutHelper.dp(30)
        private val PTT_LABEL_GAP_H = LayoutHelper.dp(10)
        private val PTT_ROW_TOP_GAP = LayoutHelper.dp(12)
        private val PTT_BOTTOM_HEIGHT = LayoutHelper.dp(56)
        private val PTT_BOTTOM_ICON = LayoutHelper.dp(28)
        private val PTT_BOTTOM_RADIUS = LayoutHelper.dp(28).toFloat()
        private val PTT_BOTTOM_GAP = LayoutHelper.dp(10)
        private val PTT_END_WEIGHT = 1f
        private const val FIRST_PTT_PREPARATION_DELAY_MS = 1_000L
        private const val PTT_LOADING_THRESHOLD_MS = 1_000L
    }

    var onCameraToggle: ((enabled: Boolean) -> Unit)? = null
    var onMicToggle: ((enabled: Boolean) -> Unit)? = null
    var onMicPressStart: (() -> Unit)? = null
    var onMicPressEnd: (() -> Unit)? = null
    var onChatClick: (() -> Unit)? = null
    var onRaiseHandClick: (() -> Unit)? = null
    var onScreenShareClick: (() -> Unit)? = null
    var onEndCallClick: (() -> Unit)? = null

    private var cameraEnabled = false
    private var micEnabled = false
    private var pushToTalkMode = false
    private var pttCompact = false
    private var isGroupCall = false
    private var isExternalMeeting = false
    private var raiseHandActive = false
    private var screenShareEnabled = false

    private val row: LinearLayout
    private val cameraButton: VoiceStyleCircleButton
    private val micButton: VoiceStyleCircleButton
    private val micWeakDot: View
    private val chatButton: VoiceStyleCircleButton
    private val screenShareButton: VoiceStyleCircleButton
    private val endCallButton: VoiceStyleCircleButton

    private val pttLayout: LinearLayout
    private val pttMicPill: FrameLayout
    private val pttMicIcon: ImageView
    private val pttMicIconSlot: FrameLayout
    private val pttMicProgress: ProgressBar
    private val pttMicLabel: TextView
    private val pttMicContent: LinearLayout
    private val pttRaisePill: FrameLayout
    private val pttChatPill: FrameLayout
    private val pttChatIcon: ImageView
    private val pttBottomRow: LinearLayout

    private val controlIconTint: Int get() = themeColors.textStrong
    private val controlBorderColor: Int get() = ColorUtils.setAlphaComponent(themeColors.textDisabled, 0x99)

    private var microphoneAvailable = true
    private var holdTriggered = false
    private var pttGranted = false
    private var audioSending = false
    private var firstPttPreparationComplete = false
    private var firstPttPreparationScheduled = false
    private var delayedPttLoadingVisible = false
    private var delayedPttLoadingScheduled = false
    private var pttReadyShown = false
    private var pulseAnimator: ValueAnimator? = null
    private var hintToast: Toast? = null
    private val firstPttPreparationRunnable = Runnable {
        firstPttPreparationScheduled = false
        if (pushToTalkMode && microphoneAvailable && holdTriggered && pttGranted && audioSending) {
            firstPttPreparationComplete = true
        }
        updatePttFeedback()
    }
    private val delayedPttLoadingRunnable = Runnable {
        delayedPttLoadingScheduled = false
        if (pushToTalkMode && microphoneAvailable && holdTriggered && !(pttGranted && audioSending)) {
            delayedPttLoadingVisible = true
        }
        updatePttFeedback()
    }
    private val holdRunnable = Runnable {
        if (!microphoneAvailable || holdTriggered) return@Runnable
        holdTriggered = true
        pttGranted = false
        audioSending = false
        updatePttFeedback()
        onMicPressStart?.invoke()
    }

    init {
        clipChildren = false
        clipToPadding = false

        val pillBg = GradientDrawable().apply {
            cornerRadius = PILL_RADIUS
            setColor(themeColors.channelPanelBg)
        }

        row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = pillBg
            clipChildren = false
            clipToPadding = false
            setPadding(
                PILL_H_PADDING + INNER_PADDING,
                PILL_V_PADDING + INNER_PADDING,
                PILL_H_PADDING + INNER_PADDING,
                PILL_V_PADDING + INNER_PADDING
            )
        }

        val defaultTint = controlIconTint
        val btnBorder = controlBorderColor
        val iconSize = VoiceStyleCircleButton.iconSizePx(context, 24f)

        cameraButton = VoiceStyleCircleButton(context, R.drawable.mz_video_off_24, themeColors.tertiary, btnBorder, defaultTint, iconSize).apply {
            setOnClickListener {
                setCameraEnabled(!cameraEnabled)
                onCameraToggle?.invoke(cameraEnabled)
            }
        }
        addButton(row, cameraButton, false)

        micButton = VoiceStyleCircleButton(context, R.drawable.mz_mic_off_24, themeColors.tertiary, btnBorder, defaultTint, iconSize).apply {
            setOnClickListener {
                setMicEnabled(!micEnabled)
                onMicToggle?.invoke(micEnabled)
            }
        }
        addButton(row, micButton, true)
        micWeakDot = View(context).apply {
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(themeColors.connectingColor)
                setStroke(WEAK_DOT_BORDER, themeColors.channelPanelBg)
            }
        }
        micButton.addView(micWeakDot, LayoutParams(WEAK_DOT_SIZE, WEAK_DOT_SIZE, Gravity.TOP or Gravity.END))

        chatButton = VoiceStyleCircleButton(context, R.drawable.mz_chat_24, themeColors.tertiary, btnBorder, defaultTint, iconSize).apply {
            setOnClickListener { onChatClick?.invoke() }
        }
        addButton(row, chatButton, true)

        screenShareButton = VoiceStyleCircleButton(
            context,
            R.drawable.mz_screen_share_24,
            themeColors.tertiary,
            btnBorder,
            defaultTint,
            iconSize,
            useSemanticTint = true
        ).apply {
            setOnClickListener { onScreenShareClick?.invoke() }
        }
        addButton(row, screenShareButton, true)

        endCallButton = VoiceStyleCircleButton(
            context,
            R.drawable.mz_call_cancel_24,
            LEAVE_ROOM_COLOR,
            0,
            0xFFFFFFFF.toInt(),
            VoiceStyleCircleButton.endCallIconSizePx(context),
            fixedIconLoader = { iconContext, icon ->
                MezonIcon.loadDrawable(iconContext, icon, ThemeMode.DARK.iconTint)
            }
        ).apply {
            setOnClickListener { onEndCallClick?.invoke() }
        }
        addButton(row, endCallButton, true)

        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        pttMicIcon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setImageDrawable(tintedIcon(R.drawable.mz_mic_off_40, controlIconTint))
        }
        pttMicProgress = ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
            visibility = View.GONE
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            indeterminateDrawable.mutate().colorFilter = PorterDuffColorFilter(themeColors.connectingColor, PorterDuff.Mode.SRC_IN)
        }
        pttMicIconSlot = FrameLayout(context).apply {
            addView(pttMicIcon, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(pttMicProgress, LayoutParams(LayoutHelper.dp(28), LayoutHelper.dp(28), Gravity.CENTER))
        }
        pttMicLabel = TextView(context).apply {
            setText(R.string.voice_room_ptt_hold)
            setTextColor(controlIconTint)
            textSize = PTT_LABEL_SIZE
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
        }
        pttMicContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = false
            setPadding(LayoutHelper.dp(12), 0, LayoutHelper.dp(12), 0)
            addView(pttMicIconSlot, LinearLayout.LayoutParams(PTT_BIG_ICON, PTT_BIG_ICON))
            addView(pttMicLabel, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = PTT_LABEL_GAP })
        }
        pttMicPill = FrameLayout(context).apply {
            background = pttIdleBackground()
            isClickable = true
            addView(pttMicContent, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setOnTouchListener { v, event ->
                if (!microphoneAvailable) return@setOnTouchListener true
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        holdRunnable.run()
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.removeCallbacks(holdRunnable)
                        if (holdTriggered) {
                            cancelPttHold()
                        } else {
                            showHoldHint()
                        }
                        v.performClick()
                        true
                    }
                    else -> false
                }
            }
        }

        val endPill = makePttPill(
            MezonIcon.loadDrawable(context, R.drawable.mz_call_end_32, ThemeMode.DARK.iconTint),
            LEAVE_ROOM_COLOR
        )
        endPill.setOnClickListener { onEndCallClick?.invoke() }

        pttRaisePill = makePttPill(
            MezonIcon.loadDrawable(context, R.drawable.mz_hand_raise_32, themeColors.iconTint),
            themeColors.tertiary
        )
        pttRaisePill.setOnClickListener { onRaiseHandClick?.invoke() }

        pttChatIcon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(tintedIcon(R.drawable.mz_chat_24, controlIconTint))
        }
        val chatPill = FrameLayout(context).apply {
            background = roundedBg(themeColors.tertiary, PTT_BOTTOM_RADIUS)
            isClickable = true
            addView(pttChatIcon, LayoutParams(PTT_BOTTOM_ICON, PTT_BOTTOM_ICON, Gravity.CENTER))
            setOnClickListener { onChatClick?.invoke() }
        }
        pttChatPill = chatPill

        pttBottomRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addPillChild(this, endPill, false, PTT_END_WEIGHT)
            addPillChild(this, pttRaisePill, true)
            addPillChild(this, chatPill, true)
        }

        pttLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(PTT_LAYOUT_H_PADDING, 0, PTT_LAYOUT_H_PADDING, 0)
            addView(pttMicPill, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, pttBigHeight()))
            addView(pttBottomRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, PTT_BOTTOM_HEIGHT).apply {
                topMargin = PTT_ROW_TOP_GAP
            })
        }
        addView(pttLayout, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    fun applyTheme() {
        (row.background as? GradientDrawable)?.setColor(themeColors.channelPanelBg)
        for (button in listOf(cameraButton, micButton, chatButton, screenShareButton)) {
            button.updateBgColor(themeColors.tertiary)
            button.updateBorderColor(controlBorderColor)
            button.updateIconTint(controlIconTint)
        }
        (micWeakDot.background as? GradientDrawable)?.apply {
            setColor(themeColors.connectingColor)
            setStroke(WEAK_DOT_BORDER, themeColors.channelPanelBg)
        }
        pttChatPill.background = roundedBg(themeColors.tertiary, PTT_BOTTOM_RADIUS)
        pttChatIcon.setImageDrawable(tintedIcon(R.drawable.mz_chat_24, controlIconTint))
        setRaiseHandActive(raiseHandActive)
        setScreenShareEnabled(screenShareEnabled)
        updatePttFeedback()
    }

    private fun pttBigHeight(): Int =
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) PTT_BIG_HEIGHT_LAND else PTT_BIG_HEIGHT

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val lp = pttMicPill.layoutParams
        if (lp != null) {
            lp.height = if (pttCompact) PTT_COMPACT_HEIGHT else pttBigHeight()
            pttMicPill.layoutParams = lp
        }
        updatePttFeedback()
    }

    private fun addButton(row: LinearLayout, button: VoiceStyleCircleButton, addGap: Boolean) {
        val lp = LinearLayout.LayoutParams(BUTTON_SIZE, BUTTON_SIZE)
        if (addGap) lp.marginStart = BUTTON_GAP
        row.addView(button, lp)
    }

    private fun addPillChild(row: LinearLayout, pill: FrameLayout, addGap: Boolean, weight: Float = 1f) {
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight)
        if (addGap) lp.marginStart = PTT_BOTTOM_GAP
        row.addView(pill, lp)
    }

    private fun makePttPill(icon: android.graphics.drawable.Drawable, bgColor: Int): FrameLayout {
        val iconView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setImageDrawable(icon)
        }
        val pill = FrameLayout(context).apply {
            background = roundedBg(bgColor, PTT_BOTTOM_RADIUS)
            isClickable = true
            addView(iconView, LayoutParams(PTT_BOTTOM_ICON, PTT_BOTTOM_ICON, Gravity.CENTER))
        }
        return pill
    }

    private fun tintedIcon(icon: Int, tint: Int) =
        MezonIcon.loadDrawable(context, icon).withDefaultIconTint(context, icon, ThemeColors.defaultIconTint).mutate().apply {
            colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
        }

    private fun roundedBg(color: Int, radius: Float) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(color)
    }

    private fun pttIdleBackground(): GradientDrawable {
        val lightTheme = themeColors.resolvedMode.isLight
        val color = if (lightTheme) themeColors.channelPanelBg else themeColors.tertiary
        return roundedBg(color, PTT_BIG_RADIUS).apply {
            if (lightTheme) setStroke(LayoutHelper.dp(1), themeColors.borderDim)
        }
    }

    private fun updatePttFeedback() {
        val holding = pushToTalkMode && microphoneAvailable && holdTriggered
        val locallyReady = holding && pttGranted && audioSending
        // First PTT gets a UI buffer after local capture/RTP starts; this is not a receiver acknowledgement.
        if (locallyReady && !firstPttPreparationComplete) {
            if (!firstPttPreparationScheduled) {
                firstPttPreparationScheduled = true
                postDelayed(firstPttPreparationRunnable, FIRST_PTT_PREPARATION_DELAY_MS)
            }
        } else if (!locallyReady) {
            removeCallbacks(firstPttPreparationRunnable)
            firstPttPreparationScheduled = false
        }
        val ready = locallyReady && firstPttPreparationComplete
        val waiting = holding && !ready
        if (waiting && firstPttPreparationComplete) {
            if (!delayedPttLoadingVisible && !delayedPttLoadingScheduled) {
                delayedPttLoadingScheduled = true
                postDelayed(delayedPttLoadingRunnable, PTT_LOADING_THRESHOLD_MS)
            }
        } else {
            removeCallbacks(delayedPttLoadingRunnable)
            delayedPttLoadingScheduled = false
            delayedPttLoadingVisible = false
        }
        val showLoading = waiting && (!firstPttPreparationComplete || delayedPttLoadingVisible)
        if (ready) {
            if (!pttReadyShown) vibrateLight()
            if (pulseAnimator?.isRunning != true) startRecordingPulse()
        } else stopRecordingPulse()
        pttReadyShown = ready
        val tint = when {
            ready -> 0xFFFFFFFF.toInt()
            showLoading -> themeColors.connectingColor
            else -> controlIconTint
        }
        pttMicPill.background = (if (ready) {
            roundedBg(themeColors.blurple, PTT_BIG_RADIUS)
        } else {
            pttIdleBackground()
        }).apply {
            if (showLoading) setStroke(LayoutHelper.dp(1), tint)
        }
        pttMicIcon.visibility = if (showLoading) View.INVISIBLE else View.VISIBLE
        pttMicProgress.visibility = if (showLoading) View.VISIBLE else View.GONE
        val micIcon = when {
            ready && pttCompact -> R.drawable.mz_mic_24
            ready -> R.drawable.mz_mic_40
            pttCompact -> R.drawable.mz_mic_off_24
            else -> R.drawable.mz_mic_off_40
        }
        pttMicIcon.setImageDrawable(
            if (ready) MezonIcon.loadDrawable(context, micIcon, ThemeMode.DARK.iconTint)
            else MezonIcon.loadDrawable(context, micIcon, themeColors.iconTint)
        )
        pttMicLabel.setText(when {
            ready -> R.string.voice_room_ptt_ready
            showLoading && pttGranted -> R.string.voice_room_ptt_preparing
            showLoading -> R.string.voice_room_ptt_waiting
            else -> R.string.voice_room_ptt_hold
        })
        pttMicLabel.setTextColor(tint)
        pttMicPill.contentDescription = pttMicLabel.text
    }

    private fun cancelPttHold() {
        val wasHolding = holdTriggered
        holdTriggered = false
        pttGranted = false
        audioSending = false
        stopRecordingPulse()
        updatePttFeedback()
        if (wasHolding) onMicPressEnd?.invoke()
    }

    private fun startRecordingPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = ValueAnimator.ofFloat(1f, 1.18f).apply {
            duration = 520L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener {
                val s = it.animatedValue as Float
                pttMicIcon.scaleX = s
                pttMicIcon.scaleY = s
            }
            start()
        }
    }

    private fun stopRecordingPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        pttMicIcon.scaleX = 1f
        pttMicIcon.scaleY = 1f
    }

    private fun showHoldHint() {
        hintToast?.cancel()
        hintToast = Toast.makeText(context, R.string.voice_room_ptt_hold_hint, Toast.LENGTH_SHORT).also { it.show() }
    }

    private fun vibrateLight() {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (!vibrator.hasVibrator()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(35)
            }
        }
    }

    fun setPushToTalkMode(enabled: Boolean) {
        if (pushToTalkMode != enabled) cancelPttHold()
        pushToTalkMode = enabled
        row.visibility = if (enabled) View.GONE else View.VISIBLE
        pttLayout.visibility = if (enabled) View.VISIBLE else View.GONE
        updatePttFeedback()
    }

    fun isPttMode(): Boolean = pushToTalkMode

    fun isPttHeld(): Boolean = holdTriggered

    fun setAudioSending(sending: Boolean) {
        audioSending = sending && (!pushToTalkMode || holdTriggered)
        updatePttFeedback()
    }

    fun setPttActive(active: Boolean) {
        pttGranted = active && holdTriggered
        if (!pttGranted) {
            audioSending = false
            stopRecordingPulse()
        }
        updatePttFeedback()
    }

    fun setPttCompact(compact: Boolean) {
        pttCompact = compact
        pttBottomRow.visibility = if (compact) View.GONE else View.VISIBLE
        (pttMicPill.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.height = if (compact) PTT_COMPACT_HEIGHT else pttBigHeight()
            it.width = if (compact) LinearLayout.LayoutParams.WRAP_CONTENT else LinearLayout.LayoutParams.MATCH_PARENT
            it.gravity = Gravity.CENTER_HORIZONTAL
            pttMicPill.layoutParams = it
        }
        if (compact) {
            pttMicPill.setPadding(PTT_COMPACT_H_PADDING, 0, PTT_COMPACT_H_PADDING, 0)
        } else {
            pttMicPill.setPadding(0, 0, 0, 0)
        }
        pttMicContent.orientation = if (compact) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        (pttMicContent.layoutParams as? LayoutParams)?.let {
            it.width = if (compact) LayoutParams.WRAP_CONTENT else LayoutParams.MATCH_PARENT
            pttMicContent.layoutParams = it
        }
        (pttMicIconSlot.layoutParams as? LinearLayout.LayoutParams)?.let {
            val size = if (compact) PTT_COMPACT_ICON else PTT_BIG_ICON
            it.width = size
            it.height = size
            pttMicIconSlot.layoutParams = it
        }
        (pttMicProgress.layoutParams as? LayoutParams)?.let {
            val size = LayoutHelper.dp(if (compact) 20 else 28)
            it.width = size
            it.height = size
            pttMicProgress.layoutParams = it
        }
        (pttMicLabel.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.topMargin = if (compact) 0 else PTT_LABEL_GAP
            it.marginStart = if (compact) PTT_LABEL_GAP_H else 0
            pttMicLabel.layoutParams = it
        }
        updatePttFeedback()
    }

    fun pttContentHeightDp(): Float {
        if (!pushToTalkMode) return 0f
        val px = if (pttCompact) PTT_COMPACT_HEIGHT else pttBigHeight() + PTT_ROW_TOP_GAP + PTT_BOTTOM_HEIGHT
        return px / resources.displayMetrics.density
    }

    fun setGroupCallMode(isGroupCall: Boolean) {
        this.isGroupCall = isGroupCall
        updateSecondaryButtonsVisibility()
    }

    fun setExternalMeetingMode(external: Boolean) {
        isExternalMeeting = external
        pttRaisePill.visibility = if (external) View.GONE else View.VISIBLE
        pttChatPill.visibility = if (external) View.GONE else View.VISIBLE
        updateSecondaryButtonsVisibility()
    }

    private fun updateSecondaryButtonsVisibility() {
        val vis = if (!isGroupCall && !pushToTalkMode) View.VISIBLE else View.GONE
        chatButton.visibility = if (isExternalMeeting) View.GONE else vis
        screenShareButton.visibility = vis
    }

    fun setMicrophoneAvailable(available: Boolean) {
        microphoneAvailable = available
        val controlAlpha = if (available) 1f else 0.4f
        micButton.isEnabled = available
        micButton.alpha = controlAlpha
        pttMicPill.isEnabled = available
        pttMicPill.alpha = controlAlpha
        if (!available) {
            pttMicPill.removeCallbacks(holdRunnable)
            firstPttPreparationComplete = false
            cancelPttHold()
        }
        updatePttFeedback()
    }

    override fun onDetachedFromWindow() {
        cancelPttHold()
        hintToast?.cancel()
        super.onDetachedFromWindow()
    }

    fun setNetworkWeak(weak: Boolean) {
        micWeakDot.visibility = if (weak) View.VISIBLE else View.GONE
    }

    fun micAnchorView(): View = if (pushToTalkMode) pttMicPill else micButton

    fun setMicEnabled(enabled: Boolean) {
        if (micEnabled == enabled) return
        micEnabled = enabled
        micButton.updateIcon(if (enabled) R.drawable.mz_mic_24 else R.drawable.mz_mic_off_24)
    }

    fun setCameraEnabled(enabled: Boolean) {
        if (cameraEnabled == enabled) return
        cameraEnabled = enabled
        cameraButton.updateIcon(if (enabled) R.drawable.mz_video_24 else R.drawable.mz_video_off_24)
    }

    fun setRaiseHandActive(active: Boolean) {
        raiseHandActive = active
        val background = if (active) {
            ColorUtils.setAlphaComponent(RAISE_HAND_ACTIVE, 0x38)
        } else {
            themeColors.tertiary
        }
        pttRaisePill.background = roundedBg(background, PTT_BOTTOM_RADIUS)
        pttRaisePill.isSelected = active
        val iconView = pttRaisePill.getChildAt(0) as ImageView
        val tint = if (active) RAISE_HAND_ACTIVE else controlIconTint
        iconView.drawable?.let { it.setIconColor(tint) }
    }

    fun setScreenShareEnabled(enabled: Boolean) {
        screenShareEnabled = enabled
        val background = if (enabled) {
            ColorUtils.setAlphaComponent(SCREEN_SHARE_ACTIVE, 0x38)
        } else {
            themeColors.tertiary
        }
        screenShareButton.updateBgColor(background)
        screenShareButton.updateIconTint(if (enabled) SCREEN_SHARE_ACTIVE else controlIconTint)
    }
}
