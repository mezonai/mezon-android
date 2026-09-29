package com.mezon.mobile.home.voice

import android.view.SurfaceHolder
import android.view.View
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/** Replays a static share after its local surface becomes drawable. No network retries. */
internal class VideoSurfaceFrameReplay(
    private val renderer: SurfaceViewRenderer,
    private val currentTrack: () -> VideoTrack?
) : SurfaceHolder.Callback, View.OnAttachStateChangeListener, View.OnLayoutChangeListener {
    private var released = false
    private var scheduled = false
    private val replay = Runnable {
        scheduled = false
        if (!released && renderer.isAttachedToWindow && renderer.isShown &&
            renderer.windowVisibility == View.VISIBLE && renderer.width > 0 && renderer.height > 0 &&
            renderer.holder.surface.isValid
        ) {
            // Resolve at execution time: a cell may have changed tracks since this was posted.
            currentTrack()?.let { track ->
                renderer.disableFpsReduction()
                VideoTrackLastFrameStore.replayLastFrame(track, renderer)
            }
        }
    }

    init {
        renderer.holder.addCallback(this)
        renderer.addOnAttachStateChangeListener(this)
        renderer.addOnLayoutChangeListener(this)
    }

    fun request() {
        if (released || scheduled || !renderer.isAttachedToWindow || !renderer.isShown ||
            renderer.windowVisibility != View.VISIBLE || currentTrack() == null
        ) return
        scheduled = true
        // Run after SurfaceViewRenderer's surface callback has queued EGL surface creation.
        if (!renderer.post(replay)) scheduled = false
    }

    fun cancel() {
        renderer.removeCallbacks(replay)
        scheduled = false
    }

    fun release() {
        released = true
        cancel()
        renderer.holder.removeCallback(this)
        renderer.removeOnAttachStateChangeListener(this)
        renderer.removeOnLayoutChangeListener(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) = request()
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = request()
    override fun surfaceDestroyed(holder: SurfaceHolder) = cancel()
    override fun onViewAttachedToWindow(view: View) = request()
    override fun onViewDetachedFromWindow(view: View) = cancel()

    override fun onLayoutChange(
        view: View, left: Int, top: Int, right: Int, bottom: Int,
        oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
    ) {
        if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) request()
    }
}
