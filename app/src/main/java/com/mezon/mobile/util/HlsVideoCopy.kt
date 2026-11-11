package com.mezon.mobile.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.system.ErrnoException
import android.system.OsConstants
import androidx.annotation.StringRes
import com.mezon.mobile.R
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

object HlsVideoCopy {
    private const val VIDEO_MIME = "video/mp4"
    private const val MIN_SAMPLE_BUFFER_BYTES = 1 shl 20
    private const val MAX_SAMPLE_BUFFER_BYTES = 64 shl 20

    enum class Outcome { SAVED, UNAVAILABLE, STILL_UPLOADING, NETWORK, NO_SPACE, FAILED }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.MINUTES)
            .build()
    }

    @StringRes
    fun failureMessage(outcome: Outcome): Int = when (outcome) {
        Outcome.STILL_UPLOADING -> R.string.video_save_still_uploading
        Outcome.NETWORK -> R.string.video_save_network_failed
        Outcome.NO_SPACE -> R.string.video_save_no_space
        Outcome.SAVED, Outcome.UNAVAILABLE, Outcome.FAILED -> R.string.message_toast_save_failed
    }

    fun isOutOfSpace(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is ErrnoException && current.errno == OsConstants.ENOSPC) return true
            val message = current.message.orEmpty()
            if (message.contains("ENOSPC") || message.contains("No space left")) return true
            current = current.cause
        }
        return false
    }

    suspend fun saveToDownloads(
        context: Context,
        attachmentUrl: String,
        onProgress: (Int) -> Unit = {},
    ): Outcome = withContext(Dispatchers.IO) {
        val copy = File(context.cacheDir, "hls-copy-${UUID.randomUUID()}.mp4")
        try {
            val outcome = copyToFile(context, attachmentUrl, copy, onProgress)
            if (outcome != Outcome.SAVED) return@withContext outcome
            val baseName = attachmentUrl.substringBefore('?').substringAfterLast('/').substringBeforeLast('.')
            publish(context.applicationContext, copy, "${baseName.ifEmpty { "video" }}.mp4")
            Outcome.SAVED
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isOutOfSpace(e)) Outcome.NO_SPACE else Outcome.FAILED
        } finally {
            copy.delete()
        }
    }

    suspend fun copyToFile(
        context: Context,
        attachmentUrl: String,
        target: File,
        onProgress: (Int) -> Unit = {},
    ): Outcome = withContext(Dispatchers.IO) {
        val parts = when (val finished = FileIngestPlaylist.finishedParts(attachmentUrl)) {
            is FileIngestPlaylist.FinishedParts.Parts -> finished.urls
            FileIngestPlaylist.FinishedParts.Missing -> return@withContext Outcome.UNAVAILABLE
            FileIngestPlaylist.FinishedParts.Unfinished -> return@withContext Outcome.STILL_UPLOADING
            FileIngestPlaylist.FinishedParts.Unreachable -> return@withContext Outcome.NETWORK
        }
        val joined = File(context.cacheDir, "hls-copy-${UUID.randomUUID()}.mp4")
        try {
            download(parts, joined, onProgress)?.let { return@withContext it }
            if (!remux(joined, target)) {
                target.delete()
                joined.copyTo(target, overwrite = true)
            }
            Outcome.SAVED
        } catch (e: CancellationException) {
            target.delete()
            throw e
        } catch (e: Exception) {
            target.delete()
            if (isOutOfSpace(e)) Outcome.NO_SPACE else Outcome.FAILED
        } finally {
            joined.delete()
        }
    }

    suspend fun firstFrame(context: Context, attachmentUrl: String, timeUs: Long): Bitmap? = withContext(Dispatchers.IO) {
        val finished = FileIngestPlaylist.finishedParts(attachmentUrl)
        val parts = (finished as? FileIngestPlaylist.FinishedParts.Parts)?.urls ?: return@withContext null
        val sample = File(context.cacheDir, "hls-frame-${UUID.randomUUID()}.mp4")
        val retriever = MediaMetadataRetriever()
        try {
            if (download(parts.take(2), sample) {} != null) return@withContext null
            retriever.setDataSource(sample.path)
            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
            sample.delete()
        }
    }

    private suspend fun download(parts: List<String>, target: File, onProgress: (Int) -> Unit): Outcome? {
        FileOutputStream(target).use { output ->
            parts.forEachIndexed { index, part ->
                currentCoroutineContext().ensureActive()
                val failure = try {
                    client.newCall(Request.Builder().url(part).get().build()).execute().use { response ->
                        val body = response.body
                        when {
                            response.code == 403 || response.code == 404 -> Outcome.UNAVAILABLE
                            !response.isSuccessful || body == null -> Outcome.NETWORK
                            else -> {
                                body.byteStream().use { it.copyTo(output) }
                                null
                            }
                        }
                    }
                } catch (e: IOException) {
                    if (isOutOfSpace(e)) Outcome.NO_SPACE else Outcome.NETWORK
                }
                if (failure != null) return failure
                onProgress((index + 1) * 100 / parts.size)
            }
        }
        return null
    }

    private fun remux(source: File, target: File): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        return try {
            extractor.setDataSource(source.path)
            val output = MediaMuxer(target.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = output
            val tracks = HashMap<Int, Int>()
            var bufferBytes = MIN_SAMPLE_BUFFER_BYTES
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    bufferBytes = maxOf(bufferBytes, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                }
                if (mime.startsWith("video/") && format.containsKey(MediaFormat.KEY_ROTATION)) {
                    output.setOrientationHint(format.getInteger(MediaFormat.KEY_ROTATION))
                }
                extractor.selectTrack(index)
                tracks[index] = output.addTrack(format)
            }
            if (tracks.isEmpty()) return false
            output.start()
            var buffer = ByteBuffer.allocate(bufferBytes)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val track = extractor.sampleTrackIndex
                if (track < 0) break
                val size = try {
                    extractor.readSampleData(buffer, 0)
                } catch (_: IllegalArgumentException) {
                    if (buffer.capacity() >= MAX_SAMPLE_BUFFER_BYTES) return false
                    buffer = ByteBuffer.allocate(buffer.capacity() * 2)
                    continue
                }
                if (size < 0) break
                val isKeyFrame = (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                info.set(0, size, extractor.sampleTime, if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                tracks[track]?.let { output.writeSampleData(it, buffer, info) }
                extractor.advance()
            }
            output.stop()
            true
        } catch (_: Exception) {
            false
        } finally {
            runCatching { muxer?.release() }
            extractor.release()
        }
    }

    private fun publish(context: Context, source: File, displayName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, VIDEO_MIME)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("MediaStore refused $displayName")
            try {
                val stream = resolver.openOutputStream(uri) ?: throw IOException("no output stream for $uri")
                stream.use { output -> source.inputStream().use { it.copyTo(output) } }
                val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, published, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            return
        }
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        downloadsDir.mkdirs()
        val target = File(downloadsDir, displayName)
        source.copyTo(target, overwrite = true)
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(VIDEO_MIME), null)
    }
}
