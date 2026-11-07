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
    private var captureInProgress = false
    private var closed = false
    private var receivedAtMs = 0L
    var track: VideoTrack? = null

    val lastFrameReceivedMs: Long?
        get() = synchronized(lock) { receivedAtMs.takeIf { it > 0 } }

    val frameAspect: Float?
        get() = synchronized(lock) {
            frame?.takeIf { it.rotatedHeight > 0 }?.let { it.rotatedWidth.toFloat() / it.rotatedHeight }
        }

    override fun onFrame(frame: VideoFrame) {
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            if (closed) return
            receivedAtMs = now
            if (captureInProgress || (this.frame != null && now - lastCaptureMs < FRAME_CAPTURE_INTERVAL_MS)) return
            captureInProgress = true
            lastCaptureMs = now
        }
        try {
            val copy = frame.buffer.toI420()?.let { VideoFrame(it, frame.rotation, frame.timestampNs) } ?: return
            val previous = synchronized(lock) {
                if (closed) {
                    copy.release()
                    return
                }
                val old = this.frame
                this.frame = copy
                old
            }
            previous?.release()
        } finally {
            synchronized(lock) { captureInProgress = false }
        }
    }

    fun replay(sink: VideoSink) {
        val current = synchronized(lock) { frame?.also { it.retain() } } ?: return
        try {
            sink.onFrame(current)
        } finally {
            current.release()
        }
    }

    fun reset() {
        val previous = synchronized(lock) {
            receivedAtMs = 0
            val old = frame
            frame = null
            old
        }
        previous?.release()
    }

    fun clear() {
        synchronized(lock) { closed = true }
        reset()
    }
}

object VideoTrackLastFrameStore {

    private val keepers = LinkedHashMap<String, VideoTrackFrameKeeper>()

    @Synchronized
    fun observe(track: VideoTrack): VideoTrackFrameKeeper? {
        val trackId = runCatching { track.id() }.getOrNull() ?: return null
        val previous = keepers.remove(trackId)
        val keeper = if (previous?.track === track) previous else {
            previous?.let { detach(it) }
            VideoTrackFrameKeeper().also {
                it.track = if (runCatching { track.addSink(it) }.isSuccess) track else null
            }
        }
        keepers[trackId] = keeper
        while (keepers.size > MAX_TRACKED_FRAMES) {
            val eldest = keepers.entries.first()
            keepers.remove(eldest.key)
            detach(eldest.value)
        }
        return keeper
    }

    @Synchronized
    fun lastFrameReceivedAt(track: VideoTrack): Long? {
        val id = runCatching { track.id() }.getOrNull() ?: return null
        return keepers[id]?.takeIf { it.track === track }?.lastFrameReceivedMs
    }

    @Synchronized
    fun lastFrameAspect(track: VideoTrack): Float? {
        val id = runCatching { track.id() }.getOrNull() ?: return null
        return keepers[id]?.takeIf { it.track === track }?.frameAspect
    }

    @Synchronized
    fun reset(track: VideoTrack) {
        val id = runCatching { track.id() }.getOrNull() ?: return
        keepers[id]?.takeIf { it.track === track }?.reset()
    }

    fun replayLastFrame(track: VideoTrack, sink: VideoSink) {
        observe(track)?.replay(sink)
    }

    @Synchronized
    fun retainTracks(tracks: Collection<VideoTrack>) {
        val stale = keepers.filterValues { keeper -> tracks.none { it === keeper.track } }.keys
        for (id in stale) keepers.remove(id)?.let { detach(it) }
    }

    private fun detach(keeper: VideoTrackFrameKeeper) {
        keeper.clear()
        keeper.track?.let { track -> runCatching { track.removeSink(keeper) } }
        keeper.track = null
    }

    @Synchronized
    fun clear() {
        keepers.values.forEach { detach(it) }
        keepers.clear()
    }
}
