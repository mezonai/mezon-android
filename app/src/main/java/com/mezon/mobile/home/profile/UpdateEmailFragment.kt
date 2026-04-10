package com.mezon.mobile.home.profile

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Patterns
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.ui.cells.ActionButton
import com.mezon.mobile.ui.cells.InputCell
import com.mezon.mobile.ui.cells.ToastOverlay

private const val OTP_COOLDOWN_SECONDS = 60
private const val ARG_CURRENT_EMAIL = "current_email"
private const val OTP_CACHE_PREFS = "otp_cooldown_cache_email"

class UpdateEmailFragment : BaseFragment() {

    companion object {
        fun newInstance(currentEmail: String): UpdateEmailFragment {
            return UpdateEmailFragment().apply {
                arguments = android.os.Bundle().apply { putString(ARG_CURRENT_EMAIL, currentEmail) }
            }
        }
    }

    private lateinit var accountController: AccountController

    var onEmailVerified: (() -> Unit)? = null

    private val currentEmail get() = arguments?.getString(ARG_CURRENT_EMAIL) ?: ""

    private lateinit var emailCell: InputCell
    private lateinit var nextButton: ActionButton
    private lateinit var loadingView: View
    private lateinit var rootFrame: FrameLayout

    private val cooldownHandler = Handler(Looper.getMainLooper())
    private var cooldownRunnable: Runnable? = null
    private val otpCooldownCache = mutableMapOf<String, Long>()
    private var remainingTime = 0

    override fun onInject(entryPoint: FragmentEntryPoint) {
        accountController = entryPoint.accountController()
    }

