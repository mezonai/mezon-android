package com.mezon.mobile.home.voice

import android.os.SystemClock
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack

private const val FRAME_CAPTURE_INTERVAL_MS = 500L
private const val MAX_TRACKED_FRAMES = 16

class VideoTrackFrameKeeper : VideoSink {

    private val lock = Any()
    private var frame: VideoFrame? = null
    private var lastCaptureMs = 0L
    var track: VideoTrack? = null

    override fun onFrame(frame: VideoFrame) {
        val now = SystemClock.elapsedRealtime()
        val hasFrame = synchronized(lock) { this.frame != null }
        if (hasFrame && now - lastCaptureMs < FRAME_CAPTURE_INTERVAL_MS) return
        lastCaptureMs = now
        val i420 = frame.buffer.toI420() ?: return
        val copy = VideoFrame(i420, frame.rotation, frame.timestampNs)
        val previous = synchronized(lock) {
            val old = this.frame
            this.frame = copy
            old
        }
        previous?.release()
    }

    fun replay(sink: VideoSink) {
        val current = synchronized(lock) { frame } ?: return
        sink.onFrame(current)
    }

    fun clear() {
        val previous = synchronized(lock) {
            val old = frame
            frame = null
            old
        }
        previous?.release()
    }
}

object VideoTrackLastFrameStore {

    private val keepers = LinkedHashMap<String, VideoTrackFrameKeeper>()

    fun observe(track: VideoTrack): VideoTrackFrameKeeper? {
        val trackId = runCatching { track.id() }.getOrNull() ?: return null
        val keeper = keepers.remove(trackId) ?: VideoTrackFrameKeeper()
        keepers[trackId] = keeper
        if (keeper.track !== track) {
            keeper.track?.let { previous -> runCatching { previous.removeSink(keeper) } }
            keeper.track = if (runCatching { track.addSink(keeper) }.isSuccess) track else null
        }
        while (keepers.size > MAX_TRACKED_FRAMES) {
            val eldest = keepers.entries.first()
            keepers.remove(eldest.key)
            eldest.value.track?.let { previous -> runCatching { previous.removeSink(eldest.value) } }
            eldest.value.clear()
        }
        return keeper
    }

    fun replayLastFrame(track: VideoTrack, sink: VideoSink) {
        observe(track)?.replay(sink)
    }

    fun clear() {
        for (keeper in keepers.values) {
            keeper.track?.let { previous -> runCatching { previous.removeSink(keeper) } }
            keeper.clear()
        }
        keepers.clear()
    }
}
