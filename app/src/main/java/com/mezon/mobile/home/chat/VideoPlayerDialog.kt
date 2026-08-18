package com.mezon.mobile.home.chat

import android.animation.ObjectAnimator
import android.app.Dialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.mezon.mobile.BuildConfig
import com.mezon.mobile.MainActivity
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.sharing.VideoShareRefinementContract
import com.mezon.mobile.ui.cells.BackupImageView
import com.mezon.mobile.ui.cells.PopupMenu
import com.mezon.mobile.ui.cells.ToastOverlay
import com.mezon.mobile.util.avatarImgproxyUrl
import dagger.hilt.android.EntryPointAccessors
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val VIDEO_SEEK_INCREMENT_MS = 15_000L
private const val VIDEO_CONTROLLER_TIMEOUT_MS = 3_000
private const val VIDEO_SAVE_PROGRESS_POLL_INTERVAL_MS = 300L
private const val NO_PENDING_DOWNLOAD_ID = -1L
private val VIDEO_ANONYMOUS_USER_ID = BuildConfig.MEZON_ANONYMOUS_USER_ID.toLongOrNull() ?: 0L

private fun Context.findLifecycleOwner(): LifecycleOwner? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is LifecycleOwner) return current
        val base = current.baseContext
        if (base === current) break
        current = base
    }
    return current as? LifecycleOwner
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoPlayerDialog(context: Context) : Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {

    companion object {
        private var activeInstance: java.lang.ref.WeakReference<VideoPlayerDialog>? = null

        fun dismissActiveIfShowing() {
            activeInstance?.get()?.takeIf { it.isShowing }?.dismiss()
            activeInstance = null
        }
    }

    data class VideoItem(
        val url: String,
        val senderName: String,
        val senderAvatarUrl: String?,
        val timestamp: Long,
        val uploaderId: Long,
        val thumbnailUrl: String = "",
        val filename: String = "",
        val mimeType: String = "",
        val width: Int = 0,
        val height: Int = 0,
        val size: Int = 0,
        val duration: Int = 0
    )

    private val backgroundDrawable = ColorDrawable(Color.BLACK)
    private val playerView: PlayerView
    private val topBar: FrameLayout
    private val avatarView: BackupImageView
    private val nameLabel: android.widget.TextView
    private val dateLabel: android.widget.TextView
    private val saveProgressOverlay: FrameLayout
    private val saveProgressLabel: TextView
    private val saveProgressBar: ProgressBar

    private var player: ExoPlayer? = null
    private lateinit var currentItem: VideoItem
    private var optionsMenu: PopupMenu? = null
    private var lastMenuDismissTime = 0L
    private var saveProgressJob: Job? = null
    private var pendingDownloadId = NO_PENDING_DOWNLOAD_ID
    private var isDownloadReceiverRegistered = false
    private val hostLifecycleOwner = context.findLifecycleOwner()
    private var isLifecycleObserverRegistered = false
    private var playbackPositionMs = 0L
    private var resumePlaybackOnForeground = false
    private var stoppedForLifecycle = false

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onPause(owner: LifecycleOwner) {
            if (!isShowing) return
            player?.let { currentPlayer ->
                playbackPositionMs = currentPlayer.currentPosition
                resumePlaybackOnForeground = currentPlayer.playWhenReady &&
                    currentPlayer.playbackState != Player.STATE_ENDED
                currentPlayer.pause()
            }
        }

        override fun onStop(owner: LifecycleOwner) {
            if (!isShowing) return
            player?.let { playbackPositionMs = it.currentPosition }
            releasePlayer()
            stoppedForLifecycle = true
        }

        override fun onStart(owner: LifecycleOwner) {
            if (!isShowing || !stoppedForLifecycle || !::currentItem.isInitialized) return
            initializePlayer(playbackPositionMs, playWhenReady = false)
            stoppedForLifecycle = false
        }

        override fun onResume(owner: LifecycleOwner) {
            if (!isShowing || !resumePlaybackOnForeground) return
            player?.play()
            resumePlaybackOnForeground = false
        }

        override fun onDestroy(owner: LifecycleOwner) {
            if (isShowing) {
                dismiss()
            } else {
                unregisterLifecycleObserver()
                releasePlayer()
            }
        }
    }

    private val downloadCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, NO_PENDING_DOWNLOAD_ID)
            handleDownloadTerminalStatus(downloadId, getDownloadStatus(downloadId))
        }
    }

    private val entryPoint: FragmentEntryPoint by lazy {
        EntryPointAccessors.fromApplication(context.applicationContext, FragmentEntryPoint::class.java)
    }

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        window?.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        window?.setBackgroundDrawable(backgroundDrawable)

        val root = FrameLayout(context)

        playerView = PlayerView(context).apply {
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            setBackgroundColor(Color.BLACK)
            useController = true
            controllerAutoShow = true
            controllerHideOnTouch = true
            controllerShowTimeoutMs = VIDEO_CONTROLLER_TIMEOUT_MS
            setShowPreviousButton(false)
            setShowNextButton(false)
            setShowRewindButton(true)
            setShowFastForwardButton(true)
        }
        root.addView(
            playerView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        topBar = FrameLayout(context).apply {
            setBackgroundColor(0x99000000.toInt())
            isClickable = true
        }

        val backButton = ImageView(context).apply {
            setImageResource(R.drawable.ic_arrow_back)
            setColorFilter(Color.WHITE)
            val padding = LayoutHelper.dp(16)
            setPadding(padding, padding, padding, padding)
            setOnClickListener { dismissWithAnimation() }
        }
        topBar.addView(
            backButton,
            FrameLayout.LayoutParams(
                LayoutHelper.dp(56),
                LayoutHelper.dp(56),
                Gravity.START or Gravity.CENTER_VERTICAL
            )
        )

        avatarView = BackupImageView(context).apply {
            setRoundRadius(LayoutHelper.dp(20))
        }
        topBar.addView(
            avatarView,
            FrameLayout.LayoutParams(
                LayoutHelper.dp(40),
                LayoutHelper.dp(40),
                Gravity.START or Gravity.CENTER_VERTICAL
            ).apply {
                marginStart = LayoutHelper.dp(56)
            }
        )

        val textLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        nameLabel = android.widget.TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            isSingleLine = true
        }
        dateLabel = android.widget.TextView(context).apply {
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 13f
            isSingleLine = true
        }
        textLayout.addView(nameLabel, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        textLayout.addView(dateLabel, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        topBar.addView(
            textLayout,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.CENTER_VERTICAL
            ).apply {
                marginStart = LayoutHelper.dp(108)
                marginEnd = LayoutHelper.dp(56)
            }
        )

        val moreButton = ImageView(context).apply {
            setImageResource(R.drawable.ic_more_vertical_24)
            setColorFilter(Color.WHITE)
            val padding = LayoutHelper.dp(16)
            setPadding(padding, padding, padding, padding)
            setOnClickListener { showMoreOptions(it) }
        }
        topBar.addView(
            moreButton,
            FrameLayout.LayoutParams(
                LayoutHelper.dp(56),
                LayoutHelper.dp(56),
                Gravity.END or Gravity.CENTER_VERTICAL
            )
        )

        root.addView(
            topBar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                LayoutHelper.dp(56),
                Gravity.TOP
            ).apply {
                topMargin = AndroidUtilities.statusBarHeight
            }
        )

        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                setHeaderVisible(visibility == View.VISIBLE)
            }
        )

        saveProgressOverlay = FrameLayout(context).apply {
            visibility = View.GONE
            setBackgroundColor(0x66000000)
            isClickable = true
            isFocusable = true
        }
        val saveProgressCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(
                LayoutHelper.dp(28),
                LayoutHelper.dp(24),
                LayoutHelper.dp(28),
                LayoutHelper.dp(24)
            )
            background = GradientDrawable().apply {
                setColor(0xFF1D1D2B.toInt())
                cornerRadius = LayoutHelper.dpf(14f)
            }
        }
        val saveProgressSpinner = ProgressBar(context).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
        }
        saveProgressCard.addView(
            saveProgressSpinner,
            LinearLayout.LayoutParams(LayoutHelper.dp(40), LayoutHelper.dp(40)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        )
        saveProgressLabel = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            text = context.getString(R.string.video_save_downloading)
        }
        saveProgressCard.addView(
            saveProgressLabel,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = LayoutHelper.dp(14)
            }
        )
        saveProgressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            isIndeterminate = true
            progressTintList = ColorStateList.valueOf(ThemeColors.instance.primary)
            progressBackgroundTintList = ColorStateList.valueOf(0xFF5A5A66.toInt())
        }
        saveProgressCard.addView(
            saveProgressBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LayoutHelper.dp(4)
            ).apply {
                topMargin = LayoutHelper.dp(18)
            }
        )
        saveProgressOverlay.addView(
            saveProgressCard,
            FrameLayout.LayoutParams(LayoutHelper.dp(290), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        )
        root.addView(
            saveProgressOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        setContentView(root)
        setOnDismissListener {
            optionsMenu?.dismiss()
            cancelPendingDownload()
            releasePlayer()
        }
    }

    fun play(item: VideoItem) {
        currentItem = item
        updateHeader()
        playbackPositionMs = 0L
        resumePlaybackOnForeground = false
        stoppedForLifecycle = false
        initializePlayer(startPositionMs = 0L, playWhenReady = true)

        playerView.alpha = 1f
        topBar.alpha = 1f
        topBar.visibility = View.VISIBLE
        backgroundDrawable.alpha = 0
        activeInstance = java.lang.ref.WeakReference(this)
        super.show()
        registerLifecycleObserver()
        ObjectAnimator.ofInt(backgroundDrawable, "alpha", 0, 255).setDuration(200).start()
        playerView.showController()
    }

    override fun dismiss() {
        resumePlaybackOnForeground = false
        stoppedForLifecycle = false
        unregisterLifecycleObserver()
        if (activeInstance?.get() === this) {
            activeInstance = null
        }
        super.dismiss()
    }

    private fun initializePlayer(startPositionMs: Long, playWhenReady: Boolean) {
        releasePlayer()
        player = ExoPlayer.Builder(context)
            .setSeekBackIncrementMs(VIDEO_SEEK_INCREMENT_MS)
            .setSeekForwardIncrementMs(VIDEO_SEEK_INCREMENT_MS)
            .build()
            .also { exoPlayer ->
                playerView.player = exoPlayer
                exoPlayer.setMediaItem(MediaItem.fromUri(currentItem.url))
                exoPlayer.repeatMode = Player.REPEAT_MODE_OFF
                if (startPositionMs > 0L) exoPlayer.seekTo(startPositionMs)
                exoPlayer.prepare()
                exoPlayer.playWhenReady = playWhenReady
            }
    }

    private fun registerLifecycleObserver() {
        if (isLifecycleObserverRegistered) return
        hostLifecycleOwner?.lifecycle?.addObserver(lifecycleObserver) ?: return
        isLifecycleObserverRegistered = true
    }

    private fun unregisterLifecycleObserver() {
        if (!isLifecycleObserverRegistered) return
        hostLifecycleOwner?.lifecycle?.removeObserver(lifecycleObserver)
        isLifecycleObserverRegistered = false
    }

    private fun releasePlayer() {
        player?.release()
        player = null
        playerView.player = null
    }

    private fun setHeaderVisible(visible: Boolean) {
        val targetAlpha = if (visible) 1f else 0f
        if (visible) topBar.visibility = View.VISIBLE
        topBar.animate().alpha(targetAlpha).setDuration(200).withEndAction {
            if (!visible) topBar.visibility = View.INVISIBLE
        }.start()
    }

    private fun dismissWithAnimation() {
        val animator = ObjectAnimator.ofInt(backgroundDrawable, "alpha", 255, 0)
        animator.duration = 150
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                dismiss()
            }
        })
        animator.start()
        playerView.animate().alpha(0f).setDuration(150).start()
        topBar.animate().alpha(0f).setDuration(150).start()
    }

    private fun updateHeader() {
        val item = currentItem
        nameLabel.text = item.senderName.takeIf { it.isNotBlank() } ?: "User"

        val timestamp = item.timestamp
        if (timestamp > 0) {
            val messageCalendar = Calendar.getInstance().apply { timeInMillis = timestamp * 1000L }
            val currentCalendar = Calendar.getInstance().apply { timeInMillis = System.currentTimeMillis() }
            val isToday = messageCalendar.get(Calendar.YEAR) == currentCalendar.get(Calendar.YEAR) &&
                messageCalendar.get(Calendar.DAY_OF_YEAR) == currentCalendar.get(Calendar.DAY_OF_YEAR)
            val isYesterday =
                (messageCalendar.get(Calendar.YEAR) == currentCalendar.get(Calendar.YEAR) &&
                    messageCalendar.get(Calendar.DAY_OF_YEAR) == currentCalendar.get(Calendar.DAY_OF_YEAR) - 1) ||
                    (currentCalendar.get(Calendar.DAY_OF_YEAR) == 1 &&
                        messageCalendar.get(Calendar.YEAR) == currentCalendar.get(Calendar.YEAR) - 1 &&
                        messageCalendar.get(Calendar.DAY_OF_YEAR) == messageCalendar.getActualMaximum(Calendar.DAY_OF_YEAR))
            val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestamp * 1000L))

            dateLabel.text = when {
                isToday -> "${context.getString(R.string.common_today_at)} $time"
                isYesterday -> "${context.getString(R.string.common_yesterday_at)} $time"
                else -> SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(timestamp * 1000L))
            }
            dateLabel.visibility = View.VISIBLE
        } else {
            dateLabel.visibility = View.GONE
        }

        when {
            item.uploaderId == VIDEO_ANONYMOUS_USER_ID -> {
                avatarView.setImageDrawable(com.mezon.mobile.ui.cells.MezonIcon.anonymousAvatar.getDrawable(context))
            }
            !item.senderAvatarUrl.isNullOrEmpty() -> {
                avatarView.setImage(
                    avatarImgproxyUrl(item.senderAvatarUrl, LayoutHelper.dp(40)),
                    item.uploaderId,
                    item.senderName
                )
            }
            else -> avatarView.setImage(null, item.uploaderId, item.senderName)
        }
    }

    private fun showMoreOptions(anchorView: View) {
        if (System.currentTimeMillis() - lastMenuDismissTime < 200) return

        optionsMenu = PopupMenu(context, ThemeColors.instance).apply {
            addItem(context.getString(R.string.action_save_video))
            addItem(context.getString(R.string.action_share_video))
            setOnItemClickListener { index ->
                when (index) {
                    0 -> saveVideo()
                    1 -> shareVideo()
                }
            }
            setOnDismissListener {
                optionsMenu = null
                lastMenuDismissTime = System.currentTimeMillis()
            }
            show(anchorView)
        }
    }

    private fun showToast(type: ToastOverlay.ToastType, messageResId: Int) {
        val parent = window?.decorView as? ViewGroup ?: return
        ToastOverlay(context, ThemeColors.instance).show(parent, type, context.getString(messageResId))
    }

    private fun saveVideo() {
        val url = currentItem.url
        if (url.isEmpty() || pendingDownloadId != NO_PENDING_DOWNLOAD_ID) return

        try {
            val filename = url.substringAfterLast('/').substringBefore('?').ifEmpty { "video" }
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(filename)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            registerDownloadReceiver()
            pendingDownloadId = downloadManager.enqueue(request)
            startSaveProgress(pendingDownloadId)
        } catch (_: Exception) {
            cancelPendingDownload()
            showToast(ToastOverlay.ToastType.ERROR, R.string.message_toast_save_failed)
        }
    }

    private fun registerDownloadReceiver() {
        if (isDownloadReceiverRegistered) return
        ContextCompat.registerReceiver(
            context,
            downloadCompleteReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
        isDownloadReceiverRegistered = true
    }

    private fun clearPendingDownloadObserver() {
        pendingDownloadId = NO_PENDING_DOWNLOAD_ID
        stopSaveProgress()
        if (!isDownloadReceiverRegistered) return
        runCatching { context.unregisterReceiver(downloadCompleteReceiver) }
        isDownloadReceiverRegistered = false
    }

    private fun cancelPendingDownload() {
        val downloadId = pendingDownloadId
        if (downloadId != NO_PENDING_DOWNLOAD_ID) {
            runCatching {
                val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                downloadManager.remove(downloadId)
            }
        }
        clearPendingDownloadObserver()
    }

    private fun startSaveProgress(downloadId: Long) {
        saveProgressJob?.cancel()
        saveProgressLabel.text = context.getString(R.string.video_save_downloading)
        saveProgressBar.isIndeterminate = true
        saveProgressBar.progress = 0
        saveProgressOverlay.visibility = View.VISIBLE

        saveProgressJob = entryPoint.applicationScope().launch(entryPoint.mainDispatcher()) {
            while (isActive && pendingDownloadId == downloadId && isShowing) {
                val snapshot = withContext(entryPoint.ioDispatcher()) {
                    getDownloadProgress(downloadId)
                }
                if (snapshot != null) {
                    if (handleDownloadTerminalStatus(downloadId, snapshot.status)) break
                    updateSaveProgress(snapshot)
                }
                delay(VIDEO_SAVE_PROGRESS_POLL_INTERVAL_MS)
            }
        }
    }

    private fun handleDownloadTerminalStatus(downloadId: Long, status: Int?): Boolean {
        if (downloadId != pendingDownloadId) return false
        val toast = when (status) {
            DownloadManager.STATUS_SUCCESSFUL -> ToastOverlay.ToastType.SUCCESS to R.string.message_toast_save_success
            DownloadManager.STATUS_FAILED -> ToastOverlay.ToastType.ERROR to R.string.message_toast_save_failed
            else -> return false
        }
        clearPendingDownloadObserver()
        if (isShowing) showToast(toast.first, toast.second)
        return true
    }

    private fun updateSaveProgress(snapshot: DownloadProgressSnapshot) {
        if (snapshot.totalBytes <= 0L) {
            saveProgressLabel.text = context.getString(R.string.video_save_downloading)
            saveProgressBar.isIndeterminate = true
            return
        }

        val percent = ((snapshot.downloadedBytes.toDouble() / snapshot.totalBytes.toDouble()) * 100.0)
            .toInt()
            .coerceIn(0, 100)
        saveProgressLabel.text = context.getString(R.string.video_save_downloading_progress, percent)
        saveProgressBar.isIndeterminate = false
        saveProgressBar.progress = percent
    }

    private fun stopSaveProgress() {
        saveProgressJob?.cancel()
        saveProgressJob = null
        if (saveProgressOverlay.visibility == View.VISIBLE) {
            saveProgressOverlay.visibility = View.GONE
        }
    }

    private data class DownloadProgressSnapshot(
        val status: Int?,
        val downloadedBytes: Long,
        val totalBytes: Long
    )

    private fun getDownloadProgress(downloadId: Long): DownloadProgressSnapshot? {
        return runCatching {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.query(DownloadManager.Query().setFilterById(downloadId))?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val downloadedIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                DownloadProgressSnapshot(
                    status = if (statusIndex >= 0) cursor.getInt(statusIndex) else null,
                    downloadedBytes = if (downloadedIndex >= 0) cursor.getLong(downloadedIndex) else 0L,
                    totalBytes = if (totalIndex >= 0) cursor.getLong(totalIndex) else -1L
                )
            }
        }.getOrNull()
    }

    private fun getDownloadStatus(downloadId: Long): Int? {
        return getDownloadProgress(downloadId)?.status
    }

    private fun shareVideo() {
        val item = currentItem
        if (item.url.isEmpty()) return
        val currentVideoSize = player?.videoSize
        val resolvedWidth = item.width.takeIf { it > 0 } ?: currentVideoSize?.width?.takeIf { it > 0 } ?: 0
        val resolvedHeight = item.height.takeIf { it > 0 } ?: currentVideoSize?.height?.takeIf { it > 0 } ?: 0
        val resolvedDuration = item.duration.takeIf { it > 0 }
            ?: player?.duration?.takeIf { it > 0L }
                ?.div(1000L)
                ?.coerceAtMost(Int.MAX_VALUE.toLong())
                ?.toInt()
            ?: 0
        val mimeType = item.mimeType.trim().lowercase(Locale.US).takeIf { it.startsWith("video/") }
            ?: VideoShareRefinementContract.mimeTypeFromUrl(item.url)
        val filename = item.filename.takeIf { it.isNotBlank() }
            ?: VideoShareRefinementContract.filenameFromUrl(item.url, mimeType)
        val attachment = AttachmentInfo(
            url = item.url,
            thumb = item.thumbnailUrl,
            width = resolvedWidth,
            height = resolvedHeight,
            filename = filename,
            filetype = mimeType,
            size = item.size,
            duration = resolvedDuration
        )
        try {
            val targetIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
            }
            val mezonComponent = ComponentName(context, MainActivity::class.java)
            val mezonIntent = Intent(targetIntent).apply {
                component = mezonComponent
                putExtra(VideoShareRefinementContract.EXTRA_INTERNAL_TARGET, true)
            }
            val chooser = Intent.createChooser(targetIntent, context.getString(R.string.action_share_video)).apply {
                putExtra(
                    Intent.EXTRA_CHOOSER_REFINEMENT_INTENT_SENDER,
                    VideoShareRefinementContract.createIntentSender(context, attachment)
                )
                putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(mezonIntent))
                putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(mezonComponent))
            }
            context.startActivity(chooser)
        } catch (_: Exception) {
            if (isShowing) {
                showToast(ToastOverlay.ToastType.ERROR, R.string.message_toast_share_failed)
            }
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        dismissWithAnimation()
    }
}
