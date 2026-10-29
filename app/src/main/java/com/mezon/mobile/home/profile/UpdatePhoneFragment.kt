package com.mezon.mobile.home.profile



import android.content.Context

import android.graphics.drawable.GradientDrawable

import android.view.Gravity

import android.view.View

import android.widget.FrameLayout

import android.widget.LinearLayout

import android.widget.TextView

import android.widget.Toast

import com.mezon.mobile.R

import com.mezon.mobile.core.BaseFragment

import com.mezon.mobile.core.LayoutHelper

import com.mezon.mobile.core.NotificationCenter

import com.mezon.mobile.di.FragmentEntryPoint

import com.mezon.mobile.ui.cells.ActionButton

import com.mezon.mobile.ui.cells.InputCell



private const val ARG_CURRENT_PHONE = "current_phone"

private const val PHONE_INLINE_FIELD_HEIGHT_DP = 56



data class CountryCode(val name: String, val prefix: String, val flag: String)



private val COUNTRY_CODES = listOf(

    CountryCode("Vietnam", "+84", "🇻🇳")

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



    private lateinit var countryLabel: TextView

    private lateinit var phoneCell: InputCell

    private lateinit var nextButton: ActionButton

    private lateinit var loadingView: View

    private lateinit var screenRoot: LinearLayout

    private lateinit var contentLayout: LinearLayout

    private val countryFieldBg = GradientDrawable()



    private val selectedCountry = COUNTRY_CODES[0]

    private val otpCooldownCache = mutableMapOf<String, Long>()



    override fun onInject(entryPoint: FragmentEntryPoint) {

        accountController = entryPoint.accountController()

    }



    override fun createView(context: Context): View {

        contentLayout = LinearLayout(context).apply {

            orientation = LinearLayout.VERTICAL

            setBackgroundColor(themeColors.background)

            val pad = LayoutHelper.dp(16)

            setPadding(pad, pad, pad, pad)

        }



        val phoneRow = LinearLayout(context).apply {

            orientation = LinearLayout.HORIZONTAL

            gravity = Gravity.BOTTOM

        }



        countryLabel = TextView(context).apply {

            text = "${selectedCountry.flag} ${selectedCountry.prefix}"

            textSize = 15f

            maxLines = 1

            gravity = Gravity.CENTER

            val hPad = LayoutHelper.dp(12)

            setPadding(hPad, 0, hPad, 0)

        }

        refreshCountryFieldChrome()

        phoneRow.addView(
            countryLabel,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LayoutHelper.dp(PHONE_INLINE_FIELD_HEIGHT_DP))

        )



        phoneCell = InputCell(context, themeColors).apply {

            setLabel(getString(R.string.phone_new_number_label))

            setHint(getString(R.string.phone_number_hint))

            editText.inputType = android.text.InputType.TYPE_CLASS_PHONE

            if (currentPhone.isNotEmpty()) {

                val country = COUNTRY_CODES.find { currentPhone.startsWith(it.prefix) }

                setText(if (country != null) currentPhone.removePrefix(country.prefix) else currentPhone)

            }

            setInputContainerMinHeightDp(PHONE_INLINE_FIELD_HEIGHT_DP)

        }

        phoneRow.addView(phoneCell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {

            marginStart = LayoutHelper.dp(8)

        })

        contentLayout.addView(phoneRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0, 0f, 0f, 0f, 24f))

        contentLayout.addView(View(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))



        nextButton = ActionButton(context, themeColors).apply {

            setText(getString(R.string.phone_next_button))

            isEnabled = false

        }

        contentLayout.addView(nextButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48))



        val rootFrame = FrameLayout(context)

        screenRoot = wrapWithActionBar(getString(R.string.update_phone_title), contentLayout) as LinearLayout

        rootFrame.addView(screenRoot, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        loadingView = View(context).apply {

            setBackgroundColor(0x88000000.toInt())

            visibility = View.GONE

            isClickable = true

        }

        rootFrame.addView(loadingView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))



        phoneCell.onTextChanged = { updateButtonState() }

        nextButton.setOnClickListener { handleNext() }



        return rootFrame

    }



    override fun onFragmentCreate(): Boolean {

        super.onFragmentCreate()



        observe(NotificationCenter.themeChanged) { _, _, _ ->

            applyPhoneScreenTheme()

        }



        return true

    }



    private fun refreshCountryFieldChrome() {

        countryFieldBg.apply {

            setColor(themeColors.surfaceVariant)

            cornerRadius = LayoutHelper.dpf(12f)

            setStroke(LayoutHelper.dp(1), themeColors.outlineVariant)

        }

        if (::countryLabel.isInitialized) {

            countryLabel.background = countryFieldBg

            countryLabel.setTextColor(themeColors.onSurface)

        }

    }



    private fun applyPhoneScreenTheme() {

        fragmentView?.setBackgroundColor(themeColors.background)

        if (::screenRoot.isInitialized) screenRoot.setBackgroundColor(themeColors.background)

        if (::contentLayout.isInitialized) contentLayout.setBackgroundColor(themeColors.background)

        if (::phoneCell.isInitialized) phoneCell.refreshThemeColors()

        refreshCountryFieldChrome()

        if (::nextButton.isInitialized) nextButton.invalidate()

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
