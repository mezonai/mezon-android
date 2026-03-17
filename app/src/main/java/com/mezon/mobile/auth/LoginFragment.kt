package com.mezon.mobile.auth

import android.content.Context
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.ui.cells.ActionButton
import com.mezon.mobile.ui.cells.InputCell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginFragment : BaseFragment() {

    private lateinit var authRepository: AuthRepository

    var onLoginSuccess: (() -> Unit)? = null

    private lateinit var emailCell: InputCell
    private lateinit var passwordCell: InputCell
    private lateinit var loginButton: ActionButton
    private lateinit var errorText: TextView
    private lateinit var progressBar: ProgressBar

    override fun onInject(entryPoint: FragmentEntryPoint) {
        authRepository = entryPoint.authRepository()
    }

    override fun createView(context: Context): View {
        val root = FrameLayout(context).apply {
            setBackgroundColor(themeColors.background)
        }

        val form = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val padH = LayoutHelper.dp(24)
            setPadding(padH, 0, padH, 0)
        }
        root.addView(form, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER
        ))

        val title = TextView(context).apply {
            text = getString(R.string.common_login_welcome_back)
            setTextColor(themeColors.onSurface)
            textSize = 24f
        }
        form.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 0f, 0f, 24f))

        emailCell = InputCell(context, themeColors).apply {
            setLabel(getString(R.string.common_login_email))
            setHint(getString(R.string.common_login_email))
            editText.inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            editText.imeOptions = EditorInfo.IME_ACTION_NEXT
        }
        form.addView(emailCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 0f, 0f, 12f))

        passwordCell = InputCell(context, themeColors).apply {
            setLabel(getString(R.string.common_login_password))
            setHint(getString(R.string.common_login_password))
            editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            editText.imeOptions = EditorInfo.IME_ACTION_DONE
        }
        form.addView(passwordCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 0f, 0f, 12f))

        errorText = TextView(context).apply {
            setTextColor(themeColors.error)
            textSize = 12f
            visibility = View.GONE
        }
        form.addView(errorText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 0f, 0f, 8f))

        val buttonFrame = FrameLayout(context)
        form.addView(buttonFrame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0f, Gravity.START, 0f, 8f, 0f, 0f))

        loginButton = ActionButton(context, themeColors).apply {
            setText(getString(R.string.common_login_log_in))
            setOnClickListener { doLogin() }
        }
        buttonFrame.addView(loginButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        progressBar = ProgressBar(context).apply {
            visibility = View.GONE
        }
        buttonFrame.addView(progressBar, LayoutHelper.createFrame(24, 24, Gravity.CENTER))

        passwordCell.editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { doLogin(); true } else false
        }

        return root
    }

    private fun doLogin() {
        val email = emailCell.getText().trim()
        val password = passwordCell.getText()

        if (email.isBlank() || password.isBlank()) {
            showError("Email and password are required")
            return
        }

        showLoading()

        fragmentScope.launch(Dispatchers.Main) {
            val result = withContext(entryPoint().ioDispatcher()) {
                authRepository.loginWithEmail(email, password)
            }
            Log.d("LoginFragment", "loginWithEmail result: isSuccess=${result.isSuccess}")
            result
                .onSuccess {
                    Log.d("LoginFragment", "onLoginSuccess invoking, callback=${onLoginSuccess != null}")
                    try {
                        entryPoint().fcmRepository().getAndRegisterToken()
                    } catch (e: Exception) {
                        Log.e("LoginFragment", "FCM token registration failed", e)
                    }
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
