package com.mezon.mobile.home.chat

import android.animation.ObjectAnimator
import android.app.DownloadManager
import android.content.ClipData
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.VideoView
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import com.mezon.mobile.network.CdnRequestUrl
import com.mezon.mobile.network.CdnSigner
import androidx.media3.ui.PlayerView
import com.mezon.mobile.BuildConfig
import com.mezon.mobile.MainActivity
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.InAppOverlayHost
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.sharing.ExistingVideoShareStore
import com.mezon.mobile.home.sharing.VideoShareRefinementContract
import com.mezon.mobile.ui.cells.BackupImageView
import com.mezon.mobile.ui.cells.PopupMenu
import com.mezon.mobile.ui.cells.ToastOverlay
import com.mezon.mobile.util.avatarImgproxyUrl
import com.mezon.mobile.util.FileIngestPlaylist
import com.mezon.mobile.util.HlsVideoCopy
import com.mezon.mobile.util.VideoCache
import com.mezon.mobile.util.VideoPlaybackDownload
import com.mezon.mobile.util.VideoPrefetcher
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val VIDEO_SEEK_INCREMENT_MS = 15_000L
private const val VIDEO_CONTROLLER_TIMEOUT_MS = 6_000
private const val VIDEO_SEEK_TOUCH_HEIGHT_DP = 72
private const val VIDEO_PLAYER_LOG_TAG = "VideoPlayerDebug"
private const val VIDEO_SAVE_PROGRESS_POLL_INTERVAL_MS = 300L
private const val VIDEO_STALL_TIMEOUT_MS = 15_000L
private const val VIDEO_DIRECT_STALL_TIMEOUT_MS = 8_000L
private const val VIDEO_START_TIMEOUT_MS = 30_000L
private const val VIDEO_REBUFFER_TIMEOUT_MS = 45_000L
private const val NO_PENDING_DOWNLOAD_ID = -1L
private val VIDEO_ANONYMOUS_USER_ID = BuildConfig.MEZON_ANONYMOUS_USER_ID.toLongOrNull() ?: -1L

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
class VideoPlayerDialog(context: Context) : ComponentDialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {

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
        val duration: Int = 0,
        val channelId: Long = 0L,
        val clanId: Long = 0L,
        val messageId: Long = 0L,
        val senderInfoResolved: Boolean = false,
        val isIngest: Boolean = false,
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
    private val nativeContainer: FrameLayout
    private val playbackOverlay: LinearLayout
    private val playbackLabel: TextView
    private val playbackSpinner: ProgressBar
    private val retryPlaybackButton: Button
    private val externalPlaybackButton: Button

    private enum class PlaybackSource { CACHED, DIRECT, HLS, DOWNLOAD, LOCAL, NATIVE, FAILED }

    private var playbackSource = PlaybackSource.CACHED
    private val triedStreams = mutableSetOf<PlaybackSource>()
    private var player: ExoPlayer? = null
    private var nativeView: VideoView? = null
    private var nativeControls: MediaController? = null
    private var nativePrepared = false
    private var watchdogJob: Job? = null
    private var durationProbeJob: Job? = null
    private var playbackDownloadJob: Job? = null
    private var playbackFile: File? = null
    private var playbackFileMimeType: String? = null
    private var playbackFileShared = false
    private var hlsPlaylistUrl: String? = null
    private var isHlsUrl = false
    private var isRemoteVideo = false
    private lateinit var currentItem: VideoItem
    private var senderInfoJob: Job? = null
    private var resolvedSender: MediaViewerSender? = null
    private var optionsMenu: PopupMenu? = null
    private var lastMenuDismissTime = 0L
    private var saveProgressJob: Job? = null
    private var hlsCopyJob: Job? = null
    private var pendingDownloadId = NO_PENDING_DOWNLOAD_ID
    private var pendingDownloadSourceUrl = ""
    private var pendingDownloadRequest: CdnRequestUrl? = null
    private var pendingDownloadRetried = false
    private var isDownloadReceiverRegistered = false
    private val hostLifecycleOwner = context.findLifecycleOwner()
    private var isLifecycleObserverRegistered = false
    private var playbackPositionMs = 0L
    private var resumePlaybackOnForeground = false
    private var stoppedForLifecycle = false
    private var pausedForLifecycle = false

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onPause(owner: LifecycleOwner) {
            if (!isShowing) return
            pausedForLifecycle = true
            player?.let { currentPlayer ->
                playbackPositionMs = currentPlayer.currentPosition
                resumePlaybackOnForeground = currentPlayer.playWhenReady &&
                    currentPlayer.playbackState != Player.STATE_ENDED
                currentPlayer.pause()
            }
            nativeView?.let { view ->
                if (nativePrepared) {
                    playbackPositionMs = view.currentPosition.toLong()
                    resumePlaybackOnForeground = view.isPlaying
                }
                view.pause()
                nativeControls?.hide()
            }
        }

