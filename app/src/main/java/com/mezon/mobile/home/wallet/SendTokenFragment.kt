package com.mezon.mobile.home.wallet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.AlertDialog
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.BottomSheet
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.DialogsController
import com.mezon.mobile.home.chat.MessageEntity
import com.mezon.mobile.home.friends.FriendController
import com.mezon.mezon.api.Friend
import com.mezon.mobile.home.profile.AccountController
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.network.STREAM_MODE_DM
import com.mezon.mobile.session.SessionManager
import com.mezon.mobile.ui.cells.ToastOverlay
import com.mezon.mobile.ui.cells.AvatarView
import com.mezon.mobile.wallet.WalletController
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.floor

class SendTokenFragment : BaseFragment() {

    companion object {
        private const val ARG_FORM_VALUE = "formValue"
        private const val MAX_NOTE_LENGTH = 512
        private val VI_LOCALE: Locale = Locale("vi", "VN")
        private val CHAIN_UNITS_PER_TOKEN: BigInteger = BigInteger.valueOf(1_000_000L)

        fun newInstance(
            formValue: String? = null
        ): SendTokenFragment {
            return SendTokenFragment().apply {
                if (formValue != null) {
                    arguments = android.os.Bundle().apply {
                        putString(ARG_FORM_VALUE, formValue)
                    }
                }
            }
        }
    }

    private lateinit var accountController: AccountController
    private lateinit var friendController: FriendController
    private lateinit var dialogsController: DialogsController
    private lateinit var mezonApi: MezonApi
    private lateinit var sessionManager: SessionManager
    private lateinit var walletController: WalletController
    private var formValue: String? = null

    private var jsonReceiverId: String? = null
    private var jsonReceiverName: String? = null
    private var jsonWalletAddress: String? = null
    private var jsonAmountDefault: String? = null
    private var jsonNoteDefault: String? = null
    private var formCanEdit: Boolean? = null
    private var isPaymentType: Boolean = false
    private var formHasPositiveAmount: Boolean = false
    private var formHasNonEmptyNote: Boolean = false

    private var amountField: EditText? = null
    private var noteField: EditText? = null
    private var sendButton: TextView? = null
    private var noteCounter: TextView? = null
    private var walletBalanceText: TextView? = null
    private var recipientValueText: TextView? = null
    private var amountFormatSuppress: Boolean = false

    /** Set by TransferSuccessFragment callbacks to control navigation when this fragment resumes. */
    private var shouldFinishOnResume = false
    private var pendingNewTransfer = false

    /** True when opened with QR / deep-link form payload (only [QrScanFragment] passes this today). */
    private val isQrTransferFlow: Boolean
        get() = !formValue.isNullOrBlank()

    override fun onInject(
        entryPoint: FragmentEntryPoint
    ) {
        accountController = entryPoint.accountController()
        friendController = entryPoint.friendController()
        dialogsController = entryPoint.dialogsController()
        mezonApi = entryPoint.mezonApi()
        sessionManager = entryPoint.sessionManager()
        walletController = entryPoint.walletController()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        formValue = arguments?.getString(ARG_FORM_VALUE)
        parseForm(formValue)
        friendController.loadFriends()
        observe(NotificationCenter.accountInfoLoaded) { _, _, _ ->
            refreshWalletBalance()
        }
        return true
    }

