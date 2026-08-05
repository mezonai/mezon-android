package com.mezon.mobile.util

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import com.mezon.mobile.home.chat.AttachmentPickerItem
import com.mezon.mobile.network.MezonApi
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

object AttachmentUploader {

    private const val TAG = "AttachmentUploader"
    private const val PARALLEL_PART_UPLOADS_BYTES = 3
    private const val PARALLEL_PART_UPLOADS_FILE = 5
    private const val MULTIPART_PART_SIZE = 10 * 1024 * 1024
    private const val MULTIPART_MIN_FILE_SIZE = 50L * 1024 * 1024
    private const val MULTIPART_UPLOAD_ENABLED = true

    private class MultipartNotApplicable : Exception()

    @Volatile
    private var skipMultipartStartForSession = false

    fun resetMultipartAvailabilityForSession() {
        skipMultipartStartForSession = false
    }

    private fun multipartPartCount(fileSize: Long): Int =
        maxOf(1, ((fileSize + MULTIPART_PART_SIZE - 1) / MULTIPART_PART_SIZE).toInt())

    private fun willUseMultipart(sizeBytes: Long): Boolean =
        MULTIPART_UPLOAD_ENABLED &&
            sizeBytes >= MULTIPART_MIN_FILE_SIZE &&
            !skipMultipartStartForSession

    data class UploadedFileResult(
        val serverFilename: String,
        val cdnUrl: String,
    )

    sealed class UploadExecutionPlan {
        data class SinglePut(
            val presignedUrl: String,
            val mimeType: String,
        ) : UploadExecutionPlan()

        data class Multipart(
            val uploadId: String,
            val presignedUrls: List<String>,
            val serverFilename: String,
            val mimeType: String,
            val fileSize: Long,
        ) : UploadExecutionPlan()
    }

    data class PresignedFileResult(
        val serverFilename: String,
        val cdnUrl: String,
        val plan: UploadExecutionPlan,
    )

    fun isOverSizeLimit(sizeBytes: Long, mimeType: String): Boolean =
        AttachmentPickerItem.isOverSizeLimit(sizeBytes, mimeType)

    private fun maxReadBytes(mimeType: String): Long = AttachmentPickerItem.maxFileSizeBytes(mimeType)

    private const val IMAGE_MAX_DIMENSION = 2560
    private const val IMAGE_WEBP_QUALITY = 85
    private const val IMAGE_RECOMPRESS_MIN_BYTES = 512L * 1024
    private const val VIDEO_THUMB_MAX_DIMENSION = 640
    private const val VIDEO_THUMB_JPEG_QUALITY = 85
    private const val VIDEO_THUMB_FRAME_US = 100_000L

    fun isVideoMimeType(mimeType: String): Boolean =
        mimeType.startsWith("video/", ignoreCase = true)

