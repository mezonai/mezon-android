package com.mezon.mobile.qr

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

fun saveBitmapToGallery(context: Context, bitmap: Bitmap, displayName: String): Uri? {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Mezon")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val item = resolver.insert(collection, values) ?: return null
    var outputStream: OutputStream? = null
    return try {
        outputStream = resolver.openOutputStream(item)
        if (outputStream == null) return null
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(item, values, null, null)
        }
        item
    } catch (e: Exception) {
        resolver.delete(item, null, null)
        null
    } finally {
        outputStream?.close()
    }
}

fun shareBitmap(context: Context, bitmap: Bitmap, displayName: String, message: String) {
    val cacheDir = File(context.cacheDir, "qr")
    if (!cacheDir.exists()) cacheDir.mkdirs()
    val file = File(cacheDir, "$displayName.png")
    FileOutputStream(file).use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.qrfileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TEXT, message)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, null))
}

fun renderInviteBitmap(context: Context, content: @androidx.compose.runtime.Composable () -> Unit, sizePx: Int): Bitmap {
    val composeView = ComposeView(context)
    composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    composeView.setContent(content)
    val spec = android.view.View.MeasureSpec.makeMeasureSpec(sizePx, android.view.View.MeasureSpec.EXACTLY)
    composeView.measure(spec, spec)
    composeView.layout(0, 0, sizePx, sizePx)
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    composeView.draw(canvas)
    return bitmap
}

fun saveBitmapLegacy(context: Context, bitmap: Bitmap, displayName: String): Uri? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return null
    val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
    val dir = File(pictures, "Mezon")
    if (!dir.exists()) dir.mkdirs()
    val file = File(dir, "$displayName.png")
    FileOutputStream(file).use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    }
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.DATA, file.absolutePath)
    }
    return context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
}

