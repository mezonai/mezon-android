package com.mezon.mobile.auth

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.InputType
import android.text.Spanned
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
import com.mezon.mobile.network.SocketRpcTransportException
import com.mezon.mobile.network.UnauthorizedException
import com.mezon.mobile.ui.cells.ActionButton
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UpdateUsernameFragment : BaseFragment() {

    companion object {
        private const val USERNAME_MIN_LEN = 2
        private const val USERNAME_MAX_LEN = 30
        private val USERNAME_REGEX = Regex("^[a-z0-9]{${USERNAME_MIN_LEN},${USERNAME_MAX_LEN}}$")

        private object AsciiLowercaseLettersAndDigitsFilter : InputFilter {
            override fun filter(
                source: CharSequence?,
                start: Int,
                end: Int,
                dest: Spanned?,
                dstart: Int,
                dend: Int
            ): CharSequence? {
                if (source == null) return null
                val out = StringBuilder(end - start)
                for (i in start until end) {
                    when (val c = source[i]) {
                        in 'a'..'z' -> out.append(c)
                        in 'A'..'Z' -> out.append(c.lowercaseChar())
                        in '0'..'9' -> out.append(c)
                    }
                }
                val sub = source.subSequence(start, end)
                return if (out.contentEquals(sub)) null else out
            }
        }
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
            filters = arrayOf(
                AsciiLowercaseLettersAndDigitsFilter,
                InputFilter.LengthFilter(USERNAME_MAX_LEN)
            )
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
                    val text = s?.toString().orEmpty()
                    if (text.isNotEmpty()) {
                        previewView.text = getString(R.string.update_username_preview, text)
                        previewView.visibility = View.VISIBLE
                    } else {
                        previewView.visibility = View.GONE
                    }
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

    private fun userMessageForUpdateUsernameFailure(err: Throwable): String {
        if (err is TimeoutCancellationException) {
            return getString(R.string.common_error_offline)
        }
        if (err is SocketRpcTransportException || err is IOException) {
            return getString(R.string.common_error_offline)
        }
        if (err is UnauthorizedException) {
            return getString(R.string.common_session_expired_content)
        }
        var chain: Throwable? = err
        while (chain != null) {
            if (chain is TimeoutCancellationException) {
                return getString(R.string.common_error_offline)
            }
            chain = chain.cause
        }
        val msg = err.message.orEmpty()
        val lower = msg.lowercase(Locale.ROOT)
        if ("timed out" in lower) {
            return getString(R.string.common_error_offline)
        }
        if ("websocket not connected" in lower || "websocket unavailable" in lower) {
            return getString(R.string.common_error_offline)
        }
        extractFramedServerUserMessage(msg)?.let { extracted ->
            if (extracted.isNotBlank()) return extracted
        }
        if (msg.startsWith("Server error: ")) {
            val rest = msg.removePrefix("Server error: ").trim()
            return rest.ifBlank { getString(R.string.update_username_error) }
        }
        if (looksLikeTechnicalRpcFailure(msg)) {
            return getString(R.string.update_username_error)
        }
        return msg.ifBlank { getString(R.string.update_username_error) }
    }

    private fun looksLikeTechnicalRpcFailure(msg: String): Boolean {
        if (msg.isBlank()) return true
        return msg.startsWith("Socket RPC ") ||
            msg.startsWith("RPC ") ||
            msg.startsWith("Server error code=")
    }

    /** Payload from framed RPC error: `Server error code=… msg='…'` */
    private fun extractFramedServerUserMessage(msg: String): String? {
        val prefix = "msg='"
        val i = msg.indexOf(prefix)
        if (i < 0) return null
        val start = i + prefix.length
        val end = msg.indexOf('\'', start)
        if (end <= start) return null
        return msg.substring(start, end)
    }

    private fun doSubmit() {
        val username = input.text?.toString().orEmpty()
        if (!USERNAME_REGEX.matches(username)) {
            errorText.text = getString(R.string.update_username_validation_invalid)
            errorText.visibility = View.VISIBLE
            return
        }

        AndroidUtilities.hideKeyboard(fragmentView)
        submitButton.isEnabled = false
        submitButton.visibility = View.INVISIBLE
        progressBar.visibility = View.VISIBLE
        errorText.visibility = View.GONE

        fragmentScope.launch(Dispatchers.Main) {
            val result = withContext(entryPoint().ioDispatcher()) {
                authRepository.updateUsername(username)
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
                    errorText.text = userMessageForUpdateUsernameFailure(err)
                }
        }
    }
}
