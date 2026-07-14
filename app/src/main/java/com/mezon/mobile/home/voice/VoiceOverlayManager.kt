package com.mezon.mobile.home.voice

import android.animation.ValueAnimator
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import io.livekit.android.room.Room
import io.livekit.android.room.track.VideoTrack

class VoiceOverlayManager(
    private val rootContainer: ViewGroup,
    private val themeColors: ThemeColors
) {

    companion object {
        val MINI_W = LayoutHelper.dp(200)
        val MINI_H = LayoutHelper.dp(150)
        private val MINI_MARGIN = LayoutHelper.dp(12)
        private const val ANIM_DURATION = 300L
    }

    enum class State { HIDDEN, EXPANDED, MINIMIZED }

    var state = State.HIDDEN
        private set

    private val overlayContainer = object : FrameLayout(rootContainer.context) {
        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (state == State.MINIMIZED) return false
            return super.onInterceptTouchEvent(ev)
        }
    }
    private var fullScreenView: View? = null
    private var miniOverlay: VoiceOverlayView? = null
    private var currentAnimator: ValueAnimator? = null

    var onExpandRequest: (() -> Unit)? = null

    init {
        overlayContainer.visibility = View.GONE
        rootContainer.addView(overlayContainer, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
    }

    fun showExpanded(contentView: View) {
        currentAnimator?.cancel()
        fullScreenView = contentView
        overlayContainer.removeAllViews()
        (contentView.parent as? ViewGroup)?.removeView(contentView)
        overlayContainer.addView(contentView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        overlayContainer.visibility = View.VISIBLE
        overlayContainer.alpha = 1f
        state = State.EXPANDED
    }

    fun minimize(
        room: Room?,
        videoTrack: VideoTrack?,
        name: String,
        username: String,
        avatarUrl: String?,
        isMuted: Boolean,
        userId: Long
    ) {
        if (state != State.EXPANDED) return
        currentAnimator?.cancel()

        val full = fullScreenView ?: return

        val overlay = miniOverlay ?: VoiceOverlayView(rootContainer.context, themeColors).also {
            it.onTapExpand = { onExpandRequest?.invoke() }
            miniOverlay = it
        }
        overlay.setContent(room, videoTrack, name, username, avatarUrl, isMuted, userId)

        val anim = ValueAnimator.ofFloat(1f, 0f)
        anim.duration = ANIM_DURATION
        anim.interpolator = DecelerateInterpolator()
        anim.addUpdateListener { va ->
            val f = va.animatedValue as Float
            full.alpha = f
        }
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                overlayContainer.removeAllViews()
                val lp = FrameLayout.LayoutParams(MINI_W, MINI_H, Gravity.TOP or Gravity.START)
                lp.topMargin = MINI_MARGIN
                lp.leftMargin = MINI_MARGIN
                overlayContainer.addView(overlay, lp)
                overlayContainer.isClickable = false
                overlayContainer.isFocusable = false
                state = State.MINIMIZED
            }
        })
        currentAnimator = anim
        anim.start()
    }

    fun minimizeStream(channelLabel: String, channelAvatarUrl: String?) {
        if (state != State.EXPANDED) return
        currentAnimator?.cancel()

        val full = fullScreenView ?: return

        val overlay = miniOverlay ?: VoiceOverlayView(rootContainer.context, themeColors).also {
            it.onTapExpand = { onExpandRequest?.invoke() }
            miniOverlay = it
        }
        overlay.setStreamContent(channelLabel, channelAvatarUrl)

        val anim = ValueAnimator.ofFloat(1f, 0f)
        anim.duration = ANIM_DURATION
        anim.interpolator = DecelerateInterpolator()
        anim.addUpdateListener { va ->
            val f = va.animatedValue as Float
            full.alpha = f
        }
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                overlayContainer.removeAllViews()
                val lp = FrameLayout.LayoutParams(MINI_W, MINI_H, Gravity.TOP or Gravity.START)
                lp.topMargin = MINI_MARGIN
                lp.leftMargin = MINI_MARGIN
                overlayContainer.addView(overlay, lp)
                overlayContainer.isClickable = false
                overlayContainer.isFocusable = false
                state = State.MINIMIZED
            }
        })
        currentAnimator = anim
        anim.start()
    }

    fun updateMiniStream(channelLabel: String, channelAvatarUrl: String?) {
        if (state != State.MINIMIZED) return
        miniOverlay?.setStreamContent(channelLabel, channelAvatarUrl)
    }

    fun expand() {
        if (state != State.MINIMIZED) return
        currentAnimator?.cancel()

        val full = fullScreenView ?: return
        miniOverlay?.releaseRenderer()

        overlayContainer.removeAllViews()
        overlayContainer.addView(full, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        overlayContainer.isClickable = true
        overlayContainer.isFocusable = true

        full.alpha = 0f
        val anim = ValueAnimator.ofFloat(0f, 1f)
        anim.duration = ANIM_DURATION
        anim.interpolator = DecelerateInterpolator()
        anim.addUpdateListener { va ->
            full.alpha = va.animatedValue as Float
        }
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                state = State.EXPANDED
            }
        })
        currentAnimator = anim
        anim.start()
    }

    fun dismiss() {
        currentAnimator?.cancel()
        miniOverlay?.releaseRenderer()
        overlayContainer.removeAllViews()
        overlayContainer.visibility = View.GONE
        fullScreenView = null
        miniOverlay = null
        state = State.HIDDEN
    }

    fun isExpanded() = state == State.EXPANDED
    fun isMinimized() = state == State.MINIMIZED
    fun isVisible() = state != State.HIDDEN

    fun updateMiniContent(
        room: Room?,
        videoTrack: VideoTrack?,
        name: String,
        username: String,
        avatarUrl: String?,
        isMuted: Boolean,
        userId: Long
    ) {
        miniOverlay?.setContent(room, videoTrack, name, username, avatarUrl, isMuted, userId)
    }
}
