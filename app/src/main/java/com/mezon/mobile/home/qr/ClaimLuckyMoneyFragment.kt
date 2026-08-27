package com.mezon.mobile.home.qr

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.wallet.LuckyMoneyClaimAmount
import com.mezon.mobile.wallet.LuckyMoneyException
import com.mezon.mobile.wallet.WalletController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ClaimLuckyMoneyFragment : BaseFragment() {

    companion object {
        private const val ARG_LUCKY_MONEY_ID = "luckyMoneyId"

        fun newInstance(luckyMoneyId: String): ClaimLuckyMoneyFragment {
            return ClaimLuckyMoneyFragment().apply {
                arguments = android.os.Bundle().apply {
                    putString(ARG_LUCKY_MONEY_ID, luckyMoneyId)
                }
            }
        }
    }

    private sealed class ScreenState {
        object Loading : ScreenState()
        data class Error(val message: String) : ScreenState()
        data class Preview(val data: LuckyMoneyClaimAmount) : ScreenState()
        data class Claimed(val data: LuckyMoneyClaimAmount) : ScreenState()
    }

    private lateinit var walletController: WalletController
    private lateinit var luckyMoneyId: String
    private lateinit var stateContainer: LinearLayout
    private var previewData: LuckyMoneyClaimAmount? = null
    private var claimButton: TextView? = null
    private var isClaiming = false

    override fun onInject(entryPoint: FragmentEntryPoint) {
        walletController = entryPoint.walletController()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        luckyMoneyId = arguments?.getString(ARG_LUCKY_MONEY_ID).orEmpty().trim()
        return luckyMoneyId.isNotEmpty()
    }

    override fun createView(context: Context): View {
        stateContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(
                LayoutHelper.dp(20),
                LayoutHelper.dp(28),
                LayoutHelper.dp(20),
                LayoutHelper.dp(28)
            )
        }

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = ScrollView.OVER_SCROLL_NEVER
            addView(
                stateContainer,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        renderState(ScreenState.Loading)
        fragmentScope.launch(Dispatchers.Main.immediate) { loadPreview() }
        return wrapWithActionBar(getString(R.string.lucky_money_title), scroll)
    }

    private suspend fun loadPreview() {
        val result = walletController.fetchLuckyMoneyClaimAmount(luckyMoneyId)
        if (isFinished) return
        result.fold(
            onSuccess = { renderState(ScreenState.Preview(it)) },
            onFailure = { renderState(ScreenState.Error(errorMessage(it))) }
        )
    }

    private fun renderState(state: ScreenState) {
        stateContainer.removeAllViews()
        claimButton = null
        when (state) {
            ScreenState.Loading -> renderLoading()
            is ScreenState.Error -> renderError(state.message)
            is ScreenState.Preview -> renderReward(state.data, claimed = false)
            is ScreenState.Claimed -> renderReward(state.data, claimed = true)
        }
    }

    private fun renderLoading() {
        val context = stateContainer.context
        val card = createCard(context)
        card.gravity = Gravity.CENTER_HORIZONTAL
        card.addView(ProgressBar(context).apply { isIndeterminate = true })
        card.addView(
            titleText(context, getString(R.string.lucky_money_processing), 18f),
            topMarginParams(LayoutHelper.dp(18))
        )
        card.addView(
            bodyText(context, getString(R.string.lucky_money_please_wait)),
            topMarginParams(LayoutHelper.dp(8))
        )
        stateContainer.addView(card, matchWidthParams())
    }

    private fun renderError(message: String) {
        val context = stateContainer.context
        val card = createCard(context)
        card.gravity = Gravity.CENTER_HORIZONTAL
        card.addView(statusBadge(context, success = false))
        card.addView(
            titleText(context, getString(R.string.lucky_money_claim_failed), 20f),
            topMarginParams(LayoutHelper.dp(18))
        )
        card.addView(
            bodyText(context, message),
            topMarginParams(LayoutHelper.dp(10))
        )
        card.addView(
            actionButton(context, getString(R.string.common_close)) { finishFragment() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, LayoutHelper.dp(50)).apply {
                topMargin = LayoutHelper.dp(22)
            }
        )
        stateContainer.addView(card, matchWidthParams())
    }

    private fun renderReward(data: LuckyMoneyClaimAmount, claimed: Boolean) {
        previewData = data
        val context = stateContainer.context
        val card = createCard(context)
        card.gravity = Gravity.CENTER_HORIZONTAL
        card.addView(statusBadge(context, success = true, showGift = !claimed))
        card.addView(
            titleText(
                context,
                getString(
                    if (claimed) R.string.lucky_money_claim_success
                    else R.string.lucky_money_congratulations
                ),
                22f
            ),
            topMarginParams(LayoutHelper.dp(16))
        )
        card.addView(
            titleText(
                context,
                "+ ${NumberFormat.getIntegerInstance(Locale.US).format(data.amount)} " +
                    getString(R.string.send_token_currency_symbol),
                30f
            ),
            topMarginParams(LayoutHelper.dp(8))
        )

        val description = data.description?.trim().orEmpty()
        if (description.isNotEmpty()) {
            card.addView(
                detailBlock(
                    context,
                    getString(R.string.send_token_note),
                    description
                ),
                topMarginParams(LayoutHelper.dp(24))
            )
        }
        card.addView(
            detailBlock(
                context,
                getString(R.string.transfer_success_time_label),
                SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
            ),
            topMarginParams(if (description.isEmpty()) LayoutHelper.dp(24) else LayoutHelper.dp(18))
        )

        val button = actionButton(
            context,
            getString(
                if (claimed) R.string.lucky_money_done
                else R.string.lucky_money_claim_to_wallet
            )
        ) {
            if (claimed) finishFragment() else claimPreview()
        }
        claimButton = if (claimed) null else button
        card.addView(
            button,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, LayoutHelper.dp(50)).apply {
                topMargin = LayoutHelper.dp(24)
            }
        )
        stateContainer.addView(card, matchWidthParams())
    }

    private fun claimPreview() {
        val data = previewData ?: return
        if (isClaiming) return
        setClaimBusy(true)
        fragmentScope.launch(Dispatchers.Main.immediate) {
            val result = walletController.claimLuckyMoney(luckyMoneyId, data.splitMoneyId)
            if (isFinished) return@launch
            setClaimBusy(false)
            result.fold(
                onSuccess = { renderState(ScreenState.Claimed(data)) },
                onFailure = { renderState(ScreenState.Error(errorMessage(it))) }
            )
        }
    }

    private fun setClaimBusy(busy: Boolean) {
        isClaiming = busy
        claimButton?.apply {
            isEnabled = !busy
            alpha = if (busy) 0.55f else 1f
            text = getString(
                if (busy) R.string.lucky_money_claiming
                else R.string.lucky_money_claim_to_wallet
            )
        }
    }

    private fun errorMessage(error: Throwable): String {
        if (error is LuckyMoneyException) {
            return when (error.reason) {
                LuckyMoneyException.Reason.SERVICE_NOT_CONFIGURED ->
                    getString(R.string.lucky_money_service_not_configured)
                LuckyMoneyException.Reason.WALLET_NOT_READY ->
                    getString(R.string.lucky_money_wallet_not_ready)
                LuckyMoneyException.Reason.INVALID_PAYLOAD ->
                    getString(R.string.lucky_money_invalid_payload)
                LuckyMoneyException.Reason.HTTP ->
                    error.message?.takeIf { it.isNotBlank() }
                        ?: getString(R.string.lucky_money_claim_failed)
            }
        }
        return error.message?.takeIf { it.isNotBlank() }
            ?: getString(R.string.lucky_money_claim_failed)
    }

    private fun createCard(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = LayoutHelper.dp(16).toFloat()
            setColor(themeColors.surface)
        }
        setPadding(
            LayoutHelper.dp(20),
            LayoutHelper.dp(24),
            LayoutHelper.dp(20),
            LayoutHelper.dp(24)
        )
    }

    private fun statusBadge(
        context: Context,
        success: Boolean,
        showGift: Boolean = false
    ): View {
        val size = LayoutHelper.dp(82)
        val wrap = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (success) themeColors.onlineGreen else 0xFFEF4444.toInt())
            }
        }
        if (success) {
            wrap.addView(
                ImageView(context).apply {
                    setImageDrawable(
                        (if (showGift) MezonIcon.giftIcon else MezonIcon.checkmarkLargeIcon)
                            .getDrawable(context, Color.WHITE)
                    )
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(LayoutHelper.dp(20), LayoutHelper.dp(20), LayoutHelper.dp(20), LayoutHelper.dp(20))
                },
                FrameLayout.LayoutParams(size, size, Gravity.CENTER)
            )
        } else {
            wrap.addView(
                TextView(context).apply {
                    text = "!"
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)
                    setTypeface(null, Typeface.BOLD)
                },
                FrameLayout.LayoutParams(size, size, Gravity.CENTER)
            )
        }
        return wrap.apply {
            layoutParams = LinearLayout.LayoutParams(size, size).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }
    }

    private fun titleText(context: Context, value: String, sizeSp: Float): TextView =
        TextView(context).apply {
            text = value
            gravity = Gravity.CENTER
            setTextColor(themeColors.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTypeface(null, Typeface.BOLD)
        }

    private fun bodyText(context: Context, value: String): TextView = TextView(context).apply {
        text = value
        gravity = Gravity.CENTER
        setTextColor(themeColors.onSurfaceVariant)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
    }

    private fun detailBlock(context: Context, label: String, value: String): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = label
                setTextColor(themeColors.onSurfaceVariant)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            })
            addView(TextView(context).apply {
                text = value
                setTextColor(themeColors.onSurface)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(null, Typeface.BOLD)
            }, topMarginParams(LayoutHelper.dp(5)))
        }

    private fun actionButton(
        context: Context,
        label: String,
        onClick: () -> Unit
    ): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTypeface(null, Typeface.BOLD)
        background = GradientDrawable().apply {
            cornerRadius = LayoutHelper.dp(16).toFloat()
            setColor(themeColors.blurple)
        }
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun matchWidthParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun topMarginParams(margin: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = margin
        }
}
