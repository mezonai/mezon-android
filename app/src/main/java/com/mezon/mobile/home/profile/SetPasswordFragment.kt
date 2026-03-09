package com.mezon.mobile.home.profile

import android.os.Bundle
import android.text.InputType
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
class SetPasswordFragment : BaseFragment() {

    @Inject lateinit var accountController: AccountController

    private lateinit var emailCell: InputCell
    private lateinit var currentPasswordCell: InputCell
    private lateinit var currentPasswordSection: LinearLayout
    private lateinit var newPasswordCell: InputCell
    private lateinit var confirmPasswordCell: InputCell
    private lateinit var saveButton: ActionButton
    private lateinit var loadingView: View

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.background)
            val pad = LayoutHelper.dp(16)
            setPadding(pad, pad, pad, pad)
        }

        emailCell = InputCell(requireContext(), themeColors).apply {
            setLabel(getString(R.string.set_password_email_label))
            setHint("")
            isEnabled = false
        }
        content.addView(emailCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0, 0f, 0f, 0f, 16f))

        currentPasswordSection = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        currentPasswordCell = InputCell(requireContext(), themeColors).apply {
            setLabel(getString(R.string.set_password_current_label))
            setHint(getString(R.string.set_password_current_hint))
            editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        currentPasswordSection.addView(currentPasswordCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0, 0f, 0f, 0f, 12f))
        content.addView(currentPasswordSection, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        newPasswordCell = InputCell(requireContext(), themeColors).apply {
            setLabel(getString(R.string.set_password_new_label))
            setHint(getString(R.string.set_password_new_hint))
            editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        content.addView(newPasswordCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0, 0f, 0f, 0f, 4f))

        val descText = TextView(requireContext()).apply {
            text = getString(R.string.set_password_description)
            textSize = 12f
            setTextColor(themeColors.onSurfaceVariant)
        }
        content.addView(descText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0, 0f, 0f, 0f, 16f))

        confirmPasswordCell = InputCell(requireContext(), themeColors).apply {
            setLabel(getString(R.string.set_password_confirm_label))
            setHint(getString(R.string.set_password_confirm_hint))
            editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        content.addView(confirmPasswordCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0, 0f, 0f, 0f, 24f))

        val spacer = View(requireContext())
        content.addView(spacer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        saveButton = ActionButton(requireContext(), themeColors).apply {
            setText(getString(R.string.set_password_save))
        }
        content.addView(saveButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48))

        val rootFrame = FrameLayout(requireContext())
        rootFrame.addView(wrapWithActionBar(getString(R.string.set_password_title), content), LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))
        loadingView = View(requireContext()).apply {
            setBackgroundColor(0x88000000.toInt())
            visibility = View.GONE
        }
        rootFrame.addView(loadingView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))
        return rootFrame
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val info = accountController.accountInfo.value
        emailCell.setText(info.email)
        currentPasswordSection.visibility = if (info.passwordSetted) View.VISIBLE else View.GONE

        observe(NotificationCenter.accountInfoLoaded) { _, _ ->
            val updated = accountController.accountInfo.value
            emailCell.setText(updated.email)
            currentPasswordSection.visibility = if (updated.passwordSetted) View.VISIBLE else View.GONE
        }
        observe(NotificationCenter.themeChanged) { _, _ ->
            view.setBackgroundColor(themeColors.background)
        }

        wireValidation()
        saveButton.setOnClickListener { handleSave() }

        if (info.email.isEmpty()) accountController.loadAccount()
    }

    private fun wireValidation() {
        newPasswordCell.onTextChanged = { pw ->
            val error = validatePassword(pw)
            val sameAsCurrent = accountController.accountInfo.value.passwordSetted
                && currentPasswordCell.getText().isNotEmpty()
                && pw == currentPasswordCell.getText()
            newPasswordCell.setError(when {
                sameAsCurrent -> getString(R.string.set_password_error_same)
                error.isNotEmpty() -> error
                else -> null
            })
            val confirmPw = confirmPasswordCell.getText()
            if (confirmPw.isNotEmpty()) {
                confirmPasswordCell.setError(if (pw != confirmPw) getString(R.string.set_password_error_not_equal) else null)
            }
        }

        confirmPasswordCell.onTextChanged = { confirm ->
            val pw = newPasswordCell.getText()
            confirmPasswordCell.setError(
                if (confirm.isNotEmpty() && confirm != pw) getString(R.string.set_password_error_not_equal) else null
            )
        }
    }

    private fun validatePassword(value: String): String {
        return when {
            value.length < 8 -> getString(R.string.set_password_error_min_chars)
            !value.any { it.isUpperCase() } -> getString(R.string.set_password_error_uppercase)
            !value.any { it.isLowerCase() } -> getString(R.string.set_password_error_lowercase)
            !value.any { it.isDigit() } -> getString(R.string.set_password_error_number)
            !value.any { !it.isLetterOrDigit() } -> getString(R.string.set_password_error_symbol)
            else -> ""
        }
    }

    private fun handleSave() {
        val info = accountController.accountInfo.value
        val currentPw = currentPasswordCell.getText()
        val newPw = newPasswordCell.getText()
        val confirmPw = confirmPasswordCell.getText()

        val pwError = validatePassword(newPw)
        val confirmError = if (newPw != confirmPw) getString(R.string.set_password_error_not_equal) else ""
        val sameError = if (info.passwordSetted && currentPw.isNotEmpty() && newPw == currentPw)
            getString(R.string.set_password_error_same) else ""

        newPasswordCell.setError(sameError.ifEmpty { pwError }.ifEmpty { null })
        confirmPasswordCell.setError(confirmError.ifEmpty { null })
        if (sameError.isNotEmpty() || pwError.isNotEmpty() || confirmError.isNotEmpty()) return

        loadingView.visibility = View.VISIBLE
        saveButton.isEnabled = false

        accountController.setPassword(info.email, newPw, if (info.passwordSetted) currentPw else "") { success, errorMsg ->
            loadingView.visibility = View.GONE
            saveButton.isEnabled = true
            if (success) {
                AlertsCreator.showSimpleAlert(
                    requireContext(),
                    getString(R.string.set_password_success_title),
                    getString(R.string.set_password_success_message)
                ) { requireActivity().supportFragmentManager.popBackStack() }
            } else {
                AlertsCreator.showSimpleAlert(
                    requireContext(),
                    getString(R.string.common_error),
                    errorMsg.ifEmpty { getString(R.string.set_password_error_failed) }
                )
            }
        }
    }
}
