package com.mezon.mobile.home.profile

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.ui.cells.ActionButton
import com.mezon.mobile.ui.cells.InputCell
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

private const val OTP_COOLDOWN_SECONDS = 60
private const val ARG_CURRENT_EMAIL = "current_email"

@AndroidEntryPoint
class UpdateEmailFragment : BaseFragment() {

    companion object {
        fun newInstance(currentEmail: String): UpdateEmailFragment {
            return UpdateEmailFragment().apply {
                arguments = Bundle().apply { putString(ARG_CURRENT_EMAIL, currentEmail) }
            }
        }
    }

    @Inject lateinit var accountController: AccountController

    var onEmailVerified: (() -> Unit)? = null

    private val currentEmail get() = arguments?.getString(ARG_CURRENT_EMAIL) ?: ""

    private lateinit var emailCell: InputCell
    private lateinit var nextButton: ActionButton
    private lateinit var loadingView: View

    private val cooldownHandler = Handler(Looper.getMainLooper())
    private var cooldownRunnable: Runnable? = null
    private var remainingSeconds = 0
    private val otpCooldownCache = mutableMapOf<String, Long>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.background)
            val pad = LayoutHelper.dp(16)
            setPadding(pad, pad, pad, pad)
        }

        emailCell = InputCell(requireContext(), themeColors).apply {
            setLabel(getString(R.string.email_new_email_label))
            setHint(getString(R.string.email_new_email_label))
            editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            if (currentEmail.isNotEmpty()) setText(currentEmail)
        }
        content.addView(emailCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0, 0f, 0f, 0f, 24f))

        val spacer = View(requireContext())
        content.addView(spacer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        nextButton = ActionButton(requireContext(), themeColors).apply {
            setText(getString(R.string.email_next_button))
            isEnabled = false
        }
        content.addView(nextButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48))

        val rootFrame = FrameLayout(requireContext())
        rootFrame.addView(wrapWithActionBar(getString(R.string.update_email_title), content), LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))
        loadingView = View(requireContext()).apply {
            setBackgroundColor(0x88000000.toInt())
            visibility = View.GONE
        }
        rootFrame.addView(loadingView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))
        return rootFrame
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        emailCell.onTextChanged = { text ->
            val isValid = text.isEmpty() || Patterns.EMAIL_ADDRESS.matcher(text).matches()
            emailCell.setError(if (!isValid && text.isNotEmpty()) getString(R.string.email_invalid) else null)
            nextButton.isEnabled = isValid && text.isNotEmpty()
        }

        nextButton.setOnClickListener { handleNext() }

        observe(NotificationCenter.themeChanged) { _, _ ->
            view.setBackgroundColor(themeColors.background)
        }
    }

    private fun handleNext() {
        val email = emailCell.getText().trim()
        if (email == currentEmail && email.isNotEmpty()) {
            emailCell.setError(getString(R.string.email_already_linked))
            return
        }

        val lastSent = otpCooldownCache[email] ?: 0L
        val elapsed = ((System.currentTimeMillis() - lastSent) / 1000).toInt()
        if (elapsed < OTP_COOLDOWN_SECONDS) {
            emailCell.setError(getString(R.string.email_too_fast, OTP_COOLDOWN_SECONDS - elapsed))
            return
        }

        loadingView.visibility = View.VISIBLE
        nextButton.isEnabled = false

        accountController.linkEmail(email) { success, reqId, errorMsg ->
            loadingView.visibility = View.GONE
            if (success) {
                otpCooldownCache[email] = System.currentTimeMillis()
                navigateToVerify(email, reqId, isPhone = false)
            } else {
                emailCell.setError(errorMsg.ifEmpty { getString(R.string.email_link_failed) })
                nextButton.isEnabled = true
            }
        }
    }

    private fun navigateToVerify(contact: String, reqId: String, isPhone: Boolean) {
        val fragment = VerifyOtpFragment.newInstance(contact, reqId, isPhone)
        fragment.onVerified = { onEmailVerified?.invoke() }
        requireActivity().supportFragmentManager.beginTransaction()
            .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out, android.R.anim.fade_in, android.R.anim.fade_out)
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cooldownRunnable?.let { cooldownHandler.removeCallbacks(it) }
    }
}
