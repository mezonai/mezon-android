package com.mezon.mobile.home.profile

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.AlertsCreator
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.ui.cells.ActionButton
import com.mezon.mobile.ui.cells.InputCell
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class EditProfileFragment : BaseFragment() {

    @Inject lateinit var accountController: AccountController
    @Inject lateinit var userController: UserController

    var onSaved: (() -> Unit)? = null

    private lateinit var displayNameCell: InputCell
    private lateinit var aboutMeCell: InputCell
    private lateinit var saveButton: ActionButton
    private lateinit var loadingView: View

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.background)
            val pad = LayoutHelper.dp(16)
            setPadding(pad, pad, pad, pad)
        }

        val info = accountController.accountInfo.value
        val username = info.username.ifEmpty { userController.username }

        val usernameCell = InputCell(requireContext(), themeColors).apply {
            setLabel(getString(R.string.account_username))
            setText(username)
            isEnabled = false
        }
        content.addView(usernameCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0, 0f, 0f, 0f, 4f))

        val usernameHint = TextView(requireContext()).apply {
            text = getString(R.string.edit_profile_username_hint)
            textSize = 11f
            setTextColor(themeColors.onSurfaceVariant)
        }
        content.addView(usernameHint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0, 0f, 0f, 0f, 16f))

        displayNameCell = InputCell(requireContext(), themeColors).apply {
            setLabel(getString(R.string.account_display_name))
            setHint(getString(R.string.edit_profile_display_name_hint))
            setText(info.displayName.ifEmpty { userController.displayName })
        }
        content.addView(displayNameCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0, 0f, 0f, 0f, 16f))

        aboutMeCell = InputCell(requireContext(), themeColors).apply {
            setLabel(getString(R.string.edit_profile_about_me))
            setHint(getString(R.string.edit_profile_about_me_hint))
            setTextarea(true, 200)
            editText.gravity = Gravity.TOP or Gravity.START
        }
        content.addView(aboutMeCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0, 0f, 0f, 0f, 24f))

        val spacer = View(requireContext())
        content.addView(spacer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        saveButton = ActionButton(requireContext(), themeColors).apply {
            setText(getString(R.string.edit_profile_save))
        }
        content.addView(saveButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48))

        val rootFrame = FrameLayout(requireContext())
        rootFrame.addView(wrapWithActionBar(getString(R.string.edit_profile_title), content), LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))
        loadingView = View(requireContext()).apply {
            setBackgroundColor(0x88000000.toInt())
            visibility = View.GONE
        }
        rootFrame.addView(loadingView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))
        return rootFrame
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        observe(NotificationCenter.accountInfoLoaded) { _, _ ->
            val info = accountController.accountInfo.value
            if (displayNameCell.getText().isEmpty()) displayNameCell.setText(info.displayName)
        }
        observe(NotificationCenter.themeChanged) { _, _ ->
            view.setBackgroundColor(themeColors.background)
        }

        saveButton.setOnClickListener { handleSave() }

        if (accountController.accountInfo.value.username.isEmpty()) accountController.loadAccount()
    }

    private fun handleSave() {
        val displayName = displayNameCell.getText().trim()
        val aboutMe = aboutMeCell.getText().trim()

        loadingView.visibility = View.VISIBLE
        saveButton.isEnabled = false

        accountController.updateProfile(displayName = displayName, avatarUrl = "", aboutMe = aboutMe) { success, errorMsg ->
            loadingView.visibility = View.GONE
            saveButton.isEnabled = true
            if (success) {
                userController.updateFromAccount(accountController.accountInfo.value)
                requireActivity().supportFragmentManager.popBackStack()
                onSaved?.invoke()
            } else {
                AlertsCreator.showSimpleAlert(
                    requireContext(),
                    getString(R.string.common_error),
                    errorMsg.ifEmpty { getString(R.string.edit_profile_save_error) }
                )
            }
        }
    }
}
