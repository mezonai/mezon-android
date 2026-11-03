package com.mezon.mobile.util

import android.content.Context
import android.util.Base64
import com.mezon.mobile.BuildConfig
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

object FileIngest {
    const val CHUNK_SIZE = 8L * 1024 * 1024
    const val MAX_SIZE = 1024L * 1024 * 1024

    private val VIDEO_EXTENSIONS = setOf(
        "3g2", "3gp", "avi", "flv", "m2ts", "m4v", "mkv", "mov", "mp4", "mpeg", "mpg", "mts", "ogv", "qt", "webm", "wmv",
    )
    private val ORIGIN_REGEX = Regex("^https?://[^/?#]+$", RegexOption.IGNORE_CASE)
    private val PRESIGNED_REGEX = Regex("^https?://([^/?#]+)(/.*)$", RegexOption.IGNORE_CASE)
    private val AMZ_DATE_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }

    fun baseUrls(raw: String = BuildConfig.MEZON_FILE_INGEST): Map<String, String> {
        val bases = HashMap<String, String>()
        for (pair in raw.split(',')) {
            val at = pair.indexOf('=')
            if (at <= 0) continue
            val host = pair.substring(0, at).trim().lowercase()
            val base = pair.substring(at + 1).trim().trimEnd('/')
            if (host.isNotEmpty() && ORIGIN_REGEX.matches(base)) bases[host] = base
        }
        return bases
    }

    fun isCandidate(mimeType: String, filename: String, size: Long): Boolean {
        val dot = filename.lastIndexOf('.')
        val isVideo = mimeType.lowercase().startsWith("video") ||
            (dot > 0 && filename.substring(dot + 1).lowercase() in VIDEO_EXTENSIONS)
        return isVideo && size in 1..MAX_SIZE && baseUrls().isNotEmpty()
    }

    fun ingestUrl(presignedUrl: String): String? {
        val match = PRESIGNED_REGEX.matchEntire(presignedUrl) ?: return null
        val host = match.groupValues[1].lowercase()
        val bases = baseUrls()
        val base = bases[host] ?: bases[host.substringBefore(':')] ?: return null
        return base + match.groupValues[2]
    }

    fun moovAfterMdat(file: File): Long {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val size = raf.length()
                val header = ByteArray(16)
                var offset = 0L
                var sawMdat = false
                while (offset + 8 <= size) {
                    raf.seek(offset)
                    val read = raf.read(header, 0, header.size)
                    if (read < 8) return -1L
                    var box = readUnsigned(header, 0, 4)
                    val type = String(header, 4, 4, Charsets.ISO_8859_1)
                    if (box == 1L) {
                        if (read < 16) return -1L
                        box = readUnsigned(header, 8, 8)
                    } else if (box == 0L) {
                        box = size - offset
                    }
                    if (type == "moov") return if (sawMdat) offset else -1L
                    if (type == "mdat") sawMdat = true
                    if (box < 8 || box > size - offset) return -1L
                    offset += box
                }
                -1L
            }
        } catch (_: IOException) {
            -1L
        }
    }

    fun nextChunk(size: Long, received: List<LongArray>, moov: Long): LongRange? {
        val order = if (moov > 0) listOf(moov to size - 1, 0L to moov - 1) else listOf(0L to size - 1)
        val ranges = received.filter { it.size == 2 }.sortedBy { it[0] }
        for ((first, last) in order) {
            var start = first
            for (range in ranges) {
                if (range[0] > start) break
                start = maxOf(start, range[1] + 1)
            }
            if (start > last) continue
            val followingStart = ranges.firstOrNull { it[0] > start }?.get(0)
            val end = minOf(start + CHUNK_SIZE - 1, last, followingStart?.minus(1) ?: last)
            return start..end
        }
        return null
    }

    fun isPresignedUrlExpired(url: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val query = url.substringAfter('?', "")
        if (query.isEmpty()) return false
        var issuedAt: String? = null
        var expiresIn: Long? = null
        for (item in query.split('&')) {
            val eq = item.indexOf('=')
            if (eq <= 0) continue
            when (item.substring(0, eq)) {
                "X-Amz-Date" -> issuedAt = item.substring(eq + 1)
                "X-Amz-Expires" -> expiresIn = item.substring(eq + 1).toLongOrNull()
            }
        }
        val issued = issuedAt?.let { runCatching { AMZ_DATE_FORMAT.get()?.parse(it)?.time }.getOrNull() } ?: return false
        val ttlSeconds = expiresIn?.takeIf { it > 0 } ?: return false
        return nowMs >= issued + ttlSeconds * 1000
    }

    private fun readUnsigned(bytes: ByteArray, start: Int, count: Int): Long {
        var value = 0L
        for (i in start until start + count) {
            value = (value shl 8) or (bytes[i].toLong() and 0xff)
        }
        return value
    }
}

