package com.mezon.mobile.home.clans.channelapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.home.clans.ChannelSectionCell
import com.mezon.mobile.ui.cells.MezonIcon

private const val LIMIT = 10
private const val COMPACT_THRESHOLD = 3
private const val HORIZONTAL_ITEM_WIDTH_DP = 48
private const val HORIZONTAL_ITEM_GAP_DP = 16
private const val HORIZONTAL_LOGO_SIZE_DP = 48

private val BLURPLE = 0xFF5A62F4.toInt()
private val STATUS_DOT = 0xFF43B581.toInt()

private val STRIP_MARGIN = LayoutHelper.dp(8)

class ChannelAppsStripView(
    context: Context,
    private val themeColors: ThemeColors
) : LinearLayout(context) {

    var onAppClick: ((ChannelAppUiModel) -> Unit)? = null
    var onViewAllClick: (() -> Unit)? = null

    private val header: TextView
    private val headerContainer: LinearLayout
    private val headerArrow: ImageView
    private val compactContainer: LinearLayout
    private val horizontalScroll: HorizontalScrollView
    private val horizontalContainer: LinearLayout

    private var apps: List<ChannelAppUiModel> = emptyList()
    private var isExpanded: Boolean = true

    init {
        orientation = VERTICAL
        visibility = GONE

        headerContainer = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ChannelSectionCell.HEIGHT
            setPadding(ChannelSectionCell.PADDING_START - STRIP_MARGIN, 0, 0, 0)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                isExpanded = !isExpanded
                updateExpandedState()
            }
            applyRipple(this, cornerDp = 8)
        }
        headerArrow = ImageView(context).apply {
            setImageDrawable(MezonIcon.chevronDownSmallIcon.getDrawable(context, themeColors.colorText))
            layoutParams = LayoutParams(ChannelSectionCell.ARROW_SIZE, ChannelSectionCell.ARROW_SIZE)
        }
        header = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, LayoutHelper.sp(13f))
            setTextColor(themeColors.colorText)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            isAllCaps = true
        }
        headerContainer.addView(headerArrow)
        headerContainer.addView(header)
        addView(headerContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        compactContainer = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(
                LayoutHelper.dp(4),
                LayoutHelper.dp(4),
                LayoutHelper.dp(4),
                LayoutHelper.dp(4)
            )
        }
        addView(compactContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        horizontalContainer = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(0, LayoutHelper.dp(6), LayoutHelper.dp(12), LayoutHelper.dp(10))
        }
        horizontalScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            addView(
                horizontalContainer,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        addView(horizontalScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun setApps(apps: List<ChannelAppUiModel>) {
        if (sameApps(this.apps, apps)) return
        this.apps = apps

        if (apps.isEmpty()) {
            visibility = GONE
            clearChildren()
            return
        }
        visibility = VISIBLE
        header.text = context.getString(R.string.channel_list_channel_apps)

        val compact = apps.size <= COMPACT_THRESHOLD
        if (compact) {
            headerContainer.visibility = VISIBLE
            horizontalScroll.visibility = GONE
            compactContainer.visibility = if (isExpanded) VISIBLE else GONE
            renderCompact(apps)
        } else {
            headerContainer.visibility = GONE
            compactContainer.visibility = GONE
            horizontalScroll.visibility = VISIBLE
            renderHorizontal(apps)
        }
    }

    fun invalidateTheme() {
        header.setTextColor(themeColors.colorText)
        headerArrow.setImageDrawable(
            MezonIcon.chevronDownSmallIcon.getDrawable(context, themeColors.colorText)
        )
        setApps(apps.toList())
    }

    private fun clearChildren() {
        compactContainer.removeAllViews()
        horizontalContainer.removeAllViews()
    }

    private fun updateExpandedState() {
        compactContainer.visibility = if (isExpanded) VISIBLE else GONE
        headerArrow.rotation = if (isExpanded) 0f else -90f
    }

    private fun renderCompact(list: List<ChannelAppUiModel>) {
        compactContainer.removeAllViews()
        for (item in list) {
            compactContainer.addView(buildCompactCard(item))
        }
        headerArrow.rotation = if (isExpanded) 0f else -90f
    }

    private fun buildCompactCard(item: ChannelAppUiModel): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = LayoutHelper.dp(42)
            setPadding(
                LayoutHelper.dp(8),
                LayoutHelper.dp(6),
                LayoutHelper.dp(16),
                LayoutHelper.dp(6)
            )
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(12).toFloat()
                setColor(themeColors.serverRailBg)
                setStroke(LayoutHelper.dp(1), themeColors.border)
            }
            val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = LayoutHelper.dp(8)
            layoutParams = lp
            isClickable = true
            isFocusable = true
            setOnClickListener { onAppClick?.invoke(item) }
            applyRipple(this, cornerDp = 12)
        }

        val logo = buildLogoView(item, LayoutHelper.dp(30), LayoutHelper.dp(10))
        row.addView(logo)

        val textCol = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            lp.marginStart = LayoutHelper.dp(8)
            layoutParams = lp
        }
        val nameTv = TextView(context).apply {
            text = item.appName
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            textSize = 14f
            setTextColor(themeColors.tabLabelActive)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        textCol.addView(nameTv)
        row.addView(textCol)

        val statusDot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(STATUS_DOT)
            }
            val sz = LayoutHelper.dp(8)
            val lp = LayoutParams(sz, sz)
            lp.marginStart = LayoutHelper.dp(8)
            layoutParams = lp
        }
        row.addView(statusDot)
        return row
    }

    private fun renderHorizontal(list: List<ChannelAppUiModel>) {
        horizontalContainer.removeAllViews()
        val total = list.size
        val showAllEnabled = total >= LIMIT
        val displayCount = if (showAllEnabled) LIMIT else total
        val viewAllIndex = LIMIT - 1
        for (i in 0 until displayCount) {
            if (showAllEnabled && i == viewAllIndex) {
                horizontalContainer.addView(buildViewAllItem())
            } else {
                horizontalContainer.addView(buildHorizontalItem(list[i]))
            }
        }
    }

    private fun buildHorizontalItem(item: ChannelAppUiModel): View {
        val col = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val lp = LayoutParams(LayoutHelper.dp(HORIZONTAL_ITEM_WIDTH_DP), LayoutParams.WRAP_CONTENT)
            lp.marginEnd = LayoutHelper.dp(HORIZONTAL_ITEM_GAP_DP)
            layoutParams = lp
            isClickable = true
            isFocusable = true
            setOnClickListener { onAppClick?.invoke(item) }
        }
        val logo = buildLogoView(item, LayoutHelper.dp(HORIZONTAL_LOGO_SIZE_DP), LayoutHelper.dp(HORIZONTAL_LOGO_SIZE_DP / 2))
        applyRipple(logo, cornerDp = 0, oval = true)
        logo.isDuplicateParentStateEnabled = true
        col.addView(logo)
        val nameTv = TextView(context).apply {
            text = item.appName
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(themeColors.colorText)
            val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            lp.topMargin = LayoutHelper.dp(2)
            layoutParams = lp
        }
        col.addView(nameTv)
        return col
    }

    private fun buildViewAllItem(): View {
        val col = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val lp = LayoutParams(LayoutHelper.dp(HORIZONTAL_ITEM_WIDTH_DP), LayoutParams.WRAP_CONTENT)
            lp.marginEnd = LayoutHelper.dp(HORIZONTAL_ITEM_GAP_DP)
            layoutParams = lp
            isClickable = true
            isFocusable = true
            setOnClickListener { onViewAllClick?.invoke() }
        }
        val logoBox = FrameLayout(context).apply {
            val sz = LayoutHelper.dp(HORIZONTAL_LOGO_SIZE_DP)
            layoutParams = LayoutParams(sz, sz)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(LayoutHelper.dp(1), BLURPLE)
                setColor(0x00000000)
            }
            isDuplicateParentStateEnabled = true
        }
        applyRipple(logoBox, cornerDp = 0, oval = true)
        val chevron = ImageView(context).apply {
            setImageDrawable(MezonIcon.chevronSmallRightIcon.getDrawable(context, BLURPLE))
            val sz = LayoutHelper.dp(30)
            layoutParams = FrameLayout.LayoutParams(sz, sz, Gravity.CENTER)
        }
        logoBox.addView(chevron)
        col.addView(logoBox)

        val nameTv = TextView(context).apply {
            text = context.getString(R.string.channel_list_view_all)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(BLURPLE)
            val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            lp.topMargin = LayoutHelper.dp(2)
            layoutParams = lp
        }
        col.addView(nameTv)
        return col
    }

    private fun buildLogoView(item: ChannelAppUiModel, sizeDp: Int, cornerDp: Int): View {
        val isCircle = cornerDp * 2 >= sizeDp
        val box = FrameLayout(context).apply {
            layoutParams = LayoutParams(sizeDp, sizeDp)
            background = GradientDrawable().apply {
                if (isCircle) {
                    shape = GradientDrawable.OVAL
                } else {
                    cornerRadius = cornerDp.toFloat()
                }
                setColor(themeColors.serverRailBg)
            }
            clipChildren = true
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    if (isCircle) {
                        outline.setOval(0, 0, view.width, view.height)
                    } else {
                        outline.setRoundRect(0, 0, view.width, view.height, cornerDp.toFloat())
                    }
                }
            }
        }
        val placeholderContainer = FrameLayout(context).apply {
            val sz = LayoutHelper.dp(24)
            layoutParams = FrameLayout.LayoutParams(sz, sz, Gravity.CENTER)
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(6).toFloat()
                setColor(0x00000000)
                setStroke(LayoutHelper.dp(1), themeColors.textDisabled)
            }
        }
        val placeholder = ImageView(context).apply {
            setImageDrawable(MezonIcon.channelApp.getDrawable(context, themeColors.textDisabled))
            val sz = LayoutHelper.dp(16)
            layoutParams = FrameLayout.LayoutParams(sz, sz, Gravity.CENTER)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        placeholderContainer.addView(placeholder)
        box.addView(placeholderContainer)

        if (item.appLogo.isNotBlank()) {
            val img = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                val sz = LayoutHelper.dp(24)
                layoutParams = FrameLayout.LayoutParams(sz, sz, Gravity.CENTER)
            }
            box.addView(img)
            val loader = MezonImageLoader.getInstance(context)
            loader.load(
                item.appLogo,
                LayoutHelper.dp(24),
                LayoutHelper.dp(24),
                onSuccess = { bmp: Bitmap ->
                    img.setImageBitmap(bmp)
                    placeholderContainer.visibility = GONE
                }
            )
        }
        return box
    }

    private fun applyRipple(view: View, cornerDp: Int, oval: Boolean = false) {
        val rippleColor = ColorStateList.valueOf((themeColors.colorText and 0x00FFFFFF) or 0x33000000)
        val mask = GradientDrawable().apply {
            if (oval) {
                shape = GradientDrawable.OVAL
            } else {
                cornerRadius = LayoutHelper.dp(cornerDp).toFloat()
            }
            setColor(0xFFFFFFFF.toInt())
        }
        view.foreground = RippleDrawable(rippleColor, null, mask)
    }

    private fun sameApps(a: List<ChannelAppUiModel>, b: List<ChannelAppUiModel>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) {
            val x = a[i]; val y = b[i]
            if (x.channelId != y.channelId) return false
            if (x.appName != y.appName) return false
            if (x.appLogo != y.appLogo) return false
        }
        return true
    }

}
