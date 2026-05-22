package com.mezon.mobile.home.chat

import android.app.Dialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Environment
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.mezon.mobile.core.LayoutHelper

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoPlayerDialog(context: Context) : Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {

    private val playerView: PlayerView
    private var player: ExoPlayer? = null
    private var currentUrl = ""

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        window?.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.BLACK))

        val root = FrameLayout(context)

        playerView = PlayerView(context).apply {
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            setBackgroundColor(Color.BLACK)
            controllerAutoShow = false
        }
        root.addView(playerView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val closeButton = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(Color.WHITE)
            setPadding(LayoutHelper.dp(12), LayoutHelper.dp(12), LayoutHelper.dp(12), LayoutHelper.dp(12))
            setOnClickListener { dismiss() }
        }
        val closeParams = FrameLayout.LayoutParams(LayoutHelper.dp(48), LayoutHelper.dp(48))
        closeParams.gravity = Gravity.TOP or Gravity.START
        closeParams.topMargin = LayoutHelper.dp(40)
        closeParams.leftMargin = LayoutHelper.dp(8)
        root.addView(closeButton, closeParams)

        val toolbar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(0x99000000.toInt())
            val pad = LayoutHelper.dp(12)
            setPadding(pad, pad, pad, pad)
        }
        val toolbarParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            LayoutHelper.dp(56)
        )
        toolbarParams.gravity = Gravity.BOTTOM
        root.addView(toolbar, toolbarParams)

        toolbar.addView(makeButton(android.R.drawable.ic_menu_share) { shareUrl() })
        toolbar.addView(makeButton(android.R.drawable.ic_menu_save) { downloadUrl() })
        toolbar.addView(makeButton(android.R.drawable.ic_menu_agenda) { copyLink() })

        setContentView(root)
        setOnDismissListener { releasePlayer() }
    }

    private fun makeButton(iconRes: Int, onClick: () -> Unit): ImageView {
        return ImageView(context).apply {
            setImageResource(iconRes)
            setColorFilter(Color.WHITE)
            val p = LayoutHelper.dp(16)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(LayoutHelper.dp(56), LayoutHelper.dp(56)).apply {
                marginStart = LayoutHelper.dp(8)
                marginEnd = LayoutHelper.dp(8)
            }
            setOnClickListener { onClick() }
        }
    }

    fun play(url: String) {
        currentUrl = url
        releasePlayer()
        player = ExoPlayer.Builder(context).build().also { exo ->
            playerView.player = exo
            exo.setMediaItem(MediaItem.fromUri(url))
            exo.repeatMode = Player.REPEAT_MODE_OFF
            exo.prepare()
            exo.playWhenReady = true
        }
        show()
        playerView.hideController()
    }

    private fun releasePlayer() {
        player?.release()
        player = null
        playerView.player = null
    }

    private fun copyLink() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("url", currentUrl))
        Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
    }

    private fun downloadUrl() {
        try {
            val filename = currentUrl.substringAfterLast('/').substringBefore('?').ifEmpty { "video" }
            val request = DownloadManager.Request(Uri.parse(currentUrl))
                .setTitle(filename)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(context, "Downloading...", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(context, "Download failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareUrl() {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, currentUrl)
            }
            context.startActivity(android.content.Intent.createChooser(intent, "Share"))
        } catch (_: Exception) {}
    }

    override fun onBackPressed() {
        dismiss()
    }
}
