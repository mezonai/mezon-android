package com.mezon.mobile.home.messages

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.Choreographer
import android.view.View
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.home.chat.ChatMessageCell
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.home.chat.ShimmerEffect
import com.mezon.mobile.util.EmbedAnimationSpec
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

private data class CellAnimator(
    val frameKeys: List<String>,
    val repeatCount: Int?,
    var frameIndex: Int = 0,
    var startedAtNs: Long = 0L,
    var finished: Boolean = false,
)

internal data class EmbedAnimationFrameState(
    val frameIndex: Int,
    val finished: Boolean,
    val nextBoundaryDelayNs: Long?,
)

internal fun resolveEmbedAnimationFrameState(
    elapsedNs: Long,
    durationNs: Long,
    frameCount: Int,
    repeatCount: Int?,
): EmbedAnimationFrameState {
    if (frameCount <= 1) return EmbedAnimationFrameState(0, finished = true, nextBoundaryDelayNs = null)
    val safeDurationNs = durationNs.coerceAtLeast(1L)
    val safeElapsedNs = elapsedNs.coerceAtLeast(0L)
    val finiteRepeat = repeatCount?.takeIf { it > 0 }
    val completedIterations = safeElapsedNs / safeDurationNs
    if (finiteRepeat != null && completedIterations >= finiteRepeat) {
        return EmbedAnimationFrameState(frameCount - 1, finished = true, nextBoundaryDelayNs = null)
    }

    val iterationNs = safeElapsedNs % safeDurationNs
    val frameIndex = ((iterationNs.toDouble() / safeDurationNs.toDouble()) * frameCount)
        .toInt()
        .coerceIn(0, frameCount - 1)
    val nextBoundaryNs = ceil(
        (frameIndex + 1).toDouble() * safeDurationNs.toDouble() / frameCount.toDouble(),
    ).toLong()
    val delayNs = (nextBoundaryNs - iterationNs).coerceAtLeast(1L)
    return EmbedAnimationFrameState(frameIndex, finished = false, nextBoundaryDelayNs = delayNs)
}