object FileIngestPlaylist {
    const val PROBE_INTERVAL_MS = 3_000L

    private const val OUTPUT_PREFIX = "hls/"
    private const val INDEX_NAME = "index.m3u8"
    private const val PLAYLIST_SUFFIX = ".m3u8"
    private const val PLAYLIST_TAG = "#EXTM3U"
    private const val MAX_REMEMBERED = 512
    private val ATTACHMENT_REGEX = Regex("^(https?://[^/?#]+)/(.+)$", RegexOption.IGNORE_CASE)

    private val playableByUrl = ConcurrentHashMap<String, String>()
    private val probesInFlight = ConcurrentHashMap.newKeySet<String>()
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    fun indexPlaylistUrl(attachmentUrl: String): String? {
        if (attachmentUrl.contains('#')) return null
        val match = ATTACHMENT_REGEX.matchEntire(attachmentUrl.substringBefore('?')) ?: return null
        val origin = match.groupValues[1]
        val key = match.groupValues[2]
        val file = key.substringAfterLast('/')
        if (file.isEmpty() || key.startsWith(OUTPUT_PREFIX) || file.lowercase().endsWith(PLAYLIST_SUFFIX)) return null
        return "$origin/$OUTPUT_PREFIX$key/$INDEX_NAME"
    }

    fun isPlayable(attachmentUrl: String): Boolean = playableByUrl.containsKey(attachmentUrl)

    suspend fun resolve(attachmentUrl: String, coalescing: Boolean = false): String? {
        playableByUrl[attachmentUrl]?.let { return it }
        val playlist = indexPlaylistUrl(attachmentUrl) ?: return null
        if (coalescing && !probesInFlight.add(attachmentUrl)) return null
        try {
            val probeUrl = "$playlist?probe=${System.currentTimeMillis() / PROBE_INTERVAL_MS}"
            val isPlaylist = runInterruptible(Dispatchers.IO) {
                try {
                    client.newCall(Request.Builder().url(probeUrl).get().build()).execute().use { response ->
                        response.isSuccessful && response.body?.string().orEmpty().startsWith(PLAYLIST_TAG)
                    }
                } catch (_: IOException) {
                    false
                }
            }
            if (!isPlaylist) return null
            if (playableByUrl.size >= MAX_REMEMBERED) playableByUrl.clear()
            playableByUrl[attachmentUrl] = playlist
            return playlist
        } finally {
            if (coalescing) probesInFlight.remove(attachmentUrl)
        }
    }
}

class FileIngestException(val reason: Reason, val statusCode: Int = 0) :
    IOException("File ingest failed: $reason $statusCode") {
    enum class Reason { BUSY, UNREACHABLE, UPLOAD_LOST, UNREADABLE_FILE, DIRECT_UPLOAD_FAILED, REJECTED }
}

