package com.mezon.mobile.home.profile

import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

private const val ARG_CONTACT = "contact"
private const val ARG_REQ_ID = "req_id"
private const val ARG_IS_PHONE = "is_phone"

@AndroidEntryPoint
class VerifyOtpFragment : BaseFragment() {

    companion object {
        fun newInstance(contact: String, reqId: String, isPhone: Boolean): VerifyOtpFragment {
            return VerifyOtpFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_CONTACT, contact)
                    putString(ARG_REQ_ID, reqId)
                    putBoolean(ARG_IS_PHONE, isPhone)
                }
            }
        }
    }

    @Inject lateinit var accountController: AccountController

    var onVerified: (() -> Unit)? = null

    private val contact get() = arguments?.getString(ARG_CONTACT) ?: ""
    private val reqId get() = arguments?.getString(ARG_REQ_ID) ?: ""
    private val isPhone get() = arguments?.getBoolean(ARG_IS_PHONE) ?: false

    private val otpFields = mutableListOf<EditText>()
    private lateinit var errorLabel: TextView
    private lateinit var verifyButton: Button
    private lateinit var loadingView: View

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.background)
            val pad = LayoutHelper.dp(24)
            setPadding(pad, pad, pad, pad)
        }

        val descText = TextView(requireContext()).apply {
            val descRes = if (isPhone) R.string.otp_description_phone else R.string.otp_description_email
            text = getString(descRes, contact)
            textSize = 14f
            setTextColor(themeColors.onSurfaceVariant)
            gravity = Gravity.CENTER
        }
        content.addView(descText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0, 0f, 0f, 0f, 32f))

        val otpRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        repeat(6) { index ->
            val field = EditText(requireContext()).apply {
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
            otpRow.addView(field, LinearLayout.LayoutParams(LayoutHelper.dp(44), LayoutHelper.dp(52)).apply {
                marginEnd = margin
            })
        }
        content.addView(otpRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0, 0f, 0f, 0f, 8f))

        errorLabel = TextView(requireContext()).apply {
            textSize = 12f
            setTextColor(themeColors.error)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        content.addView(errorLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0, 0f, 0f, 0f, 24f))

        val spacer = View(requireContext())
        content.addView(spacer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        verifyButton = Button(requireContext()).apply {
            text = getString(R.string.otp_verify_button)
            textSize = 16f
            setTextColor(themeColors.onPrimary)
            setBackgroundColor(themeColors.primary)
            isEnabled = false
            alpha = 0.5f
        }
        content.addView(verifyButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48))

        val titleRes = if (isPhone) R.string.verify_phone_title else R.string.verify_email_title
        val rootFrame = FrameLayout(requireContext())
        rootFrame.addView(wrapWithActionBar(getString(titleRes), content), LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        loadingView = View(requireContext()).apply {
            setBackgroundColor(0x88000000.toInt())
            visibility = View.GONE
        }
        rootFrame.addView(loadingView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        return rootFrame
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        wireOtpFields()
        verifyButton.setOnClickListener { handleVerify() }
    }

    private fun wireOtpFields() {
        otpFields.forEachIndexed { index, field ->
            field.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    errorLabel.visibility = View.GONE
                    if (s?.length == 1 && index < otpFields.size - 1) {
                        otpFields[index + 1].requestFocus()
                    }
                    val code = otpFields.joinToString("") { it.text.toString() }
                    val complete = code.length == 6
                    verifyButton.isEnabled = complete
                    verifyButton.alpha = if (complete) 1f else 0.5f
                    if (complete) handleVerify()
                }
            })
            field.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DEL
                    && field.text.isEmpty() && index > 0
                ) {
                    otpFields[index - 1].apply {
                        setText("")
                        requestFocus()
                    }
                    true
                } else false
            }
        }
    }

    private fun getOtpCode() = otpFields.joinToString("") { it.text.toString() }

    private fun handleVerify() {
        val code = getOtpCode()
        if (code.length != 6) return

        loadingView.visibility = View.VISIBLE
        verifyButton.isEnabled = false

        accountController.confirmLinkOTP(reqId, code) { success, errorMsg ->
            loadingView.visibility = View.GONE
            if (success) {
                onVerified?.invoke()
                requireActivity().supportFragmentManager.popBackStack()
                requireActivity().supportFragmentManager.popBackStack()
            } else {
                otpFields.forEach { it.setText("") }
                otpFields.firstOrNull()?.requestFocus()
                errorLabel.text = errorMsg.ifEmpty { getString(R.string.otp_verify_failed) }
                errorLabel.visibility = View.VISIBLE
                verifyButton.isEnabled = false
                verifyButton.alpha = 0.5f
            }
        }
    }
}
