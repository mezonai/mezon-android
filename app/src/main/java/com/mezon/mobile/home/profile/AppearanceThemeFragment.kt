package com.mezon.mobile.home.profile

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.setPadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.core.theme.DesktopThemePalette
import com.mezon.mobile.core.theme.appThemePalette
import com.mezon.mobile.core.theme.DesktopThemeToken as Token
import com.mezon.mobile.core.theme.ThemeGradient
import com.mezon.mobile.core.theme.themeSurfaceShape
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.ui.cells.AvatarView
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.theme.ThemeMode

class AppearanceThemeFragment : BaseFragment() {

    companion object {
        private val previewAvatarUrls = listOf(
            "https://cdn.komu.vn/0/1812749818716491776/1782991817428439000/1757087543445_13.jpg",
            "https://cdn.komu.vn/0/1812749818716491776/1782991817428439000/1757087543446_31.webp",
            "https://cdn.komu.vn/0/1812749818716491776/1782991817428439000/1757087543445_4.webp",
            "https://cdn.komu.vn/0/1812749818716491776/1782991817428439000/1757087543447_22.png"
        )

        private fun paletteForMode(mode: ThemeMode, context: Context): DesktopThemePalette {
            val theme = ThemeColors.instance
            if (mode == ThemeMode.SYSTEM && theme.currentMode == ThemeMode.SYSTEM) return theme.desktop
            return appThemePalette(mode.resolve(isSystemDark(context)))
        }

        private fun isSystemDark(context: Context): Boolean =
            context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

        private fun previewBackground(
            color: Int,
            gradient: ThemeGradient?,
            radius: Float,
            strokeColor: Int,
            strokeWidth: Int
        ): android.graphics.drawable.Drawable {
            val fill = gradient?.previewDrawable(radius) ?: GradientDrawable().apply {
                cornerRadius = radius
                setColor(color)
            }
            val border = GradientDrawable().apply {
                cornerRadius = radius
                setStroke(strokeWidth, strokeColor)
            }
            return LayerDrawable(arrayOf(fill, border))
        }

        // UIColor.systemBlue in iOS 27.2, resolved against the system appearance.
        private val SWATCH_SELECTION_LIGHT = 0xFF0088FF.toInt()
        private val SWATCH_SELECTION_DARK = 0xFF0091FF.toInt()
        private const val THEME_SWATCH_W_DP = 72
        private const val THEME_SWATCH_H_DP = 108
        private const val SWATCH_FRAME_RADIUS_DP = 18
        private const val SWATCH_INSET_DP = 4
        private const val THEME_ITEM_GAP_DP = 6
        private val THEME_ITEM_OUTER_W_DP = THEME_SWATCH_W_DP + THEME_ITEM_GAP_DP
    }

    private lateinit var userController: UserController
    private lateinit var rootContainer: LinearLayout
    private lateinit var selectedThemeLabel: TextView
    private lateinit var hintTextView: TextView
    private lateinit var previewCard: LinearLayout
    private lateinit var themeRecyclerView: RecyclerView
    private lateinit var themeAdapter: ThemeCarouselAdapter
    private lateinit var snapHelper: PagerSnapHelper
    private lateinit var previewTitle: TextView
    private val previewRows = mutableListOf<PreviewRowViews>()
    private var shouldApplyOnNextSnap = false

    private val themeOptions = ThemeMode.appearanceOptions.map { ThemeOption(it, it.titleResId) }

    private data class ThemeOption(
        val mode: ThemeMode,
        val titleResId: Int
    )

    private data class PreviewPalette(
        val cardBackground: Int,
        val cardGradient: ThemeGradient?,
        val cardStroke: Int,
        val onlineColor: Int,
        val titleColor: Int,
        val nameColor: Int,
        val messageColor: Int,
        val timeColor: Int,
        val hintColor: Int,
        val selectedLabelColor: Int
    )

    private data class PreviewRowViews(
        val nameView: TextView,
        val messageView: TextView,
        val timeView: TextView,
        val onlineDot: View
    )