object FileIngestUploader {
    private const val STATUS_POLL_MS = 2_000L
    private val RETRY_DELAYS_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000, 30_000, 30_000, 30_000)
    private val RETRY_STATUS_CODES = setOf(408, 500, 503)

    private val activeUploads = ConcurrentHashMap.newKeySet<String>()
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.MINUTES)
            .build()
    }

    private class Status(val received: List<LongArray>, val stored: Boolean)
    private class Answer(val code: Int, val body: String)

    suspend fun upload(
        file: File,
        presignedUrl: String,
        ingestUrl: String,
        onProgress: (Float) -> Unit,
        onSent: () -> Unit,
        directPut: suspend () -> Unit,
    ) {
        if (!activeUploads.add(ingestUrl)) throw FileIngestException(FileIngestException.Reason.BUSY)
        try {
            runUpload(file, presignedUrl, ingestUrl, onProgress, onSent, directPut)
        } finally {
            activeUploads.remove(ingestUrl)
        }
    }

    private suspend fun runUpload(
        file: File,
        presignedUrl: String,
        ingestUrl: String,
        onProgress: (Float) -> Unit,
        onSent: () -> Unit,
        directPut: suspend () -> Unit,
    ) {
        val size = file.length()
        val moov = FileIngest.moovAfterMdat(file)
        var status: Status? = null
        var retries = 0
        var announcedSent = false

        fun accept(answer: Answer) {
            val parsed = parseStatus(answer.body)
                ?: throw FileIngestException(FileIngestException.Reason.REJECTED, answer.code)
            status = parsed
            onProgress(heldFraction(parsed, size))
        }

        fun restart() {
            if (FileIngest.isPresignedUrlExpired(presignedUrl)) {
                throw FileIngestException(FileIngestException.Reason.UPLOAD_LOST)
            }
            status = null
            announcedSent = false
            onProgress(0f)
        }

        getStatus(ingestUrl)?.takeIf { it.code == 200 }?.let { runCatching { accept(it) } }

        while (true) {
            val chunk = FileIngest.nextChunk(size, status?.received ?: emptyList(), moov)
            if (chunk == null && !announcedSent) {
                announcedSent = true
                onSent()
            }
            if (chunk == null && status?.stored == true) return

            val answer = if (chunk != null) {
                put(file, chunk, size, ingestUrl)
            } else {
                delay(STATUS_POLL_MS)
                getStatus(ingestUrl)
            }
            if (answer != null && answer.code == 200) {
                accept(answer)
                retries = 0
                continue
            }
            val code = answer?.code
            if (chunk != null && code != null && (code == 403 || (status == null && (code == 503 || code == 413)))) {
                try {
                    directPut()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    throw FileIngestException(FileIngestException.Reason.DIRECT_UPLOAD_FAILED)
                }
                return
            }
            if (chunk == null && code == 404) {
                restart()
                continue
            }
            if (code != null && code !in RETRY_STATUS_CODES) {
                throw FileIngestException(FileIngestException.Reason.REJECTED, code)
            }

            var recovered = false
            while (!recovered) {
                if (retries >= RETRY_DELAYS_MS.size) throw FileIngestException(FileIngestException.Reason.UNREACHABLE)
                delay(RETRY_DELAYS_MS[retries])
                retries += 1
                val retryAnswer = getStatus(ingestUrl)
                when {
                    retryAnswer != null && retryAnswer.code == 200 -> {
                        accept(retryAnswer)
                        recovered = true
                    }
                    retryAnswer?.code == 404 -> {
                        restart()
                        recovered = true
                    }
                    retryAnswer != null && retryAnswer.code !in RETRY_STATUS_CODES ->
                        throw FileIngestException(FileIngestException.Reason.REJECTED, retryAnswer.code)
                }
            }
        }
    }

    private suspend fun put(file: File, range: LongRange, size: Long, url: String): Answer? {
        val body = runInterruptible(Dispatchers.IO) { readRange(file, range) }
        val request = Request.Builder()
            .url(url)
            .put(body.toRequestBody(null))
            .header("Content-Range", "bytes ${range.first}-${range.last}/$size")
            .build()
        return execute(request)
    }

    private suspend fun getStatus(url: String): Answer? {
        val request = Request.Builder().url(url).get().header("Cache-Control", "no-store").build()
        return execute(request)
    }

    private suspend fun execute(request: Request): Answer? = runInterruptible(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { response ->
                Answer(response.code, response.body?.string().orEmpty())
            }
        } catch (_: IOException) {
            null
        }
    }

    private fun readRange(file: File, range: LongRange): ByteArray {
        val length = (range.last - range.first + 1).toInt()
        val data = ByteArray(length)
        try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(range.first)
                raf.readFully(data)
            }
        } catch (_: IOException) {
            throw FileIngestException(FileIngestException.Reason.UNREADABLE_FILE)
        }
        return data
    }

    private fun parseStatus(body: String): Status? = try {
        val json = JSONObject(body)
        val array = json.optJSONArray("received")
        val received = ArrayList<LongArray>()
        if (array != null) {
            for (i in 0 until array.length()) {
                val pair = array.optJSONArray(i) ?: continue
                if (pair.length() == 2) received.add(longArrayOf(pair.optLong(0), pair.optLong(1)))
            }
        }
        Status(received, json.optBoolean("stored", false))
    } catch (_: Exception) {
        null
    }

    private fun heldFraction(status: Status, size: Long): Float {
        if (size <= 0L) return 0f
        val held = status.received.sumOf { it[1] - it[0] + 1 }
        return (held.toFloat() / size.toFloat()).coerceIn(0f, 1f)
    }
}

