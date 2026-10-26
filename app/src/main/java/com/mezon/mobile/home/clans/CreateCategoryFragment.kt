package com.mezon.mobile.home.clans

import android.content.Context
import android.graphics.Typeface
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
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.ui.cells.ActionBarView
import com.mezon.mobile.ui.cells.InputCell
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.cells.ToastOverlay
import com.mezon.mobile.util.CategoryNameValidator
import com.mezon.mobile.util.NetworkErrorMessages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CreateCategoryFragment : BaseFragment() {

    private lateinit var channelController: ChannelController
    private lateinit var clansController: ClansController

    private var creating = false

    private lateinit var saveButtonText: TextView
    private lateinit var nameCell: InputCell
    private lateinit var loadingOverlay: FrameLayout

    override fun onInject(entryPoint: FragmentEntryPoint) {
        channelController = entryPoint.channelController()
        clansController = entryPoint.clansController()
    }

    override fun createView(context: Context): View {
        val screenPadH = LayoutHelper.dp(16)
        val sectionCaptionToFieldSpacing = LayoutHelper.dp(8)

        saveButtonText = TextView(context).apply {
            text = getString(R.string.category_creator_action_create)
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
            setTitle(getString(R.string.category_creator_screen_title))
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
                        0f,
                    ),
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

        val nameHeading = TextView(context).apply {
            text = getString(R.string.category_creator_name_title)
            setTextColor(themeColors.onSurfaceVariant)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, sectionCaptionToFieldSpacing)
        }

        nameCell = InputCell(context, themeColors).apply {
            setLabel(null, false, false)
            setMaxCharacter(64)
            setHint(getString(R.string.category_creator_name_placeholder))
            onTextChanged = {
                refreshCreateOpacity()
                setError(null)
            }
            editText.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(screenPadH, LayoutHelper.dp(16), screenPadH, LayoutHelper.dp(28))
            addView(nameHeading, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            addView(nameCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            addView(
                Space(context),
                LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, 0, 1f),
            )
        }

        val scroll = ScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
            addView(
                body,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }

        loadingOverlay = FrameLayout(context).apply {
            visibility = View.GONE
            setBackgroundColor(0x40000000)
            isClickable = true
            addView(
                ProgressBar(context).apply { isIndeterminate = true },
                FrameLayout.LayoutParams(LayoutHelper.dp(48), LayoutHelper.dp(48), Gravity.CENTER),
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
        refreshCreateOpacity()

        return rootWithBar
    }

    private fun refreshCreateOpacity() {
        val ok = nameCell.getText().trim().isNotEmpty()
        saveButtonText.alpha = if (ok) 1f else 0.45f
    }

    private fun submitCreate() {
        if (creating) return
        val clanId = clansController.selectedClanId.value
        if (clanId == 0L) {
            showToast(getString(R.string.error_request_failed))
            return
        }
        val name = nameCell.getText().trim()
        if (!CategoryNameValidator.isValid(name)) {
            nameCell.setError(getString(R.string.category_creator_name_error))
            return
        }

        fragmentScope.launch(Dispatchers.Main) {
            creating = true
            loadingOverlay.visibility = View.VISIBLE
            try {
                channelController.createCategory(clanId, name)
                finishFragment()
            } catch (e: Exception) {
                val msg = NetworkErrorMessages.userMessage(requireContext(), e)
                showToast(msg)
            } finally {
                creating = false
                loadingOverlay.visibility = View.GONE
            }
        }
    }

    private fun showToast(text: String) {
        val ctx = getContext() ?: return
        val root = getParentActivity()?.findViewById<ViewGroup>(android.R.id.content) ?: return
        ToastOverlay(ctx, themeColors).show(root, ToastOverlay.ToastType.ERROR, text)
    }
}
