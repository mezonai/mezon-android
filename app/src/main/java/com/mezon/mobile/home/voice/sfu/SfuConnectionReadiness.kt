package com.mezon.mobile.home.voice.sfu

/** Presence confirms admission; only the current transport can confirm media readiness. */
internal class SfuConnectionReadiness(val requiresVoiceJoined: Boolean = true) {
    var iceConnected = false
    var transportConnected = false
    var roomConfirmed = false
    var peerId: String? = null
    private val confirmedPeerIds = LinkedHashSet<String>()
    private var legacyVoiceJoined = false

    fun confirmVoiceJoined(peerId: String?) {
        if (peerId.isNullOrBlank() || peerId == "0") {
            // Older voiceJoined events omit peer_id. Caller must match user + clan + room.
            legacyVoiceJoined = true
        } else {
            confirmedPeerIds.add(peerId)
            if (confirmedPeerIds.size > 8) confirmedPeerIds.remove(confirmedPeerIds.first())
        }
    }

    val voiceJoinedConfirmed: Boolean
        get() = legacyVoiceJoined || peerId in confirmedPeerIds

    val isReady: Boolean
        get() = iceConnected && transportConnected && roomConfirmed &&
            !peerId.isNullOrBlank() && peerId != "0" && peerId != "null" &&
            (!requiresVoiceJoined || voiceJoinedConfirmed)
}
