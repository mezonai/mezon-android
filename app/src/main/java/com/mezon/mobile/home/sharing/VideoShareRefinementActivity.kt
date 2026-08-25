package com.mezon.mobile.home.sharing

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.FileProvider
import com.mezon.mobile.MainActivity
import com.mezon.mobile.R
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.home.chat.AttachmentInfo
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal object ExistingVideoShareStore {
    private const val SESSION_MAX_AGE_MS = 10L * 60L * 1000L

    private data class Entry(
        val attachment: AttachmentInfo,
        val createdAt: Long
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    fun put(attachment: AttachmentInfo): String {
        val now = System.currentTimeMillis()
        entries.forEach { (token, entry) ->
            if (now - entry.createdAt > SESSION_MAX_AGE_MS) entries.remove(token)
        }
        return UUID.randomUUID().toString().also { token ->
            entries[token] = Entry(attachment, now)
        }
    }

    fun consume(token: String?): AttachmentInfo? {
        if (token.isNullOrBlank()) return null
        val entry = entries.remove(token) ?: return null
        return entry.attachment.takeIf {
            System.currentTimeMillis() - entry.createdAt <= SESSION_MAX_AGE_MS
        }
    }
}

internal object VideoShareRefinementContract {
    const val EXTRA_INTERNAL_SHARE_TOKEN = "com.mezon.mobile.extra.INTERNAL_VIDEO_SHARE_TOKEN"

    private const val ACTION_PREPARE = "com.mezon.mobile.action.PREPARE_VIDEO_SHARE"
    private const val EXTRA_URL = "video_url"
    private const val EXTRA_THUMB = "video_thumb"
    private const val EXTRA_WIDTH = "video_width"
    private const val EXTRA_HEIGHT = "video_height"
    private const val EXTRA_FILENAME = "video_filename"
    private const val EXTRA_FILETYPE = "video_filetype"
    private const val EXTRA_SIZE = "video_size"
    private const val EXTRA_DURATION = "video_duration"

    fun createPreparationIntent(context: Context, attachment: AttachmentInfo): Intent {
        val nonce = UUID.randomUUID().toString()
        return Intent(context, VideoShareRefinementActivity::class.java).apply {
            action = ACTION_PREPARE
            data = Uri.parse("mezon://video-share-preparation/$nonce")
            putExtra(EXTRA_URL, attachment.url)
            putExtra(EXTRA_THUMB, attachment.thumb)
            putExtra(EXTRA_WIDTH, attachment.width)
            putExtra(EXTRA_HEIGHT, attachment.height)
            putExtra(EXTRA_FILENAME, attachment.filename)
            putExtra(EXTRA_FILETYPE, attachment.filetype)
            putExtra(EXTRA_SIZE, attachment.size)
            putExtra(EXTRA_DURATION, attachment.duration)
        }
    }

    fun readAttachment(intent: Intent): AttachmentInfo? {
        val url = intent.getStringExtra(EXTRA_URL)?.trim().orEmpty()
        if (url.isEmpty()) return null
        val mimeType = intent.getStringExtra(EXTRA_FILETYPE)
            ?.trim()
            ?.lowercase(Locale.US)
            ?.takeIf { it.startsWith("video/") }
            ?: mimeTypeFromUrl(url)
        val filename = intent.getStringExtra(EXTRA_FILENAME)
            ?.takeIf { it.isNotBlank() }
            ?: filenameFromUrl(url, mimeType)
        return AttachmentInfo(
            url = url,
            thumb = intent.getStringExtra(EXTRA_THUMB).orEmpty(),
            width = intent.getIntExtra(EXTRA_WIDTH, 0),
            height = intent.getIntExtra(EXTRA_HEIGHT, 0),
            filename = filename,
            filetype = mimeType,
            size = intent.getIntExtra(EXTRA_SIZE, 0),
            duration = intent.getIntExtra(EXTRA_DURATION, 0)
        )
    }

    fun mimeTypeFromUrl(url: String): String = when (extensionFromUrl(url)) {
        "m4v" -> "video/x-m4v"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "3gp" -> "video/3gpp"
        "3g2" -> "video/3gpp2"
        "mpg", "mpeg" -> "video/mpeg"
        "ogv" -> "video/ogg"
        "ts" -> "video/mp2t"
        "avi" -> "video/x-msvideo"
        "flv" -> "video/x-flv"
        else -> "video/mp4"
    }

    fun filenameFromUrl(url: String, mimeType: String): String {
        val remoteName = Uri.parse(url).lastPathSegment?.takeIf { it.isNotBlank() }
        return remoteName ?: "video_${System.currentTimeMillis()}.${extensionForMimeType(mimeType)}"
    }

    fun extensionForMimeType(mimeType: String): String = when (mimeType.lowercase(Locale.US)) {
        "video/x-m4v" -> "m4v"
        "video/quicktime" -> "mov"
        "video/webm" -> "webm"
        "video/x-matroska" -> "mkv"
        "video/3gpp" -> "3gp"
        "video/3gpp2" -> "3g2"
        "video/mpeg" -> "mpg"
        "video/ogg" -> "ogv"
        "video/mp2t" -> "ts"
        "video/x-msvideo" -> "avi"
        "video/x-flv" -> "flv"
        else -> "mp4"
    }

    private fun extensionFromUrl(url: String): String {
        return Uri.parse(url).lastPathSegment
            ?.substringAfterLast('.', "")
            ?.lowercase(Locale.US)
            ?.takeIf { it.isNotBlank() }
            ?: "mp4"
    }
}

class VideoShareRefinementActivity : ComponentActivity() {
    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val SHARE_CACHE_MAX_BYTES = 512L * 1024L * 1024L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile
    private var activeConnection: HttpURLConnection? = null

    @Volatile
    private var partialFile: File? = null
    private var flowCompleted = false
    private var isResumed = false
    private var pendingChooser: Intent? = null
    private lateinit var progressLabel: TextView
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                cancelPreparationAndFinish()
            }
        })

        val attachment = VideoShareRefinementContract.readAttachment(intent)
        if (attachment == null) {
            cancelPreparationAndFinish()
            return
        }

        showProgressOverlay()
        scope.launch {
            try {
                val downloaded = withContext(Dispatchers.IO) {
                    downloadForExternalTarget(attachment)
                }
                val uri = FileProvider.getUriForFile(
                    this@VideoShareRefinementActivity,
                    "$packageName.fileprovider",
                    downloaded
                )
                val targetIntent = Intent(Intent.ACTION_SEND).apply {
                    type = attachment.filetype
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri("video", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val mezonComponent = ComponentName(this@VideoShareRefinementActivity, MainActivity::class.java)
                val chooser = Intent.createChooser(
                    targetIntent,
                    getString(R.string.action_share_video)
                ).apply {
                    putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(mezonComponent))
                }
                pendingChooser = chooser
                openChooserIfReady()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Toast.makeText(this@VideoShareRefinementActivity, R.string.message_toast_share_failed, Toast.LENGTH_SHORT).show()
                cancelPreparationAndFinish()
            }
        }
    }

    private fun showProgressOverlay() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(0x99000000.toInt())
            isClickable = true
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(LayoutHelper.dp(28), LayoutHelper.dp(24), LayoutHelper.dp(28), LayoutHelper.dp(24))
            background = GradientDrawable().apply {
                setColor(0xFF20212B.toInt())
                cornerRadius = LayoutHelper.dpf(16f)
            }
        }
        val spinner = ProgressBar(this).apply { isIndeterminate = true }
        card.addView(spinner, LinearLayout.LayoutParams(LayoutHelper.dp(40), LayoutHelper.dp(40)))

        progressLabel = TextView(this).apply {
            setText(R.string.video_share_preparing)
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
        }
        card.addView(
            progressLabel,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = LayoutHelper.dp(14)
            }
        )
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
        }
        card.addView(
            progressBar,
            LinearLayout.LayoutParams(LayoutHelper.dp(240), LayoutHelper.dp(8)).apply {
                topMargin = LayoutHelper.dp(16)
            }
        )
        root.addView(
            card,
            FrameLayout.LayoutParams(LayoutHelper.dp(300), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        )
        setContentView(root)
    }

    private suspend fun downloadForExternalTarget(attachment: AttachmentInfo): File {
        currentCoroutineContext().ensureActive()
        val directory = File(cacheDir, "shared_videos")
        if (!directory.exists() && !directory.mkdirs()) error("Unable to create share cache")

        val source = URL(attachment.url)
        require(source.protocol == "https" || source.protocol == "http") { "Unsupported video URL" }
        val connection = (source.openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
        activeConnection = connection
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")

            val totalBytes = connection.contentLengthLong.takeIf { it > 0L }
                ?: attachment.size.toLong().takeIf { it > 0L }
                ?: 0L
            trimShareCache(directory, totalBytes)
            if (totalBytes > 0L && directory.usableSpace < totalBytes) {
                error("Insufficient storage for shared video")
            }

            val extension = VideoShareRefinementContract.extensionForMimeType(attachment.filetype)
            val destination = File(directory, "share_${System.currentTimeMillis()}_${UUID.randomUUID()}.$extension")
            partialFile = destination
            var copiedBytes = 0L
            var lastProgressPercent = -1
            connection.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copiedBytes += read
                        if (totalBytes > 0L) {
                            val percent = ((copiedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
                            if (percent != lastProgressPercent) {
                                lastProgressPercent = percent
                                withContext(Dispatchers.Main.immediate) { updateProgress(percent) }
                            }
                        }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            try {
                trimShareCache(directory, destination.length(), destination)
            } catch (_: Exception) {
                // Cache cleanup must not invalidate a completed download.
            }
            return destination
        } catch (e: Exception) {
            partialFile?.delete()
            partialFile = null
            currentCoroutineContext().ensureActive()
            throw e
        } finally {
            if (activeConnection === connection) activeConnection = null
            connection.disconnect()
        }
    }

    private fun updateProgress(percent: Int) {
        if (isFinishing || isDestroyed) return
        progressBar.isIndeterminate = false
        progressBar.progress = percent
        progressLabel.text = getString(R.string.video_share_preparing_progress, percent)
    }

    private fun trimShareCache(directory: File, incomingBytes: Long, retainedFile: File? = null) {
        val cachedFiles = directory.listFiles()
            ?.filter { file ->
                file.isFile && file.name.startsWith("share_") && file != retainedFile
            }
            ?.sortedBy { it.lastModified() }
            .orEmpty()
        var cachedBytes = cachedFiles.sumOf { it.length() }
        val allowedCachedBytes = (SHARE_CACHE_MAX_BYTES - incomingBytes).coerceAtLeast(0L)
        if (cachedBytes <= allowedCachedBytes) return

        for (file in cachedFiles) {
            val fileBytes = file.length()
            if (runCatching { file.delete() }.getOrDefault(false)) {
                cachedBytes -= fileBytes
            }
            if (cachedBytes <= allowedCachedBytes) return
        }
        error("Unable to trim shared video cache")
    }

    private fun openChooserIfReady() {
        if (flowCompleted || !isResumed) return
        val chooser = pendingChooser ?: return
        try {
            startActivity(chooser)
            pendingChooser = null
            partialFile = null
            flowCompleted = true
            finish()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.message_toast_share_failed, Toast.LENGTH_SHORT).show()
            cancelPreparationAndFinish()
        }
    }

    private fun cancelPreparationAndFinish() {
        if (flowCompleted) return
        flowCompleted = true
        activeConnection?.disconnect()
        partialFile?.delete()
        partialFile = null
        scope.cancel()
        finish()
    }

    override fun onResume() {
        super.onResume()
        isResumed = true
        openChooserIfReady()
    }

    override fun onPause() {
        isResumed = false
        super.onPause()
    }

    override fun onDestroy() {
        activeConnection?.disconnect()
        if (!flowCompleted) partialFile?.delete()
        scope.cancel()
        super.onDestroy()
    }
}
