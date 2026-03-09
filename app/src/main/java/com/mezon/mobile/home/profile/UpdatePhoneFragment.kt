package com.mezon.mobile.home.profile

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.ui.cells.ActionButton
import com.mezon.mobile.ui.cells.InputCell
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

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

@AndroidEntryPoint
class UpdatePhoneFragment : BaseFragment() {

    companion object {
        fun newInstance(currentPhone: String): UpdatePhoneFragment {
            return UpdatePhoneFragment().apply {
                arguments = Bundle().apply { putString(ARG_CURRENT_PHONE, currentPhone) }
            }
        }
    }

    @Inject lateinit var accountController: AccountController

    var onPhoneVerified: (() -> Unit)? = null

    private val currentPhone get() = arguments?.getString(ARG_CURRENT_PHONE) ?: ""

    private lateinit var countrySpinner: Spinner
    private lateinit var phoneCell: InputCell
    private lateinit var nextButton: ActionButton
    private lateinit var loadingView: View

    private var selectedCountry = COUNTRY_CODES[0]
    private val otpCooldownCache = mutableMapOf<String, Long>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.background)
            val pad = LayoutHelper.dp(16)
            setPadding(pad, pad, pad, pad)
        }

        val phoneRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        countrySpinner = Spinner(requireContext()).apply {
            val items = COUNTRY_CODES.map { "${it.flag} ${it.prefix}" }
            adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, items).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setBackgroundColor(themeColors.surface)
        }
        phoneRow.addView(countrySpinner, LinearLayout.LayoutParams(LayoutHelper.dp(110), LayoutHelper.dp(52)))

        phoneCell = InputCell(requireContext(), themeColors).apply {
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

        val spacer = View(requireContext())
        content.addView(spacer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        nextButton = ActionButton(requireContext(), themeColors).apply {
            setText(getString(R.string.phone_next_button))
            isEnabled = false
        }
        content.addView(nextButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48))

        val rootFrame = FrameLayout(requireContext())
        rootFrame.addView(wrapWithActionBar(getString(R.string.update_phone_title), content), LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))
        loadingView = View(requireContext()).apply {
            setBackgroundColor(0x88000000.toInt())
            visibility = View.GONE
        }
        rootFrame.addView(loadingView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))
        return rootFrame
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

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

        observe(NotificationCenter.themeChanged) { _, _ ->
            view.setBackgroundColor(themeColors.background)
        }
    }

    private fun isValidPhone(number: String): Boolean {
        if (number.isEmpty()) return false
        if (selectedCountry.prefix == "+84") {
            return Regex("^0?(3|5|7|8|9)[0-9]{8}$").matches(number)
        }
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
            phoneCell.setError(getString(R.string.phone_already_linked))
            return
        }

        val lastSent = otpCooldownCache[fullPhone] ?: 0L
        val elapsed = ((System.currentTimeMillis() - lastSent) / 1000).toInt()
        if (elapsed < 60) {
            phoneCell.setError(getString(R.string.email_too_fast, 60 - elapsed))
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
                requireActivity().supportFragmentManager.beginTransaction()
                    .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out, android.R.anim.fade_in, android.R.anim.fade_out)
                    .replace(R.id.fragment_container, fragment)
                    .addToBackStack(null)
                    .commit()
            } else {
                phoneCell.setError(errorMsg.ifEmpty { getString(R.string.phone_link_failed) })
                nextButton.isEnabled = true
            }
        }
    }
}
