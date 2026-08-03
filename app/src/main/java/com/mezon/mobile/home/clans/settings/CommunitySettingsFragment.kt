package com.mezon.mobile.home.clans.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.home.clans.PermissionPolicy
import com.mezon.mobile.ui.MezonToast
import com.mezon.mobile.ui.cells.ActionBarView
import com.mezon.mobile.ui.cells.InputCell
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.cells.ToastOverlay
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CommunitySettingsFragment : BaseFragment() {

    companion object {
        private const val ARG_CLAN_ID = "clanId"
        private const val REQ_PICK_BANNER = 4201
        private const val MAX_BANNER_BYTES = 10 * 1024 * 1024
        private const val MAX_BANNER_DECODE_EDGE = 2048

        fun newInstance(clanId: Long): CommunitySettingsFragment =
            CommunitySettingsFragment().apply {
                arguments = Bundle().apply { putLong(ARG_CLAN_ID, clanId) }
            }
    }

    private var clanId = 0L
    private lateinit var controller: CommunitySettingsController
    private lateinit var permissionPolicy: PermissionPolicy
    private lateinit var ioDispatcher: CoroutineDispatcher
    private lateinit var mainDispatcher: CoroutineDispatcher

    private lateinit var rootContainer: FrameLayout
    private var currentContentView: View? = null
    private var displayedMode: CommunityScreenMode? = null
    private var loadingView: View? = null
    private var landingView: View? = null
    private var formHolder: FormHolder? = null

    override fun onInject(entryPoint: FragmentEntryPoint) {
        controller = entryPoint.communitySettingsController()
        permissionPolicy = entryPoint.permissionPolicy()
        ioDispatcher = entryPoint.ioDispatcher()
        mainDispatcher = entryPoint.mainDispatcher()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        clanId = arguments?.getLong(ARG_CLAN_ID) ?: 0L
        if (clanId == 0L) return false
        val perm = permissionPolicy.clanSettingsPermissionState(clanId)
        if (!perm.hasManageClanPermission) {
            return false
        }
        controller.load(clanId)
        return true
    }

    override fun createView(context: Context): View {
        actionBar = ActionBarView(context, themeColors).apply {
            setTitle(getString(R.string.community_settings_title))
            setBackButtonImage(R.drawable.ic_close_24)
            setBackButtonContentDescription(getString(R.string.common_close))
            setCenterTitle(true)
            setMenuOnItemClick(object : ActionBarView.ActionBarMenuOnItemClick() {
                override fun onItemClick(id: Int) {
                    if (id == -1) onToolbarBackPressed()
                }
            })
        }
        checkNotNull(actionBar).backButton.apply {
            scaleType = ImageView.ScaleType.CENTER
            layoutParams = (layoutParams as FrameLayout.LayoutParams).apply {
                width = LayoutHelper.dp(48f)
                height = LayoutHelper.dp(48f)
            }
        }

        val outerRoot = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.background)
        }
        outerRoot.addView(actionBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        rootContainer = FrameLayout(context)
        outerRoot.addView(rootContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        fragmentScope.launch(entryPoint().mainDispatcher()) {
            controller.uiState.collect { state ->
                renderMode(state)
            }
        }

        fragmentView = outerRoot
        return outerRoot
    }

    private fun onToolbarBackPressed() {
        when (controller.uiState.value.mode) {
            CommunityScreenMode.ENABLE_FORM -> controller.onCancelEnableForm()
            else -> finishFragment()
        }
    }

    private fun renderMode(state: CommunityUiState) {
        val ctx = getContext() ?: return
        val mode = state.mode
        if (mode == displayedMode) {
            when (mode) {
                CommunityScreenMode.ENABLE_FORM,
                CommunityScreenMode.ENABLED_EDITOR -> formHolder?.patch(state, force = false)
                else -> Unit
            }
            return
        }
        val previousMode = displayedMode
        displayedMode = mode
        when (mode) {
            CommunityScreenMode.LOADING -> showLoading(ctx)
            CommunityScreenMode.LANDING -> showLanding(ctx, force = previousMode != CommunityScreenMode.LANDING)
            CommunityScreenMode.ENABLE_FORM,
            CommunityScreenMode.ENABLED_EDITOR -> showForm(ctx, state)
        }
    }

    private fun showLoading(ctx: Context) {
        formHolder = null
        attachContent(loadingView ?: buildLoadingView(ctx).also { loadingView = it })
    }

    /** Hero + Enable CTA — never the enable form. */
    private fun showLanding(ctx: Context, force: Boolean) {
        formHolder = null
        val landing = landingView ?: buildLandingView(ctx).also { landingView = it }
        attachContent(landing, force)
    }

    private fun showForm(ctx: Context, state: CommunityUiState) {
        val holder = ensureFormHolder(ctx)
        attachContent(holder.root)
        holder.patch(state, force = true)
    }

    private fun attachContent(view: View, force: Boolean = false) {
        if (!force && rootContainer.childCount == 1 && rootContainer.getChildAt(0) === view) {
            currentContentView = view
            return
        }
        rootContainer.removeAllViews()
        rootContainer.addView(
            view,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        currentContentView = view
    }

    private fun ensureFormHolder(ctx: Context): FormHolder {
        return formHolder ?: createFormHolder(ctx).also { formHolder = it }
    }

    private fun buildLoadingView(ctx: Context): View {
        return FrameLayout(ctx).apply {
            addView(
                ProgressBar(ctx).apply {
                    isIndeterminate = true
                    indeterminateTintList = android.content.res.ColorStateList.valueOf(themeColors.primary)
                },
                FrameLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER)
            )
        }
    }

    private fun buildLandingView(ctx: Context): View {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.background)
        }

        val bannerFrame = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                colors = intArrayOf(0xFF5A62F4.toInt(), 0xFF7B5EA7.toInt())
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.TL_BR
            }
        }
        val communityImg = ImageView(ctx).apply {
            setImageResource(R.drawable.community)
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.92f
        }
        bannerFrame.addView(
            communityImg,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, LayoutHelper.dp(200f))
        )
        root.addView(
            bannerFrame,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)
        )

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(LayoutHelper.dp(24f), LayoutHelper.dp(28f), LayoutHelper.dp(24f), LayoutHelper.dp(24f))
        }

        content.addView(
            TextView(ctx).apply {
                text = getString(R.string.community_settings_landing_title)
                textSize = 22f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(themeColors.colorText)
                gravity = Gravity.CENTER
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_HORIZONTAL, 0f, 0f, 0f, 10f)
        )

        content.addView(
            TextView(ctx).apply {
                text = getString(R.string.community_settings_landing_subtitle)
                textSize = 14f
                setTextColor(themeColors.onSurfaceVariant)
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.4f)
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_HORIZONTAL, 0f, 0f, 0f, 32f)
        )

        content.addView(
            buildPrimaryButton(ctx, getString(R.string.community_settings_enable_button)) {
                controller.onTapEnableCommunity()
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52)
        )

        root.addView(content, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        return root
    }

    private fun createFormHolder(ctx: Context): FormHolder {
        val outerLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val scrollContent = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(
            scrollContent,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        outerLayout.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        val heroFrame = FrameLayout(ctx).apply {
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, LayoutHelper.dpf(16f))
                }
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = LayoutHelper.dpf(16f)
                colors = intArrayOf(0xFF5A62F4.toInt(), 0xFF7B5EA7.toInt())
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.TL_BR
            }
        }
        heroFrame.addView(
            ImageView(ctx).apply {
                setImageResource(R.drawable.community)
                scaleType = ImageView.ScaleType.CENTER_CROP
                alpha = 0.92f
            },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, LayoutHelper.dp(180f)),
        )
        val heroPad = LayoutHelper.dp(16f)
        scrollContent.addView(
            heroFrame,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, heroPad.toFloat(), 16f, heroPad.toFloat(), 16f),
        )

        val fieldsLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = LayoutHelper.dp(16)
            setPadding(pad, 0, pad, pad)
        }
        scrollContent.addView(fieldsLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val bannerImageView = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        val bannerCameraIcon = ImageView(ctx).apply {
            setImageDrawable(MezonIcon.cameraIcon.getDrawable(ctx, themeColors.onSurfaceVariant))
            scaleType = ImageView.ScaleType.CENTER
        }
        val bannerRemoveBtn = ImageView(ctx).apply {
            setImageDrawable(MezonIcon.circleXIcon.getDrawable(ctx, themeColors.error))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            elevation = LayoutHelper.dpf(4f)
            isClickable = true
            setOnClickListener { controller.removeBanner(clanId) }
        }
        val bannerFrame = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = LayoutHelper.dpf(8f)
                setColor(themeColors.surfaceVariant)
                setStroke(LayoutHelper.dp(1), themeColors.borderDim)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { openBannerPicker() }
            addView(bannerImageView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(bannerCameraIcon, FrameLayout.LayoutParams(LayoutHelper.dp(32f), LayoutHelper.dp(32f), Gravity.CENTER))
            addView(
                bannerRemoveBtn,
                FrameLayout.LayoutParams(LayoutHelper.dp(28f), LayoutHelper.dp(28f), Gravity.TOP or Gravity.END).apply {
                    topMargin = LayoutHelper.dp(8f)
                    rightMargin = LayoutHelper.dp(8f)
                },
            )
        }
        val bannerErrorTv = buildErrorText(ctx, getString(R.string.community_settings_error_banner)).apply {
            visibility = View.GONE
        }

        fieldsLayout.addView(
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    TextView(ctx).apply {
                        text = getString(R.string.community_settings_banner_label)
                        textSize = 14f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        setTextColor(themeColors.onSurfaceVariant)
                        setPadding(0, 0, 0, LayoutHelper.dp(8f))
                    },
                    LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT),
                )
                addView(bannerFrame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 140))
                addView(bannerErrorTv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 8f))
                addView(
                    TextView(ctx).apply {
                        text = getString(R.string.community_settings_banner_hint)
                        textSize = 12f
                        setTextColor(themeColors.onSurfaceVariant)
                        setPadding(0, LayoutHelper.dp(4f), 0, 0)
                    },
                    LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT),
                )
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 16f),
        )

        val descInput = InputCell(ctx, themeColors).apply {
            setLabel(getString(R.string.community_settings_description_label), required = true)
            setTextarea(true, maxChars = 300)
            editText.hint = getString(R.string.community_settings_description_hint)
            onTextChanged = { controller.onDescriptionChanged(it) }
            editText.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) controller.saveDescriptionOnBlur(clanId)
            }
        }
        fieldsLayout.addView(descInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 16f))

        val aboutInput = InputCell(ctx, themeColors).apply {
            setLabel(getString(R.string.community_settings_about_label), required = true)
            setTextarea(true, maxChars = 100)
            editText.hint = getString(R.string.community_settings_about_hint)
            onTextChanged = { controller.onAboutChanged(it) }
            editText.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) controller.saveAboutOnBlur(clanId)
            }
        }
        fieldsLayout.addView(aboutInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 16f))

        val vanityInput = InputCell(ctx, themeColors).apply {
            setLabel(getString(R.string.community_settings_vanity_label), required = true)
            setDescription(getString(R.string.community_settings_vanity_hint))
            setMaxCharacter(50)
            setShowCharacterCount(true)
            editText.hint = "my-awesome-community"
            onTextChanged = { controller.onShortUrlChanged(it) }
            editText.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) controller.saveShortUrlOnBlur(clanId)
            }
        }
        fieldsLayout.addView(vanityInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 24f))

        val bottomBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = LayoutHelper.dp(16)
            setPadding(pad, 0, pad, pad)
        }
        val enableBtn = buildPrimaryButton(ctx, getString(R.string.community_settings_enable_and_save)) {
            if (!controller.uiState.value.server.isSaving) {
                controller.confirmEnableAndSave(clanId) { ok, msg ->
                    if (ok) {
                        MezonToast.show(this@CommunitySettingsFragment, ToastOverlay.ToastType.SUCCESS, getString(R.string.community_settings_enabled_success))
                    } else if (msg != null) {
                        MezonToast.show(this@CommunitySettingsFragment, ToastOverlay.ToastType.ERROR, msg)
                    } else {
                        MezonToast.show(this@CommunitySettingsFragment, ToastOverlay.ToastType.ERROR, getString(R.string.community_settings_error_fill_required))
                    }
                }
            }
        }
        val saveBtn = buildPrimaryButton(ctx, getString(R.string.common_save)) {
            controller.saveChanges(clanId) { ok, msg ->
                if (ok) {
                    MezonToast.show(this@CommunitySettingsFragment, ToastOverlay.ToastType.SUCCESS, getString(R.string.community_settings_saved_success))
                } else if (msg != null) {
                    MezonToast.show(this@CommunitySettingsFragment, ToastOverlay.ToastType.ERROR, msg)
                } else {
                    MezonToast.show(this@CommunitySettingsFragment, ToastOverlay.ToastType.ERROR, getString(R.string.community_settings_error_fill_required))
                }
            }
        }
        lateinit var holderRef: FormHolder
        val resetBtn = buildSecondaryButton(ctx, getString(R.string.common_reset)) {
            controller.resetDraft()
            holderRef.patch(controller.uiState.value, force = true)
        }
        val disableBtn = buildDestructiveButton(ctx, getString(R.string.community_settings_disable)) {
            if (!controller.uiState.value.showSaveBar) {
                controller.disableCommunity(clanId) { ok, msg ->
                    if (!ok && msg != null) {
                        MezonToast.show(this@CommunitySettingsFragment, ToastOverlay.ToastType.ERROR, msg)
                    }
                }
            }
        }
        bottomBar.addView(enableBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52))
        bottomBar.addView(saveBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 8f))
        bottomBar.addView(resetBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 8f))
        bottomBar.addView(disableBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48))
        outerLayout.addView(bottomBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        holderRef = FormHolder(
            root = outerLayout,
            descInput = descInput,
            aboutInput = aboutInput,
            vanityInput = vanityInput,
            bannerImageView = bannerImageView,
            bannerCameraIcon = bannerCameraIcon,
            bannerRemoveBtn = bannerRemoveBtn,
            bannerErrorTv = bannerErrorTv,
            enableBtn = enableBtn,
            saveBtn = saveBtn,
            resetBtn = resetBtn,
            disableBtn = disableBtn,
        )
        return holderRef
    }

    private inner class FormHolder(
        val root: View,
        val descInput: InputCell,
        val aboutInput: InputCell,
        val vanityInput: InputCell,
        val bannerImageView: ImageView,
        val bannerCameraIcon: ImageView,
        val bannerRemoveBtn: ImageView,
        val bannerErrorTv: TextView,
        val enableBtn: TextView,
        val saveBtn: TextView,
        val resetBtn: TextView,
        val disableBtn: TextView,
    ) {
        private var lastBannerUrl: String? = null

        fun patch(state: CommunityUiState, force: Boolean) {
            syncInput(descInput, state.draft.description, force)
            syncInput(aboutInput, state.draft.about, force)
            syncInput(vanityInput, state.draft.shortUrl, force)

            val required = getString(R.string.community_settings_error_required)
            descInput.setError(if (state.fieldErrors.description) required else null)
            aboutInput.setError(if (state.fieldErrors.about) required else null)
            vanityInput.setError(if (state.fieldErrors.shortUrl) required else null)
            bannerErrorTv.visibility = if (state.fieldErrors.banner) View.VISIBLE else View.GONE

            patchBanner(state)
            patchBottomBar(state)
        }

        private fun syncInput(cell: InputCell, value: String, force: Boolean) {
            val edit = cell.editText
            if (!force && edit.isFocused) return
            if (edit.text.toString() != value) {
                edit.setText(value)
            }
        }

        fun applyLocalBannerPreview(drawable: android.graphics.drawable.Drawable, previewUrl: String) {
            lastBannerUrl = previewUrl
            bannerImageView.setImageDrawable(drawable)
            if (drawable is android.graphics.drawable.AnimatedImageDrawable) {
                drawable.start()
            }
            bannerCameraIcon.visibility = View.GONE
            bannerRemoveBtn.visibility = View.VISIBLE
        }

        private fun patchBanner(state: CommunityUiState) {
            val previewUrl = state.draft.bannerPreviewUrl
            if (previewUrl == lastBannerUrl && bannerImageView.drawable != null) return
            lastBannerUrl = previewUrl
            val hasBanner = !previewUrl.isNullOrBlank()
            bannerCameraIcon.visibility = if (hasBanner) View.GONE else View.VISIBLE
            bannerRemoveBtn.visibility = if (hasBanner) View.VISIBLE else View.GONE
            if (hasBanner) {
                val ctx = bannerImageView.context
                val h = LayoutHelper.dp(140f)
                val w = com.mezon.mobile.core.AndroidUtilities.displaySize.x
                MezonImageLoader.getInstance(ctx).loadDrawable(previewUrl, w, h, onSuccess = { drawable ->
                    if (lastBannerUrl == previewUrl) {
                        bannerImageView.setImageDrawable(drawable)
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P && drawable is android.graphics.drawable.AnimatedImageDrawable) drawable.start()
                    }
                }, cacheAnimated = true)
            } else {
                bannerImageView.setImageDrawable(null)
            }
        }

        private fun patchBottomBar(state: CommunityUiState) {
            val isEnableForm = state.mode == CommunityScreenMode.ENABLE_FORM
            if (isEnableForm) {
                enableBtn.visibility = View.VISIBLE
                saveBtn.visibility = View.GONE
                resetBtn.visibility = View.GONE
                disableBtn.visibility = View.GONE
                return
            }
            enableBtn.visibility = View.GONE
            val showSave = state.showSaveBar
            saveBtn.visibility = if (showSave) View.VISIBLE else View.GONE
            resetBtn.visibility = if (showSave) View.VISIBLE else View.GONE
            disableBtn.visibility = View.VISIBLE
        }
    }

    private fun buildPrimaryButton(ctx: Context, label: String, onClick: () -> Unit): TextView {
        return TextView(ctx).apply {
            text = label
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = LayoutHelper.dpf(8f)
                setColor(themeColors.primary)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun buildSecondaryButton(ctx: Context, label: String, onClick: () -> Unit): TextView {
        return TextView(ctx).apply {
            text = label
            textSize = 16f
            setTextColor(themeColors.primary)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = LayoutHelper.dpf(8f)
                setColor(Color.TRANSPARENT)
                setStroke(LayoutHelper.dp(1), themeColors.primary)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun buildDestructiveButton(ctx: Context, label: String, onClick: () -> Unit): TextView {
        return TextView(ctx).apply {
            text = label
            textSize = 16f
            setTextColor(themeColors.error)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = LayoutHelper.dpf(8f)
                setColor(Color.TRANSPARENT)
                setStroke(LayoutHelper.dp(1), themeColors.error)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun buildErrorText(ctx: Context, msg: String): TextView {
        return TextView(ctx).apply {
            text = msg
            textSize = 12f
            setTextColor(themeColors.error)
        }
    }

    private fun openBannerPicker() {
        val pick = Intent(Intent.ACTION_PICK).apply { type = "image/*" }
        val getContent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        val chooser = Intent.createChooser(getContent, getString(R.string.community_settings_pick_banner)).apply {
            putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(pick))
        }
        startActivityForResult(chooser, REQ_PICK_BANNER)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK || requestCode != REQ_PICK_BANNER) return
        val clip = data?.clipData
        val uri = clip?.getItemAt(0)?.uri ?: data?.data ?: return
        handleBannerUri(uri)
    }

    private fun handleBannerUri(uri: Uri) {
        val ctx = getContext() ?: return
        val previewUri = uri.toString()
        fragmentScope.launch {
            val result = withContext(ioDispatcher) { readBannerFromUri(ctx, uri) }
            withContext(mainDispatcher) {
                if (isFinished) return@withContext
                when (result) {
                    BannerLoadResult.TooLarge ->
                        MezonToast.show(this@CommunitySettingsFragment, ToastOverlay.ToastType.ERROR, getString(R.string.community_settings_error_banner_size))
                    BannerLoadResult.InvalidType ->
                        MezonToast.show(this@CommunitySettingsFragment, ToastOverlay.ToastType.ERROR, getString(R.string.community_settings_error_banner_type))
                    BannerLoadResult.Failed ->
                        MezonToast.show(this@CommunitySettingsFragment, ToastOverlay.ToastType.ERROR, getString(R.string.community_settings_error_banner))
                    is BannerLoadResult.Ok -> {
                        controller.onBannerPicked(result.bytes, result.mimeType, result.ext, previewUri)
                        result.drawable?.let { d ->
                            formHolder?.applyLocalBannerPreview(d, previewUri)
                        }
                    }
                }
            }
        }
    }

    private fun readBannerFromUri(ctx: Context, uri: Uri): BannerLoadResult {
        return try {
            val mimeType = ctx.contentResolver.getType(uri) ?: "image/jpeg"
            if (!mimeType.startsWith("image/")) {
                return BannerLoadResult.InvalidType
            }
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return BannerLoadResult.Failed
            if (bytes.size > MAX_BANNER_BYTES) {
                return BannerLoadResult.TooLarge
            }
            val ext = when (mimeType) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/gif" -> "gif"
                else -> "jpg"
            }
            
            var drawable: android.graphics.drawable.Drawable? = null
            val isAnimated = mimeType == "image/gif" || mimeType == "image/webp"
            if (isAnimated && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                try {
                    val source = android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))
                    drawable = android.graphics.ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
                        val maxW = com.mezon.mobile.core.AndroidUtilities.displaySize.x
                        val maxH = LayoutHelper.dp(140f)
                        if (maxW > 0 && maxH > 0) {
                            val scale = Math.max(maxW.toFloat() / info.size.width, maxH.toFloat() / info.size.height)
                            if (scale < 1f) {
                                decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }

            if (drawable == null) {
                val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
                val maxEdgePx = maxOf(boundsOpts.outWidth, boundsOpts.outHeight)
                var sample = 1
                while (maxEdgePx / sample > MAX_BANNER_DECODE_EDGE) {
                    sample *= 2
                }
                val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
                if (bitmap != null) {
                    drawable = android.graphics.drawable.BitmapDrawable(ctx.resources, bitmap)
                }
            }
            BannerLoadResult.Ok(bytes, mimeType, ext, drawable)
        } catch (_: Exception) {
            BannerLoadResult.Failed
        }
    }

    private sealed interface BannerLoadResult {
        data object TooLarge : BannerLoadResult
        data object InvalidType : BannerLoadResult
        data object Failed : BannerLoadResult
        data class Ok(
            val bytes: ByteArray,
            val mimeType: String,
            val ext: String,
            val drawable: android.graphics.drawable.Drawable?,
        ) : BannerLoadResult
    }
}
