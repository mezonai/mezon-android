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
import com.mezon.mobile.home.clans.ClansController
import com.mezon.mobile.home.clans.CreateClanRnUiTokens
import com.mezon.mobile.home.clans.PermissionPolicy
import com.mezon.mobile.home.profile.UserController
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.ui.MezonToast
import com.mezon.mobile.ui.cells.ActionBarView
import com.mezon.mobile.ui.cells.AvatarView
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
    private lateinit var clansController: ClansController
    private lateinit var userController: UserController

    private lateinit var contentHost: FrameLayout
    private var saveBar: View? = null
    private var blockingOverlay: FrameLayout? = null

    override fun onInject(entryPoint: FragmentEntryPoint) {
        controller = entryPoint.onboardingSettingsController()
        permissionPolicy = entryPoint.permissionPolicy()
        clansController = entryPoint.clansController()
        userController = entryPoint.userController()
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

        // A10: Existing (server-saved) questions with inline answer chips
        state.onboardingByClan.questions.forEach { item ->
            col.addView(questionCard(ctx, item, onEdit = { openEditQuestion(ctx, item) }, onDelete = { deleteServerItem(item) }), itemLp())
        }
        // Draft questions (not yet saved) — shown with "*" badge, only delete
        state.draft.questions.forEach { draft ->
            col.addView(draftItemRow(ctx, draft.title), itemLp())
        }
        scroll.addView(col)
        return scroll
    }

    /** A10: Question card with title, edit/delete buttons, and inline answer chips below. */
    private fun questionCard(
        ctx: Context,
        item: OnboardingItem,
        onEdit: () -> Unit,
        onDelete: () -> Unit,
    ): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg()
            setPadding(LayoutHelper.dp(12f), LayoutHelper.dp(10f), LayoutHelper.dp(12f), LayoutHelper.dp(10f))
        }
        // Header row: title + action icons
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(TextView(ctx).apply {
            text = item.title
            setTextColor(themeColors.colorText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
        }, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))
        headerRow.addView(actionIconBtn(ctx, MezonIcon.pencilIcon, themeColors.onSurfaceVariant, onEdit))
        headerRow.addView(actionIconBtn(ctx, MezonIcon.closeSmallBold, themeColors.error, onDelete))
        card.addView(headerRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        // A10.7: Inline answer chips row — wrap flow
        val answers = item.answersList
        if (answers.isNotEmpty()) {
            val chipRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, LayoutHelper.dp(6f), 0, 0)
            }
            // Simple horizontal list (no flow layout needed for ≤50 answers preview)
            answers.take(5).forEach { ans ->
                val chip = TextView(ctx).apply {
                    text = ans.title
                    setTextColor(themeColors.colorText)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    background = GradientDrawable().apply {
                        cornerRadius = LayoutHelper.dpf(20f)
                        setColor(themeColors.border)
                    }
                    setPadding(LayoutHelper.dp(8f), LayoutHelper.dp(4f), LayoutHelper.dp(8f), LayoutHelper.dp(4f))
                    maxLines = 1
                }
                chipRow.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT).apply {
                    rightMargin = LayoutHelper.dp(6f)
                })
            }
            if (answers.size > 5) {
                val more = TextView(ctx).apply {
                    text = "+${answers.size - 5}"
                    setTextColor(themeColors.textDisabled)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                }
                chipRow.addView(more, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))
            }
            card.addView(chipRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        }
        return card
    }

    private fun buildMissionPage(ctx: Context, state: OnboardingSettingsUiState): View {
        val scroll = ScrollView(ctx)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(16f), LayoutHelper.dp(8f), LayoutHelper.dp(16f), LayoutHelper.dp(24f))
        }

        // ────────────────────────────
        // A12: Owner Greeting / Welcome Sign card
        // ────────────────────────────
        val greetingItem = state.onboardingByClan.greeting
        col.addView(sectionTitle(ctx, getString(R.string.onboarding_welcome_sign)))
        col.addView(descText(ctx, getString(R.string.onboarding_welcome_sign_desc)))

        // Build owner greeting card with gradient border (per spec A12)
        val clan = clansController.clans.value.firstOrNull { it.clanId == state.clanId }
        col.addView(ownerGreetingCard(ctx, clan?.clanName.orEmpty(), clan?.logo.orEmpty(), state), itemLp())

        // ────────────────────────────
        // A14: New Member To-Dos / Missions
        // ────────────────────────────
        col.addView(sectionTitle(ctx, getString(R.string.onboarding_new_member_todos)))
        col.addView(descText(ctx, getString(R.string.onboarding_new_member_todos_desc)))
        col.addView(outlineBtn(ctx, getString(R.string.onboarding_add_mission)) {
            openAddMission(ctx, state)
        }, blockLp(LayoutHelper.dp(8f)))

        // A14.9: Hardcoded preview items — always shown, not editable
        col.addView(hardcodedItemRow(ctx, getString(R.string.onboarding_example_task_dont_do)), itemLp())

        state.onboardingByClan.missions.forEach { item ->
            col.addView(itemRow(ctx, item.title, { openEditMission(ctx, state, item) }, { deleteServerItem(item) }), itemLp())
        }
        state.draft.tasks.forEach { draft ->
            col.addView(draftItemRow(ctx, draft.title), itemLp())
        }

        // ────────────────────────────
        // A13: Resource Pages
        // ────────────────────────────
        col.addView(sectionTitle(ctx, getString(R.string.onboarding_resource_pages)))
        col.addView(descText(ctx, getString(R.string.onboarding_resource_pages_desc)))
        col.addView(outlineBtn(ctx, getString(R.string.onboarding_add_rule)) {
            openAddRule(ctx, state)
        }, blockLp(LayoutHelper.dp(8f)))

        // A13.9: Hardcoded preview item
        col.addView(hardcodedItemRow(ctx, getString(R.string.onboarding_read_the_rules)), itemLp())

        state.onboardingByClan.rules.forEach { item ->
            col.addView(itemRowWithImage(ctx, item, { openEditRule(ctx, item) }, { deleteServerItem(item) }), itemLp())
        }
        state.draft.rules.forEach { draft ->
            col.addView(draftItemRow(ctx, draft.title), itemLp())
        }
        scroll.addView(col)
        return scroll
    }

    /**
     * A12: OwnerGreetingCard — gradient border card showing clan avatar,
     * clan name, and default greeting text. Matches spec visual.
     */
    private fun ownerGreetingCard(ctx: Context, clanName: String, logoUrl: String, state: OnboardingSettingsUiState): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dpf(14f)
                // Gradient border: primary color stroke
                setStroke(LayoutHelper.dp(2), themeColors.primary)
                setColor(themeColors.channelPanelBg)
            }
            setPadding(LayoutHelper.dp(14f), LayoutHelper.dp(14f), LayoutHelper.dp(14f), LayoutHelper.dp(14f))
        }
        // Avatar + name row
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

        // Greeting text
        card.addView(TextView(ctx).apply {
            val existingGreeting = state.onboardingByClan.greeting
            text = existingGreeting?.content?.ifBlank { null }
                ?: getString(R.string.onboarding_owner_greeting_text)
            setTextColor(themeColors.textDisabled)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, LayoutHelper.dp(10f), 0, 0)
        }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        return card
    }

    /** A13/14: Item row with image preview (48dp) on left side for resource items. */
    private fun itemRowWithImage(
        ctx: Context,
        item: OnboardingItem,
        onEdit: (() -> Unit)?,
        onDelete: (() -> Unit)?,
    ): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = cardBg()
            setPadding(LayoutHelper.dp(12f), LayoutHelper.dp(10f), LayoutHelper.dp(12f), LayoutHelper.dp(10f))
        }
        // 48dp image thumbnail if resource has image
        if (item.imageUrl.isNotBlank()) {
            val thumb = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply {
                    cornerRadius = LayoutHelper.dpf(8f)
                    setColor(themeColors.border)
                }
                try {
                    MezonImageLoader.getInstance(ctx).load(
                        item.imageUrl,
                        LayoutHelper.dp(48f),
                        LayoutHelper.dp(48f),
                        onSuccess = { bmp -> setImageBitmap(bmp) }
                    )
                } catch (_: Throwable) {}
            }
            val thumbPx = LayoutHelper.dp(48f)
            row.addView(thumb, LayoutHelper.createLinear(thumbPx / LayoutHelper.dp(1f).toInt(), thumbPx / LayoutHelper.dp(1f).toInt()).apply {
                rightMargin = LayoutHelper.dp(10f)
            })
        }
        row.addView(TextView(ctx).apply {
            text = item.title
            setTextColor(themeColors.colorText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 2
        }, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))
        if (onEdit != null) row.addView(actionIconBtn(ctx, MezonIcon.pencilIcon, themeColors.onSurfaceVariant, onEdit))
        if (onDelete != null) row.addView(actionIconBtn(ctx, MezonIcon.closeSmallBold, themeColors.error, onDelete))
        return row
    }

    /** A9/14: Hardcoded preview row — shown with a 🔒 lock or greyed style; not editable. */
    private fun hardcodedItemRow(ctx: Context, label: String): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = CreateClanRnUiTokens.clanSettingsMenuCornerPx()
            setColor(themeColors.channelPanelBg)
            setStroke(LayoutHelper.dp(1), themeColors.borderDim)
        }
        setPadding(LayoutHelper.dp(12f), LayoutHelper.dp(10f), LayoutHelper.dp(12f), LayoutHelper.dp(10f))
        addView(TextView(ctx).apply {
            text = label
            setTextColor(themeColors.textDisabled)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 1
        }, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))
        // Optional lock icon to indicate non-editable
        addView(TextView(ctx).apply {
            text = "🔒"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        })
    }

    /** Draft item row (unsaved, displayed with "(Draft)" suffix and delete-only). */
    private fun draftItemRow(ctx: Context, title: String): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = CreateClanRnUiTokens.clanSettingsMenuCornerPx()
            setColor(themeColors.channelPanelBg)
            setStroke(LayoutHelper.dp(1), themeColors.primary)
        }
        setPadding(LayoutHelper.dp(12f), LayoutHelper.dp(10f), LayoutHelper.dp(12f), LayoutHelper.dp(10f))
        addView(TextView(ctx).apply {
            text = title
            setTextColor(themeColors.colorText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 2
        }, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))
        addView(TextView(ctx).apply {
            text = getString(R.string.onboarding_draft_badge)
            setTextColor(themeColors.primary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
        })
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
        OnboardingMissionEditorSheet(ctx, channels, onSave = { title, content, channelId, taskType ->
            val draft = MissionDraft(title = title, content = content, channelId = channelId, taskType = taskType)
            if (state.isOnboardingEnabled) {
                launchOnMain {
                    controller.addMissionDraft(draft)
                    controller.saveChanges()
                }
            } else {
                controller.addMissionDraft(draft)
            }
        }).show()
    }

    private fun openEditMission(ctx: Context, state: OnboardingSettingsUiState, item: OnboardingItem) {
        val channels = controller.publicChannelsForClan(state.clanId)
        OnboardingMissionEditorSheet(
            ctx,
            channels,
            item,
            onSave = { title, content, channelId, taskType ->
                launchOnMain { controller.updateServerMission(item, title, content, channelId, taskType) }
            },
            onRemove = {
                deleteServerItem(item)
            },
        ).show()
    }

    private fun openAddRule(ctx: Context, state: OnboardingSettingsUiState) {
        OnboardingRuleEditorSheet(ctx, onSave = { title, content, imageUrl, localPath ->
            val draft = RuleDraft(title = title, content = content, imageUrl = imageUrl, localFilePath = localPath)
            if (state.isOnboardingEnabled) {
                launchOnMain {
                    controller.addRuleDraft(draft)
                    controller.saveChanges()
                }
            } else {
                controller.addRuleDraft(draft)
            }
        }).show()
    }

    private fun openEditRule(ctx: Context, item: OnboardingItem) {
        OnboardingRuleEditorSheet(
            ctx,
            item,
            onSave = { title, content, imageUrl, localPath ->
                launchOnMain { controller.updateServerRule(item, title, content, imageUrl, localPath) }
            },
            onRemove = {
                deleteServerItem(item)
            },
        ).show()
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
            // A5.3: Reset button (outline, secondary)
            addView(outlineBtn(ctx, getString(R.string.common_reset)) {
                AlertDialog.Builder(ctx)
                    .setMessage("Discard all unsaved changes?")
                    .setPositiveButton(getString(R.string.common_reset)) { d, _ ->
                        d.dismiss()
                        controller.clearDraft()
                    }
                    .setNegativeButton(getString(R.string.common_cancel)) { d, _ -> d.dismiss() }
                    .show()
            }, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT).apply {
                rightMargin = LayoutHelper.dp(8f)
            })
            // A5: Save Changes button (primary)
            addView(primaryBtn(ctx, getString(R.string.common_save_changes)) {
                launchOnMain {
                    val result = controller.saveChanges()
                    if (result.isFailure) {
                        MezonToast.show(this@OnboardingSettingsFragment, ToastOverlay.ToastType.ERROR, getString(R.string.onboarding_save_failed))
                    }
                }
            }, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))
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
