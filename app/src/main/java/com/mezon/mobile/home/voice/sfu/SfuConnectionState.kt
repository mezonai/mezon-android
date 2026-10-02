package com.mezon.mobile.home.voice.sfu

enum class SfuConnectionState {
    CONNECTING,
    JOINING,
    AWAITING_OFFER,
    ICE_CONNECTED,
    DTLS_HANDSHAKE,
    AWAITING_CONFIRMATION,
    CONNECTED,
    DISCONNECTED,
    FAILED,
}
