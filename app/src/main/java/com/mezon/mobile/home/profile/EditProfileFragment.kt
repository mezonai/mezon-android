package com.mezon.mobile.home.profile

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.AlertsCreator
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.ui.cells.ActionButton
import com.mezon.mobile.ui.cells.InputCell

class EditProfileFragment : BaseFragment() {

    private lateinit var accountController: AccountController
    private lateinit var userController: UserController

    var onSaved: (() -> Unit)? = null

    private lateinit var displayNameCell: InputCell
    private lateinit var aboutMeCell: InputCell
    private lateinit var saveButton: ActionButton
    private lateinit var loadingView: View

    override fun onInject(entryPoint: FragmentEntryPoint) {
        accountController = entryPoint.accountController()
        userController = entryPoint.userController()
    }

    override fun createView(context: Context): View {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.background)
            val pad = LayoutHelper.dp(16)
            setPadding(pad, pad, pad, pad)
        }

        val info = accountController.accountInfo.value
        val username = info.username.ifEmpty { userController.username }

        val usernameCell = InputCell(context, themeColors).apply {
            setLabel(getString(R.string.account_username))
            setText(username)
            isEnabled = false
        }
        content.addView(usernameCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0, 0f, 0f, 0f, 4f))

        val usernameHint = TextView(context).apply {
            text = getString(R.string.edit_profile_username_hint)
            textSize = 11f
            setTextColor(themeColors.onSurfaceVariant)
        }
        content.addView(usernameHint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0, 0f, 0f, 0f, 16f))

        displayNameCell = InputCell(context, themeColors).apply {
            setLabel(getString(R.string.account_display_name))
            setHint(getString(R.string.edit_profile_display_name_hint))
            setText(info.displayName.ifEmpty { userController.displayName })
        }
        content.addView(displayNameCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0, 0f, 0f, 0f, 16f))

        aboutMeCell = InputCell(context, themeColors).apply {
            setLabel(getString(R.string.edit_profile_about_me))
            setHint(getString(R.string.edit_profile_about_me_hint))
            setTextarea(true, 200)
            editText.gravity = Gravity.TOP or Gravity.START
        }
        content.addView(aboutMeCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0, 0f, 0f, 0f, 24f))

        content.addView(View(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        saveButton = ActionButton(context, themeColors).apply {
            setText(getString(R.string.edit_profile_save))
        }
        content.addView(saveButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48))

        val rootFrame = FrameLayout(context)
        rootFrame.addView(wrapWithActionBar(getString(R.string.edit_profile_title), content), LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))
        loadingView = View(context).apply {
            setBackgroundColor(0x88000000.toInt())
            visibility = View.GONE
        }
        rootFrame.addView(loadingView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        saveButton.setOnClickListener { handleSave() }

        if (accountController.accountInfo.value.username.isEmpty()) accountController.loadAccount()

        return rootFrame
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()

        observe(NotificationCenter.accountInfoLoaded) { _, _, _ ->
            if (fragmentView == null) return@observe
            val updatedInfo = accountController.accountInfo.value
            if (displayNameCell.getText().isEmpty()) displayNameCell.setText(updatedInfo.displayName)
        }
        observe(NotificationCenter.themeChanged) { _, _, _ ->
            fragmentView?.setBackgroundColor(themeColors.background)
        }

        return true
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
                finishFragment()
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
