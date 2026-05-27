package com.mezon.mobile.auth

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.mezon.mobile.MainActivity
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.ui.cells.ActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UpdateUsernameFragment : BaseFragment() {

    companion object {
        private const val MIN_USERNAME_LENGTH = 1
        private const val MAX_USERNAME_LENGTH = 32
        private val USERNAME_DISALLOWED_CHARS = Regex("[^a-z0-9._\\-+]")
    }

    var onComplete: (() -> Unit)? = null

    private lateinit var authRepository: AuthRepository

    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var input: EditText
    private lateinit var previewView: TextView
    private lateinit var errorText: TextView
    private lateinit var submitButton: ActionButton
    private lateinit var progressBar: ProgressBar
    private lateinit var changePhoneQuestion: TextView
    private lateinit var changePhoneLink: TextView

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
            text = getString(R.string.update_username_title)
            setTextColor(0xFF000000.toInt())
            textSize = 24f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        form.addView(titleView, LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER, 0f, 0f, 0f, 12f
        ))

        subtitleView = TextView(context).apply {
            text = getString(R.string.update_username_subtitle)
            textSize = 14f
            setTextColor(0xFF505050.toInt())
            gravity = Gravity.CENTER
        }
        form.addView(subtitleView, LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER, 0f, 0f, 0f, 24f
        ))

        val inputBg = GradientDrawable().apply {
            setColor(0xFFFFFFFF.toInt())
            cornerRadius = LayoutHelper.dpf(8f)
            setStroke(LayoutHelper.dp(1), 0xFFD1D5DB.toInt())
        }
        input = EditText(context).apply {
            hint = getString(R.string.update_username_hint)
            setHintTextColor(0xFF9CA3AF.toInt())
            setTextColor(0xFF374151.toInt())
            textSize = 16f
            setPadding(LayoutHelper.dp(12), LayoutHelper.dp(12), LayoutHelper.dp(12), LayoutHelper.dp(12))
            background = inputBg
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            isSingleLine = true
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    doSubmit()
                    true
                } else false
            }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    errorText.visibility = View.GONE
                    val raw = s?.toString().orEmpty()
                    val sanitized = sanitizeUsername(raw)
                    if (raw != sanitized) {
                        val cursor = input.selectionStart.coerceAtLeast(0)
                        val newCursor = sanitizeUsername(raw.take(cursor)).length
                        input.setText(sanitized)
                        input.setSelection(newCursor.coerceIn(0, sanitized.length))
                    }
                    updateUsernameUi(sanitized)
                }
            })
        }
        form.addView(input, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER, 0f, 0f, 0f, 8f))

        previewView = TextView(context).apply {
            textSize = 13f
            setTextColor(0xFF505050.toInt())
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        form.addView(previewView, LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER, 0f, 0f, 0f, 8f
        ))

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
            LayoutHelper.MATCH_PARENT, 50, 0f, Gravity.START, 0f, 8f, 0f, 24f
        ))

        submitButton = ActionButton(context, themeColors).apply {
            useGradient = true
            setText(getString(R.string.update_username_button))
            isEnabled = false
            setOnClickListener { doSubmit() }
        }
        buttonFrame.addView(submitButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        progressBar = ProgressBar(context).apply { visibility = View.GONE }
        buttonFrame.addView(progressBar, LayoutHelper.createFrame(24, 24, Gravity.CENTER))

        changePhoneQuestion = TextView(context).apply {
            text = getString(R.string.update_username_change_phone_question)
            textSize = 14f
            setTextColor(0xFF5E5E5E.toInt())
            gravity = Gravity.CENTER
        }
        form.addView(changePhoneQuestion, LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER, 0f, 0f, 0f, 6f
        ))

        changePhoneLink = TextView(context).apply {
            text = getString(R.string.update_username_change_phone_link)
            textSize = 14f
            setTextColor(0xFF2E22FF.toInt())
            gravity = Gravity.CENTER
            setOnClickListener {
                (getParentActivity() as? MainActivity)?.logoutToChooseDifferentPhone()
            }
        }
        form.addView(changePhoneLink, LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER, 0f, 0f, 0f, 0f
        ))

        input.requestFocus()
        AndroidUtilities.showKeyboard(input)

        return root
    }

    private fun sanitizeUsername(raw: String): String {
        return raw.lowercase()
            .replace(USERNAME_DISALLOWED_CHARS, "")
            .take(MAX_USERNAME_LENGTH)
    }

    private fun isUsernameValid(username: String): Boolean =
        username.length in MIN_USERNAME_LENGTH..MAX_USERNAME_LENGTH

    private fun updateUsernameUi(username: String) {
        if (username.isNotEmpty()) {
            previewView.text = getString(R.string.update_username_preview, username)
            previewView.visibility = View.VISIBLE
        } else {
            previewView.visibility = View.GONE
        }
        submitButton.isEnabled = isUsernameValid(username)
    }

    private fun doSubmit() {
        val sanitized = sanitizeUsername(input.text?.toString().orEmpty())
        if (!isUsernameValid(sanitized)) return

        AndroidUtilities.hideKeyboard(fragmentView)
        submitButton.isEnabled = false
        submitButton.visibility = View.INVISIBLE
        progressBar.visibility = View.VISIBLE
        errorText.visibility = View.GONE

        fragmentScope.launch(Dispatchers.Main) {
            val result = withContext(entryPoint().ioDispatcher()) {
                authRepository.updateUsername(sanitized)
            }
            result
                .onSuccess {
                    entryPoint().accountController().loadAccount(noCache = true)
                    progressBar.visibility = View.GONE
                    submitButton.isEnabled = true
                    submitButton.visibility = View.VISIBLE
                    onComplete?.invoke()
                }
                .onFailure { err ->
                    progressBar.visibility = View.GONE
                    submitButton.isEnabled = true
                    submitButton.visibility = View.VISIBLE
                    errorText.visibility = View.VISIBLE
                    errorText.text = err.message?.takeIf { it.isNotBlank() }
                        ?: getString(R.string.update_username_error)
                }
        }
    }
}
