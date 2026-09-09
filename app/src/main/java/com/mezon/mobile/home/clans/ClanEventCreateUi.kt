package com.mezon.mobile.home.clans

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.StringRes
import com.mezon.mobile.R
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.util.DateTimeUtil
import com.mezon.mobile.util.createImgproxyUrl
import java.util.Calendar
import java.util.Locale

object ClanEventCreateUi {

    private const val MAX_LOGO_BYTES = 1 * 1024 * 1024
    const val MAX_LOCATION_LENGTH = 100
    const val EVENT_THUMB_SIZE_DP = 56
    const val EVENT_THUMB_CORNER_DP = 8f
    const val EVENT_BANNER_CORNER_DP = 12f

    fun buildEventLogoThumbnail(
        context: Context,
        theme: ThemeColors,
        logoUrl: String,
        onLoadToken: (MezonImageLoader.Cancellable) -> Unit = {},
    ): FrameLayout {
        val corner = LayoutHelper.dpf(EVENT_THUMB_CORNER_DP)
        val size = LayoutHelper.dp(EVENT_THUMB_SIZE_DP)
        val logoWrap = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = corner
                setColor(theme.tertiary)
            }
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
        }
        val logoView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        logoWrap.addView(logoView, FrameLayout.LayoutParams(size, size))
        val trimmed = logoUrl.trim()
        if (trimmed.isNotEmpty()) {
            val token = MezonImageLoader.getInstance(context).load(trimmed, size * 2, size, onSuccess = { bitmap ->
                logoView.setImageBitmap(bitmap)
            })
            onLoadToken(token)
        }
        return logoWrap
    }

    fun defaultStartTime(now: Calendar = Calendar.getInstance()): Calendar = (now.clone() as Calendar).apply {
        if (get(Calendar.MINUTE) != 0 || get(Calendar.SECOND) != 0 || get(Calendar.MILLISECOND) != 0) {
            add(Calendar.HOUR_OF_DAY, 1)
        }
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    fun applyEpochSeconds(calendar: Calendar, epochSeconds: Int) {
        calendar.timeInMillis = DateTimeUtil.epochToMillis(epochSeconds.toLong())
    }

    fun optionFromEvent(event: ClanEventEntity): Int = when {
        event.channelVoiceId != 0L -> ClanEventOption.SPEAKER
        event.isOfflineEvent() -> ClanEventOption.LOCATION
        event.isPrivate -> ClanEventOption.PRIVATE
        else -> ClanEventOption.SPEAKER
    }

    fun sectionCaption(context: Context, theme: ThemeColors, textRes: Int): TextView {
        return TextView(context).apply {
            text = context.getString(textRes)
            setTextColor(theme.onSurfaceVariant)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
        }
    }

    fun sectionDescription(context: Context, theme: ThemeColors, textRes: Int): TextView {
        return TextView(context).apply {
            text = context.getString(textRes)
            setTextColor(theme.onSurfaceVariant)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(LayoutHelper.dp(2).toFloat(), 1f)
        }
    }

    fun createOptionPicker(
        context: Context,
        theme: ThemeColors,
        @StringRes headingRes: Int,
        icon: MezonIcon,
        clearable: Boolean = false,
        placeholder: CharSequence? = null,
        onPick: () -> Unit,
    ): ClanEventOptionPicker {
        return ClanEventOptionPicker(
            context = context,
            theme = theme,
            heading = context.getString(headingRes),
            defaultIcon = icon,
            placeholder = placeholder,
            clearable = clearable,
        ).apply {
            onPickRequested = onPick
        }
    }

    fun pickerLayoutParams(bottomMargin: Int = 0) =
        LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            if (bottomMargin > 0) this.bottomMargin = bottomMargin
        }

    fun formatDate(context: Context, cal: Calendar): String =
        DateFormat.getMediumDateFormat(context).format(cal.time)

    fun formatTime(context: Context, cal: Calendar): String {
        return if (DateFormat.is24HourFormat(context)) {
            String.format(Locale.getDefault(), "%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        } else {
            val hour = cal.get(Calendar.HOUR)
            val displayHour = if (hour == 0) 12 else hour
            val amPm = if (cal.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM"
            String.format(Locale.getDefault(), "%d:%02d %s", displayHour, cal.get(Calendar.MINUTE), amPm)
        }
    }

    fun datePicker(context: Context, cal: Calendar, minDate: Calendar, onPicked: () -> Unit): DatePickerDialog =
        DatePickerDialog(
            context,
            { _, y, m, d ->
                cal.set(Calendar.YEAR, y)
                cal.set(Calendar.MONTH, m)
                cal.set(Calendar.DAY_OF_MONTH, d)
                onPicked()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH),
        ).apply {
            datePicker.minDate = startOfDay(minDate).timeInMillis
        }

    fun timePicker(context: Context, cal: Calendar, onPicked: () -> Unit): TimePickerDialog =
        TimePickerDialog(
            context,
            { _, hour, minute ->
                cal.set(Calendar.HOUR_OF_DAY, hour)
                cal.set(Calendar.MINUTE, minute)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                onPicked()
            },
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE),
            DateFormat.is24HourFormat(context),
        )

    fun combineDateAndTime(date: Calendar, time: Calendar): Calendar {
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, date.get(Calendar.YEAR))
            set(Calendar.MONTH, date.get(Calendar.MONTH))
            set(Calendar.DAY_OF_MONTH, date.get(Calendar.DAY_OF_MONTH))
            set(Calendar.HOUR_OF_DAY, time.get(Calendar.HOUR_OF_DAY))
            set(Calendar.MINUTE, time.get(Calendar.MINUTE))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }

    fun isSameDay(a: Calendar, b: Calendar): Boolean =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    fun startOfDay(cal: Calendar): Calendar = (cal.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    fun repeatTypeLabels(context: Context, start: Calendar): List<Pair<Int, String>> {
        val dayName = start.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.getDefault()).orEmpty()
        val weekOfMonth = start.get(Calendar.DAY_OF_WEEK_IN_MONTH)
        val monthName = start.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.getDefault()).orEmpty()
        val dayOfMonth = start.get(Calendar.DAY_OF_MONTH)
        return listOf(
            ClanEventRepeatType.DOES_NOT_REPEAT to context.getString(R.string.event_creator_repeat_none),
            ClanEventRepeatType.WEEKLY_ON_DAY to context.getString(R.string.event_creator_repeat_weekly, dayName),
            ClanEventRepeatType.EVERY_OTHER_DAY to context.getString(R.string.event_creator_repeat_every_other, dayName),
            ClanEventRepeatType.MONTHLY to context.getString(R.string.event_creator_repeat_monthly, weekOfMonth, dayName),
            ClanEventRepeatType.ANNUALLY to context.getString(R.string.event_creator_repeat_annually, monthName, dayOfMonth),
            ClanEventRepeatType.EVERY_WEEKDAY to context.getString(R.string.event_creator_repeat_weekday),
        ).filter { (type, _) -> type != ClanEventRepeatType.EVERY_WEEKDAY || isWeekday(start) }
    }

    fun isWeekday(date: Calendar): Boolean =
        date.get(Calendar.DAY_OF_WEEK) !in listOf(Calendar.SATURDAY, Calendar.SUNDAY)

    fun hasInvalidTopic(title: String): Boolean = title.any { it in "`<>,/\"\\'" }

    fun validateDetails(
        title: String,
        start: Calendar,
        end: Calendar,
        allowPastStart: Boolean = false,
        now: Calendar = Calendar.getInstance(),
    ): Boolean {
        if (title.isBlank() || hasInvalidTopic(title)) return false
        if (!allowPastStart && isSameDay(start, now) && !start.after(now)) return false
        return !isSameDay(start, end) || end.after(start)
    }

    const val MAX_LOGO_SIZE_BYTES = MAX_LOGO_BYTES

    fun buildCoverBannerSection(
        context: Context,
        theme: ThemeColors,
        onPick: () -> Unit,
        onClear: () -> Unit,
    ): EventCoverBannerPicker {
        return EventCoverBannerPicker.create(context, theme, onPick, onClear)
    }
}

