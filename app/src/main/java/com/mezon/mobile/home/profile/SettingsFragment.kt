package com.mezon.mobile.home.profile

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import com.mezon.mobile.BuildConfig
import com.mezon.mobile.R
import com.mezon.mobile.auth.AuthRepository
import com.mezon.mobile.core.AlertsCreator
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.session.LocaleManager
import com.mezon.mobile.home.qr.QrScanFragment
import com.mezon.mobile.home.friends.FriendRequestsFragment
import com.mezon.mobile.ui.cells.HeaderCell
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.cells.SelectPopup
import com.mezon.mobile.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
class SettingsFragment : BaseFragment() {

    companion object {
        private const val DEBOUNCE_MS = 300L
        private const val SETTINGS_ICON_SIZE_DP = 40
        private const val TITLE_TEXT_SP = 16f
        private const val VALUE_TEXT_SP = 15f
        private const val ICON_TEXT_GAP_DP = 3
        private const val ROW_PADDING_H_DP = 12
        private const val ROW_PADDING_V_DP = 10
        private const val SECTION_HEADER_TOP_DP = 10
        private const val LOGOUT_CARD_TOP_MARGIN_DP = 12
        private const val SEARCH_BAR_PADDING_V_DP = 14
    }

    var onLogout: (() -> Unit)? = null

    private lateinit var userController: UserController
    private lateinit var authRepository: AuthRepository

    private lateinit var scrollContent: LinearLayout
    private lateinit var searchBarWrap: LinearLayout
    private lateinit var searchBarIconView: ImageView
    private lateinit var searchEditText: EditText
    private lateinit var menuContainer: LinearLayout

    private val debounceHandler = Handler(Looper.getMainLooper())
    private var debounceRunnable: Runnable? = null

    private var fullMenu: List<MenuSection> = emptyList()

    private data class MenuItem(
        val title: String,
        @DrawableRes val iconResId: Int,
        val value: String?,
        val showChevron: Boolean,
        val isDestructive: Boolean,
        val onClick: ((View) -> Unit)?
    )

    private data class MenuSection(
        val title: String?,
        val items: List<MenuItem>
    )

    override fun onInject(entryPoint: FragmentEntryPoint) {
        userController = entryPoint.userController()
        authRepository = entryPoint.authRepository()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        observe(NotificationCenter.themeChanged) { _, _, _ ->
            if (fragmentView == null || !::searchEditText.isInitialized || !::scrollContent.isInitialized) return@observe
            fragmentView?.setBackgroundColor(themeColors.background)
            scrollContent.setBackgroundColor(themeColors.background)
            applySearchBarBackground()
            applySearchBarIconTint()
            fullMenu = buildMenuModel()
            applySearchQuery(searchEditText.text?.toString()?.trim() ?: "")
        }
        observe(NotificationCenter.languageChanged) { _, _, _ ->
            if (fragmentView == null || !::searchEditText.isInitialized) return@observe
            fullMenu = buildMenuModel()
            applySearchQuery(searchEditText.text?.toString()?.trim() ?: "")
        }
        return true
    }

    override fun onFragmentDestroy() {
        super.onFragmentDestroy()
        debounceRunnable?.let { debounceHandler.removeCallbacks(it) }
    }

