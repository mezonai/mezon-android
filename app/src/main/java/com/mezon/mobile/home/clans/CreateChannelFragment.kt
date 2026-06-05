package com.mezon.mobile.home.clans

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import com.mezon.mobile.MainActivity
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.network.CHANNEL_TYPE_CHANNEL
import com.mezon.mobile.ui.cells.ActionBarView
import com.mezon.mobile.ui.cells.InputCell
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.cells.RadioCell
import com.mezon.mobile.ui.cells.SwitchView
import com.mezon.mobile.ui.cells.ToastOverlay
import com.mezon.mobile.util.CreateChannelNameValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CreateChannelFragment : BaseFragment() {

    companion object {
        private const val ARG_CATEGORY_ID = "create_channel_category_id"

        fun newInstance(categoryId: Long): CreateChannelFragment =
            CreateChannelFragment().apply {
                arguments = Bundle().apply { putLong(ARG_CATEGORY_ID, categoryId) }
            }
    }

    private lateinit var channelController: ChannelController
    private lateinit var clansController: ClansController

    private var categoryId: Long = 0L
    private var creating = false
    private var selectedType = CHANNEL_TYPE_CHANNEL
    private val rowRadioCells = ArrayList<RadioCell>(3)

    private lateinit var saveButtonText: TextView
    private lateinit var nameCell: InputCell
    private lateinit var privateSectionRoot: LinearLayout
    private lateinit var privateSwitch: SwitchView
    private lateinit var loadingOverlay: FrameLayout

    override fun onInject(entryPoint: FragmentEntryPoint) {
        channelController = entryPoint.channelController()
        clansController = entryPoint.clansController()
    }

    override fun onFragmentCreate(): Boolean {
        categoryId = arguments?.getLong(ARG_CATEGORY_ID, 0L) ?: 0L
        return super.onFragmentCreate()
    }

    override fun createView(context: Context): View {
        val screenPadH = LayoutHelper.dp(16)
        val sectionCaptionToFieldSpacing = LayoutHelper.dp(8)
        val majorSectionVerticalGap = LayoutHelper.dp(24)
        val typeAndPrivateCardInnerPadding = LayoutHelper.dp(16)
        val cardRadius = LayoutHelper.dpf(12f)

        saveButtonText = TextView(context).apply {
            text = getString(R.string.channel_creator_action_create)
            setTextColor(themeColors.primary)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(16), 0, LayoutHelper.dp(16), 0)
            setOnClickListener { submitCreate() }
        }

        actionBar = ActionBarView(context, themeColors).apply {
            occupyStatusBar = false
            setBackButtonImage(MezonIcon.closeLargeIcon.resId)
            setTitle(getString(R.string.channel_creator_screen_title))
            setTitleColor(themeColors.textStrong)
            setCenterTitle(true)
            createMenu().addItem(1, "").also { cell ->
                cell.addView(
                    saveButtonText,
                    LayoutHelper.createFrame(
                        LayoutHelper.WRAP_CONTENT,
                        LayoutHelper.MATCH_PARENT,
                        Gravity.CENTER_VERTICAL,
                        0f,
                        3f,
                        0f,
                        0f
                    )
                )
            }
            setMenuOnItemClick(object : ActionBarView.ActionBarMenuOnItemClick() {
                override fun onItemClick(id: Int) {
                    when (id) {
                        -1 -> finishFragment()
                        1 -> submitCreate()
                    }
                }
            })
            getBackButtonView()?.apply {
                val px = LayoutHelper.dp(16)
                setPadding(px, px, px, px)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
        }

        fun mutedBoldSectionCaption(textRes: Int): TextView = TextView(context).apply {
            text = getString(textRes)
            setTextColor(themeColors.onSurfaceVariant)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
        }

        val nameHeading = mutedBoldSectionCaption(R.string.channel_creator_channel_name_title).apply {
            setPadding(0, 0, 0, sectionCaptionToFieldSpacing)
        }

        nameCell = InputCell(context, themeColors).apply {
            setLabel(null, false, false)
            setMaxCharacter(64)
            setHint(getString(R.string.channel_creator_channel_name_placeholder))
            onTextChanged = {
                refreshCreateOpacity()
                setError(null)
            }
            editText.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }

        val typeHeading = mutedBoldSectionCaption(R.string.channel_creator_channel_type_title).apply {
            setPadding(0, 0, 0, sectionCaptionToFieldSpacing)
        }

        val typeBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(themeColors.surfaceVariant)
                cornerRadius = cardRadius
            }
        }

        addTypeOption(context, typeBox, CHANNEL_TYPE_CHANNEL, MezonIcon.channelText,
            R.string.channel_creator_type_text_title, R.string.channel_creator_type_text_desc,
            showTopDivider = false, rowPad = typeAndPrivateCardInnerPadding)
        addTypeOption(context, typeBox, CHANNEL_TYPE_VOICE, MezonIcon.channelVoice,
            R.string.channel_creator_type_voice_title, R.string.channel_creator_type_voice_desc,
            showTopDivider = true, rowPad = typeAndPrivateCardInnerPadding)
        addTypeOption(context, typeBox, CHANNEL_TYPE_STREAMING, MezonIcon.channelStream,
            R.string.channel_creator_type_stream_title, R.string.channel_creator_type_stream_desc,
            showTopDivider = true, rowPad = typeAndPrivateCardInnerPadding)

        refreshTypeRadios()

        privateSectionRoot = buildPrivateChannelSection(context, cardRadius, typeAndPrivateCardInnerPadding)

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(screenPadH, LayoutHelper.dp(16), screenPadH, LayoutHelper.dp(28))
            addView(nameHeading, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            addView(nameCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            addView(
                typeHeading,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                    topMargin = majorSectionVerticalGap
                }
            )
            addView(typeBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            addView(
                privateSectionRoot,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                    topMargin = majorSectionVerticalGap
                }
            )
            addView(Space(context), LinearLayout.LayoutParams(
                LayoutHelper.MATCH_PARENT,
                0,
                1f
            ))
        }

        val scroll = ScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
            addView(body, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
        }

        loadingOverlay = FrameLayout(context).apply {
            visibility = View.GONE
            setBackgroundColor(0x40000000)
            isClickable = true
            addView(
                ProgressBar(context).apply { isIndeterminate = true },
                FrameLayout.LayoutParams(LayoutHelper.dp(48), LayoutHelper.dp(48), Gravity.CENTER)
            )
        }

        val bodyRoot = FrameLayout(context).apply {
            setBackgroundColor(themeColors.background)
            addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))
            addView(loadingOverlay, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))
        }

        val rootWithBar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(actionBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            addView(bodyRoot, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))
        }

        fragmentView = rootWithBar
        refreshPrivateBlock()
        refreshCreateOpacity()

        return rootWithBar
    }

    private fun buildPrivateChannelSection(context: Context, cardRadius: Float, rowPad: Int): LinearLayout {
        val titleView = TextView(context).apply {
            text = getString(R.string.channel_creator_private_title)
            setTextColor(themeColors.textStrong)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
        }
        privateSwitch = SwitchView(context, themeColors).apply {
            setChecked(false, animated = false)
        }
        val lock = ImageView(context).apply {
            setImageDrawable(MezonIcon.lockIcon.getDrawable(context, themeColors.textStrong))
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(rowPad, rowPad, rowPad, rowPad)
            background = GradientDrawable().apply {
                setColor(themeColors.surfaceVariant)
                cornerRadius = cardRadius
            }
            addView(lock, LayoutHelper.createLinear(22, 22).apply { rightMargin = LayoutHelper.dp(12) })
            addView(titleView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))
            addView(privateSwitch, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))
        }
        val helper = TextView(context).apply {
            text = getString(R.string.channel_creator_private_description)
            setTextColor(themeColors.onSurfaceVariant)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setLineSpacing(LayoutHelper.dp(2).toFloat(), 1f)
            setPadding(0, LayoutHelper.dp(12), 0, 0)
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            addView(helper, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        }
    }

    private fun addTypeOption(
        context: Context,
        parent: LinearLayout,
        typeConst: Int,
        icon: MezonIcon,
        titleRes: Int,
        descRes: Int,
        showTopDivider: Boolean,
        rowPad: Int
    ) {
        if (showTopDivider) {
            val div = View(context).apply { setBackgroundColor(themeColors.outlineVariant) }
            parent.addView(div, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.dp(1)))
        }

        val radio = RadioCell(context, themeColors).apply {
            drawSelectionAsCheckmark = false
        }
        rowRadioCells.add(radio)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            isBaselineAligned = false
            isClickable = true
            isFocusable = true
            setPadding(rowPad, rowPad, rowPad, rowPad)
            setOnClickListener {
                selectedType = typeConst
                refreshTypeRadios()
                refreshPrivateBlock()
                refreshCreateOpacity()
            }
        }

        val iconView = ImageView(context).apply {
            setImageDrawable(icon.getDrawable(context, themeColors.textStrong))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        row.addView(
            iconView,
            LayoutHelper.createLinear(
                width = 24,
                height = LayoutHelper.MATCH_PARENT,
                gravity = Gravity.CENTER_VERTICAL,
                rightMargin = 12f
            )
        )

        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = getString(titleRes)
                setTextColor(themeColors.textStrong)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Typeface.DEFAULT_BOLD
            }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            addView(TextView(context).apply {
                text = getString(descRes)
                setTextColor(themeColors.onSurfaceVariant)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, LayoutHelper.dp(4), 0, 0)
            }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        }
        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))
        row.addView(
            radio,
            LayoutHelper.createLinear(
                width = LayoutHelper.WRAP_CONTENT,
                height = LayoutHelper.WRAP_CONTENT,
                gravity = Gravity.CENTER_VERTICAL
            )
        )
        parent.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
    }

    private fun refreshTypeRadios() {
        val types = intArrayOf(
            CHANNEL_TYPE_CHANNEL,
            CHANNEL_TYPE_VOICE,
            CHANNEL_TYPE_STREAMING
        )
        rowRadioCells.forEachIndexed { i, radio ->
            val v = types.getOrNull(i) ?: 0
            radio.setChecked(v == selectedType, animated = false)
        }
    }

    private fun refreshPrivateBlock() {
        val show = selectedType == CHANNEL_TYPE_CHANNEL
        privateSectionRoot.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun refreshCreateOpacity() {
        val ok = nameCell.getText().trim().isNotEmpty()
        saveButtonText.alpha = if (ok) 1f else 0.45f
    }

    private fun submitCreate() {
        if (creating) return
        val clanId = clansController.selectedClanId.value
        if (clanId == 0L || categoryId == 0L) {
            showToast(getString(R.string.common_something_went_wrong))
            return
        }
        val name = nameCell.getText().trim()
        if (!CreateChannelNameValidator.isValid(name)) {
            nameCell.setError(getString(R.string.channel_creator_channel_name_error))
            return
        }
        val privateFlag = when (selectedType) {
            CHANNEL_TYPE_CHANNEL -> if (privateSwitch.isChecked()) 1 else 0
            else -> 0
        }

        fragmentScope.launch(Dispatchers.Main) {
            creating = true
            loadingOverlay.visibility = View.VISIBLE
            try {
                val isDuplicate = channelController.checkDuplicateChannelName(name, 2, categoryId).getOrDefault(false)
                if (isDuplicate) {
                    showToast(getString(R.string.channel_creator_channel_name_duplicate_error))
                    return@launch
                }

                val desc = channelController.createClanChannel(
                    clanId,
                    categoryId,
                    selectedType,
                    name,
                    privateFlag
                )
                afterCreateSuccess(desc.channelId, desc.channelLabel, clanId, desc.type)
            } catch (e: Exception) {
                val msg = e.message?.takeIf { it.length < 280 } ?: getString(R.string.common_something_went_wrong)
                showToast(msg)
            } finally {
                creating = false
                loadingOverlay.visibility = View.GONE
            }
        }
    }

    private fun afterCreateSuccess(channelId: Long, label: String, clanIdParam: Long, channelType: Int) {
        val ctx = fragmentView?.context
        if (ctx != null) {
            OwnerOnboardingManager.setCreatedChannel(ctx, clanIdParam, true)
            notificationCenter.postNotificationName(NotificationCenter.ownerOnboardingStateChanged)
        }
        fragmentView?.post {
            val act = getParentActivity() as? MainActivity ?: return@post
            when (channelType) {
                CHANNEL_TYPE_VOICE, CHANNEL_TYPE_STREAMING -> {
                    finishFragment()
                }
                else -> act.openChat(
                    channelId = channelId,
                    channelName = label,
                    clanId = clanIdParam,
                    channelType = CHANNEL_TYPE_CHANNEL,
                    replaceLastFragment = true
                )
            }
        }
    }

    private fun showToast(text: String) {
        val ctx = getContext() ?: return
        val root =
            getParentActivity()?.findViewById<ViewGroup>(android.R.id.content) ?: return
        ToastOverlay(ctx, themeColors).show(root, ToastOverlay.ToastType.ERROR, text)
    }
}
