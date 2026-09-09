package com.mezon.mobile.home.voice.sfu

import org.webrtc.AudioTrack
import org.webrtc.VideoTrack

data class SfuParticipant(
    val id: String,
    val userId: String?,
    val role: SfuRole?,
    val muted: Boolean,
    val audio: AudioTrack?,
    val video: VideoTrack?,
    val screen: VideoTrack?,
    val screenActive: Boolean,
    val cameraActive: Boolean,
)