    override fun createView(context: Context): View {
        fullMenu = buildMenuModel()
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
        }
        scrollContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(16), LayoutHelper.dp(6), LayoutHelper.dp(16), LayoutHelper.dp(16))
            setBackgroundColor(themeColors.background)
        }
        scroll.addView(scrollContent, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        buildSearchBar(context)
        scrollContent.addView(searchBarWrap, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        menuContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollContent.addView(menuContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = LayoutHelper.dp(8) })

        applySearchQuery("")
        return wrapWithActionBar(getString(R.string.common_settings), scroll)
    }

    private fun settingIconLightQr(): Int =
        if (themeColors.resolvedMode == ThemeMode.LIGHT) R.drawable.ic_qr_scan_setting_icon_black
        else R.drawable.ic_qr_scan_setting_icon

    private fun settingIconLightApp(): Int =
        if (themeColors.resolvedMode == ThemeMode.LIGHT) R.drawable.ic_app_icon_black
        else R.drawable.ic_app_icon

    private fun buildMenuModel(): List<MenuSection> {
        val accountItems = listOf(
            MenuItem(
                getString(R.string.account_settings_title),
                R.drawable.ic_account_icon,
                null,
                true,
                false,
                { openAccountSettings() }
            ),
            MenuItem(
                getString(R.string.friends_request_title),
                R.drawable.ic_friend_rq_icon,
                null,
                true,
                false,
                { presentFragment(FriendRequestsFragment()) }
            ),
            MenuItem(
                getString(R.string.setting_scan_qr),
                settingIconLightQr(),
                null,
                true,
                false,
                { presentFragment(QrScanFragment()) }
            ),
            MenuItem(
                getString(R.string.setting_devices),
                R.drawable.ic_device_icon,
                null,
                true,
                false,
                {
                    presentFragment(DeviceManageFragment())
                }
            )
        )
        val appItems = listOf(
            MenuItem(
                getString(R.string.setting_app_version_label),
                settingIconLightApp(),
                BuildConfig.VERSION_NAME,
                false,
                false,
                null
            ),
            MenuItem(
                getString(R.string.setting_app_appearance),
                R.drawable.ic_appearance_icon,
                null,
                true,
                false,
                { presentFragment(AppearanceThemeFragment()) }
            ),
            MenuItem(
                getString(R.string.setting_app_language),
                R.drawable.ic_language_setting_icon,
                languagePreviewValue(),
                true,
                false,
                { showLanguageSelector(it) }
            )
        )
        val logoutItems = listOf(
            MenuItem(
                getString(R.string.setting_log_out),
                R.drawable.ic_door_exit_icon,
                null,
                false,
                true,
                { confirmLogout() }
            )
        )
        return listOf(
            MenuSection(getString(R.string.setting_account_title), accountItems),
            MenuSection(getString(R.string.setting_app_title), appItems),
            MenuSection(null, logoutItems)
        )
    }

    private fun languagePreviewValue(): String {
        val tag = userController.languageTag
        if (tag.isEmpty()) return LocaleManager.ENGLISH
        val parts = tag.split("-", "_")
        return parts.firstOrNull()?.lowercase() ?: LocaleManager.ENGLISH
    }

    private fun applySearchBarBackground() {
        if (!::searchBarWrap.isInitialized) return
        searchBarWrap.background = GradientDrawable().apply {
            cornerRadius = LayoutHelper.dp(12f).toFloat()
            setColor(themeColors.getColor(ThemeColors.key_sheetItemBackground))
        }
    }

    private fun applySearchBarIconTint() {
        if (!::searchBarIconView.isInitialized) return
        searchBarIconView.drawable?.mutate()?.colorFilter =
            PorterDuffColorFilter(themeColors.onSurface, PorterDuff.Mode.SRC_IN)
    }

    private fun buildSearchBar(context: Context) {
        val wrap = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                LayoutHelper.dp(12),
                LayoutHelper.dp(SEARCH_BAR_PADDING_V_DP),
                LayoutHelper.dp(12),
                LayoutHelper.dp(SEARCH_BAR_PADDING_V_DP)
            )
        }
        searchBarWrap = wrap
        applySearchBarBackground()
        searchBarIconView = ImageView(context).apply {
            setImageDrawable(MezonIcon.magnifyingIcon.getDrawable(context))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        applySearchBarIconTint()
        wrap.addView(searchBarIconView, LinearLayout.LayoutParams(LayoutHelper.dp(18), LayoutHelper.dp(18)))
        searchEditText = EditText(context).apply {
            hint = getString(R.string.common_search_placeholder)
            setHintTextColor(themeColors.onSurfaceVariant)
            setTextColor(themeColors.onSurface)
            background = null
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 1
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(LayoutHelper.dp(10), 0, 0, 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    debounceRunnable?.let { debounceHandler.removeCallbacks(it) }
                    val q = s?.toString()?.trim() ?: ""
                    debounceRunnable = Runnable { applySearchQuery(q) }
                    debounceHandler.postDelayed(debounceRunnable!!, DEBOUNCE_MS)
                }
            })
        }
        wrap.addView(searchEditText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun applySearchQuery(query: String) {
        menuContainer.removeAllViews()
        val q = query.lowercase()
        val sections = if (q.isEmpty()) {
            fullMenu
        } else {
            val matched = ArrayList<MenuItem>()
            for (section in fullMenu) {
                for (item in section.items) {
                    val t = item.title.lowercase()
                    if (t.startsWith(q) || t.contains(" $q")) {
                        matched.add(item)
                    }
                }
            }
            listOf(MenuSection(null, matched))
        }
        for ((index, section) in sections.withIndex()) {
            section.title?.let { title ->
                val header = HeaderCell(requireContext(), themeColors).apply {
                    setText(title)
                    setSideMargin(0)
                    setTopPadding(if (index == 0) 0 else SECTION_HEADER_TOP_DP)
                }
                menuContainer.addView(header, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))
            }
            val card = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                val bg = GradientDrawable().apply {
                    cornerRadius = LayoutHelper.dp(12f).toFloat()
                    setColor(themeColors.getColor(ThemeColors.key_sheetItemBackground))
                }
                background = bg
            }
            section.items.forEachIndexed { i, item ->
                card.addView(createRow(requireContext(), item, i < section.items.lastIndex))
            }
            val cardLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            if (q.isEmpty() && section.title == null && section.items.size == 1 && section.items[0].isDestructive) {
                cardLp.topMargin = LayoutHelper.dp(LOGOUT_CARD_TOP_MARGIN_DP)
            }
            menuContainer.addView(card, cardLp)
        }
    }

    private fun createRow(context: Context, item: MenuItem, showDivider: Boolean): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val padV = LayoutHelper.dp(ROW_PADDING_V_DP)
            setPadding(LayoutHelper.dp(ROW_PADDING_H_DP), padV, LayoutHelper.dp(ROW_PADDING_H_DP), padV)
            isClickable = item.onClick != null
            isFocusable = item.onClick != null
            if (item.onClick != null) {
                val outValue = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                foreground = androidx.core.content.ContextCompat.getDrawable(context, outValue.resourceId)
                setOnClickListener { v -> item.onClick?.invoke(v) }
            }
        }
        val iconSize = LayoutHelper.dp(SETTINGS_ICON_SIZE_DP)
        val iconView = ImageView(context).apply {
            setImageResource(item.iconResId)
            if (item.isDestructive) {
                colorFilter = PorterDuffColorFilter(themeColors.error, PorterDuff.Mode.SRC_IN)
            }
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        row.addView(iconView, LinearLayout.LayoutParams(iconSize, iconSize))
        val label = TextView(context).apply {
            text = item.title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_TEXT_SP)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (item.isDestructive) themeColors.error else themeColors.onSurface)
            setPadding(LayoutHelper.dp(ICON_TEXT_GAP_DP), 0, 0, 0)
        }
        row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val valueText = item.value
        if (valueText != null) {
            val valueView = TextView(context).apply {
                text = valueText
                setTextSize(TypedValue.COMPLEX_UNIT_SP, VALUE_TEXT_SP)
                setTextColor(themeColors.onSurfaceVariant)
            }
            row.addView(valueView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        if (item.showChevron) {
            val chevron = ImageView(context).apply {
                setImageResource(MezonIcon.chevronSmallRightIcon.resId)
                colorFilter = PorterDuffColorFilter(themeColors.onSurfaceVariant, PorterDuff.Mode.SRC_IN)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
            row.addView(chevron, LinearLayout.LayoutParams(LayoutHelper.dp(18), LayoutHelper.dp(18)).apply {
                leftMargin = LayoutHelper.dp(6)
            })
        }
        if (!showDivider) return row
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(row)
        val divider = View(context).apply {
            setBackgroundColor(themeColors.getColor(ThemeColors.key_divider))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LayoutHelper.dp(1)
            )
        }
        container.addView(divider)
        return container
    }

    private fun openAccountSettings() {
        presentFragment(AccountSettingFragment().apply {
            onNavigateUpdateEmail = { presentFragment(UpdateEmailFragment.newInstance(it)) }
            onNavigateUpdatePhone = { presentFragment(UpdatePhoneFragment.newInstance(it)) }
            onNavigateBlockedUsers = { presentFragment(BlockedUsersFragment()) }
        })
    }

    private fun showThemeSelector(anchor: View) {
        val popup = SelectPopup(anchor.context, themeColors)
        val entries = ThemeMode.entries
        val items = entries.map { getThemeDisplayName(it) }
        popup.setItems(items, entries.indexOf(userController.themeMode))
        popup.setOnItemSelectedListener { userController.applyTheme(entries[it]) }
        popup.show(anchor)
    }

    private fun showLanguageSelector(@Suppress("UNUSED_PARAMETER") anchor: View) {
        presentFragment(LanguageSettingFragment())
    }

    private fun getThemeDisplayName(mode: ThemeMode): String = when (mode) {
        ThemeMode.LIGHT -> getString(R.string.setting_theme_light)
        ThemeMode.DARK -> getString(R.string.setting_theme_dark)
        ThemeMode.ABYSS -> getString(R.string.setting_theme_abyss)
        ThemeMode.SYSTEM -> getString(R.string.setting_theme_system)
    }

    private fun confirmLogout() {
        AlertsCreator.createConfirmDialog(
            requireContext(),
            getString(R.string.setting_log_out),
            getString(R.string.setting_log_out_description),
            confirmText = getString(R.string.setting_log_out_yes),
            cancelText = getString(R.string.setting_log_out_no),
            destructive = true
        ) {
            fragmentScope.launch(Dispatchers.Main) {
                authRepository.logout()
                onLogout?.invoke()
            }
        }.show()
    }
}
