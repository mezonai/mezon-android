package com.mezon.mobile.home.call

import org.webrtc.SessionDescription

sealed class CallState {
    object Idle : CallState()
    data class Outgoing(val callInfo: CallInfo, val startTime: Long) : CallState()
    data class Incoming(val callInfo: CallInfo, val offer: SessionDescription) : CallState()
    data class Connecting(val callInfo: CallInfo) : CallState()
    data class Connected(val callInfo: CallInfo, val connectedTime: Long) : CallState()
}

data class CallInfo(
    val peerId: Long,
    val peerName: String,
    val peerUsername: String = "",
    val peerAvatar: String?,
    val channelId: Long,
    val isVideo: Boolean,
    val isInitiator: Boolean
)

enum class CallEndReason {
    LOCAL_HANGUP,
    REMOTE_HANGUP,
    LOCAL_REJECT,
    REMOTE_REJECT,
    TIMEOUT,
    REMOTE_TIMEOUT,
    ICE_FAILED,
    BUSY,
    ERROR,
    CLEAR_CALL,
    CANCELLED
}
