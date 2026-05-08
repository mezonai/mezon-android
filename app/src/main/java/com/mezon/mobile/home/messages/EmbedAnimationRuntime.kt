package com.mezon.mobile.home.messages

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import com.mezon.mobile.core.LayoutHelper
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
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

internal class EmbedAnimationRuntime(
    private val parent: View,
    private val spec: EmbedAnimationSpec,
) {
    private data class AtlasFrame(val x: Int, val y: Int, val w: Int, val h: Int)

    private data class CellLayout(val dstW: Float, val dstH: Float, val frameKey: String)

    private var jsonCall: Call? = null
    private var bitmapLoad: MezonImageLoader.Cancellable? = null

    private val framesByKey = mutableMapOf<String, AtlasFrame>()
    private var atlasMetaW = 1
    private var atlasMetaH = 1
    @Volatile private var atlas: Bitmap? = null

    private var memoContentW = -1
    private var memoLayouts: List<CellLayout>? = null

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val drawSrcRect = Rect()
    private val drawDstRectF = RectF()

    fun dispose() {
        jsonCall?.cancel()
        jsonCall = null
        bitmapLoad?.cancel()
        bitmapLoad = null
        atlas = null
        memoLayouts = null
        memoContentW = -1
        synchronized(framesByKey) {
            framesByKey.clear()
        }
    }

    fun placeholderHeightPx(): Int =
        LayoutHelper.dp(133f) + cellGapYPx()

    private fun invalidateLayouts() {
        memoContentW = -1
        memoLayouts = null
    }

    private fun cellGapXPx(): Int = LayoutHelper.dp(6f)
    private fun cellGapYPx(): Int = LayoutHelper.dp(8f)

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
        val proto = prototypeFrame() ?: return null

        synchronized(framesByKey) {
            if (framesByKey.isEmpty()) return null
            val widthItem = proto.w.coerceAtLeast(1)
            val heightItem = proto.h.coerceAtLeast(1)

            val n = spec.pool.size
            val boxBigPx = LayoutHelper.dp(133f)
            val boxSmallPx = LayoutHelper.dp(80f)
            val gap = cellGapXPx()
            val wideEnough =
                contentWidthPx > boxBigPx * n + gap * max(0, n - 1)

            val boxPx = if (wideEnough) boxBigPx else boxSmallPx
            val denom = min(widthItem, heightItem).coerceAtLeast(1)
            val ratio = boxPx.toFloat() / denom

            val dstWbig = heightItem * ratio
            val dstHbig = widthItem * ratio

            return spec.pool.map { lane ->
                CellLayout(dstWbig, dstHbig, frameKeyForDraw(lane))
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
        val rowH =
            ceil(lays.maxOfOrNull { it.dstH }?.toDouble()
                ?: LayoutHelper.dp(133f).toDouble()).toInt()
                .coerceAtLeast(LayoutHelper.dp(40))
        return rowH + cellGapYPx() * 2
    }

    fun startLoading(context: android.content.Context) {
        synchronized(framesByKey) {
            if (framesByKey.isNotEmpty()) return
        }
        if (jsonCall != null) return
        val urlJson = spec.urlPosition.trim().ifEmpty { return }
        val req = Request.Builder().url(urlJson).build()
        val call = http.newCall(req)
        jsonCall = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call != jsonCall) return
                jsonCall = null
                parent.post { invalidateIfAlive() }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    if (call != jsonCall) return
                    jsonCall = null
                    val ok = ingestAtlasBody(response.body?.string())
                    parent.post {
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
        if (bodyStr.isNullOrBlank()) return false
        return try {
            val obj = JSONObject(bodyStr)
            val meta = obj.optJSONObject("meta") ?: return false
            val size = meta.optJSONObject("size") ?: return false
            val mw =
                size.optDouble("w", size.optDouble("width", 0.0)).toInt().coerceAtLeast(1)
            val mh =
                size.optDouble("h", size.optDouble("height", 0.0)).toInt().coerceAtLeast(1)
            synchronized(framesByKey) {
                atlasMetaW = mw
                atlasMetaH = mh
                framesByKey.clear()
                val fo = obj.optJSONObject("frames") ?: return false
                val it = fo.keys()
                while (it.hasNext()) {
                    val key = it.next()
                    val node = fo.optJSONObject(key) ?: continue
                    val fr = node.optJSONObject("frame") ?: continue
                    framesByKey[key] = AtlasFrame(
                        fr.optDouble("x", 0.0).toInt().coerceAtLeast(0),
                        fr.optDouble("y", 0.0).toInt().coerceAtLeast(0),
                        fr.optDouble("w", fr.optDouble("width", 0.0)).toInt().coerceAtLeast(1),
                        fr.optDouble("h", fr.optDouble("height", 0.0)).toInt().coerceAtLeast(1),
                    )
                }
                framesByKey.isNotEmpty()
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun startBitmapLoad(appContext: android.content.Context) {
        val mw: Int
        val mh: Int
        synchronized(framesByKey) {
            mw = atlasMetaW
            mh = atlasMetaH
        }
        val reqW = min(mw, 4096).coerceAtLeast(1)
        val reqH = min(mh, 4096).coerceAtLeast(1)
        val urlBmp = spec.urlImage.trim().ifEmpty { return }
        bitmapLoad?.cancel()
        bitmapLoad = MezonImageLoader.getInstance(appContext).load(
            url = urlBmp,
            reqWidth = reqW,
            reqHeight = reqH,
            onSuccess = { bmp ->
                synchronized(framesByKey) {
                    if (!bmp.isRecycled) atlas = bmp
                }
                invalidateLayouts()
                parent.post {
                    invalidateIfAlive()
                    parent.requestLayout()
                }
            },
            onError = {
                parent.post { invalidateIfAlive() }
            },
        )
    }

    private fun invalidateIfAlive() {
        if (parent.isAttachedToWindow) parent.invalidate()
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

        val rowWDesired =
            cells.sumOf { ceil(it.dstW.toDouble()).toInt() }.toFloat() +
                cellGapXPx().toFloat() * max(0, cells.size - 1)

        val scale =
            if (contentWidthPx > 0 && rowWDesired > contentWidthPx) {
                contentWidthPx / rowWDesired.coerceAtLeast(1f)
            } else {
                1f
            }

        val rowScaledH =
            cells.maxOf { ceil((it.dstH * scale).toDouble()).toInt() }.coerceAtLeast(
                LayoutHelper.dp(36),
            )

        val gap = cellGapXPx() * scale
        var xCursor = originX

        synchronized(framesByKey) {
            cells.forEach { cell ->
                val dw = cell.dstW * scale
                val dh = cell.dstH * scale
                val top = originY + (rowScaledH - dh) / 2f

                val fr = framesByKey[cell.frameKey] ?: prototypeFrame()
                if (fr != null) {
                    val iw = bmp.width
                    val ih = bmp.height
                    val sx = fr.x.coerceIn(0, max(0, iw - 1))
                    val sy = fr.y.coerceIn(0, max(0, ih - 1))
                    val sw = fr.w.coerceIn(1, max(1, iw - sx))
                    val sh = fr.h.coerceIn(1, max(1, ih - sy))
                    drawSrcRect.set(sx, sy, sx + sw, sy + sh)
                    drawDstRectF.set(xCursor, top, xCursor + dw, top + dh)
                    canvas.drawBitmap(bmp, drawSrcRect, drawDstRectF, bmpPaint)
                }
                xCursor += dw + gap
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
        parent.postInvalidateDelayed(32)

        val n = max(1, spec.pool.size)
        val gap = cellGapXPx().toFloat()
        val content = contentWidthPx.coerceAtLeast(1)
        val cw = ((content - gap * max(0, n - 1)) / n.toFloat()).coerceAtLeast(LayoutHelper.dp(48f).toFloat())

        repeat(n) { i ->
            val x = originX + i * (cw + gap).coerceAtLeast(4f)
            shimmer.draw(
                canvas,
                x,
                originY,
                x + cw,
                originY + LayoutHelper.dp(120f),
                LayoutHelper.dp(8).toFloat(),
                themeDarkEmbed,
            )
        }
    }

    companion object {
        private val http = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}

internal fun EmbedAnimationSpec.estimatedPlaceholderHeightPx(): Int =
    LayoutHelper.dp(133f) + LayoutHelper.dp(16)
