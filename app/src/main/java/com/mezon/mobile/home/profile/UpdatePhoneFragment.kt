package com.mezon.mobile.home.profile

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.ui.cells.ActionButton
import com.mezon.mobile.ui.cells.InputCell

private const val ARG_CURRENT_PHONE = "current_phone"

data class CountryCode(val name: String, val prefix: String, val flag: String)

private val COUNTRY_CODES = listOf(
    CountryCode("Vietnam", "+84", "🇻🇳"),
    CountryCode("United States", "+1", "🇺🇸"),
    CountryCode("United Kingdom", "+44", "🇬🇧"),
    CountryCode("Singapore", "+65", "🇸🇬"),
    CountryCode("Japan", "+81", "🇯🇵"),
    CountryCode("South Korea", "+82", "🇰🇷"),
    CountryCode("Australia", "+61", "🇦🇺"),
    CountryCode("Germany", "+49", "🇩🇪"),
    CountryCode("France", "+33", "🇫🇷"),
    CountryCode("India", "+91", "🇮🇳")
)

class UpdatePhoneFragment : BaseFragment() {

    companion object {
        fun newInstance(currentPhone: String): UpdatePhoneFragment {
            return UpdatePhoneFragment().apply {
                arguments = android.os.Bundle().apply { putString(ARG_CURRENT_PHONE, currentPhone) }
            }
        }
    }

    private lateinit var accountController: AccountController

    var onPhoneVerified: (() -> Unit)? = null

    private val currentPhone get() = arguments?.getString(ARG_CURRENT_PHONE) ?: ""

    private lateinit var countrySpinner: Spinner
    private lateinit var phoneCell: InputCell
    private lateinit var nextButton: ActionButton
    private lateinit var loadingView: View

    private var selectedCountry = COUNTRY_CODES[0]
    private val otpCooldownCache = mutableMapOf<String, Long>()

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

        val phoneRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

        countrySpinner = Spinner(context).apply {
            val items = COUNTRY_CODES.map { "${it.flag} ${it.prefix}" }
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, items).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setBackgroundColor(themeColors.surface)
        }
        phoneRow.addView(countrySpinner, LinearLayout.LayoutParams(LayoutHelper.dp(110), LayoutHelper.dp(52)))

        phoneCell = InputCell(context, themeColors).apply {
            setLabel(getString(R.string.phone_new_number_label))
            setHint(getString(R.string.phone_number_hint))
            editText.inputType = android.text.InputType.TYPE_CLASS_PHONE
            if (currentPhone.isNotEmpty()) {
                val country = COUNTRY_CODES.find { currentPhone.startsWith(it.prefix) }
                setText(if (country != null) currentPhone.removePrefix(country.prefix) else currentPhone)
            }
        }
        phoneRow.addView(phoneCell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = LayoutHelper.dp(8)
        })
        content.addView(phoneRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0, 0f, 0f, 0f, 24f))
        content.addView(View(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        nextButton = ActionButton(context, themeColors).apply {
            setText(getString(R.string.phone_next_button))
            isEnabled = false
        }
        content.addView(nextButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48))

        val rootFrame = FrameLayout(context)
        rootFrame.addView(wrapWithActionBar(getString(R.string.update_phone_title), content), LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))
        loadingView = View(context).apply {
            setBackgroundColor(0x88000000.toInt())
            visibility = View.GONE
        }
        rootFrame.addView(loadingView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        if (currentPhone.isNotEmpty()) {
            val country = COUNTRY_CODES.find { currentPhone.startsWith(it.prefix) }
            if (country != null) {
                countrySpinner.setSelection(COUNTRY_CODES.indexOf(country))
                selectedCountry = country
            }
        }

        countrySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                selectedCountry = COUNTRY_CODES[position]
                updateButtonState()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        phoneCell.onTextChanged = { updateButtonState() }
        nextButton.setOnClickListener { handleNext() }

        return rootFrame
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()

        observe(NotificationCenter.themeChanged) { _, _, _ ->
            fragmentView?.setBackgroundColor(themeColors.background)
        }

        return true
    }

    private fun isValidPhone(number: String): Boolean {
        if (number.isEmpty()) return false
        if (selectedCountry.prefix == "+84") return Regex("^0?(3|5|7|8|9)[0-9]{8}$").matches(number)
        return number.length >= 7 && number.all { it.isDigit() }
    }

    private fun updateButtonState() {
        nextButton.isEnabled = isValidPhone(phoneCell.getText().trim())
    }

    private fun buildFullPhone(): String {
        var phone = phoneCell.getText().trim()
        if (selectedCountry.prefix == "+84" && phone.startsWith("0")) phone = phone.drop(1)
        return "${selectedCountry.prefix}$phone"
    }

    private fun handleNext() {
        val fullPhone = buildFullPhone()
        if (fullPhone == currentPhone && fullPhone.length > 4) {
            showToast(getString(R.string.phone_already_linked))
            return
        }
        val lastSent = otpCooldownCache[fullPhone] ?: 0L
        val elapsed = ((System.currentTimeMillis() - lastSent) / 1000).toInt()
        if (elapsed < 60) {
            showToast(getString(R.string.email_too_fast, 60 - elapsed))
            return
        }
        loadingView.visibility = View.VISIBLE
        nextButton.isEnabled = false
        accountController.linkPhone(fullPhone) { success, reqId, errorMsg ->
            loadingView.visibility = View.GONE
            if (success) {
                otpCooldownCache[fullPhone] = System.currentTimeMillis()
                val fragment = VerifyOtpFragment.newInstance(fullPhone, reqId, isPhone = true)
                fragment.onVerified = { onPhoneVerified?.invoke() }
                presentFragment(fragment)
            } else {
                showToast(errorMsg.ifEmpty { getString(R.string.phone_link_failed) })
                nextButton.isEnabled = true
            }
        }
    }

    private fun showToast(message: String) {
        val ctx = getContext() ?: return
        Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
    }
}
