package com.mezon.mobile.home.profile

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.clans.ClansController
import com.mezon.mobile.ui.cells.InputCell
import com.mezon.mobile.ui.cells.ToastOverlay

class EditStatusFragment : BaseFragment() {

    private lateinit var accountController: AccountController
    private lateinit var clansController: ClansController

    private lateinit var statusInput: InputCell
    private lateinit var optionsContainer: LinearLayout

    private var selectedDuration: Int = -1
    private val durations = listOf(
        Pair(R.string.status_duration_today, -1),
        Pair(R.string.status_duration_4_hours, 240),
        Pair(R.string.status_duration_1_hour, 60),
        Pair(R.string.status_duration_30_minutes, 30),
        Pair(R.string.status_duration_dont_clear, 0)
    )

    override fun onInject(entryPoint: FragmentEntryPoint) {
        accountController = entryPoint.accountController()
        clansController = entryPoint.clansController()
    }

    override fun createView(context: Context): View {
        actionBar = createActionBar(context).apply {
            setBackButtonImage(R.drawable.ic_close_icon)
            setTitle(context.getString(R.string.status_edit_status))
            setCenterTitle(true)

            val saveItem = createMenu().addItem(1, context.getString(R.string.common_save))
            val saveButtonView = TextView(context).apply {
                text = context.getString(R.string.common_save)
                setTextColor(themeColors.primary)
                textSize = 16f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_VERTICAL
                setPadding(LayoutHelper.dp(16), 0, LayoutHelper.dp(16), 0)
            }
            saveItem.addView(saveButtonView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER_VERTICAL, 0f, 3f, 0f, 0f))
            
            getBackButtonView()?.apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                val p = LayoutHelper.dp(8)
                setPadding(p, p, p, p)
            }

            setMenuOnItemClick(object : com.mezon.mobile.ui.cells.ActionBarView.ActionBarMenuOnItemClick() {
                override fun onItemClick(id: Int) {
                    if (id == -1) finishFragment()
                    if (id == 1) handleSave()
                }
            })
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.background)
        }
        root.addView(actionBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val scroll = ScrollView(context).apply {
            isFillViewport = true
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = LayoutHelper.dp(16)
            setPadding(pad, pad, pad, pad)
        }

        val inputCardBg = GradientDrawable().apply {
            setColor(themeColors.surfaceVariant)
            cornerRadius = LayoutHelper.dpf(12f)
        }
        val inputCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = inputCardBg
            val pad = LayoutHelper.dp(12)
            setPadding(pad, pad, pad, pad)
        }

        statusInput = InputCell(context, themeColors).apply {
            setHint(context.getString(R.string.status_hint))
            setTextarea(true, 128)
            setCellBackgroundColor(themeColors.surfaceVariant)
            setCellStrokeColor(0x00000000)
            editText.gravity = Gravity.TOP or Gravity.START
            val currentStatus = accountController.accountInfo.value.userStatus
            if (currentStatus.isNotEmpty()) {
                setText(currentStatus)
            }
        }
        inputCard.addView(statusInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 120, 0f, 0, 0f, 0f, 0f, 0f))
        content.addView(inputCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0, 0f, 0f, 0f, 24f))

        val durationTitle = TextView(context).apply {
            text = context.getString(R.string.status_duration)
            setTextColor(themeColors.onSurface)
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        content.addView(durationTitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0, 0f, 0f, 0f, 12f))

        val optionsBg = GradientDrawable().apply {
            setColor(themeColors.surfaceVariant)
            cornerRadius = LayoutHelper.dpf(12f)
        }
        optionsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = optionsBg
        }
        content.addView(optionsContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        
        renderOptions(context)

        scroll.addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        root.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f, 0, 0f, 0f, 0f, 0f))
        
        fragmentView = root
        return root
    }

    private fun renderOptions(context: Context) {
        optionsContainer.removeAllViews()
        for ((index, item) in durations.withIndex()) {
            val title = context.getString(item.first)
            val value = item.second
            val isSelected = selectedDuration == value

            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val padH = LayoutHelper.dp(20)
                val padV = LayoutHelper.dp(20)
                setPadding(padH, padV, padH, padV)
                setOnClickListener {
                    com.mezon.mobile.core.AndroidUtilities.hideKeyboard(fragmentView ?: return@setOnClickListener)
                    selectedDuration = value
                    renderOptions(context)
                }
            }

            val labelText = TextView(context).apply {
                text = title
                setTextColor(themeColors.onSurface)
                textSize = 17f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            row.addView(labelText, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 0f, 0f, 0f, 0f))

            val radioContainer = FrameLayout(context).apply {
                val bg = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x00000000)
                    setStroke(LayoutHelper.dp(2), if (isSelected) themeColors.primary else themeColors.outline)
                }
                background = bg
            }
            if (isSelected) {
                val innerCircle = View(context).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(themeColors.primary)
                    }
                }
                radioContainer.addView(innerCircle, LayoutHelper.createFrame(12, 12, Gravity.CENTER))
            }
            row.addView(radioContainer, LayoutHelper.createLinear(24, 24, 0f, Gravity.CENTER_VERTICAL, 16f, 0f, 0f, 0f))

            optionsContainer.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0, 0f, 0f, 0f, 0f))

            if (index < durations.size - 1) {
                val divider = View(context).apply {
                    setBackgroundColor(themeColors.dividerColor)
                }
                optionsContainer.addView(divider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 0f, 0, 16f, 0f, 16f, 0f))
            }
        }
    }

    private fun handleSave() {
        val statusText = statusInput.getText().trim()
        var minutes = selectedDuration
        var noClear = false

        if (minutes == -1) {
            val cal = java.util.Calendar.getInstance()
            cal.set(java.util.Calendar.HOUR_OF_DAY, 23)
            cal.set(java.util.Calendar.MINUTE, 59)
            cal.set(java.util.Calendar.SECOND, 59)
            val timeDiff = cal.timeInMillis - System.currentTimeMillis()
            minutes = (timeDiff / (1000 * 60)).toInt()
        }
        if (selectedDuration == 0) {
            noClear = true
        }

        val clanId = clansController.selectedClanId.value
        
        com.mezon.mobile.core.AndroidUtilities.hideKeyboard(fragmentView ?: return)
        
        android.util.Log.d("EditStatusFragment", "handleSave: clanId=$clanId, text='$statusText', minutes=$minutes, noClear=$noClear")
        accountController.updateCustomStatus(clanId, statusText, minutes, noClear) { success ->
            if (success) {
                finishFragment()
                return@updateCustomStatus
            }
            val parent = getLayoutContainer() ?: (fragmentView as? android.view.ViewGroup) ?: return@updateCustomStatus
            ToastOverlay(requireContext(), themeColors).show(
                parent,
                ToastOverlay.ToastType.ERROR,
                getString(R.string.common_error_connection_failed)
            )
        }
    }
}
