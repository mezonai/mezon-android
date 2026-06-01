package com.mezon.mobile.home.profile

import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.ui.cells.AvatarView
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.theme.ThemeMode

class AppearanceThemeFragment : BaseFragment() {

    companion object {
        private val previewAvatarUrls = listOf(
            "https://cdn.mezon.ai/0/1812749818716491776/1782991817428439000/1757087543445_13.jpg",
            "https://cdn.mezon.ai/0/1812749818716491776/1782991817428439000/1757087543446_31.webp",
            "https://cdn.mezon.ai/0/1812749818716491776/1782991817428439000/1757087543445_4.webp",
            "https://cdn.mezon.ai/0/1812749818716491776/1782991817428439000/1757087543447_22.png"
        )

        private const val THEME_SWATCH_W_DP = 72
        private const val THEME_SWATCH_H_DP = 108
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

    private val themeOptions = listOf(
        ThemeOption(ThemeMode.SYSTEM, R.string.setting_theme_system),
        ThemeOption(ThemeMode.DARK, R.string.setting_theme_dark),
        ThemeOption(ThemeMode.LIGHT, R.string.setting_theme_light)
    )

    private data class ThemeOption(
        val mode: ThemeMode,
        val titleResId: Int
    )

    private data class PreviewPalette(
        val cardBackground: Int,
        val cardStroke: Int,
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
        val timeView: TextView
    )

    override fun onInject(entryPoint: FragmentEntryPoint) {
        userController = entryPoint.userController()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        observe(NotificationCenter.themeChanged) { _, _, _ ->
            if (::themeAdapter.isInitialized) updatePreview(themeAdapter.selectedMode)
        }
        return true
    }

    override fun createView(context: Context): View {
        rootContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.getColor(ThemeColors.key_windowBackgroundGray))
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
                setInfo((i + 1).toLong(), previewName(i))
                setImageUrl(previewAvatarUrls.getOrNull(i))
            }
            avatarWrap.addView(
                avatar,
                FrameLayout.LayoutParams(
                    LayoutHelper.dp(42),
                    LayoutHelper.dp(42)
                )
            )
            val onlineDot = ImageView(context).apply {
                setImageDrawable(MezonIcon.onlineStatusIcon.getDrawable(context))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
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
            previewRows += PreviewRowViews(nameView, messageView, timeView)
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
        rootContainer.setBackgroundColor(themeColors.getColor(ThemeColors.key_windowBackgroundGray))
        previewCard.background = GradientDrawable().apply {
            cornerRadius = LayoutHelper.dp(16f).toFloat()
            setColor(palette.cardBackground)
            setStroke(LayoutHelper.dp(1), palette.cardStroke)
        }
        previewTitle.setTextColor(palette.titleColor)
        for (row in previewRows) {
            row.nameView.setTextColor(palette.nameColor)
            row.messageView.setTextColor(palette.messageColor)
            row.timeView.setTextColor(palette.timeColor)
        }
        hintTextView.setTextColor(palette.hintColor)
        selectedThemeLabel.setTextColor(palette.selectedLabelColor)
    }

    private fun previewPalette(@Suppress("UNUSED_PARAMETER") mode: ThemeMode): PreviewPalette {
        val textPrimary = themeColors.onSurface
        val textSecondary = themeColors.onSurfaceVariant
        val bg = themeColors.getColor(ThemeColors.key_windowBackgroundGray)
        return PreviewPalette(
            cardBackground = themeColors.getColor(com.mezon.mobile.core.ThemeColors.key_sheetItemBackground),
            cardStroke = themeColors.getColor(com.mezon.mobile.core.ThemeColors.key_divider),
            titleColor = textPrimary,
            nameColor = textPrimary,
            messageColor = textSecondary,
            timeColor = textSecondary,
            hintColor = textSecondary,
            selectedLabelColor = readableOnBackground(bg, textPrimary)
        )
    }

    private fun readableOnBackground(background: Int, preferred: Int): Int {
        val lumBg = relativeLuminance(background)
        val lumPref = relativeLuminance(preferred)
        val contrastPref = contrastRatio(lumBg, lumPref)
        if (contrastPref >= 4.5) return preferred
        val light = Color.WHITE
        val dark = Color.parseColor("#111111")
        val cLight = contrastRatio(lumBg, relativeLuminance(light))
        val cDark = contrastRatio(lumBg, relativeLuminance(dark))
        return if (cLight >= cDark) light else dark
    }

    private fun relativeLuminance(color: Int): Double {
        fun channel(c: Int): Double {
            val v = c / 255.0
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        val r = channel(Color.red(color))
        val g = channel(Color.green(color))
        val b = channel(Color.blue(color))
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun contrastRatio(lum1: Double, lum2: Double): Double {
        val L1 = maxOf(lum1, lum2)
        val L2 = minOf(lum1, lum2)
        return (L1 + 0.05) / (L2 + 0.05)
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
            val swatch = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LayoutHelper.dp(swatchWidthDp),
                    LayoutHelper.dp(swatchHeightDp)
                )
            }
            val icon = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                visibility = View.GONE
            }
            swatch.addView(
                icon,
                FrameLayout.LayoutParams(LayoutHelper.dp(28), LayoutHelper.dp(28)).apply {
                    gravity = Gravity.CENTER
                }
            )
            cell.addView(swatch)
            val holder = ThemeViewHolder(cell, swatch, icon)
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
            holder.swatch.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(18f).toFloat()
                setStroke(
                    LayoutHelper.dp(if (isSelected) 2 else 1),
                    if (isSelected) Color.parseColor("#4F46E5") else Color.parseColor("#E5E7EB")
                )
                setColor(
                    when (item.mode) {
                        ThemeMode.SYSTEM -> Color.parseColor("#F9FAFB")
                        ThemeMode.DARK -> Color.parseColor("#111827")
                        ThemeMode.LIGHT -> Color.parseColor("#FFFFFF")
                        ThemeMode.ABYSS -> Color.parseColor("#0B1023")
                    }
                )
            }
            if (item.mode == ThemeMode.SYSTEM) {
                holder.icon.visibility = View.VISIBLE
                holder.icon.setImageDrawable(MezonIcon.reloadIcon.getDrawable(holder.itemView.context))
                holder.icon.colorFilter = PorterDuffColorFilter(Color.parseColor("#111827"), PorterDuff.Mode.SRC_IN)
            } else {
                holder.icon.visibility = View.GONE
                holder.icon.setImageDrawable(null)
                holder.icon.colorFilter = null
            }
        }

        class ThemeViewHolder(
            itemView: View,
            val swatch: FrameLayout,
            val icon: ImageView
        ) : RecyclerView.ViewHolder(itemView)
    }
}
