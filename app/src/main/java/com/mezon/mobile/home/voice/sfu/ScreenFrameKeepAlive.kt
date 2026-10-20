package com.mezon.mobile.home.voice.sfu

import android.os.SystemClock
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame

internal class ScreenFrameKeepAlive(
    private val helper: SurfaceTextureHelper,
    private val downstream: CapturerObserver,
) : CapturerObserver {

    private val handler = helper.handler
    @Volatile private var lastFrameMs = 0L
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            if (SystemClock.elapsedRealtime() - lastFrameMs >= REPEAT_INTERVAL_MS) helper.forceFrame()
            handler.postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    override fun onCapturerStarted(success: Boolean) {
        downstream.onCapturerStarted(success)
        if (!success) return
        handler.post {
            running = true
            lastFrameMs = SystemClock.elapsedRealtime()
            handler.removeCallbacks(tick)
            handler.postDelayed(tick, REPEAT_INTERVAL_MS)
        }
    }

    override fun onCapturerStopped() {
        handler.post {
            running = false
            handler.removeCallbacks(tick)
        }
        downstream.onCapturerStopped()
    }

    override fun onFrameCaptured(frame: VideoFrame) {
        lastFrameMs = SystemClock.elapsedRealtime()
        downstream.onFrameCaptured(frame)
    }

    private companion object {
        const val REPEAT_INTERVAL_MS = 500L
    }
}
