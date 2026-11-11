package com.mezon.mobile.util

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

@androidx.annotation.OptIn(UnstableApi::class)
internal object VideoPlaybackDownload {
    private const val RETAIN_MS = 24 * 60 * 60 * 1000L

    fun destination(context: Context, extension: String): File =
        File(directory(context), "playback-${UUID.randomUUID()}.$extension")

    /** Fetches the original in a single GET, or assembles the finished ingest output. */
    suspend fun copy(
        context: Context,
        url: String,
        target: File,
        mimeType: String,
        tryHls: Boolean,
        onProgress: (Int) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val directory = directory(context)
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Could not create video playback cache")
        }
        val cutoff = System.currentTimeMillis() - RETAIN_MS
        directory.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        try {
            copyOriginal(context, url, target, onProgress)
            mimeType
        } catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            if (HlsVideoCopy.isOutOfSpace(error)) throw error
            val status = (error as? HttpDataSource.InvalidResponseCodeException)?.responseCode
            if (!tryHls && status != 403 && status != 404) throw error
            target.delete()
            val outcome = HlsVideoCopy.copyToFile(context, url, target, onProgress)
            if (outcome == HlsVideoCopy.Outcome.NO_SPACE) throw IOException("No space left for video download")
            if (outcome != HlsVideoCopy.Outcome.SAVED) throw error
            "video/mp4"
        }
    }

    private suspend fun copyOriginal(
        context: Context,
        url: String,
        target: File,
        onProgress: (Int) -> Unit,
    ) {
        val source = VideoCache.upstreamDataSourceFactory(context).createDataSource()
        try {
            val length = runInterruptible { source.open(DataSpec(Uri.parse(url))) }
            if (length > 0L && length > requireNotNull(target.parentFile).usableSpace) {
                throw IOException("No space left for video download")
            }
            val contentRange = source.responseHeaders.entries.firstOrNull {
                it.key.equals("Content-Range", ignoreCase = true)
            }?.value?.firstOrNull()
            if (contentRange != null &&
                (!contentRange.startsWith("bytes 0-") || contentRange.substringAfterLast('/').toLongOrNull() != length)
            ) {
                throw IOException("Incomplete video response")
            }

            var copied = 0L
            var lastPercent = -1
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = runInterruptible { source.read(buffer, 0, buffer.size) }
                    if (count == C.RESULT_END_OF_INPUT) break
                    output.write(buffer, 0, count)
                    copied += count
                    if (length > 0L) {
                        val percent = (copied * 100 / length).toInt().coerceIn(0, 100)
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent)
                        }
                    }
                }
            }
            if (copied == 0L || (length >= 0L && copied != length)) {
                throw EOFException("Incomplete video download")
            }
        } finally {
            source.close()
        }
    }

    internal fun clear(context: Context) {
        directory(context).listFiles()?.forEach { it.delete() }
    }

    private fun directory(context: Context) = File(context.cacheDir, "video_playback")
}
