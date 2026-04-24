package com.mezon.mobile.home.chat

import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BottomSheet
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.session.SessionManager
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReportMessageBottomSheet(
    context: android.content.Context,
    private val messageId: Long,
    private val mezonApi: MezonApi,
    private val sessionManager: SessionManager,
    private val applicationScope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val mainDispatcher: CoroutineDispatcher
) : BottomSheet(context) {

    private val theme: ThemeColors = ThemeColors.instance

    private val rnRedStrong = 0xFFC61E1B.toInt()

    private val primaryColor: Int
        get() = when (theme.resolvedMode) {
            ThemeMode.LIGHT -> 0xFFF4F4F8.toInt()
            ThemeMode.DARK -> 0xFF121218.toInt()
            ThemeMode.ABYSS -> 0xFF110B33.toInt()
            else -> 0xFF121218.toInt()
        }

    private val secondaryColor: Int
        get() = when (theme.resolvedMode) {
            ThemeMode.LIGHT -> 0xFFFFFFFF.toInt()
            ThemeMode.DARK -> 0xFF1C1D23.toInt()
            ThemeMode.ABYSS -> 0xFF19153C.toInt()
            else -> 0xFF1C1D23.toInt()
        }

    private val textStrongColor: Int
        get() = when (theme.resolvedMode) {
            ThemeMode.LIGHT -> 0xFF070709.toInt()
            ThemeMode.DARK -> 0xFFDFE0E4.toInt()
            ThemeMode.ABYSS -> 0xFFDFE0E4.toInt()
            else -> 0xFFDFE0E4.toInt()
        }

    private val titleOnPrimary: Int
        get() = if (theme.resolvedMode == ThemeMode.LIGHT) textStrongColor else 0xFFFFFFFF.toInt()

    private enum class AbuseType(val wire: String, val labelRes: Int) {
        SPAM("SPAM", R.string.report_message_spam),
        ABUSE_OR_HARASSMENT("ABUSE_OR_HARASSMENT", R.string.report_message_harassment),
        HARMFUL_MISINFORMATION_OR_GLORIFYING_VIOLENCE(
            "HARMFUL_MISINFORMATION_OR_GLORIFYING_VIOLENCE",
            R.string.report_message_violent_content
        ),
        EXPOSING_PRIVATE_IDENTIFYING_INFORMATION(
            "EXPOSING_PRIVATE_IDENTIFYING_INFORMATION",
            R.string.report_message_private
        )
    }

    private var selected: AbuseType? = null
    private var isSubmitting = false

    private var step1Layout: LinearLayout? = null
    private var step2Layout: LinearLayout? = null
    private var submitButton: TextView? = null
    private var categoryTitleText: TextView? = null

    init {
        containerHeight = (AndroidUtilities.displaySize.y * 0.88f).toInt()
        setCustomView(buildContent())
    }

    private fun buildContent(): View {
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(primaryColor)
        }

        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(18), LayoutHelper.dp(18), LayoutHelper.dp(18), LayoutHelper.dp(30))
            setBackgroundColor(primaryColor)
        }

        val step1 = buildStep1()
        val step2 = buildStep2()
        step1Layout = step1
        step2Layout = step2
        step2.visibility = View.GONE

        val stage = FrameLayout(context)
        stage.addView(
            step1,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        stage.addView(
            step2,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )

        outer.addView(
            stage,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        scroll.addView(
            outer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        return scroll
    }

    private fun buildStep1(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL

            addView(centerTitle(context.getString(R.string.report_message_title)))
            addView(
                subtitleView(context.getString(R.string.report_message_subtitle)),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = LayoutHelper.dp(4) }
            )
            addView(
                labelSelectedMessage(),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = LayoutHelper.dp(16) }
            )

            val list = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            val types = listOf(
                AbuseType.SPAM,
                AbuseType.ABUSE_OR_HARASSMENT,
                AbuseType.HARMFUL_MISINFORMATION_OR_GLORIFYING_VIOLENCE,
                AbuseType.EXPOSING_PRIVATE_IDENTIFYING_INFORMATION
            )
            for (index in types.indices) {
                list.addView(buildOptionRow(types[index]))
                if (index < types.size - 1) {
                    list.addView(
                        View(context).apply { setBackgroundColor(0) },
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LayoutHelper.dp(8)
                        )
                    )
                }
            }
            addView(
                list,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = LayoutHelper.dp(10) }
            )

            val cancel = fullWidthButton(
                text = context.getString(R.string.report_message_cancel),
                backgroundColor = theme.blurple,
                textColor = 0xFFFFFFFF.toInt()
            ) {
                if (!isSubmitting) dismiss()
            }
            addView(
                cancel,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = LayoutHelper.dp(18) }
            )
        }
    }

    private fun buildStep2(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL

            val backRow = FrameLayout(context)
            val backBtn = FrameLayout(context).apply {
                setPadding(0, LayoutHelper.dp(4), LayoutHelper.dp(10), LayoutHelper.dp(4))
                setOnClickListener {
                    if (isSubmitting) return@setOnClickListener
                    selected = null
                    crossfadeToStep1()
                }
            }
            backBtn.addView(
                ImageView(context).apply {
                    setImageDrawable(MezonIcon.backArrowLarge.getDrawable(context).apply {
                        colorFilter = PorterDuffColorFilter(textStrongColor, PorterDuff.Mode.SRC_IN)
                    })
                    scaleType = ImageView.ScaleType.FIT_CENTER
                },
                FrameLayout.LayoutParams(
                    LayoutHelper.dp(20),
                    LayoutHelper.dp(20),
                    Gravity.CENTER_VERTICAL or Gravity.START
                )
            )
            backRow.addView(
                backBtn,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(backRow)

            val summaryHeader = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            summaryHeader.addView(centerTitle(context.getString(R.string.report_message_report_summary)))
            summaryHeader.addView(
                subtitleView(context.getString(R.string.report_message_review_before)),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = LayoutHelper.dp(4) }
            )
            addView(
                summaryHeader,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = LayoutHelper.dp(8) }
            )

            addView(
                labelMedium(context.getString(R.string.report_message_category_label)),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = LayoutHelper.dp(20) }
            )
            addView(
                buildCategoryPill(),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = LayoutHelper.dp(10) }
            )
            addView(
                subtitleView(context.getString(R.string.report_message_submit_description)),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = LayoutHelper.dp(20) }
            )

            val submit = fullWidthButton(
                text = context.getString(R.string.report_message_submit),
                backgroundColor = rnRedStrong,
                textColor = 0xFFFFFFFF.toInt()
            ) {
                handleSubmit()
            }
            submitButton = submit
            addView(
                wrapSubmitWithMargin(submit),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun wrapSubmitWithMargin(submit: TextView): View {
        return FrameLayout(context).apply {
            setPadding(LayoutHelper.dp(10), LayoutHelper.dp(10), LayoutHelper.dp(10), 0)
            addView(
                submit,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun buildCategoryPill(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val dot = View(context).apply {
                val bg = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(theme.getColor(ThemeColors.key_avatar_backgroundViolet))
                }
                background = bg
            }
            addView(
                dot,
                LinearLayout.LayoutParams(LayoutHelper.dp(6), LayoutHelper.dp(6)).apply {
                    rightMargin = LayoutHelper.dp(8)
                }
            )
            val label = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
                setTextColor(titleOnPrimary)
            }
            categoryTitleText = label
            addView(
                label,
                LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f)
            )
        }
    }

    private fun updateCategoryTitle() {
        val t = selected ?: return
        categoryTitleText?.text = context.getString(t.labelRes)
    }

    private fun centerTitle(text: String) = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18f)
        setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(titleOnPrimary)
        gravity = Gravity.CENTER_HORIZONTAL
    }

    private fun subtitleView(text: String) = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
        setTextColor(textStrongColor)
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(LayoutHelper.dp(14), 0, LayoutHelper.dp(14), 0)
    }

    private fun labelSelectedMessage() = TextView(context).apply {
        setText(R.string.report_message_selected_message)
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
        setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(textStrongColor)
    }

    private fun labelMedium(text: String) = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
        setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(textStrongColor)
    }

    private fun buildOptionRow(type: AbuseType): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(secondaryColor)
                cornerRadius = LayoutHelper.dp(8).toFloat()
            }
            setPadding(LayoutHelper.dp(18), LayoutHelper.dp(18), LayoutHelper.dp(18), LayoutHelper.dp(18))
            setOnClickListener {
                if (isSubmitting) return@setOnClickListener
                selected = type
                updateCategoryTitle()
                crossfadeToStep2()
            }
        }
        val rowText = if (theme.resolvedMode == ThemeMode.LIGHT) textStrongColor else 0xFFFFFFFF.toInt()
        val chevTint = if (theme.resolvedMode == ThemeMode.LIGHT) 0xFF29292B.toInt() else 0xFFCCCCCC.toInt()
        val title = TextView(context).apply {
            text = context.getString(type.labelRes)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            setTextColor(rowText)
        }
        val chev = ImageView(context).apply {
            setImageDrawable(
                MezonIcon.chevronSmallRightIcon.getDrawable(context).apply {
                    colorFilter = PorterDuffColorFilter(chevTint, PorterDuff.Mode.SRC_IN)
                }
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        row.addView(
            title,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(
            chev,
            LinearLayout.LayoutParams(LayoutHelper.dp(15), LayoutHelper.dp(15))
        )
        return row
    }

    private fun fullWidthButton(
        text: String,
        backgroundColor: Int,
        textColor: Int,
        onClick: () -> Unit
    ): TextView {
        return TextView(context).apply {
            this.text = text
            this.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
            this.setTextColor(textColor)
            gravity = Gravity.CENTER
            setPadding(0, LayoutHelper.dp(18), 0, LayoutHelper.dp(18))
            background = GradientDrawable().apply {
                setColor(backgroundColor)
                cornerRadius = LayoutHelper.dp(10).toFloat()
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun handleSubmit() {
        val t = selected ?: return
        if (isSubmitting) return
        isSubmitting = true
        submitButton?.alpha = 0.5f
        applicationScope.launch(ioDispatcher) {
            try {
                val session = sessionManager.requireValidSession()
                mezonApi.reportMessageAbuse(session.apiUrl, session.token, messageId, t.wire)
                withContext(mainDispatcher) {
                    isSubmitting = false
                    submitButton?.alpha = 1f
                    dismiss()
                    Toast.makeText(context, R.string.report_message_submitted, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(mainDispatcher) {
                    isSubmitting = false
                    submitButton?.alpha = 1f
                    Toast.makeText(
                        context,
                        context.getString(R.string.report_message_toast_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun crossfadeToStep2() {
        val a = step1Layout ?: return
        val b = step2Layout ?: return
        b.alpha = 0f
        b.translationX = AndroidUtilities.displaySize.x * 0.15f
        b.visibility = View.VISIBLE
        a.animate().alpha(0f).setDuration(150).withEndAction {
            a.visibility = View.GONE
            a.alpha = 1f
        }
        b.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(300)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun crossfadeToStep1() {
        val a = step1Layout ?: return
        val b = step2Layout ?: return
        a.alpha = 0f
        a.visibility = View.VISIBLE
        b.animate()
            .alpha(0f)
            .translationX(AndroidUtilities.displaySize.x * 0.1f)
            .setDuration(200)
            .withEndAction {
                b.visibility = View.GONE
                b.alpha = 1f
                b.translationX = 0f
            }
        a.animate().alpha(1f).setDuration(250).start()
    }
}
