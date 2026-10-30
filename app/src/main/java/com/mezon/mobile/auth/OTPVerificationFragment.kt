package com.mezon.mobile.auth

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.CountDownTimer
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.ui.cells.ActionButton
import com.mezon.mobile.util.NetworkErrorMessages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OTPVerificationFragment : AuthFragment() {

    var reqId = ""
    var identifier = ""
    var isSms = false
    var onVerifySuccess: (() -> Unit)? = null

    private lateinit var authRepository: AuthRepository

    private lateinit var titleView: TextView
    private lateinit var descriptionView: TextView
    private lateinit var identifierView: TextView
    private lateinit var verifyButton: ActionButton
    private lateinit var progressBar: ProgressBar
    private lateinit var changeLink: TextView
    private lateinit var didNotReceiveText: TextView
    private val otpFields = ArrayList<EditText>(OTP_LENGTH)
    private val otpBackgrounds = ArrayList<GradientDrawable>(OTP_LENGTH)
    private var countdownTimer: CountDownTimer? = null
    private var isResendEnabled = false
    private var didAutoFocus = false
    private var pendingFocusObserver: ViewTreeObserver? = null
    private var pendingFocusListener: ViewTreeObserver.OnWindowFocusChangeListener? = null

    override fun onInject(entryPoint: FragmentEntryPoint) {
        authRepository = entryPoint.authRepository()
    }

    override fun createView(context: Context): View {
        clearPendingFocus()
        didAutoFocus = false
        val root = AuthBackground(context)

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
        root.followContent(form)

        titleView = TextView(context).apply {
            text = getString(R.string.otp_login_to_mezon)
            setTextColor(Color.WHITE)
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
            setTextColor(AuthStyle.secondaryText)
            gravity = Gravity.CENTER
        }
        form.addView(descriptionView, LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER, 0f, 0f, 0f, 4f
        ))

        identifierView = TextView(context).apply {
            text = identifier
            textSize = 14f
            setTextColor(Color.WHITE)
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

        otpFields.clear()
        otpBackgrounds.clear()
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
            if (i < OTP_LENGTH - 1) params.marginEnd = LayoutHelper.dp(6)
            otpRow.addView(field, params)

            field.setText(ZERO_WIDTH_SPACE)
            field.setSelection(1)
            field.setOnFocusChangeListener { _, _ ->
                val filled = field.text?.toString()?.replace(ZERO_WIDTH_SPACE, "")?.isNotEmpty() == true
                updateOtpFieldState(i, filled)
            }

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
        otpRow.addOnLayoutChangeListener { row, _, _, _, _, _, _, _, _ ->
            val available = row.width - LayoutHelper.dp(6) * (OTP_LENGTH - 1)
            val size = minOf(LayoutHelper.dp(48), (available / OTP_LENGTH).coerceAtLeast(1))
            otpFields.forEach { field ->
                val params = field.layoutParams as LinearLayout.LayoutParams
                if (params.width != size || params.height != size) {
                    params.width = size
                    params.height = size
                    field.layoutParams = params
                }
            }
        }

        val buttonFrame = FrameLayout(context)
        form.addView(buttonFrame, LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT, 50, 0f, Gravity.START, 0f, 8f, 0f, 30f
        ))

        verifyButton = ActionButton(context, themeColors).apply {
            AuthStyle.styleButton(this)
            setOnClickListener {
                if (isResendEnabled) doResend() else doVerify()
            }
        }
        buttonFrame.addView(verifyButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        progressBar = ProgressBar(context).apply {
            visibility = View.GONE
            indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
        }
        buttonFrame.addView(progressBar, LayoutHelper.createFrame(24, 24, Gravity.CENTER))

        didNotReceiveText = TextView(context).apply {
            text = getString(R.string.otp_did_not_receive)
            textSize = 14f
            setTextColor(AuthStyle.secondaryText)
            gravity = Gravity.CENTER
        }
        form.addView(didNotReceiveText, LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER, 0f, 0f, 0f, 6f
        ))

        changeLink = TextView(context).apply {
            text = if (isSms) getString(R.string.otp_change_phone) else getString(R.string.otp_change_email)
            textSize = 14f
            setTextColor(AuthStyle.linkText)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setOnClickListener { finishFragment() }
        }
        form.addView(changeLink, LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER, 0f, 0f, 0f, 0f
        ))

        startCountdown()
        return root
    }

    override fun onBecomeFullyVisible() {
        super.onBecomeFullyVisible()
        val firstField = otpFields.firstOrNull() ?: return
        if (didAutoFocus) return
        firstField.post {
            if (didAutoFocus || isPaused || isFinished || fragmentView?.isShown != true ||
                !firstField.isAttachedToWindow || otpFields.firstOrNull() !== firstField
            ) return@post
            if (firstField.hasWindowFocus()) {
                focusFirstField(firstField)
            } else if (pendingFocusListener == null) {
                val observer = firstField.viewTreeObserver
                val listener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
                    if (hasFocus) {
                        clearPendingFocus()
                        focusFirstField(firstField)
                    }
                }
                pendingFocusObserver = observer
                pendingFocusListener = listener
                observer.addOnWindowFocusChangeListener(listener)
            }
        }
    }

    private fun focusFirstField(field: EditText) {
        if (didAutoFocus || isPaused || isFinished || fragmentView?.isShown != true ||
            !field.isAttachedToWindow || otpFields.firstOrNull() !== field
        ) return
        if (otpFields.drop(1).any { it.hasFocus() }) {
            didAutoFocus = true
            return
        }
        if (field.requestFocus()) {
            didAutoFocus = true
            val window = getParentActivity()?.window
            if (window != null) WindowCompat.getInsetsController(window, field).show(WindowInsetsCompat.Type.ime())
            else AndroidUtilities.showKeyboard(field)
        }
    }

    private fun clearPendingFocus() {
        val observer = pendingFocusObserver
        val listener = pendingFocusListener
        if (observer?.isAlive == true && listener != null) {
            observer.removeOnWindowFocusChangeListener(listener)
        }
        pendingFocusObserver = null
        pendingFocusListener = null
    }

    override fun onPause() {
        clearPendingFocus()
        super.onPause()
    }

    private fun updateOtpFieldState(index: Int, filled: Boolean) {
        val bg = otpBackgrounds[index]
        if (filled || otpFields[index].hasFocus()) {
            bg.setColor(Color.WHITE)
            bg.setStroke(LayoutHelper.dp(2), OTP_BORDER_FILLED)
        } else {
            bg.setColor(0xFFFFFFFF.toInt())
            bg.setStroke(LayoutHelper.dp(2), OTP_BORDER_EMPTY)
        }
    }

    private fun setOtpErrorState() {
        for (i in 0 until OTP_LENGTH) {
            val bg = otpBackgrounds[i]
            bg.setColor(0xFFFFE9E9.toInt())
            bg.setStroke(LayoutHelper.dp(2), OTP_BORDER_ERROR)
        }
    }

    private fun getOtpCode(): String =
        otpFields.joinToString("") { it.text?.toString()?.replace(ZERO_WIDTH_SPACE, "") ?: "" }

    private fun startCountdown() {
        isResendEnabled = false
        verifyButton.isEnabled = false
        countdownTimer?.cancel()
        countdownTimer = object : CountDownTimer(COUNTDOWN_MS, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (millisUntilFinished / 1000).toInt()
                verifyButton.setText(getString(R.string.otp_verify, seconds))
            }

            override fun onFinish() {
                isResendEnabled = true
                verifyButton.isEnabled = !verifyButton.isLoading
                verifyButton.setText(getString(R.string.otp_resend))
            }
        }.start()
    }

    private fun doVerify() {
        if (verifyButton.isLoading) return
        val code = getOtpCode()
        if (code.length != OTP_LENGTH) return

        AndroidUtilities.hideKeyboard(fragmentView)
        showLoading()

        fragmentScope.launch(Dispatchers.Main) {
            val result = withContext(entryPoint().ioDispatcher()) {
                authRepository.confirmOTP(reqId, code, isSms)
            }
            result
                .onSuccess {
                    hideLoading()
                    onVerifySuccess?.invoke()
                }
                .onFailure { err ->
                    hideLoading()
                    showError(getString(R.string.otp_invalid))
                    setOtpErrorState()
                    clearOtpFields()
                }
        }
    }

    private fun doResend() {
        if (verifyButton.isLoading) return
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
                    hideLoading()
                    val ctx = getContext() ?: return@onFailure
                    showError(
                        NetworkErrorMessages.userMessage(
                            ctx,
                            err,
                            err.message ?: getString(R.string.otp_resend_failed)
                        )
                    )
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
        verifyButton.isLoading = true
        verifyButton.isEnabled = false
        progressBar.visibility = View.VISIBLE
    }

    private fun hideLoading() {
        verifyButton.isLoading = false
        verifyButton.isEnabled = isResendEnabled
        progressBar.visibility = View.GONE
    }

    private fun showError(message: String) {
        verifyButton.isLoading = false
        verifyButton.isEnabled = isResendEnabled
        progressBar.visibility = View.GONE
        com.mezon.mobile.ui.MezonToast.show(this, com.mezon.mobile.ui.cells.ToastOverlay.ToastType.ERROR, message)
    }

    override fun onFragmentDestroy() {
        clearPendingFocus()
        super.onFragmentDestroy()
        countdownTimer?.cancel()
        countdownTimer = null
    }

    companion object {
        private const val ZERO_WIDTH_SPACE = "\u200B"
        private const val OTP_LENGTH = 6
        private const val COUNTDOWN_MS = 59_000L
        private val OTP_BORDER_EMPTY = 0xFFD1D5DB.toInt()
        private val OTP_BORDER_FILLED = 0xFFC4CBFF.toInt()
        private val OTP_BORDER_ERROR = 0xFFCA0000.toInt()
    }
}
