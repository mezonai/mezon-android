package com.mezon.mobile.ui.cells

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import java.util.concurrent.Executors

class ImagePickerHelper(
    private val fragment: Fragment,
    private val onImagePicked: (Uri) -> Unit
) {
    private var launcher: ActivityResultLauncher<PickVisualMediaRequest> =
        fragment.registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) onImagePicked(uri)
        }

    fun launch() {
        launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
}

class ImagePickerView(context: Context, private val theme: ThemeColors) : FrameLayout(context) {

    private val imageView: ImageView
    private val placeholderIcon: ImageView
    private var isRounded = true
    var onClickPick: (() -> Unit)? = null

    init {
        val size = LayoutHelper.dp(80)

        imageView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        addView(imageView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        placeholderIcon = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_menu_camera)
            setColorFilter(theme.onSurfaceVariant)
        }
        addView(placeholderIcon, LayoutHelper.createFrame(24, 24, Gravity.CENTER))

        setOnClickListener { onClickPick?.invoke() }
        updateShape()
    }

    fun setRounded(rounded: Boolean) {
        isRounded = rounded
        updateShape()
    }

    fun setSizeDp(dp: Int) {
        val lp = layoutParams
        val px = LayoutHelper.dp(dp)
        if (lp != null) {
            lp.width = px
            lp.height = px
            layoutParams = lp
        }
    }

    fun setImageUri(uri: Uri) {
        DECODE_EXECUTOR.execute {
            try {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }

                val maxPx = LayoutHelper.dp(200)
                opts.inSampleSize = calculateInSampleSize(opts.outWidth, opts.outHeight, maxPx, maxPx)
                opts.inJustDecodeBounds = false
                opts.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888

                val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                if (bitmap != null) {
                    MAIN_HANDLER.post {
                        imageView.setImageDrawable(BitmapDrawable(context.resources, bitmap))
                        imageView.visibility = View.VISIBLE
                        placeholderIcon.visibility = View.GONE
                    }
                }
            } catch (_: Exception) {}
        }
    }

    companion object {
        private val DECODE_EXECUTOR = Executors.newSingleThreadExecutor()
        private val MAIN_HANDLER = Handler(Looper.getMainLooper())

        private fun calculateInSampleSize(w: Int, h: Int, reqW: Int, reqH: Int): Int {
            var sample = 1
            if (reqW <= 0 || reqH <= 0) return 1
            while (w / (sample * 2) >= reqW && h / (sample * 2) >= reqH) sample *= 2
            return sample
        }
    }

    private fun updateShape() {
        val bg = GradientDrawable().apply {
            setColor(theme.surfaceVariant)
            cornerRadius = if (isRounded) LayoutHelper.dpf(999f) else LayoutHelper.dpf(8f)
        }
        background = bg
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                if (isRounded) {
                    outline.setOval(0, 0, view.width, view.height)
                } else {
                    outline.setRoundRect(0, 0, view.width, view.height, LayoutHelper.dpf(8f))
                }
            }
        }
    }
}
