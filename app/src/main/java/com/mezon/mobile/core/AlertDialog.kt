package com.mezon.mobile.core

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

open class AlertDialog(context: Context, private val type: Int = ALERT_TYPE_MESSAGE) : Dialog(context) {

    companion object {
        const val ALERT_TYPE_MESSAGE = 0
        const val ALERT_TYPE_LOADING = 1
        const val ALERT_TYPE_SPINNER = 3
    }

    private var titleText: CharSequence? = null
    private var secondTitleText: CharSequence? = null
    private var messageText: CharSequence? = null
    private var titleSizeDp = 20
    private var messageSizeDp = 16
    private var messageTextViewClickable = true
    private var topImageRes = 0
    private var topHeight = 132
    private var buttonsVertical = false
    private var customWidth = -1
    private var customMaxHeight = -1
    private var positiveButtonText: CharSequence? = null
    private var negativeButtonText: CharSequence? = null
    private var neutralButtonText: CharSequence? = null
    private var positiveListener: DialogInterface.OnClickListener? = null
    private var negativeListener: DialogInterface.OnClickListener? = null
    private var neutralListener: DialogInterface.OnClickListener? = null
    private var customView: View? = null
    private var customViewHeight = LayoutHelper.WRAP_CONTENT
    private var items: Array<CharSequence>? = null
    private var itemIcons: IntArray? = null
    private var itemListener: DialogInterface.OnClickListener? = null
    private var singleChoiceItems: Array<CharSequence>? = null
    private var singleChoiceCheckedItem = -1
    private var singleChoiceListener: ((Int) -> Unit)? = null
    private var multiChoiceItems: Array<CharSequence>? = null
    private var multiChoiceChecked: BooleanArray? = null
    private var multiChoiceListener: ((Int, Boolean) -> Unit)? = null
    private var dismissOnButtonClick = true
    private var onPreDismissRunnable: Runnable? = null
    private var canceledOnTouchOutside = true
    private var progressStyle = false
    private var destructiveButton = -1

    private var titleView: TextView? = null
    private var messageView: TextView? = null
    private val buttonsLayout = LinearLayout(context)
    private var containerLayout: LinearLayout? = null

    private val theme: ThemeColors get() = ThemeColors.instance
    private val cornerRadius = LayoutHelper.dp(14).toFloat()

    override fun setTitle(title: CharSequence?) {
        titleText = title
        titleView?.text = title
    }

    fun setMessage(message: CharSequence?) {
        messageText = message
        messageView?.text = message
    }

    fun setTextSize(titleSize: Int, messageSize: Int) {
        titleSizeDp = titleSize
        messageSizeDp = messageSize
        titleView?.setTextSize(TypedValue.COMPLEX_UNIT_DIP, titleSize.toFloat())
        messageView?.setTextSize(TypedValue.COMPLEX_UNIT_DIP, messageSize.toFloat())
    }

    fun setMessageTextViewClickable(clickable: Boolean) {
        messageTextViewClickable = clickable
        messageView?.isClickable = clickable
        messageView?.movementMethod = if (clickable) android.text.method.LinkMovementMethod.getInstance() else null
    }

    fun setPositiveButtonListener(listener: DialogInterface.OnClickListener?) {
        positiveListener = listener
    }

    fun setNegativeButtonListener(listener: DialogInterface.OnClickListener?) {
        negativeListener = listener
    }

    override fun setOnCancelListener(listener: DialogInterface.OnCancelListener?) {
        super.setOnCancelListener(listener)
    }

    fun setDismissDialogByButtons(value: Boolean) {
        dismissOnButtonClick = value
    }

    fun setOnPreDismissListener(runnable: Runnable?) {
        onPreDismissRunnable = runnable
    }

    fun setPositiveButton(text: CharSequence?, listener: DialogInterface.OnClickListener? = null) {
        positiveButtonText = text
        positiveListener = listener
    }

    fun setNegativeButton(text: CharSequence?, listener: DialogInterface.OnClickListener? = null) {
        negativeButtonText = text
        negativeListener = listener
    }

    fun setNeutralButton(text: CharSequence?, listener: DialogInterface.OnClickListener? = null) {
        neutralButtonText = text
        neutralListener = listener
    }

