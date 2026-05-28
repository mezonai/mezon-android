package com.mezon.mobile.home.clans.settings

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.AlertDialog
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.clans.CreateClanRnUiTokens
import com.mezon.mobile.home.clans.PermissionPolicy
import com.mezon.mobile.ui.MezonToast
import com.mezon.mobile.ui.cells.ActionBarView
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.cells.ToastOverlay
import com.mezon.mezon.api.OnboardingItem
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class OnboardingSettingsFragment : BaseFragment() {

    companion object {
        private const val ARG_CLAN_ID = "clanId"

        fun newInstance(clanId: Long): OnboardingSettingsFragment =
            OnboardingSettingsFragment().apply {
                arguments = Bundle().apply { putLong(ARG_CLAN_ID, clanId) }
            }
    }

    private var clanId = 0L
    private lateinit var controller: OnboardingSettingsController
    private lateinit var permissionPolicy: PermissionPolicy

    private lateinit var contentHost: FrameLayout
    private var saveBar: View? = null
    private var blockingOverlay: FrameLayout? = null

    override fun onInject(entryPoint: FragmentEntryPoint) {
        controller = entryPoint.onboardingSettingsController()
        permissionPolicy = entryPoint.permissionPolicy()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        clanId = arguments?.getLong(ARG_CLAN_ID) ?: 0L
        if (clanId == 0L) return false
        val perm = permissionPolicy.clanSettingsPermissionState(clanId)
        if (!perm.hasManageClanPermission) {
            MezonToast.show(this, ToastOverlay.ToastType.ERROR, getString(R.string.onboarding_permission_denied))
            return false
        }
        controller.open(clanId)
        return true
    }

    override fun onFragmentDestroy() {
        controller.reset()
        super.onFragmentDestroy()
    }

    override fun createView(context: Context): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.background)
        }

        actionBar = ActionBarView(context, themeColors).apply {
            setTitle(getString(R.string.menu_clan_onboarding))
            setBackButtonImage(R.drawable.ic_close_24)
            setBackButtonContentDescription(getString(R.string.common_close))
            setCenterTitle(true)
            setMenuOnItemClick(object : ActionBarView.ActionBarMenuOnItemClick() {
                override fun onItemClick(id: Int) {
                    if (id == -1) handleBack()
                }
            })
        }
        root.addView(actionBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        contentHost = FrameLayout(context)
        root.addView(contentHost, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        saveBar = buildSaveBar(context)
        root.addView(saveBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        saveBar!!.visibility = View.GONE

        blockingOverlay = FrameLayout(context).apply {
            setBackgroundColor(0x66000000)
            visibility = View.GONE
            isClickable = true
            addView(ProgressBar(context), LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER))
        }
        root.addView(blockingOverlay, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        fragmentScope.launch(entryPoint().mainDispatcher()) {
            controller.state.collectLatest { render(it) }
        }
        render(controller.state.value)

        fragmentView = root
        return root
    }

    private fun handleBack() {
        when (controller.state.value.currentPage) {
            OnboardingPage.MAIN -> {
                if (controller.state.value.isEnableSetupOpen) {
                    controller.setEnableSetupOpen(false)
                } else {
                    finishFragment()
                }
            }
            else -> controller.setPage(OnboardingPage.MAIN)
        }
    }

    private fun updateActionBarTitle(state: OnboardingSettingsUiState) {
        actionBar?.setTitle(
            when {
                state.currentPage == OnboardingPage.QUESTION ->
                    getString(R.string.onboarding_questions_block)
                state.currentPage == OnboardingPage.MISSION ->
                    getString(R.string.onboarding_clan_guide_block)
                else -> getString(R.string.menu_clan_onboarding)
            }
        )
    }

    private fun launchOnMain(block: suspend () -> Unit) {
        fragmentScope.launch(entryPoint().mainDispatcher()) { block() }
    }

    private fun render(state: OnboardingSettingsUiState) {
        if (!::contentHost.isInitialized) return
        blockingOverlay?.visibility = if (state.isLoading || state.isSaving) View.VISIBLE else View.GONE
        saveBar?.visibility = if (state.isOnboardingEnabled && state.isDirty) View.VISIBLE else View.GONE

        val ctx = contentHost.context
        contentHost.removeAllViews()
        val content = when {
            !state.isOnboardingEnabled && !state.isEnableSetupOpen -> buildLanding(ctx, state)
            state.currentPage == OnboardingPage.QUESTION -> buildQuestionsPage(ctx, state)
            state.currentPage == OnboardingPage.MISSION -> buildMissionPage(ctx, state)
            !state.isOnboardingEnabled && state.isEnableSetupOpen -> buildSetupPanel(ctx, state)
            else -> buildEnabledMain(ctx, state)
        }
        updateActionBarTitle(state)
        contentHost.addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))
    }

    private fun buildLanding(ctx: Context, state: OnboardingSettingsUiState): View {
        val scroll = ScrollView(ctx)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(20f), LayoutHelper.dp(16f), LayoutHelper.dp(20f), LayoutHelper.dp(24f))
        }
        col.addView(descText(ctx, getString(R.string.onboarding_landing_desc)))
        col.addView(primaryBtn(ctx, getString(R.string.onboarding_enable)) {
            controller.setEnableSetupOpen(true)
        }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            topMargin = LayoutHelper.dp(24f)
        })
        scroll.addView(col)
        return scroll
    }

    private fun buildSetupPanel(ctx: Context, state: OnboardingSettingsUiState): View {
        val scroll = ScrollView(ctx)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(16f), LayoutHelper.dp(12f), LayoutHelper.dp(16f), LayoutHelper.dp(24f))
        }
        col.addView(descText(ctx, getString(R.string.onboarding_setup_desc)))

        val highlightStroke = if (state.showHighlightNeedItem) themeColors.error else themeColors.borderDim
        col.addView(
            setupBlock(ctx, getString(R.string.onboarding_questions_block), getString(R.string.onboarding_questions_block_desc), highlightStroke) {
                controller.setPage(OnboardingPage.QUESTION)
            },
            blockLp(0)
        )
        col.addView(
            setupBlock(ctx, getString(R.string.onboarding_clan_guide_block), getString(R.string.onboarding_clan_guide_block_desc), highlightStroke) {
                controller.setPage(OnboardingPage.MISSION)
            },
            blockLp(LayoutHelper.dp(12f))
        )

        col.addView(primaryBtn(ctx, getString(R.string.onboarding_confirm_enable)) {
            launchOnMain {
                val result = controller.confirmEnableAndSave()
                if (result.isFailure) {
                    if (result.exceptionOrNull() is OnboardingSettingsController.NeedAtLeastOneItemException) {
                        MezonToast.show(this@OnboardingSettingsFragment, ToastOverlay.ToastType.ERROR, getString(R.string.onboarding_need_item))
                    } else {
                        MezonToast.show(this@OnboardingSettingsFragment, ToastOverlay.ToastType.ERROR, getString(R.string.onboarding_save_failed))
                    }
                }
            }
        }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            topMargin = LayoutHelper.dp(20f)
        })
        scroll.addView(col)
        return scroll
    }

    private fun buildEnabledMain(ctx: Context, state: OnboardingSettingsUiState): View {
        val scroll = ScrollView(ctx)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(16f), LayoutHelper.dp(12f), LayoutHelper.dp(16f), LayoutHelper.dp(24f))
        }

        val statusCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg()
            setPadding(LayoutHelper.dp(14f), LayoutHelper.dp(14f), LayoutHelper.dp(14f), LayoutHelper.dp(14f))
        }
        statusCard.addView(TextView(ctx).apply {
            text = getString(R.string.onboarding_enabled_title)
            setTextColor(themeColors.colorText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
        })
        statusCard.addView(TextView(ctx).apply {
            text = getString(R.string.onboarding_enabled_desc)
            setTextColor(themeColors.textDisabled)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, LayoutHelper.dp(6f), 0, LayoutHelper.dp(12f))
        })
        statusCard.addView(outlineBtn(ctx, getString(R.string.onboarding_disable)) {
            AlertDialog.Builder(ctx)
                .setMessage(getString(R.string.onboarding_disable_confirm))
                .setPositiveButton(getString(R.string.onboarding_disable)) { d, _ ->
                    d.dismiss()
                    launchOnMain { controller.disableOnboarding() }
                }
                .setNegativeButton(getString(R.string.common_cancel)) { d, _ -> d.dismiss() }
                .show()
        })
        col.addView(statusCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        col.addView(
            ClanSettingsUiHelpers.buildMezonChevronRow(
                ctx,
                themeColors,
                MezonIcon.sendMessageIcon,
                getString(R.string.onboarding_setup_questions),
                null,
                Runnable { controller.setPage(OnboardingPage.QUESTION) },
            ),
            blockLp(LayoutHelper.dp(16f)),
        )
        col.addView(
            ClanSettingsUiHelpers.buildMezonChevronRow(
                ctx,
                themeColors,
                MezonIcon.localCommunityIcon,
                getString(R.string.onboarding_setup_clan_guide),
                null,
                Runnable { controller.setPage(OnboardingPage.MISSION) },
            ),
            blockLp(LayoutHelper.dp(8f)),
        )

        scroll.addView(col)
        return scroll
    }

    private fun buildQuestionsPage(ctx: Context, state: OnboardingSettingsUiState): View {
        val scroll = ScrollView(ctx)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(16f), LayoutHelper.dp(8f), LayoutHelper.dp(16f), LayoutHelper.dp(24f))
        }
        col.addView(descText(ctx, getString(R.string.onboarding_questions_page_desc)))
        col.addView(outlineBtn(ctx, getString(R.string.onboarding_add_question)) {
            OnboardingQuestionEditorSheet(ctx) { title, answers ->
                if (state.isOnboardingEnabled) {
                    launchOnMain {
                        controller.addQuestionDraft(QuestionDraft(title = title, answers = answers))
                        controller.saveChanges()
                    }
                } else {
                    controller.addQuestionDraft(QuestionDraft(title = title, answers = answers))
                }
            }.show()
        }, blockLp(LayoutHelper.dp(12f)))

        state.onboardingByClan.questions.forEach { item ->
            col.addView(itemRow(ctx, item.title, onEdit = {
                openEditQuestion(ctx, item)
            }, onDelete = { deleteServerItem(item) }), itemLp())
        }
        state.draft.questions.forEach { draft ->
            col.addView(itemRow(ctx, draft.title + " *", onEdit = null, onDelete = {
                controller.removeDraftQuestion(draft.localId)
            }), itemLp())
        }
        scroll.addView(col)
        return scroll
    }

    private fun buildMissionPage(ctx: Context, state: OnboardingSettingsUiState): View {
        val scroll = ScrollView(ctx)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(16f), LayoutHelper.dp(8f), LayoutHelper.dp(16f), LayoutHelper.dp(24f))
        }
        state.onboardingByClan.greeting?.let { g ->
            col.addView(sectionTitle(ctx, getString(R.string.onboarding_greeting)))
            col.addView(itemRow(ctx, g.title.ifBlank { getString(R.string.onboarding_greeting) }, null, null), itemLp())
        }

        col.addView(sectionTitle(ctx, getString(R.string.onboarding_missions)))
        col.addView(outlineBtn(ctx, getString(R.string.onboarding_add_mission)) {
            openAddMission(ctx, state)
        }, blockLp(LayoutHelper.dp(8f)))
        state.onboardingByClan.missions.forEach { item ->
            col.addView(itemRow(ctx, item.title, { openEditMission(ctx, state, item) }, { deleteServerItem(item) }), itemLp())
        }
        state.draft.tasks.forEach { draft ->
            col.addView(itemRow(ctx, draft.title + " *", null, {
                controller.removeDraftMission(draft.localId)
            }), itemLp())
        }

        col.addView(sectionTitle(ctx, getString(R.string.onboarding_resources)))
        col.addView(outlineBtn(ctx, getString(R.string.onboarding_add_rule)) {
            openAddRule(ctx, state)
        }, blockLp(LayoutHelper.dp(8f)))
        state.onboardingByClan.rules.forEach { item ->
            col.addView(itemRow(ctx, item.title, { openEditRule(ctx, item) }, { deleteServerItem(item) }), itemLp())
        }
        state.draft.rules.forEach { draft ->
            col.addView(itemRow(ctx, draft.title + " *", null, {
                controller.removeDraftRule(draft.localId)
            }), itemLp())
        }
        scroll.addView(col)
        return scroll
    }

    private fun openEditQuestion(ctx: Context, item: OnboardingItem) {
        val answers = item.answersList.map { OnboardingAnswerDraft(title = it.title) }
        OnboardingQuestionEditorSheet(ctx, item) { title, newAnswers ->
            launchOnMain {
                controller.updateServerQuestion(item, title, newAnswers)
            }
        }.show()
    }

    private fun openAddMission(ctx: Context, state: OnboardingSettingsUiState) {
        val channels = controller.publicChannelsForClan(state.clanId)
        OnboardingMissionEditorSheet(ctx, channels) { title, content, channelId, taskType ->
            val draft = MissionDraft(title = title, content = content, channelId = channelId, taskType = taskType)
            if (state.isOnboardingEnabled) {
                launchOnMain {
                    controller.addMissionDraft(draft)
                    controller.saveChanges()
                }
            } else {
                controller.addMissionDraft(draft)
            }
        }.show()
    }

    private fun openEditMission(ctx: Context, state: OnboardingSettingsUiState, item: OnboardingItem) {
        val channels = controller.publicChannelsForClan(state.clanId)
        OnboardingMissionEditorSheet(ctx, channels, item) { title, content, channelId, taskType ->
            launchOnMain {
                controller.updateServerMission(item, title, content, channelId, taskType)
            }
        }.show()
    }

    private fun openAddRule(ctx: Context, state: OnboardingSettingsUiState) {
        OnboardingRuleEditorSheet(ctx) { title, content, imageUrl, localPath ->
            val draft = RuleDraft(title = title, content = content, imageUrl = imageUrl, localFilePath = localPath)
            if (state.isOnboardingEnabled) {
                launchOnMain {
                    controller.addRuleDraft(draft)
                    controller.saveChanges()
                }
            } else {
                controller.addRuleDraft(draft)
            }
        }.show()
    }

    private fun openEditRule(ctx: Context, item: OnboardingItem) {
        OnboardingRuleEditorSheet(ctx, item) { title, content, imageUrl, localPath ->
            launchOnMain {
                controller.updateServerRule(item, title, content, imageUrl, localPath)
            }
        }.show()
    }

    private fun deleteServerItem(item: OnboardingItem) {
        AlertDialog.Builder(contentHost.context)
            .setMessage(getString(R.string.onboarding_delete_confirm))
            .setPositiveButton(getString(R.string.common_delete)) { d, _ ->
                d.dismiss()
                launchOnMain { controller.deleteServerItem(item) }
            }
            .setNegativeButton(getString(R.string.common_cancel)) { d, _ -> d.dismiss() }
            .show()
    }

    private fun buildSaveBar(ctx: Context): View {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(LayoutHelper.dp(16f), LayoutHelper.dp(10f), LayoutHelper.dp(16f), LayoutHelper.dp(10f))
            setBackgroundColor(themeColors.surfaceVariant)
            addView(primaryBtn(ctx, getString(R.string.common_save_changes)) {
                launchOnMain {
                    val result = controller.saveChanges()
                    if (result.isFailure) {
                        MezonToast.show(this@OnboardingSettingsFragment, ToastOverlay.ToastType.ERROR, getString(R.string.onboarding_save_failed))
                    }
                }
            }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        }
    }

    private fun setupBlock(
        ctx: Context,
        title: String,
        desc: String,
        strokeColor: Int,
        onClick: () -> Unit,
    ): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = LayoutHelper.dpf(12f)
            setColor(themeColors.channelPanelBg)
            setStroke(LayoutHelper.dp(1), strokeColor)
        }
        setPadding(LayoutHelper.dp(14f), LayoutHelper.dp(14f), LayoutHelper.dp(14f), LayoutHelper.dp(14f))
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        addView(TextView(ctx).apply {
            isClickable = false
            text = title
            setTextColor(themeColors.colorText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
        })
        addView(TextView(ctx).apply {
            isClickable = false
            text = desc
            setTextColor(themeColors.textDisabled)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, LayoutHelper.dp(4f), 0, 0)
        })
    }

    private fun itemRow(
        ctx: Context,
        title: String,
        onEdit: (() -> Unit)?,
        onDelete: (() -> Unit)?,
    ): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = cardBg()
        setPadding(LayoutHelper.dp(12f), LayoutHelper.dp(10f), LayoutHelper.dp(12f), LayoutHelper.dp(10f))
        addView(TextView(ctx).apply {
            text = title
            setTextColor(themeColors.colorText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 2
        }, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))
        if (onEdit != null) {
            addView(actionIconBtn(ctx, MezonIcon.pencilIcon, themeColors.onSurfaceVariant, onEdit))
        }
        if (onDelete != null) {
            addView(actionIconBtn(ctx, MezonIcon.closeSmallBold, themeColors.error, onDelete))
        }
    }

    /** Nút icon gọn 18dp trong vùng chạm 36dp — tránh drawable ic_edit/ic_remove phóng to. */
    private fun actionIconBtn(
        ctx: Context,
        icon: MezonIcon,
        tint: Int,
        onClick: () -> Unit,
    ): FrameLayout {
        val touch = LayoutHelper.dp(36f)
        val iconPx = LayoutHelper.dp(18f)
        return FrameLayout(ctx).apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(touch, touch).apply {
                leftMargin = LayoutHelper.dp(4f)
            }
            addView(
                ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    adjustViewBounds = true
                    setImageDrawable(icon.getDrawable(ctx, tint))
                },
                FrameLayout.LayoutParams(iconPx, iconPx, Gravity.CENTER),
            )
        }
    }

    private fun descText(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        setTextColor(themeColors.textDisabled)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
    }

    private fun sectionTitle(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        setTextColor(themeColors.colorText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, LayoutHelper.dp(14f), 0, LayoutHelper.dp(6f))
    }

    private fun primaryBtn(ctx: Context, label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(themeColors.onPrimary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.DEFAULT_BOLD
        background = GradientDrawable().apply {
            cornerRadius = LayoutHelper.dpf(10f)
            setColor(themeColors.primary)
        }
        setPadding(LayoutHelper.dp(16f), LayoutHelper.dp(14f), LayoutHelper.dp(16f), LayoutHelper.dp(14f))
        setOnClickListener { onClick() }
    }

    private fun outlineBtn(ctx: Context, label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(themeColors.primary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.DEFAULT_BOLD
        background = GradientDrawable().apply {
            cornerRadius = LayoutHelper.dpf(10f)
            setStroke(LayoutHelper.dp(1), themeColors.primary)
        }
        setPadding(LayoutHelper.dp(16f), LayoutHelper.dp(12f), LayoutHelper.dp(16f), LayoutHelper.dp(12f))
        setOnClickListener { onClick() }
    }

    private fun cardBg() = GradientDrawable().apply {
        cornerRadius = CreateClanRnUiTokens.clanSettingsMenuCornerPx()
        setColor(themeColors.channelPanelBg)
    }

    private fun blockLp(top: Int = 0) = LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
        topMargin = top
    }

    private fun itemLp() = LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
        topMargin = LayoutHelper.dp(8f)
    }
}
