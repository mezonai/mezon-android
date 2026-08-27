package com.mezon.mobile.home.chat

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.ExifInterface
import android.net.Uri
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.InAppOverlayHost
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.shared.TransformCanvasView
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/** Full-screen, lightweight editor for a single pending chat image. */
class ChatImageEditorDialog(
    context: Context,
    private val item: AttachmentPickerItem,
    private val onSend: (AttachmentPickerItem) -> Unit,
) : Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val editorCanvas = TransformCanvasView(
        context = context,
        outsideDimColor = 0x99000000.toInt(),
    )
    private val progress = ProgressBar(context)
    private var decodeJob: Job? = null
    private var sourceBitmap: Bitmap? = null
    @Volatile
    private var pendingOutputFile: File? = null
    private var working = false
    private var hasChanges = false
    private var selectedColor = 0xFFFF3B30.toInt()
    private var toolLabelsScheduled = false
    private var editingTextIndex: Int? = null
    private var textDeleteTargetHot = false
    private val textDeleteHitRect = Rect()

    private lateinit var cropButton: LinearLayout
    private lateinit var drawButton: LinearLayout
    private lateinit var textButton: LinearLayout
    private lateinit var undoAction: TextView
    private lateinit var palette: LinearLayout
    private lateinit var paletteEraserDivider: View
    private lateinit var eraserButton: FrameLayout
    private lateinit var brushSizeSlider: VerticalBrushSizeSlider
    private lateinit var textDeleteTarget: FrameLayout
    private lateinit var textDeleteIcon: ImageView
    private lateinit var textInputOverlay: FrameLayout
    private lateinit var textInput: EditText
    private val toolButtons = ArrayList<LinearLayout>()
    private val toolLabels = ArrayList<TextView>()
    private var refreshPaletteSelection: (() -> Unit)? = null
    private val hideToolLabelsRunnable = Runnable { setToolLabelsVisible(false) }

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
            statusBarColor = Color.BLACK
            navigationBarColor = Color.BLACK
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        }
        setContentView(buildContent())
        setCanceledOnTouchOutside(false)
        loadSource()
    }

    override fun show() {
        super.show()
        InAppOverlayHost.register(this, dismissOnOverlayTap = false)
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        if (!toolLabelsScheduled) {
            toolLabelsScheduled = true
            editorCanvas.postDelayed(hideToolLabelsRunnable, TOOL_LABEL_DURATION_MS)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::textInputOverlay.isInitialized && textInputOverlay.visibility == View.VISIBLE) {
            closeTextInput(addText = false)
        } else {
            super.onBackPressed()
        }
    }

    private fun buildContent(): View {
        val root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        val statusInset = AndroidUtilities.statusBarHeight
        val navigationInset = AndroidUtilities.navigationBarHeight

        editorCanvas.apply {
            setFreeformCropEnabled(true)
            setCropInsetsDp(left = 16f, top = 76f, right = 16f, bottom = 76f)
            setBrushWidthDp(6f)
            setOnEditorChangedListener {
                if (!hasChanges) {
                    hasChanges = true
                    updateModeButtons()
                }
            }
            setOnTextEditRequestedListener { index, text, color ->
                if (!working) showTextInput(index, text, color)
            }
            setOnTextSelectedListener { color ->
                selectedColor = color
                setBrushColor(color)
                refreshPaletteSelection?.invoke()
            }
            setOnTextDeleteDragListener(::updateTextDeleteTarget)
            visibility = View.INVISIBLE
        }
        root.addView(
            editorCanvas,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        root.addView(buildTopOverlay(statusInset), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            LayoutHelper.dp(64f) + statusInset,
            Gravity.TOP,
        ))

        eraserButton = buildEraserButton()
        palette = buildPalette()
        root.addView(
            palette,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                LayoutHelper.dp(48f),
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply { bottomMargin = navigationInset + LayoutHelper.dp(80f) },
        )

        brushSizeSlider = VerticalBrushSizeSlider(context).apply {
            contentDescription = context.getString(R.string.image_editor_brush_size)
            elevation = LayoutHelper.dpf(4f)
            onValueChanged = { editorCanvas.setBrushWidthDp(it) }
        }
        root.addView(
            brushSizeSlider,
            FrameLayout.LayoutParams(
                LayoutHelper.dp(44f),
                LayoutHelper.dp(184f),
                Gravity.START or Gravity.CENTER_VERTICAL,
            ).apply { leftMargin = LayoutHelper.dp(10f) },
        )

        textDeleteTarget = buildTextDeleteTarget()
        root.addView(
            textDeleteTarget,
            FrameLayout.LayoutParams(
                LayoutHelper.dp(52f),
                LayoutHelper.dp(52f),
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply { bottomMargin = navigationInset + LayoutHelper.dp(16f) },
        )

        root.addView(
            buildToolRail(),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.TOP,
            ).apply {
                topMargin = statusInset + LayoutHelper.dp(72f)
                rightMargin = LayoutHelper.dp(12f)
            },
        )

        root.addView(
            buildSendAction(),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                LayoutHelper.dp(52f),
                Gravity.END or Gravity.BOTTOM,
            ).apply {
                rightMargin = LayoutHelper.dp(12f)
                bottomMargin = navigationInset + LayoutHelper.dp(16f)
            },
        )
        updateModeButtons()

        progress.apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
        }
        root.addView(
            progress,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )

        textInputOverlay = buildTextInputOverlay(statusInset, navigationInset)
        root.addView(
            textInputOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        return root
    }

    private fun buildTopOverlay(statusInset: Int): View = FrameLayout(context).apply {
        setPadding(LayoutHelper.dp(12f), statusInset + LayoutHelper.dp(8f), LayoutHelper.dp(12f), 0)

        undoAction = TextView(context).apply {
            text = context.getString(R.string.image_editor_undo)
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(LayoutHelper.dp(8f), 0, LayoutHelper.dp(8f), 0)
            setShadowLayer(LayoutHelper.dpf(3f), 0f, LayoutHelper.dpf(1f), Color.BLACK)
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            setOnClickListener {
                if (working) return@setOnClickListener
                editorCanvas.resetEditor()
                hasChanges = false
                updateModeButtons()
            }
        }
        addView(
            undoAction,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                LayoutHelper.dp(48f),
                Gravity.START or Gravity.TOP,
            ),
        )
    }

    private fun buildToolRail(): View {
        val tools = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        fun addTool(view: LinearLayout) {
            tools.addView(
                view,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LayoutHelper.dp(46f)).apply {
                    gravity = Gravity.END
                    bottomMargin = LayoutHelper.dp(8f)
                },
            )
        }

        drawButton = toolButton(
            iconRes = R.drawable.ic_image_editor_draw,
            label = context.getString(R.string.image_editor_draw),
        ) {
            editorCanvas.setDrawingMode(true)
            editorCanvas.setEraserMode(false)
            updateModeButtons()
        }
        addTool(drawButton)

        textButton = toolButton(
            iconRes = null,
            label = context.getString(R.string.image_editor_text),
            textGlyph = "Aa",
        ) {
            showTextInput()
        }
        addTool(textButton)

        cropButton = toolButton(
            iconRes = R.drawable.ic_image_editor_crop,
            label = context.getString(R.string.image_editor_crop),
        ) {
            editorCanvas.setDrawingMode(false)
            updateModeButtons()
        }
        addTool(cropButton)

        val rotateButton = toolButton(
            iconRes = R.drawable.ic_image_editor_rotate,
            label = context.getString(R.string.image_crop_rotate_left_cd),
        ) {
            editorCanvas.setDrawingMode(false)
            editorCanvas.rotateByDegrees(-90f)
            updateModeButtons()
        }
        addTool(rotateButton)

        return tools
    }

    private fun buildEraserButton(): FrameLayout = FrameLayout(context).apply {
        visibility = View.GONE
        contentDescription = context.getString(R.string.image_editor_eraser)
        background = toolBackground(active = false)
        elevation = LayoutHelper.dpf(4f)
        isClickable = true
        isFocusable = true
        setOnClickListener {
            if (working || !editorCanvas.isDrawingMode()) return@setOnClickListener
            editorCanvas.setEraserMode(!editorCanvas.isEraserMode())
            updateModeButtons()
        }
        addView(
            ImageView(context).apply {
                setImageResource(R.drawable.ic_image_editor_eraser)
                imageTintList = ColorStateList.valueOf(Color.WHITE)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            },
            FrameLayout.LayoutParams(LayoutHelper.dp(27f), LayoutHelper.dp(27f), Gravity.CENTER),
        )
    }

    private fun toolButton(
        iconRes: Int?,
        label: String,
        textGlyph: String? = null,
        action: () -> Unit,
    ): LinearLayout = LinearLayout(context).apply {
        gravity = Gravity.CENTER
        minimumWidth = LayoutHelper.dp(46f)
        setPadding(LayoutHelper.dp(10f), 0, LayoutHelper.dp(13f), 0)
        contentDescription = label
        background = toolBackground(active = false)
        elevation = LayoutHelper.dpf(4f)
        isClickable = true
        isFocusable = true
        setOnClickListener { if (!working) action() }

        val iconView = if (textGlyph != null) {
            TextView(context).apply {
                text = textGlyph
                textSize = 20f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        } else {
            ImageView(context).apply {
                iconRes?.let(::setImageResource)
                imageTintList = ColorStateList.valueOf(Color.WHITE)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        }
        addView(iconView, LinearLayout.LayoutParams(LayoutHelper.dp(27f), LayoutHelper.dp(27f)))

        val labelView = TextView(context).apply {
            text = label
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
        }
        addView(
            labelView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            ).apply { leftMargin = LayoutHelper.dp(7f) },
        )
        toolButtons.add(this)
        toolLabels.add(labelView)
    }

    private fun setToolLabelsVisible(visible: Boolean) {
        toolLabels.forEach { it.visibility = if (visible) View.VISIBLE else View.GONE }
        toolButtons.forEach { button ->
            button.setPadding(
                if (visible) LayoutHelper.dp(10f) else 0,
                0,
                if (visible) LayoutHelper.dp(13f) else 0,
                0,
            )
            button.layoutParams = (button.layoutParams as? LinearLayout.LayoutParams)?.apply {
                width = if (visible) LinearLayout.LayoutParams.WRAP_CONTENT else LayoutHelper.dp(46f)
            }
            button.requestLayout()
        }
    }

    private fun buildSendAction(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(LayoutHelper.dp(18f), 0, LayoutHelper.dp(20f), 0)
        background = roundedBackground(Color.WHITE, LayoutHelper.dpf(26f))
        elevation = LayoutHelper.dpf(5f)
        isClickable = true
        isFocusable = true
        contentDescription = context.getString(R.string.message_send)
        setOnClickListener { if (!working) sendImage() }

        addView(
            ImageView(context).apply {
                setImageResource(MezonIcon.sendMessageIcon.resId)
                imageTintList = ColorStateList.valueOf(Color.BLACK)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            },
            LinearLayout.LayoutParams(LayoutHelper.dp(23f), LayoutHelper.dp(23f)),
        )
        addView(
            TextView(context).apply {
                text = context.getString(R.string.message_send)
                textSize = 16f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(Color.BLACK)
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            ).apply { leftMargin = LayoutHelper.dp(8f) },
        )
    }

    private fun buildPalette(): LinearLayout {
        val colors = intArrayOf(
            Color.WHITE,
            Color.BLACK,
            0xFFFF3B30.toInt(),
            0xFFFF9500.toInt(),
            0xFFFFCC00.toInt(),
            0xFF34C759.toInt(),
            0xFF0A84FF.toInt(),
            0xFFAF52DE.toInt(),
        )
        val colorViews = ArrayList<View>(colors.size)
        editorCanvas.setBrushColor(selectedColor)

        val paletteView = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(LayoutHelper.dp(8f), 0, LayoutHelper.dp(4f), 0)
            background = roundedBackground(0xB3000000.toInt(), LayoutHelper.dpf(24f))
            elevation = LayoutHelper.dpf(4f)
            visibility = View.GONE
        }

        fun updatePalette() {
            colorViews.forEachIndexed { index, view ->
                view.background = colorCircle(colors[index], colors[index] == selectedColor)
            }
        }
        refreshPaletteSelection = { updatePalette() }

        colors.forEachIndexed { index, color ->
            val chip = View(context).apply {
                contentDescription = context.getString(R.string.image_editor_color_cd, index + 1)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    selectedColor = color
                    editorCanvas.setBrushColor(color)
                    if (editorCanvas.isDrawingMode()) editorCanvas.setEraserMode(false)
                    if (editorCanvas.isTextMode()) editorCanvas.setSelectedTextColor(color)
                    if (::textInput.isInitialized) {
                        textInput.setTextColor(color)
                        textInput.setShadowLayer(
                            LayoutHelper.dpf(2f),
                            0f,
                            LayoutHelper.dpf(1f),
                            if (color == Color.BLACK) Color.WHITE else Color.BLACK,
                        )
                    }
                    updatePalette()
                    updateModeButtons()
                }
            }
            colorViews.add(chip)
            paletteView.addView(
                chip,
                LinearLayout.LayoutParams(LayoutHelper.dp(25f), LayoutHelper.dp(25f)).apply {
                    leftMargin = LayoutHelper.dp(2f)
                    rightMargin = LayoutHelper.dp(2f)
                },
            )
        }
        paletteEraserDivider = View(context).apply { setBackgroundColor(0x33FFFFFF) }
        paletteView.addView(
            paletteEraserDivider,
            LinearLayout.LayoutParams(LayoutHelper.dp(1f), LayoutHelper.dp(26f)).apply {
                leftMargin = LayoutHelper.dp(6f)
                rightMargin = LayoutHelper.dp(4f)
            },
        )
        paletteView.addView(
            eraserButton,
            LinearLayout.LayoutParams(LayoutHelper.dp(40f), LayoutHelper.dp(40f)),
        )
        updatePalette()
        return paletteView
    }

    private fun buildTextInputOverlay(statusInset: Int, navigationInset: Int): FrameLayout =
        FrameLayout(context).apply {
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            setBackgroundColor(0xE6000000.toInt())

            textInput = EditText(context).apply {
                textSize = 32f
                setTextColor(selectedColor)
                setShadowLayer(LayoutHelper.dpf(2f), 0f, LayoutHelper.dpf(1f), Color.BLACK)
                setHintTextColor(0x66FFFFFF)
                hint = context.getString(R.string.image_editor_text_hint)
                gravity = Gravity.CENTER
                background = null
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                setSingleLine(false)
                maxLines = 6
                filters = arrayOf(InputFilter.LengthFilter(MAX_TEXT_LENGTH))
                setPadding(LayoutHelper.dp(24f), LayoutHelper.dp(24f), LayoutHelper.dp(24f), LayoutHelper.dp(24f))
            }
            addView(
                textInput,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ).apply {
                    topMargin = statusInset + LayoutHelper.dp(64f)
                    bottomMargin = navigationInset + LayoutHelper.dp(64f)
                },
            )

            fun action(label: String, bold: Boolean, onClick: () -> Unit): TextView =
                TextView(context).apply {
                    text = label
                    textSize = 16f
                    typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    setPadding(LayoutHelper.dp(12f), 0, LayoutHelper.dp(12f), 0)
                    setOnClickListener { onClick() }
                }

            addView(
                action(context.getString(R.string.common_cancel), bold = false) {
                    closeTextInput(addText = false)
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    LayoutHelper.dp(48f),
                    Gravity.START or Gravity.TOP,
                ).apply {
                    leftMargin = LayoutHelper.dp(8f)
                    topMargin = statusInset + LayoutHelper.dp(8f)
                },
            )
            addView(
                action(context.getString(R.string.image_editor_text_done), bold = true) {
                    closeTextInput(addText = true)
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    LayoutHelper.dp(48f),
                    Gravity.END or Gravity.TOP,
                ).apply {
                    rightMargin = LayoutHelper.dp(8f)
                    topMargin = statusInset + LayoutHelper.dp(8f)
                },
            )
        }

    private fun showTextInput(
        editIndex: Int? = null,
        initialText: String = "",
        initialColor: Int = selectedColor,
    ) {
        editingTextIndex = editIndex
        selectedColor = initialColor
        refreshPaletteSelection?.invoke()
        editorCanvas.setTextMode(true)
        updateModeButtons()
        textInput.setText(initialText)
        textInput.setSelection(textInput.text?.length ?: 0)
        textInput.setTextColor(selectedColor)
        textInput.setShadowLayer(
            LayoutHelper.dpf(2f),
            0f,
            LayoutHelper.dpf(1f),
            if (selectedColor == Color.BLACK) Color.WHITE else Color.BLACK,
        )
        textInputOverlay.visibility = View.VISIBLE
        textInputOverlay.bringToFront()
        textInput.requestFocus()
        textInput.postDelayed({ AndroidUtilities.showKeyboard(textInput) }, 100L)
    }

    private fun closeTextInput(addText: Boolean) {
        if (!::textInputOverlay.isInitialized || textInputOverlay.visibility != View.VISIBLE) return
        val content = textInput.text?.toString().orEmpty()
        AndroidUtilities.hideKeyboard(textInput)
        textInput.clearFocus()
        textInputOverlay.visibility = View.GONE
        if (addText) {
            val editIndex = editingTextIndex
            if (editIndex == null) {
                if (content.isNotBlank()) editorCanvas.addText(content, selectedColor)
            } else {
                editorCanvas.updateText(editIndex, content, selectedColor)
            }
        }
        editingTextIndex = null
        textInput.text?.clear()
        updateModeButtons()
    }

    private fun buildTextDeleteTarget(): FrameLayout = FrameLayout(context).apply {
        visibility = View.GONE
        contentDescription = context.getString(R.string.common_delete)
        background = textDeleteBackground(active = false)
        elevation = LayoutHelper.dpf(5f)
        textDeleteIcon = ImageView(context).apply {
            setImageResource(MezonIcon.trashIcon.resId)
            imageTintList = ColorStateList.valueOf(Color.BLACK)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        addView(textDeleteIcon, FrameLayout.LayoutParams(LayoutHelper.dp(23f), LayoutHelper.dp(23f), Gravity.CENTER))
    }

    private fun updateTextDeleteTarget(active: Boolean, x: Float, y: Float): Boolean {
        if (!::textDeleteTarget.isInitialized) return false
        if (!active) {
            textDeleteTarget.animate().cancel()
            textDeleteTarget.visibility = View.GONE
            textDeleteTarget.alpha = 1f
            textDeleteTargetHot = false
            textDeleteTarget.scaleX = 1f
            textDeleteTarget.scaleY = 1f
            textDeleteTarget.background = textDeleteBackground(active = false)
            textDeleteIcon.imageTintList = ColorStateList.valueOf(Color.BLACK)
            updateModeButtons()
            return false
        }

        if (textDeleteTarget.visibility != View.VISIBLE) {
            textDeleteTarget.visibility = View.VISIBLE
            textDeleteTarget.alpha = 0f
            textDeleteTarget.scaleX = 0.82f
            textDeleteTarget.scaleY = 0.82f
            textDeleteTarget.bringToFront()
            textDeleteTarget.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(140L).start()
        }
        palette.visibility = View.GONE

        textDeleteTarget.getHitRect(textDeleteHitRect)
        val extraHitArea = LayoutHelper.dp(14f)
        textDeleteHitRect.inset(-extraHitArea, -extraHitArea)
        val isOverTarget = textDeleteHitRect.contains(x.toInt(), y.toInt())
        if (isOverTarget != textDeleteTargetHot) {
            textDeleteTargetHot = isOverTarget
            textDeleteTarget.background = textDeleteBackground(isOverTarget)
            textDeleteIcon.imageTintList = ColorStateList.valueOf(if (isOverTarget) Color.WHITE else Color.BLACK)
            val scale = if (isOverTarget) 1.08f else 1f
            textDeleteTarget.animate().scaleX(scale).scaleY(scale).setDuration(100L).start()
        }
        return isOverTarget
    }

    private fun textDeleteBackground(active: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = LayoutHelper.dpf(26f)
        setColor(if (active) 0xFFF04444.toInt() else Color.WHITE)
    }

    private fun updateModeButtons() {
        val drawing = editorCanvas.isDrawingMode()
        val texting = editorCanvas.isTextMode()
        cropButton.background = toolBackground(active = !drawing && !texting)
        drawButton.background = toolBackground(active = drawing)
        textButton.background = toolBackground(active = texting)
        val deletingText = ::textDeleteTarget.isInitialized && textDeleteTarget.visibility == View.VISIBLE
        palette.visibility = if ((drawing || texting) && !deletingText) View.VISIBLE else View.GONE
        paletteEraserDivider.visibility = if (drawing) View.VISIBLE else View.GONE
        eraserButton.visibility = if (drawing) View.VISIBLE else View.GONE
        eraserButton.background = toolBackground(active = drawing && editorCanvas.isEraserMode())
        brushSizeSlider.visibility = if (drawing) View.VISIBLE else View.GONE
        undoAction.visibility = if (hasChanges) View.VISIBLE else View.GONE
    }

    private fun toolBackground(active: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = LayoutHelper.dpf(23f)
        setColor(if (active) 0xE60A84FF.toInt() else 0xB3000000.toInt())
        setStroke(LayoutHelper.dp(1f), if (active) 0xFF66B5FF.toInt() else 0x33777777)
    }

    private fun roundedBackground(color: Int, radius: Float): GradientDrawable = GradientDrawable().apply {
        cornerRadius = radius
        setColor(color)
    }

    private fun colorCircle(color: Int, selected: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(LayoutHelper.dp(if (selected) 3f else 1f), if (selected) Color.WHITE else 0xFF777777.toInt())
    }

    private fun loadSource() {
        decodeJob = scope.launch {
            val decoded = withContext(Dispatchers.IO) {
                decodeOrientedBitmap(context, item.uri, item.mimeType, MAX_EDITOR_EDGE)
            }
            progress.visibility = View.GONE
            if (decoded == null) {
                Toast.makeText(context, R.string.image_editor_failed, Toast.LENGTH_SHORT).show()
                dismiss()
                return@launch
            }
            sourceBitmap = decoded
            editorCanvas.setImageBitmap(decoded)
            editorCanvas.visibility = View.VISIBLE
        }
    }

    private fun sendImage() {
        if (working || sourceBitmap == null) return
        if (!hasChanges) {
            onSend(item)
            dismiss()
            return
        }
        val (outWidth, outHeight) = editorCanvas.suggestedOutputSize(MAX_EDITOR_EDGE)
        val rendered = editorCanvas.renderCropped(outWidth, outHeight) ?: return
        working = true
        progress.visibility = View.VISIBLE
        scope.launch {
            val result = withContext(Dispatchers.IO) { writeEditedFile(rendered, outWidth, outHeight) }
            rendered.recycle()
            progress.visibility = View.GONE
            working = false
            if (result == null) {
                Toast.makeText(context, R.string.image_editor_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            onSend(result)
            pendingOutputFile = null
            dismiss()
        }
    }

    private fun writeEditedFile(bitmap: Bitmap, width: Int, height: Int): AttachmentPickerItem? {
        val preservePng = item.mimeType.equals("image/png", ignoreCase = true)
        val extension = if (preservePng) "png" else "jpg"
        val mimeType = if (preservePng) "image/png" else "image/jpeg"
        val directory = File(context.cacheDir, "chat_image_edits").apply { mkdirs() }
        val file = File(directory, "edited-${UUID.randomUUID()}.$extension")
        pendingOutputFile = file
        return try {
            val wrote = FileOutputStream(file).use { output ->
                val format = if (preservePng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                bitmap.compress(format, if (preservePng) 100 else 90, output)
            }
            if (!wrote || file.length() <= 0L) {
                file.delete()
                if (pendingOutputFile === file) pendingOutputFile = null
                return null
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            item.copy(
                uri = uri,
                path = file.absolutePath,
                filename = item.filename.substringBeforeLast('.', item.filename) + ".$extension",
                mimeType = mimeType,
                width = width,
                height = height,
                size = file.length(),
                duration = 0,
                isVideo = false,
                ownedCachePath = file.absolutePath,
            )
        } catch (_: Throwable) {
            file.delete()
            if (pendingOutputFile === file) pendingOutputFile = null
            null
        }
    }

    override fun dismiss() {
        InAppOverlayHost.unregister(this)
        decodeJob?.cancel()
        decodeJob = null
        editorCanvas.removeCallbacks(hideToolLabelsRunnable)
        if (::textInput.isInitialized) AndroidUtilities.hideKeyboard(textInput)
        editorCanvas.setOnEditorChangedListener(null)
        editorCanvas.setOnTextEditRequestedListener(null)
        editorCanvas.setOnTextSelectedListener(null)
        editorCanvas.setOnTextDeleteDragListener(null)
        editorCanvas.setImageBitmap(null)
        pendingOutputFile?.delete()
        pendingOutputFile = null
        sourceBitmap?.recycle()
        sourceBitmap = null
        scope.cancel()
        super.dismiss()
    }

    companion object {
        private const val MAX_EDITOR_EDGE = 2560
        private const val MAX_TEXT_LENGTH = 200
        private const val TOOL_LABEL_DURATION_MS = 5_000L

        private fun decodeOrientedBitmap(context: Context, uri: Uri, mimeType: String, maxEdge: Int): Bitmap? {
            val resolver = context.contentResolver
            return try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

                var sample = 1
                while (bounds.outWidth / (sample * 2) >= maxEdge || bounds.outHeight / (sample * 2) >= maxEdge) {
                    sample *= 2
                }
                var bitmap = resolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    })
                } ?: return null

                bitmap = applyOrientation(bitmap, readOrientation(context, uri, mimeType))
                val longest = max(bitmap.width, bitmap.height)
                if (longest > maxEdge) {
                    val scale = maxEdge.toFloat() / longest
                    val scaled = Bitmap.createScaledBitmap(
                        bitmap,
                        (bitmap.width * scale).toInt().coerceAtLeast(1),
                        (bitmap.height * scale).toInt().coerceAtLeast(1),
                        true,
                    )
                    if (scaled !== bitmap) bitmap.recycle()
                    bitmap = scaled
                }
                bitmap
            } catch (_: Throwable) {
                null
            }
        }

        private fun readOrientation(context: Context, uri: Uri, mimeType: String): Int {
            val supportsExif = mimeType.equals("image/jpeg", true) ||
                mimeType.equals("image/jpg", true) ||
                mimeType.equals("image/heic", true) ||
                mimeType.equals("image/heif", true)
            if (!supportsExif) return ExifInterface.ORIENTATION_NORMAL
            return runCatching {
                context.contentResolver.openInputStream(uri)?.use {
                    ExifInterface(it).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL,
                    )
                } ?: ExifInterface.ORIENTATION_NORMAL
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        }

        private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    matrix.postRotate(90f)
                    matrix.postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    matrix.postRotate(270f)
                    matrix.postScale(-1f, 1f)
                }
                else -> return bitmap
            }
            val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (oriented !== bitmap) bitmap.recycle()
            return oriented
        }
    }
}

