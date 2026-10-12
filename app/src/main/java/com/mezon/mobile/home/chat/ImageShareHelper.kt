package com.mezon.mobile.home.chat

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import com.mezon.mobile.R
import com.mezon.mobile.network.CdnSigner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

internal object ImageShareHelper {
    suspend fun shareImage(
        context: Context,
        url: String,
        ioDispatcher: CoroutineDispatcher,
        mainDispatcher: CoroutineDispatcher
    ): Boolean {
        if (url.isBlank()) return false
        return try {
            withContext(ioDispatcher) {
                val mime = mimeTypeForUrl(url)
                val ext = when (mime) {
                    "image/gif" -> "gif"
                    "image/png" -> "png"
                    "image/webp" -> "webp"
                    else -> "jpg"
                }
                val dir = File(context.cacheDir, "clipboard_images").apply { mkdirs() }
                val destFile = File.createTempFile("share_", ".$ext", dir)
                try {
                    downloadToFile(url, destFile)
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", destFile)
                    withContext(mainDispatcher) {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = mime
                            putExtra(Intent.EXTRA_STREAM, uri)
                            clipData = ClipData.newRawUri("", uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        val resInfoList = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                        for (resolveInfo in resInfoList) {
                            context.grantUriPermission(resolveInfo.activityInfo.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        val chooser = Intent.createChooser(intent, context.getString(R.string.action_share_image)).apply {
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(chooser)
                    }
                } catch (e: Exception) {
                    destFile.delete()
                    throw e
                }
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    fun mimeTypeForUrl(url: String): String {
        val lower = url.lowercase(Locale.US)
        return when {
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".gif") || lower.contains("tenor.com") -> "image/gif"
            else -> "image/jpeg"
        }
    }

    private fun downloadToFile(url: String, destFile: File) {
        val conn = URL(CdnSigner.requestUrlBlocking(url).url).openConnection() as HttpURLConnection
        try {
            conn.connect()
            if (conn.responseCode !in 200..299) {
                throw Exception("HTTP Error: ${conn.responseCode}")
            }
            conn.inputStream.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            conn.disconnect()
        }
    }
}
