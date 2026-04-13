package com.mezon.mobile.home.profile

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.ui.cells.ActionButton
import com.mezon.mobile.ui.cells.ToastOverlay

private const val ARG_CONTACT = "contact"
private const val ARG_REQ_ID = "req_id"
private const val ARG_IS_PHONE = "is_phone"
private const val OTP_LENGTH = 6

class VerifyOtpFragment : BaseFragment() {

    companion object {
        fun newInstance(contact: String, reqId: String, isPhone: Boolean): VerifyOtpFragment {
            return VerifyOtpFragment().apply {
                arguments = android.os.Bundle().apply {
                    putString(ARG_CONTACT, contact)
                    putString(ARG_REQ_ID, reqId)
                    putBoolean(ARG_IS_PHONE, isPhone)
                }
            }
        }
    }

    private lateinit var accountController: AccountController

    var onVerified: (() -> Unit)? = null

    private val contact get() = arguments?.getString(ARG_CONTACT) ?: ""
    private val reqId get() = arguments?.getString(ARG_REQ_ID) ?: ""
    private val isPhone get() = arguments?.getBoolean(ARG_IS_PHONE) ?: false

    private lateinit var hiddenInput: EditText
    private val otpCells = ArrayList<TextView>(OTP_LENGTH)
    private val otpBgs = ArrayList<GradientDrawable>(OTP_LENGTH)
    private val otpCursors = ArrayList<View>(OTP_LENGTH)
    private var otpCursorAnimator: ObjectAnimator? = null
    private lateinit var verifyButton: ActionButton
    private lateinit var loadingView: View
    private lateinit var rootFrame: FrameLayout
    private var updating = false

    override fun onInject(entryPoint: FragmentEntryPoint) {
        accountController = entryPoint.accountController()
    }

