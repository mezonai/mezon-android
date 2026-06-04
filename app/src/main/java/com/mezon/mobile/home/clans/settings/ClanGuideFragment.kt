package com.mezon.mobile.home.clans.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.mezon.mobile.R
import com.mezon.mobile.MainActivity
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.clans.ChannelController
import com.mezon.mobile.home.clans.ClansController
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.ui.cells.ActionBarView
import com.mezon.mobile.ui.cells.AvatarView
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mezon.api.OnboardingItem
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ClanGuideFragment : BaseFragment() {

    companion object {
        private const val ARG_CLAN_ID = "clanId"

        fun newInstance(clanId: Long): ClanGuideFragment =
            ClanGuideFragment().apply {
                arguments = Bundle().apply { putLong(ARG_CLAN_ID, clanId) }
            }
    }

    private var clanId = 0L
    private lateinit var controller: OnboardingUserController
    private lateinit var clansController: ClansController
    private lateinit var channelController: ChannelController

    private lateinit var contentHost: FrameLayout
    private var progressOverlay: ProgressBar? = null

    override fun onInject(entryPoint: FragmentEntryPoint) {
        controller = entryPoint.onboardingUserController()
        clansController = entryPoint.clansController()
        channelController = entryPoint.channelController()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        clanId = arguments?.getLong(ARG_CLAN_ID) ?: 0L
        return clanId != 0L
    }

    override fun createView(context: Context): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.background)
        }

        actionBar = ActionBarView(context, themeColors).apply {
            setBackClickListener { finishFragment() }
            setTitle(getString(R.string.guide_navigation_clan_guide))
            setCenterTitle(true)
        }
        root.addView(actionBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56))

        contentHost = FrameLayout(context).apply {
            setBackgroundColor(themeColors.background)
        }
        root.addView(contentHost, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        progressOverlay = ProgressBar(context).apply {
            visibility = View.VISIBLE
        }
        contentHost.addView(progressOverlay, LayoutHelper.createFrame(48, 48, Gravity.CENTER))

        fragmentScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            controller.loadOnboarding(clanId, forceRefresh = true)
            controller.uiState.collectLatest { state ->
                if (state.clanId == clanId) {
                    renderState(context, state)
                }
            }
        }

        return root
    }

    private fun renderState(ctx: Context, state: OnboardingUserState) {
        contentHost.removeAllViews()

        if (state.isLoading) {
            val progress = ProgressBar(ctx)
            contentHost.addView(progress, LayoutHelper.createFrame(48, 48, Gravity.CENTER))
            return
        }

        if (state.error != null) {
            val errorText = TextView(ctx).apply {
                text = state.error
                setTextColor(themeColors.error)
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            }
            contentHost.addView(errorText, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER))
            return
        }

        val scroll = ScrollView(ctx)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(16f), LayoutHelper.dp(12f), LayoutHelper.dp(16f), LayoutHelper.dp(24f))
        }

        val clan = clansController.clans.value.firstOrNull { it.clanId == clanId }

        col.addView(ownerGreetingCard(ctx, clan?.clanName.orEmpty(), clan?.logo.orEmpty(), state), itemLp())

        col.addView(sectionLabel(ctx, getString(R.string.onboarding_questions_block)), sectionLp())
        col.addView(questionsPanel(ctx, state), itemLp())

        col.addView(sectionLabel(ctx, getString(R.string.onboarding_resources)), sectionLp())
        if (state.rules.isEmpty()) {
            col.addView(emptyText(ctx, getString(R.string.guide_no_rules)), itemLp())
        } else {
            state.rules.forEach { rule ->
                col.addView(ruleCard(ctx, rule), itemLp())
            }
        }

        col.addView(sectionLabel(ctx, getString(R.string.onboarding_missions)), sectionLp())
        if (state.missions.isEmpty()) {
            col.addView(emptyText(ctx, getString(R.string.guide_no_missions)), itemLp())
        } else {
            state.missions.forEachIndexed { idx, mission ->
                col.addView(missionRow(ctx, mission, idx, state), itemLp())
            }
        }

        scroll.addView(col)
        contentHost.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))
    }

    private fun ownerGreetingCard(ctx: Context, clanName: String, logoUrl: String, state: OnboardingUserState): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dpf(14f)
                setStroke(LayoutHelper.dp(2), themeColors.primary)
                setColor(themeColors.channelPanelBg)
            }
            setPadding(LayoutHelper.dp(14f), LayoutHelper.dp(14f), LayoutHelper.dp(14f), LayoutHelper.dp(14f))
        }

        val avatarRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val avatarView = AvatarView(ctx).apply {
            setSizeDp(36)
            setRoundRadius(10f)
            setInfo(state.clanId, clanName)
            if (logoUrl.isNotBlank()) setImageUrl(logoUrl)
        }
        avatarRow.addView(avatarView, LayoutHelper.createLinear(36, 36))
        avatarRow.addView(TextView(ctx).apply {
            text = clanName.ifBlank { "Clan" }
            setTextColor(themeColors.colorText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(LayoutHelper.dp(10f), 0, 0, 0)
        }, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))
        card.addView(avatarRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        card.addView(TextView(ctx).apply {
            val greetingItem = state.rules.firstOrNull { it.guideType == 1 } ?: state.questions.firstOrNull { it.guideType == 1 }
            text = greetingItem?.content?.ifBlank { null }
                ?: getString(R.string.onboarding_owner_greeting_text)
            setTextColor(themeColors.textDisabled)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, LayoutHelper.dp(10f), 0, 0)
        }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        return card
    }

    private fun questionsPanel(ctx: Context, state: OnboardingUserState): View {
        if (state.questions.isEmpty()) {
            return emptyText(ctx, getString(R.string.guide_no_questions))
        }

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        var totalAnswers = 0
        var totalAnswered = 0
        state.questions.forEach { q ->
            totalAnswers += q.answersList.size
            totalAnswered += state.keepAnswers[q.id.toString()]?.size ?: 0
        }
        val percent = if (totalAnswers > 0) (totalAnswered * 100f) / totalAnswers else 0f

        val progressLine = VerticalProgressLineView(ctx).apply {
            this.percent = percent
        }
        container.addView(progressLine, LayoutHelper.createLinear(8, LayoutHelper.MATCH_PARENT).apply {
            rightMargin = LayoutHelper.dp(12f)
        })

        val listLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        state.questions.forEach { q ->
            val qView = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, LayoutHelper.dp(16f))
            }
            qView.addView(TextView(ctx).apply {
                text = q.title
                setTextColor(themeColors.colorText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
            }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

            val scrollAnswers = HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
            }
            val chipGroup = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, LayoutHelper.dp(8f), 0, 0)
            }
            q.answersList.forEachIndexed { answerIndex, answer ->
                val selected = state.keepAnswers[q.id.toString()]?.contains(answerIndex) == true
                val chip = TextView(ctx).apply {
                    text = answer.title
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(LayoutHelper.dp(12f), LayoutHelper.dp(6f), LayoutHelper.dp(12f), LayoutHelper.dp(6f))
                    background = GradientDrawable().apply {
                        cornerRadius = LayoutHelper.dpf(16f)
                        if (selected) {
                            setColor(themeColors.primary)
                        } else {
                            setColor(themeColors.tertiary)
                            setStroke(LayoutHelper.dp(1), themeColors.borderDim)
                        }
                    }
                    setTextColor(if (selected) Color.WHITE else themeColors.colorText)
                    typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        controller.selectAnswer(q.id.toString(), answerIndex)
                    }
                }
                chipGroup.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT).apply {
                    rightMargin = LayoutHelper.dp(8f)
                })
            }
            scrollAnswers.addView(chipGroup)
            qView.addView(scrollAnswers, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            listLayout.addView(qView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        }

        container.addView(listLayout, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))
        return container
    }

    private fun ruleCard(ctx: Context, item: OnboardingItem): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = cardBg()
            setPadding(LayoutHelper.dp(12f), LayoutHelper.dp(12f), LayoutHelper.dp(12f), LayoutHelper.dp(12f))
        }

        if (item.imageUrl.isNotBlank()) {
            val thumb = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply {
                    cornerRadius = LayoutHelper.dpf(8f)
                    setColor(themeColors.border)
                }
                MezonImageLoader.getInstance(ctx).load(
                    item.imageUrl,
                    LayoutHelper.dp(48f),
                    LayoutHelper.dp(48f),
                    onSuccess = { bmp -> setImageBitmap(bmp) }
                )
            }
            card.addView(thumb, LayoutHelper.createLinear(48, 48).apply {
                rightMargin = LayoutHelper.dp(12f)
            })
        }

        val textLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        textLayout.addView(TextView(ctx).apply {
            text = item.title
            setTextColor(themeColors.colorText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
        }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        if (item.content.isNotBlank()) {
            textLayout.addView(TextView(ctx).apply {
                text = item.content
                setTextColor(themeColors.textDisabled)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, LayoutHelper.dp(4f), 0, 0)
            }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        }

        card.addView(textLayout, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))
        return card
    }

    private fun missionRow(ctx: Context, item: OnboardingItem, index: Int, state: OnboardingUserState): View {
        val isCompleted = index < state.missionDoneIndex || state.isCompleted
        val isActive = index == state.missionDoneIndex && !state.isCompleted
        val isLocked = index > state.missionDoneIndex && !state.isCompleted

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = cardBg().apply {
                if (isLocked) {
                    alpha = 120
                }
            }
            setPadding(LayoutHelper.dp(12f), LayoutHelper.dp(12f), LayoutHelper.dp(12f), LayoutHelper.dp(12f))
        }

        val titleText = TextView(ctx).apply {
            text = item.title
            setTextColor(if (isLocked) themeColors.textDisabled else themeColors.colorText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = if (isActive) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        row.addView(titleText, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))

        if (isCompleted) {
            val checkmark = TextView(ctx).apply {
                text = "✓"
                setTextColor(themeColors.onlineGreen)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Typeface.DEFAULT_BOLD
            }
            row.addView(checkmark, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))
        } else if (isLocked) {
            val lock = TextView(ctx).apply {
                text = "🔒"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            }
            row.addView(lock, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))
        } else {
            val actionBtn = TextView(ctx).apply {
                text = when (item.taskType) {
                    1 -> getString(R.string.onboarding_mission_type_send_message)
                    2 -> getString(R.string.onboarding_mission_type_visit)
                    else -> getString(R.string.onboarding_step_done)
                }
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(LayoutHelper.dp(10f), LayoutHelper.dp(5f), LayoutHelper.dp(10f), LayoutHelper.dp(5f))
                background = GradientDrawable().apply {
                    cornerRadius = LayoutHelper.dpf(12f)
                    setColor(themeColors.primary)
                }
            }
            row.addView(actionBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))
        }

        if (isActive) {
            row.isClickable = true
            row.isFocusable = true
            row.setOnClickListener {
                when (item.taskType) {
                    1 -> { 
                        redirectUserToChannel(item.channelId, item.title)
                    }
                    2 -> { 
                        controller.completeMission(clanId, index)
                        redirectUserToChannel(item.channelId, item.title)
                    }
                    else -> { 
                        controller.completeMission(clanId, index)
                    }
                }
            }
        } else if (isLocked) {
            row.setOnClickListener {
                Toast.makeText(ctx, getString(R.string.onboarding_mission_locked_toast), Toast.LENGTH_SHORT).show()
            }
        }

        return row
    }

    private fun redirectUserToChannel(channelId: Long, channelName: String) {
        if (channelId == 0L) return
        val clanChannel = channelController.findChannelById(channelId)
        val channelType = clanChannel?.type ?: 1
        val finalChannelName = clanChannel?.channelLabel ?: channelName
        (getParentActivity() as? MainActivity)?.openChat(channelId, finalChannelName, clanId, channelType)
    }

    private fun sectionLabel(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        setTextColor(themeColors.colorText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun emptyText(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        setTextColor(themeColors.textDisabled)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
    }

    private fun cardBg(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = LayoutHelper.dpf(10f)
        setColor(themeColors.channelPanelBg)
        setStroke(LayoutHelper.dp(1), themeColors.borderDim)
    }

    private fun itemLp(): LinearLayout.LayoutParams =
        LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            bottomMargin = LayoutHelper.dp(12f)
        }

    private fun sectionLp(): LinearLayout.LayoutParams =
        LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            topMargin = LayoutHelper.dp(16f)
            bottomMargin = LayoutHelper.dp(8f)
        }

    class VerticalProgressLineView(context: Context) : View(context) {
        var percent: Float = 0f
            set(value) {
                field = value
                invalidate()
            }
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = LayoutHelper.dpf(4f)
            strokeCap = Paint.Cap.ROUND
        }
        private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = LayoutHelper.dpf(4f)
            strokeCap = Paint.Cap.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            val x = width / 2f
            val startY = LayoutHelper.dpf(4f)
            val endY = height.toFloat() - LayoutHelper.dpf(4f)
            if (endY <= startY) return

            bgPaint.color = ThemeColors.instance.borderDim
            canvas.drawLine(x, startY, x, endY, bgPaint)

            if (percent > 0f) {
                progressPaint.color = ThemeColors.instance.onlineGreen
                val progressY = startY + (endY - startY) * (percent / 100f)
                canvas.drawLine(x, startY, x, progressY, progressPaint)
            }
        }
    }
}
