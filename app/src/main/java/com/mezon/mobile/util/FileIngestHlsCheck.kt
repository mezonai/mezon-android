package com.mezon.mobile.util

import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object FileIngestHlsCheck {
    private const val MOOV_LIMIT = 64L * 1024 * 1024
    private const val TOP_BOX_LIMIT = 1024
    private const val TRACK_LIMIT = 16
    private const val SAMPLE_LIMIT = 4_194_304u
    private const val SEGMENT_LIMIT = 16_384u
    private const val SEGMENT_SECONDS = 4uL
    private const val MAX_SAMPLE_SIZE = 0x8000_0000u

    private val TOP_BOX_TYPES = setOf("ftyp", "moov", "mdat", "free", "skip", "wide", "uuid", "pnot", "junk", "meta")
    private val FRAGMENT_BOX_TYPES = setOf("moof", "styp", "sidx")
    private val AAC_SAMPLE_RATES = longArrayOf(
        96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350,
    )

    private class Bytes(private val data: ByteArray, private val lower: Int, private val upper: Int) {
        constructor(data: ByteArray) : this(data, 0, data.size)

        val count: Int get() = upper - lower

        operator fun get(index: Int): Int = data[lower + index].toInt() and 0xFF

        fun from(start: Int) = Bytes(data, lower + start, upper)

        fun slice(start: Int, end: Int) = Bytes(data, lower + start, lower + end)

        fun u16(at: Int): UInt = ((get(at) shl 8) or get(at + 1)).toUInt()

        fun u32(at: Int): UInt =
            (get(at).toUInt() shl 24) or (get(at + 1).toUInt() shl 16) or
                (get(at + 2).toUInt() shl 8) or get(at + 3).toUInt()

        fun u64(at: Int): ULong = (u32(at).toULong() shl 32) or u32(at + 4).toULong()

        fun type(at: Int): String = String(data, lower + at, 4, Charsets.ISO_8859_1)
    }

    private class BoxCursor(private var rest: Bytes) {
        fun next(): Pair<String, Bytes>? {
            if (rest.count < 8) return null
            var size = rest.u32(0).toULong()
            var header = 8
            if (size == 1uL) {
                if (rest.count < 16) return null
                size = rest.u64(8)
                header = 16
            } else if (size == 0uL) {
                size = rest.count.toULong()
            }
            if (size < header.toULong() || size > rest.count.toULong()) return null
            val end = size.toInt()
            val box = rest.type(4) to rest.slice(header, end)
            rest = rest.from(end)
            return box
        }
    }

    private class DescriptorStream(var rest: Bytes)

    private class BitReader(private val bytes: Bytes) {
        private var bit = 0

        fun read(bits: Int): Long? {
            var value = 0L
            repeat(bits) {
                if (bit >= bytes.count * 8) return null
                value = (value shl 1) or ((bytes[bit / 8] shr (7 - bit % 8)) and 1).toLong()
                bit += 1
            }
            return value
        }
    }

    private class Trak {
        var tkhd: Bytes? = null
        var mdhd: Bytes? = null
        var hdlr: Bytes? = null
        var elst: Bytes? = null
        var stsd: Bytes? = null
        var stts: Bytes? = null
        var ctts: Bytes? = null
        var stss: Bytes? = null
        var stsz: Bytes? = null
        var stz2: Bytes? = null
        var stsc: Bytes? = null
        var stco: Bytes? = null
        var co64: Bytes? = null
    }

    private class TrackTiming(
        val timescale: UInt,
        val editStart: Long,
        val dts: LongArray,
        val cto: IntArray,
        val sync: BooleanArray,
    )

    private class TrackCheck(val timing: TrackTiming?, val problem: String?)

    suspend fun supportsHls(file: File): Boolean = withContext(Dispatchers.IO) {
        hlsProblem(file) == null
    }

    fun hlsProblem(file: File): String? = try {
        RandomAccessFile(file, "r").use { problem(it) }
    } catch (_: Exception) {
        "unreadable"
    }

    private fun problem(raf: RandomAccessFile): String? {
        val fileSize = raf.length()
        var offset = 0L
        var moovOffset = 0L
        var moovLength = 0L
        var boxCount = 0
        while (moovLength == 0L) {
            if (offset >= fileSize) return "no moov box"
            val left = fileSize - offset
            if (boxCount == TOP_BOX_LIMIT || left < 8) return "bad top-level boxes"
            val head = read(raf, offset, 8) ?: return "unreadable"
            var boxSize = Bytes(head).u32(0).toULong()
            var headerSize = 8uL
            if (boxSize == 1uL) {
                if (left < 16) return "bad top-level boxes"
                val large = read(raf, offset + 8, 8) ?: return "unreadable"
                boxSize = Bytes(large).u64(0)
                headerSize = 16uL
            } else if (boxSize == 0uL) {
                boxSize = left.toULong()
            }
            val type = Bytes(head).type(4)
            if (type in FRAGMENT_BOX_TYPES) return "fragmented MP4"
            if (boxCount == 0 && type !in TOP_BOX_TYPES) return "not an MP4 or MOV file"
            if (boxSize < headerSize || boxSize > left.toULong()) return "box past the end of the file"
            if (type == "moov") {
                moovOffset = offset + headerSize.toLong()
                moovLength = (boxSize - headerSize).toLong()
                if (moovLength == 0L) return "empty moov"
            }
            offset += boxSize.toLong()
            boxCount += 1
        }
        if (moovLength > MOOV_LIMIT) return "moov over 64 MiB"
        val moov = read(raf, moovOffset, moovLength.toInt()) ?: return "unreadable"
        return moovProblem(Bytes(moov), fileSize)
    }

    private fun read(raf: RandomAccessFile, position: Long, count: Int): ByteArray? {
        if (position < 0 || count < 0 || position > raf.length() - count) return null
        val buffer = ByteArray(count)
        raf.seek(position)
        raf.readFully(buffer)
        return buffer
    }

    private fun child(parent: Bytes, type: String): Bytes? {
        val cursor = BoxCursor(parent)
        while (true) {
            val (boxType, body) = cursor.next() ?: return null
            if (boxType == type) return body
        }
    }

    private fun trak(body: Bytes): Trak {
        val boxes = Trak()
        boxes.tkhd = child(body, "tkhd")
        boxes.elst = child(body, "edts")?.let { child(it, "elst") }
        val mdia = child(body, "mdia") ?: return boxes
        boxes.mdhd = child(mdia, "mdhd")
        boxes.hdlr = child(mdia, "hdlr")
        val stbl = child(mdia, "minf")?.let { child(it, "stbl") } ?: return boxes
        boxes.stsd = child(stbl, "stsd")
        boxes.stts = child(stbl, "stts")
        boxes.ctts = child(stbl, "ctts")
        boxes.stss = child(stbl, "stss")
        boxes.stsz = child(stbl, "stsz")
        boxes.stz2 = child(stbl, "stz2")
        boxes.stsc = child(stbl, "stsc")
        boxes.stco = child(stbl, "stco")
        boxes.co64 = child(stbl, "co64")
        return boxes
    }

    private fun timescaleOf(box: Bytes): UInt = when {
        box.count < 24 -> 0u
        box[0] == 1 -> if (box.count >= 32) box.u32(20) else 0u
        else -> box.u32(12)
    }

    private fun table(box: Bytes, entrySize: Int): UInt? {
        if (box.count < 8) return null
        val count = box.u32(4)
        return if (count.toLong() <= ((box.count - 8) / entrySize).toLong()) count else null
    }

    private fun parseAudioSpecificConfig(config: Bytes): Boolean {
        val reader = BitReader(config)
        val objectType = reader.read(5) ?: return false
        if (objectType == 31L && reader.read(6) == null) return false
        val index = reader.read(4) ?: return false
        val rate = when {
            index == 15L -> reader.read(24) ?: return false
            index < AAC_SAMPLE_RATES.size -> AAC_SAMPLE_RATES[index.toInt()]
            else -> return false
        }
        return reader.read(4) != null && rate > 0
    }

    private fun descriptor(stream: DescriptorStream): Pair<Int, Bytes>? {
        val source = stream.rest
        if (source.count < 2) return null
        val tag = source[0]
        var index = 1
        var length = 0
        var lengthBytes = 0
        while (true) {
            if (index >= source.count || lengthBytes == 4) return null
            val byte = source[index]
            index += 1
            lengthBytes += 1
            length = (length shl 7) or (byte and 0x7f)
            if ((byte and 0x80) == 0) break
        }
        if (length > source.count - index) return null
        val body = source.slice(index, index + length)
        stream.rest = source.from(index + length)
        return tag to body
    }

    private fun audioSpecificConfig(esds: Bytes): Bytes? {
        if (esds.count < 4) return null
        val (tag, elementary) = descriptor(DescriptorStream(esds.from(4))) ?: return null
        if (tag != 0x03 || elementary.count < 3) return null
        val flags = elementary[2]
        var skip = if ((flags and 0x80) != 0) 5 else 3
        if ((flags and 0x40) != 0) {
            if (elementary.count <= skip) return null
            skip += 1 + elementary[skip]
        }
        if ((flags and 0x20) != 0) skip += 2
        if (skip > elementary.count) return null
        val rest = DescriptorStream(elementary.from(skip))
        while (true) {
            val (descriptorTag, body) = descriptor(rest) ?: return null
            if (descriptorTag != 0x04) continue
            if (body.count < 13 || body[0] != 0x40) return null
            val nested = DescriptorStream(body.from(13))
            while (true) {
                val (infoTag, info) = descriptor(nested) ?: return null
                if (infoTag == 0x05) return info
            }
        }
    }

    private fun videoEntryProblem(type: String, entry: Bytes, width: UInt, height: UInt): String? {
        val isAvc = type == "avc1" || type == "avc3"
        val isHevc = type == "hvc1" || type == "hev1"
        if (!isAvc && !isHevc) return "video is not H.264 or HEVC"
        if (entry.count < 86) return "short video sample entry"
        val config = child(entry.from(86), if (isAvc) "avcC" else "hvcC")
        if (config == null || config.count < (if (isAvc) 4 else 23) || config[0] != 1) {
            return if (isAvc) "no avcC" else "no hvcC"
        }
        var pictureWidth = width
        var pictureHeight = height
        if (pictureWidth == 0u || pictureHeight == 0u) {
            pictureWidth = entry.u16(32)
            pictureHeight = entry.u16(34)
        }
        return if (pictureWidth > 0u && pictureHeight > 0u) null else "no picture size"
    }

    private fun audioEntryProblem(type: String, entry: Bytes): String? {
        if (entry.count < 36) return "short audio sample entry"
        if (type == "Opus") {
            val dOps = child(entry.from(36), "dOps")
            return if (dOps != null && dOps.count >= 11) null else "no dOps"
        }
        if (type != "mp4a") return "audio is not AAC or Opus"
        val version = entry.u16(16)
        val boxesStart = when (version) {
            1u -> 52
            2u -> 72
            else -> 36
        }
        if (version > 2u || entry.count < boxesStart) return "unknown sound sample description"
        val rest = entry.from(boxesStart)
        val esds = child(rest, "esds") ?: child(rest, "wave")?.let { child(it, "esds") } ?: return "no esds"
        val config = audioSpecificConfig(esds)
        if (config == null || config.count == 0 || config.count > 64 || !parseAudioSpecificConfig(config)) {
            return "audio is not AAC"
        }
        return null
    }

    private fun sampleSize(table: Bytes, fixed: UInt, field: Int, index: Int): UInt = when {
        fixed != 0u -> fixed
        field == 32 -> table.u32(4 * index)
        field == 16 -> table.u16(2 * index)
        field == 8 -> table[index].toUInt()
        else -> ((table[index / 2] shr (if (index % 2 == 0) 4 else 0)) and 15).toUInt()
    }

    private fun rejected(reason: String) = TrackCheck(null, reason)

    private fun checkTrack(boxes: Trak, video: Boolean, movieTimescale: UInt, fileSize: Long): TrackCheck {
        val timescale = boxes.mdhd?.let { timescaleOf(it) } ?: 0u
        val tkhd = boxes.tkhd
        val stsd = boxes.stsd
        if (tkhd == null || stsd == null || timescale == 0u ||
            tkhd.count < 84 || (tkhd[0] == 1 && tkhd.count < 96) || stsd.count < 16
        ) {
            return rejected("track without timescale, tkhd or stsd")
        }
        val matrixOffset = if (tkhd[0] == 1) 52 else 40
        val width = tkhd.u32(matrixOffset + 36) shr 16
        val height = tkhd.u32(matrixOffset + 40) shr 16

        val entryLength = stsd.u32(8)
        if (stsd.u32(4) == 0u || entryLength < 8u || entryLength.toLong() > (stsd.count - 8).toLong()) {
            return rejected("bad stsd")
        }
        val entry = stsd.slice(8, 8 + entryLength.toInt())
        val type = entry.type(4)
        val entryProblem = if (video) videoEntryProblem(type, entry, width, height) else audioEntryProblem(type, entry)
        if (entryProblem != null) return rejected(entryProblem)

        val sizes: Bytes
        val fixed: UInt
        val field: Int
        val count: UInt
        val stsz = boxes.stsz
        val stz2 = boxes.stz2
        if (stsz != null) {
            if (stsz.count < 12) return rejected("short stsz")
            sizes = stsz
            fixed = stsz.u32(4)
            field = 32
            count = stsz.u32(8)
        } else if (stz2 != null && stz2.count >= 12) {
            val fieldSize = stz2[7]
            if (fieldSize != 4 && fieldSize != 8 && fieldSize != 16) return rejected("bad stz2 field size")
            sizes = stz2
            fixed = 0u
            field = fieldSize
            count = stz2.u32(8)
        } else {
            return rejected("no stsz")
        }
        if (count == 0u) {
            return if (video) {
                rejected("video track without samples")
            } else {
                TrackCheck(TrackTiming(timescale, 0L, LongArray(0), IntArray(0), BooleanArray(0)), null)
            }
        }
        if (count > SAMPLE_LIMIT) return rejected("over 4 million samples in a track")
        if (fixed == 0u && count.toULong() * field.toULong() > (sizes.count - 12).toULong() * 8uL) {
            return rejected("stsz shorter than its count")
        }
        val sizeTable = sizes.from(12)
        val sampleCount = count.toInt()
        for (index in 0 until sampleCount) {
            if (sampleSize(sizeTable, fixed, field, index) >= MAX_SAMPLE_SIZE) return rejected("sample over 2 GiB")
        }

        val dts = LongArray(if (video) sampleCount else 0)
        val cto = IntArray(if (video) sampleCount else 0)
        val sync = BooleanArray(if (video) sampleCount else 0)

        val stts = boxes.stts ?: return rejected("bad stts")
        val sttsCount = table(stts, 8) ?: return rejected("bad stts")
        var timed = 0
        var time = 0L
        for (entryIndex in 0 until sttsCount.toInt()) {
            if (timed >= sampleCount) break
            val repeats = stts.u32(8 + 8 * entryIndex).toLong()
            val delta = stts.u32(12 + 8 * entryIndex).toLong()
            var repeated = 0L
            while (repeated < repeats && timed < sampleCount) {
                if (video) dts[timed] = time
                time += delta
                timed += 1
                repeated += 1
            }
        }
        if (timed != sampleCount) return rejected("stts covers fewer samples than stsz")
        val endDts = time

        val ctts = boxes.ctts
        if (ctts != null) {
            val cttsCount = table(ctts, 8) ?: return rejected("bad ctts")
            if (video) {
                var shifted = 0
                for (entryIndex in 0 until cttsCount.toInt()) {
                    if (shifted >= sampleCount) break
                    val repeats = ctts.u32(8 + 8 * entryIndex).toLong()
                    val offset = ctts.u32(12 + 8 * entryIndex).toInt()
                    var repeated = 0L
                    while (repeated < repeats && shifted < sampleCount) {
                        cto[shifted] = offset
                        shifted += 1
                        repeated += 1
                    }
                }
            }
        }

        val stss = boxes.stss
        if (stss != null) {
            val stssCount = table(stss, 4) ?: return rejected("bad stss")
            for (entryIndex in 0 until stssCount.toInt()) {
                val number = stss.u32(8 + 4 * entryIndex)
                if (number == 0u || number > count) return rejected("stss names a sample that does not exist")
                if (video) sync[number.toInt() - 1] = true
            }
        } else if (video) {
            sync.fill(true)
        }

        val isWide = boxes.stco == null
        val chunkOffsets = if (isWide) boxes.co64 else boxes.stco
        val chunkCount = chunkOffsets?.let { table(it, if (isWide) 8 else 4) }
        val stsc = boxes.stsc
        val stscCount = stsc?.let { table(it, 12) }
        if (chunkOffsets == null || chunkCount == null || stsc == null || stscCount == null || stscCount == 0u) {
            return rejected("bad stsc, stco or co64")
        }
        val chunks = chunkCount.toLong()
        var placed = 0
        for (entryIndex in 0 until stscCount.toInt()) {
            if (placed >= sampleCount) break
            val base = 8 + 12 * entryIndex
            val first = stsc.u32(base).toLong()
            val perChunk = stsc.u32(base + 4).toLong()
            val next = if (entryIndex + 1 < stscCount.toInt()) stsc.u32(base + 12).toLong() else chunks + 1
            if (first == 0L || (entryIndex == 0 && first != 1L) || next <= first || next > chunks + 1 || perChunk == 0L) {
                return rejected("bad stsc")
            }
            if (stsc.u32(base + 8) != 1u) return rejected("more than one sample description")
            var chunk = first
            while (chunk < next && placed < sampleCount) {
                val chunkIndex = (chunk - 1).toInt()
                var offset = if (isWide) chunkOffsets.u64(8 + 8 * chunkIndex) else chunkOffsets.u32(8 + 4 * chunkIndex).toULong()
                var inChunk = 0L
                while (inChunk < perChunk && placed < sampleCount) {
                    val size = sampleSize(sizeTable, fixed, field, placed).toULong()
                    if (offset > fileSize.toULong() || size > fileSize.toULong() - offset) {
                        return rejected("sample past the end of the file")
                    }
                    offset += size
                    placed += 1
                    inChunk += 1
                }
                chunk += 1
            }
        }
        if (placed != sampleCount) return rejected("chunks hold fewer samples than stsz")

        var editStart = 0L
        val elst = boxes.elst
        if (elst != null) {
            val isVersion1 = elst.count > 0 && elst[0] == 1
            val entrySize = if (isVersion1) 20 else 12
            val elstCount = table(elst, entrySize) ?: return rejected("bad elst")
            var delay = 0.0
            for (entryIndex in 0 until elstCount.toInt()) {
                val base = 8 + entryIndex * entrySize
                val duration = if (isVersion1) elst.u64(base) else elst.u32(base).toULong()
                val media = if (isVersion1) elst.u64(base + 8).toLong() else elst.u32(base + 4).toInt().toLong()
                if (media == -1L) {
                    delay += duration.toDouble()
                    continue
                }
                if (media < 0 || media.toULong() > endDts.toULong()) return rejected("edit outside the track")
                editStart = media
                break
            }
            if (movieTimescale > 0u && delay > 0 &&
                delay * timescale.toDouble() / movieTimescale.toDouble() > UInt.MAX_VALUE.toDouble()
            ) {
                return rejected("edit delay too long")
            }
        }

        if (video) {
            var lowest = 0L
            var highest = 0L
            for (offset in cto) {
                lowest = minOf(lowest, offset.toLong())
                highest = maxOf(highest, offset.toLong())
            }
            if (highest - lowest > Int.MAX_VALUE) return rejected("composition offsets out of range")
        }
        return TrackCheck(TrackTiming(timescale, editStart, dts, cto, sync), null)
    }

    private fun moovProblem(moov: Bytes, fileSize: Long): String? {
        var video: Trak? = null
        var videoEnabled = false
        var audio: Trak? = null
        var audioEnabled = false
        var movieTimescale = 0u
        var trakCount = 0
        val cursor = BoxCursor(moov)
        while (true) {
            val (type, body) = cursor.next() ?: break
            if (type == "mvex") return "fragmented MP4"
            if (type == "mvhd") movieTimescale = timescaleOf(body)
            if (type != "trak") continue
            trakCount += 1
            if (trakCount > TRACK_LIMIT) return "more than 16 tracks"
            val boxes = trak(body)
            val hdlr = boxes.hdlr
            val tkhd = boxes.tkhd
            if (hdlr == null || tkhd == null || hdlr.count < 12 || tkhd.count < 4) continue
            val enabled = (tkhd[3] and 1) != 0
            when (hdlr.type(8)) {
                "vide" -> if (video == null || (enabled && !videoEnabled)) {
                    video = boxes
                    videoEnabled = enabled
                }
                "soun" -> if (audio == null || (enabled && !audioEnabled)) {
                    audio = boxes
                    audioEnabled = enabled
                }
            }
        }
        val videoBoxes = video ?: return "no video track"
        val videoCheck = checkTrack(videoBoxes, true, movieTimescale, fileSize)
        val timing = videoCheck.timing ?: return videoCheck.problem
        audio?.let { checkTrack(it, false, movieTimescale, fileSize).problem }?.let { return it }

        val sampleCount = timing.dts.size
        var first = (0 until sampleCount).firstOrNull { timing.sync[it] } ?: return "no video keyframe"
        val editStart = timing.editStart
        fun presentationTime(index: Int): Long = timing.dts[index] + timing.cto[index]
        if (presentationTime(first) < editStart) {
            while (first < sampleCount && !(timing.sync[first] && presentationTime(first) >= editStart)) first += 1
        }
        if (first >= sampleCount) return "no keyframe from the video edit's start on"
        val step = SEGMENT_SECONDS * timing.timescale.toULong()
        var segments = 1u
        var segmentStart = first
        for (index in first + 1 until sampleCount) {
            if (timing.sync[index] && (timing.dts[index] - timing.dts[segmentStart]).toULong() >= step) {
                segments += 1u
                segmentStart = index
            }
        }
        return if (segments > SEGMENT_LIMIT) "over 16384 segments" else null
    }
}