    override fun onInject(entryPoint: FragmentEntryPoint) {
        userController = entryPoint.userController()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        observe(NotificationCenter.themeChanged) { _, _, _ ->
            if (::themeAdapter.isInitialized) {
                updatePreview(themeAdapter.selectedMode)
                themeAdapter.notifyDataSetChanged()
            }
        }
        return true
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::themeAdapter.isInitialized) themeAdapter.notifyDataSetChanged()
    }

    override fun createView(context: Context): View {
        rootContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.surface)
            setPadding(LayoutHelper.dp(16), LayoutHelper.dp(12), LayoutHelper.dp(16), LayoutHelper.dp(12))
        }

        val previewSection = FrameLayout(context)
        rootContainer.addView(
            previewSection,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                0.62f
            ).apply {
                topMargin = LayoutHelper.dp(4)
            }
        )

        previewCard = createPreviewCard(context)
        previewSection.addView(
            previewCard,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
        )

        val bottomSection = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        rootContainer.addView(
            bottomSection,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                0.38f
            ).apply { topMargin = LayoutHelper.dp(8) }
        )

        selectedThemeLabel = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        bottomSection.addView(
            selectedThemeLabel,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = LayoutHelper.dp(8) }
        )

        themeRecyclerView = RecyclerView(context).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        }
        themeAdapter = ThemeCarouselAdapter(
            themeOptions,
            ThemeMode.SYSTEM,
            swatchWidthDp = THEME_SWATCH_W_DP,
            swatchHeightDp = THEME_SWATCH_H_DP,
            itemOuterWidthDp = THEME_ITEM_OUTER_W_DP
        ) { position ->
            centerThemeAt(position, smooth = true, shouldApply = true)
        }
        themeRecyclerView.adapter = themeAdapter
        snapHelper = PagerSnapHelper().also { it.attachToRecyclerView(themeRecyclerView) }
        themeRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    syncSelectionWithSnap(shouldApply = shouldApplyOnNextSnap)
                    shouldApplyOnNextSnap = true
                }
            }
        })

        bottomSection.addView(
            themeRecyclerView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        hintTextView = TextView(context).apply {
            text = getString(R.string.theme_preview_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        bottomSection.addView(
            hintTextView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = LayoutHelper.dp(8) }
        )

        themeRecyclerView.post {
            val itemWidth = LayoutHelper.dp(THEME_ITEM_OUTER_W_DP)
            val horizontalPadding = ((themeRecyclerView.width - itemWidth) / 2).coerceAtLeast(0)
            themeRecyclerView.setPadding(horizontalPadding, 0, horizontalPadding, 0)
            val initialPosition = themeOptions.indexOfFirst { it.mode == userController.themeMode }
                .takeIf { it >= 0 } ?: 0
            centerThemeAt(position = initialPosition, smooth = false, shouldApply = false)
            themeRecyclerView.post {
                syncSelectionWithSnap(shouldApply = false)
                shouldApplyOnNextSnap = true
            }
        }

        return wrapWithActionBar(getString(R.string.theme_screen_title), rootContainer)
    }

    private fun createPreviewCard(context: Context): LinearLayout {
        previewRows.clear()
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(18))
            clipToPadding = false
            clipChildren = false
            ViewCompat.setElevation(this, LayoutHelper.dp(6).toFloat())
        }
        previewTitle = TextView(context).apply {
            text = getString(R.string.theme_preview_conversations)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        card.addView(previewTitle)
        val rowsHost = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        card.addView(
            rowsHost,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply { topMargin = LayoutHelper.dp(8) }
        )
        repeat(4) { i ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val avatarWrap = FrameLayout(context).apply {
                clipChildren = false
            }
            val avatar = AvatarView(context).apply {
                setSizeDp(42)
                setRoundRadius(21f)
                setImageUrl(previewAvatarUrls.getOrNull(i))
            }
            avatarWrap.addView(
                avatar,
                FrameLayout.LayoutParams(
                    LayoutHelper.dp(42),
                    LayoutHelper.dp(42)
                )
            )
            val onlineDot = View(context)
            avatarWrap.addView(
                onlineDot,
                FrameLayout.LayoutParams(LayoutHelper.dp(14), LayoutHelper.dp(14)).apply {
                    gravity = Gravity.BOTTOM or Gravity.END
                }
            )
            row.addView(avatarWrap, LinearLayout.LayoutParams(LayoutHelper.dp(42), LayoutHelper.dp(42)))

            val content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            val nameView = TextView(context).apply {
                text = previewName(i)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            val messageView = TextView(context).apply {
                text = previewMessage(i)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                maxLines = 2
            }
            content.addView(nameView)
            content.addView(messageView)
            row.addView(
                content,
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply { leftMargin = LayoutHelper.dp(10) }
            )

            val timeView = TextView(context).apply {
                text = previewTime(i)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.END
            }
            row.addView(timeView)
            rowsHost.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )
            previewRows += PreviewRowViews(nameView, messageView, timeView, onlineDot)
        }
        return card
    }

    private fun centerThemeAt(position: Int, smooth: Boolean, shouldApply: Boolean) {
        shouldApplyOnNextSnap = shouldApply
        if (smooth) {
            themeRecyclerView.smoothScrollToPosition(position)
        } else {
            themeRecyclerView.scrollToPosition(position)
        }
    }

    private fun syncSelectionWithSnap(shouldApply: Boolean) {
        val lm = themeRecyclerView.layoutManager ?: return
        val snap = snapHelper.findSnapView(lm) ?: return
        val pos = themeRecyclerView.getChildAdapterPosition(snap)
        if (pos == RecyclerView.NO_POSITION) return
        val selected = themeOptions[pos]
        selectedThemeLabel.text = if (selected.mode == ThemeMode.SYSTEM) {
            getString(R.string.setting_theme_system_short)
        } else {
            getString(selected.titleResId)
        }
        if (themeAdapter.selectedMode != selected.mode) {
            themeAdapter.selectedMode = selected.mode
            themeAdapter.notifyDataSetChanged()
        }
        if (shouldApply && userController.themeMode != selected.mode) {
            userController.applyTheme(selected.mode)
        }
        updatePreview(selected.mode)
    }

    private fun updatePreview(mode: ThemeMode) {
        val palette = previewPalette(mode)
        rootContainer.setBackgroundColor(themeColors.surface)
        previewCard.background = previewBackground(
            palette.cardBackground, palette.cardGradient, LayoutHelper.dp(16f).toFloat(),
            palette.cardStroke, LayoutHelper.dp(1)
        )
        previewTitle.setTextColor(palette.titleColor)
        for (row in previewRows) {
            row.nameView.setTextColor(palette.nameColor)
            row.messageView.setTextColor(palette.messageColor)
            row.timeView.setTextColor(palette.timeColor)
            row.onlineDot.background = themeSurfaceShape(
                palette.onlineColor, LayoutHelper.dpf(7f), palette.cardBackground, LayoutHelper.dp(2)
            )
        }
        hintTextView.setTextColor(palette.hintColor)
        selectedThemeLabel.setTextColor(palette.selectedLabelColor)
    }

    private fun previewPalette(mode: ThemeMode): PreviewPalette {
        val context = requireContext()
        val palette = paletteForMode(mode, context)
        val isLegacyDark = if (mode == ThemeMode.SYSTEM && themeColors.currentMode == ThemeMode.SYSTEM) {
            themeColors.usesLegacyDarkColors
        } else {
            mode.resolve(isSystemDark(context)) == ThemeMode.DARK
        }
        return PreviewPalette(
            cardBackground = if (isLegacyDark) 0xFF2B2B33.toInt() else palette[Token.BG_THEME_DIRECT_MESSAGE],
            cardGradient = palette.conversationGradient,
            cardStroke = if (isLegacyDark) 0xFF49454F.toInt() else palette[Token.BG_THEME_DIRECT_MESSAGE],
            onlineColor = palette.semantic.statusOnline,
            titleColor = palette.semantic.textPrimary,
            nameColor = palette.semantic.textPrimary,
            messageColor = if (isLegacyDark) palette.semantic.textSecondary else palette.semantic.textMuted,
            timeColor = if (isLegacyDark) palette.semantic.textSecondary else palette.semantic.textMuted,
            hintColor = if (themeColors.usesLegacyDarkColors) themeColors.onSurfaceVariant else themeColors.textStrong,
            selectedLabelColor = themeColors.onSurface
        )
    }

    private fun previewName(index: Int): String = when (index) {
        0 -> "John Doe"
        1 -> "Jane Smith"
        2 -> "Alice Johnson"
        else -> "Bob Brown"
    }

    private fun previewMessage(index: Int): String = when (index) {
        0 -> getString(R.string.theme_preview_message_1)
        1 -> getString(R.string.theme_preview_message_2)
        2 -> getString(R.string.theme_preview_message_3)
        else -> getString(R.string.theme_preview_message_4)
    }

    private fun previewTime(index: Int): String = when (index) {
        0 -> "10m"
        1 -> "1h"
        2 -> "8h"
        else -> "14h"
    }

    private class ThemeCarouselAdapter(
        private val options: List<ThemeOption>,
        var selectedMode: ThemeMode,
        private val swatchWidthDp: Int,
        private val swatchHeightDp: Int,
        private val itemOuterWidthDp: Int,
        private val onItemClick: (Int) -> Unit
    ) : RecyclerView.Adapter<ThemeCarouselAdapter.ThemeViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ThemeViewHolder {
            val context = parent.context
            val cell = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = RecyclerView.LayoutParams(
                    LayoutHelper.dp(itemOuterWidthDp),
                    RecyclerView.LayoutParams.MATCH_PARENT
                )
            }
            val swatchFrame = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LayoutHelper.dp(swatchWidthDp),
                    LayoutHelper.dp(swatchHeightDp)
                )
            }
            val colorSwatch = View(context)
            val inset = LayoutHelper.dp(SWATCH_INSET_DP)
            swatchFrame.addView(
                colorSwatch,
                FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT).apply {
                    setMargins(inset, inset, inset, inset)
                }
            )
            val icon = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                visibility = View.GONE
            }
            swatchFrame.addView(
                icon,
                FrameLayout.LayoutParams(LayoutHelper.dp(28), LayoutHelper.dp(28)).apply {
                    gravity = Gravity.CENTER
                }
            )
            cell.addView(swatchFrame)
            val holder = ThemeViewHolder(cell, swatchFrame, colorSwatch, icon)
            cell.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onItemClick(pos)
            }
            return holder
        }

        override fun getItemCount(): Int = options.size

        override fun onBindViewHolder(holder: ThemeViewHolder, position: Int) {
            val item = options[position]
            val isSelected = item.mode == selectedMode
            val context = holder.itemView.context
            val palette = paletteForMode(item.mode, context)
            holder.colorSwatch.background = previewBackground(
                palette.previewColor,
                palette.previewGradient,
                LayoutHelper.dp(SWATCH_FRAME_RADIUS_DP - SWATCH_INSET_DP).toFloat(),
                if (item.mode == ThemeMode.DARK) 0xFFE5E7EB.toInt() else palette[Token.BORDER_PRIMARY],
                LayoutHelper.dp(1)
            )
            holder.swatchFrame.background = if (isSelected) {
                val selectionColor = when {
                    item.mode == ThemeMode.DARK -> 0xFF4F46E5.toInt()
                    isSystemDark(context) -> SWATCH_SELECTION_DARK
                    else -> SWATCH_SELECTION_LIGHT
                }
                themeSurfaceShape(
                    Color.TRANSPARENT,
                    LayoutHelper.dp(SWATCH_FRAME_RADIUS_DP).toFloat(),
                    selectionColor,
                    LayoutHelper.dp(2)
                )
            } else {
                null
            }
            if (item.mode == ThemeMode.SYSTEM) {
                holder.icon.visibility = View.VISIBLE
                holder.icon.setImageDrawable(MezonIcon.reloadIcon.getDrawable(holder.itemView.context))
                holder.icon.colorFilter = PorterDuffColorFilter(palette.semantic.textPrimary, PorterDuff.Mode.SRC_IN)
            } else {
                holder.icon.visibility = View.GONE
                holder.icon.setImageDrawable(null)
                holder.icon.colorFilter = null
            }
        }

        class ThemeViewHolder(
            itemView: View,
            val swatchFrame: FrameLayout,
            val colorSwatch: View,
            val icon: ImageView
        ) : RecyclerView.ViewHolder(itemView)
    }
}
