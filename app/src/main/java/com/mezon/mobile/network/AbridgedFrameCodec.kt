package com.mezon.mobile.network

sealed interface AbridgedFrame {
    data class Pong(val cid: Int) : AbridgedFrame
    class Raw(val cid: Int, val responseCode: Int, val fin: Boolean, val payload: ByteArray) : AbridgedFrame
    class Realtime(val payload: ByteArray) : AbridgedFrame
}

sealed interface AbridgedFrameStep {
    object NeedMore : AbridgedFrameStep
    data class Reset(val reason: String) : AbridgedFrameStep
    class Frame(val consumed: Int, val frame: AbridgedFrame) : AbridgedFrameStep
}

sealed interface AbridgedParsedEvent {
    data class Pong(val cid: Int) : AbridgedParsedEvent
    class ApiResponse(val cid: Int, val code: Int, val payload: ByteArray) : AbridgedParsedEvent
    class Realtime(val payload: ByteArray) : AbridgedParsedEvent
}

sealed interface AbridgedIngestResult {
    class Events(val events: List<AbridgedParsedEvent>) : AbridgedIngestResult
    data class Failure(val reason: String) : AbridgedIngestResult
}

object AbridgedFrameCodec {

    const val MAX_REALTIME_FRAME_LEN = 1 shl 20
    const val MAX_API_RESPONSE_LEN = 16 shl 20
    const val RESPONSE_CODE_TOO_LARGE = 0xFFFF

    private const val PREFIX_HANDSHAKE = 0xEF
    private const val PREFIX_EXTENDED = 0x7F
    private const val PREFIX_RAW = 0xFF
    private const val RAW_HEADER_LENGTH = 11
    private const val CODE_FIN = 0xFF

    fun frameHandshake(credential: String): ByteArray {
        var payload = credential.toByteArray(Charsets.UTF_8)
        val padding = (4 - (payload.size % 4)) % 4
        if (padding > 0) payload += ByteArray(padding)
        val lenDiv4 = payload.size / 4
        val header = if (lenDiv4 < 127) {
            byteArrayOf(PREFIX_HANDSHAKE.toByte(), lenDiv4.toByte())
        } else {
            byteArrayOf(
                PREFIX_HANDSHAKE.toByte(), PREFIX_EXTENDED.toByte(),
                (lenDiv4 and 0xFF).toByte(), ((lenDiv4 ushr 8) and 0xFF).toByte(), ((lenDiv4 ushr 16) and 0xFF).toByte()
            )
        }
        return header + payload
    }

    fun framePing(cid: Int): ByteArray =
        byteArrayOf(0x00, ((cid ushr 8) and 0xFF).toByte(), (cid and 0xFF).toByte())

    fun frameEnvelope(payload: ByteArray): ByteArray {
        var padded = payload
        val padding = (4 - (padded.size % 4)) % 4
        if (padding > 0) padded += ByteArray(padding)
        val lenDiv4 = padded.size / 4
        val header = if (lenDiv4 < 127) {
            byteArrayOf(lenDiv4.toByte())
        } else {
            byteArrayOf(
                PREFIX_EXTENDED.toByte(),
                (lenDiv4 and 0xFF).toByte(), ((lenDiv4 ushr 8) and 0xFF).toByte(), ((lenDiv4 ushr 16) and 0xFF).toByte()
            )
        }
        return header + padded
    }

    fun looksLikeHttp(chunk: ByteArray): Boolean {
        fun startsWith(prefix: String): Boolean {
            val p = prefix.toByteArray(Charsets.US_ASCII)
            if (chunk.size < p.size) return false
            for (i in p.indices) if (chunk[i] != p[i]) return false
            return true
        }
        return startsWith("HTTP/") || startsWith("GET ") || startsWith("POST ")
    }

    private fun readVarint(bytes: ByteArray, offset: Int): Pair<Long, Int>? {
        var value = 0L
        var shift = 0
        var i = offset
        while (i < bytes.size) {
            if (shift >= 64) return null
            val b = bytes[i].toInt() and 0xFF
            value = value or ((b.toLong() and 0x7F) shl shift)
            if ((b and 0x80) == 0) return Pair(value, i - offset + 1)
            shift += 7
            i += 1
        }
        return null
    }

