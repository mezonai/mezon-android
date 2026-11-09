package com.mezon.mobile.util

import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object FileIngestHlsCheck {
    private const val MOOV_LIMIT = 64L * 1024 * 1024
    private const val TRACK_LIMIT = 16
    private const val SAMPLE_LIMIT = 4L * 1024 * 1024
    private const val SEGMENT_LIMIT = 16384.0
    private const val MINIMUM_SEGMENT_SECONDS = 4.0
    private const val MPEG4_AUDIO_OBJECT_TYPE = 0x40
    private const val ESDS_READ_LIMIT = 256L

    private val FIRST_BOX_TYPES = setOf("ftyp", "moov", "mdat", "free", "skip", "wide", "uuid", "pnot", "junk", "meta")
    private val FRAGMENT_BOX_TYPES = setOf("moof", "styp", "sidx")
    private val AVC_SAMPLE_ENTRIES = setOf("avc1", "avc3")
    private val HEVC_SAMPLE_ENTRIES = setOf("hvc1", "hev1")

    private enum class Rejection {
        UNREADABLE,
        NOT_MP4,
        FRAGMENTED,
        NO_MOOV,
        MOOV_TOO_LARGE,
        TOO_MANY_TRACKS,
        NO_VIDEO_TRACK,
        VIDEO_CODEC,
        NO_KEYFRAME,
        AUDIO_CODEC,
        TOO_MANY_SAMPLES,
        TOO_MANY_SEGMENTS,
    }

    private class Box(val type: String, val start: Long, val payload: Long, val end: Long)

    private class Track {
        var handler = ""
        var isEnabled = false
        var sampleEntry = ""
        var hasAvcC = false
        var hasHvcC = false
        var hasDOps = false
        var objectTypeIndication: Int? = null
        var sampleCount = 0L
        var syncSampleCount: Long? = null
    }

    private class Report {
        var firstBox = ""
        var moovSize = 0L
        var durationSeconds = 0.0
        val tracks = mutableListOf<Track>()
        var video: Track? = null
        var audio: Track? = null
        var rejection: Rejection? = null
    }

    private class Reader(private val file: RandomAccessFile) {
        val size: Long = file.length()

        fun bytes(offset: Long, count: Int): ByteArray? {
            if (count <= 0 || offset < 0 || offset > size - count) return null
            val buffer = ByteArray(count)
            file.seek(offset)
            file.readFully(buffer)
            return buffer
        }
    }

    suspend fun supportsHls(file: File): Boolean = withContext(Dispatchers.IO) {
        inspect(file).rejection == null
    }

    private fun inspect(file: File): Report {
        val report = Report()
        report.rejection = try {
            RandomAccessFile(file, "r").use { raf -> rejection(Reader(raf), report) }
        } catch (_: Exception) {
            Rejection.UNREADABLE
        }
        return report
    }

    private fun rejection(reader: Reader, report: Report): Rejection? {
        var moov: Box? = null
        var isFragmented = false
        forEachBox(reader, 0, reader.size) { box ->
            if (report.firstBox.isEmpty()) {
                report.firstBox = box.type
                if (box.type !in FIRST_BOX_TYPES) return@forEachBox false
            }
            if (box.type in FRAGMENT_BOX_TYPES) {
                isFragmented = true
                return@forEachBox false
            }
            if (box.type == "moov" && moov == null) moov = box
            true
        }
        if (report.firstBox !in FIRST_BOX_TYPES) return Rejection.NOT_MP4
        if (isFragmented) return Rejection.FRAGMENTED
        val movie = moov ?: return Rejection.NO_MOOV
        report.moovSize = movie.end - movie.start
        if (report.moovSize > MOOV_LIMIT) return Rejection.MOOV_TOO_LARGE
        forEachBox(reader, movie.payload, movie.end) { box ->
            when (box.type) {
                "mvex" -> {
                    isFragmented = true
                    return@forEachBox false
                }
                "mvhd" -> report.durationSeconds = movieDuration(reader, box)
                "trak" -> report.tracks.add(track(reader, box))
            }
            true
        }
        if (isFragmented) return Rejection.FRAGMENTED
        if (report.tracks.size > TRACK_LIMIT) return Rejection.TOO_MANY_TRACKS
        val videos = report.tracks.filter { it.handler == "vide" }
        val audios = report.tracks.filter { it.handler == "soun" }
        report.video = videos.firstOrNull { it.isEnabled } ?: videos.firstOrNull()
        report.audio = audios.firstOrNull { it.isEnabled } ?: audios.firstOrNull()
        val video = report.video ?: return Rejection.NO_VIDEO_TRACK
        if (!isSupportedVideo(video)) return Rejection.VIDEO_CODEC
        if (video.sampleCount <= 0 || (video.syncSampleCount ?: video.sampleCount) <= 0) return Rejection.NO_KEYFRAME
        val audio = report.audio
        if (audio != null && !isSupportedAudio(audio)) return Rejection.AUDIO_CODEC
        if (video.sampleCount > SAMPLE_LIMIT || (audio?.sampleCount ?: 0L) > SAMPLE_LIMIT) return Rejection.TOO_MANY_SAMPLES
        if (report.durationSeconds / MINIMUM_SEGMENT_SECONDS > SEGMENT_LIMIT) return Rejection.TOO_MANY_SEGMENTS
        return null
    }

    private fun isSupportedVideo(track: Track): Boolean =
        (track.sampleEntry in AVC_SAMPLE_ENTRIES && track.hasAvcC) ||
            (track.sampleEntry in HEVC_SAMPLE_ENTRIES && track.hasHvcC)

    private fun isSupportedAudio(track: Track): Boolean =
        (track.sampleEntry == "mp4a" && track.objectTypeIndication == MPEG4_AUDIO_OBJECT_TYPE) ||
            (track.sampleEntry == "Opus" && track.hasDOps)

    private fun forEachBox(reader: Reader, start: Long, end: Long, body: (Box) -> Boolean) {
        val limit = minOf(end, reader.size)
        var offset = start
        while (offset <= limit - 8) {
            val header = reader.bytes(offset, minOf(16L, limit - offset).toInt()) ?: return
            var boxSize = uint(header, 0, 4)
            var headerSize = 8L
            if (boxSize == 1L) {
                if (header.size < 16) return
                boxSize = uint(header, 8, 8)
                headerSize = 16L
            } else if (boxSize == 0L) {
                boxSize = limit - offset
            }
            if (boxSize < headerSize || boxSize > limit - offset) return
            if (!body(Box(fourCC(header, 4), offset, offset + headerSize, offset + boxSize))) return
            offset += boxSize
        }
    }

    private fun movieDuration(reader: Reader, mvhd: Box): Double {
        val bytes = reader.bytes(mvhd.payload, 32) ?: return 0.0
        val isVersion1 = u8(bytes, 0) == 1
        val timescale = uint(bytes, if (isVersion1) 20 else 12, 4)
        val duration = uint(bytes, if (isVersion1) 24 else 16, if (isVersion1) 8 else 4)
        return if (timescale > 0 && duration >= 0) duration.toDouble() / timescale else 0.0
    }

    private fun track(reader: Reader, trak: Box): Track {
        val track = Track()
        forEachBox(reader, trak.payload, trak.end) { box ->
            when (box.type) {
                "tkhd" -> reader.bytes(box.payload, 4)?.let { track.isEnabled = (u8(it, 3) and 0x01) != 0 }
                "mdia" -> forEachBox(reader, box.payload, box.end) { media ->
                    when (media.type) {
                        "hdlr" -> reader.bytes(media.payload, 12)?.let { track.handler = fourCC(it, 8) }
                        "minf" -> forEachBox(reader, media.payload, media.end) { info ->
                            if (info.type == "stbl") readSampleTable(reader, info, track)
                            true
                        }
                    }
                    true
                }
            }
            true
        }
        return track
    }

    private fun readSampleTable(reader: Reader, stbl: Box, track: Track) {
        forEachBox(reader, stbl.payload, stbl.end) { box ->
            when (box.type) {
                "stsd" -> readSampleDescription(reader, box, track)
                "stsz", "stz2" -> reader.bytes(box.payload, 12)?.let { track.sampleCount = uint(it, 8, 4) }
                "stss" -> reader.bytes(box.payload, 8)?.let { track.syncSampleCount = uint(it, 4, 4) }
            }
            true
        }
    }

    private fun readSampleDescription(reader: Reader, stsd: Box, track: Track) {
        val entryStart = stsd.payload + 8
        val header = reader.bytes(entryStart, 8) ?: return
        val entryEnd = minOf(entryStart + uint(header, 0, 4), stsd.end)
        track.sampleEntry = fourCC(header, 4)
        when (track.sampleEntry) {
            in AVC_SAMPLE_ENTRIES, in HEVC_SAMPLE_ENTRIES -> forEachBox(reader, entryStart + 86, entryEnd) { box ->
                if (box.type == "avcC") track.hasAvcC = true
                if (box.type == "hvcC") track.hasHvcC = true
                true
            }
            "mp4a" -> {
                val version = reader.bytes(entryStart + 16, 2) ?: return
                val extraBytes = when (uint(version, 0, 2)) {
                    1L -> 16L
                    2L -> 36L
                    else -> 0L
                }
                track.objectTypeIndication = audioObjectTypeIndication(reader, entryStart + 36 + extraBytes, entryEnd)
            }
            "Opus" -> forEachBox(reader, entryStart + 36, entryEnd) { box ->
                if (box.type == "dOps") track.hasDOps = true
                true
            }
        }
    }

    private fun audioObjectTypeIndication(reader: Reader, start: Long, end: Long): Int? {
        var result: Int? = null
        forEachBox(reader, start, end) { box ->
            when (box.type) {
                "esds" -> result = reader.bytes(box.payload, minOf(box.end - box.payload, ESDS_READ_LIMIT).toInt())
                    ?.let { objectTypeIndication(it) }
                "wave" -> result = audioObjectTypeIndication(reader, box.payload, box.end)
            }
            result == null
        }
        return result
    }

    private fun objectTypeIndication(bytes: ByteArray): Int? {
        if (bytes.size <= 4 || u8(bytes, 4) != 0x03) return null
        var index = descriptorBodyStart(bytes, 4) ?: return null
        if (index + 3 > bytes.size) return null
        val flags = u8(bytes, index + 2)
        index += 3
        if ((flags and 0x80) != 0) index += 2
        if ((flags and 0x40) != 0) {
            if (index >= bytes.size) return null
            index += 1 + u8(bytes, index)
        }
        if ((flags and 0x20) != 0) index += 2
        if (index >= bytes.size || u8(bytes, index) != 0x04) return null
        val body = descriptorBodyStart(bytes, index) ?: return null
        return if (body < bytes.size) u8(bytes, body) else null
    }

    private fun descriptorBodyStart(bytes: ByteArray, tag: Int): Int? {
        var index = tag + 1
        repeat(4) {
            if (index >= bytes.size) return null
            val byte = u8(bytes, index)
            index += 1
            if ((byte and 0x80) == 0) return index
        }
        return null
    }

    private fun u8(bytes: ByteArray, index: Int): Int = bytes[index].toInt() and 0xFF

    private fun uint(bytes: ByteArray, start: Int, count: Int): Long {
        var value = 0L
        for (index in start until start + count) value = (value shl 8) or u8(bytes, index).toLong()
        return value
    }

    private fun fourCC(bytes: ByteArray, start: Int): String = String(bytes, start, 4, Charsets.ISO_8859_1)
}
