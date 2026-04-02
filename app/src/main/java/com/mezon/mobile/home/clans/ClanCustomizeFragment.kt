package com.mezon.mobile.home.clans

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.ImagePickerHelper
import com.mezon.mobile.ui.cells.ImagePickerView
import com.mezon.mobile.ui.cells.MezonIcon

class ClanCustomizeFragment : BaseFragment() {

    var onSubmitClan: ((name: String, avatarUri: Uri?, templateId: String?) -> Unit)? = null
    var templateId: String? = null

    private var pickedImageUri: Uri? = null
    private lateinit var nameInput: EditText
    private lateinit var errorText: TextView
    private lateinit var pickerView: ImagePickerView
    private lateinit var picker: ImagePickerHelper

    override fun createView(context: Context): View {
        picker = ImagePickerHelper(this) { uri ->
            pickedImageUri = uri
            pickerView.setImageUri(uri)
        }

        val root = wrapWithActionBar(context.getString(R.string.clan_customize_title), buildContent(context))
        actionBar?.setCenterTitle(true)
        return root
    }

    private fun buildContent(context: Context): View {
        val scroll = ScrollView(context)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(16), LayoutHelper.dp(12), LayoutHelper.dp(16), LayoutHelper.dp(24))
        }

        val subtitle = TextView(context).apply {
            text = context.getString(R.string.clan_customize_subtitle)
            setTextColor(themeColors.onSurfaceVariant)
            textSize = 14f
            gravity = Gravity.CENTER_HORIZONTAL
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding(0, 0, 0, LayoutHelper.dp(20))
        }
        container.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        pickerView = ImagePickerView(context, themeColors).apply {
            setRounded(true)
            setSizeDp(120)
            setUploadStyle(true, context.getString(R.string.clan_upload_label))
            onClickPick = { picker.launch() }
        }
        val pickerWrapper = FrameLayout(context).apply {
            addView(pickerView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER))
        }
        container.addView(pickerWrapper, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0, 0f, 0f, 0f, 16f))

        val nameLabel = TextView(context).apply {
            text = context.getString(R.string.clan_name_label)
            setTextColor(themeColors.onSurface)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, LayoutHelper.dp(8), 0, LayoutHelper.dp(8))
        }
        container.addView(nameLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        nameInput = EditText(context).apply {
            hint = context.getString(R.string.clan_name_placeholder)
            setTextColor(themeColors.onSurface)
            setHintTextColor(themeColors.onSurfaceVariant)
            background = GradientDrawable().apply {
                setColor(themeColors.surfaceVariant)
                cornerRadius = LayoutHelper.dp(12).toFloat()
            }
            textSize = 16f
            minHeight = LayoutHelper.dp(48)
            setPadding(LayoutHelper.dp(12), LayoutHelper.dp(12), LayoutHelper.dp(12), LayoutHelper.dp(12))
        }
        nameInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { hideError() }
            override fun afterTextChanged(s: Editable?) {}
        })
        container.addView(nameInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        errorText = TextView(context).apply {
            text = context.getString(R.string.clan_name_error)
            setTextColor(themeColors.error)
            textSize = 12f
            visibility = View.GONE
            setPadding(0, LayoutHelper.dp(6), 0, 0)
            val icon = MezonIcon.circleExlaimionIcon.getDrawable(context, themeColors.error)
            val iconSize = LayoutHelper.dp(14)
            icon.setBounds(0, 0, iconSize, iconSize)
            setCompoundDrawables(icon, null, null, null)
            compoundDrawablePadding = LayoutHelper.dp(6)
        }
        container.addView(errorText)

        val linkText = context.getString(R.string.clan_terms_link)
        val termsText = context.getString(R.string.clan_terms_text, linkText)
        val spannable = SpannableString(termsText)
        val linkStart = termsText.indexOf(linkText)
        if (linkStart >= 0) {
            spannable.setSpan(
                ForegroundColorSpan(themeColors.primary),
                linkStart,
                linkStart + linkText.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        val tos = TextView(context).apply {
            text = spannable
            setTextColor(themeColors.onSurfaceVariant)
            textSize = 13f
            setPadding(0, LayoutHelper.dp(12), 0, LayoutHelper.dp(16))
        }
        container.addView(tos)

        val createBtn = TextView(context).apply {
            text = context.getString(R.string.clan_create)
            gravity = Gravity.CENTER
            setTextColor(themeColors.onPrimary)
            background = GradientDrawable().apply {
                setColor(themeColors.primary)
                cornerRadius = LayoutHelper.dp(12).toFloat()
            }
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            minHeight = LayoutHelper.dp(48)
            setPadding(LayoutHelper.dp(12), LayoutHelper.dp(14), LayoutHelper.dp(12), LayoutHelper.dp(14))
            setOnClickListener { onSubmit() }
        }
        container.addView(createBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        scroll.addView(container, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        return scroll
    }

    private fun hideError() { errorText.visibility = View.GONE }

    private fun showError() { errorText.visibility = View.VISIBLE }

    private fun onSubmit() {
        val name = nameInput.text?.toString()?.trim().orEmpty()
        val valid = name.isNotEmpty() && NAME_PATTERN.matches(name)
        if (!valid) {
            showError()
            return
        }
        onSubmitClan?.invoke(name, pickedImageUri, templateId)
        if (onSubmitClan == null) {
            Toast.makeText(requireContext(), R.string.common_pending, Toast.LENGTH_SHORT).show()
        }
        finishFragment()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (picker.handleActivityResult(requestCode, resultCode, data)) {
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    companion object {
        private val NAME_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")

        fun newInstance(templateId: String?): ClanCustomizeFragment = ClanCustomizeFragment().apply {
            this.templateId = templateId
        }
    }
}
