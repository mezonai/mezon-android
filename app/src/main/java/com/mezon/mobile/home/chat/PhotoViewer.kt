package com.mezon.mobile.home.chat

import android.animation.ObjectAnimator
import android.app.Dialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Environment
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import coil.Coil
import coil.request.ImageRequest
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.util.createImgproxyUrl

class PhotoViewer(context: Context) : Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {

    private val imageView: ImageView
    private val backgroundDrawable = ColorDrawable(Color.BLACK)
    private val closeButton: ImageView

    private val matrix = Matrix()
    private val savedMatrix = Matrix()
    private val startPoint = PointF()
    private val midPoint = PointF()
    private var oldDist = 1f
    private var mode = NONE
    private var minScale = 1f
    private var maxScale = 5f

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        window?.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        window?.setBackgroundDrawable(backgroundDrawable)

        val root = FrameLayout(context)

        imageView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.MATRIX
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
    private var currentIndex = 0

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

    fun show(url: String, animated: Boolean = false, gallery: List<String> = emptyList(), index: Int = 0) {
        urls = if (gallery.isEmpty()) listOf(url) else gallery
        currentIndex = if (index in urls.indices) index else 0
        currentUrl = urls[currentIndex]
        isAnimated = animated
        val loadUrl = if (animated) url else {
            val screenW = context.resources.displayMetrics.widthPixels
            val screenH = context.resources.displayMetrics.heightPixels
            createImgproxyUrl(url, screenW, screenH, "fit")
        }

        val request = ImageRequest.Builder(context)
            .data(loadUrl)
            .allowHardware(false)
            .target(onSuccess = { drawable ->
                if (animated) {
                    imageView.scaleType = ImageView.ScaleType.FIT_CENTER
                    imageView.setImageDrawable(drawable)
                    (drawable as? android.graphics.drawable.Animatable)?.start()
                } else {
                    imageView.setImageDrawable(drawable)
                    imageView.post { fitImageToScreen() }
                }
            })
            .build()
        Coil.imageLoader(context).enqueue(request)

        backgroundDrawable.alpha = 0
        super.show()
        ObjectAnimator.ofInt(backgroundDrawable, "alpha", 0, 255).setDuration(200).start()
    }

    private fun navigateTo(index: Int) {
        if (index !in urls.indices) return
        currentIndex = index
        currentUrl = urls[currentIndex]
        val direction = if (index > currentIndex) 1f else -1f
        imageView.animate().alpha(0f).setDuration(100).withEndAction {
            loadCurrentImage()
            imageView.animate().alpha(1f).setDuration(150).start()
        }.start()
    }

    private fun loadCurrentImage() {
        val loadUrl = if (isAnimated) currentUrl else {
            val screenW = context.resources.displayMetrics.widthPixels
            val screenH = context.resources.displayMetrics.heightPixels
            createImgproxyUrl(currentUrl, screenW, screenH, "fit")
        }
        val request = ImageRequest.Builder(context)
            .data(loadUrl)
            .allowHardware(false)
            .target(onSuccess = { drawable ->
                if (isAnimated) {
                    imageView.scaleType = ImageView.ScaleType.FIT_CENTER
                    imageView.setImageDrawable(drawable)
                    (drawable as? android.graphics.drawable.Animatable)?.start()
                } else {
                    imageView.scaleType = ImageView.ScaleType.MATRIX
                    imageView.setImageDrawable(drawable)
                    imageView.post { fitImageToScreen() }
                }
            })
            .build()
        Coil.imageLoader(context).enqueue(request)
    }

    private fun fitImageToScreen() {
        val drawable = imageView.drawable ?: return
        val dw = drawable.intrinsicWidth.toFloat()
        val dh = drawable.intrinsicHeight.toFloat()
        val vw = imageView.width.toFloat()
        val vh = imageView.height.toFloat()
        if (dw <= 0 || dh <= 0 || vw <= 0 || vh <= 0) return

        val scale = minOf(vw / dw, vh / dh)
        minScale = scale
        maxScale = scale * 5f
        matrix.reset()
        matrix.setScale(scale, scale)
        matrix.postTranslate((vw - dw * scale) / 2f, (vh - dh * scale) / 2f)
        imageView.imageMatrix = matrix
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
        val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val values = FloatArray(9)
                matrix.getValues(values)
                val currentScale = values[Matrix.MSCALE_X]
                var scaleFactor = detector.scaleFactor
                val newScale = currentScale * scaleFactor
                if (newScale > maxScale) scaleFactor = maxScale / currentScale
                if (newScale < minScale) scaleFactor = minScale / currentScale
                matrix.postScale(scaleFactor, scaleFactor, detector.focusX, detector.focusY)
                view.imageMatrix = matrix
                return true
            }
        })

        val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val values = FloatArray(9)
                matrix.getValues(values)
                val currentScale = values[Matrix.MSCALE_X]
                if (currentScale > minScale * 1.5f) {
                    fitImageToScreen()
                } else {
                    val targetScale = minScale * 2.5f
                    matrix.postScale(targetScale / currentScale, targetScale / currentScale, e.x, e.y)
                    view.imageMatrix = matrix
                }
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                dismissWithAnimation()
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (urls.size <= 1) return false
                val values = FloatArray(9)
                matrix.getValues(values)
                if (values[Matrix.MSCALE_X] > minScale * 1.05f) return false
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
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    savedMatrix.set(matrix)
                    startPoint.set(event.x, event.y)
                    mode = DRAG
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    oldDist = spacing(event)
                    if (oldDist > 10f) {
                        savedMatrix.set(matrix)
                        midPoint(midPoint, event)
                        mode = ZOOM
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (mode == DRAG && !scaleDetector.isInProgress) {
                        val dx = event.x - startPoint.x
                        val dy = event.y - startPoint.y
                        val values = FloatArray(9)
                        matrix.getValues(values)
                        val currentScale = values[Matrix.MSCALE_X]
                        if (currentScale <= minScale * 1.05f && kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.5f) {
                            val progress = (kotlin.math.abs(dy) / (view.height * 0.4f)).coerceIn(0f, 1f)
                            view.translationY = dy
                            backgroundDrawable.alpha = ((1f - progress) * 255).toInt()
                        } else {
                            matrix.set(savedMatrix)
                            matrix.postTranslate(dx, dy)
                            view.imageMatrix = matrix
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    if (mode == DRAG && kotlin.math.abs(view.translationY) > view.height * 0.15f) {
                        dismissWithAnimation()
                    } else if (view.translationY != 0f) {
                        view.animate().translationY(0f).setDuration(150).start()
                        ObjectAnimator.ofInt(backgroundDrawable, "alpha", backgroundDrawable.alpha, 255).setDuration(150).start()
                    }
                    mode = NONE
                }
            }
            true
        }
    }

    private fun spacing(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val x = event.getX(0) - event.getX(1)
        val y = event.getY(0) - event.getY(1)
        return kotlin.math.sqrt(x * x + y * y)
    }

    private fun midPoint(point: PointF, event: MotionEvent) {
        if (event.pointerCount < 2) return
        point.set((event.getX(0) + event.getX(1)) / 2, (event.getY(0) + event.getY(1)) / 2)
    }

    companion object {
        private const val NONE = 0
        private const val DRAG = 1
        private const val ZOOM = 2
    }
}
