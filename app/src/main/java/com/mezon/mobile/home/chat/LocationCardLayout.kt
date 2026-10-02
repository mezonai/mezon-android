package com.mezon.mobile.home.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.mezon.mobile.core.AvatarDrawable
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.theme.ThemeMode
import com.mezon.mobile.util.LocationMessageData
import com.mezon.mobile.util.MAP_TILE_PX
import com.mezon.mobile.util.MapTile
import com.mezon.mobile.util.avatarImgproxyUrl
import com.mezon.mobile.util.mapTilePlan
import com.mezon.mobile.util.staticMapImage

class LocationCardLayout(private val context: Context) {

    var invalidateCallback: (() -> Unit)? = null

    private val avatarDrawable = AvatarDrawable()
    private var avatarCancellable: MezonImageLoader.Cancellable? = null
    private var currentAvatarUrl = ""

    private var mapCancellable: MezonImageLoader.Cancellable? = null
    private var currentMapUrl = ""
    private var mapBitmap: Bitmap? = null

    private var currentTilePlanKey = ""
    private val tiles = ArrayList<MapTile>()
    private val tileBitmaps = HashMap<String, Bitmap>()
    private val tileCancellables = ArrayList<MezonImageLoader.Cancellable>()

    private val cardRect = RectF()
    private val mapRect = RectF()
    private val tileDstRect = RectF()
    private val clipPath = Path()
    private var clipPathW = Float.NaN
    private var clipPathH = 0f
    private val mapMatrix = Matrix()
    private val avatarClipPath = Path()
    private var titleLayout: StaticLayout? = null

    var cardWidth = 0
        private set
    var blockHeight = 0
        private set