    override fun createView(context: Context): View {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.background)
            val pad = LayoutHelper.dp(24)
            setPadding(pad, pad, pad, pad)
        }

        val descText = TextView(context).apply {
            val descRes = if (isPhone) R.string.otp_description_phone else R.string.otp_description_email
            text = getString(descRes, contact)
            textSize = 14f
            setTextColor(themeColors.onSurfaceVariant)
            gravity = Gravity.CENTER
        }
        content.addView(descText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0, 0f, 0f, 0f, 32f))

        val otpContainer = FrameLayout(context)

        val otpRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        for (i in 0 until OTP_LENGTH) {
            val bg = GradientDrawable().apply {
                setColor(themeColors.surfaceVariant)
                cornerRadius = LayoutHelper.dpf(12f)
                setStroke(LayoutHelper.dp(1), themeColors.outlineVariant)
            }.mutate() as GradientDrawable
            otpBgs.add(bg)

            val cell = TextView(context).apply {
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(themeColors.onSurface)
                background = bg
                val p = LayoutHelper.dp(4)
                setPadding(p, p, p, p)
            }
            otpCells.add(cell)

            val cursor = View(context).apply {
                layoutParams = FrameLayout.LayoutParams(LayoutHelper.dp(2), LayoutHelper.dp(24)).apply {
                    gravity = Gravity.CENTER
                }
                setBackgroundColor(themeColors.primary)
                visibility = View.GONE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            otpCursors.add(cursor)

            val cellFrame = FrameLayout(context).apply {
                addView(cell, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ))
                addView(cursor)
            }
            val margin = if (i < OTP_LENGTH - 1) LayoutHelper.dp(8) else 0
            otpRow.addView(cellFrame, LinearLayout.LayoutParams(LayoutHelper.dp(48), LayoutHelper.dp(56)).apply { marginEnd = margin })
        }
        otpContainer.addView(otpRow, FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        hiddenInput = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_DONE
            isCursorVisible = false
            setTextColor(Color.TRANSPARENT)
            background = ColorDrawable(Color.TRANSPARENT)
            isSingleLine = true
            maxLines = 1
            filters = arrayOf(InputFilter.LengthFilter(OTP_LENGTH))
            setPadding(0, 0, 0, 0)
        }
        otpContainer.addView(hiddenInput, FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        otpContainer.setOnClickListener {
            hiddenInput.requestFocus()
            AndroidUtilities.showKeyboard(hiddenInput)
        }

        hiddenInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (updating) return
                updating = true

                val raw = s?.toString() ?: ""
                val digits = raw.filter { it.isDigit() }.take(OTP_LENGTH)
                if (raw != digits) {
                    hiddenInput.setText(digits)
                    hiddenInput.setSelection(digits.length)
                }

                for (i in 0 until OTP_LENGTH) {
                    otpCells[i].text = if (i < digits.length) digits[i].toString() else ""
                    updateCellStyle(i, i < digits.length)
                }
                updateOtpCursorUi(digits.length)

                verifyButton.isEnabled = digits.length == OTP_LENGTH
                if (digits.length == OTP_LENGTH) handleVerify()

                updating = false
            }
        })

        content.addView(otpContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0, 0f, 0f, 0f, 8f))

        content.addView(View(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        verifyButton = ActionButton(context, themeColors).apply {
            setText(getString(R.string.otp_verify_button))
            isEnabled = false
        }
        content.addView(verifyButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48))

        val titleRes = if (isPhone) R.string.verify_phone_title else R.string.verify_email_title
        rootFrame = FrameLayout(context)
        rootFrame.addView(wrapWithActionBar(getString(titleRes), content), LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))
        loadingView = View(context).apply {
            setBackgroundColor(0x88000000.toInt())
            visibility = View.GONE
            isClickable = true
        }
        rootFrame.addView(loadingView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        verifyButton.setOnClickListener { handleVerify() }

        rootFrame.post {
            hiddenInput.requestFocus()
            AndroidUtilities.showKeyboard(hiddenInput)
            updateOtpCursorUi(0)
        }

        return rootFrame
    }

    override fun onFragmentDestroy() {
        otpCursorAnimator?.cancel()
        otpCursorAnimator = null
        super.onFragmentDestroy()
    }

    private fun updateOtpCursorUi(filledCount: Int) {
        otpCursorAnimator?.cancel()
        otpCursorAnimator = null
        for (i in 0 until OTP_LENGTH) {
            otpCursors[i].visibility = View.GONE
            otpCursors[i].alpha = 1f
        }
        if (filledCount >= OTP_LENGTH) return
        val v = otpCursors[filledCount]
        v.visibility = View.VISIBLE
        otpCursorAnimator = ObjectAnimator.ofFloat(v, View.ALPHA, 1f, 0.15f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    private fun getOtpCode(): String =
        hiddenInput.text?.toString()?.filter { it.isDigit() } ?: ""

    private fun updateCellStyle(index: Int, filled: Boolean) {
        otpBgs[index].setStroke(
            LayoutHelper.dp(1),
            if (filled) themeColors.primary else themeColors.outlineVariant
        )
    }

    private fun clearOtpFields() {
        updating = true
        hiddenInput.setText("")
        for (i in 0 until OTP_LENGTH) {
            otpCells[i].text = ""
            updateCellStyle(i, false)
        }
        updateOtpCursorUi(0)
        updating = false
        verifyButton.isEnabled = false
        hiddenInput.requestFocus()
    }

    private fun showToast(type: ToastOverlay.ToastType, title: String) {
        val overlay = ToastOverlay(requireContext(), themeColors)
        (getParentActivity()?.findViewById<ViewGroup>(android.R.id.content) ?: rootFrame as? ViewGroup)?.let { root ->
            overlay.show(root, type, title, null)
        }
    }

    private fun handleVerify() {
        val code = getOtpCode()
        if (code.length != OTP_LENGTH) return
        loadingView.visibility = View.VISIBLE
        verifyButton.isEnabled = false

        accountController.confirmLinkOTP(reqId, code, contact, isPhone) { success, _ ->
            loadingView.visibility = View.GONE
            if (success) {
                val successMsg = if (isPhone) {
                    getString(R.string.phone_verify_success)
                } else {
                    getString(R.string.email_verify_success)
                }
                showToast(ToastOverlay.ToastType.SUCCESS, successMsg)
                onVerified?.invoke()
                finishFragment()
                finishFragment()
            } else {
                clearOtpFields()
                showToast(ToastOverlay.ToastType.ERROR, getString(R.string.otp_verify_failed))
            }
        }
    }
}
