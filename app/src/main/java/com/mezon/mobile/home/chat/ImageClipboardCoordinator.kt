package com.mezon.mobile.home.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.mezon.mobile.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImageClipboardCoordinator @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val okHttpClient: OkHttpClient,
) {

    fun clipboardLooksLikeImage(context: Context, clipboard: ClipboardManager): Boolean {
        return resolvePasteImageUri(context, clipboard.primaryClip) != null
    }

    fun resolvePasteImageUri(context: Context, clipboard: ClipboardManager): Uri? =
        resolvePasteImageUri(context, clipboard.primaryClip)

    suspend fun duplicateClipUriToAttachment(context: Context, sourceUri: Uri): AttachmentPickerItem? =
        withContext(ioDispatcher) {
            duplicateClipUriToAttachmentSync(context, sourceUri)
        }

    suspend fun copyRemoteUrlToClipboard(
        displayContext: Context,
        imageUrl: String,
        mimeHint: String?
    ): Boolean = withContext(ioDispatcher) {
        copyRemoteUrlToClipboardSync(displayContext.applicationContext, imageUrl, mimeHint)
    }

    fun resolvePrimaryImageUrlForCopy(msg: MessageEntity): String? {
        val imgs = msg.allImageAttachments.filter { att ->
            if (isVideoAttachmentType(att.filetype)) return@filter false
            isImageAttachmentType(att.filetype) ||
                isGifAttachment(att.filetype, att.filename, att.url)
        }
        if (imgs.isNotEmpty()) return imgs.firstOrNull()?.url?.takeIf { it.isNotBlank() }
        if (msg.attachmentUrl.isNotBlank() && msg.hasMedia) {
            if (isImageAttachmentType(msg.attachmentFiletype) ||
                isGifAttachment(msg.attachmentFiletype, msg.attachmentFilename, msg.attachmentUrl)
            ) {
                return msg.attachmentUrl
            }
        }
        return null
    }

    private fun resolvePasteImageUri(context: Context, clip: ClipData?): Uri? {
        if (clip == null) return null
        val resolver = context.contentResolver
        for (i in 0 until clip.itemCount) {
            val item = clip.getItemAt(i)
            item.uri?.let { u ->
                if (uriLooksLikePasteableImage(resolver, u)) return u
            }
            item.text?.let { t ->
                val s = t.toString()
                if (s.startsWith("content://") &&
                    (s.contains("image", ignoreCase = true) ||
                            s.contains("photo", ignoreCase = true) ||
                            s.contains("media", ignoreCase = true))
                ) {
                    val u = Uri.parse(s)
                    if (uriLooksLikePasteableImage(resolver, u)) return u
                }
            }
            item.intent?.data?.let { u ->
                if (uriLooksLikePasteableImage(resolver, u)) return u
            }
        }
        return null
    }

    private fun uriLooksLikePasteableImage(resolver: ContentResolver, u: Uri): Boolean {
        val type = resolver.getType(u)
        if (type?.startsWith("image/") == true) return true
        if (type?.startsWith("video/") == true) return false
        val path = u.toString().lowercase(Locale.US)
        if ((path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") ||
                    path.endsWith(".webp") || path.endsWith(".gif") ||
                    path.contains("/image", ignoreCase = true)) &&
            path.startsWith("content://")
        ) {
            try {
                resolver.openInputStream(u)?.use {
                    val head = ByteArray(24)
                    val read = it.read(head)
                    if (read <= 8) return@use
                    val isRiff = head[0].toInt() == 0x52 && head[1].toInt() == 0x49 &&
                            head[2].toInt() == 0x46 && head[3].toInt() == 0x46
                    val isPngSig = head[0].toInt() == 0x89 && head[1].toInt() == 0x50 &&
                            head[2].toInt() == 0x4E && head[3].toInt() == 0x47
                    val isJpeg = head[0].toInt() == 0xFF && head[1].toInt() == 0xD8
                    val isGif = head[0].toInt() == 0x47 && head[1].toInt() == 0x49 && head[2].toInt() == 0x46
                    if (isPngSig || isJpeg || isGif || isRiff) return true
                }
            } catch (_: Exception) {
                return false
            }
        }
        return false
    }

    private fun duplicateClipUriToAttachmentSync(context: Context, sourceUri: Uri): AttachmentPickerItem? {
        val resolver = context.contentResolver
        var mime = resolver.getType(sourceUri) ?: inferMimeFromUri(sourceUri)
        if (mime.isEmpty()) mime = "image/jpeg"
        var displayName = "image"
        var sizeFromQuery = 0L
        resolver.query(sourceUri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIdx >= 0) displayName = cursor.getString(nameIdx) ?: displayName
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                    sizeFromQuery = cursor.getLong(sizeIdx)
                }
            }
        }
        val input = try {
            resolver.openInputStream(sourceUri)
        } catch (_: SecurityException) {
            null
        } ?: return null
        val dir = File(context.cacheDir, "clipboard_paste").apply { mkdirs() }
        val ext = extensionForMime(mime, displayName, sourceUri)
        val outFile = File(dir, "paste_${System.currentTimeMillis()}.$ext")
        input.use { ins ->
            FileOutputStream(outFile).use { out -> ins.copyTo(out) }
        }
        val fileSize = if (sizeFromQuery > 0L) sizeFromQuery else outFile.length()
        if (fileSize > AttachmentPickerItem.IMAGE_MAX_FILE_SIZE) {
            outFile.delete()
            return null
        }
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(outFile.absolutePath, opts)
        val bw = opts.outWidth.takeIf { it > 0 } ?: 0
        val bh = opts.outHeight.takeIf { it > 0 } ?: 0
        val authority = "${context.packageName}.fileprovider"
        val contentUri = FileProvider.getUriForFile(context, authority, outFile)
        return AttachmentPickerItem(
            id = contentUri.hashCode().toLong(),
            uri = contentUri,
            path = contentUri.toString(),
            filename = displayName.ifEmpty { outFile.name },
            mimeType = mime,
            width = bw,
            height = bh,
            size = fileSize,
            duration = 0,
            isVideo = false
        )
    }

    private fun inferMimeFromUri(uri: Uri): String {
        val path = uri.toString().lowercase(Locale.US)
        return when {
            path.endsWith(".png") -> "image/png"
            path.endsWith(".webp") -> "image/webp"
            path.endsWith(".gif") -> "image/gif"
            path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
            else -> "image/jpeg"
        }
    }

    private fun extensionForMime(mime: String, displayName: String, sourceUri: Uri): String {
        MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { return it }
        val fromName = displayName.substringAfterLast('.', "").lowercase(Locale.US)
        if (fromName.isNotEmpty() && fromName.length <= 5) return fromName
        val fromPath = sourceUri.path?.substringAfterLast('.', "")?.lowercase(Locale.US).orEmpty()
        if (fromPath.isNotEmpty() && fromPath.length <= 5) return fromPath
        return "jpg"
    }

    private fun copyRemoteUrlToClipboardSync(appContext: Context, imageUrl: String, mimeHint: String?): Boolean {
        val req = Request.Builder().url(imageUrl).build()
        val response = okHttpClient.newCall(req).execute()
        if (!response.isSuccessful) return false
        val body = response.body ?: return false
        val mediaType = body.contentType()
        val mime = when {
            mediaType != null -> "${mediaType.type}/${mediaType.subtype}"
            !mimeHint.isNullOrBlank() -> mimeHint
            else -> inferMimeFromUrl(imageUrl)
        }
        val bytes = body.bytes()
        if (bytes.isEmpty()) return false
        val dir = File(appContext.cacheDir, "clipboard_images").apply { mkdirs() }
        val ext = extensionForMime(mime, imageUrl.substringAfterLast('/'), Uri.parse(imageUrl))
        val outFile = File(dir, "clipboard_${System.currentTimeMillis()}.$ext")
        FileOutputStream(outFile).use { it.write(bytes) }
        val authority = "${appContext.packageName}.fileprovider"
        val contentUri = FileProvider.getUriForFile(appContext, authority, outFile)
        val resolver = appContext.contentResolver
        val clip = ClipData.newUri(resolver, "Image", contentUri)
        val cb = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(clip)
        return true
    }

    private fun inferMimeFromUrl(url: String): String {
        val lower = url.lowercase(Locale.US)
        return when {
            lower.endsWith(".png") -> "image/png"
            lower.contains(".webp") -> "image/webp"
            lower.endsWith(".gif") || lower.contains("tenor.com", ignoreCase = true) -> "image/gif"
            else -> "image/jpeg"
        }
    }
}
