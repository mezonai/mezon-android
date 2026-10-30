package com.mezon.mobile.home.chat

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ForwardingExtractor
import androidx.media3.extractor.ForwardingTrackOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackAwareSeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Reads the last indexed fragment, without decoding it or downloading the whole video. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal suspend fun probeFragmentedVideoDurationUs(uri: Uri, factory: DataSource.Factory): Long? {
    val source = factory.createDataSource()
    val extractor = FragmentedMp4Extractor(FragmentedMp4Extractor.FLAG_READ_MFRA_FOR_SEEK_MAP)
    val holder = PositionHolder()
    var map: SeekMap? = null
    var largestTimeUs = Long.MIN_VALUE
    var previousTimeUs = Long.MIN_VALUE
    var lastFragment = false
    var length = C.LENGTH_UNSET.toLong()
    var input: DefaultExtractorInput? = null
    val startedMs = android.os.SystemClock.elapsedRealtime()

    fun openAt(position: Long): DefaultExtractorInput {
        source.close()
        val remaining = source.open(DataSpec.Builder().setUri(uri).setPosition(position).build())
        if (length == C.LENGTH_UNSET.toLong() && remaining != C.LENGTH_UNSET.toLong()) {
            length = position + remaining
        }
        return DefaultExtractorInput(source, position, length)
    }

    try {
        input = openAt(0)
        if (length <= 0 || !extractor.sniff(input)) return null
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput {
                return object : ForwardingTrackOutput(DiscardingTrackOutput()) {
                    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                        if (!lastFragment || (type != C.TRACK_TYPE_VIDEO && type != C.TRACK_TYPE_AUDIO)) return
                        if (timeUs > largestTimeUs) {
                            previousTimeUs = largestTimeUs
                            largestTimeUs = timeUs
                        } else if (timeUs in (previousTimeUs + 1) until largestTimeUs) {
                            previousTimeUs = timeUs
                        }
                    }
                }
            }
            override fun endTracks() = Unit
            override fun seekMap(seekMap: SeekMap) { map = seekMap }
        })

        while (android.os.SystemClock.elapsedRealtime() - startedMs < 15_000) {
            currentCoroutineContext().ensureActive()
            val result = extractor.read(input!!, holder)
            val readyMap = map
            if (!lastFragment && readyMap != null) {
                if (!readyMap.isSeekable || readyMap.durationUs > 0) return null
                val last = readyMap.getSeekPoints(Long.MAX_VALUE / 2).first
                // Bound network use when a file has a very large final fragment.
                if (last.position <= 0 || last.position >= length || length - last.position > 20L * 1024 * 1024) return null
                extractor.seek(last.position, last.timeUs)
                input = openAt(last.position)
                lastFragment = true
                continue
            }
            if (result == Extractor.RESULT_SEEK) input = openAt(holder.position)
            if (result == Extractor.RESULT_END_OF_INPUT) {
                if (!lastFragment || largestTimeUs <= 0) return null
                val sampleDuration = (largestTimeUs - previousTimeUs).takeIf {
                    previousTimeUs != Long.MIN_VALUE && it in 1L..1_000_000L
                } ?: 0L
                return largestTimeUs + sampleDuration
            }
        }
        return null
    } finally {
        extractor.release()
        source.close()
    }
}

/** Preserve the extractor's seek points while supplying a duration it could not determine. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun durationAwareExtractorsFactory(durationUs: Long): ExtractorsFactory {
    val base = DefaultExtractorsFactory()
    fun wrap(extractors: Array<Extractor>): Array<Extractor> = extractors.map { extractor ->
        if (extractor !is FragmentedMp4Extractor) return@map extractor
        object : ForwardingExtractor(extractor) {
            override fun init(output: ExtractorOutput) {
                super.init(object : ExtractorOutput by output {
                    override fun seekMap(seekMap: SeekMap) {
                        if (!seekMap.isSeekable || seekMap.durationUs > 0) {
                            output.seekMap(seekMap)
                        } else if (seekMap is TrackAwareSeekMap) {
                            output.seekMap(object : TrackAwareSeekMap by seekMap {
                                override fun getDurationUs(): Long = durationUs
                            })
                        } else {
                            output.seekMap(object : SeekMap by seekMap {
                                override fun getDurationUs(): Long = durationUs
                            })
                        }
                    }
                })
            }
        }
    }.toTypedArray()
    return object : ExtractorsFactory {
        override fun createExtractors(): Array<Extractor> = wrap(base.createExtractors())
        override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
            wrap(base.createExtractors(uri, responseHeaders))
    }
}
