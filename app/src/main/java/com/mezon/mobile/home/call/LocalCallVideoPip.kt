package com.mezon.mobile.home.call

import android.content.Context
import android.widget.FrameLayout
import com.mezon.mobile.core.LayoutHelper
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

class LocalCallVideoPip(context: Context) : FrameLayout(context) {

    val renderer: SurfaceViewRenderer
    private var initialized = false

    init {
        val w = LayoutHelper.dp(120)
        val h = LayoutHelper.dp(160)
        renderer = SurfaceViewRenderer(context).apply {
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setMirror(true)
            setZOrderMediaOverlay(true)
            setEnableHardwareScaler(false)
        }
        addView(renderer, LayoutParams(w, h))
    }

    fun ensureInitialized() {
        if (initialized) return
        initialized = true
        val eglContext = EglBaseProvider.acquire()
        renderer.init(eglContext, null)
    }

    fun safeRelease() {
        if (!initialized) return
        initialized = false
        try {
            renderer.release()
        } catch (_: Exception) {}
        EglBaseProvider.release()
    }
}