    override fun onResume() {
        super.onResume()
        if (shouldFinishOnResume) {
            shouldFinishOnResume = false
            val doNewTransfer = pendingNewTransfer.also { pendingNewTransfer = false }
            val view = fragmentView
            if (view != null) {
                view.post {
                    if (doNewTransfer) presentFragment(SendTokenFragment.newInstance())
                    finishFragment()
                }
            } else {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (doNewTransfer) presentFragment(SendTokenFragment.newInstance())
                    finishFragment()
                }
            }
        }
    }

    private fun parseForm(
        raw: String?
    ) {
        if (raw.isNullOrBlank()) {
            return
        }
        val j = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val rawName = if (j.isNull("receiver_name")) null else j.optString("receiver_name", "")
        val rawDisplayName = if (j.isNull("receiver_display_name")) null else j.optString("receiver_display_name", "")
        jsonReceiverName = rawName?.ifBlank { rawDisplayName?.ifBlank { null } } ?: rawDisplayName?.ifBlank { null }
        jsonWalletAddress = if (j.isNull("wallet_address")) null else j.optString("wallet_address", "")
        jsonReceiverId = readIdString(j, "receiver_id")
        isPaymentType = j.optString("type", "") == "payment"
        if (j.has("can_edit")) {
            formCanEdit = j.getBoolean("can_edit")
        } else {
            formCanEdit = null
        }
        if (j.has("amount") && !j.isNull("amount")) {
            val rawStr = jsonAmountToRawString(j.get("amount"))
            formHasPositiveAmount = isPositiveJsonAmount(j.get("amount"), rawStr)
            jsonAmountDefault = formatTokenAmountLikeRn(rawStr)
        }
        if (j.has("note") && !j.isNull("note")) {
            val ns = j.optString("note", "")
            if (ns.isNotBlank()) {
                formHasNonEmptyNote = true
                jsonNoteDefault = ns
            }
        }
    }

    private fun isPositiveJsonAmount(
        raw: Any,
        asString: String
    ): Boolean {
        if (raw is java.lang.Number) {
            return raw.doubleValue() != 0.0
        }
        val n = parseTokenAmountDigitsLikeRn(asString) ?: return false
        return n > BigInteger.ZERO
    }

    private fun isAmountFieldEditable(): Boolean {
        if (isPaymentType) return false
        if (!formHasPositiveAmount) return true
        return formCanEdit == true
    }

    private fun isNoteFieldEditable(): Boolean {
        if (isPaymentType) return false
        if (!formHasNonEmptyNote) return true
        return formCanEdit == true
    }

    private fun readIdString(
        j: JSONObject,
        key: String
    ): String? {
        if (!j.has(key) || j.isNull(key)) return null
        return when (val v = j.get(key)) {
            is String -> v.ifBlank { null }
            is Number -> v.toString()
            else -> v.toString()
        }
    }

    override fun createView(
        context: android.content.Context
    ): View {
        val acc = accountController.accountInfo.value
        val userDisplay = acc.username.ifEmpty { acc.displayName }.ifEmpty { "—" }
        val balance = acc.balance
        val symbol = getString(R.string.send_token_currency_symbol)

        val contentColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val qr = isQrTransferFlow
        val padH = if (qr) LayoutHelper.dp(16f) else LayoutHelper.dp(20f)
        val contentPadB = if (qr) LayoutHelper.dp(24f) else LayoutHelper.dp(20f)
        contentColumn.setPadding(padH, if (qr) LayoutHelper.dp(12f) else LayoutHelper.dp(20f), padH, contentPadB)

        contentColumn.addView(
            if (qr) headingViewQr(context, getString(R.string.send_token_heading))
            else headingView(context, getString(R.string.send_token_heading))
        )
        contentColumn.addView(
            if (qr) walletCardViewQr(context, userDisplay, balance, symbol)
            else walletCardView(context, userDisplay, balance, symbol)
        )
        addRecipientBlock(context, contentColumn, qr)

        contentColumn.addView(
            if (qr) qrFormSectionLabel(context, getString(R.string.send_token_amount_quantity))
            else sectionLabel(
                context,
                getString(R.string.send_token_amount)
            )
        )
        val amount = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (qr) 13f else 14f)
            setTextColor(themeColors.onSurface)
            setHintTextColor(themeColors.onSurfaceVariant)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                LayoutHelper.dp(if (qr) 14f else 10f),
                0,
                LayoutHelper.dp(if (qr) 14f else 10f),
                0
            )
            hint = "0"
            maxLines = 1
            isSingleLine = true
            if (!jsonAmountDefault.isNullOrBlank()) {
                setText(jsonAmountDefault)
            }
            isEnabled = isAmountFieldEditable()
            if (qr) applyQrTransferPlainEditText(this, singleLine = true)
        }
        amountField = amount
        bindAmountTextWatcher(amount)
        contentColumn.addView(
            wrapAmountField(context, amount, qr),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LayoutHelper.dp(40f))
        )

        contentColumn.addView(
            if (qr) qrFormSectionLabel(context, getString(R.string.send_token_note))
            else sectionLabel(
                context,
                getString(R.string.send_token_note)
            )
        )
        val defaultNote = if (jsonNoteDefault.isNullOrBlank()) {
            getString(R.string.send_token_title)
        } else {
            jsonNoteDefault!!
        }
        val note = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(MAX_NOTE_LENGTH))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (qr) 13f else 12f)
            setTextColor(themeColors.onSurface)
            setHintTextColor(themeColors.onSurfaceVariant)
            hint = getString(R.string.send_token_note_hint)
            setText(defaultNote)
            minLines = 2
            maxLines = 5
            gravity = Gravity.TOP
            setPadding(
                LayoutHelper.dp(if (qr) 14f else 10f),
                LayoutHelper.dp(if (qr) 10f else 10f),
                LayoutHelper.dp(if (qr) 14f else 10f),
                LayoutHelper.dp(if (qr) 10f else 10f)
            )
            if (!qr) {
                background = null
            }
            isEnabled = isNoteFieldEditable()
            if (qr) applyQrTransferPlainEditText(this, singleLine = false)
        }
        noteField = note
        val counter = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(if (qr) themeColors.onSurfaceVariant else themeColors.textDisabled)
            gravity = Gravity.END
        }
        noteCounter = counter
        updateNoteCounter(note.length())
        val noteBox = wrapNoteFieldWithCounter(context, note, counter, qr)
        contentColumn.addView(
            noteBox,
            LayoutHelper.createLinear(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        note.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(
                s: CharSequence?,
                start: Int,
                count: Int,
                after: Int
            ) { }

            override fun onTextChanged(
                s: CharSequence?,
                start: Int,
                before: Int,
                count: Int
            ) { }

            override fun afterTextChanged(
                s: Editable?
            ) {
                updateNoteCounter(s?.length ?: 0)
            }
        })
        val scroll = ScrollView(context).apply { isFillViewport = true }
        scroll.addView(
            contentColumn,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val sendBtn = TextView(context).apply {
            val bg = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(if (qr) 16f else 14f).toFloat()
                setColor(themeColors.blurple)
            }
            background = bg
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            setText(R.string.send_token_submit)
            isClickable = true
            setOnClickListener { onSendClick() }
            minHeight = LayoutHelper.dp(if (qr) 52f else 50f)
        }
        sendButton = sendBtn

        val sideInset = when {
            AndroidUtilities.displaySize.x > 0 ->
                (AndroidUtilities.displaySize.x * 0.05f).toInt()
            else -> LayoutHelper.dp(18f)
        }.coerceAtLeast(1)
        val sendBtnLp = if (qr) {
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                LayoutHelper.dp(52f)
            )
        } else {
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                LayoutHelper.dp(50f)
            ).apply {
                marginStart = sideInset
                marginEnd = sideInset
            }
        }

        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val inset = if (qr) LayoutHelper.dp(16f) else LayoutHelper.dp(20f)
            setPadding(
                inset,
                LayoutHelper.dp(10f),
                inset,
                if (qr) LayoutHelper.dp(20f) else LayoutHelper.dp(30f)
            )
            setBackgroundColor(themeColors.background)
        }
        footer.addView(
            sendBtn,
            sendBtnLp
        )

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        root.addView(
            footer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        if (isAmountFieldEditable() && (jsonReceiverId != null || !jsonWalletAddress.isNullOrBlank())) {
            root.post { amount.requestFocus() }
        }
        val actionBarTitle = if (qr) {
            getString(R.string.send_token_screen_wallet_title)
        } else {
            getString(R.string.send_token_title)
        }
        return wrapWithActionBar(
            actionBarTitle,
            root
        )
    }

    private fun headingView(
        context: android.content.Context,
        title: String
    ): TextView = TextView(context).apply {
        text = title
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setTypeface(null, Typeface.BOLD)
        setTextColor(themeColors.textStrong)
        setPadding(0, 0, 0, LayoutHelper.dp(15f))
    }

    private fun headingViewQr(
        context: android.content.Context,
        title: String
    ): TextView = TextView(context).apply {
        text = title
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        setTypeface(null, Typeface.BOLD)
        setTextColor(themeColors.onSurface)
        setPadding(0, 0, 0, LayoutHelper.dp(18f))
    }

    private fun qrFormSectionLabel(
        context: android.content.Context,
        text: String
    ): TextView = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(themeColors.onSurfaceVariant)
        setPadding(0, LayoutHelper.dp(14f), 0, LayoutHelper.dp(6f))
    }

    private fun createQrTransferInputBackground(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = LayoutHelper.dp(12f).toFloat()
        setColor(themeColors.surface)
        setStroke(LayoutHelper.dp(1f), themeColors.outlineVariant)
    }

    /**
     * Themed [EditText] draws an accent underline when focused; the QR transfer layout uses only
     * the outer rounded stroke. Also tightens typography to match the reference (small body text).
     */
    private fun applyQrTransferPlainEditText(
        editText: EditText,
        singleLine: Boolean
    ) {
        editText.background = ColorDrawable(Color.TRANSPARENT)
        ViewCompat.setBackgroundTintList(editText, null)
        editText.includeFontPadding = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            editText.defaultFocusHighlightEnabled = false
        }
        if (singleLine) {
            editText.maxLines = 1
            editText.setSingleLine(true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val d = editText.resources.displayMetrics.density
            val w = (2f * d).toInt().coerceAtLeast(2)
            val h = (20f * d).toInt().coerceAtLeast(16)
            editText.textCursorDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(themeColors.onSurface)
                setSize(w, h)
            }
        }
    }

    private fun walletCardView(
        context: android.content.Context,
        userDisplay: String,
        balance: String,
        symbol: String
    ): View {
        val gr = GradientDrawable(
            GradientDrawable.Orientation.RIGHT_LEFT,
            intArrayOf(themeColors.secondaryLight, themeColors.colorAvatarDefault)
        )
        gr.cornerRadius = LayoutHelper.dp(10f).toFloat()
        gr.setStroke(1, themeColors.textDisabled)
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = gr
            setPadding(
                LayoutHelper.dp(16f),
                LayoutHelper.dp(14f),
                LayoutHelper.dp(16f),
                LayoutHelper.dp(14f)
            )
        }
        addWalletRowBothTitles(
            context,
            box,
            getString(R.string.send_token_debit_account),
            userDisplay
        )
        val balHuman = runCatching {
            formatBigIntegerHumanVi(
                balance.toBigInteger() / CHAIN_UNITS_PER_TOKEN
            )
        }.getOrDefault("—")
        addWalletRowBalance(
            context,
            box,
            getString(R.string.send_token_balance_label),
            getString(R.string.send_token_balance_line, balHuman, symbol)
        )
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = LayoutHelper.dp(20f)
        box.layoutParams = lp
        return box
    }

    private fun walletCardViewQr(
        context: android.content.Context,
        userDisplay: String,
        balance: String,
        symbol: String
    ): View {
        val gr = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(themeColors.surfaceVariant, themeColors.surface)
        )
        gr.cornerRadius = LayoutHelper.dp(16f).toFloat()
        gr.setStroke(LayoutHelper.dp(1f), themeColors.outlineVariant)
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = gr
            setPadding(
                LayoutHelper.dp(16f),
                LayoutHelper.dp(14f),
                LayoutHelper.dp(16f),
                LayoutHelper.dp(14f)
            )
        }
        addWalletRowQrAccountLine(
            context,
            box,
            getString(R.string.send_token_debit_account),
            userDisplay
        )
        val balHuman = runCatching {
            formatBigIntegerHumanVi(
                balance.toBigInteger() / CHAIN_UNITS_PER_TOKEN
            )
        }.getOrDefault("—")
        addWalletRowQrBalanceLine(
            context,
            box,
            getString(R.string.send_token_balance_label),
            getString(R.string.send_token_balance_line, balHuman, symbol)
        )
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = LayoutHelper.dp(20f)
        box.layoutParams = lp
        return box
    }

    private fun addWalletRowQrAccountLine(
        context: android.content.Context,
        parent: LinearLayout,
        label: String,
        value: String
    ) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val a = TextView(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(themeColors.onSurfaceVariant)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val b = TextView(context).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(themeColors.onSurface)
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(a)
        row.addView(b)
        parent.addView(row)
    }

    private fun addWalletRowQrBalanceLine(
        context: android.content.Context,
        parent: LinearLayout,
        label: String,
        amountText: String
    ) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        val labelTv = TextView(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(themeColors.onSurfaceVariant)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val amountTv = TextView(context).apply {
            text = amountText
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(themeColors.onSurface)
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        walletBalanceText = amountTv
        row.addView(labelTv)
        row.addView(amountTv)
        parent.addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = LayoutHelper.dp(16f) }
        )
    }

    private val walletCardTitleTypeface: Typeface
        get() = Typeface.create(Typeface.DEFAULT, 600, false)

    private fun addWalletRowBothTitles(
        context: android.content.Context,
        parent: LinearLayout,
        left: String,
        right: String
    ) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        val a = TextView(context).apply {
            text = left
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = walletCardTitleTypeface
            setTextColor(themeColors.onSurface)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val b = TextView(context).apply {
            text = right
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = walletCardTitleTypeface
            setTextColor(themeColors.onSurface)
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(a)
        row.addView(b)
        parent.addView(row)
    }

    private fun addWalletRowBalance(
        context: android.content.Context,
        parent: LinearLayout,
        label: String,
        amountText: String
    ) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        val labelTv = TextView(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = walletCardTitleTypeface
            setTextColor(themeColors.onSurface)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val amountTv = TextView(context).apply {
            text = amountText
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(themeColors.onSurface)
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        walletBalanceText = amountTv
        row.addView(labelTv)
        row.addView(amountTv)
        parent.addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = LayoutHelper.dp(14f) }
        )
    }

    private fun refreshWalletBalance() {
        val raw = accountController.accountInfo.value.balance.toBigInteger()
        val human = formatBigIntegerHumanVi(raw / CHAIN_UNITS_PER_TOKEN)
        val symbol = getString(R.string.send_token_currency_symbol)
        walletBalanceText?.text = getString(R.string.send_token_balance_line, human, symbol)
    }

    private fun addRecipientBlock(
        context: android.content.Context,
        column: LinearLayout,
        qrFlow: Boolean
    ) {
        val hasRecipient = !jsonWalletAddress.isNullOrBlank() || !jsonReceiverId.isNullOrBlank()
        val sectionTitle = when {
            !jsonWalletAddress.isNullOrBlank() -> getString(R.string.send_token_recipient_to_address)
            else -> getString(R.string.send_token_recipient_to)
        }
        fun sectionForRecipient(): TextView =
            if (qrFlow) qrFormSectionLabel(context, sectionTitle).apply {
                setPadding(0, 0, 0, LayoutHelper.dp(6f))
            }
            else sectionLabel(context, sectionTitle)

        if (!hasRecipient && !formValue.isNullOrBlank()) {
            column.addView(sectionForRecipient())
            column.addView(
                labelAndText(
                    context,
                    getString(R.string.send_token_recipient),
                    getString(R.string.send_token_qr_invalid)
                )
            )
            return
        }
        if (!jsonWalletAddress.isNullOrBlank()) {
            column.addView(sectionForRecipient())
            val rowBg = if (qrFlow) createQrTransferInputBackground() else createTextFieldBackground()
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rowBg
                setPadding(
                    if (qrFlow) LayoutHelper.dp(10f) else LayoutHelper.dp(4f),
                    0,
                    LayoutHelper.dp(10f),
                    0
                )
            }
            val valueTv = TextView(context).apply {
                text = jsonWalletAddress
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (qrFlow) 13f else 14f)
                setTextColor(themeColors.onSurface)
                gravity = Gravity.CENTER_VERTICAL
                includeFontPadding = false
                setPadding(
                    LayoutHelper.dp(if (qrFlow) 4f else 6f),
                    0,
                    LayoutHelper.dp(6f),
                    0
                )
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val copyBtn = ImageView(context).apply {
                setImageResource(R.drawable.ic_copy_icon)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                contentDescription = getString(R.string.send_token_copy)
                colorFilter = PorterDuffColorFilter(
                    themeColors.onSurfaceVariant,
                    PorterDuff.Mode.SRC_IN
                )
                isClickable = true
                isFocusable = true
                val padH = LayoutHelper.dp(if (qrFlow) 6f else 8f)
                setPadding(padH, 0, if (qrFlow) LayoutHelper.dp(4f) else padH, 0)
                setOnClickListener {
                    copyToClipboard(
                        requireContext(),
                        jsonWalletAddress!!
                    )
                    showToast(
                        getString(R.string.send_token_address_copied),
                        ToastOverlay.ToastType.INFO
                    )
                }
            }
            row.addView(
                valueTv,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            )
            row.addView(
                copyBtn,
                LinearLayout.LayoutParams(
                    LayoutHelper.dp(36f),
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            column.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    LayoutHelper.dp(40f)
                )
            )
            return
        }
        if (!jsonReceiverId.isNullOrBlank()) {
            column.addView(sectionForRecipient())
            val display = when {
                !jsonReceiverName.isNullOrBlank() -> jsonReceiverName!!
                else -> jsonReceiverId!!
            }
            if (qrFlow) {
                column.addView(
                    qrRecipientReadout(context, display),
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        LayoutHelper.dp(40f)
                    )
                )
            } else {
                column.addView(
                    labelAndText(
                        context,
                        getString(R.string.send_token_recipient),
                        display
                    )
                )
            }
            return
        }
        column.addView(
            if (qrFlow) qrFormSectionLabel(context, getString(R.string.send_token_recipient_to)).apply {
                setPadding(0, 0, 0, LayoutHelper.dp(6f))
            }
            else sectionLabel(context, getString(R.string.send_token_recipient_to))
        )
        column.addView(
            selectableRecipientRow(context, qrFlow),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                LayoutHelper.dp(40f)
            )
        )
    }

    private fun selectableRecipientRow(
        context: Context,
        qrFlow: Boolean
    ): View {
        val rowBg = if (qrFlow) createQrTransferInputBackground() else createTextFieldBackground()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rowBg
            isClickable = true
            isFocusable = true
            setPadding(
                if (qrFlow) LayoutHelper.dp(12f) else LayoutHelper.dp(10f),
                0,
                if (qrFlow) LayoutHelper.dp(12f) else LayoutHelper.dp(10f),
                0
            )
            setOnClickListener { openRecipientPicker() }
        }
        val valueTv = TextView(context).apply {
            text = getString(R.string.send_token_select_user_placeholder)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (qrFlow) 13f else 14f)
            setTextColor(themeColors.onSurfaceVariant)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
        }
        recipientValueText = valueTv
        row.addView(
            valueTv,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(
            TextView(context).apply {
                text = "\u25BE"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(themeColors.onSurfaceVariant)
                includeFontPadding = false
                gravity = Gravity.CENTER_VERTICAL
            }
        )
        return row
    }

    private fun openRecipientPicker() {
        val act = getParentActivity() ?: return
        val allOptions = buildRecipientOptions()
        if (allOptions.isEmpty()) {
            friendController.loadFriends(noCache = true)
            showToast(
                getString(R.string.send_token_no_friends_to_select),
                ToastOverlay.ToastType.INFO
            )
            return
        }
        val sheet = BottomSheet(act, needFocusable = true)
        val content = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(16f), LayoutHelper.dp(10f), LayoutHelper.dp(16f), LayoutHelper.dp(16f))
            setBackgroundColor(themeColors.background)
        }
        val titleTv = TextView(act).apply {
            text = getString(R.string.send_token_select_user_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(themeColors.onSurface)
            setPadding(LayoutHelper.dp(4f), LayoutHelper.dp(6f), LayoutHelper.dp(4f), LayoutHelper.dp(10f))
        }
        val searchField = EditText(act).apply {
            hint = getString(R.string.send_token_search_user_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(themeColors.onSurface)
            setHintTextColor(themeColors.onSurfaceVariant)
            maxLines = 1
            isSingleLine = true
            setPadding(LayoutHelper.dp(12f), 0, LayoutHelper.dp(12f), 0)
            background = createQrTransferInputBackground()
        }
        val emptyTv = TextView(act).apply {
            text = getString(R.string.send_token_no_user_match)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(themeColors.onSurfaceVariant)
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, LayoutHelper.dp(16f), 0, LayoutHelper.dp(8f))
        }
        val recycler = RecyclerView(act).apply {
            layoutManager = LinearLayoutManager(act)
            overScrollMode = View.OVER_SCROLL_NEVER
            isNestedScrollingEnabled = true
        }
        val adapter = RecipientPickerAdapter(themeColors) { selected ->
            applyRecipientSelection(selected.friend)
            sheet.dismiss()
        }
        recycler.adapter = adapter
        adapter.submit(allOptions)
        recycler.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            LayoutHelper.dp(320f)
        )
        fun applyFilter(q: String) {
            val query = q.trim().lowercase(Locale.getDefault())
            val filtered = if (query.isBlank()) {
                allOptions
            } else {
                allOptions.filter {
                    it.displayName.lowercase(Locale.getDefault()).contains(query) ||
                        it.username.lowercase(Locale.getDefault()).contains(query)
                }
            }
            adapter.submit(filtered)
            emptyTv.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        }
        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyFilter(s?.toString().orEmpty())
            }
        })
        content.addView(titleTv)
        content.addView(
            searchField,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                LayoutHelper.dp(40f)
            )
        )
        content.addView(emptyTv)
        content.addView(
            recycler,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                LayoutHelper.dp(320f)
            ).apply {
                topMargin = LayoutHelper.dp(8f)
            }
        )
        sheet.setCustomView(content)
        sheet.delegate = object : BottomSheet.BottomSheetDelegateInterface {
            override fun onOpenAnimationEnd() {
                searchField.post {
                    if (!sheet.isShowing) return@post
                    searchField.requestFocus()
                    AndroidUtilities.showKeyboard(searchField)
                }
            }
        }
        sheet.show()
    }

    private fun buildRecipientOptions(): List<RecipientOption> {
        val currentUserId = accountController.accountInfo.value.userId
        return friendController.friends.value
            .asSequence()
            .filter { it.user.id != currentUserId }
            .map { friend ->
                val display = friend.user.displayName.ifBlank { friend.user.username }.ifBlank { friend.user.id.toString() }
                RecipientOption(
                    friend = friend,
                    displayName = display,
                    username = friend.user.username,
                    avatarUrl = friend.user.avatarUrl
                )
            }
            .sortedBy { it.displayName.lowercase(Locale.getDefault()) }
            .toList()
    }

    private fun applyRecipientSelection(friend: Friend) {
        jsonReceiverId = friend.user.id.toString()
        jsonReceiverName = friend.user.displayName.ifBlank { friend.user.username }.ifBlank { jsonReceiverId }
        recipientValueText?.text = jsonReceiverName
        recipientValueText?.setTextColor(themeColors.onSurface)
        if (isAmountFieldEditable()) {
            amountField?.requestFocus()
        }
    }

    private data class RecipientOption(
        val friend: Friend,
        val displayName: String,
        val username: String,
        val avatarUrl: String
    )

    private class RecipientPickerAdapter(
        private val themeColors: com.mezon.mobile.core.ThemeColors,
        private val onSelect: (RecipientOption) -> Unit
    ) : RecyclerView.Adapter<RecipientPickerAdapter.Holder>() {
        private val items = ArrayList<RecipientOption>()

        fun submit(newItems: List<RecipientOption>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val ctx = parent.context
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(LayoutHelper.dp(8f), LayoutHelper.dp(8f), LayoutHelper.dp(8f), LayoutHelper.dp(8f))
                setBackgroundColor(themeColors.background)
            }
            val avatar = AvatarView(ctx).apply {
                setSizeDp(36)
                setRoundRadius(18f)
            }
            val textWrap = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(LayoutHelper.dp(10f), 0, LayoutHelper.dp(10f), 0)
            }
            val nameTv = TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(themeColors.onSurface)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val userTv = TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(themeColors.onSurfaceVariant)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            row.addView(
                avatar,
                LinearLayout.LayoutParams(LayoutHelper.dp(36f), LayoutHelper.dp(36f))
            )
            textWrap.addView(nameTv)
            textWrap.addView(userTv)
            row.addView(
                textWrap,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            row.setOnClickListener {
                val pos = (it.tag as? Int) ?: return@setOnClickListener
                val chosen = items.getOrNull(pos) ?: return@setOnClickListener
                onSelect(chosen)
            }
            return Holder(row, avatar, nameTv, userTv)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.itemView.tag = position
            holder.avatar.setInfo(item.friend.user.id, item.username)
            holder.avatar.setImageUrl(item.avatarUrl.ifBlank { null })
            holder.nameTv.text = item.displayName
            holder.userTv.text = "@${item.username}"
        }

        override fun getItemCount(): Int = items.size

        class Holder(
            itemView: View,
            val avatar: AvatarView,
            val nameTv: TextView,
            val userTv: TextView
        ) : RecyclerView.ViewHolder(itemView)
    }

    private fun qrRecipientReadout(
        context: android.content.Context,
        display: String
    ): TextView = TextView(context).apply {
        text = display
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(themeColors.onSurface)
        gravity = Gravity.CENTER_VERTICAL
        includeFontPadding = false
        background = createQrTransferInputBackground()
        setPadding(LayoutHelper.dp(14f), 0, LayoutHelper.dp(14f), 0)
    }

    private fun copyToClipboard(
        context: Context,
        text: String
    ) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("address", text))
    }

    private fun sectionLabel(
        context: android.content.Context,
        text: String
    ): TextView = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(themeColors.onSurface)
        setPadding(0, LayoutHelper.dp(10f), 0, LayoutHelper.dp(10f))
    }

    private fun bindAmountTextWatcher(
        amount: EditText
    ) {
        amount.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(
                s: CharSequence?,
                start: Int,
                count: Int,
                after: Int
            ) { }

            override fun onTextChanged(
                s: CharSequence?,
                start: Int,
                before: Int,
                count: Int
            ) { }

            override fun afterTextChanged(
                s: Editable?
            ) {
                if (amountFormatSuppress) return
                val raw = s?.toString().orEmpty()
                val formatted = formatTokenAmountLikeRn(
                    if (raw.filter { it in '0'..'9' }.isEmpty() && raw.isNotEmpty()) {
                        "0"
                    } else {
                        raw
                    }
                )
                if (raw == formatted) return
                amountFormatSuppress = true
                amount.setText(formatted)
                runCatching { amount.setSelection(formatted.length) }
                amountFormatSuppress = false
            }
        })
    }

    private fun updateNoteCounter(
        len: Int
    ) {
        noteCounter?.text = getString(R.string.send_token_note_char_count, len)
    }

    private fun buildRecipientDisplay(): String {
        return when {
            !jsonWalletAddress.isNullOrBlank() -> jsonWalletAddress!!
            !jsonReceiverName.isNullOrBlank() -> jsonReceiverName!!
            !jsonReceiverId.isNullOrBlank() -> jsonReceiverId!!
            else -> "—"
        }
    }

    private fun labelAndText(
        context: android.content.Context,
        label: String,
        value: String
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(
            TextView(context).apply {
                this.text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(themeColors.onSurfaceVariant)
            }
        )
        addView(
            TextView(context).apply {
                this.text = value
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(themeColors.onSurface)
                setPadding(0, LayoutHelper.dp(4f), 0, 0)
            }
        )
    }

    private fun createTextFieldBackground(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = LayoutHelper.dp(6f).toFloat()
        setColor(themeColors.secondaryLight)
        setStroke(1, themeColors.textDisabled)
    }

    private fun wrapAmountField(
        context: android.content.Context,
        field: EditText,
        qrFlow: Boolean = false
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = if (qrFlow) createQrTransferInputBackground() else createTextFieldBackground()
        setPadding(
            if (qrFlow) LayoutHelper.dp(2f) else LayoutHelper.dp(4f),
            0,
            if (qrFlow) LayoutHelper.dp(2f) else LayoutHelper.dp(4f),
            0
        )
        addView(
            field,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        )
    }

    private fun wrapNoteFieldWithCounter(
        context: android.content.Context,
        field: EditText,
        counter: TextView,
        qrFlow: Boolean = false
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = if (qrFlow) createQrTransferInputBackground() else createTextFieldBackground()
        val padSide = if (qrFlow) LayoutHelper.dp(2f) else LayoutHelper.dp(4f)
        setPadding(
            padSide,
            if (qrFlow) LayoutHelper.dp(2f) else 0,
            padSide,
            if (qrFlow) LayoutHelper.dp(8f) else LayoutHelper.dp(4f)
        )
        addView(
            field,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (qrFlow) LayoutHelper.dp(108f) else LayoutHelper.dp(100f)
            )
        )
        val cLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            rightMargin = LayoutHelper.dp(if (qrFlow) 10f else 2f)
            bottomMargin = LayoutHelper.dp(if (qrFlow) 8f else 2f)
        }
        addView(
            counter,
            cLp
        )
    }

    private fun doubleAmountToRawString(
        d: Double
    ): String {
        return when {
            d.isNaN() || d.isInfinite() -> "0"
            else -> {
                val fl = floor(d)
                if (d == fl && d >= Long.MIN_VALUE.toDouble() && d <= Long.MAX_VALUE.toDouble()) {
                    d.toLong().toString()
                } else {
                    d.toString()
                }
            }
        }
    }

    private fun jsonAmountToRawString(
        v: Any
    ): String {
        return when (v) {
            is Double -> doubleAmountToRawString(v)
            is Float -> doubleAmountToRawString(v.toDouble())
            is java.lang.Number -> v.toString()
            else -> v.toString()
        }
    }

    private fun formatBigIntegerHumanVi(
        n: BigInteger
    ): String {
        if (n == BigInteger.ZERO) return "0"
        val maxLong = BigInteger.valueOf(Long.MAX_VALUE)
        if (n.signum() >= 0 && n <= maxLong) {
            val lv = n.toString().toLongOrNull()
            if (lv != null && BigInteger.valueOf(lv) == n) {
                return String.format(VI_LOCALE, "%,d", lv)
            }
        }
        val s = n.toString()
        return s.reversed().chunked(3).joinToString(".").reversed()
    }

    private fun formatTokenAmountLikeRn(
        amount: String
    ): String {
        val sanitized = amount.filter { it in '0'..'9' }
        if (sanitized.isEmpty()) return "0"
        val numericString = sanitized.dropWhile { it == '0' }.ifEmpty { "0" }
        val numericValue = runCatching { BigInteger(numericString) }.getOrNull()
            ?: return "0"
        if (numericValue == BigInteger.ZERO) return "0"
        return formatBigIntegerHumanVi(numericValue)
    }

    private fun parseTokenAmountDigitsLikeRn(
        raw: String
    ): BigInteger? {
        val sanitized = raw.filter { it in '0'..'9' }
        if (sanitized.isEmpty()) return null
        val noLeading = sanitized.dropWhile { it == '0' }
        val numStr = if (noLeading.isEmpty()) "0" else noLeading
        return runCatching { BigInteger(numStr) }.getOrNull()
    }

    private fun onSendClick() {
        if (jsonReceiverId.isNullOrBlank() && jsonWalletAddress.isNullOrBlank()) {
            showToast(
                getString(R.string.send_token_error_no_recipient),
                ToastOverlay.ToastType.ERROR
            )
            return
        }
        val n = parseTokenAmountDigitsLikeRn(amountField?.text?.toString().orEmpty())
        if (n == null || n <= BigInteger.ZERO) {
            showToast(
                getString(R.string.send_token_error_amount),
                ToastOverlay.ToastType.ERROR
            )
            return
        }
        val balanceRaw = runCatching { accountController.accountInfo.value.balance.toBigInteger() }
            .getOrNull()
        if (balanceRaw == null) {
            showToast(
                getString(R.string.send_token_error_balance_parse),
                ToastOverlay.ToastType.ERROR
            )
            return
        }
        val amountInChainUnits = n.multiply(CHAIN_UNITS_PER_TOKEN)
        if (amountInChainUnits > balanceRaw) {
            showToast(
                getString(R.string.send_token_error_exceeds_balance),
                ToastOverlay.ToastType.ERROR
            )
            return
        }
        if (!walletController.isReadyToSendTransaction()) {
            showZkReloginDialog()
            return
        }
        val acc = accountController.accountInfo.value
        val senderId = acc.userId.toString()
        if (senderId == "0") {
            showToast(
                getString(R.string.send_token_wallet_not_available),
                ToastOverlay.ToastType.ERROR
            )
            return
        }
        val userName = acc.username.ifEmpty { acc.displayName }
        val byAddr = jsonWalletAddress?.trim()?.takeIf { it.isNotEmpty() }
        val toUserId = jsonReceiverId?.trim()?.takeIf { it.isNotEmpty() }
        val note = noteField?.text?.toString()?.trim()
            ?.replace(Regex("\\s+"), " ")
            .orEmpty()
        val act = getParentActivity() ?: return
        setSendEnabled(false)
        fragmentScope.launch(Dispatchers.Main) {
            try {
                val result = walletController.sendTokenTransfer(
                    senderId = senderId,
                    receiverId = toUserId,
                    receiverMmnAddress = byAddr,
                    amountHuman = n.toString(),
                    note = note,
                    senderUsername = userName
                )
                val parent = getLayoutContainer() ?: (fragmentView as? ViewGroup) ?: return@launch
                result.fold(
                    onSuccess = { r ->
                        if (r.ok) {
                            accountController.reduceBalanceLocally(amountInChainUnits)
                            val timeStr = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                                .format(Date())
                            val amountShow = amountField?.text?.toString().orEmpty()
                            val sym = getString(R.string.send_token_currency_symbol)
                            val recipientDisplay = buildRecipientDisplay()
                            val successFrag = TransferSuccessFragment.newInstance(
                                amount = amountShow,
                                symbol = sym,
                                recipient = recipientDisplay,
                                note = note,
                                time = timeStr
                            )
                  
                            successFrag.onDone = { shouldFinishOnResume = true }
                            successFrag.onNewTransfer = { shouldFinishOnResume = true; pendingNewTransfer = true }
                            presentFragment(successFrag)
                            if (!toUserId.isNullOrBlank()) {
                                fragmentScope.launch(Dispatchers.IO) {
                                    sendTransferDmNotification(
                                        receiverId = toUserId,
                                        amountDisplay = amountShow,
                                        symbol = sym,
                                        note = note
                                    )
                                }
                            }
              
                            return@launch
                        } else {
                            ToastOverlay(requireContext(), themeColors).show(
                                parent,
                                ToastOverlay.ToastType.ERROR,
                                r.error.ifEmpty { getString(R.string.send_token_send_error, "") }
                            )
                        }
                    },
                    onFailure = { e ->
                        ToastOverlay(requireContext(), themeColors).show(
                            parent,
                            ToastOverlay.ToastType.ERROR,
                            getString(R.string.send_token_send_error, e.message.orEmpty())
                        )
                    }
                )
            } finally {
                setSendEnabled(true)
            }
        }
    }

    private suspend fun sendTransferDmNotification(
        receiverId: String,
        amountDisplay: String,
        symbol: String,
        note: String
    ) {
        val receiverLong = receiverId.toLongOrNull() ?: return
        val session = sessionManager.sessionFlow.first() ?: return
        val dmChannelId = withContext(Dispatchers.IO) {
            dialogsController.getOrCreateDm(receiverLong)
        }
        if (dmChannelId == 0L) return
        val safeNote = note.ifBlank { "-" }
        val text = "Funds Transferred: $amountDisplay$symbol | $safeNote"
        val content = JSONObject().apply {
            put("t", text)
            put("mk", JSONArray())
        }.toString()
        val request = com.mezon.mezon.rtapi.channelMessageSend {
            this.clanId = 0L
            this.channelId = dmChannelId
            this.mode = STREAM_MODE_DM
            this.isPublic = false
            this.content = content
            this.code = MessageEntity.CODE_SEND_TOKEN
        }
        runCatching {
            withContext(Dispatchers.IO) {
                mezonApi.sendChannelMessage(session.apiUrl, session.token, request)
            }
        }
    }

    private fun setSendEnabled(
        enabled: Boolean
    ) {
        sendButton?.isEnabled = enabled
        sendButton?.alpha = if (enabled) 1f else 0.5f
    }

    private fun showZkReloginDialog() {
        val act = getParentActivity() ?: return
        AlertDialog.Builder(act)
            .setMessage(getString(R.string.send_token_zk_relogin))
            .setPositiveButton(getString(R.string.common_ok), null)
            .show()
    }

    private fun showToast(
        message: String,
        type: ToastOverlay.ToastType
    ) {
        val parent = getLayoutContainer() ?: (fragmentView as? ViewGroup) ?: return
        ToastOverlay(requireContext(), themeColors).show(parent, type, message)
    }
}