    fun extractVideoThumbnailJpeg(file: File): CompressedImage? {
        val retriever = MediaMetadataRetriever()
        var frame: Bitmap? = null
        return try {
            retriever.setDataSource(file.absolutePath)
            frame = retriever.getFrameAtTime(
                VIDEO_THUMB_FRAME_US,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            ) ?: return null
            var bitmap = scaleToMaxDimension(frame, VIDEO_THUMB_MAX_DIMENSION)
            if (bitmap !== frame) frame.recycle()
            frame = null
            val out = ByteArrayOutputStream()
            val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, VIDEO_THUMB_JPEG_QUALITY, out)
            val w = bitmap.width
            val h = bitmap.height
            bitmap.recycle()
            if (!ok) return null
            CompressedImage(
                bytes = out.toByteArray(),
                width = w,
                height = h,
                mimeType = "image/jpeg",
                filename = "thumb.jpg",
            )
        } catch (e: Throwable) {
            Log.w(TAG, "extractVideoThumbnailJpeg failed path=${file.absolutePath}", e)
            null
        } finally {
            frame?.recycle()
            runCatching { retriever.release() }
        }
    }

    class CompressedImage(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
        val mimeType: String,
        val filename: String,
    )

    fun isCompressibleImage(mimeType: String): Boolean {
        val m = mimeType.lowercase()
        return m == "image/jpeg" || m == "image/jpg" || m == "image/png" ||
            m == "image/heic" || m == "image/heif"
    }

    fun compressImageFromUri(
        contentResolver: ContentResolver,
        uri: Uri,
        mimeType: String,
        filename: String,
        originalSize: Long,
    ): CompressedImage? {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val srcW = bounds.outWidth
            val srcH = bounds.outHeight
            if (srcW <= 0 || srcH <= 0) return null

            val maxDim = maxOf(srcW, srcH)
            val needsDownscale = maxDim > IMAGE_MAX_DIMENSION
            val worthRecompressing = originalSize <= 0L || originalSize > IMAGE_RECOMPRESS_MIN_BYTES
            if (!needsDownscale && !worthRecompressing) return null

            val opts = BitmapFactory.Options().apply {
                inSampleSize = computeInSampleSize(srcW, srcH, IMAGE_MAX_DIMENSION)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            var bitmap = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null

            val orientation = readExifOrientation(contentResolver, uri, mimeType)
            bitmap = applyOrientation(bitmap, orientation)
            bitmap = scaleToMaxDimension(bitmap, IMAGE_MAX_DIMENSION)

            val out = ByteArrayOutputStream()
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            val ok = bitmap.compress(format, IMAGE_WEBP_QUALITY, out)
            val finalW = bitmap.width
            val finalH = bitmap.height
            bitmap.recycle()
            if (!ok) return null

            val bytes = out.toByteArray()
            if (originalSize in 1 until bytes.size.toLong() && !needsDownscale) return null

            return CompressedImage(
                bytes = bytes,
                width = finalW,
                height = finalH,
                mimeType = "image/webp",
                filename = replaceExtension(filename, "webp"),
            )
        } catch (e: Throwable) {
            Log.w(TAG, "compressImageFromUri failed for $filename", e)
            return null
        }
    }

    private fun computeInSampleSize(srcW: Int, srcH: Int, maxDim: Int): Int {
        var sample = 1
        var w = srcW
        var h = srcH
        while ((w / 2) >= maxDim || (h / 2) >= maxDim) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }

    private fun scaleToMaxDimension(bitmap: Bitmap, maxDim: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val longest = maxOf(w, h)
        if (longest <= maxDim) return bitmap
        val scale = maxDim.toFloat() / longest
        val nw = (w * scale).toInt().coerceAtLeast(1)
        val nh = (h * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, nw, nh, true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun readExifOrientation(
        contentResolver: ContentResolver,
        uri: Uri,
        mimeType: String,
    ): Int {
        val m = mimeType.lowercase()
        if (m != "image/jpeg" && m != "image/jpg" && m != "image/heic" && m != "image/heif") {
            return ExifInterface.ORIENTATION_NORMAL
        }
        return try {
            contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: Throwable) {
            ExifInterface.ORIENTATION_NORMAL
        }
    }

    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private fun replaceExtension(filename: String, newExt: String): String {
        val dot = filename.lastIndexOf('.')
        val base = if (dot > 0) filename.substring(0, dot) else filename
        return "$base.$newExt"
    }

    suspend fun presignAttachmentBytes(
        api: MezonApi,
        apiUrl: String,
        token: String,
        uploadFilename: String,
        mimeType: String,
        sizeBytes: Long,
        width: Int = 0,
        height: Int = 0,
        cdnBaseUrl: String,
    ): PresignedFileResult {
        if (willUseMultipart(sizeBytes)) {
            try {
                return presignMultipartBytes(
                    api, apiUrl, token, uploadFilename, mimeType, sizeBytes.toInt(),
                    width, height, cdnBaseUrl,
                )
            } catch (_: MultipartNotApplicable) {
                skipMultipartStartForSession = true
            } catch (e: Exception) {
                Log.w(TAG, "Multipart presign failed, falling back to single PUT: $uploadFilename", e)
            }
        }
        val presign = api.uploadAttachmentFile(
            apiUrl, token, uploadFilename, mimeType, sizeBytes.toInt(), width, height,
        )
        val cdnUrl = "$cdnBaseUrl/${presign.filename}"
        Log.d(TAG, "presign bytes file=$uploadFilename cdnUrl=$cdnUrl minioUrl=${presign.url}")
        return PresignedFileResult(
            serverFilename = presign.filename,
            cdnUrl = cdnUrl,
            plan = UploadExecutionPlan.SinglePut(presign.url, mimeType),
        )
    }

    suspend fun presignAttachmentFromFile(
        api: MezonApi,
        apiUrl: String,
        token: String,
        uploadFilename: String,
        mimeType: String,
        fileSize: Long,
        width: Int = 0,
        height: Int = 0,
        cdnBaseUrl: String,
    ): PresignedFileResult {
        if (willUseMultipart(fileSize)) {
            try {
                return presignMultipartFromFile(
                    api, apiUrl, token, uploadFilename, mimeType, fileSize.toInt(),
                    width, height, cdnBaseUrl,
                )
            } catch (_: MultipartNotApplicable) {
                skipMultipartStartForSession = true
            } catch (e: Exception) {
                Log.w(TAG, "Multipart presign failed, falling back to single PUT: $uploadFilename", e)
            }
        }
        val presign = api.uploadAttachmentFile(
            apiUrl, token, uploadFilename, mimeType, fileSize.toInt(), width, height,
        )
        val cdnUrl = "$cdnBaseUrl/${presign.filename}"
        Log.d(TAG, "presign file=$uploadFilename cdnUrl=$cdnUrl minioUrl=${presign.url}")
        return PresignedFileResult(
            serverFilename = presign.filename,
            cdnUrl = cdnUrl,
            plan = UploadExecutionPlan.SinglePut(presign.url, mimeType),
        )
    }

    suspend fun executeUploadPlan(
        api: MezonApi,
        apiUrl: String,
        token: String,
        plan: UploadExecutionPlan,
        bytes: ByteArray? = null,
        file: File? = null,
        onProgress: ((uploaded: Long, total: Long) -> Unit)? = null,
    ) {
        when (plan) {
            is UploadExecutionPlan.SinglePut -> {
                Log.d(TAG, "minio PUT start url=${plan.presignedUrl}")
                val total = bytes?.size?.toLong() ?: file?.length() ?: 0L
                onProgress?.invoke(0, total)
                when {
                    bytes != null -> api.putFileToPresignedUrl(plan.presignedUrl, bytes, plan.mimeType)
                    file != null -> api.putFileToPresignedUrlFromFile(plan.presignedUrl, file, plan.mimeType)
                    else -> throw IllegalArgumentException("executeUploadPlan SinglePut requires bytes or file")
                }
                onProgress?.invoke(total, total)
                Log.d(TAG, "minio PUT done url=${plan.presignedUrl}")
            }
            is UploadExecutionPlan.Multipart -> {
                Log.d(TAG, "minio multipart PUT start uploadId=${plan.uploadId} cdnFile=${plan.serverFilename} parts=${plan.presignedUrls.size}")
                executeMultipartPlan(api, apiUrl, token, plan, bytes, file, onProgress)
                Log.d(TAG, "minio multipart PUT done uploadId=${plan.uploadId} cdnFile=${plan.serverFilename}")
            }
        }
    }

    suspend fun uploadAttachmentBytes(
        api: MezonApi,
        apiUrl: String,
        token: String,
        uploadFilename: String,
        mimeType: String,
        bytes: ByteArray,
        width: Int = 0,
        height: Int = 0,
        cdnBaseUrl: String,
        onProgress: ((uploaded: Long, total: Long) -> Unit)? = null,
    ): UploadedFileResult {
        val totalBytes = bytes.size.toLong()
        onProgress?.invoke(0, totalBytes)
        val presigned = presignAttachmentBytes(
            api, apiUrl, token, uploadFilename, mimeType, totalBytes, width, height, cdnBaseUrl,
        )
        executeUploadPlan(api, apiUrl, token, presigned.plan, bytes = bytes, onProgress = onProgress)
        return UploadedFileResult(
            serverFilename = presigned.serverFilename,
            cdnUrl = presigned.cdnUrl,
        )
    }

    suspend fun uploadAttachmentFromUri(
        api: MezonApi,
        contentResolver: ContentResolver,
        uri: Uri,
        mimeType: String,
        cacheDir: File,
        apiUrl: String,
        token: String,
        uploadFilename: String,
        width: Int = 0,
        height: Int = 0,
        cdnBaseUrl: String,
        onProgress: ((uploaded: Long, total: Long) -> Unit)? = null,
    ): UploadedFileResult? {
        val bytes = readUriBytesSafely(contentResolver, uri, mimeType, cacheDir) ?: return null
        return uploadAttachmentBytes(
            api, apiUrl, token, uploadFilename, mimeType, bytes,
            width, height, cdnBaseUrl, onProgress,
        )
    }

    fun copyUriToTempFile(
        contentResolver: ContentResolver,
        uri: Uri,
        mimeType: String,
        cacheDir: File,
    ): File? {
        val input: InputStream = contentResolver.openInputStream(uri) ?: return null
        val tmpFile = File(cacheDir, "upload_" + System.nanoTime())
        try {
            input.use { i ->
                FileOutputStream(tmpFile).use { o ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = i.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > maxReadBytes(mimeType)) {
                            tmpFile.delete()
                            return null
                        }
                        o.write(buf, 0, n)
                    }
                }
            }
        } catch (e: Exception) {
            runCatching { tmpFile.delete() }
            return null
        }
        if (mimeType.equals("image/jpeg", true) ||
            mimeType.equals("image/jpg", true) ||
            mimeType.equals("image/heic", true) ||
            mimeType.equals("image/heif", true)
        ) {
            stripExifSensitive(tmpFile)
        }
        return tmpFile
    }

    suspend fun uploadAttachmentFromFile(
        api: MezonApi,
        apiUrl: String,
        token: String,
        uploadFilename: String,
        mimeType: String,
        file: File,
        fileSize: Long,
        width: Int = 0,
        height: Int = 0,
        cdnBaseUrl: String,
        onProgress: ((uploaded: Long, total: Long) -> Unit)? = null,
    ): UploadedFileResult {
        onProgress?.invoke(0, fileSize)
        val presigned = presignAttachmentFromFile(
            api, apiUrl, token, uploadFilename, mimeType, fileSize, width, height, cdnBaseUrl,
        )
        executeUploadPlan(api, apiUrl, token, presigned.plan, file = file, onProgress = onProgress)
        return UploadedFileResult(
            serverFilename = presigned.serverFilename,
            cdnUrl = presigned.cdnUrl,
        )
    }

    private suspend fun presignMultipartFromFile(
        api: MezonApi,
        apiUrl: String,
        token: String,
        uploadFilename: String,
        mimeType: String,
        sizeBytes: Int,
        width: Int,
        height: Int,
        cdnBaseUrl: String,
    ): PresignedFileResult {
        val requestedPartCount = multipartPartCount(sizeBytes.toLong())
        val start = api.multipartUploadAttachmentFileStart(
            apiUrl, token, uploadFilename, mimeType, sizeBytes, width, height,
            partCount = requestedPartCount,
        )
        val urls = start.urlsList
        val uploadId = start.uploadId
        // The object key must come from the server: falling back to the local name
        // silently builds a CDN url that points at nothing.
        val serverFilename = start.filename
        require(serverFilename.isNotEmpty()) { "multipart upload start returned an empty filename" }
        val cdnUrl = "$cdnBaseUrl/$serverFilename"
        if (urls.size == 1 && uploadId.isEmpty()) {
            Log.d(TAG, "presign multipart→single file=$uploadFilename cdnUrl=$cdnUrl minioUrl=${urls[0]}")
            return PresignedFileResult(
                serverFilename = serverFilename,
                cdnUrl = cdnUrl,
                plan = UploadExecutionPlan.SinglePut(urls[0], mimeType),
            )
        }
        if (urls.isEmpty() || uploadId.isEmpty()) {
            throw MultipartNotApplicable()
        }
        Log.d(TAG, "presign multipart file=$uploadFilename cdnUrl=$cdnUrl uploadId=$uploadId parts=${urls.size}")
        return PresignedFileResult(
            serverFilename = serverFilename,
            cdnUrl = cdnUrl,
            plan = UploadExecutionPlan.Multipart(
                uploadId = uploadId,
                presignedUrls = urls,
                serverFilename = serverFilename,
                mimeType = mimeType,
                fileSize = sizeBytes.toLong(),
            ),
        )
    }

    private suspend fun presignMultipartBytes(
        api: MezonApi,
        apiUrl: String,
        token: String,
        uploadFilename: String,
        mimeType: String,
        sizeBytes: Int,
        width: Int,
        height: Int,
        cdnBaseUrl: String,
    ): PresignedFileResult {
        val requestedPartCount = multipartPartCount(sizeBytes.toLong())
        val start = api.multipartUploadAttachmentFileStart(
            apiUrl, token, uploadFilename, mimeType, sizeBytes, width, height,
            partCount = requestedPartCount,
        )
        val urls = start.urlsList
        val uploadId = start.uploadId
        // The object key must come from the server: falling back to the local name
        // silently builds a CDN url that points at nothing.
        val serverFilename = start.filename
        require(serverFilename.isNotEmpty()) { "multipart upload start returned an empty filename" }
        val cdnUrl = "$cdnBaseUrl/$serverFilename"
        if (urls.size == 1 && uploadId.isEmpty()) {
            Log.d(TAG, "presign multipart→single bytes file=$uploadFilename cdnUrl=$cdnUrl minioUrl=${urls[0]}")
            return PresignedFileResult(
                serverFilename = serverFilename,
                cdnUrl = cdnUrl,
                plan = UploadExecutionPlan.SinglePut(urls[0], mimeType),
            )
        }
        if (urls.isEmpty() || uploadId.isEmpty()) {
            throw MultipartNotApplicable()
        }
        Log.d(TAG, "presign multipart bytes file=$uploadFilename cdnUrl=$cdnUrl uploadId=$uploadId parts=${urls.size}")
        return PresignedFileResult(
            serverFilename = serverFilename,
            cdnUrl = cdnUrl,
            plan = UploadExecutionPlan.Multipart(
                uploadId = uploadId,
                presignedUrls = urls,
                serverFilename = serverFilename,
                mimeType = mimeType,
                fileSize = sizeBytes.toLong(),
            ),
        )
    }

    private suspend fun executeMultipartPlan(
        api: MezonApi,
        apiUrl: String,
        token: String,
        plan: UploadExecutionPlan.Multipart,
        bytes: ByteArray?,
        file: File?,
        onProgress: ((uploaded: Long, total: Long) -> Unit)?,
    ) {
        val urls = plan.presignedUrls
        val partCount = urls.size
        onProgress?.invoke(0, plan.fileSize)
        val partResults = if (file != null) {
            val semaphore = Semaphore(PARALLEL_PART_UPLOADS_FILE)
            val uploadedLock = Any()
            var uploadedBytes = 0L
            coroutineScope {
                urls.mapIndexed { index, url ->
                    async {
                        semaphore.withPermit {
                            val offset = index.toLong() * MULTIPART_PART_SIZE
                            val endExclusive = if (index == partCount - 1) {
                                plan.fileSize
                            } else {
                                offset + MULTIPART_PART_SIZE
                            }
                            val len = endExclusive - offset
                            val etag = api.putFilePartRangeToPresignedUrl(
                                url, file, offset, len, plan.mimeType,
                            )
                            synchronized(uploadedLock) {
                                uploadedBytes += len
                                onProgress?.invoke(uploadedBytes, plan.fileSize)
                            }
                            (index + 1) to etag
                        }
                    }
                }.awaitAll().sortedBy { it.first }
            }
        } else {
            val data = bytes ?: throw IllegalArgumentException("executeMultipartPlan requires bytes or file")
            val semaphore = Semaphore(PARALLEL_PART_UPLOADS_BYTES)
            val uploadedLock = Any()
            var uploadedBytes = 0L
            coroutineScope {
                urls.mapIndexed { index, url ->
                    async {
                        semaphore.withPermit {
                            val offset = index * MULTIPART_PART_SIZE
                            val end = if (index == partCount - 1) {
                                data.size
                            } else {
                                offset + MULTIPART_PART_SIZE
                            }
                            val partBytes = data.copyOfRange(offset, end)
                            val etag = api.putFilePartToPresignedUrl(url, partBytes, plan.mimeType)
                            synchronized(uploadedLock) {
                                uploadedBytes += partBytes.size
                                onProgress?.invoke(uploadedBytes, plan.fileSize)
                            }
                            (index + 1) to etag
                        }
                    }
                }.awaitAll().sortedBy { it.first }
            }
        }
        api.multipartUploadAttachmentFileFinish(
            apiUrl, token, plan.uploadId, partResults, plan.serverFilename,
        )
    }

    fun readUriBytesSafely(
        contentResolver: ContentResolver,
        uri: Uri,
        mimeType: String,
        cacheDir: File
    ): ByteArray? {
        val tmpInput: InputStream = contentResolver.openInputStream(uri) ?: return null
        val tmpFile = File(cacheDir, "upload_" + System.nanoTime())
        try {
            tmpInput.use { input ->
                FileOutputStream(tmpFile).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > maxReadBytes(mimeType)) {
                            tmpFile.delete()
                            return null
                        }
                        output.write(buf, 0, n)
                    }
                }
            }

            if (mimeType.equals("image/jpeg", true) ||
                mimeType.equals("image/jpg", true) ||
                mimeType.equals("image/heic", true) ||
                mimeType.equals("image/heif", true)
            ) {
                stripExifSensitive(tmpFile)
            }

            return tmpFile.readBytes()
        } finally {
            runCatching { tmpFile.delete() }
        }
    }

    private fun stripExifSensitive(file: File) {
        runCatching {
            val exif = ExifInterface(file.absolutePath)
            EXIF_SENSITIVE_TAGS.forEach { tag -> exif.setAttribute(tag, null) }
            exif.saveAttributes()
        }
    }

    private val EXIF_SENSITIVE_TAGS = arrayOf(
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD,
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL
    )
}
