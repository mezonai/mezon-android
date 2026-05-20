package com.mezon.mobile.ui.shared

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.ui.cells.MezonIcon
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

class TransformCanvasView(
    context: Context,
    private val outsideColor: Int = Color.BLACK,
    private val cropChromeColor: Int = Color.WHITE,
) : View(context) {

    private var bitmap: Bitmap? = null

    private val imageMatrix = Matrix()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = outsideColor }

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = LayoutHelper.dpf(1f)
        color = cropChromeColor
    }

    private val cornerPath = Path()
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = LayoutHelper.dpf(3.5f)
        color = cropChromeColor
        strokeJoin = Paint.Join.MITER
        strokeCap = Paint.Cap.SQUARE
    }

    private val overlayPath = Path()
    private val holePath = Path()

    private val cropRect = RectF()

    private var cropAspectW = 1f
    private var cropAspectH = 1f

    private val initialMatrix = Matrix()

    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var dragMode = false

    companion object {
        private const val MAX_COVER_ITERATIONS = 28
        private const val MAX_ZOOM_ITERATIONS = 14
        private const val MAX_ZOOM_FACTOR = 8f
    }

    fun setImageBitmap(b: Bitmap?) {
        bitmap = b
        if (width > 0 && height > 0 && b != null) {
            resetTransform()
        }
        invalidate()
    }

    fun resetToInitial() {
        imageMatrix.set(initialMatrix)
        invalidate()
    }

    fun rotateByDegrees(delta: Float) {
        imageMatrix.postRotate(delta, cropRect.centerX(), cropRect.centerY())
        clampAfterTransform()
        invalidate()
    }

    fun setCropAspectRatio(width: Float, height: Float) {
        cropAspectW = width.coerceAtLeast(1e-3f)
        cropAspectH = height.coerceAtLeast(1e-3f)
        if (width > 0f && height > 0f && measuredWidth > 0 && measuredHeight > 0) {
            layoutCropRect(measuredWidth, measuredHeight)
            bitmap?.let { resetTransform() }
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutCropRect(w, h)
        bitmap?.let { resetTransform() }
    }

    private fun layoutCropRect(w: Int, h: Int) {
        val pad = LayoutHelper.dp(24f)
        val maxW = (w - pad * 2).toFloat().coerceAtLeast(LayoutHelper.dp(120f).toFloat())
        val maxH = (h - pad * 2).toFloat().coerceAtLeast(LayoutHelper.dp(120f).toFloat())
        val aspect = cropAspectW / cropAspectH
        var cropW = maxW
        var cropH = cropW / aspect
        if (cropH > maxH) {
            cropH = maxH
            cropW = cropH * aspect
        }
        val left = (w - cropW) / 2f
        val top = (h - cropH) / 2f
        cropRect.set(left, top, left + cropW, top + cropH)
    }

    private fun resetTransform() {
        val bmp = bitmap ?: return
        imageMatrix.reset()
        val s = max(cropRect.width() / bmp.width, cropRect.height() / bmp.height)
        imageMatrix.postScale(s, s)
        val scaledW = bmp.width * s
        val scaledH = bmp.height * s
        imageMatrix.postTranslate(
            cropRect.centerX() - scaledW / 2f,
            cropRect.centerY() - scaledH / 2f
        )
        clampAfterTransform()
        initialMatrix.set(imageMatrix)
    }

    private fun mapBitmapBounds(): RectF {
        val bmp = bitmap!!
        val pts = floatArrayOf(
            0f, 0f,
            bmp.width.toFloat(), 0f,
            bmp.width.toFloat(), bmp.height.toFloat(),
            0f, bmp.height.toFloat()
        )
        imageMatrix.mapPoints(pts)
        val minX = minOf(pts[0], pts[2], pts[4], pts[6])
        val maxX = maxOf(pts[0], pts[2], pts[4], pts[6])
        val minY = minOf(pts[1], pts[3], pts[5], pts[7])
        val maxY = maxOf(pts[1], pts[3], pts[5], pts[7])
        return RectF(minX, minY, maxX, maxY)
    }

    private fun coversCrop(bounds: RectF, slack: Float): Boolean =
        bounds.left <= cropRect.left + slack &&
            bounds.right >= cropRect.right - slack &&
            bounds.top <= cropRect.top + slack &&
            bounds.bottom >= cropRect.bottom - slack

    private fun ensureCropCover() {
        bitmap ?: return
        repeat(MAX_COVER_ITERATIONS) {
            val b = mapBitmapBounds()
            if (coversCrop(b, 0.5f)) return
            val nw = cropRect.width() / b.width().coerceAtLeast(1e-3f)
            val nh = cropRect.height() / b.height().coerceAtLeast(1e-3f)
            val factor = max(nw, nh) * 1.004f
            if (factor <= 1.0005f) return
            imageMatrix.postScale(factor, factor, cropRect.centerX(), cropRect.centerY())
        }
    }

    private fun clampPanInCrop() {
        val b = mapBitmapBounds()
        var dx = 0f
        var dy = 0f
        if (b.width() <= cropRect.width() + 1f) {
            dx = cropRect.centerX() - b.centerX()
        } else {
            if (b.left > cropRect.left) dx = cropRect.left - b.left
            else if (b.right < cropRect.right) dx = cropRect.right - b.right
        }
        if (b.height() <= cropRect.height() + 1f) {
            dy = cropRect.centerY() - b.centerY()
        } else {
            if (b.top > cropRect.top) dy = cropRect.top - b.top
            else if (b.bottom < cropRect.bottom) dy = cropRect.bottom - b.bottom
        }
        if (dx != 0f || dy != 0f) {
            imageMatrix.postTranslate(dx, dy)
        }
    }

    private fun clampExcessiveZoom() {
        bitmap ?: return
        val maxW = cropRect.width() * MAX_ZOOM_FACTOR
        repeat(MAX_ZOOM_ITERATIONS) {
            val b = mapBitmapBounds()
            if (b.width() <= maxW) return
            val factor = maxW / b.width()
            imageMatrix.postScale(factor, factor, cropRect.centerX(), cropRect.centerY())
        }
    }

    private fun clampAfterTransform() {
        ensureCropCover()
        clampPanInCrop()
        clampExcessiveZoom()
        clampPanInCrop()
    }

    private fun drawCornerBrackets(canvas: Canvas) {
        val a = LayoutHelper.dpf(22f)
        val r = cropRect
        val p = cornerPaint
        cornerPath.reset()
        cornerPath.moveTo(r.left + a, r.top)
        cornerPath.lineTo(r.left, r.top)
        cornerPath.lineTo(r.left, r.top + a)
        canvas.drawPath(cornerPath, p)

        cornerPath.reset()
        cornerPath.moveTo(r.right - a, r.top)
        cornerPath.lineTo(r.right, r.top)
        cornerPath.lineTo(r.right, r.top + a)
        canvas.drawPath(cornerPath, p)

        cornerPath.reset()
        cornerPath.moveTo(r.left + a, r.bottom)
        cornerPath.lineTo(r.left, r.bottom)
        cornerPath.lineTo(r.left, r.bottom - a)
        canvas.drawPath(cornerPath, p)

        cornerPath.reset()
        cornerPath.moveTo(r.right - a, r.bottom)
        cornerPath.lineTo(r.right, r.bottom)
        cornerPath.lineTo(r.right, r.bottom - a)
        canvas.drawPath(cornerPath, p)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(outsideColor)
        val bmp = bitmap ?: return

        canvas.drawBitmap(bmp, imageMatrix, bitmapPaint)

        overlayPath.reset()
        overlayPath.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        holePath.reset()
        holePath.addRect(cropRect, Path.Direction.CW)
        overlayPath.op(holePath, Path.Op.DIFFERENCE)
        canvas.drawPath(overlayPath, dimPaint)

        canvas.drawRect(cropRect, framePaint)
        drawCornerBrackets(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragMode = true
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_POINTER_DOWN -> dragMode = false
            MotionEvent.ACTION_MOVE -> {
                if (dragMode && !scaleDetector.isInProgress && event.pointerCount == 1) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    imageMatrix.postTranslate(dx, dy)
                    clampAfterTransform()
                    lastTouchX = event.x
                    lastTouchY = event.y
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragMode = false
        }
        return true
    }

    fun renderCropped(outWidth: Int, outHeight: Int): Bitmap? {
        val bmp = bitmap ?: return null
        if (outWidth <= 0 || outHeight <= 0 || cropRect.width() <= 0f) return null

        val viewToOutput = Matrix()
        viewToOutput.setRectToRect(
            cropRect,
            RectF(0f, 0f, outWidth.toFloat(), outHeight.toFloat()),
            Matrix.ScaleToFit.FILL
        )

        val bmpToOutput = Matrix()
        bmpToOutput.setConcat(viewToOutput, imageMatrix)

        val out = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(bmp, bmpToOutput, bitmapPaint)
        return out
    }

    fun renderCroppedSquare(outSize: Int): Bitmap? = renderCropped(outSize, outSize)

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            imageMatrix.postScale(detector.scaleFactor, detector.scaleFactor, detector.focusX, detector.focusY)
            clampAfterTransform()
            invalidate()
            return true
        }
    }
}

