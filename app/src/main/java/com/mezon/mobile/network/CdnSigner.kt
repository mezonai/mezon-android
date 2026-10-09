package com.mezon.mobile.network

import android.os.SystemClock
import com.mezon.mobile.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor

data class CdnRequestUrl(
    val url: String,
    val channelId: Long,
    val signature: String,
) {
    val isSigned: Boolean get() = signature.isNotEmpty()

    companion object {
        fun unsigned(url: String) = CdnRequestUrl(url, 0L, "")
    }
}

object CdnSigner {
    private const val REFRESH_AFTER_MS = 3 * 60 * 60 * 1000L
    private const val REFUSED_RETRY_AFTER_MS = 60_000L
    private const val FORBIDDEN_REFETCH_AFTER_MS = 30_000L
    private const val FETCH_TIMEOUT_MS = 10_000L
    private const val CHANNEL_SEGMENT_LENGTH = 16
    private const val IMGPROXY_SOURCE_MARKER = "/plain/"
    private const val HEX_DIGITS = "0123456789ABCDEF"
    private val KNOWN_MEDIA_ORIGINS = listOf(
        "https://cdn.mezon.ai",
        "https://cdn.mezon.vn",
        "https://cdn.komu.vn",
        "https://cdn.komu.ai",
    )

    private sealed class Entry {
        class Signed(val signature: String, val at: Long) : Entry()
        class Refused(val at: Long) : Entry()
    }

    private sealed class CacheState {
        object Missing : CacheState()
        object Refused : CacheState()
        class Signed(val signature: String) : CacheState()
    }

    private class SigningTarget(val channelId: Long, val build: (String) -> String)

    private class RenditionParts(val head: String, val source: String, val suffix: String)

    private val lock = Any()
    private var provider: (suspend (Long) -> String)? = null
    private val entries = HashMap<Long, Entry>()
    private val flights = HashMap<Long, Deferred<String?>>()
    private var generation = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val mediaOrigins: List<String> by lazy {
        (listOf(BuildConfig.MEZON_BASE_IMG_URL) + KNOWN_MEDIA_ORIGINS).mapNotNull(::urlBase).distinct()
    }

    private val imgproxyBase: String? by lazy { urlBase(BuildConfig.MEZON_IMGPROXY_BASE_URL) }

    val interceptor = Interceptor { chain ->
        val original = chain.request()
        val url = original.url.newBuilder().fragment(null).build().toString()
        if (!wants(url)) return@Interceptor chain.proceed(original)
        val request = runCatching { requestUrlBlocking(url) }.getOrElse { CdnRequestUrl.unsigned(url) }
        if (!request.isSigned) return@Interceptor chain.proceed(original)
        val response = chain.proceed(original.newBuilder().url(request.url).build())
        if (!shouldRetry(request, response.code)) return@Interceptor response
        val fresh = runCatching { freshRequestUrlBlocking(request, url) }.getOrNull() ?: return@Interceptor response
        response.close()
        chain.proceed(original.newBuilder().url(fresh.url).build())
    }

    fun install(provider: suspend (Long) -> String) {
        synchronized(lock) { this.provider = provider }
    }

    fun reset() {
        synchronized(lock) {
            entries.clear()
            flights.clear()
            generation += 1
        }
    }

    fun prefetch(channelId: Long) {
        if (channelId == 0L) return
        val shouldFetch = synchronized(lock) {
            provider != null && flights[channelId] == null && stateLocked(channelId) is CacheState.Missing
        }
        if (!shouldFetch) return
        scope.launch { signature(channelId) }
    }

    fun wants(url: String): Boolean = signingTarget(url) != null

    fun readyRequestUrl(url: String): CdnRequestUrl? {
        val target = signingTarget(url) ?: return CdnRequestUrl.unsigned(url)
        return when (val state = synchronized(lock) { stateLocked(target.channelId) }) {
            CacheState.Missing -> null
            CacheState.Refused -> CdnRequestUrl.unsigned(url)
            is CacheState.Signed -> CdnRequestUrl(target.build(state.signature), target.channelId, state.signature)
        }
    }

    suspend fun requestUrl(url: String): CdnRequestUrl {
        val target = signingTarget(url) ?: return CdnRequestUrl.unsigned(url)
        val signature = signature(target.channelId) ?: return CdnRequestUrl.unsigned(url)
        return CdnRequestUrl(target.build(signature), target.channelId, signature)
    }

    fun requestUrlBlocking(url: String): CdnRequestUrl =
        readyRequestUrl(url) ?: runBlocking { requestUrl(url) }

    fun invalidate(request: CdnRequestUrl): Boolean {
        if (!request.isSigned) return false
        synchronized(lock) {
            val entry = entries[request.channelId]
            if (entry !is Entry.Signed || entry.signature != request.signature) return true
            val age = SystemClock.elapsedRealtime() - entry.at
            if (age < FORBIDDEN_REFETCH_AFTER_MS) return false
            entries.remove(request.channelId)
            return true
        }
    }

    fun shouldRetry(request: CdnRequestUrl, statusCode: Int): Boolean =
        request.isSigned && statusCode == 403 && invalidate(request)

    suspend fun freshRequestUrl(after: CdnRequestUrl, url: String): CdnRequestUrl? {
        val next = requestUrl(url)
        return next.takeIf { it.isSigned && it.signature != after.signature }
    }

