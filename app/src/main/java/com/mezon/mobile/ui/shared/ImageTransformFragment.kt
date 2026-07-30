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
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
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
import kotlin.math.hypot
import kotlin.math.max

class TransformCanvasView(
    context: Context,
    private val outsideColor: Int = Color.BLACK,
    private val cropChromeColor: Int = Color.WHITE,
    private val outsideDimColor: Int = outsideColor,
) : FrameLayout(context) {



    private var bitmap: Bitmap? = null

    private val imageMatrix = Matrix()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = outsideDimColor }

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
    private val initialCropRect = RectF()
    private var rotationCropFitScale = 1f

    private var cropAspectW = 1f
    private var cropAspectH = 1f
    private var cropInsetLeft = LayoutHelper.dpf(24f)
    private var cropInsetTop = LayoutHelper.dpf(24f)
    private var cropInsetRight = LayoutHelper.dpf(24f)
    private var cropInsetBottom = LayoutHelper.dpf(24f)
    private var freeformCropEnabled = false
    private var drawingMode = false
    private var eraserMode = false
    private var textMode = false
    private var onEditorChanged: (() -> Unit)? = null

    private data class DrawStroke(
        val path: Path,
        val color: Int,
        val widthInBitmapPx: Float,
    )

    private data class TextOverlay(
        var text: String,
        var color: Int,
        var centerX: Float,
        var centerY: Float,
        var textSizeInViewPx: Float,
    )

    private enum class CropTouchTarget {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT,
        LEFT,
        TOP,
        RIGHT,
        BOTTOM,
        PAN_IMAGE,
        IMAGE,
        NONE,
    }

    private val strokes = ArrayList<DrawStroke>()
    private var currentStroke: DrawStroke? = null
    private var brushColor = Color.RED
    private var brushWidthViewPx = LayoutHelper.dpf(5f)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val inverseImageMatrix = Matrix()
    private val mappedTouchPoint = FloatArray(2)
    private var lastBitmapTouchX = 0f
    private var lastBitmapTouchY = 0f
    private val strokePathMeasure = PathMeasure()
    private val strokeMeasurePosition = FloatArray(2)

    private val textOverlays = ArrayList<TextOverlay>()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        typeface = Typeface.DEFAULT_BOLD
    }
    private val textOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        typeface = Typeface.DEFAULT_BOLD
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeJoin = Paint.Join.ROUND
    }
    private val textBounds = RectF()
    private var selectedTextIndex = -1
    private var textTouchIndex = -1
    private var textDragOffsetX = 0f
    private var textDragOffsetY = 0f
    private var textTouchDownX = 0f
    private var textTouchDownY = 0f
    private var textPointerDown = false
    private var textTouchMoved = false
    private val textTouchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var onTextEditRequested: ((index: Int, text: String, color: Int) -> Unit)? = null
    private var onTextSelected: ((color: Int) -> Unit)? = null
    private var onTextDeleteDrag: ((active: Boolean, x: Float, y: Float) -> Boolean)? = null

    private val initialMatrix = Matrix()

    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var dragMode = false
    private var cropTouchTarget = CropTouchTarget.NONE

    companion object {
        private const val MAX_COVER_ITERATIONS = 28
        private const val MAX_ZOOM_ITERATIONS = 14
        private const val MAX_ZOOM_FACTOR = 8f
    }

    private val imageContainerView = object : View(context) {
        override fun onDraw(canvas: Canvas) {
            val bmp = bitmap ?: return
            canvas.save()
            canvas.concat(imageMatrix)
            canvas.drawBitmap(bmp, 0f, 0f, bitmapPaint)
            canvas.restore()

            canvas.save()
            canvas.clipRect(cropRect)
            canvas.concat(imageMatrix)
            fun draw(stroke: DrawStroke) {
                strokePaint.color = stroke.color
                strokePaint.strokeWidth = stroke.widthInBitmapPx
                canvas.drawPath(stroke.path, strokePaint)
            }
            strokes.forEach(::draw)
            currentStroke?.let(::draw)
            canvas.restore()
        }
    }

    private val textContainerView = object : View(context) {
        override fun onDraw(canvas: Canvas) {
            drawTextOverlays(canvas, transform = null, clipToCrop = true)
        }
    }

    private val cropMaskView = object : View(context) {
        init {
            dimPaint.style = Paint.Style.FILL
        }
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            
            // Draw 4 rectangles around the cropRect to create a dimming mask
            canvas.drawRect(0f, 0f, w, cropRect.top, dimPaint) // Top
            canvas.drawRect(0f, cropRect.bottom, w, h, dimPaint) // Bottom
            canvas.drawRect(0f, cropRect.top, cropRect.left, cropRect.bottom, dimPaint) // Left
            canvas.drawRect(cropRect.right, cropRect.top, w, cropRect.bottom, dimPaint) // Right

            canvas.drawRect(cropRect, framePaint)
            if (!drawingMode && !textMode) drawCornerBrackets(canvas)
        }
    }

    init {
        setBackgroundColor(outsideColor)
        setWillNotDraw(false)
        addView(imageContainerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(textContainerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(cropMaskView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun invalidate() {
        super.invalidate()
        imageContainerView.invalidate()
        textContainerView.invalidate()
        cropMaskView.invalidate()
    }

    fun setFreeformCropEnabled(enabled: Boolean) {
        if (freeformCropEnabled == enabled) return
        freeformCropEnabled = enabled
        if (measuredWidth > 0 && measuredHeight > 0) {
            layoutCropRect(measuredWidth, measuredHeight)
            bitmap?.let { resetTransform() }
        }
        invalidate()
    }

    fun setCropInsetsDp(left: Float, top: Float, right: Float, bottom: Float) {
        cropInsetLeft = LayoutHelper.dpf(left.coerceAtLeast(0f))
        cropInsetTop = LayoutHelper.dpf(top.coerceAtLeast(0f))
        cropInsetRight = LayoutHelper.dpf(right.coerceAtLeast(0f))
        cropInsetBottom = LayoutHelper.dpf(bottom.coerceAtLeast(0f))
        if (measuredWidth > 0 && measuredHeight > 0) {
            layoutCropRect(measuredWidth, measuredHeight)
            bitmap?.let { resetTransform() }
        }
        invalidate()
    }

    fun setOnEditorChangedListener(listener: (() -> Unit)?) {
        onEditorChanged = listener
    }

    fun setOnTextEditRequestedListener(listener: ((index: Int, text: String, color: Int) -> Unit)?) {
        onTextEditRequested = listener
    }

    fun setOnTextSelectedListener(listener: ((color: Int) -> Unit)?) {
        onTextSelected = listener
    }

    fun setOnTextDeleteDragListener(listener: ((active: Boolean, x: Float, y: Float) -> Boolean)?) {
        onTextDeleteDrag = listener
    }

    fun setDrawingMode(enabled: Boolean) {
        if ((enabled && drawingMode) || (!enabled && !drawingMode && !textMode)) return
        if (textMode) cancelTextInteraction()
        commitCurrentStroke()
        drawingMode = enabled
        if (!enabled) eraserMode = false
        textMode = false
        cropTouchTarget = CropTouchTarget.NONE
        dragMode = false
        invalidate()
    }

    fun isDrawingMode(): Boolean = drawingMode

    fun setEraserMode(enabled: Boolean) {
        commitCurrentStroke()
        eraserMode = enabled && drawingMode
        invalidate()
    }

    fun isEraserMode(): Boolean = eraserMode

    fun setTextMode(enabled: Boolean) {
        if ((enabled && textMode) || (!enabled && !textMode && !drawingMode)) return
        cancelTextInteraction()
        commitCurrentStroke()
        textMode = enabled
        drawingMode = false
        eraserMode = false
        cropTouchTarget = CropTouchTarget.NONE
        dragMode = false
        invalidate()
    }

    fun isTextMode(): Boolean = textMode

    fun setBrushColor(color: Int) {
        brushColor = color
    }

    fun setBrushWidthDp(widthDp: Float) {
        brushWidthViewPx = LayoutHelper.dpf(widthDp.coerceAtLeast(1f))
    }

    fun addText(text: String, color: Int): Boolean {
        val content = text.trim()
        if (content.isEmpty()) return false
        textOverlays.add(
            TextOverlay(
                text = content,
                color = color,
                centerX = cropRect.centerX(),
                centerY = cropRect.centerY(),
                textSizeInViewPx = LayoutHelper.dpf(32f),
            ),
        )
        selectedTextIndex = textOverlays.lastIndex
        invalidate()
        onEditorChanged?.invoke()
        return true
    }

    fun updateText(index: Int, text: String, color: Int): Boolean {
        val overlay = textOverlays.getOrNull(index) ?: return false
        val content = text.trim()
        if (content.isEmpty()) {
            removeTextAt(index)
            return true
        } else {
            overlay.text = content
            overlay.color = color
            selectedTextIndex = index
        }
        invalidate()
        onEditorChanged?.invoke()
        return true
    }

    fun setSelectedTextColor(color: Int) {
        val overlay = textOverlays.getOrNull(selectedTextIndex) ?: return
        if (overlay.color == color) return
        overlay.color = color
        postInvalidateOnAnimation()
        onEditorChanged?.invoke()
    }

    private fun clearEdits() {
        cancelTextInteraction()
        currentStroke = null
        strokes.clear()
        textOverlays.clear()
        selectedTextIndex = -1
        textTouchIndex = -1
    }

    fun setImageBitmap(b: Bitmap?) {
        bitmap = b
        if (width > 0 && height > 0 && b != null) {
            layoutCropRect(width, height)
            resetTransform()
        }
        invalidate()
    }

    fun resetToInitial() {
        if (!initialCropRect.isEmpty) cropRect.set(initialCropRect)
        rotationCropFitScale = 1f
        imageMatrix.set(initialMatrix)
        invalidate()
    }

    fun resetEditor() {
        if (!initialCropRect.isEmpty) cropRect.set(initialCropRect)
        bitmap?.let { resetTransform() }
        clearEdits()
        invalidate()
    }

    fun rotateByDegrees(delta: Float) {
        val pivotX = cropRect.centerX()
        val pivotY = cropRect.centerY()
        imageMatrix.postRotate(delta, pivotX, pivotY)
        if (freeformCropEnabled && kotlin.math.abs((delta / 90f).toInt()) % 2 == 1) {
            rotateFreeformCropRect(pivotX, pivotY)
        }
        clampAfterTransform()
        invalidate()
        onEditorChanged?.invoke()
    }

    private fun rotateFreeformCropRect(pivotX: Float, pivotY: Float) {
        val allowedLeft = cropInsetLeft
        val allowedTop = cropInsetTop
        val allowedRight = width - cropInsetRight
        val allowedBottom = height - cropInsetBottom
        val allowedWidth = (allowedRight - allowedLeft).coerceAtLeast(1f)
        val allowedHeight = (allowedBottom - allowedTop).coerceAtLeast(1f)
        val currentFit = rotationCropFitScale.coerceAtLeast(1e-4f)
        val unscaledRotatedWidth = cropRect.height() / currentFit
        val unscaledRotatedHeight = cropRect.width() / currentFit
        val newFit = minOf(
            1f,
            allowedWidth / unscaledRotatedWidth,
            allowedHeight / unscaledRotatedHeight,
        )
        val scaleChange = newFit / currentFit
        val newWidth = unscaledRotatedWidth * newFit
        val newHeight = unscaledRotatedHeight * newFit
        val newCenterX = pivotX.coerceIn(allowedLeft + newWidth / 2f, allowedRight - newWidth / 2f)
        val newCenterY = pivotY.coerceIn(allowedTop + newHeight / 2f, allowedBottom - newHeight / 2f)

        imageMatrix.postScale(scaleChange, scaleChange, pivotX, pivotY)
        imageMatrix.postTranslate(newCenterX - pivotX, newCenterY - pivotY)
        cropRect.set(
            newCenterX - newWidth / 2f,
            newCenterY - newHeight / 2f,
            newCenterX + newWidth / 2f,
            newCenterY + newHeight / 2f,
        )
        rotationCropFitScale = newFit
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
        val maxW = (w - cropInsetLeft - cropInsetRight).coerceAtLeast(LayoutHelper.dpf(120f))
        val maxH = (h - cropInsetTop - cropInsetBottom).coerceAtLeast(LayoutHelper.dpf(120f))
        if (freeformCropEnabled) {
            val bmp = bitmap
            if (bmp == null) {
                cropRect.set(cropInsetLeft, cropInsetTop, cropInsetLeft + maxW, cropInsetTop + maxH)
            } else {
                val aspect = bmp.width.toFloat() / bmp.height.coerceAtLeast(1)
                var cropW = maxW
                var cropH = cropW / aspect
                if (cropH > maxH) {
                    cropH = maxH
                    cropW = cropH * aspect
                }
                val left = cropInsetLeft + (maxW - cropW) / 2f
                val top = cropInsetTop + (maxH - cropH) / 2f
                cropRect.set(left, top, left + cropW, top + cropH)
            }
        } else {
            val aspect = cropAspectW / cropAspectH
            var cropW = maxW
            var cropH = cropW / aspect
            if (cropH > maxH) {
                cropH = maxH
                cropW = cropH * aspect
            }
            val left = cropInsetLeft + (maxW - cropW) / 2f
            val top = cropInsetTop + (maxH - cropH) / 2f
            cropRect.set(left, top, left + cropW, top + cropH)
        }
        initialCropRect.set(cropRect)
        rotationCropFitScale = 1f
    }

    private fun resetTransform() {
        val bmp = bitmap ?: return
        rotationCropFitScale = 1f
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



    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                if (drawingMode) {
                    dragMode = false
                    if (cropRect.contains(event.x, event.y)) {
                        if (eraserMode) eraseStrokesAt(event.x, event.y) else startStroke(event.x, event.y)
                    }
                } else if (textMode) {
                    dragMode = false
                    textTouchIndex = findTextOverlay(event.x, event.y)
                    textPointerDown = textTouchIndex >= 0
                    textTouchMoved = false
                    textTouchDownX = event.x
                    textTouchDownY = event.y
                    if (textPointerDown) {
                        selectedTextIndex = textTouchIndex
                        val overlay = textOverlays[textTouchIndex]
                        onTextSelected?.invoke(overlay.color)
                        textDragOffsetX = overlay.centerX - event.x
                        textDragOffsetY = overlay.centerY - event.y
                    }
                    invalidate()
                } else {
                    cropTouchTarget = if (freeformCropEnabled) {
                        findCropTouchTarget(event.x, event.y)
                    } else {
                        CropTouchTarget.IMAGE
                    }
                    dragMode = cropTouchTarget != CropTouchTarget.NONE
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                commitCurrentStroke()
                cropTouchTarget = CropTouchTarget.NONE
                dragMode = false
                cancelTextInteraction()
            }
            MotionEvent.ACTION_MOVE -> {
                if (textMode && textPointerDown && event.pointerCount == 1 && !scaleDetector.isInProgress) {
                    if (!textTouchMoved &&
                        hypot(event.x - textTouchDownX, event.y - textTouchDownY) > textTouchSlop
                    ) {
                        textTouchMoved = true
                    }
                    if (textTouchMoved) {
                        moveTouchedText(event.x, event.y)
                        onTextDeleteDrag?.invoke(true, event.x, event.y)
                    }
                } else if (drawingMode && event.pointerCount == 1 && !scaleDetector.isInProgress) {
                    if (eraserMode) eraseStrokesAt(event.x, event.y) else appendStrokePoint(event.x, event.y)
                } else if (dragMode && !scaleDetector.isInProgress && event.pointerCount == 1) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    if (freeformCropEnabled) {
                        when (cropTouchTarget) {
                            CropTouchTarget.IMAGE -> moveCropRect(dx, dy)
                            CropTouchTarget.PAN_IMAGE -> {
                                imageMatrix.postTranslate(dx, dy)
                                clampAfterTransform()
                                onEditorChanged?.invoke()
                            }
                            else -> resizeCropRect(cropTouchTarget, dx, dy)
                        }
                    } else {
                        imageMatrix.postTranslate(dx, dy)
                        clampAfterTransform()
                        onEditorChanged?.invoke()
                    }
                    lastTouchX = event.x
                    lastTouchY = event.y
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                commitCurrentStroke()
                cropTouchTarget = CropTouchTarget.NONE
                dragMode = false
                if (textMode && textPointerDown) {
                    if (textTouchMoved) {
                        val deleteText = onTextDeleteDrag?.invoke(true, event.x, event.y) == true
                        onTextDeleteDrag?.invoke(false, event.x, event.y)
                        if (deleteText) removeTextAt(textTouchIndex)
                    } else {
                        textOverlays.getOrNull(textTouchIndex)?.let { overlay ->
                            onTextEditRequested?.invoke(textTouchIndex, overlay.text, overlay.color)
                        }
                    }
                }
                resetTextTouchState()
            }
            MotionEvent.ACTION_CANCEL -> {
                commitCurrentStroke()
                cropTouchTarget = CropTouchTarget.NONE
                dragMode = false
                cancelTextInteraction()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun findCropTouchTarget(x: Float, y: Float): CropTouchTarget {
        val radius = LayoutHelper.dpf(28f)
        val nearLeft = kotlin.math.abs(x - cropRect.left) <= radius
        val nearRight = kotlin.math.abs(x - cropRect.right) <= radius
        val nearTop = kotlin.math.abs(y - cropRect.top) <= radius
        val nearBottom = kotlin.math.abs(y - cropRect.bottom) <= radius
        val inExpandedX = x in (cropRect.left - radius)..(cropRect.right + radius)
        val inExpandedY = y in (cropRect.top - radius)..(cropRect.bottom + radius)
        return when {
            nearLeft && nearTop -> CropTouchTarget.TOP_LEFT
            nearRight && nearTop -> CropTouchTarget.TOP_RIGHT
            nearLeft && nearBottom -> CropTouchTarget.BOTTOM_LEFT
            nearRight && nearBottom -> CropTouchTarget.BOTTOM_RIGHT
            nearLeft && inExpandedY -> CropTouchTarget.LEFT
            nearRight && inExpandedY -> CropTouchTarget.RIGHT
            nearTop && inExpandedX -> CropTouchTarget.TOP
            nearBottom && inExpandedX -> CropTouchTarget.BOTTOM
            cropRect.contains(x, y) -> CropTouchTarget.IMAGE
            canPanImage() && isPointOnImage(x, y) -> CropTouchTarget.PAN_IMAGE
            else -> CropTouchTarget.NONE
        }
    }

    private fun canPanImage(): Boolean {
        if (bitmap == null) return false
        val bounds = mapBitmapBounds()
        val tolerance = LayoutHelper.dpf(1f)
        return bounds.width() > cropRect.width() + tolerance ||
            bounds.height() > cropRect.height() + tolerance
    }

    private fun isPointOnImage(viewX: Float, viewY: Float): Boolean {
        val bmp = bitmap ?: return false
        if (!mapViewPointToBitmap(viewX, viewY)) return false
        return mappedTouchPoint[0] in 0f..bmp.width.toFloat() &&
            mappedTouchPoint[1] in 0f..bmp.height.toFloat()
    }

    private fun resizeCropRect(target: CropTouchTarget, dx: Float, dy: Float) {
        val minSize = LayoutHelper.dpf(80f)
        val imageBounds = mapBitmapBounds()
        val minLeft = maxOf(cropInsetLeft, imageBounds.left)
        val maxRight = minOf(width - cropInsetRight, imageBounds.right)
        val minTop = maxOf(cropInsetTop, imageBounds.top)
        val maxBottom = minOf(height - cropInsetBottom, imageBounds.bottom)
        when (target) {
            CropTouchTarget.TOP_LEFT -> {
                cropRect.left = (cropRect.left + dx).coerceIn(minLeft, cropRect.right - minSize)
                cropRect.top = (cropRect.top + dy).coerceIn(minTop, cropRect.bottom - minSize)
            }
            CropTouchTarget.TOP_RIGHT -> {
                cropRect.right = (cropRect.right + dx).coerceIn(cropRect.left + minSize, maxRight)
                cropRect.top = (cropRect.top + dy).coerceIn(minTop, cropRect.bottom - minSize)
            }
            CropTouchTarget.BOTTOM_LEFT -> {
                cropRect.left = (cropRect.left + dx).coerceIn(minLeft, cropRect.right - minSize)
                cropRect.bottom = (cropRect.bottom + dy).coerceIn(cropRect.top + minSize, maxBottom)
            }
            CropTouchTarget.BOTTOM_RIGHT -> {
                cropRect.right = (cropRect.right + dx).coerceIn(cropRect.left + minSize, maxRight)
                cropRect.bottom = (cropRect.bottom + dy).coerceIn(cropRect.top + minSize, maxBottom)
            }
            CropTouchTarget.LEFT ->
                cropRect.left = (cropRect.left + dx).coerceIn(minLeft, cropRect.right - minSize)
            CropTouchTarget.TOP ->
                cropRect.top = (cropRect.top + dy).coerceIn(minTop, cropRect.bottom - minSize)
            CropTouchTarget.RIGHT ->
                cropRect.right = (cropRect.right + dx).coerceIn(cropRect.left + minSize, maxRight)
            CropTouchTarget.BOTTOM ->
                cropRect.bottom = (cropRect.bottom + dy).coerceIn(cropRect.top + minSize, maxBottom)
            else -> return
        }
        rotationCropFitScale = 1f
        onEditorChanged?.invoke()
    }

    private fun moveCropRect(dx: Float, dy: Float) {
        val imageBounds = mapBitmapBounds()
        val minLeft = maxOf(cropInsetLeft, imageBounds.left)
        val maxRight = minOf(width - cropInsetRight, imageBounds.right)
        val minTop = maxOf(cropInsetTop, imageBounds.top)
        val maxBottom = minOf(height - cropInsetBottom, imageBounds.bottom)
        val moveX = dx.coerceIn(minLeft - cropRect.left, maxRight - cropRect.right)
        val moveY = dy.coerceIn(minTop - cropRect.top, maxBottom - cropRect.bottom)
        if (moveX == 0f && moveY == 0f) return
        cropRect.offset(moveX, moveY)
        onEditorChanged?.invoke()
    }

    private fun imageScale(): Float {
        val values = FloatArray(9)
        imageMatrix.getValues(values)
        return hypot(values[Matrix.MSCALE_X], values[Matrix.MSKEW_Y]).coerceAtLeast(1e-4f)
    }

    private fun mapViewPointToBitmap(x: Float, y: Float): Boolean {
        if (!imageMatrix.invert(inverseImageMatrix)) return false
        mappedTouchPoint[0] = x
        mappedTouchPoint[1] = y
        inverseImageMatrix.mapPoints(mappedTouchPoint)
        return true
    }

    private fun startStroke(viewX: Float, viewY: Float) {
        if (!mapViewPointToBitmap(viewX, viewY)) return
        val path = Path().apply { moveTo(mappedTouchPoint[0], mappedTouchPoint[1]) }
        lastBitmapTouchX = mappedTouchPoint[0]
        lastBitmapTouchY = mappedTouchPoint[1]
        currentStroke = DrawStroke(path, brushColor, brushWidthViewPx / imageScale())
        invalidate()
    }

    private fun appendStrokePoint(viewX: Float, viewY: Float) {
        val stroke = currentStroke ?: return
        if (!mapViewPointToBitmap(viewX, viewY)) return
        val x = mappedTouchPoint[0]
        val y = mappedTouchPoint[1]
        stroke.path.quadTo(lastBitmapTouchX, lastBitmapTouchY, (lastBitmapTouchX + x) / 2f, (lastBitmapTouchY + y) / 2f)
        lastBitmapTouchX = x
        lastBitmapTouchY = y
        invalidate()
    }

    private fun commitCurrentStroke() {
        val stroke = currentStroke ?: return
        stroke.path.lineTo(lastBitmapTouchX, lastBitmapTouchY)
        strokes.add(stroke)
        currentStroke = null
        invalidate()
        onEditorChanged?.invoke()
    }

    private fun eraseStrokesAt(viewX: Float, viewY: Float) {
        if (!cropRect.contains(viewX, viewY) || !mapViewPointToBitmap(viewX, viewY)) return
        val x = mappedTouchPoint[0]
        val y = mappedTouchPoint[1]
        val eraserRadius = LayoutHelper.dpf(14f) / imageScale()
        var removed = false
        for (index in strokes.lastIndex downTo 0) {
            if (strokeContainsPoint(strokes[index], x, y, eraserRadius)) {
                strokes.removeAt(index)
                removed = true
            }
        }
        if (!removed) return
        invalidate()
        onEditorChanged?.invoke()
    }

    private fun strokeContainsPoint(stroke: DrawStroke, x: Float, y: Float, eraserRadius: Float): Boolean {
        strokePathMeasure.setPath(stroke.path, false)
        val hitRadius = eraserRadius + stroke.widthInBitmapPx / 2f
        val sampleStep = (eraserRadius * 0.45f).coerceAtLeast(2f)
        do {
            val length = strokePathMeasure.length
            var distance = 0f
            while (distance <= length) {
                if (strokePathMeasure.getPosTan(distance, strokeMeasurePosition, null) &&
                    hypot(strokeMeasurePosition[0] - x, strokeMeasurePosition[1] - y) <= hitRadius
                ) {
                    return true
                }
                distance += sampleStep
            }
            if (length > 0f && strokePathMeasure.getPosTan(length, strokeMeasurePosition, null) &&
                hypot(strokeMeasurePosition[0] - x, strokeMeasurePosition[1] - y) <= hitRadius
            ) {
                return true
            }
        } while (strokePathMeasure.nextContour())
        return false
    }



    private fun drawTextOverlays(canvas: Canvas, transform: Matrix?, clipToCrop: Boolean) {
        if (textOverlays.isEmpty()) return
        canvas.save()
        if (clipToCrop) canvas.clipRect(cropRect)
        transform?.let(canvas::concat)
        textOverlays.forEach { overlay -> drawTextOverlay(canvas, overlay) }
        canvas.restore()
    }

    private fun createStaticLayout(overlay: TextOverlay): android.text.StaticLayout {
        textPaint.textSize = overlay.textSizeInViewPx
        val baseSize = LayoutHelper.dpf(32f)
        val scale = overlay.textSizeInViewPx / baseSize
        val viewWidth = if (width > 0) width else context.resources.displayMetrics.widthPixels
        val availableWidth = kotlin.math.max(1, ((viewWidth - LayoutHelper.dp(48f)) * scale).toInt())
        val textPaintForLayout = android.text.TextPaint(textPaint)
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            android.text.StaticLayout.Builder.obtain(overlay.text, 0, overlay.text.length, textPaintForLayout, availableWidth)
                .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
                .build()
        } else {
            @Suppress("DEPRECATION")
            android.text.StaticLayout(overlay.text, textPaintForLayout, availableWidth, android.text.Layout.Alignment.ALIGN_CENTER, 1f, 0f, false)
        }
    }

    private fun drawTextOverlay(canvas: Canvas, overlay: TextOverlay) {
        textPaint.textSize = overlay.textSizeInViewPx
        textPaint.color = overlay.color
        textPaint.style = Paint.Style.FILL

        val staticLayout = createStaticLayout(overlay)
        canvas.save()
        canvas.translate(overlay.centerX - staticLayout.width / 2f, overlay.centerY - staticLayout.height / 2f)
        staticLayout.draw(canvas)
        canvas.restore()
    }

    private fun textOverlayBoundsInView(overlay: TextOverlay, out: RectF) {
        val staticLayout = createStaticLayout(overlay)
        val w = staticLayout.width.toFloat()
        val h = staticLayout.height.toFloat()
        val padding = overlay.textSizeInViewPx * 0.35f
        out.set(
            overlay.centerX - w / 2f - padding,
            overlay.centerY - h / 2f - padding,
            overlay.centerX + w / 2f + padding,
            overlay.centerY + h / 2f + padding,
        )
    }

    private fun findTextOverlay(viewX: Float, viewY: Float): Int {
        for (index in textOverlays.indices.reversed()) {
            textOverlayBoundsInView(textOverlays[index], textBounds)
            if (textBounds.contains(viewX, viewY)) return index
        }
        return -1
    }

    private fun moveTouchedText(viewX: Float, viewY: Float) {
        val overlay = textOverlays.getOrNull(textTouchIndex) ?: return
        overlay.centerX = (viewX + textDragOffsetX).coerceIn(cropRect.left, cropRect.right)
        overlay.centerY = (viewY + textDragOffsetY).coerceIn(cropRect.top, cropRect.bottom)
        invalidate()
        onEditorChanged?.invoke()
    }

    private fun removeTextAt(index: Int): Boolean {
        if (index !in textOverlays.indices) return false
        textOverlays.removeAt(index)
        selectedTextIndex = when {
            selectedTextIndex == index -> -1
            selectedTextIndex > index -> selectedTextIndex - 1
            else -> selectedTextIndex
        }
        textTouchIndex = -1
        postInvalidateOnAnimation()
        onEditorChanged?.invoke()
        return true
    }

    private fun cancelTextInteraction() {
        if (textTouchMoved) onTextDeleteDrag?.invoke(false, 0f, 0f)
        resetTextTouchState()
    }

    private fun resetTextTouchState() {
        textPointerDown = false
        textTouchMoved = false
        textTouchIndex = -1
    }

    override fun onDetachedFromWindow() {
        cancelTextInteraction()
        super.onDetachedFromWindow()
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
        drawStrokesForExport(canvas, bmpToOutput)
        drawTextOverlays(canvas, viewToOutput, clipToCrop = false)
        return out
    }

    private fun drawStrokesForExport(canvas: Canvas, matrix: Matrix) {
        commitCurrentStroke()
        if (strokes.isEmpty()) return
        canvas.save()
        canvas.concat(matrix)
        strokes.forEach { stroke ->
            strokePaint.color = stroke.color
            strokePaint.strokeWidth = stroke.widthInBitmapPx
            canvas.drawPath(stroke.path, strokePaint)
        }
        canvas.restore()
    }

    fun suggestedOutputSize(maxEdge: Int): Pair<Int, Int> {
        val bmp = bitmap ?: return maxEdge to maxEdge
        val scale = imageScale()
        var outW = (cropRect.width() / scale).toInt().coerceAtLeast(1)
        var outH = (cropRect.height() / scale).toInt().coerceAtLeast(1)
        val sourceLimit = max(bmp.width, bmp.height).coerceAtMost(maxEdge)
        val currentMax = max(outW, outH)
        if (currentMax > sourceLimit) {
            val factor = sourceLimit.toFloat() / currentMax
            outW = (outW * factor).toInt().coerceAtLeast(1)
            outH = (outH * factor).toInt().coerceAtLeast(1)
        }
        return outW to outH
    }

    fun renderCroppedSquare(outSize: Int): Bitmap? = renderCropped(outSize, outSize)

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        private var zoomTextIndex = -1

        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            zoomTextIndex = -1
            if (textMode && textOverlays.isNotEmpty()) {
                var minDistance = Float.MAX_VALUE
                textOverlays.forEachIndexed { index, overlay ->
                    val dx = overlay.centerX - detector.focusX
                    val dy = overlay.centerY - detector.focusY
                    val dist = dx * dx + dy * dy
                    if (dist < minDistance) {
                        minDistance = dist
                        zoomTextIndex = index
                    }
                }
            }
            return super.onScaleBegin(detector)
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scaleFactor = detector.scaleFactor
            if (scaleFactor.isNaN() || scaleFactor.isInfinite()) return false
            if (textMode) {
                if (zoomTextIndex >= 0) {
                    val overlay = textOverlays[zoomTextIndex]
                    overlay.textSizeInViewPx = (overlay.textSizeInViewPx * scaleFactor)
                        .coerceIn(LayoutHelper.dpf(12f), LayoutHelper.dpf(120f))
                    textContainerView.invalidate()
                }
                return true
            }
            if (drawingMode) return true
            imageMatrix.postScale(scaleFactor, scaleFactor, detector.focusX, detector.focusY)
            clampAfterTransform()
            invalidate()
            onEditorChanged?.invoke()
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