    fun setCustomView(view: View?, height: Int = LayoutHelper.WRAP_CONTENT) {
        customView = view
        customViewHeight = height
    }

    fun setItems(itemTexts: Array<CharSequence>?, listener: DialogInterface.OnClickListener?) {
        items = itemTexts
        itemListener = listener
    }

    fun setItems(itemTexts: Array<CharSequence>?, icons: IntArray?, listener: DialogInterface.OnClickListener?) {
        items = itemTexts
        itemIcons = icons
        itemListener = listener
    }

    fun setDestructiveButton(index: Int) {
        destructiveButton = index
    }

    fun setDismissOnButtonClick(dismiss: Boolean) {
        dismissOnButtonClick = dismiss
    }

    fun setSingleChoiceItems(itemTexts: Array<CharSequence>?, checkedItem: Int, listener: ((Int) -> Unit)?) {
        singleChoiceItems = itemTexts
        singleChoiceCheckedItem = checkedItem
        singleChoiceListener = listener
    }

    fun setMultiChoiceItems(itemTexts: Array<CharSequence>?, checked: BooleanArray?, listener: ((Int, Boolean) -> Unit)?) {
        multiChoiceItems = itemTexts
        multiChoiceChecked = checked
        multiChoiceListener = listener
    }

    fun setTopImage(resId: Int, height: Int = 132) {
        topImageRes = resId
        topHeight = height
    }

    fun setSecondTitle(text: CharSequence?) {
        secondTitleText = text
    }

    fun setButtonsVertical(vertical: Boolean) {
        buttonsVertical = vertical
    }

    fun setCustomWidth(width: Int) {
        customWidth = width
    }

    fun setCustomMaxHeight(height: Int) {
        customMaxHeight = height
    }

    fun getButton(type: Int): View? {
        val index = when (type) {
            DialogInterface.BUTTON_POSITIVE -> buttonsLayout.childCount - 1
            DialogInterface.BUTTON_NEGATIVE -> buttonsLayout.childCount - 2
            DialogInterface.BUTTON_NEUTRAL -> 0
            else -> -1
        }
        return if (index in 0 until buttonsLayout.childCount) buttonsLayout.getChildAt(index) else null
    }

    fun getMessageTextView(): TextView? = messageView

    fun showDelayed(delay: Long) {
        AndroidUtilities.runOnUIThread(Runnable { if (!isShowing) show() }, delay)
    }

    fun dismissUnless(minDuration: Long) {
        AndroidUtilities.runOnUIThread(Runnable { dismiss() }, minDuration)
    }

    override fun dismiss() {
        onPreDismissRunnable?.run()
        onPreDismissRunnable = null
        super.dismiss()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bgDrawable = GradientDrawable().apply {
            setColor(theme.getColor(ThemeColors.key_dialogBackground))
            cornerRadius = this@AlertDialog.cornerRadius
        }

        if (type == ALERT_TYPE_LOADING) {
            val loadingLayout = FrameLayout(context).apply {
                background = bgDrawable
                val progress = ProgressBar(context)
                addView(progress, LayoutHelper.createFrame(48, 48, Gravity.CENTER))
            }
            setContentView(loadingLayout, ViewGroup.LayoutParams(LayoutHelper.dp(120), LayoutHelper.dp(120)))
            configureWindow()
            return
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = bgDrawable
            val padH = LayoutHelper.dp(24)
            val padV = LayoutHelper.dp(20)
            setPadding(padH, padV, padH, LayoutHelper.dp(14))
        }

        if (topImageRes != 0) {
            val topImageView = android.widget.ImageView(context).apply {
                setImageResource(topImageRes)
                scaleType = android.widget.ImageView.ScaleType.CENTER
            }
            root.addView(topImageView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, topHeight, 0f, Gravity.CENTER_HORIZONTAL, 0f, 0f, 0f, 8f))
        }

