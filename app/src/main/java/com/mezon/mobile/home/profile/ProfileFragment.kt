package com.mezon.mobile.home.profile

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.R
import com.mezon.mobile.auth.AuthRepository
import com.mezon.mobile.core.AlertsCreator
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.RecyclerListView
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.session.LocaleManager
import com.mezon.mobile.ui.cells.HeaderCell
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.cells.ProfileHeaderCell
import com.mezon.mobile.ui.cells.SelectPopup
import com.mezon.mobile.ui.cells.ShadowSectionCell
import com.mezon.mobile.ui.cells.TextSettingsCell
import com.mezon.mobile.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ProfileFragment : BaseFragment() {

    companion object {
        private const val VIEW_TYPE_PROFILE_HEADER = 0
        private const val VIEW_TYPE_HEADER = 1
        private const val VIEW_TYPE_TEXT_SETTINGS = 2
        private const val VIEW_TYPE_SHADOW = 3
    }

    private lateinit var userController: UserController
    private lateinit var authRepository: AuthRepository

    var onLogout: (() -> Unit)? = null

    private var rowCount = 0
    private var profileHeaderRow = -1
    private var profileShadowRow = -1
    private var settingsHeaderRow = -1
    private var accountRow = -1
    private var themeRow = -1
    private var languageRow = -1
    private var settingsShadowRow = -1
    private var developerHeaderRow = -1
    private var componentPreviewRow = -1
    private var developerShadowRow = -1
    private var logoutRow = -1
    private var logoutShadowRow = -1

    private lateinit var listView: RecyclerListView
    private lateinit var listAdapter: ListAdapter

    override fun onInject(entryPoint: FragmentEntryPoint) {
        userController = entryPoint.userController()
        authRepository = entryPoint.authRepository()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()

        observe(NotificationCenter.userDataLoaded) { _, _, _ ->
            if (fragmentView == null || isPaused) return@observe
            updateRows()
        }
        observe(NotificationCenter.updateInterfaces) { _, _, args ->
            if (fragmentView == null || isPaused) return@observe
            val mask = args.firstOrNull() as? Int ?: 0
            updateVisibleRows(mask)
        }
        observe(NotificationCenter.themeChanged) { _, _, _ ->
            if (fragmentView == null) return@observe
            fragmentView?.setBackgroundColor(themeColors.background)
            listAdapter.notifyDataSetChanged()
        }
        observe(NotificationCenter.languageChanged) { _, _, _ ->
            if (fragmentView == null || isPaused) return@observe
            if (languageRow >= 0) listAdapter.notifyItemChanged(languageRow)
        }

        return true
    }

    override fun createView(context: Context): View {
        val root = FrameLayout(context).apply {
            setBackgroundColor(themeColors.background)
        }

        listAdapter = ListAdapter()
        listView = RecyclerListView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = listAdapter
            overScrollMode = View.OVER_SCROLL_NEVER
            setOnItemClickListener(RecyclerListView.OnItemClickListener { view, position ->
                onItemClick(view, position)
            })
        }
        root.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        return root
    }

    override fun onBecomeFullyVisible() {
        super.onBecomeFullyVisible()
        if (userController.userIdStr.isNotEmpty()) updateRows()
    }

    private fun updateVisibleRows(mask: Int) {
        if (isPaused) return
        if (mask == 0) {
            updateRows()
            return
        }
        if ((mask and NotificationCenter.UPDATE_MASK_NAME) != 0 ||
            (mask and NotificationCenter.UPDATE_MASK_AVATAR) != 0
        ) {
            if (profileHeaderRow >= 0) listAdapter.notifyItemChanged(profileHeaderRow)
        }
    }

    private fun updateRows() {
        rowCount = 0
        profileHeaderRow = rowCount++
        profileShadowRow = rowCount++
        settingsHeaderRow = rowCount++
        accountRow = rowCount++
        themeRow = rowCount++
        languageRow = rowCount++
        settingsShadowRow = rowCount++
        developerHeaderRow = rowCount++
        componentPreviewRow = rowCount++
        developerShadowRow = rowCount++
        logoutRow = rowCount++
        logoutShadowRow = rowCount++
        listAdapter.notifyDataSetChanged()
    }

    private fun onItemClick(view: View, position: Int) {
        when (position) {
            accountRow -> openAccountSetting()
            themeRow -> showThemeSelector(view)
            languageRow -> showLanguageSelector(view)
            componentPreviewRow -> presentFragment(ComponentPreviewFragment())
            logoutRow -> confirmLogout()
        }
    }

    private fun openAccountSetting() {
        val fragment = AccountSettingFragment().apply {
            onNavigateUpdateEmail = { currentEmail ->
                presentFragment(UpdateEmailFragment.newInstance(currentEmail))
            }
            onNavigateUpdatePhone = { currentPhone ->
                presentFragment(UpdatePhoneFragment.newInstance(currentPhone))
            }
            onNavigateBlockedUsers = {
                presentFragment(BlockedUsersFragment())
            }
        }
        presentFragment(fragment)
    }

    private fun showThemeSelector(anchor: View) {
        val popup = SelectPopup(anchor.context, themeColors)
        val entries = ThemeMode.entries
        val items = entries.map { getThemeDisplayName(it) }
        val currentName = getThemeDisplayName(userController.themeMode)
        popup.setItems(items, items.indexOf(currentName))
        popup.setOnItemSelectedListener { index -> userController.applyTheme(entries[index]) }
        popup.show(anchor)
    }

    private fun showLanguageSelector(anchor: View) {
        val popup = SelectPopup(anchor.context, themeColors)
        val items = listOf(getString(R.string.setting_language_english), getString(R.string.setting_language_vietnamese))
        val tags = listOf(LocaleManager.ENGLISH, LocaleManager.VIETNAMESE)
        val currentIndex = tags.indexOf(userController.languageTag).let { if (it < 0) 0 else it }
        popup.setItems(items, currentIndex)
        popup.setOnItemSelectedListener { index -> userController.applyLanguage(tags[index]) }
        popup.show(anchor)
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
                notificationCenter.postNotificationOnMainThread(NotificationCenter.sessionExpired)
            }
        }.show()
    }

    private fun getThemeDisplayName(mode: ThemeMode): String = when (mode) {
        ThemeMode.LIGHT -> getString(R.string.setting_theme_light)
        ThemeMode.DARK -> getString(R.string.setting_theme_dark)
        ThemeMode.ABYSS -> getString(R.string.setting_theme_abyss)
        ThemeMode.SYSTEM -> getString(R.string.setting_theme_system)
    }

    private fun getLanguageDisplayName(tag: String): String = when (tag) {
        LocaleManager.ENGLISH -> getString(R.string.setting_language_english)
        LocaleManager.VIETNAMESE -> getString(R.string.setting_language_vietnamese)
        else -> tag
    }

    private inner class ListAdapter : RecyclerListView.SelectionAdapter() {

        override fun isEnabled(holder: RecyclerView.ViewHolder): Boolean {
            val type = getItemViewType(holder.adapterPosition)
            return type == VIEW_TYPE_TEXT_SETTINGS
        }

        override fun getItemCount(): Int = rowCount

        override fun getItemViewType(position: Int): Int = when (position) {
            profileHeaderRow -> VIEW_TYPE_PROFILE_HEADER
            settingsHeaderRow, developerHeaderRow -> VIEW_TYPE_HEADER
            profileShadowRow, settingsShadowRow, developerShadowRow, logoutShadowRow -> VIEW_TYPE_SHADOW
            else -> VIEW_TYPE_TEXT_SETTINGS
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val ctx = parent.context
            val view: View = when (viewType) {
                VIEW_TYPE_PROFILE_HEADER -> ProfileHeaderCell(ctx, themeColors)
                VIEW_TYPE_HEADER -> HeaderCell(ctx, themeColors)
                VIEW_TYPE_TEXT_SETTINGS -> TextSettingsCell(ctx, themeColors)
                VIEW_TYPE_SHADOW -> ShadowSectionCell(ctx, themeColors)
                else -> View(ctx)
            }
            if (view.layoutParams == null) {
                view.layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                )
            }
            return object : RecyclerView.ViewHolder(view) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val userIdStr = if (userController.userId != 0L)
                getString(R.string.profile_user_id, userController.userIdStr)
            else ""

            when (getItemViewType(position)) {
                VIEW_TYPE_PROFILE_HEADER -> {
                    (holder.itemView as ProfileHeaderCell).setInfo(
                        userController.userId,
                        userController.userIdStr,
                        userIdStr
                    )
                }
                VIEW_TYPE_HEADER -> {
                    val cell = holder.itemView as HeaderCell
                    when (position) {
                        settingsHeaderRow -> cell.setText(getString(R.string.setting_app_title))
                        developerHeaderRow -> cell.setText("Developer")
                    }
                }
                VIEW_TYPE_TEXT_SETTINGS -> {
                    val cell = holder.itemView as TextSettingsCell
                    when (position) {
                        accountRow -> {
                            cell.setTextAndValue(getString(R.string.profile_account_settings), divider = true)
                            cell.setIcon(MezonIcon.settingIcon)
                            cell.setTitleColor(0)
                        }
                        themeRow -> {
                            cell.setTextAndValue(getString(R.string.setting_theme_title), getThemeDisplayName(userController.themeMode), divider = true)
                            cell.setIcon(MezonIcon.paintPaletteIcon)
                            cell.setTitleColor(0)
                        }
                        languageRow -> {
                            cell.setTextAndValue(getString(R.string.setting_app_language), getLanguageDisplayName(userController.languageTag))
                            cell.setIcon(MezonIcon.languageIcon)
                            cell.setTitleColor(0)
                        }
                        componentPreviewRow -> {
                            cell.setTextAndValue("Component Preview")
                            cell.setIcon(MezonIcon.settingIcon)
                            cell.setTitleColor(0)
                        }
                        logoutRow -> {
                            cell.setTextAndValue(getString(R.string.profile_sign_out))
                            cell.setIcon(MezonIcon.doorExitIcon)
                            cell.setTitleColor(themeColors.error)
                        }
                    }
                }
            }
        }
    }
}
