package com.mezon.mobile.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.SimpleCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@androidx.annotation.OptIn(UnstableApi::class)
object VideoPrefetcher {
    private const val HEAD_BYTES = 1024L * 1024
    private const val TAIL_LIMIT_BYTES = 1024L * 1024
    private const val SCAN_BYTES = 64 * 1024
    private const val READ_BUFFER_BYTES = 64 * 1024
    private const val MAX_ACTIVE_JOBS = 1

    private class Entry {
        var job: Job? = null
        var started = false
    }

    private class Fetched(val prefix: ByteArray, val contentLength: Long, val acceptsRanges: Boolean)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val permits = Semaphore(MAX_ACTIVE_JOBS)
    private val lock = Any()
    private val entries = HashMap<String, Entry>()
    private val finished = HashSet<String>()
    private var isPlaybackActive = false

    fun prefetch(context: Context, url: String) {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return
        synchronized(lock) {
            if (isPlaybackActive || url in finished || url in entries) return
        }
        if (!isUnmetered(context)) return
        val appContext = context.applicationContext
        synchronized(lock) {
            if (isPlaybackActive || url in finished || url in entries) return
            val entry = Entry()
            val job = scope.launch(start = CoroutineStart.LAZY) {
                var completed = false
                try {
                    permits.withPermit {
                        synchronized(lock) {
                            if (entries[url] !== entry) return@withPermit
                            entry.started = true
                        }
                        warm(appContext, url)
                        completed = true
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                } finally {
                    synchronized(lock) {
                        if (entries[url] === entry) entries.remove(url)
                        if (completed) finished += url
                    }
                }
            }
            entry.job = job
            entries[url] = entry
            job.start()
        }
    }

    fun withdraw(url: String) {
        synchronized(lock) {
            val entry = entries[url] ?: return
            if (entry.started) return
            entries.remove(url)
            entry.job?.cancel()
        }
    }

    fun onPlaybackStarted(url: String) {
        synchronized(lock) {
            isPlaybackActive = true
            finished += url
            entries.values.forEach { it.job?.cancel() }
            entries.clear()
        }
    }

    fun onPlaybackStopped() {
        synchronized(lock) {
            isPlaybackActive = false
        }
    }

    internal fun reset() {
        synchronized(lock) {
            entries.values.forEach { it.job?.cancel() }
            entries.clear()
            finished.clear()
        }
    }

    private fun isUnmetered(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return runCatching { !manager.isActiveNetworkMetered }.getOrDefault(false)
    }

    private suspend fun warm(context: Context, url: String) {
        val cache = VideoCache.cacheOrNull(context) ?: return
        val uri = Uri.parse(url)
        val key = uri.toString()
        if (cache.isCached(key, 0L, HEAD_BYTES)) return
        val factory = VideoCache.dataSourceFactory(context)
        val head = fetch(factory, uri, 0L, HEAD_BYTES, SCAN_BYTES)
        val knownLength = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        if (knownLength == C.LENGTH_UNSET.toLong() && head.contentLength > 0L) {
            storeContentLength(cache, key, head.contentLength)
        }
        val contentLength = if (knownLength > 0L) knownLength else head.contentLength
        if (contentLength <= 0L) return
        if (!head.acceptsRanges) return
        val tailStart = moovTailStart(head.prefix) ?: return
        val tailLength = contentLength - tailStart
        if (tailStart < HEAD_BYTES || tailLength <= 0L || tailLength > TAIL_LIMIT_BYTES) return
        if (cache.isCached(key, tailStart, tailLength)) return
        fetch(factory, uri, tailStart, tailLength, 0)
    }

    private suspend fun fetch(
        factory: DataSource.Factory,
        uri: Uri,
        position: Long,
        length: Long,
        keepBytes: Int,
    ): Fetched {
        val source = factory.createDataSource()
        val prefix = ByteArray(keepBytes)
        var kept = 0
        var remaining = length
        try {
            // A normal GET gives the cache the actual length even for files smaller than HEAD_BYTES.
            val requestedLength = if (position == 0L) C.LENGTH_UNSET.toLong() else length
            source.open(DataSpec.Builder().setUri(uri).setPosition(position).setLength(requestedLength).build())
            val headers = source.responseHeaders
            val contentLength = contentRangeTotal(headers)
            val acceptsRanges = contentLength > 0L || headers.any { (name, values) ->
                name.equals("Accept-Ranges", ignoreCase = true) && values.any { it.equals("bytes", true) }
            }
            val buffer = ByteArray(READ_BUFFER_BYTES)
            while (remaining > 0L) {
                currentCoroutineContext().ensureActive()
                val read = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read == C.RESULT_END_OF_INPUT) break
                remaining -= read
                if (kept < keepBytes) {
                    val count = minOf(read, keepBytes - kept)
                    System.arraycopy(buffer, 0, prefix, kept, count)
                    kept += count
                }
            }
            return Fetched(prefix.copyOf(kept), contentLength, acceptsRanges)
        } finally {
            runCatching { source.close() }
        }
    }

    private fun storeContentLength(cache: SimpleCache, key: String, contentLength: Long) {
        runCatching {
            val mutations = ContentMetadataMutations()
            ContentMetadataMutations.setContentLength(mutations, contentLength)
            cache.applyContentMetadataMutations(key, mutations)
        }
    }

    private fun contentRangeTotal(headers: Map<String, List<String>>): Long =
        headers.entries
            .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
            ?.value?.firstOrNull()
            ?.substringAfterLast('/')
            ?.trim()
            ?.toLongOrNull()
            ?: C.LENGTH_UNSET.toLong()

    private fun moovTailStart(prefix: ByteArray): Long? {
        var offset = 0L
        while (offset + 8 <= prefix.size) {
            val start = offset.toInt()
            val declaredSize = readUnsigned(prefix, start, 4)
            val type = String(prefix, start + 4, 4, Charsets.US_ASCII)
            val size = when (declaredSize) {
                0L -> return null
                1L -> if (offset + 16 <= prefix.size) readUnsigned(prefix, start + 8, 8) else return null
                else -> declaredSize
            }
            if (size < 8L) return null
            when (type) {
                "moov" -> return null
                "mdat" -> return offset + size
            }
            offset += size
        }
        return null
    }

    private fun readUnsigned(bytes: ByteArray, start: Int, count: Int): Long {
        var value = 0L
        for (index in start until start + count) {
            value = (value shl 8) or (bytes[index].toLong() and 0xFF)
        }
        return value
    }
}