class EventCoverBannerPicker private constructor(
    val section: LinearLayout,
    private val bannerFrame: FrameLayout,
    private val imageView: ImageView,
    private val cameraBadge: ImageView,
    private val clearButton: ImageView,
    private val progressBar: ProgressBar,
    private val context: Context,
) {
    var onPickRequested: (() -> Unit)? = null
    var onClearRequested: (() -> Unit)? = null
    private var previewLoad: MezonImageLoader.Cancellable? = null
    private var previewUrl: String? = null

    init {
        bannerFrame.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right > left && right - left != oldRight - oldLeft) renderPreview()
        }
    }

    fun setUploading(uploading: Boolean) {
        progressBar.visibility = if (uploading) View.VISIBLE else View.GONE
        bannerFrame.isEnabled = !uploading
        clearButton.isEnabled = !uploading
        imageView.alpha = if (uploading) 0.6f else 1f
        cameraBadge.alpha = if (uploading) 0f else 1f
    }

    fun loadPreview(url: String) {
        previewUrl = url.trim()
        if (previewUrl.isNullOrEmpty()) {
            clearImage()
            return
        }
        renderPreview()
    }

    fun clearImage() {
        previewLoad?.cancel()
        previewLoad = null
        previewUrl = null
        imageView.setImageDrawable(null)
        cameraBadge.visibility = View.VISIBLE
        clearButton.visibility = View.GONE
    }

    fun release() {
        clearImage()
        onPickRequested = null
        onClearRequested = null
    }

    private fun renderPreview() {
        val url = previewUrl ?: return
        previewLoad?.cancel()
        val width = bannerFrame.width.coerceAtLeast(LayoutHelper.dp(300))
        val height = LayoutHelper.dp(BANNER_HEIGHT_DP)
        if (bannerFrame.width <= 0) return
        cameraBadge.visibility = View.GONE
        clearButton.visibility = View.VISIBLE
        val proxyUrl = createImgproxyUrl(url, width, height, "fit")
        previewLoad = MezonImageLoader.getInstance(context).load(
            proxyUrl,
            width,
            height,
            onSuccess = { bitmap -> imageView.setImageBitmap(bitmap) },
        )
    }

    companion object {
        private const val BANNER_HEIGHT_DP = 128

        fun create(
            context: Context,
            theme: ThemeColors,
            onPick: () -> Unit,
            onClear: () -> Unit,
        ): EventCoverBannerPicker {
            val imageView = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                isClickable = false
                isFocusable = false
            }
            val cameraBadge = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER
                val pad = LayoutHelper.dp(11)
                setPadding(pad, pad, pad, pad)
                setImageDrawable(MezonIcon.cameraIcon.getDrawable(context, theme.primary))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(theme.surface)
                }
                elevation = LayoutHelper.dpf(2f)
            }
            val clearButton = ImageView(context).apply {
                visibility = View.GONE
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setImageDrawable(MezonIcon.closeSmallBold.getDrawable(context, theme.error))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xCC000000.toInt())
                }
                val inset = LayoutHelper.dp(5)
                setPadding(inset, inset, inset, inset)
                elevation = LayoutHelper.dpf(4f)
            }
            val progressBar = ProgressBar(context).apply {
                visibility = View.GONE
                isIndeterminate = true
            }
            val bannerFrame = FrameLayout(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = LayoutHelper.dpf(ClanEventCreateUi.EVENT_BANNER_CORNER_DP)
                    setColor(theme.secondaryLight)
                    setStroke(LayoutHelper.dp(1), theme.outlineVariant, LayoutHelper.dpf(5f), LayoutHelper.dpf(4f))
                }
                contentDescription = context.getString(R.string.event_creator_cover_label)
                clipToOutline = true
                outlineProvider = ViewOutlineProvider.BACKGROUND
                isClickable = true
                isFocusable = true
                addView(imageView, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ))
                addView(
                    cameraBadge,
                    FrameLayout.LayoutParams(LayoutHelper.dp(44), LayoutHelper.dp(44), Gravity.CENTER),
                )
                addView(
                    clearButton,
                    FrameLayout.LayoutParams(LayoutHelper.dp(30), LayoutHelper.dp(30), Gravity.END or Gravity.TOP).apply {
                        topMargin = LayoutHelper.dp(6)
                        marginEnd = LayoutHelper.dp(6)
                    },
                )
                addView(
                    progressBar,
                    FrameLayout.LayoutParams(LayoutHelper.dp(32), LayoutHelper.dp(32), Gravity.CENTER),
                )
            }
            val section = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(bannerFrame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, BANNER_HEIGHT_DP))
            }
            return EventCoverBannerPicker(
                section = section,
                bannerFrame = bannerFrame,
                imageView = imageView,
                cameraBadge = cameraBadge,
                clearButton = clearButton,
                progressBar = progressBar,
                context = context,
            ).apply {
                onPickRequested = onPick
                onClearRequested = onClear
                bannerFrame.setOnClickListener { onPickRequested?.invoke() }
                cameraBadge.setOnClickListener { onPickRequested?.invoke() }
                clearButton.setOnClickListener { onClearRequested?.invoke() }
            }
        }
    }
}
