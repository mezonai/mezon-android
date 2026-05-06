package com.mezon.mobile.home.call

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.ui.cells.AvatarView
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

class CallVideoView(context: Context) : FrameLayout(context) {

    val remoteRenderer: SurfaceViewRenderer
    val localRenderer: SurfaceViewRenderer
    private val localPreviewContainer: FrameLayout
    private val localAvatarOverlay: AvatarView
    private var initialized = false

    private val localWidth = LayoutHelper.dp(120)
    private val localHeight = LayoutHelper.dp(160)
    private val localMargin = LayoutHelper.dp(10)

    init {
        remoteRenderer = SurfaceViewRenderer(context).apply {
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setMirror(false)
            setZOrderMediaOverlay(false)
            setEnableHardwareScaler(true)
        }
        addView(remoteRenderer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        localPreviewContainer = FrameLayout(context)
        localRenderer = SurfaceViewRenderer(context).apply {
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setMirror(true)
            setZOrderMediaOverlay(true)
            setEnableHardwareScaler(true)
        }
        localPreviewContainer.addView(
            localRenderer,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        localAvatarOverlay = AvatarView(context).apply {
            visibility = View.GONE
            setSizeDp(120)
            setRoundRadius(60f)
        }
        localPreviewContainer.addView(
            localAvatarOverlay,
            FrameLayout.LayoutParams(LayoutHelper.dp(120), LayoutHelper.dp(120), Gravity.CENTER),
        )
        val localParams = LayoutParams(localWidth, localHeight).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = localMargin
            marginEnd = localMargin
        }
        addView(localPreviewContainer, localParams)

        setBackgroundColor(Color.BLACK)
    }

    fun setLocalPreviewVisible(visible: Boolean) {
        if (visible) {
            localPreviewContainer.visibility = View.VISIBLE
        } else {
            localPreviewContainer.visibility = View.GONE
            localAvatarOverlay.visibility = View.GONE
            localAvatarOverlay.setImageUrl(null)
        }
    }

    fun initialize() {
        if (initialized) return
        initialized = true
        val eglContext = EglBaseProvider.acquire()
        remoteRenderer.init(eglContext, null)
        localRenderer.init(eglContext, null)
    }

    fun release() {
        if (!initialized) return
        initialized = false
        localAvatarOverlay.setImageUrl(null)
        localAvatarOverlay.visibility = View.GONE
        try {
            remoteRenderer.release()
            localRenderer.release()
            EglBaseProvider.release()
            EglBaseProvider.release()
        } catch (_: Exception) {}
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        release()
    }
}