internal class EmbedAnimationRuntime(
    private val parent: View,
    private val spec: EmbedAnimationSpec,
    private val httpClient: OkHttpClient = EmbedAnimationHttp.client(),
) {
    private data class AtlasFrame(val x: Int, val y: Int, val w: Int, val h: Int)
    private data class CellLayout(val dstW: Float, val dstH: Float, val frameKey: String)

    private var jsonCall: Call? = null
    private var bitmapLoad: MezonImageLoader.Cancellable? = null
    @Volatile private var loadFailed: Boolean = false
    @Volatile private var disposed: Boolean = false

    private val framesByKey = mutableMapOf<String, AtlasFrame>()

    private var atlasMetaW = 1
    private var atlasMetaH = 1
    @Volatile private var atlas: Bitmap? = null

    private var memoContentW = -1
    private var memoLayouts: List<CellLayout>? = null

    private var cellAnimators: List<CellAnimator> = emptyList()
    private var tickerRunning = false

    var onAnimationFinished: (() -> Unit)? = null
    var onLayoutMetricsChanged: (() -> Unit)? = null

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val drawSrcRect = Rect()
    private val drawDstRectF = RectF()

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!tickerRunning) return
            if (!isParentVisible()) {
                stopTicker()
                return
            }
            val advance = advanceAnimations(frameTimeNanos)
            if (advance.frameChanged) invalidateIfAlive()
            if (!shouldAnimate()) {
                stopTicker()
                notifyAnimationFinished()
                return
            }
            val delayMs = advance.nextBoundaryDelayNs
                ?.let { ceil(it.toDouble() / 1_000_000.0).toLong() }
                ?.coerceAtLeast(1L)
                ?: 1L
            Choreographer.getInstance().postFrameCallbackDelayed(this, delayMs)
        }
    }

    fun isAnimating(): Boolean = shouldAnimate()

    fun isNonTerminating(): Boolean =
        !spec.isStaticResult && (spec.repeat == null || spec.repeat <= 0)

    fun dispose() {
        disposed = true
        onAnimationFinished = null
        onLayoutMetricsChanged = null
        stopTicker()
        jsonCall?.cancel()
        jsonCall = null
        bitmapLoad?.cancel()
        bitmapLoad = null
        atlas = null
        memoLayouts = null
        memoContentW = -1
        cellAnimators = emptyList()
        loadFailed = false
        synchronized(framesByKey) {
            framesByKey.clear()
        }
    }

    fun onAttachedToWindow() {
        if (disposed) return
        ensureTickerRunning()
        invalidateIfAlive()
    }

    fun onDetachedFromWindow() {
        stopTicker()
    }

    fun placeholderHeightPx(): Int {
        val count = max(1, spec.pool.size)
        return if (spec.vertical) {
            count * PLACEHOLDER_H + (count + 1) * CELL_GAP_Y
        } else {
            MIN_BLOCK_ROW_H + CELL_GAP_Y * 2
        }
    }

    private fun invalidateLayouts() {
        memoContentW = -1
        memoLayouts = null
    }

    private fun prototypeFrame(): AtlasFrame? {
        synchronized(framesByKey) {
            if (framesByKey.isEmpty()) return null
            for (lane in spec.pool) {
                for (k in frameKeysToProbe(lane)) {
                    framesByKey[k]?.let { return it }
                }
            }
            return framesByKey.values.firstOrNull()
        }
    }

    private fun frameKeysToProbe(lane: List<String>): List<String> {
        if (lane.isEmpty()) return emptyList()
        return when {
            spec.isStaticResult -> listOfNotNull(lane.firstOrNull()?.trim(), lane.lastOrNull()?.trim())
            else -> listOfNotNull(lane.firstOrNull()?.trim())
        }.filter { it.isNotEmpty() && it != "null" }.distinct()
    }

    private fun frameKeyForDraw(lane: List<String>): String {
        val first = lane.firstOrNull()?.trim().orEmpty()
        val last = lane.lastOrNull()?.trim().orEmpty()
        return when {
            spec.isStaticResult -> last.ifEmpty { first }
            else -> first.ifEmpty { last }
        }
    }

    private fun computeLayouts(contentWidthPx: Int): List<CellLayout>? {
        if (contentWidthPx <= 0 || spec.pool.isEmpty()) return null

        synchronized(framesByKey) {
            if (framesByKey.isEmpty()) return null
            return spec.pool.map { lane ->
                val firstFrame = lane.firstNotNullOfOrNull { framesByKey[it.trim()] }
                    ?: prototypeFrame()
                    ?: return null
                val widthItem = firstFrame.w.coerceAtLeast(1)
                val heightItem = firstFrame.h.coerceAtLeast(1)
                val denominator = if (spec.isStaticResult) {
                    widthItem
                } else {
                    min(widthItem, heightItem).coerceAtLeast(1)
                }
                val ratio = BOX_SMALL_PX.toFloat() / denominator
                CellLayout(
                    dstW = widthItem * ratio,
                    dstH = heightItem * ratio,
                    frameKey = frameKeyForDraw(lane),
                )
            }
        }
    }

    private fun layoutsFor(contentWidthPx: Int): List<CellLayout>? {
        if (memoContentW == contentWidthPx && memoLayouts != null) return memoLayouts
        val computed = computeLayouts(contentWidthPx) ?: return null
        memoLayouts = computed
        memoContentW = contentWidthPx
        return memoLayouts
    }

    fun blockHeightPx(contentWidthPx: Int): Int {
        val lays = layoutsFor(contentWidthPx) ?: return placeholderHeightPx()
        val scale = layoutScale(lays, contentWidthPx)
        val contentH = if (spec.vertical) {
            lays.sumOf { ceil((it.dstH * scale).toDouble()).toInt() } +
                CELL_GAP_Y * max(0, lays.size - 1)
        } else {
            ceil((lays.maxOfOrNull { it.dstH } ?: BOX_SMALL_PX.toFloat()) * scale)
                .toInt()
                .coerceAtLeast(MIN_BLOCK_ROW_H)
        }
        return contentH + CELL_GAP_Y * 2
    }

    private fun layoutScale(cells: List<CellLayout>, contentWidthPx: Int): Float {
        if (contentWidthPx <= 0 || cells.isEmpty()) return 1f
        val desiredWidth = if (spec.vertical) {
            cells.maxOf { it.dstW }
        } else {
            cells.sumOf { ceil(it.dstW.toDouble()).toInt() }.toFloat() +
                CELL_GAP_X.toFloat() * max(0, cells.size - 1)
        }
        return if (desiredWidth > contentWidthPx) {
            contentWidthPx / desiredWidth.coerceAtLeast(1f)
        } else {
            1f
        }
    }

    fun startLoading(context: android.content.Context) {
        if (disposed || loadFailed) return
        synchronized(framesByKey) {
            if (framesByKey.isNotEmpty()) return
        }
        if (jsonCall != null) return
        val urlJson = spec.urlPosition.trim().ifEmpty {
            loadFailed = true
            return
        }
        val req = Request.Builder().url(urlJson).build()
        val call = httpClient.newCall(req)
        jsonCall = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (disposed || call != jsonCall) return
                jsonCall = null
                loadFailed = true
                parent.post {
                    if (!disposed) invalidateIfAlive()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    if (disposed || call != jsonCall) return
                    jsonCall = null
                    val ok = ingestAtlasBody(response.body?.string())
                    if (disposed) return
                    if (!ok) loadFailed = true
                    parent.post {
                        if (disposed) return@post
                        invalidateIfAlive()
                        if (ok) {
                            invalidateLayouts()
                            startBitmapLoad(context.applicationContext)
                        }
                    }
                } finally {
                    response.close()
                }
            }
        })
    }

    private fun ingestAtlasBody(bodyStr: String?): Boolean {
        if (disposed || bodyStr.isNullOrBlank()) return false
        return try {
            val obj = JSONObject(bodyStr)
            val meta = obj.optJSONObject("meta") ?: return false
            val size = meta.optJSONObject("size") ?: return false
            val mw = size.optDouble("w", size.optDouble("width", 0.0)).toInt().coerceAtLeast(1)
            val mh = size.optDouble("h", size.optDouble("height", 0.0)).toInt().coerceAtLeast(1)
            synchronized(framesByKey) {
                if (disposed) return false
                atlasMetaW = mw
                atlasMetaH = mh
                framesByKey.clear()
                val fo = obj.optJSONObject("frames") ?: return false
                val it = fo.keys()
                while (it.hasNext()) {
                    val key = it.next()
                    val node = fo.optJSONObject(key) ?: continue
                    val fr = node.optJSONObject("frame") ?: continue
                    val x = fr.optDouble("x", 0.0).toInt().coerceAtLeast(0)
                    val y = fr.optDouble("y", 0.0).toInt().coerceAtLeast(0)
                    val w = fr.optDouble("w", fr.optDouble("width", 0.0)).toInt().coerceAtLeast(1)
                    val h = fr.optDouble("h", fr.optDouble("height", 0.0)).toInt().coerceAtLeast(1)
                    framesByKey[key] = AtlasFrame(x, y, w, h)
                }
                if (framesByKey.isEmpty()) return false
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun startBitmapLoad(appContext: android.content.Context) {
        if (disposed) return
        val mw: Int
        val mh: Int
        synchronized(framesByKey) {
            mw = atlasMetaW
            mh = atlasMetaH
        }
        val reqW = min(mw, 4096).coerceAtLeast(1)
        val reqH = min(mh, 4096).coerceAtLeast(1)
        val urlBmp = spec.urlImage.trim().ifEmpty {
            loadFailed = true
            return
        }
        bitmapLoad?.cancel()
        bitmapLoad = MezonImageLoader.getInstance(appContext).load(
            url = urlBmp,
            reqWidth = reqW,
            reqHeight = reqH,
            onSuccess = { bmp ->
                if (disposed) return@load
                synchronized(framesByKey) {
                    if (disposed) return@synchronized
                    if (!bmp.isRecycled) atlas = bmp
                }
                if (disposed || atlas == null) return@load
                initAnimators()
                invalidateLayouts()
                parent.post {
                    if (disposed) return@post
                    invalidateIfAlive()
                    onLayoutMetricsChanged?.invoke() ?: parent.requestLayout()
                }
            },
            onError = {
                if (disposed) return@load
                loadFailed = true
                parent.post {
                    if (!disposed) invalidateIfAlive()
                }
            },
        )
    }

    private fun initAnimators() {
        val now = System.nanoTime()
        val finiteRepeat = spec.repeat?.takeIf { it > 0 }
        cellAnimators = synchronized(framesByKey) {
            spec.pool.map { lane ->
                val validKeys = lane.map { it.trim() }
                    .filter { it.isNotEmpty() && framesByKey.containsKey(it) }
                val fallbackKey = framesByKey.keys.firstOrNull().orEmpty()
                val keys = validKeys.ifEmpty { listOf(fallbackKey) }.filter { it.isNotEmpty() }
                CellAnimator(
                    frameKeys = keys,
                    repeatCount = finiteRepeat,
                    frameIndex = if (spec.isStaticResult) max(0, keys.lastIndex) else 0,
                    startedAtNs = now,
                    finished = spec.isStaticResult || keys.size <= 1,
                )
            }
        }
        ensureTickerRunning()
    }

    private data class AnimationAdvance(
        val frameChanged: Boolean,
        val nextBoundaryDelayNs: Long?,
    )

    private fun advanceAnimations(frameTimeNanos: Long): AnimationAdvance {
        val durationNs = (spec.durationSec.toDouble() * 1_000_000_000.0)
            .toLong()
            .coerceAtLeast(1L)
        var frameChanged = false
        var nextBoundaryDelayNs: Long? = null
        for (anim in cellAnimators) {
            if (anim.finished || anim.frameKeys.size <= 1) continue
            val elapsedNs = (frameTimeNanos - anim.startedAtNs).coerceAtLeast(0L)
            val state = resolveEmbedAnimationFrameState(
                elapsedNs = elapsedNs,
                durationNs = durationNs,
                frameCount = anim.frameKeys.size,
                repeatCount = anim.repeatCount,
            )
            if (anim.frameIndex != state.frameIndex) {
                anim.frameIndex = state.frameIndex
                frameChanged = true
            }
            anim.finished = state.finished
            val laneDelayNs = state.nextBoundaryDelayNs
            if (laneDelayNs != null && (nextBoundaryDelayNs == null || laneDelayNs < nextBoundaryDelayNs)) {
                nextBoundaryDelayNs = laneDelayNs
            }
        }
        return AnimationAdvance(frameChanged, nextBoundaryDelayNs)
    }

    private fun shouldAnimate(): Boolean {
        if (spec.isStaticResult || atlas == null) return false
        return cellAnimators.any { !it.finished && it.frameKeys.size > 1 }
    }

    private fun isParentVisible(): Boolean =
        parent.isAttachedToWindow && (parent as? ChatMessageCell)?.visibleOnScreen != false

    private fun ensureTickerRunning() {
        if (tickerRunning || !shouldAnimate()) return
        if (!isParentVisible()) return
        tickerRunning = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopTicker() {
        if (!tickerRunning) return
        tickerRunning = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    private fun notifyAnimationFinished() {
        val callback = onAnimationFinished
        onAnimationFinished = null
        callback?.invoke()
    }

    private fun invalidateIfAlive() {
        if (parent.isAttachedToWindow) parent.invalidate()
    }

    private fun drawSpriteFrame(
        canvas: Canvas,
        bmp: Bitmap,
        left: Float,
        top: Float,
        dw: Float,
        dh: Float,
        frameKey: String,
    ) {
        val fr = synchronized(framesByKey) { framesByKey[frameKey] } ?: prototypeFrame() ?: return
        val iw = bmp.width
        val ih = bmp.height
        val sourceScaleX = iw.toFloat() / atlasMetaW.coerceAtLeast(1)
        val sourceScaleY = ih.toFloat() / atlasMetaH.coerceAtLeast(1)
        val sx = (fr.x * sourceScaleX).toInt().coerceIn(0, max(0, iw - 1))
        val sy = (fr.y * sourceScaleY).toInt().coerceIn(0, max(0, ih - 1))
        val right = ceil((fr.x + fr.w) * sourceScaleX).toInt().coerceIn(sx + 1, iw)
        val bottom = ceil((fr.y + fr.h) * sourceScaleY).toInt().coerceIn(sy + 1, ih)
        drawSrcRect.set(sx, sy, right, bottom)
        drawDstRectF.set(left, top, left + dw, top + dh)
        canvas.drawBitmap(bmp, drawSrcRect, drawDstRectF, bmpPaint)
    }

    fun draw(
        canvas: Canvas,
        originX: Float,
        originY: Float,
        contentWidthPx: Int,
        shimmer: ShimmerEffect,
        themeDarkEmbed: Boolean,
    ) {
        val cells = layoutsFor(contentWidthPx)
        val bmp = atlas?.takeIf { !it.isRecycled }

        if (cells == null || bmp == null) {
            drawPlaceholders(canvas, originX, originY, contentWidthPx, shimmer, themeDarkEmbed)
            return
        }

        if (shouldAnimate()) ensureTickerRunning()

        val scale = layoutScale(cells, contentWidthPx)
        val horizontalGap = CELL_GAP_X * scale
        var xCursor = originX
        var yCursor = originY + CELL_GAP_Y
        val rowScaledH = if (spec.vertical) {
            0
        } else {
            cells.maxOf { ceil((it.dstH * scale).toDouble()).toInt() }
                .coerceAtLeast(MIN_SCALED_ROW_H)
        }

        cells.forEachIndexed { index, cell ->
            val dw = cell.dstW * scale
            val dh = cell.dstH * scale
            val left: Float
            val top: Float
            if (spec.vertical) {
                left = originX
                top = yCursor
            } else {
                left = xCursor
                top = yCursor + (rowScaledH - dh) / 2f
            }

            val anim = cellAnimators.getOrNull(index)
            val frameKey = anim?.frameKeys?.getOrNull(anim.frameIndex) ?: cell.frameKey
            drawSpriteFrame(canvas, bmp, left, top, dw, dh, frameKey)

            if (spec.vertical) {
                yCursor += dh + CELL_GAP_Y
            } else {
                xCursor += dw + horizontalGap
            }
        }
    }

    private fun drawPlaceholders(
        canvas: Canvas,
        originX: Float,
        originY: Float,
        contentWidthPx: Int,
        shimmer: ShimmerEffect,
        themeDarkEmbed: Boolean,
    ) {
        if (!loadFailed && isParentVisible()) {
            parent.postInvalidateDelayed(32)
        }

        val n = max(1, spec.pool.size)
        val content = contentWidthPx.coerceAtLeast(1)
        if (spec.vertical) {
            val cw = min(content, BOX_SMALL_PX).toFloat()
            repeat(n) { i ->
                val top = originY + CELL_GAP_Y + i * (PLACEHOLDER_H + CELL_GAP_Y)
                shimmer.draw(
                    canvas,
                    originX,
                    top,
                    originX + cw,
                    top + PLACEHOLDER_H,
                    PLACEHOLDER_RADIUS,
                    themeDarkEmbed,
                )
            }
        } else {
            val top = originY + CELL_GAP_Y
            shimmer.draw(
                canvas,
                originX,
                top,
                originX + content,
                top + MIN_BLOCK_ROW_H,
                PLACEHOLDER_RADIUS,
                themeDarkEmbed,
            )
        }
    }

    companion object {
        private val CELL_GAP_X = LayoutHelper.dp(6f)
        private val CELL_GAP_Y = LayoutHelper.dp(8f)
        private val BOX_SMALL_PX = LayoutHelper.dp(80f)
        private val MIN_BLOCK_ROW_H = LayoutHelper.dp(40)
        private val MIN_SCALED_ROW_H = LayoutHelper.dp(36)
        private val PLACEHOLDER_H = LayoutHelper.dp(120f)
        private val PLACEHOLDER_RADIUS = LayoutHelper.dp(8).toFloat()
    }
}

internal fun EmbedAnimationSpec.estimatedPlaceholderHeightPx(): Int =
    if (vertical) {
        val count = max(1, pool.size)
        count * LayoutHelper.dp(120f) + (count + 1) * LayoutHelper.dp(8f)
    } else {
        LayoutHelper.dp(40f) + LayoutHelper.dp(16f)
    }
