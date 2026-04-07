package com.mezon.mobile.home.chat

import android.animation.ObjectAnimator
import android.app.Dialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.util.createImgproxyUrl

class PhotoViewer(context: Context) : Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {

    private val imageView: ImageView
    private val backgroundDrawable = ColorDrawable(Color.BLACK)
    private val closeButton: ImageView

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        window?.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        window?.setBackgroundDrawable(backgroundDrawable)

        val root = FrameLayout(context)

        imageView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        root.addView(imageView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        closeButton = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(Color.WHITE)
            setPadding(LayoutHelper.dp(12), LayoutHelper.dp(12), LayoutHelper.dp(12), LayoutHelper.dp(12))
            setOnClickListener { dismissWithAnimation() }
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

        toolbar.addView(createToolbarButton(context, android.R.drawable.ic_menu_share, "Share") {
            shareUrl(currentUrl)
        })
        toolbar.addView(createToolbarButton(context, android.R.drawable.ic_menu_save, "Save") {
            downloadUrl(currentUrl)
        })
        toolbar.addView(createToolbarButton(context, android.R.drawable.ic_menu_agenda, "Copy") {
            copyLink(currentUrl)
        })

        setContentView(root)
        setupTouchHandling(imageView)
    }

    private var currentUrl = ""
    private var isAnimated = false
    private var urls = emptyList<String>()

    private fun createToolbarButton(ctx: Context, iconRes: Int, desc: String, onClick: () -> Unit): ImageView {
        return ImageView(ctx).apply {
            setImageResource(iconRes)
            setColorFilter(Color.WHITE)
            contentDescription = desc
            val p = LayoutHelper.dp(16)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(LayoutHelper.dp(56), LayoutHelper.dp(56)).apply {
                marginStart = LayoutHelper.dp(8)
                marginEnd = LayoutHelper.dp(8)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun copyLink(url: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("url", url))
        Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
    }

    private fun downloadUrl(url: String) {
        try {
            val filename = url.substringAfterLast('/').substringBefore('?').ifEmpty { "download" }
            val request = DownloadManager.Request(Uri.parse(url))
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

    private fun shareUrl(url: String) {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, url)
            }
            context.startActivity(android.content.Intent.createChooser(intent, "Share"))
        } catch (_: Exception) {}
    }

    private var currentIndex = 0

    fun show(url: String, animated: Boolean = false, gallery: List<String> = emptyList(), index: Int = 0, thumbBitmap: Bitmap? = null) {
        urls = if (gallery.isEmpty()) listOf(url) else gallery
        currentIndex = if (index in urls.indices) index else 0
        currentUrl = urls[currentIndex]
        isAnimated = animated

        if (thumbBitmap != null && !animated) {
            imageView.setImageDrawable(BitmapDrawable(context.resources, thumbBitmap))
        }

        backgroundDrawable.alpha = 0
        super.show()
        ObjectAnimator.ofInt(backgroundDrawable, "alpha", 0, 255).setDuration(200).start()

        val screenW = context.resources.displayMetrics.widthPixels
        val screenH = context.resources.displayMetrics.heightPixels
        val loader = MezonImageLoader.getInstance(context)

        if (animated) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                loader.loadDrawable(url, screenW, screenH,
                    onSuccess = { drawable ->
                        imageView.setImageDrawable(drawable)
                        if (drawable is AnimatedImageDrawable) {
                            drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                            drawable.start()
                        }
                    }
                )
            } else {
                loader.load(url, screenW, screenH,
                    onSuccess = { bmp ->
                        imageView.setImageDrawable(BitmapDrawable(context.resources, bmp))
                    }
                )
            }
        } else {
            val loadUrl = createImgproxyUrl(url, screenW, screenH, "fit")
            loader.load(loadUrl, screenW, screenH,
                onSuccess = { bmp ->
                    imageView.setImageDrawable(BitmapDrawable(context.resources, bmp))
                }
            )
        }
    }

    private fun navigateTo(index: Int) {
        if (index !in urls.indices) return
        currentIndex = index
        currentUrl = urls[currentIndex]
        imageView.animate().alpha(0f).setDuration(100).withEndAction {
            loadCurrentImage()
            imageView.animate().alpha(1f).setDuration(150).start()
        }.start()
    }

    private fun loadCurrentImage() {
        val screenW = context.resources.displayMetrics.widthPixels
        val screenH = context.resources.displayMetrics.heightPixels
        val loader = MezonImageLoader.getInstance(context)

        if (isAnimated && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            loader.loadDrawable(currentUrl, screenW, screenH,
                onSuccess = { drawable ->
                    imageView.setImageDrawable(drawable)
                    if (drawable is AnimatedImageDrawable) {
                        drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                        drawable.start()
                    }
                }
            )
        } else {
            val loadUrl = if (isAnimated) currentUrl else createImgproxyUrl(currentUrl, screenW, screenH, "fit")
            loader.load(loadUrl, screenW, screenH,
                onSuccess = { bmp ->
                    imageView.setImageDrawable(BitmapDrawable(context.resources, bmp))
                }
            )
        }
    }

    private fun dismissWithAnimation() {
        val anim = ObjectAnimator.ofInt(backgroundDrawable, "alpha", 255, 0)
        anim.duration = 150
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                dismiss()
            }
        })
        anim.start()
        imageView.animate().alpha(0f).setDuration(150).start()
    }

    override fun onBackPressed() {
        dismissWithAnimation()
    }

    private fun setupTouchHandling(view: ImageView) {
        val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                dismissWithAnimation()
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (urls.size <= 1) return false
                if (kotlin.math.abs(velocityX) > kotlin.math.abs(velocityY) && kotlin.math.abs(velocityX) > 800) {
                    if (velocityX < 0 && currentIndex < urls.size - 1) {
                        navigateTo(currentIndex + 1)
                        return true
                    } else if (velocityX > 0 && currentIndex > 0) {
                        navigateTo(currentIndex - 1)
                        return true
                    }
                }
                return false
            }
        })

        view.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }
}
