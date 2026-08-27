package com.mezon.mobile.home.messages

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.View
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.chat.ChatMessageCell
import com.mezon.mobile.home.chat.CodeFenceSpan
import com.mezon.mobile.home.chat.ImageReceiver
import com.mezon.mobile.home.chat.ShimmerEffect
import com.mezon.mobile.ui.theme.ThemeMode
import com.mezon.mobile.util.EmbedActionRow
import com.mezon.mobile.util.EmbedAnimationSpec
import com.mezon.mobile.util.EmbedButtonStyle
import com.mezon.mobile.util.EmbedData
import com.mezon.mobile.util.EmbedField
import com.mezon.mobile.util.EmbedFieldInteractive
import com.mezon.mobile.util.EmbedInputComponentSpec
import com.mezon.mobile.util.EmbedRadioOptionSpec
import com.mezon.mobile.util.EmbedRadioSpec
import com.mezon.mobile.util.EmbedSelectSpec
import com.mezon.mobile.util.createImgproxyUrl
import com.mezon.mobile.util.formatEmbedRichText
import com.mezon.mobile.util.parseEmbedPayload
import okhttp3.OkHttpClient
import kotlin.math.max
import kotlin.math.min

class EmbedButtonHit {
    val rect = RectF()
    var buttonId: String = ""
        private set
    var url: String? = null
        private set
    var disabled: Boolean = false
        private set
    val pressKey: String
        get() = buttonId.ifEmpty { url.orEmpty() }

    fun set(left: Float, top: Float, right: Float, bottom: Float, id: String, link: String?, disabledValue: Boolean) {
        rect.set(left, top, right, bottom)
        buttonId = id
        url = link
        disabled = disabledValue
    }
}

sealed class EmbedInteractiveGeometry {
    abstract val rect: RectF
    abstract var componentId: String

    class InputField : EmbedInteractiveGeometry() {
        override val rect = RectF()
        override var componentId: String = ""
        lateinit var input: EmbedInputComponentSpec

        fun set(left: Float, top: Float, right: Float, bottom: Float, id: String, spec: EmbedInputComponentSpec) {
            rect.set(left, top, right, bottom)
            componentId = id
            input = spec
        }
    }

    class SelectField : EmbedInteractiveGeometry() {
        override val rect = RectF()
        override var componentId: String = ""
        lateinit var input: EmbedSelectSpec
        var fieldName: String = ""

        fun set(left: Float, top: Float, right: Float, bottom: Float, id: String, spec: EmbedSelectSpec, name: String) {
            rect.set(left, top, right, bottom)
            componentId = id
            input = spec
            fieldName = name
        }
    }

    class RadioField : EmbedInteractiveGeometry() {
        override val rect = RectF()
        override var componentId: String = ""
        lateinit var input: EmbedRadioSpec

        fun set(left: Float, top: Float, right: Float, bottom: Float, id: String, spec: EmbedRadioSpec) {
            rect.set(left, top, right, bottom)
            componentId = id
            input = spec
        }
    }
}

private class EmbedCardImageBundle(private val parent: View) {
    val embedGallery = mutableListOf<ImageReceiver>()
    val thumbImage = ImageReceiver(parent)
    val authorIconImage = ImageReceiver(parent)
    val footerIconImage = ImageReceiver(parent)

    fun syncEmbedGalleryCount(want: Int) {
        while (embedGallery.size < want) {
            val ir = ImageReceiver(parent)
            embedGallery.add(ir)
            if (parent.isAttachedToWindow) ir.onAttachedToWindow()
        }
        while (embedGallery.size > want) {
            val ir = embedGallery.removeAt(embedGallery.lastIndex)
            ir.onDetachedFromWindow()
            ir.recycle()
        }
    }

    fun embedGalleryReceiver(i: Int): ImageReceiver {
        syncEmbedGalleryCount(i + 1)
        return embedGallery[i]
    }

    fun onAttachedToWindow() {
        for (ir in embedGallery) ir.onAttachedToWindow()
        thumbImage.onAttachedToWindow()
        authorIconImage.onAttachedToWindow()
        footerIconImage.onAttachedToWindow()
    }

    fun onDetachedFromWindow() {
        for (ir in embedGallery) ir.onDetachedFromWindow()
        thumbImage.onDetachedFromWindow()
        authorIconImage.onDetachedFromWindow()
        footerIconImage.onDetachedFromWindow()
    }

    fun recycle() {
        for (ir in embedGallery) ir.recycle()
        embedGallery.clear()
        thumbImage.recycle()
        authorIconImage.recycle()
        footerIconImage.recycle()
    }
}

private data class LaidOutEmbedCard(
    val data: EmbedData,
    val authorLayout: StaticLayout?,
    val titleLayout: StaticLayout?,
    val descLayout: StaticLayout?,
    val fieldLayouts: List<Pair<StaticLayout?, StaticLayout?>>,
    val footerLayout: StaticLayout?,
    val layoutLeftColumnW: Int,
    val embedImageCells: List<Pair<Int, Int>>,
    val animationSpecsInOrder: List<EmbedAnimationSpec>,
    val fieldDrawOrder: IntArray,
    val hasFooterIcon: Boolean,
)