private class VerticalBrushSizeSlider(context: Context) : View(context) {
    var onValueChanged: ((Float) -> Unit)? = null

    private val minValue = 3f
    private val maxValue = 16f
    private var value = 6f
    private val trapezoidPath = Path()
    private val activePath = Path()
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x59FFFFFF }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0A84FF.toInt()
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0A84FF.toInt() }

    init {
        isClickable = true
        isFocusable = true
        background = GradientDrawable().apply {
            cornerRadius = LayoutHelper.dpf(22f)
            setColor(0xCC111216.toInt())
            setStroke(LayoutHelper.dp(1f), 0x40FFFFFF)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val top = LayoutHelper.dpf(22f)
        val bottom = height - LayoutHelper.dpf(22f)
        val centerX = width / 2f
        val fraction = (value - minValue) / (maxValue - minValue)
        val thumbY = bottom - fraction * (bottom - top)
        val topHalfWidth = LayoutHelper.dpf(9f)
        val bottomHalfWidth = LayoutHelper.dpf(1.5f)
        val thumbHalfWidth = bottomHalfWidth + (topHalfWidth - bottomHalfWidth) * fraction

        trapezoidPath.reset()
        trapezoidPath.moveTo(centerX - topHalfWidth, top)
        trapezoidPath.lineTo(centerX + topHalfWidth, top)
        trapezoidPath.lineTo(centerX + bottomHalfWidth, bottom)
        trapezoidPath.lineTo(centerX - bottomHalfWidth, bottom)
        trapezoidPath.close()
        canvas.drawPath(trapezoidPath, trackPaint)

        activePath.reset()
        activePath.moveTo(centerX - bottomHalfWidth, bottom)
        activePath.lineTo(centerX + bottomHalfWidth, bottom)
        activePath.lineTo(centerX + thumbHalfWidth, thumbY)
        activePath.lineTo(centerX - thumbHalfWidth, thumbY)
        activePath.close()
        canvas.drawPath(activePath, activePaint)

        canvas.drawCircle(centerX, thumbY, LayoutHelper.dpf(9f), thumbPaint)
        val previewRadius = LayoutHelper.dpf(2f) + fraction * LayoutHelper.dpf(4f)
        canvas.drawCircle(centerX, thumbY, previewRadius, previewPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                updateValue(event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateValue(event.y)
                return true
            }
            MotionEvent.ACTION_UP -> {
                updateValue(event.y)
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateValue(touchY: Float) {
        val top = LayoutHelper.dpf(22f)
        val bottom = height - LayoutHelper.dpf(22f)
        if (bottom <= top) return
        val fraction = (1f - (touchY - top) / (bottom - top)).coerceIn(0f, 1f)
        val newValue = minValue + fraction * (maxValue - minValue)
        if (kotlin.math.abs(newValue - value) < 0.05f) return
        value = newValue
        invalidate()
        onValueChanged?.invoke(value)
    }
}
