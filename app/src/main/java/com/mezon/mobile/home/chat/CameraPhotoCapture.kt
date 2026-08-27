package com.mezon.mobile.home.chat

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

data class CameraPhotoCapture(
    val file: File,
    val uri: Uri
) {
    fun captureIntent(): Intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
        putExtra(MediaStore.EXTRA_OUTPUT, uri)
        clipData = ClipData.newRawUri("camera-photo", uri)
        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun toAttachment(): AttachmentPickerItem? {
        if (!file.isFile || file.length() <= 0L) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        return AttachmentPickerItem(
            id = uri.hashCode().toLong(),
            uri = uri,
            path = uri.toString(),
            filename = file.name,
            mimeType = "image/jpeg",
            width = bounds.outWidth.coerceAtLeast(0),
            height = bounds.outHeight.coerceAtLeast(0),
            size = file.length(),
            duration = 0,
            isVideo = false,
            ownedCachePath = file.absolutePath
        )
    }

    fun discard() {
        file.delete()
    }

    companion object {
        const val CACHE_DIRECTORY = "camera"

        fun create(context: Context): CameraPhotoCapture? {
            val directory = File(context.cacheDir, CACHE_DIRECTORY).apply { mkdirs() }
            val file = File(directory, "camera-${UUID.randomUUID()}.jpg")
            if (!file.createNewFile()) return null
            return CameraPhotoCapture(file, uriFor(context, file))
        }

        fun restore(context: Context, path: String): CameraPhotoCapture? {
            val file = File(path)
            if (!file.isFile) return null
            return CameraPhotoCapture(file, uriFor(context, file))
        }

        private fun uriFor(context: Context, file: File): Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }
}
