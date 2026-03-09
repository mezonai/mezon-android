package com.mezon.mobile.home.profile

import android.graphics.Color
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.AlertsCreator
import com.mezon.mobile.core.AvatarDrawable
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.ui.cells.ActionButton
import com.mezon.mobile.ui.cells.AvatarView
import com.mezon.mobile.ui.cells.BadgeDrawable
import com.mezon.mobile.ui.cells.ColoredImageSpan
import com.mezon.mobile.ui.cells.HeaderCell
import com.mezon.mobile.ui.cells.IconButton
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.cells.PopupMenu
import com.mezon.mobile.ui.cells.RadioCell
import com.mezon.mobile.ui.cells.SelectPopup
import com.mezon.mobile.ui.cells.ShadowSectionCell
import com.mezon.mobile.ui.cells.SlideOptionView
import com.mezon.mobile.ui.cells.SwitchView
import com.mezon.mobile.ui.cells.TextCheckCell
import com.mezon.mobile.ui.cells.TextDetailCell
import com.mezon.mobile.ui.cells.TextSettingsCell
import com.mezon.mobile.ui.cells.InputCell
import com.mezon.mobile.ui.cells.SearchCell
import com.mezon.mobile.ui.cells.ToggleView
import com.mezon.mobile.ui.cells.ToastOverlay
import com.mezon.mobile.ui.cells.MezonBottomSheetDialog
import com.mezon.mobile.ui.cells.ScreenStateView
import com.mezon.mobile.ui.cells.ImagePickerView
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ComponentPreviewFragment : BaseFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()

        val scrollView = ScrollView(ctx).apply {
            setBackgroundColor(themeColors.background)
        }

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val padH = LayoutHelper.dp(16)
            setPadding(padH, LayoutHelper.dp(16), padH, LayoutHelper.dp(32))
        }
        scrollView.addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val backBtn = TextView(ctx).apply {
            text = "← Back"
            setTextColor(themeColors.primary)
            textSize = 16f
            setPadding(0, 0, 0, LayoutHelper.dp(8))
            setOnClickListener { requireActivity().supportFragmentManager.popBackStack() }
        }
        content.addView(backBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))

        val title = TextView(ctx).apply {
            text = "Component Preview (Canvas Cells)"
            setTextColor(themeColors.onSurface)
            textSize = 22f
        }
        content.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 0f, 0f, 16f))

        addSectionHeader(content, "BadgeDrawable")
        addBadgeSection(content)

        addShadowDivider(content)

        addSectionHeader(content, "ActionButton")
        addActionButtonSection(content)

        addShadowDivider(content)

        addSectionHeader(content, "IconButton")
        addIconButtonSection(content)

        addShadowDivider(content)

        addSectionHeader(content, "ColoredImageSpan")
        addColoredImageSpanSection(content)

        addShadowDivider(content)

        addSectionHeader(content, "RadioCell")
        addRadioCellSection(content)

        addShadowDivider(content)

        addSectionHeader(content, "AvatarView")
        addAvatarSection(content)

        addShadowDivider(content)

        addSectionHeader(content, "SwitchView")
        addSwitchSection(content)

        addShadowDivider(content)

        addSectionHeader(content, "TextCheckCell")
        addTextCheckSection(content)

        addShadowDivider(content)

        addSectionHeader(content, "HeaderCell + TextSettingsCell")
        addSettingsCellSection(content)

        addShadowDivider(content)

        addSectionHeader(content, "TextDetailCell")
        addTextDetailSection(content)

        addShadowDivider(content)

        addSectionHeader(content, "SlideOptionView")
        addSlideOptionSection(content)

        addShadowDivider(content)

        addSectionHeader(content, "AlertsCreator")
        addAlertsSection(content)

        addShadowDivider(content)

        addSectionHeader(content, "PopupMenu")
        addPopupMenuSection(content)

        addShadowDivider(content)

        addSectionHeader(content, "SelectPopup")
        addSelectPopupSection(content)

        addShadowDivider(content)

        addSectionHeader(content, "InputCell")
        addInputSection(content)

        addShadowDivider(content)

        addSectionHeader(content, "SearchCell")
        addSearchSection(content)

        addShadowDivider(content)

        addSectionHeader(content, "ToggleView")
        addToggleSection(content)

        addShadowDivider(content)

        addSectionHeader(content, "ToastOverlay")
        addToastSection(content)

        addShadowDivider(content)

        addSectionHeader(content, "MezonBottomSheetDialog")
        addBottomSheetSection(content)

        addShadowDivider(content)

        addSectionHeader(content, "ScreenStateView")
        addScreenStateSection(content)

        addShadowDivider(content)

        addSectionHeader(content, "ImagePickerView")
        addImagePickerSection(content)

        return scrollView
    }

    private fun addSectionHeader(parent: LinearLayout, text: String) {
        val header = HeaderCell(requireContext(), themeColors)
        header.setText(text)
        parent.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
    }

    private fun addShadowDivider(parent: LinearLayout) {
        val shadow = ShadowSectionCell(requireContext(), themeColors)
        parent.addView(shadow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
    }

    private fun addBadgeSection(parent: LinearLayout) {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        listOf(3, 42, 100).forEach { count ->
            val label = TextView(requireContext()).apply {
                text = "count=$count: "
                setTextColor(themeColors.onSurfaceVariant)
                textSize = 14f
            }
            row.addView(label, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL, 0f, 0f, 4f, 0f))

            val badge = BadgeDrawable(themeColors)
            badge.setCount(count)
            val badgeView = View(requireContext()).apply {
                background = badge
            }
            val w = badge.intrinsicWidth.coerceAtLeast(LayoutHelper.dp(18))
            val h = badge.intrinsicHeight.coerceAtLeast(LayoutHelper.dp(18))
            row.addView(badgeView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL, 0f, 0f, 16f, 0f))
            badgeView.layoutParams.width = w
            badgeView.layoutParams.height = h
        }

        parent.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 8f, 0f, 8f))
    }

    private fun addActionButtonSection(parent: LinearLayout) {
        val primary = ActionButton(requireContext(), themeColors).apply { setText("Primary") }
        parent.addView(primary, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 4f, 0f, 4f))

        val outlined = ActionButton(requireContext(), themeColors).apply {
            setText("Outlined")
            isOutlined = true
        }
        parent.addView(outlined, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 4f, 0f, 4f))

        val disabled = ActionButton(requireContext(), themeColors).apply {
            setText("Disabled")
            isEnabled = false
        }
        parent.addView(disabled, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 4f, 0f, 4f))
    }

    private fun addIconButtonSection(parent: LinearLayout) {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val icons = listOf(
            MezonIcon.phoneCallIcon to "Call",
            MezonIcon.cameraIcon to "Camera",
            MezonIcon.shareIcon to "Share",
            MezonIcon.pencilIcon to "Edit"
        )

        icons.forEach { (mezonIcon, label) ->
            val col = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            val btn = IconButton(requireContext(), themeColors)
            btn.setIcon(mezonIcon)
            col.addView(btn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_HORIZONTAL))

            val tv = TextView(requireContext()).apply {
                text = label
                setTextColor(themeColors.onSurfaceVariant)
                textSize = 12f
                gravity = Gravity.CENTER
            }
            col.addView(tv, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_HORIZONTAL, 0f, 4f, 0f, 0f))
            row.addView(col, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL, 0f, 0f, 24f, 0f))
        }

        parent.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 8f, 0f, 8f))
    }

    private fun addColoredImageSpanSection(parent: LinearLayout) {
        val ctx = requireContext()

        val tv1 = TextView(ctx).apply {
            textSize = 16f
            setTextColor(themeColors.onSurface)
            val ssb = SpannableStringBuilder()
            ssb.append("# General channel")
            val span = ColoredImageSpan(ctx, MezonIcon.channelText.resId, ColoredImageSpan.ALIGN_CENTER)
            span.setSize(LayoutHelper.dp(18))
            ssb.setSpan(span, 0, 1, 0)
            text = ssb
        }
        parent.addView(tv1, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 16f, 8f, 16f, 4f))

        val tv2 = TextView(ctx).apply {
            textSize = 16f
            setTextColor(themeColors.onSurface)
            val ssb = SpannableStringBuilder()
            ssb.append("# Voice channel")
            val span = ColoredImageSpan(ctx, MezonIcon.channelVoice.resId, ColoredImageSpan.ALIGN_CENTER)
            span.setSize(LayoutHelper.dp(18))
            ssb.setSpan(span, 0, 1, 0)
            text = ssb
        }
        parent.addView(tv2, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 16f, 4f, 16f, 4f))

        val tv3 = TextView(ctx).apply {
            textSize = 14f
            setTextColor(themeColors.onSurfaceVariant)
            val ssb = SpannableStringBuilder()
            ssb.append("d Forwarded  d Replied  d Gift")
            val fwd = ColoredImageSpan(ctx, MezonIcon.forwardAllIcon.resId, ColoredImageSpan.ALIGN_CENTER)
            fwd.setSize(LayoutHelper.dp(14))
            fwd.setAlpha(0.9f)
            ssb.setSpan(fwd, 0, 1, 0)
            val rep = ColoredImageSpan(ctx, MezonIcon.reply.resId, ColoredImageSpan.ALIGN_CENTER)
            rep.setSize(LayoutHelper.dp(14))
            rep.setAlpha(0.9f)
            ssb.setSpan(rep, 13, 14, 0)
            val gift = ColoredImageSpan(ctx, MezonIcon.giftIcon.resId, ColoredImageSpan.ALIGN_CENTER)
            gift.setSize(LayoutHelper.dp(14))
            gift.setAlpha(0.9f)
            ssb.setSpan(gift, 24, 25, 0)
            text = ssb
        }
        parent.addView(tv3, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 16f, 4f, 16f, 8f))
    }

    private fun addRadioCellSection(parent: LinearLayout) {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val radio1 = RadioCell(requireContext(), themeColors)
        val radio2 = RadioCell(requireContext(), themeColors).apply { setChecked(true, animated = false) }

        val label1 = TextView(requireContext()).apply {
            text = "Unchecked"
            setTextColor(themeColors.onSurface)
            textSize = 14f
        }
        val label2 = TextView(requireContext()).apply {
            text = "Checked"
            setTextColor(themeColors.onSurface)
            textSize = 14f
        }

        row.addView(radio1, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL))
        row.addView(label1, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL, 8f, 0f, 24f, 0f))
        row.addView(radio2, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL))
        row.addView(label2, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL, 8f, 0f, 0f, 0f))

        radio1.setOnClickListener { radio1.setChecked(!radio1.isChecked()) }
        radio2.setOnClickListener { radio2.setChecked(!radio2.isChecked()) }

        parent.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 8f, 0f, 8f))
    }

    private fun addAvatarSection(parent: LinearLayout) {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val names = listOf("Alice" to 1L, "Bob" to 2L, "Charlie" to 3L, "Diana" to 4L)
        val sizes = listOf(32, 40, 48, 56)

        names.forEachIndexed { idx, (name, id) ->
            val av = AvatarView(requireContext()).apply {
                setSizeDp(sizes[idx])
                setInfo(id, name)
            }
            row.addView(av, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL, 0f, 0f, 12f, 0f))
        }

        parent.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 8f, 0f, 8f))
    }

    private fun addSwitchSection(parent: LinearLayout) {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val sw1 = SwitchView(requireContext(), themeColors)
        val sw2 = SwitchView(requireContext(), themeColors).apply { setChecked(true, animated = false) }

        val label1 = TextView(requireContext()).apply {
            text = "Off"
            setTextColor(themeColors.onSurface)
            textSize = 14f
        }
        val label2 = TextView(requireContext()).apply {
            text = "On"
            setTextColor(themeColors.onSurface)
            textSize = 14f
        }

        row.addView(label1, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL, 0f, 0f, 8f, 0f))
        row.addView(sw1, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL, 0f, 0f, 24f, 0f))
        row.addView(label2, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL, 0f, 0f, 8f, 0f))
        row.addView(sw2, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL))

        parent.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 8f, 0f, 8f))
    }

    private fun addTextCheckSection(parent: LinearLayout) {
        val cell1 = TextCheckCell(requireContext(), themeColors).apply {
            setTextAndCheck("Notifications", "Enable push notifications", checked = true, divider = true)
        }
        parent.addView(cell1, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val cell2 = TextCheckCell(requireContext(), themeColors).apply {
            setTextAndCheck("Dark Mode", checked = false, divider = true)
        }
        parent.addView(cell2, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val cell3 = TextCheckCell(requireContext(), themeColors).apply {
            setTextAndCheck("Auto-Download Media", "Download images and videos automatically", checked = true)
        }
        parent.addView(cell3, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
    }

    private fun addSettingsCellSection(parent: LinearLayout) {
        val header = HeaderCell(requireContext(), themeColors).apply { setText("Account") }
        parent.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val cell1 = TextSettingsCell(requireContext(), themeColors).apply {
            setTextAndValue("Username", "@alice", divider = true)
            setIcon(MezonIcon.userIcon)
        }
        parent.addView(cell1, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val cell2 = TextSettingsCell(requireContext(), themeColors).apply {
            setTextAndValue("Phone", "+1 555-0123", divider = true)
        }
        parent.addView(cell2, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val cell3 = TextSettingsCell(requireContext(), themeColors).apply {
            setTextAndValue("Email", "alice@example.com")
        }
        parent.addView(cell3, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
    }

    private fun addTextDetailSection(parent: LinearLayout) {
        val cell1 = TextDetailCell(requireContext(), themeColors).apply {
            setTextAndValue("Clan", "Select a clan...", divider = true)
        }
        parent.addView(cell1, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val cell2 = TextDetailCell(requireContext(), themeColors).apply {
            setTextAndValue("Invite Link", "https://mezon.ai/invite/abc123")
        }
        parent.addView(cell2, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
    }

    private fun addSlideOptionSection(parent: LinearLayout) {
        val slideView = SlideOptionView(requireContext(), themeColors)
        slideView.setOptions(
            listOf(
                SlideOptionView.Option("light", "Light"),
                SlideOptionView.Option("dark", "Dark"),
                SlideOptionView.Option("abyss", "Abyss")
            ),
            selected = 1
        )
        parent.addView(slideView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 8f, 0f, 8f))
    }

    private fun addAlertsSection(parent: LinearLayout) {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val btn1 = ActionButton(requireContext(), themeColors).apply { setText("Simple Alert") }
        btn1.setOnClickListener {
            AlertsCreator.showSimpleAlert(requireContext(), "Info", "This is a simple alert dialog.", "OK")
        }
        row.addView(btn1, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 0f, 0f, 4f, 0f))

        val btn2 = ActionButton(requireContext(), themeColors).apply {
            setText("Confirm")
            isOutlined = true
        }
        btn2.setOnClickListener {
            AlertsCreator.createConfirmDialog(
                requireContext(), "Delete Message", "This action cannot be undone.",
                confirmText = "Delete", destructive = true
            ) {}.show()
        }
        row.addView(btn2, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 4f, 0f, 0f, 0f))

        parent.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 8f, 0f, 8f))
    }

    private fun addPopupMenuSection(parent: LinearLayout) {
        val anchor = ActionButton(requireContext(), themeColors).apply { setText("Show Popup Menu") }
        anchor.setOnClickListener {
            val popup = PopupMenu(requireContext(), themeColors)
            popup.addItem("Copy", MezonIcon.copyIcon)
            popup.addItem("Edit", MezonIcon.pencilIcon)
            popup.addItem("Delete", MezonIcon.trashIcon, destructive = true)
            popup.setOnItemClickListener {}
            popup.show(anchor)
        }
        parent.addView(anchor, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 8f, 0f, 8f))
    }

    private fun addSelectPopupSection(parent: LinearLayout) {
        val anchor = ActionButton(requireContext(), themeColors).apply { setText("Show Select Popup") }
        anchor.setOnClickListener {
            val popup = SelectPopup(requireContext(), themeColors)
            popup.setItems(
                listOf(
                    SelectPopup.SelectItem("text", "Channel Text"),
                    SelectPopup.SelectItem("voice", "Channel Voice"),
                    SelectPopup.SelectItem("forum", "Channel Forum"),
                    SelectPopup.SelectItem("stream", "Channel Streaming")
                ),
                selected = "text"
            )
            popup.onItemSelected = {}
            popup.show(anchor)
        }
        parent.addView(anchor, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 8f, 0f, 8f))
    }

    private fun addInputSection(parent: LinearLayout) {
        val input1 = InputCell(requireContext(), themeColors).apply {
            setLabel("Username")
            setHint("Enter your username...")
        }
        parent.addView(input1, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 4f, 0f, 8f))

        val input2 = InputCell(requireContext(), themeColors).apply {
            setLabel("Description")
            setHint("Write something...")
            setTextarea(true, 200)
        }
        parent.addView(input2, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 4f, 0f, 8f))

        val input3 = InputCell(requireContext(), themeColors).apply {
            setLabel("Email", required = true)
            setHint("Enter email...")
            setError("Please enter a valid email")
        }
        parent.addView(input3, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 4f, 0f, 8f))

        val input4 = InputCell(requireContext(), themeColors).apply {
            setLabel("ROLE NAME", uppercase = true)
            setText("Disabled field")
            setEnabled(false)
        }
        parent.addView(input4, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 4f, 0f, 8f))
    }

    private fun addSearchSection(parent: LinearLayout) {
        val search1 = SearchCell(requireContext(), themeColors).apply {
            setPlaceholder("Search channels...")
        }
        parent.addView(search1, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 4f, 0f, 8f))

        val search2 = SearchCell(requireContext(), themeColors).apply {
            setPlaceholder("With cancel")
            showCancel = true
        }
        parent.addView(search2, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 4f, 0f, 8f))
    }

    private fun addToggleSection(parent: LinearLayout) {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val toggle1 = ToggleView(requireContext(), themeColors)
        val toggle2 = ToggleView(requireContext(), themeColors).apply { setChecked(true, animated = false) }

        val label1 = TextView(requireContext()).apply {
            text = "Off"
            setTextColor(themeColors.onSurface)
            textSize = 14f
        }
        val label2 = TextView(requireContext()).apply {
            text = "On"
            setTextColor(themeColors.onSurface)
            textSize = 14f
        }

        row.addView(label1, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL, 0f, 0f, 8f, 0f))
        row.addView(toggle1, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL, 0f, 0f, 24f, 0f))
        row.addView(label2, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL, 0f, 0f, 8f, 0f))
        row.addView(toggle2, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL))

        parent.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 8f, 0f, 8f))
    }

    private fun addToastSection(parent: LinearLayout) {
        val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }

        val types = listOf("Success" to ToastOverlay.ToastType.SUCCESS, "Error" to ToastOverlay.ToastType.ERROR, "Info" to ToastOverlay.ToastType.INFO)
        types.forEach { (label, type) ->
            val btn = ActionButton(requireContext(), themeColors).apply {
                setText(label)
                isOutlined = true
            }
            btn.setOnClickListener {
                val overlay = ToastOverlay(requireContext(), themeColors)
                val rootView = requireActivity().findViewById<ViewGroup>(android.R.id.content)
                overlay.show(rootView, type, "$label!", "This is a $label toast message")
            }
            row.addView(btn, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 0f, 0f, 4f, 0f))
        }
        parent.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 8f, 0f, 8f))
    }

    private fun addBottomSheetSection(parent: LinearLayout) {
        val btn = ActionButton(requireContext(), themeColors).apply { setText("Show Bottom Sheet") }
        btn.setOnClickListener {
            MezonBottomSheetDialog.create(requireContext(), themeColors, title = "Select Channel") { container ->
                listOf("General", "Random", "Announcements", "Off-Topic", "Voice Chat").forEach { name ->
                    val cell = TextSettingsCell(requireContext(), themeColors).apply {
                        setTextAndValue(name, divider = name != "Voice Chat")
                    }
                    container.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
                }
            }.show()
        }
        parent.addView(btn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 8f, 0f, 8f))
    }

    private fun addScreenStateSection(parent: LinearLayout) {
        val container = android.widget.FrameLayout(requireContext()).apply {
            minimumHeight = LayoutHelper.dp(150)
            setBackgroundColor(themeColors.surfaceVariant)
        }
        val stateView = ScreenStateView(requireContext(), themeColors)
        container.addView(stateView, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        ))
        parent.addView(container, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 8f, 0f, 4f))

        val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Loading" to 1, "Error" to 2, "Empty" to 3).forEach { (label, mode) ->
            val btn = ActionButton(requireContext(), themeColors).apply {
                setText(label)
                isOutlined = true
            }
            btn.setOnClickListener {
                when (mode) {
                    1 -> stateView.showLoading()
                    2 -> stateView.showError("Something went wrong")
                    3 -> stateView.showEmpty("No messages yet")
                }
            }
            row.addView(btn, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 0f, 0f, 4f, 0f))
        }
        parent.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 4f, 0f, 8f))
    }

    private fun addImagePickerSection(parent: LinearLayout) {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val picker1 = ImagePickerView(requireContext(), themeColors).apply {
            setRounded(true)
            setSizeDp(80)
        }
        row.addView(picker1, LayoutHelper.createLinear(80, 80, 0f, Gravity.CENTER_VERTICAL, 0f, 0f, 16f, 0f))

        val picker2 = ImagePickerView(requireContext(), themeColors).apply {
            setRounded(false)
            setSizeDp(80)
        }
        row.addView(picker2, LayoutHelper.createLinear(80, 80, 0f, Gravity.CENTER_VERTICAL))

        parent.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 8f, 0f, 8f))
    }
}
