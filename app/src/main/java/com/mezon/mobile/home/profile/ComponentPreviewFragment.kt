package com.mezon.mobile.home.profile

import android.content.Context
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.view.Gravity
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

class ComponentPreviewFragment : BaseFragment() {

    override fun createView(context: Context): View {
        val scrollView = ScrollView(context).apply {
            setBackgroundColor(themeColors.background)
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padH = LayoutHelper.dp(16)
            setPadding(padH, LayoutHelper.dp(16), padH, LayoutHelper.dp(32))
        }
        scrollView.addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val backBtn = TextView(context).apply {
            text = "← Back"
            setTextColor(themeColors.primary)
            textSize = 16f
            setPadding(0, 0, 0, LayoutHelper.dp(8))
            setOnClickListener { finishFragment() }
        }
        content.addView(backBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))

        val title = TextView(context).apply {
            text = "Component Preview (Canvas Cells)"
            setTextColor(themeColors.onSurface)
            textSize = 22f
        }
        content.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 0f, 0f, 16f))

        addSectionHeader(context, content, "BadgeDrawable")
        addBadgeSection(context, content)
        addShadowDivider(context, content)
        addSectionHeader(context, content, "ActionButton")
        addActionButtonSection(context, content)
        addShadowDivider(context, content)
        addSectionHeader(context, content, "IconButton")
        addIconButtonSection(context, content)
        addShadowDivider(context, content)
        addSectionHeader(context, content, "ColoredImageSpan")
        addColoredImageSpanSection(context, content)
        addShadowDivider(context, content)
        addSectionHeader(context, content, "RadioCell")
        addRadioCellSection(context, content)
        addShadowDivider(context, content)
        addSectionHeader(context, content, "AvatarView")
        addAvatarSection(context, content)
        addShadowDivider(context, content)
        addSectionHeader(context, content, "SwitchView")
        addSwitchSection(context, content)
        addShadowDivider(context, content)
        addSectionHeader(context, content, "TextCheckCell")
        addTextCheckSection(context, content)
        addShadowDivider(context, content)
        addSectionHeader(context, content, "HeaderCell + TextSettingsCell")
        addSettingsCellSection(context, content)
        addShadowDivider(context, content)
        addSectionHeader(context, content, "TextDetailCell")
        addTextDetailSection(context, content)
        addShadowDivider(context, content)
        addSectionHeader(context, content, "SlideOptionView")
        addSlideOptionSection(context, content)
        addShadowDivider(context, content)
        addSectionHeader(context, content, "AlertsCreator")
        addAlertsSection(context, content)
        addShadowDivider(context, content)
        addSectionHeader(context, content, "PopupMenu")
        addPopupMenuSection(context, content)
        addShadowDivider(context, content)
        addSectionHeader(context, content, "SelectPopup")
        addSelectPopupSection(context, content)
        addShadowDivider(context, content)
        addSectionHeader(context, content, "InputCell")
        addInputSection(context, content)
        addShadowDivider(context, content)
        addSectionHeader(context, content, "SearchCell")
        addSearchSection(context, content)
        addShadowDivider(context, content)
        addSectionHeader(context, content, "ToggleView")
        addToggleSection(context, content)
        addShadowDivider(context, content)
        addSectionHeader(context, content, "ToastOverlay")
        addToastSection(context, content)
        addShadowDivider(context, content)
        addSectionHeader(context, content, "MezonBottomSheetDialog")
        addBottomSheetSection(context, content)
        addShadowDivider(context, content)
        addSectionHeader(context, content, "ScreenStateView")
        addScreenStateSection(context, content)
        addShadowDivider(context, content)
        addSectionHeader(context, content, "ImagePickerView")
        addImagePickerSection(context, content)

        return scrollView
    }

    private fun addSectionHeader(context: Context, parent: LinearLayout, text: String) {
        val header = HeaderCell(context, themeColors)
        header.setText(text)
        parent.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
    }

    private fun addShadowDivider(context: Context, parent: LinearLayout) {
        parent.addView(ShadowSectionCell(context, themeColors), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
    }

    private fun addBadgeSection(context: Context, parent: LinearLayout) {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        listOf(3, 42, 100).forEach { count ->
            val label = TextView(context).apply { text = "count=$count: "; setTextColor(themeColors.onSurfaceVariant); textSize = 14f }
            row.addView(label, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL, 0f, 0f, 4f, 0f))
            val badge = BadgeDrawable(themeColors); badge.setCount(count)
            val badgeView = View(context).apply { background = badge }
            val w = badge.intrinsicWidth.coerceAtLeast(LayoutHelper.dp(18))
            val h = badge.intrinsicHeight.coerceAtLeast(LayoutHelper.dp(18))
            row.addView(badgeView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL, 0f, 0f, 16f, 0f))
            badgeView.layoutParams.width = w; badgeView.layoutParams.height = h
        }
        parent.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 8f, 0f, 8f))
    }

    private fun addActionButtonSection(context: Context, parent: LinearLayout) {
        parent.addView(ActionButton(context, themeColors).apply { setText("Primary") }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 4f, 0f, 4f))
        parent.addView(ActionButton(context, themeColors).apply { setText("Outlined"); isOutlined = true }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 4f, 0f, 4f))
        parent.addView(ActionButton(context, themeColors).apply { setText("Disabled"); isEnabled = false }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 4f, 0f, 4f))
    }

    private fun addIconButtonSection(context: Context, parent: LinearLayout) {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        listOf(MezonIcon.phoneCallIcon to "Call", MezonIcon.cameraIcon to "Camera", MezonIcon.shareIcon to "Share", MezonIcon.pencilIcon to "Edit").forEach { (icon, label) ->
            val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            val btn = IconButton(context, themeColors); btn.setIcon(icon)
            col.addView(btn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_HORIZONTAL))
            col.addView(TextView(context).apply { text = label; setTextColor(themeColors.onSurfaceVariant); textSize = 12f; gravity = Gravity.CENTER }, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_HORIZONTAL, 0f, 4f, 0f, 0f))
            row.addView(col, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL, 0f, 0f, 24f, 0f))
        }
        parent.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 8f, 0f, 8f))
    }

    private fun addColoredImageSpanSection(context: Context, parent: LinearLayout) {
        val tv = TextView(context).apply {
            textSize = 16f; setTextColor(themeColors.onSurface)
            val ssb = SpannableStringBuilder(); ssb.append("# General channel")
            val span = ColoredImageSpan(context, MezonIcon.channelText.resId, ColoredImageSpan.ALIGN_CENTER); span.setSize(LayoutHelper.dp(18)); ssb.setSpan(span, 0, 1, 0)
            text = ssb
        }
        parent.addView(tv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 16f, 8f, 16f, 8f))
    }

    private fun addRadioCellSection(context: Context, parent: LinearLayout) {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val radio1 = RadioCell(context, themeColors); val radio2 = RadioCell(context, themeColors).apply { setChecked(true, animated = false) }
        row.addView(radio1, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL))
        row.addView(TextView(context).apply { text = "Unchecked"; setTextColor(themeColors.onSurface); textSize = 14f }, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL, 8f, 0f, 24f, 0f))
        row.addView(radio2, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL))
        row.addView(TextView(context).apply { text = "Checked"; setTextColor(themeColors.onSurface); textSize = 14f }, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL, 8f, 0f, 0f, 0f))
        radio1.setOnClickListener { radio1.setChecked(!radio1.isChecked()) }; radio2.setOnClickListener { radio2.setChecked(!radio2.isChecked()) }
        parent.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 8f, 0f, 8f))
    }

    private fun addAvatarSection(context: Context, parent: LinearLayout) {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        listOf("Alice" to 1L, "Bob" to 2L, "Charlie" to 3L, "Diana" to 4L).forEachIndexed { idx, (name, id) ->
            val av = AvatarView(context).apply { setSizeDp(listOf(32, 40, 48, 56)[idx]); setInfo(id, name) }
            row.addView(av, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL, 0f, 0f, 12f, 0f))
        }
        parent.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 8f, 0f, 8f))
    }

    private fun addSwitchSection(context: Context, parent: LinearLayout) {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val sw1 = SwitchView(context, themeColors); val sw2 = SwitchView(context, themeColors).apply { setChecked(true, animated = false) }
        row.addView(TextView(context).apply { text = "Off"; setTextColor(themeColors.onSurface); textSize = 14f }, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL, 0f, 0f, 8f, 0f))
        row.addView(sw1, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL, 0f, 0f, 24f, 0f))
        row.addView(TextView(context).apply { text = "On"; setTextColor(themeColors.onSurface); textSize = 14f }, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL, 0f, 0f, 8f, 0f))
        row.addView(sw2, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL))
        parent.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 8f, 0f, 8f))
    }

    private fun addTextCheckSection(context: Context, parent: LinearLayout) {
        parent.addView(TextCheckCell(context, themeColors).apply { setTextAndCheck("Notifications", "Enable push notifications", checked = true, divider = true) }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        parent.addView(TextCheckCell(context, themeColors).apply { setTextAndCheck("Dark Mode", checked = false, divider = true) }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        parent.addView(TextCheckCell(context, themeColors).apply { setTextAndCheck("Auto-Download Media", "Download images and videos automatically", checked = true) }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
    }

    private fun addSettingsCellSection(context: Context, parent: LinearLayout) {
        parent.addView(HeaderCell(context, themeColors).apply { setText("Account") }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        parent.addView(TextSettingsCell(context, themeColors).apply { setTextAndValue("Username", "@alice", divider = true); setIcon(MezonIcon.userIcon) }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        parent.addView(TextSettingsCell(context, themeColors).apply { setTextAndValue("Phone", "+1 555-0123", divider = true) }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        parent.addView(TextSettingsCell(context, themeColors).apply { setTextAndValue("Email", "alice@example.com") }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
    }

    private fun addTextDetailSection(context: Context, parent: LinearLayout) {
        parent.addView(TextDetailCell(context, themeColors).apply { setTextAndValue("Clan", "Select a clan...", divider = true) }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        parent.addView(TextDetailCell(context, themeColors).apply { setTextAndValue("Invite Link", "https://mezon.ai/invite/abc123") }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
    }

    private fun addSlideOptionSection(context: Context, parent: LinearLayout) {
        val slideView = SlideOptionView(context, themeColors)
        slideView.setOptions(listOf(SlideOptionView.Option("light", "Light"), SlideOptionView.Option("dark", "Dark"), SlideOptionView.Option("abyss", "Abyss")), selected = 1)
        parent.addView(slideView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 8f, 0f, 8f))
    }

    private fun addAlertsSection(context: Context, parent: LinearLayout) {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val btn1 = ActionButton(context, themeColors).apply { setText("Simple Alert") }
        btn1.setOnClickListener { AlertsCreator.showSimpleAlert(context, "Info", "This is a simple alert dialog.", "OK") }
        row.addView(btn1, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 0f, 0f, 4f, 0f))
        val btn2 = ActionButton(context, themeColors).apply { setText("Confirm"); isOutlined = true }
        btn2.setOnClickListener { AlertsCreator.createConfirmDialog(context, "Delete Message", "This action cannot be undone.", confirmText = "Delete", destructive = true) {}.show() }
        row.addView(btn2, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 4f, 0f, 0f, 0f))
        parent.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 8f, 0f, 8f))
    }

    private fun addPopupMenuSection(context: Context, parent: LinearLayout) {
        val anchor = ActionButton(context, themeColors).apply { setText("Show Popup Menu") }
        anchor.setOnClickListener {
            val popup = PopupMenu(context, themeColors); popup.addItem("Copy", MezonIcon.copyIcon); popup.addItem("Edit", MezonIcon.pencilIcon); popup.addItem("Delete", MezonIcon.trashIcon, destructive = true)
            popup.setOnItemClickListener {}; popup.show(anchor)
        }
        parent.addView(anchor, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 8f, 0f, 8f))
    }

    private fun addSelectPopupSection(context: Context, parent: LinearLayout) {
        val anchor = ActionButton(context, themeColors).apply { setText("Show Select Popup") }
        anchor.setOnClickListener {
            val popup = SelectPopup(context, themeColors)
            popup.setItems(listOf(SelectPopup.SelectItem("text", "Channel Text"), SelectPopup.SelectItem("voice", "Channel Voice"), SelectPopup.SelectItem("forum", "Channel Forum"), SelectPopup.SelectItem("stream", "Channel Streaming")), selected = "text")
            popup.onItemSelected = {}; popup.show(anchor)
        }
        parent.addView(anchor, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 8f, 0f, 8f))
    }

    private fun addInputSection(context: Context, parent: LinearLayout) {
        parent.addView(InputCell(context, themeColors).apply { setLabel("Username"); setHint("Enter your username...") }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 4f, 0f, 8f))
        parent.addView(InputCell(context, themeColors).apply { setLabel("Description"); setHint("Write something..."); setTextarea(true, 200) }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 4f, 0f, 8f))
        parent.addView(InputCell(context, themeColors).apply { setLabel("Email", required = true); setHint("Enter email..."); setError("Please enter a valid email") }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 4f, 0f, 8f))
    }

    private fun addSearchSection(context: Context, parent: LinearLayout) {
        parent.addView(SearchCell(context, themeColors).apply { setPlaceholder("Search channels...") }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 4f, 0f, 8f))
        parent.addView(SearchCell(context, themeColors).apply { setPlaceholder("With cancel"); showCancel = true }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 4f, 0f, 8f))
    }

    private fun addToggleSection(context: Context, parent: LinearLayout) {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val t1 = ToggleView(context, themeColors); val t2 = ToggleView(context, themeColors).apply { setChecked(true, animated = false) }
        row.addView(TextView(context).apply { text = "Off"; setTextColor(themeColors.onSurface); textSize = 14f }, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL, 0f, 0f, 8f, 0f))
        row.addView(t1, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL, 0f, 0f, 24f, 0f))
        row.addView(TextView(context).apply { text = "On"; setTextColor(themeColors.onSurface); textSize = 14f }, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL, 0f, 0f, 8f, 0f))
        row.addView(t2, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL))
        parent.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 8f, 0f, 8f))
    }

    private fun addToastSection(context: Context, parent: LinearLayout) {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Success" to ToastOverlay.ToastType.SUCCESS, "Error" to ToastOverlay.ToastType.ERROR, "Info" to ToastOverlay.ToastType.INFO).forEach { (label, type) ->
            val btn = ActionButton(context, themeColors).apply { setText(label); isOutlined = true }
            btn.setOnClickListener {
                val overlay = ToastOverlay(context, themeColors)
                val rootView = getParentActivity()?.findViewById<ViewGroup>(android.R.id.content) ?: return@setOnClickListener
                overlay.show(rootView, type, "$label!", "This is a $label toast message")
            }
            row.addView(btn, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 0f, 0f, 4f, 0f))
        }
        parent.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 8f, 0f, 8f))
    }

    private fun addBottomSheetSection(context: Context, parent: LinearLayout) {
        val btn = ActionButton(context, themeColors).apply { setText("Show Bottom Sheet") }
        btn.setOnClickListener {
            MezonBottomSheetDialog.create(context, themeColors, title = "Select Channel") { container ->
                listOf("General", "Random", "Announcements", "Off-Topic", "Voice Chat").forEach { name ->
                    container.addView(TextSettingsCell(context, themeColors).apply { setTextAndValue(name, divider = name != "Voice Chat") }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
                }
            }.show()
        }
        parent.addView(btn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 8f, 0f, 8f))
    }

    private fun addScreenStateSection(context: Context, parent: LinearLayout) {
        val container = android.widget.FrameLayout(context).apply { minimumHeight = LayoutHelper.dp(150); setBackgroundColor(themeColors.surfaceVariant) }
        val stateView = ScreenStateView(context, themeColors)
        container.addView(stateView, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
        parent.addView(container, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 8f, 0f, 4f))
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Loading" to 1, "Error" to 2, "Empty" to 3).forEach { (label, mode) ->
            val btn = ActionButton(context, themeColors).apply { setText(label); isOutlined = true }
            btn.setOnClickListener { when (mode) { 1 -> stateView.showLoading(); 2 -> stateView.showError("Something went wrong"); 3 -> stateView.showEmpty("No messages yet") } }
            row.addView(btn, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 0f, 0f, 4f, 0f))
        }
        parent.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 4f, 0f, 8f))
    }

    private fun addImagePickerSection(context: Context, parent: LinearLayout) {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(ImagePickerView(context, themeColors).apply { setRounded(true); setSizeDp(80) }, LayoutHelper.createLinear(80, 80, 0f, Gravity.CENTER_VERTICAL, 0f, 0f, 16f, 0f))
        row.addView(ImagePickerView(context, themeColors).apply { setRounded(false); setSizeDp(80) }, LayoutHelper.createLinear(80, 80, 0f, Gravity.CENTER_VERTICAL))
        parent.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 8f, 0f, 8f))
    }
}