    override fun createView(context: Context): View {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.background)
            val pad = LayoutHelper.dp(16)
            setPadding(pad, pad, pad, pad)
        }

        emailCell = InputCell(context, themeColors).apply {
            setLabel(getString(R.string.account_email))
            setHint(getString(R.string.common_login_enter_email_address))
            editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            if (currentEmail.isNotEmpty()) setText(currentEmail)
        }
        content.addView(emailCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0, 0f, 0f, 0f, 24f))
        content.addView(View(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        nextButton = ActionButton(context, themeColors).apply {
            setText(getString(R.string.email_next_button))
            isEnabled = false
        }
        content.addView(nextButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48))

        rootFrame = FrameLayout(context)
        rootFrame.addView(wrapWithActionBar(getString(R.string.update_email_title), content), LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))
        loadingView = View(context).apply {
            setBackgroundColor(0x88000000.toInt())
            visibility = View.GONE
            isClickable = true
        }
        rootFrame.addView(loadingView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        emailCell.onTextChanged = { text ->
            val trimmed = text.trim()
            val isValid = trimmed.isEmpty() || Patterns.EMAIL_ADDRESS.matcher(trimmed).matches()
            emailCell.setError(if (!isValid && trimmed.isNotEmpty()) getString(R.string.email_invalid) else null)
            updateButtonState(isValid && trimmed.isNotEmpty())
            updateCooldownForEmail(trimmed)
        }
        nextButton.setOnClickListener { handleNext() }

        loadCooldownCache()

        return rootFrame
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()

        observe(NotificationCenter.themeChanged) { _, _, _ ->
            fragmentView?.setBackgroundColor(themeColors.background)
        }

        return true
    }

    override fun onResume() {
        super.onResume()
        val text = emailCell.getText().trim()
        val isValid = text.isEmpty() || Patterns.EMAIL_ADDRESS.matcher(text).matches()
        emailCell.setError(if (!isValid && text.isNotEmpty()) getString(R.string.email_invalid) else null)
        updateCooldownForEmail(text)
    }

    override fun onFragmentDestroy() {
        stopCooldownTimer()
        saveCooldownCache()
        super.onFragmentDestroy()
    }

    private fun loadCooldownCache() {
        try {
            val prefs = requireContext().getSharedPreferences(OTP_CACHE_PREFS, Context.MODE_PRIVATE)
            val currentTime = System.currentTimeMillis()
            val allEntries = prefs.all
            val editor = prefs.edit()
            var hasChanges = false

            allEntries.forEach { (email, timestamp) ->
                val ts = timestamp as? Long ?: return@forEach
                val elapsed = ((currentTime - ts) / 1000).toInt()
                if (elapsed < OTP_COOLDOWN_SECONDS) {
                    otpCooldownCache[email] = ts
                } else {
                    editor.remove(email)
                    hasChanges = true
                }
            }

            if (hasChanges) editor.apply()

            if (currentEmail.isNotEmpty()) {
                updateCooldownForEmail(currentEmail)
            }
        } catch (_: Exception) {
        }
    }

    private fun saveCooldownCache() {
        try {
            val prefs = requireContext().getSharedPreferences(OTP_CACHE_PREFS, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            editor.clear()
            otpCooldownCache.forEach { (email, timestamp) ->
                editor.putLong(email, timestamp)
            }
            editor.apply()
        } catch (_: Exception) {
        }
    }

    private fun updateCooldownForEmail(email: String) {
        val lastSent = otpCooldownCache[email]
        if (lastSent != null) {
            val elapsed = ((System.currentTimeMillis() - lastSent) / 1000).toInt()
            val remaining = OTP_COOLDOWN_SECONDS - elapsed
            if (remaining > 0) {
                remainingTime = remaining
                startCooldownTimer(email)
                syncNextButtonWithField()
                return
            }
        }
        remainingTime = 0
        stopCooldownTimer()
        updateButtonText()
        syncNextButtonWithField()
    }

    private fun syncNextButtonWithField() {
        val text = emailCell.getText().trim()
        val ok = text.isEmpty() || Patterns.EMAIL_ADDRESS.matcher(text).matches()
        updateButtonState(ok && text.isNotEmpty())
    }

    private fun startCooldownTimer(email: String) {
        stopCooldownTimer()
        updateButtonText()

        cooldownRunnable = object : Runnable {
            override fun run() {
                val lastSent = otpCooldownCache[email] ?: run {
                    remainingTime = 0
                    updateButtonText()
                    syncNextButtonWithField()
                    return
                }
                val elapsed = ((System.currentTimeMillis() - lastSent) / 1000).toInt()
                val remaining = OTP_COOLDOWN_SECONDS - elapsed

                if (remaining <= 0) {
                    remainingTime = 0
                    updateButtonText()
                    cleanupExpiredEntries()
                    syncNextButtonWithField()
                } else {
                    remainingTime = remaining
                    updateButtonText()
                    cooldownHandler.postDelayed(this, 1000)
                }
            }
        }
        cooldownHandler.postDelayed(cooldownRunnable!!, 1000)
    }

    private fun stopCooldownTimer() {
        cooldownRunnable?.let { cooldownHandler.removeCallbacks(it) }
        cooldownRunnable = null
    }

    private fun cleanupExpiredEntries() {
        val currentTime = System.currentTimeMillis()
        val iterator = otpCooldownCache.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val elapsed = ((currentTime - entry.value) / 1000).toInt()
            if (elapsed >= OTP_COOLDOWN_SECONDS) iterator.remove()
        }
        saveCooldownCache()
    }

    private fun updateButtonText() {
        if (remainingTime > 0) {
            nextButton.setText(getString(R.string.email_next_button_cooldown, remainingTime))
        } else {
            nextButton.setText(getString(R.string.email_next_button))
        }
    }

    private fun updateButtonState(isFormValid: Boolean) {
        nextButton.isEnabled = isFormValid && remainingTime <= 0
    }

    private fun showToast(type: ToastOverlay.ToastType, title: String) {
        val overlay = ToastOverlay(requireContext(), themeColors)
        (getParentActivity()?.findViewById<ViewGroup>(android.R.id.content) ?: rootFrame as? ViewGroup)?.let { root ->
            overlay.show(root, type, title, null)
        }
    }

    private fun handleNext() {
        val email = emailCell.getText().trim()
        if (email == currentEmail && email.isNotEmpty()) {
            emailCell.setError(getString(R.string.email_already_linked))
            return
        }

        loadingView.visibility = View.VISIBLE
        nextButton.isEnabled = false

        accountController.linkEmail(email) { success, reqId, _ ->
            loadingView.visibility = View.GONE
            if (success) {
                otpCooldownCache[email] = System.currentTimeMillis()
                saveCooldownCache()
                remainingTime = OTP_COOLDOWN_SECONDS
                startCooldownTimer(email)
                syncNextButtonWithField()

                val fragment = VerifyOtpFragment.newInstance(email, reqId, isPhone = false)
                fragment.onVerified = { onEmailVerified?.invoke() }
                presentFragment(fragment)
            } else {
                showToast(ToastOverlay.ToastType.ERROR, getString(R.string.email_link_failed))
                syncNextButtonWithField()
            }
        }
    }
}