        override fun onStop(owner: LifecycleOwner) {
            if (!isShowing) return
            player?.let { playbackPositionMs = it.currentPosition }
            stoppedForLifecycle = true
            releasePlaybackEngines()
            stopSaveProgress()
        }

        override fun onStart(owner: LifecycleOwner) {
            if (!isShowing) return
            if (stoppedForLifecycle && ::currentItem.isInitialized) {
                stoppedForLifecycle = false
                if (playbackSource != PlaybackSource.DOWNLOAD && playbackSource != PlaybackSource.FAILED) {
                    startPlayback(playbackSource, playbackPositionMs, resumePlaybackOnForeground)
                }
            }
            pendingDownloadId.takeIf { it != NO_PENDING_DOWNLOAD_ID }?.let(::startSaveProgress)
        }

        override fun onResume(owner: LifecycleOwner) {
            if (!isShowing) return
            pausedForLifecycle = false
            if (!resumePlaybackOnForeground) return
            player?.play()
            nativeView?.start()
        }

        override fun onDestroy(owner: LifecycleOwner) {
            if (isShowing) {
                dismiss()
            } else {
                unregisterLifecycleObserver()
                releasePlaybackEngines()
            }
        }
    }

    private val downloadCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, NO_PENDING_DOWNLOAD_ID)
            if (downloadId != pendingDownloadId) return
            val pendingResult = goAsync()
            entryPoint.applicationScope().launch(entryPoint.ioDispatcher()) {
                try {
                    val snapshot = getDownloadProgress(downloadId)
                    withContext(entryPoint.mainDispatcher()) {
                        handleDownloadTerminalStatus(downloadId, snapshot?.status, snapshot?.reason ?: 0)
                    }
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    private val entryPoint: FragmentEntryPoint by lazy {
        EntryPointAccessors.fromApplication(context.applicationContext, FragmentEntryPoint::class.java)
    }

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                dismissWithAnimation()
            }
        })
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
            findViewById<View>(androidx.media3.ui.R.id.exo_progress)?.let { progress ->
                progress.layoutParams = progress.layoutParams.apply {
                    height = LayoutHelper.dp(VIDEO_SEEK_TOUCH_HEIGHT_DP)
                }
            }
        }
        root.addView(
            playerView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        nativeContainer = FrameLayout(context).apply { visibility = View.GONE }
        root.addView(nativeContainer, FrameLayout.LayoutParams(-1, -1))

        playbackOverlay = LinearLayout(context).apply {
            visibility = View.GONE
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(LayoutHelper.dp(16), LayoutHelper.dp(12), LayoutHelper.dp(16), LayoutHelper.dp(12))
        }
        playbackSpinner = ProgressBar(context).apply {
            indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
        }
        playbackOverlay.addView(playbackSpinner, LinearLayout.LayoutParams(LayoutHelper.dp(36), LayoutHelper.dp(36)))
        playbackLabel = TextView(context).apply {
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            textSize = 16f
            setPadding(0, LayoutHelper.dp(8), 0, LayoutHelper.dp(8))
        }
        playbackOverlay.addView(playbackLabel)
        retryPlaybackButton = Button(context).apply {
            setText(R.string.common_retry)
            setOnClickListener { retryPlayback() }
        }
        playbackOverlay.addView(retryPlaybackButton)
        externalPlaybackButton = Button(context).apply {
            setText(R.string.video_playback_open_external)
            setOnClickListener { openInExternalPlayer() }
        }
        playbackOverlay.addView(externalPlaybackButton)
        root.addView(playbackOverlay, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))

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
            setAspectFill(true)
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
                if (player != null) setHeaderVisible(visibility == View.VISIBLE)
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
            hlsCopyJob?.cancel()
            hlsCopyJob = null
            releasePlaybackEngines()
        }
    }

    fun play(item: VideoItem) {
        activeInstance?.get()?.takeIf { it !== this && it.isShowing }?.dismiss()
        playbackDownloadJob?.cancel()
        playbackDownloadJob = null
        releasePlaybackEngines()
        clearPlaybackFile()
        VideoPrefetcher.onPlaybackStarted(item.url)
        currentItem = item
        resolvedSender = null
        updateHeader()
        playbackPositionMs = 0L
        resumePlaybackOnForeground = false
        stoppedForLifecycle = false
        pausedForLifecycle = false
        triedStreams.clear()

        val uri = Uri.parse(item.url)
        isRemoteVideo = uri.scheme.equals("http", true) || uri.scheme.equals("https", true)
        isHlsUrl = uri.lastPathSegment?.endsWith(".m3u8", ignoreCase = true) == true ||
            item.mimeType.equals(MimeTypes.APPLICATION_M3U8, ignoreCase = true) ||
            item.mimeType.equals("application/vnd.apple.mpegurl", ignoreCase = true)
        hlsPlaylistUrl = if (isHlsUrl) item.url else FileIngestPlaylist.indexPlaylistUrl(item.url)

        playerView.alpha = 1f
        topBar.alpha = 0f
        topBar.visibility = View.INVISIBLE
        backgroundDrawable.alpha = 0
        activeInstance = java.lang.ref.WeakReference(this)
        super.show()
        InAppOverlayHost.register(this, dismissOnOverlayTap = true)
        registerLifecycleObserver()
        ObjectAnimator.ofInt(backgroundDrawable, "alpha", 0, 255).setDuration(200).start()
        startPlayback(initialPlaybackSource(), startPositionMs = 0L, playWhenReady = true)
        playerView.hideController()
    }

    override fun dismiss() {
        senderInfoJob?.cancel()
        senderInfoJob = null
        playbackDownloadJob?.cancel()
        playbackDownloadJob = null
        releasePlaybackEngines()
        clearPlaybackFile()
        VideoPrefetcher.onPlaybackStopped()
        InAppOverlayHost.unregister(this)
        resumePlaybackOnForeground = false
        stoppedForLifecycle = false
        unregisterLifecycleObserver()
        if (activeInstance?.get() === this) {
            activeInstance = null
        }
        super.dismiss()
    }

    private fun initialPlaybackSource(): PlaybackSource = when {
        playbackFile != null -> PlaybackSource.LOCAL
        hlsPlaylistUrl != null &&
            (isHlsUrl || currentItem.isIngest || FileIngestPlaylist.isPlayable(currentItem.url)) -> PlaybackSource.HLS
        isRemoteVideo -> PlaybackSource.CACHED
        else -> PlaybackSource.LOCAL
    }

    private fun startPlayback(source: PlaybackSource, startPositionMs: Long, playWhenReady: Boolean) {
        releasePlaybackEngines()
        playbackSource = source
        playbackPositionMs = startPositionMs
        resumePlaybackOnForeground = playWhenReady
        if (stoppedForLifecycle) return

        val autoPlay = playWhenReady && !pausedForLifecycle
        when (source) {
            PlaybackSource.DOWNLOAD -> downloadForPlayback()
            PlaybackSource.NATIVE -> startNativePlayer(startPositionMs)
            PlaybackSource.FAILED -> showPlaybackFailure()
            else -> {
                try {
                    startExoPlayer(source, startPositionMs, autoPlay)
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.d(VIDEO_PLAYER_LOG_TAG, "source=$source setup failed: ${e.javaClass.simpleName}")
                    val failedPlayer = player
                    if (failedPlayer != null) recoverPlayback(failedPlayer, source) else showPlaybackFailure()
                }
            }
        }
    }

    private fun startExoPlayer(source: PlaybackSource, startPositionMs: Long, playWhenReady: Boolean) {
        triedStreams += source
        val uri = when (source) {
            PlaybackSource.HLS -> Uri.parse(requireNotNull(hlsPlaylistUrl))
            PlaybackSource.LOCAL -> localPlaybackUri()
            else -> Uri.parse(currentItem.url)
        }
        val dataSourceFactory = when (source) {
            PlaybackSource.CACHED -> VideoCache.dataSourceFactory(context)
            PlaybackSource.LOCAL -> DefaultDataSource.Factory(context.applicationContext)
            else -> VideoCache.upstreamDataSourceFactory(context)
        }
        val item = if (source == PlaybackSource.HLS) {
            MediaItem.Builder().setUri(uri).setMimeType(MimeTypes.APPLICATION_M3U8)
                .setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder().setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f).build()
                ).build()
        } else {
            MediaItem.fromUri(uri)
        }
        val sourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(1))
        val loadControl = DefaultLoadControl.Builder().setBufferDurationsMsForStreaming(
            DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
            DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
            1_000,
            DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
        ).build()
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        val exo = ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(sourceFactory)
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(VIDEO_SEEK_INCREMENT_MS)
            .setSeekForwardIncrementMs(VIDEO_SEEK_INCREMENT_MS)
            .build()
        player = exo
        playerView.visibility = View.VISIBLE
        playbackOverlay.visibility = View.GONE
        playerView.player = exo
        var renderedFrame = false
        exo.addListener(object : Player.Listener {
            private var previousState = Player.STATE_IDLE
            private var durationProbeStarted = false

            override fun onPlaybackStateChanged(playbackState: Int) {
                logPlayerState(exo, "source=$source state=$playbackState")
                if (previousState == Player.STATE_ENDED && exo.playWhenReady) playerView.hideController()
                previousState = playbackState
            }

            override fun onRenderedFirstFrame() {
                if (renderedFrame || player !== exo) return
                renderedFrame = true
                maybeProbeDuration()
            }

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                maybeProbeDuration()
            }

            private fun maybeProbeDuration() {
                if (!renderedFrame || durationProbeStarted || source == PlaybackSource.HLS ||
                    player !== exo || !exo.isCurrentMediaItemSeekable || exo.duration > 0L
                ) return
                durationProbeStarted = true
                probeDurationAfterStart(exo, uri, dataSourceFactory)
            }

            override fun onPlayerError(error: PlaybackException) {
                // Releasing a player during its listener dispatch can trigger further callbacks.
                playerView.post { recoverPlayback(exo, source, error) }
            }
        })
        exo.setMediaItem(item, startPositionMs.takeIf { it > 0L } ?: C.TIME_UNSET)
        exo.playWhenReady = playWhenReady
        exo.prepare()
        watchExoPlayback(exo, source) { renderedFrame }
    }

    private fun watchExoPlayback(exo: ExoPlayer, source: PlaybackSource, hasFrame: () -> Boolean) {
        val stallTimeout = if (source == PlaybackSource.DIRECT) VIDEO_DIRECT_STALL_TIMEOUT_MS else VIDEO_STALL_TIMEOUT_MS
        watchdogJob = entryPoint.applicationScope().launch(entryPoint.mainDispatcher()) {
            var waitingSince = 0L
            var lastProgressAt = 0L
            var bufferedMs = C.TIME_UNSET
            while (player === exo && isShowing) {
                delay(1_000)
                if (player !== exo || !isShowing) break
                val audioOnly = !exo.currentTracks.isEmpty &&
                    !exo.currentTracks.containsType(C.TRACK_TYPE_VIDEO)
                val wantsPlayback = !pausedForLifecycle && exo.playWhenReady &&
                    exo.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE
                if (wantsPlayback && exo.playbackState == Player.STATE_ENDED && !hasFrame() && !audioOnly) {
                    recoverPlayback(exo, source)
                    return@launch
                }
                val waiting = wantsPlayback &&
                    (exo.playbackState == Player.STATE_BUFFERING ||
                        (exo.playbackState == Player.STATE_READY && !hasFrame() && !(audioOnly && exo.isPlaying)))
                if (!waiting) {
                    waitingSince = 0L
                    continue
                }

                val now = SystemClock.elapsedRealtime()
                if (waitingSince == 0L) {
                    waitingSince = now
                    lastProgressAt = now
                    bufferedMs = exo.bufferedPosition
                } else if (exo.bufferedPosition > bufferedMs) {
                    bufferedMs = exo.bufferedPosition
                    lastProgressAt = now
                }
                val maxWait = if (hasFrame() || audioOnly) VIDEO_REBUFFER_TIMEOUT_MS else VIDEO_START_TIMEOUT_MS
                if (now - lastProgressAt >= stallTimeout || now - waitingSince >= maxWait) {
                    logPlayerState(exo, "source=$source playback timeout")
                    recoverPlayback(exo, source)
                    return@launch
                }
            }
        }
    }

    private fun probeDurationAfterStart(exo: ExoPlayer, uri: Uri, factory: DataSource.Factory) {
        durationProbeJob = entryPoint.applicationScope().launch(entryPoint.mainDispatcher()) {
            val durationUs = try {
                withContext(entryPoint.ioDispatcher()) { probeFragmentedVideoDurationUs(uri, factory) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.d(VIDEO_PLAYER_LOG_TAG, "duration probe failed: ${e.javaClass.simpleName}")
                null
            }
            if (durationUs == null || player !== exo || !isShowing || exo.duration > 0L ||
                exo.playbackState != Player.STATE_READY
            ) return@launch
            val sourceFactory = DefaultMediaSourceFactory(factory, durationAwareExtractorsFactory(durationUs))
                .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(1))
            exo.setMediaSource(sourceFactory.createMediaSource(MediaItem.fromUri(uri)), exo.currentPosition)
            exo.prepare()
        }
    }

    private fun recoverPlayback(exo: ExoPlayer, source: PlaybackSource, error: PlaybackException? = null) {
        if (player !== exo || !isShowing || stoppedForLifecycle) return
        if (error != null) logPlayerState(exo, "source=$source error=${error.errorCodeName}")
        val rendererFailure = error is ExoPlaybackException && error.type == ExoPlaybackException.TYPE_RENDERER
        val originalMissing = error != null && isOriginalMissing(error)
        val next = when {
            source == PlaybackSource.LOCAL -> PlaybackSource.NATIVE
            source == PlaybackSource.HLS && isHlsUrl -> PlaybackSource.NATIVE
            source == PlaybackSource.HLS && PlaybackSource.CACHED !in triedStreams -> PlaybackSource.CACHED
            PlaybackSource.HLS !in triedStreams && hlsPlaylistUrl != null &&
                (originalMissing || currentItem.isIngest || FileIngestPlaylist.isPlayable(currentItem.url)) -> PlaybackSource.HLS
            source == PlaybackSource.CACHED && !originalMissing && !rendererFailure -> PlaybackSource.DIRECT
            else -> PlaybackSource.DOWNLOAD
        }
        val playWhenReady = if (pausedForLifecycle) resumePlaybackOnForeground else exo.playWhenReady
        startPlayback(next, exo.currentPosition.coerceAtLeast(0L), playWhenReady)
    }

    private fun isOriginalMissing(error: PlaybackException): Boolean {
        var cause: Throwable? = error.cause
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) {
                return cause.responseCode == 403 || cause.responseCode == 404
            }
            cause = cause.cause
        }
        return false
    }

    private fun downloadForPlayback() {
        val item = currentItem
        val mimeType = videoMimeType(item)
        val target = VideoPlaybackDownload.destination(context, VideoShareRefinementContract.extensionForMimeType(mimeType))
        showPlaybackStatus(R.string.video_save_downloading)
        playbackDownloadJob = entryPoint.applicationScope().launch(entryPoint.mainDispatcher()) {
            val job = coroutineContext[Job]
            try {
                val resultMimeType = VideoPlaybackDownload.copy(
                    context.applicationContext, item.url, target, mimeType,
                    tryHls = PlaybackSource.HLS in triedStreams,
                ) { percent ->
                    playbackOverlay.post {
                        if (playbackDownloadJob === job && playbackSource == PlaybackSource.DOWNLOAD && isShowing) {
                            playbackLabel.text = context.getString(R.string.video_save_downloading_progress, percent)
                        }
                    }
                }
                if (!isShowing || playbackDownloadJob !== job || currentItem !== item) return@launch
                playbackFile = target
                playbackFileMimeType = resultMimeType
                startPlayback(PlaybackSource.LOCAL, playbackPositionMs, resumePlaybackOnForeground)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isShowing || playbackDownloadJob !== job || currentItem !== item) return@launch
                if (BuildConfig.DEBUG) Log.d(VIDEO_PLAYER_LOG_TAG, "local download failed: ${e.javaClass.simpleName}")
                showPlaybackFailure(
                    if (HlsVideoCopy.isOutOfSpace(e)) R.string.video_save_no_space else R.string.video_playback_failed
                )
            } finally {
                if (playbackDownloadJob === job) playbackDownloadJob = null
                if (playbackFile != target) {
                    withContext(NonCancellable + entryPoint.ioDispatcher()) { target.delete() }
                }
            }
        }
    }

    private fun startNativePlayer(startPositionMs: Long) {
        showPlaybackStatus(R.string.video_playback_preparing)
        nativeContainer.visibility = View.VISIBLE
        val view = VideoView(context)
        nativeView = view
        nativeContainer.addView(view, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        val controls = MediaController(context)
        nativeControls = controls
        view.setMediaController(controls)
        var needsFirstFrame = false
        var hasFrame = false
        var buffering = false
        view.setOnPreparedListener { mediaPlayer ->
            if (nativeView !== view) return@setOnPreparedListener
            nativePrepared = true
            needsFirstFrame = mediaPlayer.videoWidth > 0
            playbackOverlay.visibility = View.GONE
            if (startPositionMs > 0L) view.seekTo(startPositionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
        view.setOnInfoListener { _, what, _ ->
            when (what) {
                MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> hasFrame = true
                MediaPlayer.MEDIA_INFO_BUFFERING_START -> buffering = true
                MediaPlayer.MEDIA_INFO_BUFFERING_END -> buffering = false
            }
            false
        }
        view.setOnErrorListener { _, what, extra ->
            if (BuildConfig.DEBUG) Log.d(VIDEO_PLAYER_LOG_TAG, "native playback error=$what extra=$extra")
            playerView.post { if (nativeView === view && isShowing) showPlaybackFailure() }
            true
        }
        watchdogJob = entryPoint.applicationScope().launch(entryPoint.mainDispatcher()) {
            try {
                val uri = if (isHlsUrl && isRemoteVideo && playbackFile == null) {
                    Uri.parse(CdnSigner.requestUrl(currentItem.url).url)
                } else {
                    localPlaybackUri()
                }
                if (nativeView !== view || !isShowing) return@launch
                view.setVideoURI(uri)
                if (!pausedForLifecycle && resumePlaybackOnForeground) view.start()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (nativeView !== view || !isShowing) return@launch
                if (BuildConfig.DEBUG) Log.d(VIDEO_PLAYER_LOG_TAG, "native playback failed: ${e.javaClass.simpleName}")
                showPlaybackFailure()
                return@launch
            }

            var waitingSince = SystemClock.elapsedRealtime()
            while (nativeView === view && isShowing) {
                delay(1_000)
                if (nativeView !== view || !isShowing) break
                val now = SystemClock.elapsedRealtime()
                val waiting = !nativePrepared ||
                    (view.isPlaying && (buffering || (needsFirstFrame && !hasFrame)))
                if (pausedForLifecycle || !waiting) waitingSince = now
                if (now - waitingSince >= VIDEO_STALL_TIMEOUT_MS) {
                    showPlaybackFailure()
                    return@launch
                }
            }
        }
    }

    private fun localPlaybackUri(): Uri = playbackFile?.let(Uri::fromFile) ?: Uri.parse(currentItem.url)

    private fun showPlaybackStatus(message: Int, failed: Boolean = false) {
        playerView.visibility = View.GONE
        playbackLabel.setText(message)
        playbackSpinner.visibility = if (failed) View.GONE else View.VISIBLE
        retryPlaybackButton.visibility = if (failed) View.VISIBLE else View.GONE
        externalPlaybackButton.visibility = if (failed && (playbackFile != null || !isRemoteVideo || isHlsUrl)) {
            View.VISIBLE
        } else {
            View.GONE
        }
        playbackOverlay.visibility = View.VISIBLE
        setHeaderVisible(true)
    }

    private fun showPlaybackFailure(message: Int = R.string.video_playback_failed) {
        releasePlaybackEngines()
        playbackSource = PlaybackSource.FAILED
        showPlaybackStatus(message, failed = true)
    }

    private fun retryPlayback() {
        if (playbackDownloadJob != null) return
        triedStreams.clear()
        startPlayback(initialPlaybackSource(), playbackPositionMs, playWhenReady = true)
    }

    private fun openInExternalPlayer() {
        try {
            val file = playbackFile
            val uri = when {
                file != null -> FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                isHlsUrl && isRemoteVideo -> Uri.parse(currentItem.url)
                else -> localPlaybackUri()
            }
            val readableUri = if (uri.scheme == "file") {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(requireNotNull(uri.path)))
            } else {
                uri
            }
            val mimeType = playbackFileMimeType ?: if (isHlsUrl) MimeTypes.APPLICATION_M3U8 else videoMimeType(currentItem)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(readableUri, mimeType)
                if (readableUri.scheme == "content") {
                    clipData = ClipData.newRawUri("video", readableUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.video_playback_open_external)))
            if (file != null) playbackFileShared = true
        } catch (_: Exception) {
            showToast(ToastOverlay.ToastType.ERROR, R.string.common_file_open_failed)
        }
    }

    private fun clearPlaybackFile() {
        val file = playbackFile
        playbackFile = null
        playbackFileMimeType = null
        if (file != null && !playbackFileShared) {
            entryPoint.applicationScope().launch(entryPoint.ioDispatcher()) { file.delete() }
        }
        playbackFileShared = false
    }

    private fun logPlayerState(exoPlayer: ExoPlayer, event: String) {
        if (!BuildConfig.DEBUG) return
        Log.d(
            VIDEO_PLAYER_LOG_TAG,
            "$event durationMs=${exoPlayer.duration} positionMs=${exoPlayer.currentPosition} " +
                "seekable=${exoPlayer.isCurrentMediaItemSeekable} bufferedMs=${exoPlayer.bufferedPosition}"
        )
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

    private fun releasePlaybackEngines() {
        watchdogJob?.cancel()
        watchdogJob = null
        durationProbeJob?.cancel()
        durationProbeJob = null
        val exo = player
        player = null
        playerView.player = null
        exo?.release()

        nativeControls?.hide()
        nativeControls = null
        val view = nativeView
        nativeView = null
        nativePrepared = false
        view?.stopPlayback()
        nativeContainer.removeAllViews()
        nativeContainer.visibility = View.GONE
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
        senderInfoJob?.cancel()
        senderInfoJob = null
        val item = currentItem
        val sender = resolvedSender
            ?: resolveMediaViewerSender(item.uploaderId, item.senderName, item.senderAvatarUrl)
        nameLabel.text = sender.name

        val formattedDate = formatViewerHeaderDate(context, item.timestamp)
        if (formattedDate != null) {
            dateLabel.text = formattedDate
            dateLabel.visibility = View.VISIBLE
        } else {
            dateLabel.visibility = View.GONE
        }

        when {
            item.uploaderId == VIDEO_ANONYMOUS_USER_ID && sender.avatarUrl == null -> {
                avatarView.setImageDrawable(com.mezon.mobile.ui.cells.MezonIcon.anonymousAvatar.getDrawable(context))
            }
            sender.avatarUrl != null -> {
                avatarView.setImage(
                    avatarImgproxyUrl(sender.avatarUrl, LayoutHelper.dp(40)),
                    item.uploaderId
                )
            }
            else -> avatarView.setImage(null, item.uploaderId)
        }
        if (item.uploaderId == VIDEO_ANONYMOUS_USER_ID && !item.senderInfoResolved &&
            item.channelId != 0L && item.messageId != 0L && resolvedSender == null
        ) {
            senderInfoJob = entryPoint.applicationScope().launch(entryPoint.mainDispatcher()) {
                val loaded = entryPoint.channelGalleryController().loadAnonymousSenderInfo(
                    item.channelId, item.clanId, item.messageId
                ) ?: return@launch
                if (!isActive || currentItem != item) return@launch
                resolvedSender = loaded
                senderInfoJob = null
                updateHeader()
            }
        }
    }

    private fun showMoreOptions(anchorView: View) {
        if (System.currentTimeMillis() - lastMenuDismissTime < 200) return

        optionsMenu = PopupMenu(context, ThemeColors.instance, fullWidthDividers = true).apply {
            addItem(context.getString(R.string.action_save_video))
            addItem(context.getString(R.string.action_share_video_to_mezon))
            addItem(context.getString(R.string.action_share_video_to_other_apps))
            setOnItemClickListener { index ->
                when (index) {
                    0 -> saveVideo()
                    1 -> shareVideoToMezon()
                    2 -> shareVideoToOtherApps()
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
        if (url.isEmpty() || pendingDownloadId != NO_PENDING_DOWNLOAD_ID || hlsCopyJob != null) return
        CdnSigner.requestUrlOnMain(url) { signed ->
            if (isShowing && pendingDownloadId == NO_PENDING_DOWNLOAD_ID) {
                enqueueVideoDownload(url, signed, retried = false)
            }
        }
    }

    private fun enqueueVideoDownload(url: String, signed: CdnRequestUrl, retried: Boolean) {
        try {
            val filename = url.substringAfterLast('/').substringBefore('?').ifEmpty { "video" }
            val request = DownloadManager.Request(Uri.parse(signed.url))
                .setTitle(filename)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            registerDownloadReceiver()
            pendingDownloadId = downloadManager.enqueue(request)
            pendingDownloadSourceUrl = url
            pendingDownloadRequest = signed
            pendingDownloadRetried = retried
            startSaveProgress(pendingDownloadId)
        } catch (_: Exception) {
            cancelPendingDownload()
            showToast(ToastOverlay.ToastType.ERROR, R.string.message_toast_save_failed)
        }
    }

    private fun retryDownloadWithFreshSignature(reason: Int): Boolean {
        val request = pendingDownloadRequest ?: return false
        if (pendingDownloadRetried || !CdnSigner.shouldRetry(request, reason)) return false
        val url = pendingDownloadSourceUrl
        cancelPendingDownload()
        CdnSigner.freshRequestUrlOnMain(request, url) { fresh ->
            if (!isShowing || pendingDownloadId != NO_PENDING_DOWNLOAD_ID) return@freshRequestUrlOnMain
            if (fresh == null) {
                showToast(ToastOverlay.ToastType.ERROR, R.string.message_toast_save_failed)
                return@freshRequestUrlOnMain
            }
            enqueueVideoDownload(url, fresh, retried = true)
        }
        return true
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
                    if (handleDownloadTerminalStatus(downloadId, snapshot.status, snapshot.reason)) break
                    updateSaveProgress(snapshot)
                }
                delay(VIDEO_SAVE_PROGRESS_POLL_INTERVAL_MS)
            }
        }
    }

    private fun handleDownloadTerminalStatus(downloadId: Long, status: Int?, reason: Int): Boolean {
        if (downloadId != pendingDownloadId) return false
        if (status == DownloadManager.STATUS_FAILED && retryDownloadWithFreshSignature(reason)) return true
        if (status == DownloadManager.STATUS_FAILED && (reason == 403 || reason == 404) && saveFinishedHlsCopy()) return true
        val toast = when (status) {
            DownloadManager.STATUS_SUCCESSFUL -> ToastOverlay.ToastType.SUCCESS to R.string.message_toast_save_success
            DownloadManager.STATUS_FAILED -> ToastOverlay.ToastType.ERROR to downloadFailureMessage(reason)
            else -> return false
        }
        clearPendingDownloadObserver()
        if (isShowing) showToast(toast.first, toast.second)
        return true
    }

    private fun saveFinishedHlsCopy(): Boolean {
        val url = pendingDownloadSourceUrl
        if (url.isEmpty()) return false
        cancelPendingDownload()
        showHlsCopyProgress(0)
        hlsCopyJob = entryPoint.applicationScope().launch(entryPoint.mainDispatcher()) {
            val outcome = HlsVideoCopy.saveToDownloads(context.applicationContext, url) { percent ->
                saveProgressOverlay.post {
                    if (hlsCopyJob != null) showHlsCopyProgress(percent)
                }
            }
            hlsCopyJob = null
            stopSaveProgress()
            if (isShowing) {
                if (outcome == HlsVideoCopy.Outcome.SAVED) {
                    showToast(ToastOverlay.ToastType.SUCCESS, R.string.message_toast_save_success)
                } else {
                    showToast(ToastOverlay.ToastType.ERROR, HlsVideoCopy.failureMessage(outcome))
                }
            }
        }
        return true
    }

    private fun downloadFailureMessage(reason: Int): Int = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> R.string.video_save_no_space
        DownloadManager.ERROR_HTTP_DATA_ERROR,
        DownloadManager.ERROR_CANNOT_RESUME -> R.string.video_save_network_failed
        else -> R.string.message_toast_save_failed
    }

    private fun showHlsCopyProgress(percent: Int) {
        saveProgressLabel.text = context.getString(R.string.video_save_downloading_progress, percent)
        saveProgressBar.isIndeterminate = false
        saveProgressBar.progress = percent
        saveProgressOverlay.visibility = View.VISIBLE
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
        val totalBytes: Long,
        val reason: Int
    )

    private fun getDownloadProgress(downloadId: Long): DownloadProgressSnapshot? {
        return runCatching {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.query(DownloadManager.Query().setFilterById(downloadId))?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val downloadedIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val reasonIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                DownloadProgressSnapshot(
                    status = if (statusIndex >= 0) cursor.getInt(statusIndex) else null,
                    downloadedBytes = if (downloadedIndex >= 0) cursor.getLong(downloadedIndex) else 0L,
                    totalBytes = if (totalIndex >= 0) cursor.getLong(totalIndex) else -1L,
                    reason = if (reasonIndex >= 0) cursor.getInt(reasonIndex) else 0
                )
            }
        }.getOrNull()
    }

    private fun buildShareAttachment(): AttachmentInfo? {
        val item = currentItem
        if (item.url.isEmpty()) return null
        val currentVideoSize = player?.videoSize
        val resolvedWidth = item.width.takeIf { it > 0 } ?: currentVideoSize?.width?.takeIf { it > 0 } ?: 0
        val resolvedHeight = item.height.takeIf { it > 0 } ?: currentVideoSize?.height?.takeIf { it > 0 } ?: 0
        val resolvedDuration = item.duration.takeIf { it > 0 }
            ?: player?.duration?.takeIf { it > 0L }
                ?.div(1000L)
                ?.coerceAtMost(Int.MAX_VALUE.toLong())
                ?.toInt()
            ?: 0
        val mimeType = videoMimeType(item)
        val filename = item.filename.takeIf { it.isNotBlank() }
            ?: VideoShareRefinementContract.filenameFromUrl(item.url, mimeType)
        return AttachmentInfo(
            url = item.url,
            thumb = item.thumbnailUrl,
            width = resolvedWidth,
            height = resolvedHeight,
            filename = filename,
            filetype = mimeType,
            size = item.size,
            duration = resolvedDuration
        )
    }

    private fun videoMimeType(item: VideoItem): String =
        resolveWebmVideoMimeType(item.mimeType, item.filename, item.url)
            .substringBefore(';').trim().lowercase(Locale.US).takeIf { it.startsWith("video/") && it != "video/*" }
            ?: VideoShareRefinementContract.mimeTypeFromUrl(item.filename.ifBlank { item.url })

    private fun shareVideoToMezon() {
        val attachment = buildShareAttachment() ?: return
        try {
            val token = ExistingVideoShareStore.put(attachment)
            val shareIntent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_SEND
                type = attachment.filetype
                putExtra(VideoShareRefinementContract.EXTRA_INTERNAL_SHARE_TOKEN, token)
            }
            context.startActivity(shareIntent)
        } catch (_: Exception) {
            if (isShowing) {
                showToast(ToastOverlay.ToastType.ERROR, R.string.message_toast_share_failed)
            }
        }
    }

    private fun shareVideoToOtherApps() {
        val attachment = buildShareAttachment() ?: return
        try {
            context.startActivity(VideoShareRefinementContract.createPreparationIntent(context, attachment))
        } catch (_: Exception) {
            if (isShowing) {
                showToast(ToastOverlay.ToastType.ERROR, R.string.message_toast_share_failed)
            }
        }
    }
}