    private val cardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mapPlaceholderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = LayoutHelper.dpf(1f)
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val avatarRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val pinShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x4D000000 }
    private val haloFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val haloStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = LayoutHelper.dpf(1f)
    }
    private val placeholderGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var placeholderGlowKey = ""

    fun prepare(
        data: LocationMessageData,
        senderId: Long,
        senderUsername: String,
        senderAvatarUrl: String,
        title: String,
        theme: ThemeColors,
        width: Int
    ) {
        cardWidth = width.coerceAtLeast(LayoutHelper.dp(200))
        blockHeight = MAP_HEIGHT + INFO_BAR_HEIGHT
        cardRect.set(0f, 0f, cardWidth.toFloat(), blockHeight.toFloat())
        mapRect.set(0f, 0f, cardWidth.toFloat(), MAP_HEIGHT.toFloat())

        val dark = theme.resolvedMode != ThemeMode.LIGHT
        cardBgPaint.color = theme.secondaryLight
        mapPlaceholderPaint.color = theme.border
        gridPaint.color = if (dark) 0x14FFFFFF else 0x14000000
        CARD_BORDER_PAINT.color = theme.border
        haloFillPaint.color = (theme.blurple and 0x00FFFFFF) or 0x26000000
        haloStrokePaint.color = (theme.blurple and 0x00FFFFFF) or 0x66000000
        val glowKey = "$cardWidth/${theme.blurple}"
        if (glowKey != placeholderGlowKey) {
            placeholderGlowKey = glowKey
            placeholderGlowPaint.shader = RadialGradient(
                mapRect.centerX(), mapRect.centerY(), cardWidth * 0.45f,
                (theme.blurple and 0x00FFFFFF) or 0x2E000000, 0x00000000,
                Shader.TileMode.CLAMP
            )
        }

        TITLE_PAINT.typeface = Typeface.DEFAULT_BOLD
        TITLE_PAINT.textSize = LayoutHelper.sp(14f)
        TITLE_PAINT.color = theme.textStrong
        val titleW = (cardWidth - TITLE_INSET_H * 2).coerceAtLeast(1)
        titleLayout = StaticLayout.Builder.obtain(title, 0, title.length, TITLE_PAINT, titleW)
            .setMaxLines(1)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

        avatarDrawable.setInfo(senderId, senderUsername)
        loadAvatar(senderAvatarUrl)
        loadMap(data, dark)
    }

    fun draw(canvas: Canvas, left: Float, top: Float) {
        canvas.save()
        canvas.translate(left, top)
        if (clipPathW != cardRect.width() || clipPathH != cardRect.height()) {
            clipPathW = cardRect.width()
            clipPathH = cardRect.height()
            clipPath.reset()
            clipPath.addRoundRect(cardRect, CARD_RADIUS, CARD_RADIUS, Path.Direction.CW)
        }
        canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawRect(cardRect, cardBgPaint)
        drawMap(canvas)
        drawAvatarPin(canvas)
        titleLayout?.let {
            canvas.save()
            canvas.translate(TITLE_INSET_H.toFloat(), MAP_HEIGHT + (INFO_BAR_HEIGHT - it.height) / 2f)
            it.draw(canvas)
            canvas.restore()
        }
        canvas.restore()
        canvas.drawRoundRect(cardRect, CARD_RADIUS, CARD_RADIUS, CARD_BORDER_PAINT)
        canvas.restore()
    }

    private fun drawMap(canvas: Canvas) {
        val bmp = mapBitmap
        when {
            bmp != null && !bmp.isRecycled -> drawStaticMap(canvas, bmp)
            tiles.isNotEmpty() -> drawTiles(canvas)
            else -> drawPlaceholder(canvas)
        }
    }

    private fun drawPlaceholder(canvas: Canvas) {
        canvas.drawRect(mapRect, mapPlaceholderPaint)
        canvas.drawRect(mapRect, placeholderGlowPaint)
        val step = GRID_STEP.toFloat()
        var x = step
        while (x < mapRect.right) {
            canvas.drawLine(x, mapRect.top, x, mapRect.bottom, gridPaint)
            x += step
        }
        var y = step
        while (y < mapRect.bottom) {
            canvas.drawLine(mapRect.left, y, mapRect.right, y, gridPaint)
            y += step
        }
    }

    private fun drawStaticMap(canvas: Canvas, bmp: Bitmap) {
        val scale = maxOf(mapRect.width() / bmp.width, mapRect.height() / bmp.height)
        val dx = (mapRect.width() - bmp.width * scale) / 2f
        val dy = (mapRect.height() - bmp.height * scale) / 2f
        mapMatrix.reset()
        mapMatrix.setScale(scale, scale)
        mapMatrix.postTranslate(dx, dy)
        canvas.save()
        canvas.clipRect(mapRect)
        canvas.drawBitmap(bmp, mapMatrix, bitmapPaint)
        canvas.restore()
    }

    private fun drawTiles(canvas: Canvas) {
        canvas.drawRect(mapRect, mapPlaceholderPaint)
        canvas.save()
        canvas.clipRect(mapRect)
        for (tile in tiles) {
            val bmp = tileBitmaps[tile.url] ?: continue
            if (bmp.isRecycled) continue
            tileDstRect.set(tile.left, tile.top, tile.left + MAP_TILE_PX, tile.top + MAP_TILE_PX)
            canvas.drawBitmap(bmp, null, tileDstRect, bitmapPaint)
        }
        canvas.restore()
    }

    private fun drawAvatarPin(canvas: Canvas) {
        val cx = mapRect.centerX()
        val cy = mapRect.centerY()
        val outerR = AVATAR_SIZE / 2f
        canvas.drawCircle(cx, cy, HALO_RADIUS, haloFillPaint)
        canvas.drawCircle(cx, cy, HALO_RADIUS, haloStrokePaint)
        canvas.drawCircle(cx, cy + PIN_SHADOW_OFFSET, outerR + PIN_SHADOW_SPREAD, pinShadowPaint)
        canvas.drawCircle(cx, cy, outerR, avatarRingPaint)
        val r = outerR - AVATAR_RING
        avatarClipPath.reset()
        avatarClipPath.addCircle(cx, cy, r, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(avatarClipPath)
        avatarDrawable.setBounds(
            (cx - r).toInt(),
            (cy - r).toInt(),
            (cx + r).toInt(),
            (cy + r).toInt()
        )
        avatarDrawable.draw(canvas)
        canvas.restore()
    }

    fun hitTest(localX: Float, localY: Float): Boolean = cardRect.contains(localX, localY)

    private fun loadAvatar(url: String) {
        if (url == currentAvatarUrl && avatarDrawable.hasPhoto()) return
        currentAvatarUrl = url
        avatarCancellable?.cancel()
        avatarCancellable = null
        if (url.isEmpty()) {
            avatarDrawable.setPhoto(null)
            avatarDrawable.setDrawableByInfo(true)
            return
        }
        val proxyUrl = avatarImgproxyUrl(url, AVATAR_SIZE)
        val loader = MezonImageLoader.getInstance(context)
        val cached = loader.getBitmapFromMemory(proxyUrl, AVATAR_SIZE, AVATAR_SIZE)
        if (cached != null) {
            avatarDrawable.setPhoto(cached)
            avatarDrawable.setDrawableByInfo(true)
            return
        }
        avatarDrawable.setDrawableByInfo(false)
        avatarCancellable = loader.load(proxyUrl, AVATAR_SIZE, AVATAR_SIZE, onSuccess = { bmp ->
            avatarDrawable.setPhoto(bmp)
            avatarDrawable.setDrawableByInfo(true)
            invalidateCallback?.invoke()
        }, onError = {
            avatarDrawable.setDrawableByInfo(true)
            invalidateCallback?.invoke()
        })
    }

    private fun loadMap(data: LocationMessageData, dark: Boolean) {
        val staticImage = staticMapImage(data.latitude, data.longitude, cardWidth, MAP_HEIGHT, dark)
        if (staticImage != null) {
            clearTiles()
            bitmapPaint.colorFilter = if (dark && !staticImage.styledDark) DARK_MAP_FILTER else null
            loadStaticMap(staticImage.url)
            return
        }
        clearStaticMap()
        loadTiles(data, dark)
    }

    private fun loadStaticMap(url: String) {
        if (url == currentMapUrl && mapBitmap != null) return
        currentMapUrl = url
        mapCancellable?.cancel()
        mapCancellable = null
        mapBitmap = null
        val loader = MezonImageLoader.getInstance(context)
        val cached = loader.getBitmapFromMemory(url, cardWidth, MAP_HEIGHT)
        if (cached != null) {
            mapBitmap = cached
            return
        }
        mapCancellable = loader.load(url, cardWidth, MAP_HEIGHT, onSuccess = { bmp ->
            if (url == currentMapUrl) {
                mapBitmap = bmp
                invalidateCallback?.invoke()
            }
        })
    }

    private fun clearStaticMap() {
        mapCancellable?.cancel()
        mapCancellable = null
        currentMapUrl = ""
        mapBitmap = null
    }

    private fun loadTiles(data: LocationMessageData, dark: Boolean) {
        val plan = mapTilePlan(data.latitude, data.longitude, cardWidth, MAP_HEIGHT, dark)
        if (plan == null) {
            clearTiles()
            return
        }
        bitmapPaint.colorFilter = if (dark && !plan.styledDark) DARK_MAP_FILTER else null
        if (plan.key == currentTilePlanKey) return
        clearTiles()
        val planKey = plan.key
        currentTilePlanKey = planKey
        tiles.addAll(plan.tiles)
        val loader = MezonImageLoader.getInstance(context)
        for (tile in tiles) {
            val cached = loader.getBitmapFromMemory(tile.url, MAP_TILE_PX, MAP_TILE_PX)
            if (cached != null) {
                tileBitmaps[tile.url] = cached
                continue
            }
            tileCancellables.add(loader.load(tile.url, MAP_TILE_PX, MAP_TILE_PX, onSuccess = { bmp ->
                if (currentTilePlanKey == planKey) {
                    tileBitmaps[tile.url] = bmp
                    invalidateCallback?.invoke()
                }
            }))
        }
    }

    private fun clearTiles() {
        for (cancellable in tileCancellables) cancellable.cancel()
        tileCancellables.clear()
        tiles.clear()
        tileBitmaps.clear()
        currentTilePlanKey = ""
    }

    fun clear() {
        avatarCancellable?.cancel()
        avatarCancellable = null
        currentAvatarUrl = ""
        clearStaticMap()
        clearTiles()
        titleLayout = null
        avatarDrawable.setPhoto(null)
        avatarDrawable.setDrawableByInfo(true)
        cardWidth = 0
        blockHeight = 0
    }

    companion object {
        private val MAP_HEIGHT = LayoutHelper.dp(150)
        private val INFO_BAR_HEIGHT = LayoutHelper.dp(40)
        private val AVATAR_SIZE = LayoutHelper.dp(30)
        private val AVATAR_RING = LayoutHelper.dpf(2f)
        private val CARD_RADIUS = LayoutHelper.dpf(10f)
        private val TITLE_INSET_H = LayoutHelper.dp(10)
        private val GRID_STEP = LayoutHelper.dp(24)
        private val HALO_RADIUS = LayoutHelper.dpf(34f)
        private val PIN_SHADOW_OFFSET = LayoutHelper.dpf(1.5f)
        private val PIN_SHADOW_SPREAD = LayoutHelper.dpf(1.5f)
        private val DARK_MAP_FILTER: ColorMatrixColorFilter by lazy {
            val invert = ColorMatrix(
                floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            val hueRotate180 = ColorMatrix(
                floatArrayOf(
                    -0.574f, 1.430f, 0.144f, 0f, 0f,
                    0.426f, 0.430f, 0.144f, 0f, 0f,
                    0.426f, 1.430f, -0.856f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            val saturation = ColorMatrix().apply { setSaturation(0.85f) }
            val tone = ColorMatrix(
                floatArrayOf(
                    0.86f, 0f, 0f, 0f, 10f,
                    0f, 0.86f, 0f, 0f, 10f,
                    0f, 0f, 0.86f, 0f, 10f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            val matrix = ColorMatrix(invert)
            matrix.postConcat(hueRotate180)
            matrix.postConcat(saturation)
            matrix.postConcat(tone)
            ColorMatrixColorFilter(matrix)
        }
        private val CARD_BORDER_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = LayoutHelper.dp(1f).toFloat()
        }
        private val TITLE_PAINT = TextPaint(Paint.ANTI_ALIAS_FLAG)
    }
}
