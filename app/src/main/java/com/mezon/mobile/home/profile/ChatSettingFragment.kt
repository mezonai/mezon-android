package com.mezon.mobile.home.profile

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.session.SessionManager
import com.mezon.mobile.ui.MezonToast
import com.mezon.mobile.ui.cells.SwitchView
import com.mezon.mobile.ui.cells.ToastOverlay
import com.mezon.mobile.util.NetworkErrorMessages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatSettingFragment : BaseFragment() {

    private lateinit var api: MezonApi
    private lateinit var sessionManager: SessionManager
    private lateinit var ioDispatcher: CoroutineDispatcher

    private lateinit var root: LinearLayout
    private var onlyFriendsSwitch: SwitchView? = null
    private var onlyFriendsDm = false
    private var isDmSettingLoaded = false
    private var dmSettingGeneration = 0

    override fun onInject(entryPoint: FragmentEntryPoint) {
        api = entryPoint.mezonApi()
        sessionManager = entryPoint.sessionManager()
        ioDispatcher = entryPoint.ioDispatcher()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        observe(NotificationCenter.themeChanged) { _, _, _ ->
            if (fragmentView == null) return@observe
            fragmentView?.setBackgroundColor(themeColors.background)
            rebuildContent()
        }
        observe(NotificationCenter.languageChanged) { _, _, _ ->
            if (fragmentView == null) return@observe
            rebuildContent()
        }
        return true
    }

    override fun createView(context: Context): View {
        root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(16), LayoutHelper.dp(8), LayoutHelper.dp(16), LayoutHelper.dp(16))
        }
        rebuildContent()
        loadDmSetting()
        return wrapWithActionBar(getString(R.string.setting_app_chat), root)
    }

    private fun rebuildContent() {
        if (!::root.isInitialized) return
        root.removeAllViews()
        root.setBackgroundColor(themeColors.background)
        val context = root.context

        val switchView = SwitchView(context, themeColors).apply {
            setChecked(onlyFriendsDm, animated = false)
            onCheckedChange = { onOnlyFriendsDmChanged(it) }
        }
        setSwitchEnabled(switchView, isDmSettingLoaded)
        onlyFriendsSwitch = switchView

        val title = TextView(context).apply {
            text = getString(R.string.setting_chat_only_friends_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(themeColors.onSurface)
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = LayoutHelper.dp(56)
            setPadding(LayoutHelper.dp(16), LayoutHelper.dp(12), LayoutHelper.dp(16), LayoutHelper.dp(12))
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(12f).toFloat()
                setColor(themeColors.getColor(ThemeColors.key_sheetItemBackground))
            }
            setOnClickListener { if (switchView.isEnabled) switchView.performClick() }
        }
        row.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(switchView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = LayoutHelper.dp(12) })
        root.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val description = TextView(context).apply {
            text = getString(R.string.setting_chat_only_friends_description)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(themeColors.onSurfaceVariant)
            setPadding(0, LayoutHelper.dp(8), 0, 0)
        }
        root.addView(description, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
    }

    private fun loadDmSetting() {
        val generation = dmSettingGeneration
        fragmentScope.launch(Dispatchers.Main.immediate) {
            try {
                val setting = withContext(ioDispatcher) {
                    sessionManager.withAutoRefresh { session -> api.getDmSetting(session.apiUrl, session.token) }
                }
                if (isFinished || generation != dmSettingGeneration) return@launch
                onlyFriendsDm = setting.onlyFriendsDm
                isDmSettingLoaded = true
                onlyFriendsSwitch?.let {
                    it.setChecked(onlyFriendsDm, animated = false)
                    setSwitchEnabled(it, true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isFinished) return@launch
                showError(NetworkErrorMessages.userMessage(requireContext(), e))
            }
        }
    }

    private fun onOnlyFriendsDmChanged(enabled: Boolean) {
        val previous = onlyFriendsDm
        onlyFriendsDm = enabled
        dmSettingGeneration++
        val generation = dmSettingGeneration
        fragmentScope.launch(Dispatchers.Main.immediate) {
            try {
                val setting = withContext(ioDispatcher) {
                    sessionManager.withAutoRefresh { session ->
                        api.updateDmSetting(session.apiUrl, session.token, enabled)
                    }
                }
                if (isFinished || generation != dmSettingGeneration) return@launch
                if (setting.onlyFriendsDm != enabled) {
                    revertOnlyFriendsDm(previous)
                    showError(getString(R.string.setting_chat_only_friends_update_failed))
                    return@launch
                }
                onlyFriendsDm = setting.onlyFriendsDm
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isFinished || generation != dmSettingGeneration) return@launch
                revertOnlyFriendsDm(previous)
                showError(NetworkErrorMessages.userMessage(requireContext(), e))
            }
        }
    }

    private fun revertOnlyFriendsDm(value: Boolean) {
        onlyFriendsDm = value
        onlyFriendsSwitch?.setChecked(value)
    }

    private fun setSwitchEnabled(switchView: SwitchView, enabled: Boolean) {
        switchView.isEnabled = enabled
        switchView.alpha = if (enabled) 1f else 0.45f
    }

    private fun showError(message: String) {
        MezonToast.show(this, ToastOverlay.ToastType.ERROR, message)
    }
}