class EmbedMessageRenderer(
    private val parent: View,
    private val theme: () -> ThemeColors,
    private val httpClient: OkHttpClient = EmbedAnimationHttp.client(),
) {
    var onAfterDraw: (() -> Unit)? = null
    var onLayoutsRebuilt: (() -> Unit)? = null
    private var embedSourceList: List<EmbedData> = emptyList()
    private var laidOutCards: List<LaidOutEmbedCard> = emptyList()
    private var actionRows: List<EmbedActionRow> = emptyList()
    private val cardImageBundles = mutableListOf<EmbedCardImageBundle>()
    private val embedCardHitRects = mutableListOf<RectF>()
    private var embedCardHitRectCount = 0

    val embedData: EmbedData? get() = laidOutCards.firstOrNull()?.data
    fun hasEmbedOrButtons(): Boolean = laidOutCards.isNotEmpty() || actionRows.isNotEmpty()

    private data class LaidOutButton(
        val relX: Float,
        val relY: Float,
        val width: Float,
        val height: Int,
        val buttonId: String,
        val url: String?,
        val disabled: Boolean,
        val labelLayout: StaticLayout,
        val style: EmbedButtonStyle,
    )

    private val buttonHits = mutableListOf<EmbedButtonHit>()
    private var buttonHitCount = 0
    private var laidOutButtons = emptyList<LaidOutButton>()
    private var buttonsBlockHeight = 0
    private var pressedButtonKey = ""

    fun setPressedButton(key: String?) {
        val next = key.orEmpty()
        if (pressedButtonKey == next) return
        pressedButtonKey = next
        parent.invalidate()
    }

    private val embedInteractiveGeometries = mutableListOf<EmbedInteractiveGeometry>()
    private var embedInteractiveGeometryCount = 0
    val lastEmbedInteractiveGeometries: List<EmbedInteractiveGeometry> get() = embedInteractiveGeometries

    private var animationRuntimeGrid: List<List<EmbedAnimationRuntime>> = emptyList()
    private var deferredRebuildTextWidth = 0
    private var deferredRebuildPending = false
    private var sourceRevision = 0L
    private var laidOutSourceRevision = -1L
    private var embedSourceContent = ""

    fun isEmbedAnimationRunning(): Boolean =
        animationRuntimeGrid.any { row -> row.any { it.isAnimating() } }

    fun scheduleRebuildLayoutsAfterAnimation(textWidth: Int, context: Context) {
        deferredRebuildTextWidth = textWidth
        val sourceChanged = sourceRevision != laidOutSourceRevision
        val hasNonTerminatingRuntime = animationRuntimeGrid.any { row ->
            row.any { it.isAnimating() && it.isNonTerminating() }
        }
        if (sourceChanged || hasNonTerminatingRuntime) {
            rebuildLayouts(textWidth, context)
            return
        }
        if (deferredRebuildPending) return
        if (!isEmbedAnimationRunning()) {
            rebuildLayouts(textWidth, context)
            return
        }
        deferredRebuildPending = true
        for (row in animationRuntimeGrid) {
            for (runtime in row) {
                if (!runtime.isAnimating()) continue
                runtime.onAnimationFinished = {
                    tryApplyDeferredRebuild(context)
                }
            }
        }
    }

    private fun tryApplyDeferredRebuild(context: Context) {
        if (!deferredRebuildPending || isEmbedAnimationRunning()) return
        deferredRebuildPending = false
        rebuildLayouts(deferredRebuildTextWidth, context)
    }

    private fun clearDeferredRebuild() {
        deferredRebuildPending = false
        deferredRebuildTextWidth = 0
        for (row in animationRuntimeGrid) {
            for (runtime in row) {
                runtime.onAnimationFinished = null
            }
        }
    }

    private val radioOptionHeightCache = HashMap<Long, Int>()

    fun containsTouch(x: Float, y: Float): Boolean {
        if (laidOutCards.isEmpty()) return false
        for (i in 0 until embedInteractiveGeometryCount) {
            val g = embedInteractiveGeometries[i]
            val r = g.rect
            if (x >= r.left && x <= r.right && y >= r.top && y <= r.bottom) return false
        }
        for (i in 0 until embedCardHitRectCount) {
            val r = embedCardHitRects[i]
            if (x >= r.left && x <= r.right && y >= r.top && y <= r.bottom) return true
        }
        return false
    }

    fun hitTestEmbedCardLink(x: Float, y: Float): String? {
        for (i in 0 until embedCardHitRectCount) {
            val r = embedCardHitRects[i]
            if (x >= r.left && x <= r.right && y >= r.top && y <= r.bottom) {
                val u = laidOutCards.getOrNull(i)?.data?.url ?: return null
                return u.takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    fun hitTestButton(x: Float, y: Float): EmbedButtonHit? {
        for (i in 0 until buttonHitCount) {
            val h = buttonHits[i]
            if (x >= h.rect.left && x <= h.rect.right && y >= h.rect.top && y <= h.rect.bottom) return h
        }
        return null
    }

    fun setDataFromContent(content: String): Boolean {
        if (!content.contains("\"embed\"") && !content.contains("\"components\"")) {
            clear()
            return false
        }
        val payload = parseEmbedPayload(content)
        if (payload.embeds.isEmpty() && payload.actionRows.isEmpty()) {
            clear()
            return false
        }
        if (embedSourceContent != content) {
            sourceRevision++
            embedSourceContent = content
            embedSourceList = payload.embeds
            actionRows = payload.actionRows
        }
        return true
    }

    fun discardInteractiveGeometry() {
        embedInteractiveGeometryCount = 0
        trimInteractiveGeometries()
    }

    @Deprecated(
        message = "Renamed to discardInteractiveGeometry()",
        replaceWith = ReplaceWith("discardInteractiveGeometry()"),
    )
    fun discardInputGeometry() {
        discardInteractiveGeometry()
    }

    @Deprecated(
        message = "Renamed to lastEmbedInteractiveGeometries; this list is input fields only.",
        replaceWith = ReplaceWith("lastEmbedInteractiveGeometries"),
    )
    val lastEmbedInputGeometries: List<EmbedInteractiveGeometry.InputField>
        get() = lastEmbedInteractiveGeometries.filterIsInstance<EmbedInteractiveGeometry.InputField>()

    fun clear() {
        pressedButtonKey = ""
        embedSourceList = emptyList()
        laidOutCards = emptyList()
        actionRows = emptyList()
        sourceRevision = 0L
        laidOutSourceRevision = -1L
        embedSourceContent = ""
        clearDeferredRebuild()
        disposeAnimationGrid()
        radioOptionHeightCache.clear()
        syncCardImageBundles(0)
        laidOutButtons = emptyList()
        buttonsBlockHeight = 0
        buttonHitCount = 0
        buttonHits.clear()
        embedInteractiveGeometryCount = 0
        embedInteractiveGeometries.clear()
        embedCardHitRectCount = 0
        embedCardHitRects.clear()
    }

    fun onAttachedToWindow() {
        for (b in cardImageBundles) b.onAttachedToWindow()
        for (row in animationRuntimeGrid) {
            for (runtime in row) runtime.onAttachedToWindow()
        }
    }

    fun onDetachedFromWindow() {
        for (b in cardImageBundles) b.onDetachedFromWindow()
        for (row in animationRuntimeGrid) {
            for (runtime in row) runtime.onDetachedFromWindow()
        }
    }

    private fun disposeAnimationGrid() {
        clearDeferredRebuild()
        for (row in animationRuntimeGrid) {
            for (r in row) r.dispose()
        }
        animationRuntimeGrid = emptyList()
    }

    private fun embedFieldDrawOrder(fields: List<EmbedField>): IntArray {
        if (fields.isEmpty()) return IntArray(0)
        val nonAnim = ArrayList<Int>(fields.size)
        val anim = ArrayList<Int>(4)
        for (i in fields.indices) {
            when (fields[i].interactive) {
                is EmbedFieldInteractive.Animation -> anim.add(i)
                else -> nonAnim.add(i)
            }
        }
        val out = IntArray(nonAnim.size + anim.size)
        var p = 0
        for (i in nonAnim) out[p++] = i
        for (i in anim) out[p++] = i
        return out
    }

    private fun resetDrawGeometry() {
        buttonHitCount = 0
        embedCardHitRectCount = 0
        embedInteractiveGeometryCount = 0
    }

    private fun trimInteractiveGeometries() {
        while (embedInteractiveGeometries.size > embedInteractiveGeometryCount) {
            embedInteractiveGeometries.removeAt(embedInteractiveGeometries.lastIndex)
        }
    }

    private fun nextCardHitRect(): RectF {
        val index = embedCardHitRectCount++
        if (index < embedCardHitRects.size) return embedCardHitRects[index]
        return RectF().also { embedCardHitRects.add(it) }
    }

    private fun nextButtonHit(): EmbedButtonHit {
        val index = buttonHitCount++
        if (index < buttonHits.size) return buttonHits[index]
        return EmbedButtonHit().also { buttonHits.add(it) }
    }

    private fun putInputGeometry(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        componentId: String,
        input: EmbedInputComponentSpec,
    ) {
        val index = embedInteractiveGeometryCount++
        val geom = embedInteractiveGeometries.getOrNull(index) as? EmbedInteractiveGeometry.InputField
            ?: EmbedInteractiveGeometry.InputField().also {
                if (index < embedInteractiveGeometries.size) {
                    embedInteractiveGeometries[index] = it
                } else {
                    embedInteractiveGeometries.add(it)
                }
            }
        geom.set(left, top, right, bottom, componentId, input)
    }

    private fun putSelectGeometry(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        componentId: String,
        input: EmbedSelectSpec,
        fieldName: String,
    ) {
        val index = embedInteractiveGeometryCount++
        val geom = embedInteractiveGeometries.getOrNull(index) as? EmbedInteractiveGeometry.SelectField
            ?: EmbedInteractiveGeometry.SelectField().also {
                if (index < embedInteractiveGeometries.size) {
                    embedInteractiveGeometries[index] = it
                } else {
                    embedInteractiveGeometries.add(it)
                }
            }
        geom.set(left, top, right, bottom, componentId, input, fieldName)
    }

    private fun putRadioGeometry(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        componentId: String,
        input: EmbedRadioSpec,
    ) {
        val index = embedInteractiveGeometryCount++
        val geom = embedInteractiveGeometries.getOrNull(index) as? EmbedInteractiveGeometry.RadioField
            ?: EmbedInteractiveGeometry.RadioField().also {
                if (index < embedInteractiveGeometries.size) {
                    embedInteractiveGeometries[index] = it
                } else {
                    embedInteractiveGeometries.add(it)
                }
            }
        geom.set(left, top, right, bottom, componentId, input)
    }

    private fun embedFieldShowsRequired(field: EmbedField): Boolean =
        when (val iv = field.interactive) {
            is EmbedFieldInteractive.Input -> iv.input.required
            is EmbedFieldInteractive.Select -> iv.input.minPick > 0
            else -> false
        }

    private fun embedFieldNameWithRequired(field: EmbedField, th: ThemeColors): CharSequence? {
        if (field.name.isEmpty()) return null
        val base = formatEmbedRichText(field.name, th)
        if (!embedFieldShowsRequired(field)) return base
        val out = SpannableStringBuilder(base)
        out.append(' ')
        val starStart = out.length
        out.append('*')
        out.setSpan(
            ForegroundColorSpan(th.error),
            starStart,
            out.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        return out
    }

    private fun syncCardImageBundles(want: Int) {
        while (cardImageBundles.size < want) {
            val b = EmbedCardImageBundle(parent)
            cardImageBundles.add(b)
            if (parent.isAttachedToWindow) b.onAttachedToWindow()
        }
        while (cardImageBundles.size > want) {
            val b = cardImageBundles.removeAt(cardImageBundles.lastIndex)
            b.onDetachedFromWindow()
            b.recycle()
        }
    }

    private fun buildLaidOutCard(
        d: EmbedData,
        textWidth: Int,
        context: Context,
        th: ThemeColors,
        imgs: EmbedCardImageBundle,
    ): LaidOutEmbedCard {
        val contentW = (textWidth - COLOR_BAR_W - PAD * 2).coerceAtLeast(1)
        val hasThumb = d.thumbnailUrl.isNotEmpty()
        val leftColumnW = if (hasThumb) (contentW - THUMB_SIZE - GAP).coerceAtLeast(1) else contentW
        val hasAuthorIcon = d.authorIconUrl.isNotEmpty()
        val authorTextW = if (hasAuthorIcon) (leftColumnW - AUTHOR_ICON - GAP).coerceAtLeast(1) else leftColumnW

        val authorLayout = if (d.authorName.isNotEmpty()) {
            val rich = formatEmbedRichText(d.authorName, th)
            CodeFenceSpan.buildRichStaticLayout(rich, AUTHOR_PAINT, authorTextW) {
                setMaxLines(3)
                setEllipsize(TextUtils.TruncateAt.END)
                setLineSpacing(LayoutHelper.dpf(2f), 1f)
            }
        } else null

        val titleLayout = if (d.title.isNotEmpty()) {
            val rich = formatEmbedRichText(d.title, th)
            CodeFenceSpan.buildRichStaticLayout(rich, TITLE_PAINT, leftColumnW) {
                setMaxLines(12)
                setEllipsize(TextUtils.TruncateAt.END)
                setLineSpacing(LayoutHelper.dpf(2f), 1f)
            }
        } else null

        val descLayout = if (d.description.isNotEmpty()) {
            val rich = formatEmbedRichText(d.description, th)
            CodeFenceSpan.buildRichStaticLayout(rich, DESC_PAINT, leftColumnW) {
                setMaxLines(32)
                setEllipsize(TextUtils.TruncateAt.END)
                setLineSpacing(LayoutHelper.dpf(2f), 1f)
            }
        } else null

        val fieldLayouts = d.fields.map { field ->
            val nameLay = embedFieldNameWithRequired(field, th)?.let { rich ->
                CodeFenceSpan.buildRichStaticLayout(rich, FIELD_NAME_PAINT, leftColumnW) {
                    setMaxLines(4)
                    setEllipsize(TextUtils.TruncateAt.END)
                    setLineSpacing(LayoutHelper.dpf(2f), 1f)
                }
            }
            val valLay = if (field.value.isNotEmpty()) {
                val rich = formatEmbedRichText(field.value, th)
                CodeFenceSpan.buildRichStaticLayout(rich, FIELD_VALUE_PAINT, leftColumnW) {
                    setMaxLines(16)
                    setEllipsize(TextUtils.TruncateAt.END)
                    setLineSpacing(LayoutHelper.dpf(2f), 1f)
                }
            } else null
            nameLay to valLay
        }

        val footerParts = mutableListOf<String>()
        if (d.footerText.isNotEmpty()) footerParts.add(d.footerText)
        if (d.timestamp.isNotEmpty()) {
            try {
                val parsed = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                    .parse(d.timestamp.replace("Z", "+0000").take(19))
                if (parsed != null) {
                    val fmt = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.US)
                    footerParts.add(fmt.format(parsed))
                }
            } catch (_: Exception) {}
        }
        val footerStr = footerParts.joinToString(" • ")
        val hasFooterIcon = d.footerIconUrl.isNotEmpty()
        val footerTextW = if (hasFooterIcon) {
            (contentW - FOOTER_ICON - GAP).coerceAtLeast(1)
        } else contentW
        val footerLayout = if (footerStr.isNotEmpty()) {
            val rich = formatEmbedRichText(footerStr, th)
            CodeFenceSpan.buildRichStaticLayout(rich, FOOTER_PAINT, footerTextW) {
                setMaxLines(3)
                setEllipsize(TextUtils.TruncateAt.END)
                setLineSpacing(LayoutHelper.dpf(2f), 1f)
            }
        } else null

        val animationSpecsInOrder = d.fields.mapNotNull { f ->
            (f.interactive as? EmbedFieldInteractive.Animation)?.input
        }

        val embedImageCells = mutableListOf<Pair<Int, Int>>()
        val gallery = d.images.filter { it.url.isNotBlank() }
        imgs.syncEmbedGalleryCount(gallery.size)
        if (gallery.isNotEmpty()) {
            val n = gallery.size
            val gapTotal = GAP * max(0, n - 1)
            val cellW = ((leftColumnW - gapTotal).coerceAtLeast(1)) / n
            var rowH = 0
            for (im in gallery) {
                val asp = if (im.width > 0 && im.height > 0) {
                    im.width.toFloat() / im.height.toFloat()
                } else {
                    16f / 9f
                }
                val ih = (cellW / asp).toInt().coerceIn(LayoutHelper.dp(48), LayoutHelper.dp(300))
                rowH = max(rowH, ih)
            }
            for (gi in gallery.indices) {
                embedImageCells.add(cellW to rowH)
                val imgRef = gallery[gi]
                val cw = embedImageCells[gi].first
                val rh = embedImageCells[gi].second
                val ir = imgs.embedGalleryReceiver(gi)
                ir.setRoundRadius(IMG_RADIUS.toInt())
                ir.setImage(
                    createImgproxyUrl(imgRef.url, cw, rh, "fit"),
                    null,
                    context,
                )
            }
        }

        if (hasThumb) {
            imgs.thumbImage.setRoundRadius(IMG_RADIUS.toInt())
            imgs.thumbImage.setImage(createImgproxyUrl(d.thumbnailUrl, THUMB_SIZE, THUMB_SIZE, "fit"), null, context)
        } else {
            imgs.thumbImage.setImage(null, null, context)
        }

        if (hasAuthorIcon) {
            imgs.authorIconImage.setRoundRadius(AUTHOR_ICON / 2)
            imgs.authorIconImage.setImage(
                createImgproxyUrl(d.authorIconUrl, AUTHOR_ICON, AUTHOR_ICON, "fit"),
                null,
                context,
            )
        } else {
            imgs.authorIconImage.setImage(null, null, context)
        }

        if (hasFooterIcon) {
            imgs.footerIconImage.setRoundRadius(FOOTER_ICON / 2)
            imgs.footerIconImage.setImage(
                createImgproxyUrl(d.footerIconUrl, FOOTER_ICON, FOOTER_ICON, "fit"),
                null,
                context,
            )
        } else {
            imgs.footerIconImage.setImage(null, null, context)
        }

        return LaidOutEmbedCard(
            data = d,
            authorLayout = authorLayout,
            titleLayout = titleLayout,
            descLayout = descLayout,
            fieldLayouts = fieldLayouts,
            footerLayout = footerLayout,
            layoutLeftColumnW = leftColumnW,
            embedImageCells = embedImageCells,
            animationSpecsInOrder = animationSpecsInOrder,
            fieldDrawOrder = embedFieldDrawOrder(d.fields),
            hasFooterIcon = hasFooterIcon,
        )
    }

    private fun applyPaints() {
        val t = theme()
        TITLE_PAINT.color = t.onSurface
        DESC_PAINT.color = t.onSurfaceVariant
        FIELD_NAME_PAINT.color = t.onSurface
        FIELD_VALUE_PAINT.color = t.onSurfaceVariant
        FOOTER_PAINT.color = t.onSurfaceVariant
        AUTHOR_PAINT.color = t.onSurface
    }

    fun rebuildLayouts(textWidth: Int, context: Context) {
        clearDeferredRebuild()
        disposeAnimationGrid()
        radioOptionHeightCache.clear()
        laidOutCards = emptyList()
        if (embedSourceList.isEmpty()) {
            syncCardImageBundles(0)
        } else {
            applyPaints()
            val th = theme()
            syncCardImageBundles(embedSourceList.size)
            laidOutCards = embedSourceList.mapIndexed { idx, d ->
                buildLaidOutCard(d, textWidth, context, th, cardImageBundles[idx])
            }
            rebuildAnimationGrid(context)
        }
        BUTTON_LABEL_PAINT.textSize = LayoutHelper.dpf(14f)
        BUTTON_LABEL_PAINT.color = 0xFFFFFFFF.toInt()
        rebuildButtonLayouts(textWidth)
        laidOutSourceRevision = sourceRevision
        onLayoutsRebuilt?.invoke()
    }

    private fun rebuildAnimationGrid(context: Context) {
        animationRuntimeGrid = laidOutCards.map { card ->
            card.animationSpecsInOrder.map { spec ->
                EmbedAnimationRuntime(parent, spec, httpClient).also { runtime ->
                    runtime.onLayoutMetricsChanged = {
                        onLayoutsRebuilt?.invoke()
                    }
                    runtime.startLoading(context)
                }
            }
        }
    }

    private fun rebuildButtonLayouts(textWidth: Int) {
        laidOutButtons = emptyList()
        buttonsBlockHeight = 0
        if (actionRows.isEmpty()) return
        val maxW = textWidth.toFloat().coerceAtLeast(1f)
        val gap = BUTTON_ROW_GAP.toFloat()
        val panelGap = ACTION_ROW_PANEL_GAP.toFloat()
        val placed = mutableListOf<LaidOutButton>()
        var globalY = 0f
        for ((rowIdx, row) in actionRows.withIndex()) {
            if (rowIdx > 0) globalY += panelGap
            var x = 0f
            var lineTop = globalY
            var lineMaxH = 0
            for (btn in row.buttons) {
                val innerW = (maxW - BUTTON_PAD_H * 2).toInt().coerceAtLeast(1)
                val rich = formatEmbedRichText(btn.label, theme())
                val labelLayout = CodeFenceSpan.buildRichStaticLayout(rich, BUTTON_LABEL_PAINT, innerW) {
                    setMaxLines(2)
                    setEllipsize(TextUtils.TruncateAt.END)
                    setLineSpacing(LayoutHelper.dpf(1f), 1f)
                    setIncludePad(false)
                }
                var textW = 0f
                for (li in 0 until labelLayout.lineCount) {
                    textW = max(textW, labelLayout.getLineWidth(li))
                }
                var bw = (BUTTON_PAD_H * 2 + textW).coerceAtLeast(BUTTON_MIN_W.toFloat()).coerceAtMost(maxW)
                if (x > 0f && x + bw > maxW + 0.5f) {
                    globalY += lineMaxH + gap
                    x = 0f
                    lineTop = globalY
                    lineMaxH = 0
                }
                val btnH = max(BUTTON_H, labelLayout.height + LayoutHelper.dp(12))
                placed.add(
                    LaidOutButton(
                        relX = x,
                        relY = lineTop,
                        width = bw,
                        height = btnH,
                        buttonId = btn.componentId,
                        url = btn.url,
                        disabled = btn.disabled,
                        labelLayout = labelLayout,
                        style = btn.style,
                    )
                )
                lineMaxH = max(lineMaxH, btnH)
                x += bw + gap
            }
            globalY = lineTop + lineMaxH
        }
        laidOutButtons = placed
        buttonsBlockHeight = globalY.toInt()
    }

    private fun computeCardHeight(cardIdx: Int, card: LaidOutEmbedCard): Int {
        applyPaints()
        val d = card.data
        var h = PAD * 2 + TOP_MARGIN
        if (d.authorIconUrl.isNotEmpty() || card.authorLayout != null) {
            val rowH = max(
                card.authorLayout?.height ?: 0,
                if (d.authorIconUrl.isNotEmpty()) AUTHOR_ICON else 0,
            )
            if (rowH > 0) h += rowH + GAP
        }
        card.titleLayout?.let { h += it.height + GAP }
        card.descLayout?.let { h += it.height + GAP }
        var animIxCount = 0
        for (orderIndex in card.fieldDrawOrder.indices) {
            val i = card.fieldDrawOrder[orderIndex]
            val layouts = card.fieldLayouts.getOrNull(i)
            val nameLay = layouts?.first
            val valLay = layouts?.second
            nameLay?.let { h += it.height + FIELD_NAME_GAP }
            valLay?.let { h += it.height + GAP }
            d.fields[i].interactive?.let { iv ->
                val blockH =
                    when (iv) {
                        is EmbedFieldInteractive.Animation -> {
                            val ix = animIxCount++
                            animationRuntimeGrid.getOrNull(cardIdx)?.getOrNull(ix)?.blockHeightPx(card.layoutLeftColumnW)
                                ?: iv.input.estimatedPlaceholderHeightPx()
                        }
                        else -> interactiveBlockHeight(iv, card.layoutLeftColumnW)
                    }
                h += blockH + GAP
            }
        }
        if (card.embedImageCells.isNotEmpty()) {
            h += card.embedImageCells.maxOf { it.second } + GAP
        }
        card.footerLayout?.let {
            val footerRow = max(it.height, if (card.hasFooterIcon) FOOTER_ICON else 0)
            h += footerRow + GAP
        }
        val thumbH = if (d.thumbnailUrl.isNotEmpty()) THUMB_SIZE + PAD else 0
        return max(h, thumbH + PAD * 2 + TOP_MARGIN)
    }

    fun computeHeight(): Int {
        var h = 0
        for (i in laidOutCards.indices) {
            if (i > 0) h += INTER_EMBED_CARD_GAP
            h += computeCardHeight(i, laidOutCards[i])
        }
        if (actionRows.isNotEmpty()) {
            if (laidOutCards.isNotEmpty()) h += ACTION_PANEL_TOP_GAP
            h += buttonsBlockHeight
        }
        return h
    }

    fun draw(canvas: Canvas, left: Float, top: Float, bubbleMaxW: Int, shimmer: ShimmerEffect): Float {
        applyPaints()
        resetDrawGeometry()
        var bottom = top
        for (i in laidOutCards.indices) {
            if (i > 0) bottom += INTER_EMBED_CARD_GAP
            bottom = drawEmbedCard(canvas, left, bottom, bubbleMaxW, shimmer, i, laidOutCards[i], cardImageBundles[i])
        }
        if (actionRows.isNotEmpty()) {
            if (laidOutCards.isNotEmpty()) bottom += ACTION_PANEL_TOP_GAP
            bottom = drawButtons(canvas, left, bottom)
        }
        trimInteractiveGeometries()
        onAfterDraw?.invoke()
        return bottom
    }

    private fun drawEmbedCard(
        canvas: Canvas,
        left: Float,
        top: Float,
        bubbleMaxW: Int,
        shimmer: ShimmerEffect,
        cardIdx: Int,
        card: LaidOutEmbedCard,
        imgs: EmbedCardImageBundle,
    ): Float {
        val d = card.data
        val cardTop = top + TOP_MARGIN
        val embedH = computeCardHeight(cardIdx, card) - TOP_MARGIN
        val contentW = (bubbleMaxW - COLOR_BAR_W - PAD * 2).coerceAtLeast(1)
        val cardW = (COLOR_BAR_W + PAD * 2 + contentW).toFloat()

        nextCardHitRect().set(left, cardTop, left + cardW, cardTop + embedH)

        BG_PAINT.color = theme().secondaryLight
        tmpRect.set(left, cardTop, left + cardW, cardTop + embedH)
        canvas.drawRoundRect(tmpRect, RADIUS, RADIUS, BG_PAINT)

        val barColor = if (d.color != 0) d.color else theme().primary
        BAR_PAINT.color = barColor
        tmpRect.set(left, cardTop, left + COLOR_BAR_W, cardTop + embedH)
        canvas.drawRoundRect(tmpRect, RADIUS / 2, RADIUS / 2, BAR_PAINT)

        val textLeft = left + COLOR_BAR_W + PAD
        var y = cardTop + PAD.toFloat()

        if (d.thumbnailUrl.isNotEmpty()) {
            val thumbX = left + cardW - PAD - THUMB_SIZE
            val thumbY = y
            imgs.thumbImage.setImageCoords(thumbX, thumbY, THUMB_SIZE.toFloat(), THUMB_SIZE.toFloat())
            imgs.thumbImage.draw(canvas)
        }

        val hasAuthorIcon = d.authorIconUrl.isNotEmpty()
        if (hasAuthorIcon || card.authorLayout != null) {
            val rowH = max(
                card.authorLayout?.height ?: 0,
                if (hasAuthorIcon) AUTHOR_ICON else 0,
            ).toFloat()
            if (rowH > 0) {
                val authorTextX = if (hasAuthorIcon) textLeft + AUTHOR_ICON + GAP else textLeft
                if (hasAuthorIcon) {
                    val iconY = y + (rowH - AUTHOR_ICON) / 2f
                    imgs.authorIconImage.setImageCoords(textLeft, iconY, AUTHOR_ICON.toFloat(), AUTHOR_ICON.toFloat())
                    imgs.authorIconImage.draw(canvas)
                }
                card.authorLayout?.let {
                    val textY = y + (rowH - it.height) / 2f
                    canvas.save()
                    canvas.translate(authorTextX, textY)
                    it.draw(canvas)
                    canvas.restore()
                }
                y += rowH + GAP
            }
        }

        card.titleLayout?.let {
            canvas.save()
            canvas.translate(textLeft, y)
            it.draw(canvas)
            canvas.restore()
            y += it.height + GAP
        }

        card.descLayout?.let {
            canvas.save()
            canvas.translate(textLeft, y)
            it.draw(canvas)
            canvas.restore()
            y += it.height + GAP
        }

        var animIxDraw = 0
        for (orderIndex in card.fieldDrawOrder.indices) {
            val i = card.fieldDrawOrder[orderIndex]
            val field = d.fields[i]
            val layouts = card.fieldLayouts.getOrNull(i)
            val nameLay = layouts?.first
            val valLay = layouts?.second
            nameLay?.let {
                canvas.save()
                canvas.translate(textLeft, y)
                it.draw(canvas)
                canvas.restore()
                y += it.height + FIELD_NAME_GAP
            }
            valLay?.let {
                canvas.save()
                canvas.translate(textLeft, y)
                it.draw(canvas)
                canvas.restore()
                y += it.height + GAP
            }
            field.interactive?.let { iv ->
                when (iv) {
                    is EmbedFieldInteractive.Animation -> {
                        val ix = animIxDraw++
                        val cwAnim = card.layoutLeftColumnW
                        val rt = animationRuntimeGrid.getOrNull(cardIdx)?.getOrNull(ix)
                        val bh = rt?.blockHeightPx(cwAnim) ?: iv.input.estimatedPlaceholderHeightPx()
                        rt?.draw(
                            canvas,
                            textLeft,
                            y,
                            cwAnim,
                            shimmer,
                            theme().resolvedMode != ThemeMode.LIGHT,
                        )
                        y += bh + GAP
                    }
                    else -> {
                        val blockH = interactiveBlockHeight(iv, card.layoutLeftColumnW).toFloat()
                        when (iv) {
                            is EmbedFieldInteractive.Input -> putInputGeometry(
                                textLeft,
                                y,
                                textLeft + card.layoutLeftColumnW,
                                y + blockH,
                                iv.componentId,
                                iv.input,
                            )
                            is EmbedFieldInteractive.Select -> putSelectGeometry(
                                textLeft,
                                y,
                                textLeft + card.layoutLeftColumnW,
                                y + blockH,
                                iv.componentId,
                                iv.input,
                                field.name,
                            )
                            is EmbedFieldInteractive.Radio -> putRadioGeometry(
                                textLeft,
                                y,
                                textLeft + card.layoutLeftColumnW,
                                y + blockH,
                                iv.componentId,
                                iv.input,
                            )
                            is EmbedFieldInteractive.Animation ->
                                throw IllegalStateException("embed animation should use the Animation branch")
                        }
                        y += blockH + GAP
                    }
                }
            }
        }

        if (card.embedImageCells.isNotEmpty()) {
            var xImg = textLeft
            val rowH = card.embedImageCells.maxOf { it.second }
            for ((ci, cell) in card.embedImageCells.withIndex()) {
                val (cw, ch) = cell
                val ir = imgs.embedGalleryReceiver(ci)
                ir.setRoundRadius(IMG_RADIUS.toInt())
                ir.setImageCoords(xImg, y, cw.toFloat(), ch.toFloat())
                ir.draw(canvas)
                if (!ir.hasMainImage()) {
                    shimmer.draw(
                        canvas, xImg, y, xImg + cw, y + ch,
                        IMG_RADIUS, theme().resolvedMode != ThemeMode.LIGHT,
                    )
                    if (parent.isAttachedToWindow && (parent as? ChatMessageCell)?.visibleOnScreen != false) {
                        parent.postInvalidateDelayed(32)
                    }
                }
                xImg += cw + GAP
            }
            y += rowH + GAP
        }

        card.footerLayout?.let { lay ->
            val rowH = max(lay.height, if (card.hasFooterIcon) FOOTER_ICON else 0)
            val baseY = y
            if (card.hasFooterIcon) {
                val iconY = baseY + (rowH - FOOTER_ICON) / 2f
                imgs.footerIconImage.setImageCoords(textLeft, iconY, FOOTER_ICON.toFloat(), FOOTER_ICON.toFloat())
                imgs.footerIconImage.draw(canvas)
            }
            val textX = if (card.hasFooterIcon) textLeft + FOOTER_ICON + GAP else textLeft
            val textY = baseY + (rowH - lay.height) / 2f
            canvas.save()
            canvas.translate(textX, textY)
            lay.draw(canvas)
            canvas.restore()
            y += rowH + GAP
        }

        return cardTop + embedH
    }

    private fun drawButtons(canvas: Canvas, left: Float, top: Float): Float {
        val t = theme()
        for (b in laidOutButtons) {
            val bg = buttonBackgroundColor(t, b.style)
            BUTTON_BG_PAINT.color = bg
            val l = left + b.relX
            val tt = top + b.relY
            val underline = b.url != null || b.style == EmbedButtonStyle.LINK
            val alpha = if (b.disabled) 130 else 255
            BUTTON_BG_PAINT.alpha = alpha
            BUTTON_LABEL_PAINT.isUnderlineText = underline
            BUTTON_LABEL_PAINT.alpha = alpha
            tmpRect.set(l, tt, l + b.width, tt + b.height)
            canvas.drawRoundRect(tmpRect, BUTTON_RADIUS, BUTTON_RADIUS, BUTTON_BG_PAINT)
            if (!b.disabled && pressedButtonKey.isNotEmpty() && pressedButtonKey == b.buttonId.ifEmpty { b.url.orEmpty() }) {
                BUTTON_RIPPLE_PAINT.color = if (t.resolvedMode == ThemeMode.LIGHT) {
                    0x26000000
                } else {
                    0x33FFFFFF
                }
                canvas.drawRoundRect(tmpRect, BUTTON_RADIUS, BUTTON_RADIUS, BUTTON_RIPPLE_PAINT)
            }
            val innerLeft = l + BUTTON_PAD_H
            val innerW = b.width - BUTTON_PAD_H * 2
            var contentW = 0f
            for (li in 0 until b.labelLayout.lineCount) {
                contentW = max(contentW, b.labelLayout.getLineWidth(li))
            }
            val textX = innerLeft + (innerW - min(contentW, innerW.toFloat())) / 2f
            val textY = tt + (b.height - b.labelLayout.height) / 2f
            canvas.save()
            canvas.translate(textX, textY)
            b.labelLayout.draw(canvas)
            canvas.restore()
            nextButtonHit().set(l, tt, l + b.width, tt + b.height, b.buttonId, b.url, b.disabled)
        }
        BUTTON_BG_PAINT.alpha = 255
        BUTTON_LABEL_PAINT.alpha = 255
        BUTTON_LABEL_PAINT.isUnderlineText = false
        return top + buttonsBlockHeight
    }

    private fun embedInputHeight(spec: EmbedInputComponentSpec): Int =
        if (spec.textarea) INPUT_TEXTAREA_MIN_H else INPUT_MIN_H

    private fun interactiveBlockHeight(iv: EmbedFieldInteractive, colW: Int): Int = when (iv) {
        is EmbedFieldInteractive.Input -> embedInputHeight(iv.input)
        is EmbedFieldInteractive.Select -> SELECT_ROW_H
        is EmbedFieldInteractive.Radio -> iv.input.options.sumOf { embedRadioOptionHeight(it, colW) }
        is EmbedFieldInteractive.Animation -> iv.input.estimatedPlaceholderHeightPx()
    }

    private fun embedRadioOptionHeight(o: EmbedRadioOptionSpec, colW: Int): Int {
        val cacheKey = radioOptionCacheKey(o, colW)
        radioOptionHeightCache[cacheKey]?.let { return it }
        val th = theme()
        val textWidth = (colW - RADIO_CONTROL_W).coerceAtLeast(1)
        val primaryText = o.label.ifEmpty { o.value }
        val raw = buildString {
            append(primaryText)
            if (o.description.isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                append(o.description)
            }
        }
        val textHeight = if (raw.isNotEmpty()) {
            val rich = formatEmbedRichText(raw, th)
            val lay = CodeFenceSpan.buildRichStaticLayout(rich, FIELD_NAME_PAINT, textWidth) {
                setMaxLines(12)
                setEllipsize(TextUtils.TruncateAt.END)
                setLineSpacing(LayoutHelper.dpf(2f), 1f)
            }
            lay.height
        } else 0
        val h = max(textHeight, RADIO_CONTROL_H)
        radioOptionHeightCache[cacheKey] = h
        return h
    }

    private fun radioOptionCacheKey(o: EmbedRadioOptionSpec, colW: Int): Long =
        (o.hashCode().toLong() and 0xFFFFFFFFL) shl 32 or (colW.toLong() and 0xFFFFFFFFL)

    private fun buttonBackgroundColor(theme: ThemeColors, style: EmbedButtonStyle): Int = when (style) {
        EmbedButtonStyle.PRIMARY -> theme.primary
        EmbedButtonStyle.SECONDARY -> theme.surfaceVariant
        EmbedButtonStyle.SUCCESS -> theme.colorSuccess
        EmbedButtonStyle.DANGER -> theme.error
        EmbedButtonStyle.LINK -> theme.surfaceVariant
    }

    companion object {
        private val COLOR_BAR_W = LayoutHelper.dp(4)
        private val PAD = LayoutHelper.dp(10)
        private val RADIUS = LayoutHelper.dpf(4f)
        private val GAP = LayoutHelper.dp(6)
        private val THUMB_SIZE = LayoutHelper.dp(50)
        private val IMG_RADIUS = LayoutHelper.dpf(4f)
        private val TOP_MARGIN = LayoutHelper.dp(4)
        private val AUTHOR_ICON = LayoutHelper.dp(28)
        private val FOOTER_ICON = LayoutHelper.dp(24)
        private val FIELD_NAME_GAP = LayoutHelper.dp(2)
        private val INPUT_MIN_H = LayoutHelper.dp(40)
        private val INPUT_TEXTAREA_MIN_H = LayoutHelper.dp(80)
        private val SELECT_ROW_H = INPUT_MIN_H
        private val RADIO_CONTROL_W = LayoutHelper.dp(48)
        private val RADIO_CONTROL_H = LayoutHelper.dp(48)

        private val ACTION_PANEL_TOP_GAP = LayoutHelper.dp(8)
        private val INTER_EMBED_CARD_GAP = LayoutHelper.dp(8)
        private val ACTION_ROW_PANEL_GAP = LayoutHelper.dp(8)
        private val BUTTON_H = LayoutHelper.dp(40)
        private val BUTTON_PAD_H = LayoutHelper.dp(20)
        private val BUTTON_MIN_W = LayoutHelper.dp(60)
        private val BUTTON_RADIUS = LayoutHelper.dpf(4f)
        private val BUTTON_ROW_GAP = LayoutHelper.dp(8)

        private val tmpRect = RectF()

        private val BG_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        private val BAR_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        private val BUTTON_BG_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        private val BUTTON_RIPPLE_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        private val BUTTON_LABEL_PAINT = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            isAntiAlias = true
        }

        private val TITLE_PAINT = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = LayoutHelper.dpf(14f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        private val DESC_PAINT = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = LayoutHelper.dpf(13f)
        }

        private val FIELD_NAME_PAINT = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = LayoutHelper.dpf(14f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        private val FIELD_VALUE_PAINT = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = LayoutHelper.dpf(13f)
        }

        private val FOOTER_PAINT = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = LayoutHelper.dpf(12f)
        }

        private val AUTHOR_PAINT = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = LayoutHelper.dpf(13f)
        }
    }
}
