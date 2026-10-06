package com.mezon.mobile.home.voice.sfu

import org.webrtc.RTCStatsReport

class SfuNetworkQuality {

    class LossSample(val id: String, val upload: Boolean, val packets: Long, val lost: Long)

    private var previous: Map<String, LossSample> = emptyMap()
    private var weak = false
    private var badSamples = 0
    private var cleanSamples = 0

    fun update(samples: List<LossSample>): Boolean {
        var receivedExpected = 0L
        var receivedLost = 0L
        var sentExpected = 0L
        var sentLost = 0L
        for (sample in samples) {
            val before = previous[sample.id] ?: continue
            val lostDelta = (sample.lost - before.lost).coerceAtLeast(0L)
            val packetsDelta = (sample.packets - before.packets).coerceAtLeast(0L)
            if (sample.upload) {
                sentLost += lostDelta
                sentExpected += packetsDelta
            } else {
                receivedLost += lostDelta
                receivedExpected += packetsDelta + lostDelta
            }
        }
        previous = samples.associateBy { it.id }
        val ratio = listOfNotNull(
            lossRatio(receivedExpected, receivedLost),
            lossRatio(sentExpected, sentLost)
        ).maxOrNull()
        if (ratio == null) {
            // Silence or a new stream is not evidence that the network recovered.
            badSamples = 0
            cleanSamples = 0
            return weak
        }
        badSamples = if (ratio >= WARNING_LOSS_RATIO) (badSamples + 1).coerceAtMost(WARNING_SAMPLES) else 0
        cleanSamples = if (ratio < RECOVERY_LOSS_RATIO) (cleanSamples + 1).coerceAtMost(CLEAR_SAMPLES) else 0
        if (ratio >= SEVERE_LOSS_RATIO || badSamples >= WARNING_SAMPLES) {
            weak = true
        } else if (cleanSamples >= CLEAR_SAMPLES) {
            weak = false
        }
        return weak
    }

    private fun lossRatio(expected: Long, lost: Long): Double? =
        if (expected >= MIN_PACKETS) lost.toDouble() / expected else null

    companion object {
        private const val WARNING_LOSS_RATIO = 0.10
        private const val SEVERE_LOSS_RATIO = 0.20
        private const val RECOVERY_LOSS_RATIO = 0.05
        private const val MIN_PACKETS = 50L
        private const val WARNING_SAMPLES = 2
        private const val CLEAR_SAMPLES = 2

        fun lossSamples(report: RTCStatsReport): List<LossSample> {
            val stats = report.statsMap
            val samples = ArrayList<LossSample>()
            for (stat in stats.values) {
                if (stat.type != "inbound-rtp" && stat.type != "remote-inbound-rtp") continue
                val lost = (stat.members["packetsLost"] as? Number)?.toLong() ?: continue
                val upload = stat.type == "remote-inbound-rtp"
                val media = if (upload) {
                    (stat.members["localId"] as? String)?.let { stats[it] } ?: continue
                } else stat
                if ((media.members["kind"] ?: media.members["mediaType"]) != "audio") continue
                val packets = media.members[if (upload) "packetsSent" else "packetsReceived"] as? Number ?: continue
                samples.add(LossSample(stat.id, upload, packets.toLong(), lost))
            }
            return samples
        }
    }
}
