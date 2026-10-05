package com.mezon.mobile.home.voice.sfu

import org.webrtc.RTCStatsReport

class SfuNetworkQuality {

    class LossSample(val id: String, val upload: Boolean, val packets: Long, val lost: Long)

    private var previous: Map<String, LossSample> = emptyMap()
    private var weak = false
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
        val lossy = isLossy(receivedExpected, receivedLost) || isLossy(sentExpected, sentLost)
        cleanSamples = if (lossy) 0 else cleanSamples + 1
        if (lossy) weak = true else if (cleanSamples >= CLEAR_SAMPLES) weak = false
        return weak
    }

    private fun isLossy(expected: Long, lost: Long): Boolean =
        expected >= MIN_PACKETS && lost.toDouble() / expected >= LOSS_RATIO

    companion object {
        private const val LOSS_RATIO = 0.05
        private const val MIN_PACKETS = 50L
        private const val CLEAR_SAMPLES = 2

        fun lossSamples(report: RTCStatsReport): List<LossSample> {
            val stats = report.statsMap
            val samples = ArrayList<LossSample>()
            for (stat in stats.values) {
                val lost = (stat.members["packetsLost"] as? Number)?.toLong() ?: continue
                val upload = stat.type == "remote-inbound-rtp"
                val packets = when {
                    stat.type == "inbound-rtp" -> stat.members["packetsReceived"]
                    upload -> (stat.members["localId"] as? String)?.let { stats[it]?.members?.get("packetsSent") }
                    else -> null
                } as? Number ?: continue
                samples.add(LossSample(stat.id, upload, packets.toLong(), lost))
            }
            return samples
        }
    }
}
