package com.mezon.mobile.home.profile

import android.content.Context
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.ui.cells.ActionButton

private const val ARG_CONTACT = "contact"
private const val ARG_REQ_ID = "req_id"
private const val ARG_IS_PHONE = "is_phone"

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

    private val otpFields = mutableListOf<EditText>()
    private lateinit var errorLabel: TextView
    private lateinit var verifyButton: ActionButton
    private lateinit var loadingView: View

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

        val otpRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        repeat(6) { index ->
            val field = EditText(context).apply {
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(themeColors.onSurface)
                background = null
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                filters = arrayOf(InputFilter.LengthFilter(1))
                setBackgroundColor(themeColors.surface)
            }
            otpFields.add(field)
            val margin = if (index < 5) LayoutHelper.dp(8) else 0
            otpRow.addView(field, LinearLayout.LayoutParams(LayoutHelper.dp(44), LayoutHelper.dp(52)).apply { marginEnd = margin })
        }
        content.addView(otpRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0, 0f, 0f, 0f, 8f))

        errorLabel = TextView(context).apply {
            textSize = 12f
            setTextColor(themeColors.error)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        content.addView(errorLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0, 0f, 0f, 0f, 24f))
        content.addView(View(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        verifyButton = ActionButton(context, themeColors).apply {
            setText(getString(R.string.otp_verify_button))
            isEnabled = false
        }
        content.addView(verifyButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48))

        val titleRes = if (isPhone) R.string.verify_phone_title else R.string.verify_email_title
        val rootFrame = FrameLayout(context)
        rootFrame.addView(wrapWithActionBar(getString(titleRes), content), LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))
        loadingView = View(context).apply {
            setBackgroundColor(0x88000000.toInt())
            visibility = View.GONE
        }
        rootFrame.addView(loadingView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        wireOtpFields()
        verifyButton.setOnClickListener { handleVerify() }

        return rootFrame
    }

    private fun wireOtpFields() {
        otpFields.forEachIndexed { index, field ->
            field.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    errorLabel.visibility = View.GONE
                    if (s?.length == 1 && index < otpFields.size - 1) otpFields[index + 1].requestFocus()
                    val code = otpFields.joinToString("") { it.text.toString() }
                    val complete = code.length == 6
                    verifyButton.isEnabled = complete
                    if (complete) handleVerify()
                }
            })
            field.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DEL
                    && field.text.isEmpty() && index > 0
                ) {
                    otpFields[index - 1].apply { setText(""); requestFocus() }
                    true
                } else false
            }
        }
    }

    private fun handleVerify() {
        val code = otpFields.joinToString("") { it.text.toString() }
        if (code.length != 6) return
        loadingView.visibility = View.VISIBLE
        verifyButton.isEnabled = false
        accountController.confirmLinkOTP(reqId, code) { success, errorMsg ->
            loadingView.visibility = View.GONE
            if (success) {
                onVerified?.invoke()
                finishFragment()
                finishFragment()
            } else {
                otpFields.forEach { it.setText("") }
                otpFields.firstOrNull()?.requestFocus()
                errorLabel.text = errorMsg.ifEmpty { getString(R.string.otp_verify_failed) }
                errorLabel.visibility = View.VISIBLE
                verifyButton.isEnabled = false
            }
        }
    }
}
