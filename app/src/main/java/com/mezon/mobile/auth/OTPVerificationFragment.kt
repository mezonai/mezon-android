package com.mezon.mobile.auth

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.CountDownTimer
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.ui.cells.ActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OTPVerificationFragment : BaseFragment() {

    var reqId = ""
    var identifier = ""
    var isSms = false
    var onVerifySuccess: (() -> Unit)? = null

    private lateinit var authRepository: AuthRepository

    private lateinit var titleView: TextView
    private lateinit var descriptionView: TextView
    private lateinit var identifierView: TextView
    private lateinit var verifyButton: ActionButton
    private lateinit var errorText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var changeLink: TextView
    private lateinit var didNotReceiveText: TextView
    private val otpFields = ArrayList<EditText>(OTP_LENGTH)
    private val otpBackgrounds = ArrayList<GradientDrawable>(OTP_LENGTH)
    private var countdownTimer: CountDownTimer? = null
    private var isResendEnabled = false

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

        titleView = TextView(context).apply {
            text = getString(R.string.otp_login_to_mezon)
            setTextColor(0xFF000000.toInt())
            textSize = 24f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        form.addView(titleView, LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER, 0f, 0f, 0f, 20f
        ))

        descriptionView = TextView(context).apply {
            text = getString(R.string.otp_enter_code_from)
            textSize = 14f
            setTextColor(0xFF505050.toInt())
            gravity = Gravity.CENTER
        }
        form.addView(descriptionView, LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER, 0f, 0f, 0f, 4f
        ))

        identifierView = TextView(context).apply {
            text = identifier
            textSize = 14f
            setTextColor(0xFF505050.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        form.addView(identifierView, LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER, 0f, 0f, 0f, 24f
        ))

        val otpRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        form.addView(otpRow, LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER, 0f, 0f, 0f, 16f
        ))

        for (i in 0 until OTP_LENGTH) {
            val bg = GradientDrawable().apply {
                setColor(0xFFFFFFFF.toInt())
                cornerRadius = LayoutHelper.dpf(8f)
                setStroke(LayoutHelper.dp(2), OTP_BORDER_EMPTY)
            }
            otpBackgrounds.add(bg)

            val field = EditText(context).apply {
                textSize = 20f
                setTextColor(0xFF374151.toInt())
                setHintTextColor(0x40A8A8A8.toInt())
                hint = "0"
                gravity = Gravity.CENTER
                inputType = InputType.TYPE_CLASS_NUMBER
                imeOptions = if (i < OTP_LENGTH - 1) EditorInfo.IME_ACTION_NEXT else EditorInfo.IME_ACTION_DONE
                isSingleLine = true
                maxLines = 1
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                background = bg
                setPadding(0, 0, 0, 0)
            }
            otpFields.add(field)
            val params = LinearLayout.LayoutParams(LayoutHelper.dp(48), LayoutHelper.dp(48))
            if (i < OTP_LENGTH - 1) params.marginEnd = LayoutHelper.dp(8)
            otpRow.addView(field, params)

            field.setText(ZERO_WIDTH_SPACE)
            field.setSelection(1)

            field.addTextChangedListener(object : TextWatcher {
                private var isUpdating = false
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (isUpdating) return
                    isUpdating = true

                    val raw = s?.toString() ?: ""
                    val digits = raw.replace(ZERO_WIDTH_SPACE, "").filter { it.isDigit() }

                    if (digits.length >= 2) {
                        distributePastedOtp(digits, i)
                    } else if (digits.length == 1) {
                        field.setText(ZERO_WIDTH_SPACE + digits)
                        field.setSelection(field.text!!.length)
                        updateOtpFieldState(i, true)
                        if (i < OTP_LENGTH - 1) {
                            otpFields[i + 1].requestFocus()
                        }
                    } else {
                        field.setText(ZERO_WIDTH_SPACE)
                        field.setSelection(field.text!!.length)
                        updateOtpFieldState(i, false)
                        if (i > 0) {
                            otpFields[i - 1].requestFocus()
                            otpFields[i - 1].setSelection(otpFields[i - 1].text!!.length)
                        }
                    }

                    if (getOtpCode().length == OTP_LENGTH && !isResendEnabled) {
                        doVerify()
                    }

                    isUpdating = false
                }
            })
        }

        errorText = TextView(context).apply {
            setTextColor(0xFFCA0000.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        form.addView(errorText, LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER, 0f, 0f, 0f, 8f
        ))

        val buttonFrame = FrameLayout(context)
        form.addView(buttonFrame, LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT, 50, 0f, Gravity.START, 0f, 8f, 0f, 30f
        ))

        verifyButton = ActionButton(context, themeColors).apply {
            useGradient = true
            setOnClickListener {
                if (isResendEnabled) doResend() else doVerify()
            }
        }
        buttonFrame.addView(verifyButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        progressBar = ProgressBar(context).apply { visibility = View.GONE }
        buttonFrame.addView(progressBar, LayoutHelper.createFrame(24, 24, Gravity.CENTER))

        didNotReceiveText = TextView(context).apply {
            text = getString(R.string.otp_did_not_receive)
            textSize = 14f
            setTextColor(0xFF5E5E5E.toInt())
            gravity = Gravity.CENTER
        }
        form.addView(didNotReceiveText, LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER, 0f, 0f, 0f, 6f
        ))

        changeLink = TextView(context).apply {
            text = if (isSms) getString(R.string.otp_change_phone) else getString(R.string.otp_change_email)
            textSize = 14f
            setTextColor(0xFF2E22FF.toInt())
            gravity = Gravity.CENTER
            setOnClickListener { finishFragment() }
        }
        form.addView(changeLink, LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER, 0f, 0f, 0f, 0f
        ))

        startCountdown()
        otpFields.firstOrNull()?.requestFocus()

        return root
    }

    private fun updateOtpFieldState(index: Int, filled: Boolean) {
        val bg = otpBackgrounds[index]
        if (filled) {
            bg.setColor(0x1A0052FF.toInt())
            bg.setStroke(LayoutHelper.dp(2), OTP_BORDER_FILLED)
        } else {
            bg.setColor(0xFFFFFFFF.toInt())
            bg.setStroke(LayoutHelper.dp(2), OTP_BORDER_EMPTY)
        }
    }

    private fun setOtpErrorState() {
        for (i in 0 until OTP_LENGTH) {
            val bg = otpBackgrounds[i]
            bg.setColor(0x1ACA0000.toInt())
            bg.setStroke(LayoutHelper.dp(2), OTP_BORDER_ERROR)
        }
    }

    private fun getOtpCode(): String =
        otpFields.joinToString("") { it.text?.toString()?.replace(ZERO_WIDTH_SPACE, "") ?: "" }

    private fun startCountdown() {
        isResendEnabled = false
        verifyButton.isEnabled = true
        countdownTimer?.cancel()
        countdownTimer = object : CountDownTimer(COUNTDOWN_MS, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (millisUntilFinished / 1000).toInt()
                verifyButton.setText(getString(R.string.otp_verify, seconds))
            }

            override fun onFinish() {
                isResendEnabled = true
                verifyButton.setText(getString(R.string.otp_resend))
            }
        }.start()
    }

    private fun doVerify() {
        val code = getOtpCode()
        if (code.length != OTP_LENGTH) return

        showLoading()

        fragmentScope.launch(Dispatchers.Main) {
            val result = withContext(entryPoint().ioDispatcher()) {
                authRepository.confirmOTP(reqId, code)
            }
            result
                .onSuccess {
                    hideLoading()
                    onVerifySuccess?.invoke()
                }
                .onFailure { err ->
                    showError(err.message ?: getString(R.string.otp_verify_failed))
                    setOtpErrorState()
                    clearOtpFields()
                }
        }
    }

    private fun doResend() {
        showLoading()

        fragmentScope.launch(Dispatchers.Main) {
            val result = withContext(entryPoint().ioDispatcher()) {
                if (isSms) authRepository.requestSmsOTP(identifier)
                else authRepository.requestEmailOTP(identifier)
            }
            result
                .onSuccess { newReqId ->
                    reqId = newReqId
                    hideLoading()
                    clearOtpFields()
                    startCountdown()
                }
                .onFailure { err ->
                    showError(err.message ?: getString(R.string.otp_resend_failed))
                }
        }
    }

    private fun distributePastedOtp(digits: String, startIndex: Int) {
        val digitsToFill = digits.take(OTP_LENGTH - startIndex)
        for ((offset, digit) in digitsToFill.withIndex()) {
            val idx = startIndex + offset
            if (idx < OTP_LENGTH) {
                otpFields[idx].setText(ZERO_WIDTH_SPACE + digit)
                otpFields[idx].setSelection(otpFields[idx].text!!.length)
                updateOtpFieldState(idx, true)
            }
        }
        val nextEmpty = (startIndex + digitsToFill.length).coerceAtMost(OTP_LENGTH - 1)
        otpFields[nextEmpty].requestFocus()
    }

    private fun clearOtpFields() {
        for (i in 0 until OTP_LENGTH) {
            otpFields[i].setText(ZERO_WIDTH_SPACE)
            otpFields[i].setSelection(1)
            updateOtpFieldState(i, false)
        }
        otpFields.firstOrNull()?.requestFocus()
    }

    private fun showLoading() {
        verifyButton.isEnabled = false
        verifyButton.visibility = View.INVISIBLE
        progressBar.visibility = View.VISIBLE
        errorText.visibility = View.GONE
    }

    private fun hideLoading() {
        verifyButton.isEnabled = true
        verifyButton.visibility = View.VISIBLE
        progressBar.visibility = View.GONE
    }

    private fun showError(message: String) {
        verifyButton.isEnabled = true
        verifyButton.visibility = View.VISIBLE
        progressBar.visibility = View.GONE
        errorText.visibility = View.VISIBLE
        errorText.text = message
    }

    override fun onFragmentDestroy() {
        super.onFragmentDestroy()
        countdownTimer?.cancel()
        countdownTimer = null
    }

    companion object {
        private const val ZERO_WIDTH_SPACE = "\u200B"
        private const val OTP_LENGTH = 6
        private const val COUNTDOWN_MS = 59_000L
        private val OTP_BORDER_EMPTY = 0xFFD1D5DB.toInt()
        private val OTP_BORDER_FILLED = 0xFF1661FF.toInt()
        private val OTP_BORDER_ERROR = 0xFFCA0000.toInt()
    }
}
