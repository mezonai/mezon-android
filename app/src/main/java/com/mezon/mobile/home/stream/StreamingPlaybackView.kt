package com.mezon.mobile.home.stream

import android.content.Context
import android.graphics.Color
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import androidx.media3.exoplayer.ExoPlayer
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/** One renderer per surface; the transport/player belongs to the session. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class StreamingPlaybackView(context: Context) : FrameLayout(context) {
    private val texture = TextureView(context)
    private var renderer: SurfaceViewRenderer? = null
    private var track: VideoTrack? = null
    private var player: ExoPlayer? = null
    private var session: StreamingWebRtcSession? = null
    private val replayFrame = Runnable { renderer?.let { this.session?.replayVideoFrame(it) } }

    init {
        setBackgroundColor(Color.BLACK)
        addView(texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        isClickable = false
    }

    fun update(session: StreamingWebRtcSession?) {
        this.session = session
        if (!isAttachedToWindow) return
        val nextPlayer = session?.player
        if (player !== nextPlayer) {
            player?.let { runCatching { it.clearVideoTextureView(texture) } }
            player = nextPlayer
            nextPlayer?.setVideoTextureView(texture)
        }
        texture.visibility = if (nextPlayer != null) View.VISIBLE else View.GONE
        val nextTrack = session?.remoteVideoTrack
        if (track !== nextTrack) {
            renderer?.let { sink -> track?.let { old -> runCatching { old.removeSink(sink) } } }
            track = nextTrack
            if (nextTrack != null && session != null) {
                val sink = renderer ?: SurfaceViewRenderer(context).also {
                    it.init(session.eglContext, null)
                    it.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                    it.setEnableHardwareScaler(true)
                    it.holder.addCallback(object : android.view.SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: android.view.SurfaceHolder) { post(replayFrame) }
                        override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) { post(replayFrame) }
                        override fun surfaceDestroyed(holder: android.view.SurfaceHolder) { removeCallbacks(replayFrame) }
                    })
                    addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                    renderer = it
                }
                nextTrack.addSink(sink)
                post(replayFrame)
            }
        }
        renderer?.visibility = if (nextTrack != null) View.VISIBLE else View.GONE
        // TextureView keeps the HLS picture fitted without cropping in fullscreen and mini mode.
        nextPlayer?.videoSize?.let { size ->
            if (width > 0 && height > 0 && size.width > 0 && size.height > 0) {
                val videoRatio = size.width * size.pixelWidthHeightRatio / size.height
                val viewRatio = width.toFloat() / height
                texture.setTransform(android.graphics.Matrix().apply {
                    if (videoRatio > viewRatio) setScale(1f, viewRatio / videoRatio, width / 2f, height / 2f)
                    else setScale(videoRatio / viewRatio, 1f, width / 2f, height / 2f)
                })
            }
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); update(session) }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); update(session) }
    override fun onDetachedFromWindow() {
        removeCallbacks(replayFrame)
        player?.let { runCatching { it.clearVideoTextureView(texture) } }; player = null
        renderer?.let { sink ->
            track?.let { old -> runCatching { old.removeSink(sink) } }
            sink.release()
            removeView(sink)
        }
        renderer = null; track = null
        super.onDetachedFromWindow()
    }
}
