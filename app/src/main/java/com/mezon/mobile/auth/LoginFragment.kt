package com.mezon.mobile.auth

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.util.Log
import androidx.lifecycle.lifecycleScope
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.di.IoDispatcher
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class LoginFragment : BaseFragment() {

    @Inject lateinit var authRepository: AuthRepository
    @Inject @IoDispatcher lateinit var ioDispatcher: CoroutineDispatcher

    var onLoginSuccess: (() -> Unit)? = null
        set(value) {
            Log.d("LoginFragment", "onLoginSuccess set: ${value != null}, trace=${Thread.currentThread().stackTrace.take(5).joinToString()}")
            field = value
        }

    private lateinit var emailInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var loginButton: Button
    private lateinit var errorText: TextView
    private lateinit var progressBar: ProgressBar

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = FrameLayout(requireContext()).apply {
            setBackgroundColor(themeColors.background)
        }

        val form = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val padH = LayoutHelper.dp(24)
            setPadding(padH, 0, padH, 0)
        }
        root.addView(form, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER
        ))

        val title = TextView(requireContext()).apply {
            text = getString(R.string.common_login_welcome_back)
            setTextColor(themeColors.onSurface)
            textSize = 24f
        }
        form.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 0f, 0f, 24f))

        emailInput = EditText(requireContext()).apply {
            hint = getString(R.string.common_login_email)
            setHintTextColor(themeColors.onSurfaceVariant)
            setTextColor(themeColors.onSurface)
            inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            imeOptions = EditorInfo.IME_ACTION_NEXT
            isSingleLine = true
            textSize = 16f
        }
        form.addView(emailInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 0f, 0f, 12f))

        passwordInput = EditText(requireContext()).apply {
            hint = getString(R.string.common_login_password)
            setHintTextColor(themeColors.onSurfaceVariant)
            setTextColor(themeColors.onSurface)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_DONE
            isSingleLine = true
            textSize = 16f
        }
        form.addView(passwordInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 0f, 0f, 12f))

        errorText = TextView(requireContext()).apply {
            setTextColor(themeColors.error)
            textSize = 12f
            visibility = View.GONE
        }
        form.addView(errorText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 0f, 0f, 8f))

        val buttonFrame = FrameLayout(requireContext())
        form.addView(buttonFrame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0f, Gravity.START, 0f, 8f, 0f, 0f))

        loginButton = Button(requireContext()).apply {
            text = getString(R.string.common_login_log_in)
            setTextColor(themeColors.onPrimary)
            textSize = 16f
            setOnClickListener { doLogin() }
        }
        buttonFrame.addView(loginButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        progressBar = ProgressBar(requireContext()).apply {
            visibility = View.GONE
        }
        buttonFrame.addView(progressBar, LayoutHelper.createFrame(24, 24, Gravity.CENTER))

        passwordInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                doLogin()
                true
            } else false
        }

        return root
    }

    private fun doLogin() {
        val email = emailInput.text.toString().trim()
        val password = passwordInput.text.toString()

        if (email.isBlank() || password.isBlank()) {
            showError("Email and password are required")
            return
        }

        showLoading()

        lifecycleScope.launch {
            val result = withContext(ioDispatcher) {
                authRepository.loginWithEmail(email, password)
            }
            Log.d("LoginFragment", "loginWithEmail result: isSuccess=${result.isSuccess} isFailure=${result.isFailure}")
            result
                .onSuccess {
                    Log.d("LoginFragment", "onLoginSuccess invoking, callback=${onLoginSuccess != null}")
                    onLoginSuccess?.invoke()
                }
                .onFailure { err ->
                    Log.e("LoginFragment", "login failed: ${err.message}", err)
                    showError(err.message ?: "Login failed")
                }
        }
    }

    private fun showLoading() {
        loginButton.isEnabled = false
        loginButton.visibility = View.INVISIBLE
        progressBar.visibility = View.VISIBLE
        errorText.visibility = View.GONE
    }

    private fun showError(message: String) {
        loginButton.isEnabled = true
        loginButton.visibility = View.VISIBLE
        progressBar.visibility = View.GONE
        errorText.visibility = View.VISIBLE
        errorText.text = message
    }
}