data class FileIngestJob(
    val id: String,
    val key: String,
    val userId: String,
    val fileName: String,
    val size: Long,
    val presignedUrl: String,
    val ingestUrl: String,
    val mimeType: String,
    val clanId: Long,
    val channelId: Long,
    val cacheKey: Long,
    val mode: Int,
    val isPublic: Boolean,
    val topicId: Long,
    val messageId: Long,
    val createTimeSeconds: Int,
    val outgoingBase: String,
    val mentions: List<ByteArray>,
    val ingestKeys: List<String>,
    val createdAtMs: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("key", key)
        put("userId", userId)
        put("fileName", fileName)
        put("size", size)
        put("presignedUrl", presignedUrl)
        put("ingestUrl", ingestUrl)
        put("mimeType", mimeType)
        put("clanId", clanId)
        put("channelId", channelId)
        put("cacheKey", cacheKey)
        put("mode", mode)
        put("isPublic", isPublic)
        put("topicId", topicId)
        put("messageId", messageId)
        put("createTimeSeconds", createTimeSeconds)
        put("outgoingBase", outgoingBase)
        put("mentions", JSONArray().apply { mentions.forEach { put(Base64.encodeToString(it, Base64.NO_WRAP)) } })
        put("ingestKeys", JSONArray().apply { ingestKeys.forEach { put(it) } })
        put("createdAtMs", createdAtMs)
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()

        fun fromJson(json: JSONObject): FileIngestJob? = try {
            val mentions = json.optJSONArray("mentions")
            val keys = json.optJSONArray("ingestKeys")
            FileIngestJob(
                id = json.getString("id"),
                key = json.getString("key"),
                userId = json.getString("userId"),
                fileName = json.getString("fileName"),
                size = json.getLong("size"),
                presignedUrl = json.getString("presignedUrl"),
                ingestUrl = json.getString("ingestUrl"),
                mimeType = json.getString("mimeType"),
                clanId = json.getLong("clanId"),
                channelId = json.getLong("channelId"),
                cacheKey = json.getLong("cacheKey"),
                mode = json.getInt("mode"),
                isPublic = json.getBoolean("isPublic"),
                topicId = json.getLong("topicId"),
                messageId = json.getLong("messageId"),
                createTimeSeconds = json.getInt("createTimeSeconds"),
                outgoingBase = json.getString("outgoingBase"),
                mentions = (0 until (mentions?.length() ?: 0)).map { Base64.decode(mentions!!.getString(it), Base64.NO_WRAP) },
                ingestKeys = (0 until (keys?.length() ?: 0)).map { keys!!.getString(it) },
                createdAtMs = json.getLong("createdAtMs"),
            )
        } catch (_: Exception) {
            null
        }
    }
}

object FileIngestJobStore {
    private const val DIRECTORY = "file_ingest_jobs"
    private const val METADATA_EXTENSION = ".json"
    private const val MAX_AGE_MS = 24L * 60 * 60 * 1000
    private val lock = Any()

    fun save(context: Context, job: FileIngestJob, source: File): File? = synchronized(lock) {
        val directory = directory(context)
        val stored = File(directory, job.fileName)
        directory.mkdirs()
        if (!source.renameTo(stored)) {
            null
        } else {
            try {
                File(directory, job.id + METADATA_EXTENSION).writeText(job.toJson().toString())
                stored
            } catch (_: Exception) {
                stored.renameTo(source)
                null
            }
        }
    }

    fun fileFor(context: Context, job: FileIngestJob): File = File(directory(context), job.fileName)

    fun all(context: Context): List<FileIngestJob> = synchronized(lock) {
        val now = System.currentTimeMillis()
        loadAll(context).filter { job ->
            val expired = now - job.createdAtMs > MAX_AGE_MS
            if (expired) delete(context, job)
            !expired
        }
    }

    fun remove(context: Context, keys: Collection<String>) {
        if (keys.isEmpty()) return
        synchronized(lock) {
            loadAll(context)
                .filter { job -> keys.any { PresignFinishContent.presignKeysMatch(it, job.key) } }
                .forEach { delete(context, it) }
        }
    }

    fun remove(context: Context, job: FileIngestJob) = synchronized(lock) { delete(context, job) }

    fun removeAll(context: Context) = synchronized(lock) {
        directory(context).deleteRecursively()
    }

    private fun directory(context: Context): File = File(context.filesDir, DIRECTORY)

    private fun loadAll(context: Context): List<FileIngestJob> =
        directory(context).listFiles { file -> file.name.endsWith(METADATA_EXTENSION) }
            ?.mapNotNull { file -> runCatching { FileIngestJob.fromJson(JSONObject(file.readText())) }.getOrNull() }
            ?: emptyList()

    private fun delete(context: Context, job: FileIngestJob) {
        val directory = directory(context)
        File(directory, job.id + METADATA_EXTENSION).delete()
        File(directory, job.fileName).delete()
    }
}
