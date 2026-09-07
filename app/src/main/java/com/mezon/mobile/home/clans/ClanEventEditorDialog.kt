package com.mezon.mobile.home.clans

import android.app.Dialog
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.mezon.mobile.BuildConfig
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.BottomSheet
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.ui.MezonToast
import com.mezon.mobile.ui.cells.ActionButton
import com.mezon.mobile.ui.cells.InputCell
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.cells.RadioCell
import com.mezon.mobile.ui.cells.SelectPopup
import com.mezon.mobile.ui.cells.ToastOverlay
import com.mezon.mobile.util.DateTimeUtil
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Calendar
import java.util.Locale
import java.util.UUID

class ClanEventEditorDialog private constructor(
    private val host: BaseFragment,
    private val clanId: Long,
    private val editingEventId: Long,
) : ComponentDialog(requireNotNull(host.getParentActivity())) {

    companion object {
        private enum class Step { TYPE, DETAILS, PREVIEW }

        fun show(
            host: BaseFragment,
            clanId: Long,
            eventId: Long = 0L,
        ): ClanEventEditorDialog? {
            val activity = host.getParentActivity() as? ComponentActivity ?: return null
            if (host.isFinished || activity.isFinishing || activity.isDestroyed) return null
            return ClanEventEditorDialog(
                host,
                clanId,
                eventId,
            ).also { it.show() }
        }
    }

    private val themeColors = host.themeColors
    private val entryPoint = EntryPointAccessors.fromApplication(context.applicationContext, FragmentEntryPoint::class.java)
    private val clanEventController = entryPoint.clanEventController()
    private val ioDispatcher = entryPoint.ioDispatcher()
    private val mainDispatcher = entryPoint.mainDispatcher()
    private val notificationCenter = host.notificationCenter
    private val activity = host.getParentActivity() as ComponentActivity
    private var dismissed = false
    private var childDialog: Dialog? = null
    private var repeatPopup: SelectPopup? = null
    private var coverUploadJob: Job? = null
    private var previewLoad: MezonImageLoader.Cancellable? = null
    private lateinit var modalRoot: FrameLayout
    private lateinit var stepCount: TextView
    private lateinit var backAction: ActionButton
    private lateinit var closeAction: ImageView
    private val progressBars = ArrayList<View>()
    private val progressLabels = ArrayList<TextView>()
    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) { dismiss() }
    }
    private val imagePicker = activity.activityResultRegistry.register(
        "event-cover-${UUID.randomUUID()}", ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null && !dismissed && !host.isFinished) uploadCover(uri)
    }
    private val eventObserver = object : NotificationCenter.NotificationCenterDelegate {
        override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
            if (dismissed) return
            if (args.firstOrNull() == clanId && originalEvent == null) {
                if (applyLoadedEventIfNeeded()) {
                    goToStep(Step.TYPE)
                } else if (!clanEventController.isLoading(clanId)) {
                    MezonToast.show(host, ToastOverlay.ToastType.ERROR,
                        clanEventController.getLoadError(clanId) ?: getString(R.string.event_creator_update_failed))
                    dismiss()
                }
            }
        }
    }

    private var originalEvent: ClanEventEntity? = null
    private val typeOptionRows = mutableMapOf<Int, View>()
    private var currentStep = Step.TYPE

    private var selectedOption = 0
    private var channelVoiceId = 0L
    private var address = ""
    private var channelId = 0L
    private var isPrivate = false

    private val startDate: Calendar = ClanEventCreateUi.defaultStartTime()
    private val startTime: Calendar = startDate.clone() as Calendar
    private val endDate: Calendar = (startDate.clone() as Calendar).apply { add(Calendar.HOUR_OF_DAY, 1) }
    private val endTime: Calendar = endDate.clone() as Calendar
    private var repeatType = ClanEventRepeatType.DOES_NOT_REPEAT
    private var logoUrl = ""
    private var submitting = false

    private val optionValues = intArrayOf(
        ClanEventOption.SPEAKER,
        ClanEventOption.LOCATION,
        ClanEventOption.PRIVATE,
    )
    private val radioCells = ArrayList<RadioCell>(3)

    private lateinit var primaryActionText: ActionButton
    private lateinit var stepTypePanel: LinearLayout
    private lateinit var stepDetailsPanel: LinearLayout
    private lateinit var stepPreviewPanel: LinearLayout
    private lateinit var loadingOverlay: FrameLayout
    private lateinit var formScroll: ScrollView

    private lateinit var voiceSelectSection: LinearLayout
    private lateinit var voicePickerRow: ClanEventOptionPicker
    private lateinit var addressCell: InputCell
    private lateinit var channelSection: LinearLayout
    private lateinit var channelPickerRow: ClanEventOptionPicker

    private lateinit var titleCell: InputCell
    private lateinit var descriptionCell: InputCell
    private lateinit var startDatePicker: ClanEventOptionPicker
    private lateinit var startTimePicker: ClanEventOptionPicker
    private lateinit var endDatePicker: ClanEventOptionPicker
    private lateinit var endTimePicker: ClanEventOptionPicker
    private lateinit var repeatPicker: ClanEventOptionPicker
    private lateinit var startTimeError: TextView
    private lateinit var endTimeError: TextView
    private lateinit var coverBannerPicker: EventCoverBannerPicker
    private lateinit var previewHost: LinearLayout

    private fun getString(id: Int, vararg args: Any): String = context.getString(id, *args)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setOwnerActivity(activity)
        activity.lifecycle.addObserver(lifecycleObserver)
        if (isEditMode) notificationCenter.addObserver(eventObserver, NotificationCenter.clanEventsDidLoad)
        setContentView(createContent(context))
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.CENTER)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.52f }
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        setCanceledOnTouchOutside(false)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!submitting) onToolbarBack()
            }
        })
    }

    override fun show() {
        if (dismissed) return
        if (activity.isFinishing || activity.isDestroyed || host.isFinished) {
            dismiss()
            return
        }
        super.show()
        window?.setLayout(
            (context.resources.displayMetrics.widthPixels - LayoutHelper.dp(32)).coerceAtMost(LayoutHelper.dp(560)),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    override fun dismiss() {
        if (dismissed) return
        dismissed = true
        coverUploadJob?.cancel()
        coverUploadJob = null
        childDialog?.let { dialog ->
            if (dialog is BottomSheet) {
                dialog.dismissWithoutAnimation()
            } else dialog.dismiss()
        }
        childDialog = null
        repeatPopup?.dismiss()
        repeatPopup = null
        previewLoad?.cancel()
        previewLoad = null
        imagePicker.unregister()
        activity.lifecycle.removeObserver(lifecycleObserver)
        notificationCenter.removeObserver(eventObserver, NotificationCenter.clanEventsDidLoad)
        if (::coverBannerPicker.isInitialized) coverBannerPicker.release()
        super.dismiss()
    }

    private fun showChildDialog(dialog: Dialog) {
        if (dismissed || activity.isFinishing || activity.isDestroyed) return
        childDialog?.dismiss()
        childDialog = dialog
        dialog.setOnDismissListener { if (childDialog === dialog) childDialog = null }
        dialog.show()
    }

    private fun showError(type: ToastOverlay.ToastType, message: String) {
        ToastOverlay(context, themeColors).show(modalRoot, type, message)
    }

    private fun createContent(context: Context): View {
        val screenPadH = LayoutHelper.dp(20)
        val sectionGap = LayoutHelper.dp(8)
        val majorGap = LayoutHelper.dp(20)
        val cardInnerPad = LayoutHelper.dp(14)
        val voiceChannels = clanEventController.voiceChannels(clanId)

        primaryActionText = ActionButton(context, themeColors).apply {
            setText(getString(R.string.event_creator_action_next))
            setOnClickListener { onPrimaryAction() }
        }
        backAction = ActionButton(context, themeColors).apply {
            isOutlined = true
            setOnClickListener { if (!submitting) onToolbarBack() }
        }
        stepTypePanel = buildTypeStep(context, screenPadH, majorGap, cardInnerPad, voiceChannels)
        stepDetailsPanel = buildDetailsStep(context, screenPadH, sectionGap, majorGap)
        stepPreviewPanel = buildPreviewStep(context, screenPadH)

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(stepTypePanel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            addView(stepDetailsPanel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            addView(stepPreviewPanel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        }

        formScroll = ScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
            addView(
                body,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        loadingOverlay = FrameLayout(context).apply {
            visibility = View.GONE
            isClickable = true
            addView(
                ProgressBar(context).apply { isIndeterminate = true },
                FrameLayout.LayoutParams(LayoutHelper.dp(48), LayoutHelper.dp(48), Gravity.CENTER),
            )
        }

        val bodyRoot = FrameLayout(context).apply {
            addView(formScroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))
            addView(loadingOverlay, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(themeColors.surface, 20f)
            clipToOutline = true
            addView(buildHeader(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            addView(bodyRoot, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))
            addView(View(context).apply { setBackgroundColor(themeColors.outlineVariant) },
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1))
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(screenPadH, LayoutHelper.dp(16), screenPadH, LayoutHelper.dp(16))
                addView(backAction, LinearLayout.LayoutParams(0, LayoutHelper.dp(50), 1f).apply {
                    marginEnd = LayoutHelper.dp(12)
                })
                addView(primaryActionText, LayoutHelper.createLinear(0, 50, 1f))
            }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        }
        modalRoot = object : FrameLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val available = MeasureSpec.getSize(heightMeasureSpec)
                val desired = (resources.displayMetrics.heightPixels * 0.86f).toInt().coerceAtMost(LayoutHelper.dp(780))
                val maxHeight = minOf(available, desired)
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.EXACTLY))
                val panel = when (currentStep) {
                    Step.TYPE -> stepTypePanel
                    Step.DETAILS -> stepDetailsPanel
                    Step.PREVIEW -> stepPreviewPanel
                }
                val contentHeight = panel.measuredHeight + card.getChildAt(0).measuredHeight +
                    card.getChildAt(2).measuredHeight + card.getChildAt(3).measuredHeight
                card.measure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(minOf(contentHeight, maxHeight), MeasureSpec.EXACTLY))
            }
        }.apply {
            addView(card, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER))
        }
        goToStep(Step.TYPE)
        refreshTypeConditionalSections()
        if (isEditMode && !applyLoadedEventIfNeeded()) {
            loadingOverlay.visibility = View.VISIBLE
            clanEventController.loadEvents(clanId, force = true)
        }
        return modalRoot
    }

    private fun roundedBackground(color: Int, radius: Float = 12f, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = LayoutHelper.dpf(radius)
        if (stroke != null) setStroke(LayoutHelper.dp(1), stroke)
    }

    private fun buildHeader(context: Context): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(LayoutHelper.dp(20), LayoutHelper.dp(16), LayoutHelper.dp(20), LayoutHelper.dp(16))
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = getString(if (isEditMode) R.string.event_creator_edit_screen_title else R.string.event_creator_screen_title)
                    textSize = 18f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(themeColors.onSurface)
                })
                stepCount = TextView(context).apply {
                    textSize = 12f
                    setTextColor(themeColors.onSurfaceVariant)
                    setPadding(0, LayoutHelper.dp(4), 0, 0)
                    accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                }
                addView(stepCount)
            }, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))
            closeAction = ImageView(context).apply {
                setImageDrawable(MezonIcon.closeLargeIcon.getDrawable(context, themeColors.onSurfaceVariant))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(LayoutHelper.dp(12), LayoutHelper.dp(12), LayoutHelper.dp(12), LayoutHelper.dp(12))
                contentDescription = getString(R.string.common_cancel)
                setOnClickListener { if (!submitting) dismiss() }
            }
            addView(closeAction, LayoutHelper.createLinear(44, 44))
        })
        addView(LinearLayout(context).apply {
            val labels = intArrayOf(R.string.event_creator_step_location, R.string.event_creator_step_details, R.string.event_creator_preview_header)
            labels.forEachIndexed { index, label ->
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    val bar = View(context)
                    progressBars.add(bar)
                    addView(bar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 4))
                    val text = TextView(context).apply {
                        text = getString(label)
                        textSize = 12f
                        setPadding(0, LayoutHelper.dp(8), 0, 0)
                    }
                    progressLabels.add(text)
                    addView(text)
                }, LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f).apply {
                    if (index < 2) marginEnd = LayoutHelper.dp(8)
                })
            }
        }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply { topMargin = LayoutHelper.dp(18) })
    }

    private val isEditMode: Boolean get() = editingEventId != 0L

    private fun applyLoadedEventIfNeeded(): Boolean {
        if (!isEditMode) return false
        if (originalEvent != null) return true
        val event = clanEventController.getEvent(clanId, editingEventId) ?: return false
        originalEvent = event
        applyEventToState(event)
        applyEventToViews(event)
        loadingOverlay.visibility = View.GONE
        refreshPrimaryAction()
        return true
    }

    private fun applyEventToState(event: ClanEventEntity) {
        selectedOption = ClanEventCreateUi.optionFromEvent(event)
        isPrivate = event.isPrivate
        channelVoiceId = event.channelVoiceId
        address = event.address
        channelId = event.channelId
        repeatType = event.repeatType
        logoUrl = event.logo
        ClanEventCreateUi.applyEpochSeconds(startDate, event.startTimeSeconds)
        ClanEventCreateUi.applyEpochSeconds(startTime, event.startTimeSeconds)
        ClanEventCreateUi.applyEpochSeconds(endDate, event.endTimeSeconds)
        ClanEventCreateUi.applyEpochSeconds(endTime, event.endTimeSeconds)
    }

    private fun applyEventToViews(event: ClanEventEntity) {
        titleCell.setText(event.title)
        descriptionCell.setText(event.description)
        addressCell.setText(event.address)
        refreshTypeRadios()
        typeOptionRows.forEach { (option, row) ->
            row.visibility = if ((option == ClanEventOption.PRIVATE) == event.isPrivate) View.VISIBLE else View.GONE
        }
        refreshTypeConditionalSections()
        refreshVoicePickerRow()
        refreshChannelPickerRow()
        refreshDateTimeLabels()
        validateAndRefresh()
        if (event.logo.isNotBlank()) {
            coverBannerPicker.loadPreview(event.logo)
        }
        refreshPrimaryAction()
    }

    private fun buildTypeStep(
        context: Context,
        screenPadH: Int,
        majorGap: Int,
        cardInnerPad: Int,
        voiceChannels: List<ClanChannelEntity>,
    ): LinearLayout {
        val typeBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        addTypeOptionInCard(context, typeBox, ClanEventOption.SPEAKER, MezonIcon.channelVoice,
            R.string.event_creator_type_voice_title, R.string.event_creator_type_voice_desc,
            voiceChannels.isNotEmpty(), rowPad = cardInnerPad)
        addTypeOptionInCard(context, typeBox, ClanEventOption.LOCATION, MezonIcon.locationIcon,
            R.string.event_creator_type_location_title, R.string.event_creator_type_location_desc,
            true, rowPad = cardInnerPad)
        addTypeOptionInCard(context, typeBox, ClanEventOption.PRIVATE, MezonIcon.lockIcon,
            R.string.event_creator_type_private_title, R.string.event_creator_type_private_desc,
            true, rowPad = cardInnerPad)
        refreshTypeRadios()

        voicePickerRow = ClanEventOptionPicker(
            context,
            themeColors,
            getString(R.string.event_creator_voice_channel_label),
            MezonIcon.channelVoice,
        ).apply {
            onPickRequested = { showVoiceChannelPicker(context, voiceChannels) }
            onClearRequested = {
                channelVoiceId = 0L
                clearSelection()
                refreshPrimaryAction()
            }
            bind(channelVoiceId, voiceChannels)
        }
        voiceSelectSection = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(voicePickerRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        }

        addressCell = InputCell(context, themeColors).apply {
            setCellBackgroundColor(themeColors.secondaryLight)
            setLabel(getString(R.string.event_creator_address_label), required = true)
            setHint(getString(R.string.event_creator_address_placeholder))
            onTextChanged = {
                address = it
                setError(
                    if (it.length > ClanEventCreateUi.MAX_LOCATION_LENGTH) {
                        getString(R.string.event_creator_location_too_long)
                    } else {
                        null
                    },
                )
                refreshPrimaryAction()
            }
        }

        val textChannels = clanEventController.textChannels(clanId)
        channelPickerRow = ClanEventOptionPicker(
            context,
            themeColors,
            getString(R.string.event_creator_linked_channel_label),
        ).apply {
            onPickRequested = { showTextChannelPicker(context) }
            onClearRequested = {
                channelId = 0L
                clearSelection()
            }
            bind(channelId, textChannels)
        }
        channelSection = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(channelPickerRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(screenPadH, LayoutHelper.dp(4), screenPadH, LayoutHelper.dp(20))
            addView(
                sectionTitle(R.string.event_creator_type_title),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT),
            )
            addView(
                ClanEventCreateUi.sectionDescription(context, themeColors, R.string.event_creator_type_subtitle),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                    topMargin = LayoutHelper.dp(6)
                    bottomMargin = LayoutHelper.dp(16)
                },
            )
            addView(typeBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            addView(voiceSelectSection, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                topMargin = majorGap
            })
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    visibility = View.GONE
                    addView(addressCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                        topMargin = majorGap
                    })
                }.also { addressSection = it },
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT),
            )
            addView(channelSection, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                topMargin = majorGap
            })
        }
    }

    private lateinit var addressSection: LinearLayout

    private fun buildDetailsStep(
        context: Context,
        screenPadH: Int,
        sectionGap: Int,
        majorGap: Int,
    ): LinearLayout {
        titleCell = InputCell(context, themeColors).apply {
            setCellBackgroundColor(themeColors.secondaryLight)
            setLabel(null, false, false)
            setMaxCharacter(BuildConfig.MEZON_MAX_LENGTH_NAME_ALLOWED * 2)
            setHint(getString(R.string.event_creator_name_placeholder))
            onTextChanged = {
                setError(if (ClanEventCreateUi.hasInvalidTopic(it)) getString(R.string.event_creator_invalid_topic) else null)
                refreshPrimaryAction()
            }
        }

        startDatePicker = ClanEventCreateUi.createOptionPicker(
            context,
            themeColors,
            R.string.event_creator_start_date_label,
            MezonIcon.calendarIcon,
        ) {
            showChildDialog(ClanEventCreateUi.datePicker(context, startDate, minDate = Calendar.getInstance()) {
                if (ClanEventCreateUi.startOfDay(startDate).after(ClanEventCreateUi.startOfDay(endDate))) {
                    endDate.timeInMillis = startDate.timeInMillis
                }
                refreshDateTimeLabels()
                validateAndRefresh()
            })
        }
        startTimePicker = ClanEventCreateUi.createOptionPicker(
            context,
            themeColors,
            R.string.event_creator_start_time_label,
            MezonIcon.eventTimeIcon,
        ) {
            showChildDialog(ClanEventCreateUi.timePicker(context, startTime) {
                refreshDateTimeLabels()
                validateAndRefresh()
            })
        }
        endDatePicker = ClanEventCreateUi.createOptionPicker(
            context,
            themeColors,
            R.string.event_creator_end_date_label,
            MezonIcon.calendarIcon,
        ) {
            showChildDialog(ClanEventCreateUi.datePicker(context, endDate, minDate = startDate) {
                refreshDateTimeLabels()
                validateAndRefresh()
            })
        }
        endTimePicker = ClanEventCreateUi.createOptionPicker(
            context,
            themeColors,
            R.string.event_creator_end_time_label,
            MezonIcon.eventTimeIcon,
        ) {
            showChildDialog(ClanEventCreateUi.timePicker(context, endTime) {
                refreshDateTimeLabels()
                validateAndRefresh()
            })
        }
        startTimeError = errorText(context)
        endTimeError = errorText(context)

        val endSection = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(dateTimeRow(endDatePicker, endTimePicker))
            addView(endTimeError)
        }

        descriptionCell = InputCell(context, themeColors).apply {
            setCellBackgroundColor(themeColors.secondaryLight)
            setLabel(null, false, false)
            setHint(getString(R.string.event_creator_description_placeholder))
            setTextarea(true, 255)
        }

        repeatPicker = ClanEventCreateUi.createOptionPicker(
            context,
            themeColors,
            R.string.event_creator_repeat_label,
            MezonIcon.calendarIcon,
        ) {
            showRepeatPicker(context)
        }
        startDatePicker.bindValue(ClanEventCreateUi.formatDate(context, startDate))
        startTimePicker.bindValue(ClanEventCreateUi.formatTime(context, startTime))
        endDatePicker.bindValue(ClanEventCreateUi.formatDate(context, endDate))
        endTimePicker.bindValue(ClanEventCreateUi.formatTime(context, endTime))
        repeatPicker.bindValue(repeatLabel(context))

        coverBannerPicker = ClanEventCreateUi.buildCoverBannerSection(
            context,
            themeColors,
            onPick = { openCoverPicker() },
            onClear = {
                logoUrl = ""
                coverBannerPicker.clearImage()
                refreshPrimaryAction()
            },
        )

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(screenPadH, LayoutHelper.dp(4), screenPadH, LayoutHelper.dp(20))
            addView(sectionTitle(R.string.event_creator_details_title))
            addView(ClanEventCreateUi.sectionDescription(context, themeColors, R.string.event_creator_details_subtitle),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                    topMargin = LayoutHelper.dp(6)
                    bottomMargin = majorGap
                })
            titleCell.setLabel(getString(R.string.event_creator_name_label), required = true)
            addView(titleCell)
            addView(dateTimeRow(startDatePicker, startTimePicker),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply { topMargin = majorGap })
            addView(startTimeError)
            addView(endSection, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                topMargin = sectionGap
            })
            addView(repeatPicker, ClanEventCreateUi.pickerLayoutParams().apply { topMargin = majorGap })
            descriptionCell.setLabel(getString(R.string.event_creator_description_label))
            addView(descriptionCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply { topMargin = majorGap })
            addView(ClanEventCreateUi.sectionCaption(context, themeColors, R.string.event_creator_cover_label),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                    topMargin = majorGap
                    bottomMargin = sectionGap
                })
            addView(coverBannerPicker.section)
        }
    }

    private fun sectionTitle(resId: Int) = TextView(context).apply {
        text = getString(resId)
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(themeColors.onSurface)
    }

    private fun dateTimeRow(date: View, time: View): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(date, LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1.15f).apply {
            marginEnd = LayoutHelper.dp(8)
        })
        addView(time, LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f))
    }

    private fun buildPreviewStep(context: Context, screenPadH: Int): LinearLayout {
        previewHost = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(screenPadH, LayoutHelper.dp(4), screenPadH, LayoutHelper.dp(20))
            addView(previewHost, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            addView(
                sectionTitle(R.string.event_creator_preview_title),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                    topMargin = LayoutHelper.dp(20)
                },
            )
            addView(
                TextView(context).apply {
                    tag = "preview_subtitle"
                    setTextColor(themeColors.onSurfaceVariant)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setLineSpacing(LayoutHelper.dp(2).toFloat(), 1f)
                    setPadding(0, LayoutHelper.dp(8), 0, 0)
                },
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT),
            )
        }
    }

    private fun errorText(context: Context): TextView = TextView(context).apply {
        setTextColor(themeColors.badgeRed)
        textSize = 12f
        visibility = View.GONE
        setPadding(LayoutHelper.dp(4), LayoutHelper.dp(4), 0, 0)
    }

    private fun addTypeOptionInCard(
        context: Context,
        parent: LinearLayout,
        typeConst: Int,
        icon: MezonIcon,
        titleRes: Int,
        descRes: Int,
        enabled: Boolean,
        rowPad: Int,
    ) {
        val radio = RadioCell(context, themeColors).apply { drawSelectionAsCheckmark = false }
        radioCells.add(radio)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBackground(themeColors.secondaryLight, stroke = themeColors.outlineVariant)
            isClickable = enabled
            isFocusable = enabled
            alpha = if (enabled) 1f else 0.45f
            setPadding(rowPad, rowPad, rowPad, rowPad)
            setOnClickListener {
                if (enabled) selectOption(typeConst)
            }
        }
        row.addView(
            ImageView(context).apply {
                setImageDrawable(icon.getDrawable(context, themeColors.textStrong))
                scaleType = ImageView.ScaleType.FIT_CENTER
            },
            LayoutHelper.createLinear(24, 24, 0f, Gravity.CENTER_VERTICAL, 0f, 0f, 12f, 0f),
        )
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(context).apply {
                text = getString(titleRes)
                setTextColor(themeColors.textStrong)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(context).apply {
                text = getString(descRes)
                setTextColor(themeColors.onSurfaceVariant)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, LayoutHelper.dp(4), 0, 0)
            })
        }
        row.addView(texts)
        row.addView(radio, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL))
        typeOptionRows[typeConst] = row
        parent.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            bottomMargin = LayoutHelper.dp(8)
        })
    }

    private fun selectOption(option: Int) {
        selectedOption = option
        isPrivate = option == ClanEventOption.PRIVATE
        refreshTypeRadios()
        refreshTypeConditionalSections()
        refreshPrimaryAction()
    }

    private fun refreshTypeRadios() {
        radioCells.forEachIndexed { index, cell ->
            val opt = optionValues.getOrNull(index) ?: return@forEachIndexed
            val selected = opt == selectedOption
            cell.setChecked(selected, animated = true)
            typeOptionRows[opt]?.apply {
                isSelected = selected
                background = roundedBackground(
                    if (selected) ColorUtils.blendARGB(themeColors.surface, themeColors.primary, 0.08f) else themeColors.secondaryLight,
                    stroke = if (selected) themeColors.primary else themeColors.outlineVariant,
                )
            }
        }
    }

    private fun refreshTypeConditionalSections() {
        if (!::voiceSelectSection.isInitialized) return
        val hasVoice = clanEventController.voiceChannels(clanId).isNotEmpty()
        voiceSelectSection.visibility =
            if (selectedOption == ClanEventOption.SPEAKER && hasVoice) View.VISIBLE else View.GONE
        if (::addressSection.isInitialized) {
            addressSection.visibility =
                if (selectedOption == ClanEventOption.LOCATION) View.VISIBLE else View.GONE
        }
        channelSection.visibility =
            if (selectedOption != ClanEventOption.PRIVATE) View.VISIBLE else View.GONE
    }

    private fun goToStep(step: Step) {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(modalRoot.windowToken, 0)
        modalRoot.clearFocus()
        currentStep = step
        stepTypePanel.visibility = if (step == Step.TYPE) View.VISIBLE else View.GONE
        stepDetailsPanel.visibility = if (step == Step.DETAILS) View.VISIBLE else View.GONE
        stepPreviewPanel.visibility = if (step == Step.PREVIEW) View.VISIBLE else View.GONE
        formScroll.scrollTo(0, 0)
        updateToolbarForStep(step)
        if (step == Step.PREVIEW) bindPreview()
        refreshPrimaryAction()
    }

    private fun updateToolbarForStep(step: Step) {
        stepCount.text = getString(R.string.event_creator_step_count, step.ordinal + 1, 3)
        backAction.setText(getString(if (step == Step.TYPE) R.string.common_cancel else R.string.event_creator_action_back))
        primaryActionText.setText(getString(
            if (step != Step.PREVIEW) R.string.event_creator_action_next
            else if (isEditMode) R.string.event_creator_action_save else R.string.event_creator_action_create,
        ))
        progressBars.forEachIndexed { index, bar ->
            bar.background = roundedBackground(if (index <= step.ordinal) themeColors.primary else themeColors.outlineVariant, 3f)
            progressLabels[index].setTextColor(if (index == step.ordinal) themeColors.primary else themeColors.onSurfaceVariant)
            progressLabels[index].typeface = if (index == step.ordinal) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            progressLabels[index].isSelected = index == step.ordinal
        }
        if (step == Step.DETAILS) {
            refreshDateTimeLabels()
            validateAndRefresh()
        }
    }

    private fun onToolbarBack() {
        when (currentStep) {
            Step.TYPE -> dismiss()
            Step.DETAILS -> goToStep(Step.TYPE)
            Step.PREVIEW -> goToStep(Step.DETAILS)
        }
    }

    private fun onPrimaryAction() {
        if (!primaryActionText.isEnabled || submitting) return
        when (currentStep) {
            Step.TYPE -> goToStep(Step.DETAILS)
            Step.DETAILS -> goToStep(Step.PREVIEW)
            Step.PREVIEW -> submitEvent()
        }
    }

    private fun canProceedType(): Boolean {
        if (selectedOption == 0) return false
        if (selectedOption == ClanEventOption.LOCATION && address.isEmpty()) return false
        if (selectedOption == ClanEventOption.LOCATION && address.length > ClanEventCreateUi.MAX_LOCATION_LENGTH) return false
        if (selectedOption == ClanEventOption.SPEAKER && channelVoiceId == 0L) return false
        return true
    }

    private fun refreshPrimaryAction() {
        if (!::closeAction.isInitialized) return
        val enabled = when (currentStep) {
            Step.TYPE -> canProceedType()
            Step.DETAILS -> hasValidDetails()
            Step.PREVIEW -> canProceedType() && !ClanEventCreateUi.hasInvalidTopic(titleCell.getText()) &&
                (!isEditMode || originalEvent?.let { buildDraft().hasChangesFrom(it) } == true)
        }
        primaryActionText.isEnabled = enabled && !submitting && coverUploadJob == null &&
            (!isEditMode || originalEvent != null)
        backAction.isEnabled = !submitting
        closeAction.isEnabled = !submitting
        setCancelable(!submitting)
    }

    private fun refreshVoicePickerRow() {
        if (!::voicePickerRow.isInitialized) return
        voicePickerRow.bind(channelVoiceId, clanEventController.voiceChannels(clanId))
    }

    private fun hasValidDetails(): Boolean = ClanEventCreateUi.validateDetails(
        titleCell.getText(), combinedStart(), combinedEnd(), allowPastStart = allowsPastStart(),
    )

    private fun refreshChannelPickerRow() {
        if (!::channelPickerRow.isInitialized) return
        channelPickerRow.bind(channelId, clanEventController.textChannels(clanId))
    }

    private fun showVoiceChannelPicker(context: Context, channels: List<ClanChannelEntity>) {
        if (channels.isEmpty()) return
        showChildDialog(ChannelPickerSheet(
            context,
            themeColors,
            channels,
            getString(R.string.event_creator_voice_channel_label),
            selectedChannelId = channelVoiceId,
        ) { picked ->
            channelVoiceId = picked.channelId
            refreshVoicePickerRow()
            refreshPrimaryAction()
        })
    }

    private fun showTextChannelPicker(context: Context) {
        val channels = clanEventController.textChannels(clanId)
        if (channels.isEmpty()) {
            showError(ToastOverlay.ToastType.INFO, getString(R.string.clan_invite_need_channel))
            return
        }
        showChildDialog(ChannelPickerSheet(
            context,
            themeColors,
            channels,
            getString(R.string.event_creator_channel_picker_title),
            selectedChannelId = channelId,
        ) { picked ->
            channelId = picked.channelId
            refreshChannelPickerRow()
        })
    }

    private fun refreshDateTimeLabels() {
        val ctx = context
        startDatePicker.bindValue(ClanEventCreateUi.formatDate(ctx, startDate))
        startTimePicker.bindValue(ClanEventCreateUi.formatTime(ctx, startTime))
        endDatePicker.bindValue(ClanEventCreateUi.formatDate(ctx, endDate))
        endTimePicker.bindValue(ClanEventCreateUi.formatTime(ctx, endTime))
        repeatPicker.bindValue(repeatLabel(ctx))
    }

    private fun repeatLabel(context: Context): String {
        val combined = ClanEventCreateUi.combineDateAndTime(startDate, startTime)
        return ClanEventCreateUi.repeatTypeLabels(context, combined)
            .firstOrNull { it.first == repeatType }?.second.orEmpty()
    }

    private fun showRepeatPicker(context: Context) {
        val combined = ClanEventCreateUi.combineDateAndTime(startDate, startTime)
        val labels = ClanEventCreateUi.repeatTypeLabels(context, combined)
        val popup = SelectPopup(context, themeColors)
        repeatPopup?.dismiss()
        repeatPopup = popup
        popup.setItems(labels.map { SelectPopup.SelectItem(it.first.toString(), it.second) }, repeatType.toString())
        popup.onItemSelected = { item ->
            repeatType = item.id.toIntOrNull() ?: ClanEventRepeatType.DOES_NOT_REPEAT
            repeatPicker.bindValue(item.label)
            validateAndRefresh()
        }
        popup.show(repeatPicker, matchAnchorWidth = true)
    }

    private fun combinedStart(): Calendar = ClanEventCreateUi.combineDateAndTime(startDate, startTime)

    private fun combinedEnd(): Calendar = ClanEventCreateUi.combineDateAndTime(endDate, endTime)

    private fun allowsPastStart(): Boolean = repeatType != ClanEventRepeatType.DOES_NOT_REPEAT

    private fun validateAndRefresh() {
        val now = Calendar.getInstance()
        val start = combinedStart()
        val end = combinedEnd()

        val startTimeErr = !allowsPastStart() && ClanEventCreateUi.isSameDay(start, now) && start.timeInMillis <= now.timeInMillis
        startTimeError.visibility = if (startTimeErr) View.VISIBLE else View.GONE
        startTimeError.text = getString(R.string.event_creator_start_time_error)

        val endTimeErr = ClanEventCreateUi.isSameDay(start, end) && !end.after(start)
        endTimeError.visibility = if (endTimeErr) View.VISIBLE else View.GONE
        endTimeError.text = getString(R.string.event_creator_end_time_error)

        refreshPrimaryAction()
    }

    private fun openCoverPicker() {
        if (dismissed || coverUploadJob != null) return
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(modalRoot.windowToken, 0)
        modalRoot.clearFocus()
        imagePicker.launch("image/*")
    }

    private fun uploadCover(uri: Uri) {
        coverBannerPicker.setUploading(true)
        coverUploadJob = host.fragmentScope.launch(mainDispatcher) {
            try {
                val image = withContext(ioDispatcher) { readCoverImage(uri) }
                val url = clanEventController.uploadEventCover(
                    image.bytes,
                    image.mimeType,
                    image.width,
                    image.height,
                )
                onCoverUploaded(url)
            } catch (e: CancellationException) {
                throw e
            } catch (_: CoverTooLargeException) {
                if (!dismissed) {
                    showError(
                        ToastOverlay.ToastType.ERROR,
                        getString(R.string.clan_image_too_large, 1),
                    )
                }
            } catch (_: Exception) {
                if (!dismissed) {
                    showError(
                        ToastOverlay.ToastType.ERROR,
                        getString(R.string.event_creator_cover_upload_failed),
                    )
                }
            } finally {
                coverUploadJob = null
                if (!dismissed) {
                    coverBannerPicker.setUploading(false)
                    refreshPrimaryAction()
                }
            }
        }
        refreshPrimaryAction()
    }

    private fun readCoverImage(uri: Uri): CoverImage {
        val resolver = context.contentResolver
        val declaredMimeType = resolver.getType(uri)?.substringBefore(';')?.lowercase()
        val output = ByteArrayOutputStream()
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                total += count
                if (total > ClanEventCreateUi.MAX_LOGO_SIZE_BYTES) throw CoverTooLargeException()
                output.write(buffer, 0, count)
            }
        } ?: throw IllegalArgumentException("Cannot read cover image")
        val bytes = output.toByteArray()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalArgumentException("Invalid cover image")
        }
        val mimeType = declaredMimeType?.takeIf { it.startsWith("image/") }
            ?: bounds.outMimeType?.lowercase()?.takeIf { it.startsWith("image/") }
            ?: throw IllegalArgumentException("Unsupported cover image")
        return CoverImage(bytes, mimeType, bounds.outWidth, bounds.outHeight)
    }

    private data class CoverImage(
        val bytes: ByteArray,
        val mimeType: String,
        val width: Int,
        val height: Int,
    )

    private class CoverTooLargeException : Exception()

    private fun onCoverUploaded(url: String) {
        if (dismissed || !::coverBannerPicker.isInitialized) return
        logoUrl = url
        coverBannerPicker.loadPreview(url)
        refreshPrimaryAction()
    }

    private fun buildDraft(): CreateEventDraft {
        val startSec = (combinedStart().timeInMillis / 1000L).toInt()
        val endSec = (combinedEnd().timeInMillis / 1000L).toInt()
        return CreateEventDraft(
            option = selectedOption,
            channelVoiceId = if (selectedOption == ClanEventOption.SPEAKER) channelVoiceId else 0L,
            address = if (selectedOption == ClanEventOption.LOCATION) address else "",
            channelId = this.channelId,
            isPrivate = isPrivate,
            title = titleCell.getText(),
            description = descriptionCell.getText(),
            startTimeSeconds = startSec,
            endTimeSeconds = endSec,
            repeatType = repeatType,
            logoUrl = logoUrl,
            editingEventId = editingEventId,
        )
    }

    private fun bindPreview() {
        val context = context
        val draft = buildDraft()
        val subtitleRes = when {
            isEditMode -> R.string.event_creator_preview_subtitle_edit
            draft.option == ClanEventOption.LOCATION -> R.string.event_creator_preview_subtitle_location
            else -> R.string.event_creator_preview_subtitle_voice
        }
        (stepPreviewPanel.findViewWithTag<TextView>("preview_subtitle"))?.text = getString(subtitleRes)
        previewLoad?.cancel()
        previewLoad = null
        previewHost.removeAllViews()
        previewHost.addView(buildPreviewCard(context, draft))
    }

    private fun buildPreviewCard(context: Context, draft: CreateEventDraft): View {
        val pad = LayoutHelper.dp(16)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(themeColors.secondaryLight)
                cornerRadius = LayoutHelper.dpf(12f)
            }
        }
        val pattern = if (DateFormat.is24HourFormat(context)) "EEE, MMM d · HH:mm" else "EEE, MMM d · h:mm a"
        val timeLabel = DateTimeUtil.formatEpochSeconds(draft.startTimeSeconds, pattern, Locale.getDefault())
        root.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(context).apply {
                    setImageDrawable(MezonIcon.eventTimeIcon.getDrawable(context, themeColors.textStrong))
                }, LayoutHelper.createLinear(18, 18))
                addView(TextView(context).apply {
                    text = timeLabel
                    textSize = 13f
                    setTextColor(themeColors.onSurfaceVariant)
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(LayoutHelper.dp(6), 0, 0, 0)
                })
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 10f),
        )
        val mainRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f)
        }
        val badge = when {
            draft.isPrivate -> context.getString(R.string.clan_event_badge_private)
            draft.channelId != 0L -> context.getString(R.string.clan_event_badge_channel)
            else -> context.getString(R.string.clan_event_badge_clan)
        }
        textCol.addView(TextView(context).apply {
            text = badge
            textSize = 11f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
            val ph = LayoutHelper.dp(8)
            val pv = LayoutHelper.dp(3)
            setPadding(ph, pv, ph, pv)
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(6f).toFloat()
                setColor(when {
                    draft.isPrivate -> 0xFFEF4444.toInt()
                    draft.channelId != 0L -> 0xFFF97316.toInt()
                    else -> 0xFF3B82F6.toInt()
                })
            }
        }, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 0f, 0f, 6f))
        textCol.addView(TextView(context).apply {
            text = draft.title
            textSize = 18f
            setTextColor(themeColors.textStrong)
            typeface = Typeface.DEFAULT_BOLD
        })
        if (draft.description.isNotBlank()) {
            textCol.addView(TextView(context).apply {
                text = draft.description
                textSize = 13f
                setTextColor(themeColors.onSurfaceVariant)
                maxLines = 2
            }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 4f, 0f, 0f))
        }
        val locationText = when (draft.option) {
            ClanEventOption.LOCATION -> draft.address
            ClanEventOption.SPEAKER ->
                clanEventController.getChannel(clanId, draft.channelVoiceId)?.channelLabel
                    ?: context.getString(R.string.clan_event_private_room)
            else -> context.getString(R.string.clan_event_private_room)
        }
        val locationIcon = if (draft.option == ClanEventOption.LOCATION) MezonIcon.locationIcon else MezonIcon.channelVoice
        textCol.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(context).apply {
                    setImageDrawable(locationIcon.getDrawable(context, themeColors.textStrong))
                }, LayoutHelper.createLinear(18, 18))
                addView(TextView(context).apply {
                    text = locationText
                    textSize = 14f
                    setTextColor(themeColors.onSurfaceVariant)
                    setPadding(LayoutHelper.dp(8), 0, 0, 0)
                })
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 4f, 0f, 0f),
        )
        mainRow.addView(textCol)
        if (draft.logoUrl.isNotBlank()) {
            mainRow.addView(
                ClanEventCreateUi.buildEventLogoThumbnail(context, themeColors, draft.logoUrl) { previewLoad = it },
                LinearLayout.LayoutParams(LayoutHelper.dp(ClanEventCreateUi.EVENT_THUMB_SIZE_DP), LayoutHelper.dp(ClanEventCreateUi.EVENT_THUMB_SIZE_DP)).apply {
                    leftMargin = LayoutHelper.dp(10)
                },
            )
        }
        root.addView(mainRow)
        if (draft.channelId != 0L) {
            clanEventController.getChannel(clanId, draft.channelId)?.let { channel ->
                root.addView(TextView(context).apply {
                    text = getString(R.string.clan_event_channel_in, channel.channelLabel)
                    textSize = 12f
                    setTextColor(themeColors.onSurfaceVariant)
                    setPadding(0, LayoutHelper.dp(12), 0, 0)
                })
            }
        }
        return root
    }

    private fun submitEvent() {
        if (submitting) return
        val original = originalEvent
        if (isEditMode && original == null) return
        submitting = true
        loadingOverlay.visibility = View.VISIBLE
        refreshPrimaryAction()
        val draft = buildDraft()
        if (original != null) {
            clanEventController.updateEvent(draft, clanId, original, ::onSubmitDone)
        } else {
            clanEventController.createEvent(draft, clanId, ::onSubmitDone)
        }
    }

    private fun onSubmitDone(success: Boolean, error: String?) {
        if (dismissed) return
        submitting = false
        loadingOverlay.visibility = View.GONE
        if (success) {
            val message = if (isEditMode) {
                getString(R.string.event_creator_update_success)
            } else {
                getString(R.string.event_creator_create_success)
            }
            MezonToast.show(host, ToastOverlay.ToastType.SUCCESS, message)
            dismiss()
        } else {
            refreshPrimaryAction()
            val fallback = if (isEditMode) {
                getString(R.string.event_creator_update_failed)
            } else {
                getString(R.string.event_creator_create_failed)
            }
            showError(ToastOverlay.ToastType.ERROR, error ?: fallback)
        }
    }
}