        titleText?.let { title ->
            titleView = TextView(context).apply {
                text = title
                setTextColor(theme.getColor(ThemeColors.key_dialogTextBlack))
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, titleSizeDp.toFloat())
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.START
            }
            root.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 0f, 0f, 4f))
        }

        secondTitleText?.let { second ->
            val secondView = TextView(context).apply {
                text = second
                setTextColor(theme.getColor(ThemeColors.key_text_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
                gravity = Gravity.START
            }
            root.addView(secondView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 0f, 0f, 8f))
        }

        messageText?.let { msg ->
            if (items == null && singleChoiceItems == null && multiChoiceItems == null) {
                val scrollView = ScrollView(context).apply {
                    isVerticalScrollBarEnabled = true
                }
                messageView = TextView(context).apply {
                    text = msg
                    setTextColor(theme.getColor(ThemeColors.key_dialogTextBlack))
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, messageSizeDp.toFloat())
                    gravity = Gravity.START
                    setLineSpacing(LayoutHelper.dp(2).toFloat(), 1f)
                    isClickable = messageTextViewClickable
                    movementMethod = if (messageTextViewClickable) android.text.method.LinkMovementMethod.getInstance() else null
                }
                scrollView.addView(messageView, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.START))
                root.addView(scrollView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 1f, Gravity.START, 0f, 0f, 0f, 16f))
            }
        }

        items?.let { itemList ->
            val scrollView = ScrollView(context)
            val itemsContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            for (i in itemList.indices) {
                val itemView = createItemView(context, itemList[i], itemIcons?.getOrNull(i) ?: 0, i)
                itemsContainer.addView(itemView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48))
            }
            scrollView.addView(itemsContainer, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.START))
            root.addView(scrollView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 1f))
        }

        singleChoiceItems?.let { itemList ->
            val scrollView = ScrollView(context)
            val itemsContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            val itemViews = mutableListOf<TextView>()
            for (i in itemList.indices) {
                val itemView = createSingleChoiceItemView(context, itemList[i], i == singleChoiceCheckedItem, i)
                itemsContainer.addView(itemView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48))
                itemViews.add(itemView)
            }
            itemViews.forEachIndexed { idx, view ->
                view.setOnClickListener {
                    singleChoiceCheckedItem = idx
                    itemViews.forEachIndexed { i, v -> updateSingleChoiceDrawable(v, i == idx) }
                    singleChoiceListener?.invoke(idx)
                    if (dismissOnButtonClick) dismiss()
                }
            }
            scrollView.addView(itemsContainer, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.START))
            root.addView(scrollView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 1f))
        }

        multiChoiceItems?.let { itemList ->
            val scrollView = ScrollView(context)
            val itemsContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            val checked = multiChoiceChecked?.copyOf() ?: BooleanArray(itemList.size)
            for (i in itemList.indices) {
                val itemView = createMultiChoiceItemView(context, itemList[i], checked.getOrElse(i) { false }, i)
                itemsContainer.addView(itemView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48))
            }
            for (idx in 0 until itemsContainer.childCount) {
                val child = itemsContainer.getChildAt(idx)
                (child as? TextView)?.setOnClickListener {
                    checked[idx] = !checked[idx]
                    updateMultiChoiceDrawable(child, checked[idx])
                    multiChoiceListener?.invoke(idx, checked[idx])
                }
            }
            scrollView.addView(itemsContainer, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.START))
            root.addView(scrollView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 1f))
        }

        customView?.let { view ->
            if (view.parent is ViewGroup) (view.parent as ViewGroup).removeView(view)
            root.addView(view, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, customViewHeight, 0f, Gravity.START, 0f, 8f, 0f, 16f))
        }

        buttonsLayout.orientation = if (buttonsVertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        buttonsLayout.gravity = if (buttonsVertical) Gravity.END else (Gravity.END or Gravity.CENTER_VERTICAL)

        addButtonToLayout(neutralButtonText, 2, neutralListener)
        addButtonToLayout(negativeButtonText, 1, negativeListener)
        addButtonToLayout(positiveButtonText, 0, positiveListener)

        if (buttonsLayout.childCount > 0) {
            root.addView(buttonsLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, gravity = Gravity.END, topMargin = 4f))
        }

        containerLayout = root
        setContentView(root, ViewGroup.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        configureWindow()
    }

    private fun addButtonToLayout(text: CharSequence?, which: Int, listener: DialogInterface.OnClickListener?) {
        if (text == null) return
        val button = TextView(context).apply {
            setText(text)
            gravity = Gravity.CENTER
            val buttonColor = when {
                which == 0 && destructiveButton == 0 -> theme.getColor(ThemeColors.key_dialogButtonRedText)
                which == 1 && destructiveButton == 1 -> theme.getColor(ThemeColors.key_dialogButtonRedText)
                else -> theme.getColor(ThemeColors.key_dialogButton)
            }
            setTextColor(buttonColor)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            isClickable = true
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)
            val padH = LayoutHelper.dp(16)
            val padV = LayoutHelper.dp(10)
            setPadding(padH, padV, padH, padV)
            setOnClickListener {
                val buttonWhich = when (which) {
                    0 -> DialogInterface.BUTTON_POSITIVE
                    1 -> DialogInterface.BUTTON_NEGATIVE
                    else -> DialogInterface.BUTTON_NEUTRAL
                }
                listener?.onClick(this@AlertDialog, buttonWhich)
                if (dismissOnButtonClick) dismiss()
            }
        }
        buttonsLayout.addView(button, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, gravity = Gravity.CENTER_VERTICAL, leftMargin = 4f))
    }

    private fun createItemView(context: Context, text: CharSequence, iconRes: Int, index: Int): View {
        val item = TextView(context).apply {
            setText(text)
            setTextColor(theme.getColor(ThemeColors.key_dialogTextBlack))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(LayoutHelper.dp(24), 0, LayoutHelper.dp(24), 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)
            if (iconRes != 0) {
                setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0)
                compoundDrawablePadding = LayoutHelper.dp(12)
            }
            setOnClickListener {
                itemListener?.onClick(this@AlertDialog, index)
                if (dismissOnButtonClick) dismiss()
            }
        }
        return item
    }

    private fun createSingleChoiceItemView(context: Context, text: CharSequence, checked: Boolean, index: Int): TextView {
        val item = TextView(context).apply {
            setText(text)
            setTextColor(theme.getColor(ThemeColors.key_dialogTextBlack))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(LayoutHelper.dp(24), 0, LayoutHelper.dp(48), 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)
        }
        updateSingleChoiceDrawable(item, checked)
        return item
    }

    private fun updateSingleChoiceDrawable(textView: TextView, checked: Boolean) {
        val drawable = if (checked) {
            object : Drawable() {
                private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = theme.getColor(ThemeColors.key_dialogButton)
                }
                override fun draw(canvas: Canvas) {
                    val r = bounds.width().coerceAtMost(bounds.height()) / 2f
                    canvas.drawCircle(bounds.exactCenterX(), bounds.exactCenterY(), r, paint)
                }
                override fun setAlpha(alpha: Int) { paint.alpha = alpha }
                override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
                override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE
                override fun getIntrinsicWidth(): Int = LayoutHelper.dp(16)
                override fun getIntrinsicHeight(): Int = LayoutHelper.dp(16)
            }
        } else null
        textView.setCompoundDrawablesWithIntrinsicBounds(null, null, drawable, null)
        textView.compoundDrawablePadding = LayoutHelper.dp(12)
    }

    private fun createMultiChoiceItemView(context: Context, text: CharSequence, checked: Boolean, index: Int): TextView {
        val item = TextView(context).apply {
            setText(text)
            setTextColor(theme.getColor(ThemeColors.key_dialogTextBlack))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(LayoutHelper.dp(24), 0, LayoutHelper.dp(48), 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)
        }
        updateMultiChoiceDrawable(item, checked)
        return item
    }

    private fun updateMultiChoiceDrawable(textView: TextView, checked: Boolean) {
        val drawable = if (checked) {
            object : Drawable() {
                private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = theme.getColor(ThemeColors.key_dialogButton)
                }
                override fun draw(canvas: Canvas) {
                    canvas.drawRect(bounds, paint)
                }
                override fun setAlpha(alpha: Int) { paint.alpha = alpha }
                override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
                override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE
                override fun getIntrinsicWidth(): Int = LayoutHelper.dp(16)
                override fun getIntrinsicHeight(): Int = LayoutHelper.dp(16)
            }
        } else null
        textView.setCompoundDrawablesWithIntrinsicBounds(null, null, drawable, null)
        textView.compoundDrawablePadding = LayoutHelper.dp(12)
    }

    private fun configureWindow() {
        window?.let { w ->
            w.setBackgroundDrawableResource(android.R.color.transparent)
            val lp = w.attributes
            lp.width = if (customWidth > 0) customWidth
                else (context.resources.displayMetrics.widthPixels * 0.85f).toInt().coerceAtMost(LayoutHelper.dp(360))
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT
            lp.gravity = Gravity.CENTER
            w.attributes = lp
            w.setDimAmount(0.5f)
        }
        setCanceledOnTouchOutside(canceledOnTouchOutside)
    }

    class Builder(private val context: Context) {
        private val dialog = AlertDialog(context)

        fun setTitle(title: CharSequence?): Builder { dialog.setTitle(title); return this }
        fun setMessage(message: CharSequence?): Builder { dialog.setMessage(message); return this }

        fun setPositiveButton(text: CharSequence?, listener: DialogInterface.OnClickListener? = null): Builder {
            dialog.setPositiveButton(text, listener)
            return this
        }

        fun setNegativeButton(text: CharSequence?, listener: DialogInterface.OnClickListener? = null): Builder {
            dialog.setNegativeButton(text, listener)
            return this
        }

        fun setNeutralButton(text: CharSequence?, listener: DialogInterface.OnClickListener? = null): Builder {
            dialog.setNeutralButton(text, listener)
            return this
        }

        fun setView(view: View?, height: Int = LayoutHelper.WRAP_CONTENT): Builder {
            dialog.setCustomView(view, height)
            return this
        }

        fun setItems(items: Array<CharSequence>?, listener: DialogInterface.OnClickListener?): Builder {
            dialog.setItems(items, listener)
            return this
        }

        fun setItems(items: Array<CharSequence>?, icons: IntArray?, listener: DialogInterface.OnClickListener?): Builder {
            dialog.setItems(items, icons, listener)
            return this
        }

        fun setDestructiveButton(index: Int): Builder { dialog.setDestructiveButton(index); return this }
        fun setDismissDialogByButtons(dismiss: Boolean): Builder { dialog.setDismissOnButtonClick(dismiss); return this }
        fun setTopImage(resId: Int, height: Int = 132): Builder { dialog.setTopImage(resId, height); return this }
        fun setSecondTitle(text: CharSequence?): Builder { dialog.setSecondTitle(text); return this }
        fun setButtonsVertical(value: Boolean): Builder { dialog.setButtonsVertical(value); return this }
        fun makeButtonsHorizontal(): Builder { dialog.setButtonsVertical(false); return this }

        fun setTextSize(titleSize: Int, messageSize: Int): Builder { dialog.setTextSize(titleSize, messageSize); return this }
        fun setMessageTextViewClickable(clickable: Boolean): Builder { dialog.setMessageTextViewClickable(clickable); return this }
        fun setPositiveButtonListener(listener: DialogInterface.OnClickListener?): Builder { dialog.setPositiveButtonListener(listener); return this }
        fun setNegativeButtonListener(listener: DialogInterface.OnClickListener?): Builder { dialog.setNegativeButtonListener(listener); return this }

        fun setSingleChoiceItems(items: Array<CharSequence>?, checkedItem: Int, listener: ((Int) -> Unit)?): Builder {
            dialog.setSingleChoiceItems(items, checkedItem, listener)
            return this
        }
        fun setMultiChoiceItems(items: Array<CharSequence>?, checked: BooleanArray?, listener: ((Int, Boolean) -> Unit)?): Builder {
            dialog.setMultiChoiceItems(items, checked, listener)
            return this
        }
        fun setOnPreDismissListener(runnable: Runnable?): Builder { dialog.setOnPreDismissListener(runnable); return this }

        fun setOnCancelListener(listener: DialogInterface.OnCancelListener?): Builder {
            dialog.setOnCancelListener(listener)
            return this
        }

        fun setOnDismissListener(listener: DialogInterface.OnDismissListener?): Builder {
            dialog.setOnDismissListener(listener)
            return this
        }

        fun create(): AlertDialog = dialog

        fun show(): AlertDialog {
            dialog.show()
            return dialog
        }
    }
}
