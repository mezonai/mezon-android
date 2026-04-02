package com.mezon.mobile.ui.cells

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import java.util.concurrent.Executors
import kotlin.math.min

class ImagePickerHelper(
    private val fragment: BaseFragment,
    private val onImagePicked: (Uri) -> Unit
) {
    fun launch() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        fragment.startActivityForResult(Intent.createChooser(intent, "Select image"), REQUEST_CODE_PICK_IMAGE)
    }

    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_CODE_PICK_IMAGE || resultCode != Activity.RESULT_OK) {
            return false
        }
        val uri = data?.data ?: return true
        onImagePicked(uri)
        return true
    }

    companion object {
        private const val REQUEST_CODE_PICK_IMAGE = 9127
    }
}

class ImagePickerView(context: Context, private val theme: ThemeColors) : FrameLayout(context) {

    private val imageView: ImageView
    private val placeholderContainer: LinearLayout
    private val placeholderIcon: ImageView
    private val placeholderLabel: TextView
    private val badgeView: ImageView
    private var isRounded = true
    private var uploadStyle = false
    private var hasImage = false
    var onClickPick: (() -> Unit)? = null

    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = LayoutHelper.dpf(1.5f)
        pathEffect = DashPathEffect(floatArrayOf(LayoutHelper.dpf(4f), LayoutHelper.dpf(4f)), 0f)
        color = theme.onSurfaceVariant
        alpha = 140
    }
    private val dashRect = RectF()

    init {
        imageView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        addView(imageView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        placeholderIcon = ImageView(context).apply {
            setImageDrawable(MezonIcon.cameraIcon.getDrawable(context, theme.onSurfaceVariant))
        }

        placeholderLabel = TextView(context).apply {
            setTextColor(theme.onSurfaceVariant)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            visibility = View.GONE
            setPadding(0, LayoutHelper.dp(6), 0, 0)
        }

        placeholderContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(placeholderIcon, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))
            addView(placeholderLabel, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))
        }
        addView(placeholderContainer, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER))

        badgeView = ImageView(context).apply {
            setImageDrawable(MezonIcon.circlePlusPrimaryIcon.getDrawable(context))
            visibility = View.GONE
        }
        addView(badgeView, LayoutHelper.createFrame(24, 24, Gravity.END or Gravity.TOP, 0f, 2f, 2f, 0f))

        setOnClickListener { onClickPick?.invoke() }
        setWillNotDraw(false)
        updateShape()
    }

    fun setUploadStyle(enabled: Boolean, label: String? = null) {
        uploadStyle = enabled
        placeholderLabel.text = label
        placeholderLabel.visibility = if (enabled && !label.isNullOrEmpty() && !hasImage) View.VISIBLE else View.GONE
        badgeView.visibility = if (enabled && !hasImage) View.VISIBLE else View.GONE
        updateShape()
        invalidate()
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
                        hasImage = true
                        imageView.setImageDrawable(BitmapDrawable(context.resources, bitmap))
                        imageView.visibility = View.VISIBLE
                        placeholderContainer.visibility = View.GONE
                        badgeView.visibility = View.GONE
                        invalidate()
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
            setColor(if (uploadStyle) theme.background else theme.surfaceVariant)
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!uploadStyle || hasImage) return
        val size = min(width, height).toFloat()
        val halfStroke = dashPaint.strokeWidth / 2f
        val left = (width - size) / 2f + halfStroke
        val top = (height - size) / 2f + halfStroke
        val right = left + size - dashPaint.strokeWidth
        val bottom = top + size - dashPaint.strokeWidth
        dashRect.set(left, top, right, bottom)
        canvas.drawOval(dashRect, dashPaint)
    }
}
