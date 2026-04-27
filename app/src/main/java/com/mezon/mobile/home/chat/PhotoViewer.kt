package com.mezon.mobile.home.chat

import android.animation.ObjectAnimator
import android.app.Dialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.github.chrisbanes.photoview.PhotoView
import com.mezon.mobile.R
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.util.createImgproxyUrl

class PhotoViewer(context: Context) : Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {

    private val backgroundDrawable = ColorDrawable(Color.BLACK)
    private val viewPager: ViewPager2
    private val topBar: FrameLayout
    private val bottomBar: LinearLayout
    private val counterView: android.widget.TextView
    private var toolbarVisible = true

    private var urls = emptyList<String>()
    private var isAnimated = false
    private var currentIndex = 0
    private val currentUrl get() = urls.getOrElse(currentIndex) { "" }

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        window?.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        window?.setBackgroundDrawable(backgroundDrawable)

        val root = FrameLayout(context)

        viewPager = ViewPager2(context).apply {
            offscreenPageLimit = 1
            setBackgroundColor(Color.BLACK)
        }
        root.addView(viewPager, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        topBar = FrameLayout(context)
        counterView = android.widget.TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
        }
        val closeDiameter = LayoutHelper.dp(44)
        val closeWrap = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x80000000.toInt())
            }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            clipToOutline = true
            isClickable = true
            isFocusable = true
            contentDescription = context.getString(R.string.common_close)
            val tvRipple = TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tvRipple, true)) {
                foreground = ContextCompat.getDrawable(context, tvRipple.resourceId)
            }
            setOnClickListener { dismissWithAnimation() }
            val closeBtn = ImageView(context).apply {
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                setColorFilter(Color.WHITE)
                val p = LayoutHelper.dp(10)
                setPadding(p, p, p, p)
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            addView(closeBtn, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ))
        }
        val counterParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )
        topBar.addView(counterView, counterParams)
        val closeBtnParams = FrameLayout.LayoutParams(closeDiameter, closeDiameter, Gravity.END or Gravity.CENTER_VERTICAL)
        closeBtnParams.marginEnd = LayoutHelper.dp(8)
        topBar.addView(closeWrap, closeBtnParams)

        val topBarParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            LayoutHelper.dp(56)
        )
        topBarParams.gravity = Gravity.TOP
        topBarParams.topMargin = LayoutHelper.dp(32)
        root.addView(topBar, topBarParams)

        bottomBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(0x99000000.toInt())
            val pad = LayoutHelper.dp(12)
            setPadding(pad, pad, pad, pad)
        }
        bottomBar.addView(createToolbarButton(context, android.R.drawable.ic_menu_share, "Share") {
            shareUrl(currentUrl)
        })
        bottomBar.addView(createToolbarButton(context, android.R.drawable.ic_menu_save, "Save") {
            downloadUrl(currentUrl)
        })
        bottomBar.addView(createToolbarButton(context, android.R.drawable.ic_menu_agenda, "Copy") {
            copyLink(currentUrl)
        })
        val bottomBarParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            LayoutHelper.dp(56)
        )
        bottomBarParams.gravity = Gravity.BOTTOM
        root.addView(bottomBar, bottomBarParams)

        setContentView(root)
    }

    fun show(
        url: String,
        animated: Boolean = false,
        gallery: List<String> = emptyList(),
        index: Int = 0,
        thumbBitmap: Bitmap? = null
    ) {
        urls = if (gallery.isEmpty()) listOf(url) else gallery
        currentIndex = if (index in urls.indices) index else 0
        isAnimated = animated

        viewPager.adapter = PhotoPagerAdapter(thumbBitmap.takeIf { !animated && urls.size == 1 })
        viewPager.setCurrentItem(currentIndex, false)
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentIndex = position
                updateCounter()
            }
        })

        updateCounter()
        backgroundDrawable.alpha = 0
        super.show()
        ObjectAnimator.ofInt(backgroundDrawable, "alpha", 0, 255).setDuration(200).start()
    }

    private fun updateCounter() {
        if (urls.size > 1) {
            counterView.text = "${currentIndex + 1} / ${urls.size}"
            counterView.visibility = View.VISIBLE
        } else {
            counterView.visibility = View.GONE
        }
    }

    private fun toggleToolbar() {
        toolbarVisible = !toolbarVisible
        val targetAlpha = if (toolbarVisible) 1f else 0f
        topBar.animate().alpha(targetAlpha).setDuration(200).start()
        bottomBar.animate().alpha(targetAlpha).setDuration(200).start()
    }

    private fun dismissWithAnimation() {
        val anim = ObjectAnimator.ofInt(backgroundDrawable, "alpha", 255, 0)
        anim.duration = 150
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) { dismiss() }
        })
        anim.start()
        viewPager.animate().alpha(0f).setDuration(150).start()
    }

    override fun onBackPressed() {
        dismissWithAnimation()
    }

    private inner class PhotoPagerAdapter(
        private val initialThumb: Bitmap?
    ) : RecyclerView.Adapter<PhotoPagerAdapter.ViewHolder>() {

        inner class ViewHolder(val photoView: PhotoView) : RecyclerView.ViewHolder(photoView) {
            var pendingLoad: MezonImageLoader.Cancellable? = null
            var bindGeneration: Long = 0L
        }

        private fun stopAndClearPhotoView(photoView: PhotoView) {
            (photoView.drawable as? AnimatedImageDrawable)?.stop()
            photoView.setImageDrawable(null)
            photoView.getAttacher().update()
            photoView.setScale(1f, false)
        }

        private fun applyLoadedDrawable(
            holder: ViewHolder,
            expectedPosition: Int,
            expectedUrl: String,
            drawable: Drawable
        ) {
            if (holder.bindingAdapterPosition != expectedPosition) return
            if (urls.getOrNull(expectedPosition) != expectedUrl) return
            val pv = holder.photoView
            (pv.drawable as? AnimatedImageDrawable)?.stop()
            pv.setImageDrawable(drawable)
            pv.getAttacher().update()
            pv.setScale(1f, false)
            if (drawable is AnimatedImageDrawable) {
                drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                drawable.start()
            }
        }

        private fun applyLoadedBitmap(
            holder: ViewHolder,
            expectedPosition: Int,
            expectedUrl: String,
            bmp: Bitmap
        ) {
            if (holder.bindingAdapterPosition != expectedPosition) return
            if (urls.getOrNull(expectedPosition) != expectedUrl) return
            val pv = holder.photoView
            (pv.drawable as? AnimatedImageDrawable)?.stop()
            pv.setImageDrawable(BitmapDrawable(pv.context.resources, bmp))
            pv.getAttacher().update()
            pv.setScale(1f, false)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val photoView = PhotoView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(Color.BLACK)
                maximumScale = 4f
                mediumScale = 2f
                minimumScale = 0.5f
                setOnSingleFlingListener { _, _, velocityX, velocityY ->
                    val absX = kotlin.math.abs(velocityX)
                    val absY = kotlin.math.abs(velocityY)
                    val nearDefault = kotlin.math.abs(scale - 1f) < 0.06f
                    if (absY > absX && absY > 800f && velocityY > 0 && nearDefault) {
                        dismissWithAnimation()
                        true
                    } else false
                }
                setOnPhotoTapListener { _, _, _ -> toggleToolbar() }
                setOnOutsidePhotoTapListener { toggleToolbar() }
            }
            return ViewHolder(photoView)
        }

        override fun onViewRecycled(holder: ViewHolder) {
            super.onViewRecycled(holder)
            holder.pendingLoad?.cancel()
            holder.pendingLoad = null
            holder.bindGeneration++
            stopAndClearPhotoView(holder.photoView)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val url = urls[position]
            val photoView = holder.photoView

            holder.pendingLoad?.cancel()
            holder.pendingLoad = null
            holder.bindGeneration++
            val bindGen = holder.bindGeneration
            stopAndClearPhotoView(photoView)

            if (position == currentIndex && initialThumb != null) {
                photoView.setImageBitmap(initialThumb)
                photoView.getAttacher().update()
                photoView.setScale(1f, false)
            }

            val screenW = context.resources.displayMetrics.widthPixels
            val screenH = context.resources.displayMetrics.heightPixels
            val loader = MezonImageLoader.getInstance(context)

            if (isAnimated) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    holder.pendingLoad = loader.loadDrawable(url, screenW, screenH,
                        onSuccess = { drawable ->
                            if (holder.bindGeneration != bindGen) return@loadDrawable
                            applyLoadedDrawable(holder, position, url, drawable)
                        }
                    )
                } else {
                    holder.pendingLoad = loader.load(url, screenW, screenH,
                        onSuccess = { bmp ->
                            if (holder.bindGeneration != bindGen) return@load
                            applyLoadedBitmap(holder, position, url, bmp)
                        }
                    )
                }
            } else {
                val loadUrl = createImgproxyUrl(url, screenW, screenH, "fit")
                holder.pendingLoad = loader.load(loadUrl, screenW, screenH,
                    onSuccess = { bmp ->
                        if (holder.bindGeneration != bindGen) return@load
                        applyLoadedBitmap(holder, position, url, bmp)
                    }
                )
            }
        }

        override fun getItemCount() = urls.size
    }

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
}
