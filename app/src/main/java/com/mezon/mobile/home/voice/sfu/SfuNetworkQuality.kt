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

class SfuCameraUplinkQuality {

    class Sample(val framesEncoded: Long, val targetBitrate: Double, val bandwidthLimited: Boolean)

    private var limited = false
    private var limitedSamples = 0
    private var cleanSamples = 0
    private var recoverySamples = RECOVERY_SAMPLES
    private var samplesSinceRecovery = Int.MAX_VALUE
    private var framesEncoded: Long? = null

    fun update(sample: Sample?, maxBitrateBps: Int): Boolean {
        val previousFrames = framesEncoded
        framesEncoded = sample?.framesEncoded
        if (sample == null || previousFrames == null || sample.framesEncoded <= previousFrames) {
            limited = false
            limitedSamples = 0
            cleanSamples = 0
            return false
        }
        val sampleLimited = sample.bandwidthLimited && sample.targetBitrate < maxBitrateBps * TARGET_RATIO
        if (sampleLimited) {
            limitedSamples += 1
            cleanSamples = 0
        } else {
            limitedSamples = 0
            cleanSamples += 1
        }
        if (limited) {
            if (cleanSamples >= recoverySamples) {
                limited = false
                samplesSinceRecovery = 0
            }
            return limited
        }
        if (samplesSinceRecovery < Int.MAX_VALUE) samplesSinceRecovery += 1
        if (limitedSamples >= LIMITED_SAMPLES) {
            limited = true
            recoverySamples = if (samplesSinceRecovery <= RELAPSE_SAMPLES) {
                (recoverySamples * 2).coerceAtMost(MAX_RECOVERY_SAMPLES)
            } else {
                RECOVERY_SAMPLES
            }
        }
        return limited
    }

    companion object {
        private const val TARGET_RATIO = 0.8
        private const val LIMITED_SAMPLES = 2
        private const val RECOVERY_SAMPLES = 6
        private const val MAX_RECOVERY_SAMPLES = 24
        private const val RELAPSE_SAMPLES = 12

        fun sample(report: RTCStatsReport, mid: String): Sample? {
            var best: Sample? = null
            for (stat in report.statsMap.values) {
                if (stat.type != "outbound-rtp") continue
                val members = stat.members
                if ((members["kind"] ?: members["mediaType"]) != "video" || members["mid"] != mid) continue
                val frames = (members["framesEncoded"] as? Number)?.toLong() ?: continue
                val target = (members["targetBitrate"] as? Number)?.toDouble() ?: continue
                val reason = members["qualityLimitationReason"] as? String ?: continue
                val current = best
                if (current != null && current.framesEncoded >= frames) continue
                best = Sample(frames, target, reason == "bandwidth")
            }
            return best
        }
    }
}
