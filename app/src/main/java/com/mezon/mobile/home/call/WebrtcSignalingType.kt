package com.mezon.mobile.home.call

object WebrtcSignalingType {
    const val SDP_INIT = 0
    const val SDP_OFFER = 1
    const val SDP_ANSWER = 2
    const val ICE_CANDIDATE = 3
    const val SDP_QUIT = 4
    const val SDP_TIMEOUT = 5
    const val SDP_NOT_AVAILABLE = 6
    const val SDP_JOINED_OTHER_CALL = 7
    const val STATUS_REMOTE_MEDIA = 8
    const val CLEAR_CALL = 50
}