    fun protobufMessageLength(bytes: ByteArray): Int? {
        var pos = 0
        while (pos < bytes.size) {
            val tag = readVarint(bytes, pos) ?: return null
            val field = tag.first ushr 3
            val wire = (tag.first and 7L).toInt()
            if (field == 0L || wire == 3 || wire == 4 || wire == 6 || wire == 7) {
                return pos
            }
            val valueStart = pos + tag.second
            val valueEnd: Int
            when (wire) {
                0 -> {
                    if (valueStart > bytes.size) return null
                    val v = readVarint(bytes, valueStart) ?: return null
                    valueEnd = valueStart + v.second
                }
                1 -> valueEnd = valueStart + 8
                5 -> valueEnd = valueStart + 4
                2 -> {
                    if (valueStart > bytes.size) return null
                    val lenV = readVarint(bytes, valueStart) ?: return null
                    if (lenV.first > MAX_REALTIME_FRAME_LEN.toLong()) {
                        return pos
                    }
                    valueEnd = valueStart + lenV.second + lenV.first.toInt()
                }
                else -> return pos
            }
            if (valueEnd > bytes.size) return null
            pos = valueEnd
        }
        return pos
    }

    fun trimRealtimePayload(framed: ByteArray): ByteArray {
        val end = protobufMessageLength(framed) ?: framed.size
        return framed.copyOfRange(0, end)
    }

    fun decodeFrame(buffer: ByteArray, start: Int): AbridgedFrameStep {
        val available = buffer.size - start
        if (available < 1) return AbridgedFrameStep.NeedMore
        val first = buffer[start].toInt() and 0xFF

        when (first) {
            0x00 -> {
                if (available < 3) return AbridgedFrameStep.NeedMore
                val cid = ((buffer[start + 1].toInt() and 0xFF) shl 8) or (buffer[start + 2].toInt() and 0xFF)
                return AbridgedFrameStep.Frame(3, AbridgedFrame.Pong(cid))
            }

            PREFIX_RAW -> {
                if (available < RAW_HEADER_LENGTH) return AbridgedFrameStep.NeedMore
                val cid = ((buffer[start + 1].toInt() and 0xFF) shl 8) or (buffer[start + 2].toInt() and 0xFF)
                val code = ((buffer[start + 3].toInt() and 0xFF) shl 24) or
                    ((buffer[start + 4].toInt() and 0xFF) shl 16) or
                    ((buffer[start + 5].toInt() and 0xFF) shl 8) or
                    (buffer[start + 6].toInt() and 0xFF)
                val len = ((buffer[start + 7].toInt() and 0xFF) shl 24) or
                    ((buffer[start + 8].toInt() and 0xFF) shl 16) or
                    ((buffer[start + 9].toInt() and 0xFF) shl 8) or
                    (buffer[start + 10].toInt() and 0xFF)
                if (len > MAX_API_RESPONSE_LEN) {
                    return AbridgedFrameStep.Reset("raw frame length too large")
                }
                val total = RAW_HEADER_LENGTH + len
                if (available < total) return AbridgedFrameStep.NeedMore
                val responseCode = (code ushr 16) and 0xFFFF
                val fin = (code and 0xFFFF) == CODE_FIN
                val payload = buffer.copyOfRange(start + RAW_HEADER_LENGTH, start + total)
                return AbridgedFrameStep.Frame(total, AbridgedFrame.Raw(cid, responseCode, fin, payload))
            }

            0x82 -> {
                if (available < 2) return AbridgedFrameStep.NeedMore
                val b1 = buffer[start + 1].toInt() and 0xFF
                if ((b1 and 0x80) != 0) return AbridgedFrameStep.Reset("masked websocket frame")
                val len7 = b1
                val header: Int
                val payloadLen: Int
                when {
                    len7 < 126 -> {
                        header = 2
                        payloadLen = len7
                    }
                    len7 == 126 -> {
                        if (available < 4) return AbridgedFrameStep.NeedMore
                        header = 4
                        payloadLen = ((buffer[start + 2].toInt() and 0xFF) shl 8) or (buffer[start + 3].toInt() and 0xFF)
                    }
                    else -> {
                        if (available < 10) return AbridgedFrameStep.NeedMore
                        var len64 = 0L
                        for (i in 0 until 8) {
                            len64 = (len64 shl 8) or (buffer[start + 2 + i].toLong() and 0xFF)
                        }
                        if (len64 > MAX_REALTIME_FRAME_LEN.toLong()) {
                            return AbridgedFrameStep.Reset("websocket frame length too large")
                        }
                        header = 10
                        payloadLen = len64.toInt()
                    }
                }
                if (payloadLen > MAX_REALTIME_FRAME_LEN) {
                    return AbridgedFrameStep.Reset("websocket frame length too large")
                }
                val total = header + payloadLen
                if (available < total) return AbridgedFrameStep.NeedMore
                val payload = buffer.copyOfRange(start + header, start + total)
                return AbridgedFrameStep.Frame(total, AbridgedFrame.Realtime(payload))
            }

            PREFIX_EXTENDED -> {
                if (available < 4) return AbridgedFrameStep.NeedMore
                val lenDiv4 = (buffer[start + 1].toInt() and 0xFF) or
                    ((buffer[start + 2].toInt() and 0xFF) shl 8) or
                    ((buffer[start + 3].toInt() and 0xFF) shl 16)
                val payloadLen = lenDiv4 * 4
                if (payloadLen > MAX_REALTIME_FRAME_LEN) {
                    return AbridgedFrameStep.Reset("extended frame length too large")
                }
                val total = 4 + payloadLen
                if (available < total) return AbridgedFrameStep.NeedMore
                val framed = buffer.copyOfRange(start + 4, start + total)
                return AbridgedFrameStep.Frame(total, AbridgedFrame.Realtime(trimRealtimePayload(framed)))
            }

            else -> {
                if (first in 0x01..0x7E) {
                    val total = 1 + first * 4
                    if (available < total) return AbridgedFrameStep.NeedMore
                    val framed = buffer.copyOfRange(start + 1, start + total)
                    return AbridgedFrameStep.Frame(total, AbridgedFrame.Realtime(trimRealtimePayload(framed)))
                }
                return AbridgedFrameStep.Reset("unexpected lead byte 0x" + Integer.toHexString(first))
            }
        }
    }
}

