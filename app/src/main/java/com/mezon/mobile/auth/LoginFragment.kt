package com.mezon.mobile.auth

import android.content.Context
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.PaintDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.ScrollView
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

    private lateinit var titleText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var emailCell: InputCell
    private lateinit var passwordCell: InputCell
    private lateinit var phoneCell: InputCell
    private lateinit var countryButton: TextView
    private lateinit var showPasswordCheck: CheckBox
    private lateinit var showPasswordLabel: TextView
    private lateinit var submitButton: ActionButton
    private lateinit var errorText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var altText: TextView
    private lateinit var altLink1: TextView
    private lateinit var altLink2: TextView

    private lateinit var passwordRow: LinearLayout
    private lateinit var showPasswordRow: LinearLayout
    private lateinit var phoneRow: LinearLayout

    private var currentMode = LoginMode.SMS
    private var selectedCountry = Country.VIETNAM
    private var countryPopup: PopupWindow? = null

    private val lastOTPSentTime = HashMap<String, Long>()

    enum class LoginMode { SMS, OTP, PASSWORD }

    data class Country(val code: String, val prefix: String, val nameResId: Int, val flag: String) {
        companion object {
            val VIETNAM = Country("VN", "+84", R.string.common_login_country_vietnam, "\uD83C\uDDFB\uD83C\uDDF3")
            val JAPAN = Country("JP", "+81", R.string.common_login_country_japan, "\uD83C\uDDEF\uD83C\uDDF5")
            val USA = Country("US", "+1", R.string.common_login_country_usa, "\uD83C\uDDFA\uD83C\uDDF8")
            val ALL = listOf(VIETNAM, JAPAN, USA)
        }
    }

    override fun onInject(entryPoint: FragmentEntryPoint) {
        authRepository = entryPoint.authRepository()
    }

    override fun createView(context: Context): View {
        val root = FrameLayout(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFFF0EDFD.toInt(), 0xFFBEB5F8.toInt(), 0xFF9774FA.toInt())
            )
        }

        val scrollView = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
        root.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        val form = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val padH = LayoutHelper.dp(16)
            setPadding(padH, LayoutHelper.dp(100), padH, LayoutHelper.dp(24))
        }
        scrollView.addView(form, FrameLayout.LayoutParams(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT
        ))

        titleText = TextView(context).apply {
            setTextColor(0xFF000000.toInt())
            textSize = 24f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        form.addView(titleText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER, 0f, 0f, 0f, 8f))

        subtitleText = TextView(context).apply {
            text = getString(R.string.common_login_choose_another_option)
            setTextColor(0xFF505050.toInt())
            textSize = 14f
            alpha = 0.9f
            gravity = Gravity.CENTER
        }
        form.addView(subtitleText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER, 0f, 0f, 0f, 40f))

        phoneRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        form.addView(phoneRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 0f, 0f, 12f))

        countryButton = TextView(context).apply {
            textSize = 15f
            setTextColor(0xFF000000.toInt())
            gravity = Gravity.CENTER
            val pad = LayoutHelper.dp(12)
            setPadding(pad, pad, pad, pad)
            val bg = GradientDrawable().apply {
                setColor(0xFFF6F6F6.toInt())
                cornerRadius = LayoutHelper.dpf(12f)
                setStroke(LayoutHelper.dp(1), 0xFFCCCCCC.toInt())
            }
            background = bg
            setOnClickListener { showCountryDropdown(it) }
        }
        phoneRow.addView(countryButton, LinearLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.dp(50)).apply {
            marginEnd = LayoutHelper.dp(8)
        })

        phoneCell = InputCell(context, themeColors).apply {
            setHint(getString(R.string.common_login_phone_number))
            editText.inputType = InputType.TYPE_CLASS_PHONE
            editText.imeOptions = EditorInfo.IME_ACTION_DONE
        }
        phoneRow.addView(phoneCell, LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f))

        emailCell = InputCell(context, themeColors).apply {
            setHint(getString(R.string.common_login_email_address))
            editText.inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            editText.imeOptions = EditorInfo.IME_ACTION_NEXT
        }
        form.addView(emailCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 0f, 0f, 12f))

        passwordRow = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        form.addView(passwordRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        passwordCell = InputCell(context, themeColors).apply {
            setHint(getString(R.string.common_login_password))
            editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            editText.imeOptions = EditorInfo.IME_ACTION_DONE
        }
        passwordRow.addView(passwordCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 0f, 0f, 12f))

        showPasswordRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        passwordRow.addView(showPasswordRow, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 0f, 0f, 12f))

        showPasswordCheck = CheckBox(context).apply {
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            setOnCheckedChangeListener { _, isChecked ->
                passwordCell.editText.inputType = if (isChecked) {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                } else {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                passwordCell.editText.setSelection(passwordCell.getText().length)
            }
        }
        showPasswordRow.addView(showPasswordCheck, LinearLayout.LayoutParams(
            LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER_VERTICAL })

        showPasswordLabel = TextView(context).apply {
            text = getString(R.string.common_login_show_password)
            setTextColor(themeColors.onSurfaceVariant)
            textSize = 14f
            setPadding(LayoutHelper.dp(4), 0, 0, 0)
            setOnClickListener { showPasswordCheck.toggle() }
        }
        showPasswordRow.addView(showPasswordLabel, LinearLayout.LayoutParams(
            LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER_VERTICAL })

        errorText = TextView(context).apply {
            setTextColor(themeColors.error)
            textSize = 12f
            visibility = View.GONE
        }
        form.addView(errorText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 0f, 0f, 8f))

        val buttonFrame = FrameLayout(context)
        form.addView(buttonFrame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50, 0f, Gravity.START, 0f, 8f, 0f, 0f))

        submitButton = ActionButton(context, themeColors).apply {
            useGradient = true
            setOnClickListener { doSubmit() }
        }
        buttonFrame.addView(submitButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        progressBar = ProgressBar(context).apply {
            visibility = View.GONE
        }
        buttonFrame.addView(progressBar, LayoutHelper.createFrame(24, 24, Gravity.CENTER))

        val altSection = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        form.addView(altSection, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_HORIZONTAL, 0f, 24f, 0f, 0f))

        altText = TextView(context).apply {
            setTextColor(0xFF5E5E5E.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
        }
        altSection.addView(altText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER, 0f, 0f, 0f, 8f))

        altLink1 = TextView(context).apply {
            setTextColor(0xFF2E22FF.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
        }
        altSection.addView(altLink1, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER, 0f, 0f, 0f, 4f))

        altLink2 = TextView(context).apply {
            setTextColor(0xFF2E22FF.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
        }
        altSection.addView(altLink2, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER, 0f, 0f, 0f, 0f))

        phoneCell.editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { doSubmit(); true } else false
        }
        passwordCell.editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { doSubmit(); true } else false
        }

        applyMode(LoginMode.SMS)

        return root
    }

    private fun applyMode(mode: LoginMode) {
        currentMode = mode

        when (mode) {
            LoginMode.SMS -> {
                titleText.text = getString(R.string.common_login_enter_phone)
                phoneRow.visibility = View.VISIBLE
                emailCell.visibility = View.GONE
                passwordRow.visibility = View.GONE
                submitButton.setText(getString(R.string.common_login_send))
                altText.text = getString(R.string.common_login_cannot_access_phone)
                altLink1.text = getString(R.string.common_login_with_email_otp)
                altLink1.setOnClickListener { applyMode(LoginMode.OTP) }
                altLink2.text = getString(R.string.common_login_with_password)
                altLink2.setOnClickListener { applyMode(LoginMode.PASSWORD) }
                updateCountryButton()
            }
            LoginMode.OTP -> {
                titleText.text = getString(R.string.common_login_enter_email_address)
                phoneRow.visibility = View.GONE
                emailCell.visibility = View.VISIBLE
                passwordRow.visibility = View.GONE
                submitButton.setText(getString(R.string.common_login_send))
                altText.text = getString(R.string.common_login_cannot_access_email)
                altLink1.text = getString(R.string.common_login_with_sms)
                altLink1.setOnClickListener { applyMode(LoginMode.SMS) }
                altLink2.text = getString(R.string.common_login_with_password)
                altLink2.setOnClickListener { applyMode(LoginMode.PASSWORD) }
            }
            LoginMode.PASSWORD -> {
                titleText.text = getString(R.string.common_login_enter_email_address)
                phoneRow.visibility = View.GONE
                emailCell.visibility = View.VISIBLE
                passwordRow.visibility = View.VISIBLE
                submitButton.setText(getString(R.string.common_login_log_in))
                altText.text = getString(R.string.common_login_cannot_access_email)
                altLink1.text = getString(R.string.common_login_with_sms)
                altLink1.setOnClickListener { applyMode(LoginMode.SMS) }
                altLink2.text = getString(R.string.common_login_with_email_otp)
                altLink2.setOnClickListener { applyMode(LoginMode.OTP) }
            }
        }
        errorText.visibility = View.GONE
    }

    private fun updateCountryButton() {
        countryButton.text = "${selectedCountry.flag} ${selectedCountry.prefix}"
    }

    private fun showCountryDropdown(anchor: View) {
        val ctx = anchor.context
        countryPopup?.dismiss()

        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable().apply {
                setColor(themeColors.surface)
                cornerRadius = LayoutHelper.dpf(8f)
                setStroke(LayoutHelper.dp(1), themeColors.outline)
            }
            background = bg
        }

        for (country in Country.ALL) {
            val row = TextView(ctx).apply {
                text = "${country.flag}  ${getString(country.nameResId)}  (${country.prefix})"
                textSize = 14f
                setTextColor(themeColors.onSurface)
                val pad = LayoutHelper.dp(12)
                setPadding(pad, pad, pad, pad)
                if (country == selectedCountry) {
                    setBackgroundColor(themeColors.primaryContainer)
                }
                setOnClickListener {
                    selectedCountry = country
                    updateCountryButton()
                    countryPopup?.dismiss()
                }
            }
            list.addView(row, LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        }

        countryPopup = PopupWindow(list, LayoutHelper.dp(220), LayoutHelper.WRAP_CONTENT, true).apply {
            elevation = LayoutHelper.dpf(4f)
            showAsDropDown(anchor, 0, LayoutHelper.dp(4))
        }
    }

    private fun doSubmit() {
        when (currentMode) {
            LoginMode.SMS -> doSmsOTP()
            LoginMode.OTP -> doEmailOTP()
            LoginMode.PASSWORD -> doPasswordLogin()
        }
    }

    private fun doSmsOTP() {
        val phone = phoneCell.getText().trim()
        if (!isValidPhone(phone)) {
            showError(getString(R.string.common_login_invalid_phone))
            return
        }

        val fullPhone = selectedCountry.prefix + normalizePhone(phone)
        val cooldownRemaining = checkCooldown(fullPhone)
        if (cooldownRemaining > 0) {
            showError(getString(R.string.common_login_login_too_fast, cooldownRemaining))
            return
        }

        showLoading()
        lastOTPSentTime[fullPhone] = System.currentTimeMillis()

        fragmentScope.launch(Dispatchers.Main) {
            val result = withContext(entryPoint().ioDispatcher()) {
                authRepository.requestSmsOTP(fullPhone)
            }
            result
                .onSuccess { reqId ->
                    hideLoading()
                    navigateToOTP(reqId, fullPhone, true)
                }
                .onFailure { err ->
                    Log.e(TAG, "SMS OTP failed: ${err.message}", err)
                    showError(err.message ?: getString(R.string.otp_send_failed))
                }
        }
    }

    private fun doEmailOTP() {
        val email = emailCell.getText().trim()
        if (!isValidEmail(email)) {
            showError(getString(R.string.common_login_invalid_email))
            return
        }

        val cooldownRemaining = checkCooldown(email)
        if (cooldownRemaining > 0) {
            showError(getString(R.string.common_login_login_too_fast, cooldownRemaining))
            return
        }

        showLoading()
        lastOTPSentTime[email] = System.currentTimeMillis()

        fragmentScope.launch(Dispatchers.Main) {
            val result = withContext(entryPoint().ioDispatcher()) {
                authRepository.requestEmailOTP(email)
            }
            result
                .onSuccess { reqId ->
                    hideLoading()
                    navigateToOTP(reqId, email, false)
                }
                .onFailure { err ->
                    Log.e(TAG, "Email OTP failed: ${err.message}", err)
                    showError(err.message ?: getString(R.string.otp_send_failed))
                }
        }
    }

    private fun doPasswordLogin() {
        val email = emailCell.getText().trim()
        val password = passwordCell.getText()

        if (email.isBlank() || password.isBlank()) {
            showError(getString(R.string.common_login_invalid_credentials))
            return
        }

        showLoading()

        fragmentScope.launch(Dispatchers.Main) {
            val result = withContext(entryPoint().ioDispatcher()) {
                authRepository.loginWithEmail(email, password)
            }
            result
                .onSuccess {
                    try {
                        entryPoint().fcmRepository().getAndRegisterToken()
                    } catch (e: Exception) {
                        Log.e(TAG, "FCM token registration failed", e)
                    }
                    onLoginSuccess?.invoke()
                }
                .onFailure { err ->
                    Log.e(TAG, "Password login failed: ${err.message}", err)
                    showError(err.message ?: getString(R.string.common_login_login_failed))
                }
        }
    }

    private fun navigateToOTP(reqId: String, identifier: String, isSms: Boolean) {
        val otpFragment = OTPVerificationFragment().apply {
            this.reqId = reqId
            this.identifier = identifier
            this.isSms = isSms
            this.onVerifySuccess = {
                try {
                    entryPoint().fcmRepository().getAndRegisterToken()
                } catch (e: Exception) {
                    Log.e(TAG, "FCM token registration failed", e)
                }
                onLoginSuccess?.invoke()
            }
        }
        parentLayout?.presentFragment(otpFragment)
    }

    private fun checkCooldown(key: String): Int {
        val lastSent = lastOTPSentTime[key] ?: return 0
        val elapsed = (System.currentTimeMillis() - lastSent) / 1000
        return if (elapsed < 60) (60 - elapsed).toInt() else 0
    }

    private fun isValidEmail(email: String): Boolean =
        email.matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))

    private fun isValidPhone(phone: String): Boolean {
        val digits = phone.replace(Regex("[^0-9]"), "")
        if (selectedCountry == Country.VIETNAM) {
            val normalized = if (digits.startsWith("0")) digits.substring(1) else digits
            return normalized.matches(Regex("^(3|5|7|8|9)[0-9]{8}$"))
        }
        return digits.length >= 7
    }

    private fun normalizePhone(phone: String): String {
        val digits = phone.replace(Regex("[^0-9]"), "")
        if (selectedCountry == Country.VIETNAM && digits.startsWith("0")) {
            return digits.substring(1)
        }
        return digits
    }

    private fun showLoading() {
        submitButton.isEnabled = false
        submitButton.visibility = View.INVISIBLE
        progressBar.visibility = View.VISIBLE
        errorText.visibility = View.GONE
    }

    private fun hideLoading() {
        submitButton.isEnabled = true
        submitButton.visibility = View.VISIBLE
        progressBar.visibility = View.GONE
    }

    private fun showError(message: String) {
        submitButton.isEnabled = true
        submitButton.visibility = View.VISIBLE
        progressBar.visibility = View.GONE
        errorText.visibility = View.VISIBLE
        errorText.text = message
    }

    companion object {
        private const val TAG = "LoginFragment"
    }
}