    fun freshRequestUrlBlocking(after: CdnRequestUrl, url: String): CdnRequestUrl? =
        runBlocking { freshRequestUrl(after, url) }

    fun requestUrlOnMain(url: String, onReady: (CdnRequestUrl) -> Unit) {
        readyRequestUrl(url)?.let {
            onReady(it)
            return
        }
        scope.launch {
            val request = requestUrl(url)
            withContext(Dispatchers.Main) { onReady(request) }
        }
    }

    fun freshRequestUrlOnMain(after: CdnRequestUrl, url: String, onReady: (CdnRequestUrl?) -> Unit) {
        scope.launch {
            val fresh = freshRequestUrl(after, url)
            withContext(Dispatchers.Main) { onReady(fresh) }
        }
    }

    private fun stateLocked(channelId: Long): CacheState {
        val now = SystemClock.elapsedRealtime()
        return when (val entry = entries[channelId]) {
            is Entry.Signed -> if (now - entry.at < REFRESH_AFTER_MS) CacheState.Signed(entry.signature) else CacheState.Missing
            is Entry.Refused -> if (now - entry.at < REFUSED_RETRY_AFTER_MS) CacheState.Refused else CacheState.Missing
            null -> CacheState.Missing
        }
    }

    private suspend fun signature(channelId: Long): String? {
        val flight = synchronized(lock) {
            when (val state = stateLocked(channelId)) {
                is CacheState.Signed -> return state.signature
                CacheState.Refused -> return null
                CacheState.Missing -> Unit
            }
            val currentProvider = provider ?: return null
            flights[channelId] ?: run {
                val started = generation
                scope.async { fetchSignature(channelId, currentProvider, started) }.also { flights[channelId] = it }
            }
        }
        return flight.await()
    }

    private suspend fun fetchSignature(channelId: Long, provider: suspend (Long) -> String, started: Int): String? {
        val fetched = fetchWithTimeout(channelId, provider)
        synchronized(lock) {
            if (generation != started) return null
            flights.remove(channelId)
            val now = SystemClock.elapsedRealtime()
            entries[channelId] = if (fetched != null) Entry.Signed(fetched, now) else Entry.Refused(now)
        }
        return fetched
    }

    private suspend fun fetchWithTimeout(channelId: Long, provider: suspend (Long) -> String): String? {
        val call = scope.async { provider(channelId) }
        val fetched = try {
            withTimeoutOrNull(FETCH_TIMEOUT_MS) { call.await() }
        } catch (_: CancellationException) {
            currentCoroutineContext().ensureActive()
            return null
        } catch (_: Exception) {
            return null
        }
        if (fetched == null) {
            call.cancel()
            return null
        }
        return fetched.takeIf { it.isNotEmpty() }
    }

    private fun signingTarget(url: String): SigningTarget? {
        channelIdOf(url)?.let { channelId ->
            return SigningTarget(channelId) { signature -> "$url?$signature" }
        }
        val parts = renditionParts(url) ?: return null
        val channelId = channelIdOf(parts.source) ?: return null
        return SigningTarget(channelId) { signature ->
            "${parts.head}$IMGPROXY_SOURCE_MARKER${escapeRenditionSource("${parts.source}?$signature")}${parts.suffix}"
        }
    }

    private fun channelIdOf(url: String): Long? {
        val origin = mediaOrigins.firstOrNull { url.startsWith(it) } ?: return null
        val rest = url.substring(origin.length)
        if (!rest.startsWith("/") || rest.contains('?') || rest.contains('#')) return null
        val path = rest.substring(1)
        val slash = path.indexOf('/')
        if (slash < 0 || slash == path.length - 1) return null
        return channelIdFromSegment(path.substring(0, slash))
    }

    private fun renditionParts(url: String): RenditionParts? {
        val proxy = imgproxyBase ?: return null
        if (!url.startsWith(proxy) || url.getOrNull(proxy.length) != '/') return null
        val marker = url.indexOf(IMGPROXY_SOURCE_MARKER)
        if (marker < 0) return null
        val head = url.substring(0, marker)
        val rest = url.substring(marker + IMGPROXY_SOURCE_MARKER.length)
        val at = rest.lastIndexOf('@')
        if (at < 0) return RenditionParts(head, rest, "")
        return RenditionParts(head, rest.substring(0, at), rest.substring(at))
    }

    private fun escapeRenditionSource(source: String): String {
        val out = StringBuilder(source.length + 32)
        for (byte in source.toByteArray(Charsets.UTF_8)) {
            val value = byte.toInt() and 0xFF
            if (value < 0x20 || value >= 0x7F || value == '%'.code || value == '?'.code || value == '#'.code || value == '@'.code) {
                out.append('%').append(HEX_DIGITS[value shr 4]).append(HEX_DIGITS[value and 0x0F])
            } else {
                out.append(value.toChar())
            }
        }
        return out.toString()
    }

    private fun urlBase(raw: String): String? = raw.trim().trimEnd('/').takeIf { it.isNotEmpty() }

    private fun channelIdFromSegment(segment: String): Long? {
        if (segment.length != CHANNEL_SEGMENT_LENGTH) return null
        if (!segment.all { it in '0'..'9' || it in 'a'..'f' }) return null
        val value = segment.toULongOrNull(16) ?: return null
        if (value == 0UL) return null
        return value.toLong()
    }
}