class AbridgedStreamParser {

    private var buffer = ByteArray(0)
    private val streams = HashMap<Int, ByteArray>()
    private var sawValidFrame = false

    fun reset() {
        buffer = ByteArray(0)
        streams.clear()
        sawValidFrame = false
    }

    fun ingest(chunk: ByteArray): AbridgedIngestResult {
        if (chunk.isEmpty()) return AbridgedIngestResult.Events(emptyList())
        if (!sawValidFrame && buffer.isEmpty() && AbridgedFrameCodec.looksLikeHttp(chunk)) {
            return AbridgedIngestResult.Failure("server spoke HTTP instead of abridged TCP")
        }
        buffer += chunk

        val events = ArrayList<AbridgedParsedEvent>()
        var start = 0

        while (true) {
            if (start >= buffer.size) {
                buffer = ByteArray(0)
                return AbridgedIngestResult.Events(events)
            }
            when (val step = AbridgedFrameCodec.decodeFrame(buffer, start)) {
                is AbridgedFrameStep.NeedMore -> {
                    buffer = buffer.copyOfRange(start, buffer.size)
                    return AbridgedIngestResult.Events(events)
                }
                is AbridgedFrameStep.Reset -> {
                    buffer = ByteArray(0)
                    streams.clear()
                    return AbridgedIngestResult.Failure(step.reason)
                }
                is AbridgedFrameStep.Frame -> {
                    start += step.consumed
                    sawValidFrame = true
                    when (val frame = step.frame) {
                        is AbridgedFrame.Pong -> events.add(AbridgedParsedEvent.Pong(frame.cid))
                        is AbridgedFrame.Raw -> {
                            if (frame.fin) {
                                val prev = streams.remove(frame.cid)
                                val body = if (prev != null) prev + frame.payload else frame.payload
                                events.add(AbridgedParsedEvent.ApiResponse(frame.cid, frame.responseCode, body))
                            } else {
                                val prev = streams[frame.cid] ?: ByteArray(0)
                                val pending = prev + frame.payload
                                if (pending.size > AbridgedFrameCodec.MAX_API_RESPONSE_LEN) {
                                    streams.remove(frame.cid)
                                    events.add(
                                        AbridgedParsedEvent.ApiResponse(
                                            frame.cid, AbridgedFrameCodec.RESPONSE_CODE_TOO_LARGE, ByteArray(0)
                                        )
                                    )
                                } else {
                                    streams[frame.cid] = pending
                                }
                            }
                        }
                        is AbridgedFrame.Realtime -> events.add(AbridgedParsedEvent.Realtime(frame.payload))
                    }
                }
            }
        }
    }
}