open class ImageTransformFragment : BaseFragment() {

    companion object {
        const val ARG_URI = "uri"
        const val EXPORT_PX = 400

        private const val EXPORT_JPEG_QUALITY_START = 92
        private const val EXPORT_JPEG_QUALITY_FLOOR = 50
        private const val EXPORT_JPEG_QUALITY_STEP = 10
    }

    private val cropBarBlue = 0xFF0A84FF.toInt()
    private val cropBarGold = 0xFFFFD60A.toInt()

    protected lateinit var transformCanvas: TransformCanvasView
    protected lateinit var progress: ProgressBar
    protected lateinit var cropBottomBar: LinearLayout
    protected lateinit var uploadOverlay: FrameLayout

    protected lateinit var imageUri: Uri
    protected var loadedBitmap: Bitmap? = null
    protected var isWorking = false

    override fun onInject(entryPoint: FragmentEntryPoint) {}

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        val uriStr = arguments?.getString(ARG_URI)
        if (uriStr.isNullOrEmpty()) {
            return false
        }
        imageUri = Uri.parse(uriStr)
        return true
    }

    // Max source file size before decode (bytes)
    protected open fun maxSourceBytes(): Long = Long.MAX_VALUE

    protected open fun onSourceTooLarge() {
        finishFragment()
    }

    protected open fun onDecodeFailed() {
        finishFragment()
    }

    protected open fun onExportTooLarge() {}

    // Max encoded JPEG size for export (bytes)
    protected open fun maxExportBytes(): Int = 1 shl 20

    /** Crop frame aspect. Default 1×1. */
    protected open fun cropAspectWidth(): Float = 1f

    protected open fun cropAspectHeight(): Float = 1f

    protected open fun exportWidthPx(): Int = EXPORT_PX

    protected open fun exportHeightPx(): Int = EXPORT_PX

    protected open fun cacheFilePrefix(): String = "image_transform"

    /**
     * Called on main after a cropped JPEG file exists.
     * On the main thread to clear loading overlay state.
     */
    protected open fun onExportReady(jpegFile: File, onWorkFinished: () -> Unit) {
        jpegFile.delete()
        onWorkFinished()
        finishFragment()
    }

    override fun createView(context: Context): View {
        if (!::imageUri.isInitialized) {
            finishFragment()
            return View(context)
        }

        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
        }

        transformCanvas = TransformCanvasView(context).apply {
            setCropAspectRatio(cropAspectWidth(), cropAspectHeight())
            visibility = View.GONE
        }
        root.addView(
            transformCanvas,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        progress = ProgressBar(context).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
        }
        root.addView(
            progress,
            FrameLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
        )

        val navPad = AndroidUtilities.navigationBarHeight
        val barPadH = LayoutHelper.dp(20f)
        val barPadV = LayoutHelper.dp(14f)
        val iconTouch = LayoutHelper.dp(44f)
        val iconVisual = LayoutHelper.dp(22f)

        fun toolbarIconButton(icon: MezonIcon, rotationDeg: Float, onClick: () -> Unit): FrameLayout {
            val wrap = FrameLayout(context).apply {
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
                contentDescription = when (icon) {
                    MezonIcon.arrowLargeLeftIcon ->
                        when (rotationDeg) {
                            0f -> context.getString(R.string.image_crop_rotate_left_cd)
                            180f -> context.getString(R.string.image_crop_rotate_right_cd)
                            else -> icon.name
                        }
                    MezonIcon.reloadIcon -> context.getString(R.string.image_crop_reset_cd)
                    else -> icon.name
                }
            }
            val iv = ImageView(context).apply {
                setImageDrawable(icon.getDrawable(context, Color.WHITE))
                rotation = rotationDeg
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            wrap.addView(
                iv,
                FrameLayout.LayoutParams(iconVisual, iconVisual, Gravity.CENTER)
            )
            wrap.layoutParams = LinearLayout.LayoutParams(iconTouch, iconTouch).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            return wrap
        }

        val bottomBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(barPadH, barPadV, barPadH, barPadV + navPad)
        }
        cropBottomBar = bottomBar

        val cancel = TextView(context).apply {
            text = getString(R.string.common_cancel)
            textSize = 17f
            setTextColor(cropBarBlue)
            setOnClickListener { finishFragment() }
        }
        val leftCell = FrameLayout(context)
        leftCell.addView(
            cancel,
            FrameLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
            }
        )
        bottomBar.addView(leftCell, LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f))

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        actions.addView(toolbarIconButton(MezonIcon.arrowLargeLeftIcon, 0f) {
            transformCanvas.rotateByDegrees(-90f)
        })
        val gap = LayoutHelper.dp(20f)
        actions.addView(View(context), LinearLayout.LayoutParams(gap, 1))
        actions.addView(toolbarIconButton(MezonIcon.reloadIcon, 0f) {
            transformCanvas.resetToInitial()
        })
        actions.addView(View(context), LinearLayout.LayoutParams(gap, 1))
        actions.addView(toolbarIconButton(MezonIcon.arrowLargeLeftIcon, 180f) {
            transformCanvas.rotateByDegrees(90f)
        })

        val midCell = FrameLayout(context)
        midCell.addView(
            actions,
            FrameLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER)
        )
        bottomBar.addView(midCell, LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 2f))

        val choose = TextView(context).apply {
            text = getString(R.string.image_crop_choose)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(cropBarGold)
            setOnClickListener { onSaveClicked() }
        }
        val rightCell = FrameLayout(context)
        rightCell.addView(
            choose,
            FrameLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
        )
        bottomBar.addView(rightCell, LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f))

        root.addView(
            bottomBar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
            }
        )

        uploadOverlay = FrameLayout(context).apply {
            visibility = View.GONE
            isClickable = true
            setBackgroundColor(0x88000000.toInt())
            val pb = ProgressBar(context).apply {
                isIndeterminate = true
                indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
            }
            addView(
                pb,
                FrameLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER)
            )
        }
        root.addView(
            uploadOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        fragmentView = root

        val ep = entryPoint()
        val hostCtx = context
        fragmentScope.launch(ep.ioDispatcher()) {
            val maxSrc = maxSourceBytes()
            if (maxSrc < Long.MAX_VALUE) {
                val estimatedSize = runCatching {
                    hostCtx.contentResolver.openFileDescriptor(imageUri, "r")?.use { it.statSize } ?: 0L
                }.getOrDefault(0L)
                if (estimatedSize > maxSrc) {
                    withContext(ep.mainDispatcher()) {
                        onSourceTooLarge()
                    }
                    return@launch
                }
            }

            val bmp = decodeBitmap(hostCtx, imageUri)
            withContext(ep.mainDispatcher()) {
                progress.visibility = View.GONE
                if (bmp == null) {
                    onDecodeFailed()
                    return@withContext
                }
                loadedBitmap = bmp
                transformCanvas.visibility = View.VISIBLE
                transformCanvas.setImageBitmap(bmp)
            }
        }

        return root
    }

    override fun onFragmentDestroy() {
        if (::transformCanvas.isInitialized) {
            transformCanvas.setImageBitmap(null)
        }
        loadedBitmap?.recycle()
        loadedBitmap = null
        super.onFragmentDestroy()
    }

    protected fun setUploadBlocking(active: Boolean) {
        if (!::uploadOverlay.isInitialized || !::cropBottomBar.isInitialized) return
        uploadOverlay.visibility = if (active) View.VISIBLE else View.GONE
        cropBottomBar.alpha = if (active) 0.35f else 1f
    }

    protected open fun onSaveClicked() {
        if (isWorking || !::transformCanvas.isInitialized || transformCanvas.visibility != View.VISIBLE) return
        val cropped = transformCanvas.renderCropped(exportWidthPx(), exportHeightPx()) ?: return
        isWorking = true
        setUploadBlocking(true)
        val ep = entryPoint()
        fragmentScope.launch(ep.ioDispatcher()) {
            val file = writeJpegUnderCap(cropped, maxExportBytes())
            cropped.recycle()
            if (file == null) {
                withContext(ep.mainDispatcher()) {
                    isWorking = false
                    setUploadBlocking(false)
                    onExportTooLarge()
                }
                return@launch
            }
            withContext(ep.mainDispatcher()) {
                onExportReady(file) {
                    isWorking = false
                    setUploadBlocking(false)
                }
            }
        }
    }

    protected fun decodeBitmap(context: Context, uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val maxSide = 4096
        var sample = 1
        while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) {
            sample *= 2
        }

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
        }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    protected open fun writeJpegUnderCap(bitmap: Bitmap, maxBytes: Int): File? {
        val ctx = getContext() ?: return null
        val file = File(ctx.cacheDir, "${cacheFilePrefix()}_${System.currentTimeMillis()}.jpg")
        var quality = EXPORT_JPEG_QUALITY_START
        while (quality >= EXPORT_JPEG_QUALITY_FLOOR) {
            FileOutputStream(file).use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            }
            if (file.length() in 1..maxBytes) return file
            quality -= EXPORT_JPEG_QUALITY_STEP
        }
        file.delete()
        return null
    }
}
