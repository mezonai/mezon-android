package com.mezon.mobile.home.voice

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.otaliastudios.zoom.Alignment
import com.otaliastudios.zoom.ZoomApi
import com.otaliastudios.zoom.ZoomLayout
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.home.call.EglBaseProvider
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

class VoiceFocusedShareView(
    context: Context,
    private val themeColors: ThemeColors
) : FrameLayout(context) {

    companion object {
        private const val MIN_SCALE = 1f
        private const val MAX_SCALE = 4f
        private const val MIN_ASPECT_RATIO = 0.4f
        private const val MAX_ASPECT_RATIO = 3.2f
        private const val ACTION_TOP_RATIO = 0.07f
        private const val ACTION_END_RATIO = 0.03f
    }

    private var textureRenderer: SurfaceViewRenderer? = null
    private val zoomContainer: ZoomLayout
    private val zoomCenterHost: FrameLayout
    private val zoomContent: FrameLayout
    private val actionRow: LinearLayout
    private val emojiButton: FrameLayout
    private val fullScreenButton: FrameLayout
    private val minimizeButton: FrameLayout
    private var rendererInitialized = false
    private var currentVideoTrack: VideoTrack? = null
    private var contentAspectRatio = 16f / 9f
    private var pendingVideoTrack: VideoTrack? = null
    private var attachScheduled = false

    var onEmojiClick: (() -> Unit)? = null
    var onMinimizeClick: (() -> Unit)? = null
    var onFullScreenClick: (() -> Unit)? = null

    init {
        visibility = View.INVISIBLE
        setBackgroundColor(0xFF000000.toInt())
        zoomContainer = ZoomLayout(context).apply {
            setHasClickableChildren(true)
            setTransformation(ZoomApi.TRANSFORMATION_CENTER_INSIDE, ZoomApi.TRANSFORMATION_GRAVITY_AUTO)
            setAlignment(Alignment.CENTER)
            setMinZoom(MIN_SCALE, ZoomApi.TYPE_ZOOM)
            setMaxZoom(MAX_SCALE, ZoomApi.TYPE_ZOOM)
            setOverPinchable(false)
            setHorizontalPanEnabled(true)
            setVerticalPanEnabled(true)
            setFlingEnabled(true)
        }
        zoomCenterHost = FrameLayout(context)
        zoomContent = FrameLayout(context)
        val renderer = SurfaceViewRenderer(context).apply {
            setZOrderMediaOverlay(true)
            setScalingType(
                RendererCommon.ScalingType.SCALE_ASPECT_FIT,
                RendererCommon.ScalingType.SCALE_ASPECT_FIT
            )
            setMirror(false)
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                maybeAttachPending()
            }
        }
        textureRenderer = renderer
        zoomContent.addView(renderer, FrameLayout.LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        ))
        zoomCenterHost.addView(zoomContent, FrameLayout.LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))
        zoomContainer.addView(zoomCenterHost, FrameLayout.LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        ))
        addView(zoomContainer, LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        ))

        actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        emojiButton = createFocusActionButton(context, MezonIcon.faceIcon) {
            onEmojiClick?.invoke()
        }
        fullScreenButton = createFocusActionButton(context, MezonIcon.expandIcon) {
            onFullScreenClick?.invoke()
        }.apply {
            visibility = View.GONE
        }
        minimizeButton = createFocusActionButton(context, MezonIcon.minimizeIcon) {
            onMinimizeClick?.invoke()
        }

        actionRow.addView(emojiButton, LinearLayout.LayoutParams(LayoutHelper.dp(36), LayoutHelper.dp(36)))
        actionRow.addView(fullScreenButton, LinearLayout.LayoutParams(LayoutHelper.dp(36), LayoutHelper.dp(36)))
        actionRow.addView(minimizeButton, LinearLayout.LayoutParams(LayoutHelper.dp(36), LayoutHelper.dp(36)).apply {
            marginStart = LayoutHelper.dp(10)
        })

        addView(actionRow, LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        ).apply {
            topMargin = LayoutHelper.dp(20)
            marginEnd = LayoutHelper.dp(12)
        })
    }

    fun setPipMode(enabled: Boolean) {
        actionRow.visibility = if (enabled) View.GONE else View.VISIBLE
        emojiButton.visibility = View.VISIBLE
        fullScreenButton.visibility = View.GONE
    }

    fun showShare(participant: ParticipantInfo): Boolean {
        if (!participant.isScreenShare || participant.videoTrack == null) {
            return false
        }
        visibility = View.VISIBLE
        val renderer = textureRenderer ?: return false
        val videoTrack = participant.videoTrack
        contentAspectRatio = participant.contentAspectRatio.coerceIn(MIN_ASPECT_RATIO, MAX_ASPECT_RATIO)
        updateZoomContentLayout()
        pendingVideoTrack = videoTrack
        maybeAttachPending()
        renderer.setScalingType(
            RendererCommon.ScalingType.SCALE_ASPECT_FIT,
            RendererCommon.ScalingType.SCALE_ASPECT_FIT
        )
        renderer.setMirror(false)
        return true
    }

    fun refreshTrack(participant: ParticipantInfo) {
        if (!participant.isScreenShare) return
        val track = participant.videoTrack ?: return
        if (track === currentVideoTrack) return
        pendingVideoTrack = track
        maybeAttachPending()
    }

    fun clear() {
        val renderer = textureRenderer
        if (renderer != null) {
            currentVideoTrack?.let { runCatching { it.removeSink(renderer) } }
        }
        currentVideoTrack = null
        pendingVideoTrack = null
        contentAspectRatio = 16f / 9f
        updateZoomContentLayout()
        if (renderer != null) {
            resetTransformState(renderer)
        }
        visibility = View.INVISIBLE
    }

    fun releaseRenderer() {
        clear()
        textureRenderer?.release()
        if (rendererInitialized) EglBaseProvider.release()
        textureRenderer = null
        rendererInitialized = false
        attachScheduled = false
    }

    private fun createFocusActionButton(context: Context, icon: MezonIcon, onClick: () -> Unit): FrameLayout {
        return FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x96000000.toInt())
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            applyVoiceButtonPressFeedback()
            val iconView = ImageView(context).apply {
                setImageDrawable(icon.getDrawable(context).apply {
                    colorFilter = PorterDuffColorFilter(0xFFFFFFFF.toInt(), PorterDuff.Mode.SRC_IN)
                })
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
            addView(iconView, LayoutParams(LayoutHelper.dp(16), LayoutHelper.dp(16), Gravity.CENTER))
        }
    }

    private fun resetTransformState(renderer: SurfaceViewRenderer) {
        zoomContainer.moveToCenter(MIN_SCALE, false)
    }

    private fun maybeAttachPending() {
        val renderer = textureRenderer ?: return
        val track = pendingVideoTrack ?: return
        if (!canInitRenderer(renderer)) {
            scheduleAttachRetry()
            return
        }
        if (!rendererInitialized) {
            renderer.init(EglBaseProvider.acquire(), null)
            rendererInitialized = true
        }
        if (currentVideoTrack !== track) {
            currentVideoTrack?.let { runCatching { it.removeSink(renderer) } }
            try {
                track.addSink(renderer)
                currentVideoTrack = track
                resetTransformState(renderer)
            } catch (e: IllegalStateException) {
                currentVideoTrack = null
                pendingVideoTrack = null
            }
        }
    }

    private fun canInitRenderer(renderer: SurfaceViewRenderer): Boolean {
        if (visibility != View.VISIBLE) return false
        if (!renderer.isAttachedToWindow || !isAttachedToWindow) return false
        if (renderer.width <= 0 || renderer.height <= 0) return false
        if (renderer.width > width * 4 || renderer.height > height * 4) return false
        if (width <= 0 || height <= 0) return false
        return true
    }

    private fun scheduleAttachRetry() {
        if (attachScheduled) return
        attachScheduled = true
        postOnAnimation {
            attachScheduled = false
            maybeAttachPending()
            if (pendingVideoTrack != null && currentVideoTrack == null) {
                scheduleAttachRetry()
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateZoomContentLayout()
        val actionLp = actionRow.layoutParams as LayoutParams
        actionLp.topMargin = (h * ACTION_TOP_RATIO).toInt().coerceAtLeast(LayoutHelper.dp(12))
        actionLp.marginEnd = (w * ACTION_END_RATIO).toInt().coerceAtLeast(LayoutHelper.dp(10))
        actionRow.layoutParams = actionLp
        maybeAttachPending()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        maybeAttachPending()
    }

    private fun updateZoomContentLayout() {
        val parentW = width.coerceAtLeast(1)
        val parentH = height.coerceAtLeast(1)
        if (parentW <= 1 || parentH <= 1) return
        val parentAspect = parentW.toFloat() / parentH.toFloat()
        val targetAspect = contentAspectRatio.coerceIn(MIN_ASPECT_RATIO, MAX_ASPECT_RATIO)
        val nextW: Int
        val nextH: Int
        if (parentAspect > targetAspect) {
            nextH = parentH
            nextW = (nextH * targetAspect).toInt().coerceAtLeast(1)
        } else {
            nextW = parentW
            nextH = (nextW / targetAspect).toInt().coerceAtLeast(1)
        }
        val contentLp = zoomContent.layoutParams as FrameLayout.LayoutParams
        if (contentLp.width != nextW || contentLp.height != nextH || contentLp.gravity != Gravity.CENTER) {
            contentLp.width = nextW
            contentLp.height = nextH
            contentLp.gravity = Gravity.CENTER
            zoomContent.layoutParams = contentLp
        }
    }
}
