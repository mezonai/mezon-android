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
import kotlinx.coroutines.launch

class CommunitySettingsFragment : BaseFragment() {

    companion object {
        private const val ARG_CLAN_ID = "clanId"
        private const val REQ_PICK_BANNER = 4201
        private const val MAX_BANNER_BYTES = 10 * 1024 * 1024

        fun newInstance(clanId: Long): CommunitySettingsFragment =
            CommunitySettingsFragment().apply {
                arguments = Bundle().apply { putLong(ARG_CLAN_ID, clanId) }
            }
    }

    private var clanId = 0L
    private lateinit var controller: CommunitySettingsController
    private lateinit var permissionPolicy: PermissionPolicy

    private lateinit var rootContainer: FrameLayout
    private var currentContentView: View? = null

    override fun onInject(entryPoint: FragmentEntryPoint) {
        controller = entryPoint.communitySettingsController()
        permissionPolicy = entryPoint.permissionPolicy()
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
                    if (id == -1) finishFragment()
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

    private fun renderMode(state: CommunityUiState) {
        val ctx = getContext() ?: return
        val newContent: View = when (state.mode) {
            CommunityScreenMode.LOADING -> buildLoadingView(ctx)
            CommunityScreenMode.LANDING,
            CommunityScreenMode.ENABLE_FORM -> buildFormView(ctx, state, enableMode = true)
            CommunityScreenMode.ENABLED_EDITOR -> buildFormView(ctx, state, enableMode = false)
        }
        rootContainer.removeAllViews()
        rootContainer.addView(
            newContent,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        currentContentView = newContent
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

        // Banner tím với ảnh community
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

        // Phần text + button bên dưới
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

    private fun buildFormView(ctx: Context, state: CommunityUiState, enableMode: Boolean): View {
        val outerLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val scrollContent = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(
            scrollContent,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        outerLayout.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        // Ảnh community.png tím ở đầu trang, bo góc
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
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, LayoutHelper.dp(180f))
        )
        val heroPad = LayoutHelper.dp(16f)
        scrollContent.addView(heroFrame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, heroPad.toFloat(), 16f, heroPad.toFloat(), 16f))

        // Các field phía dưới có padding ngang
        val fieldsLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = LayoutHelper.dp(16)
            setPadding(pad, 0, pad, pad)
        }
        scrollContent.addView(fieldsLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        // Banner section
        val bannerSection = buildBannerSection(ctx, state)
        fieldsLayout.addView(bannerSection, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 16f))

        if (state.fieldErrors.banner) {
            fieldsLayout.addView(buildErrorText(ctx, getString(R.string.community_settings_error_banner)),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 8f))
        }

        // Description field
        val descInput = InputCell(ctx, themeColors).apply {
            setLabel(getString(R.string.community_settings_description_label), required = true)
            setTextarea(true, maxChars = 100)
            editText.hint = getString(R.string.community_settings_description_hint)
            editText.setText(state.draft.description)
            if (state.fieldErrors.description) setError(getString(R.string.community_settings_error_required))
            onTextChanged = { controller.onDescriptionChanged(it) }
            editText.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) controller.saveDescriptionOnBlur(clanId)
            }
        }
        fieldsLayout.addView(descInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 16f))

        // About field
        val aboutInput = InputCell(ctx, themeColors).apply {
            setLabel(getString(R.string.community_settings_about_label), required = true)
            setTextarea(true, maxChars = 300)
            editText.hint = getString(R.string.community_settings_about_hint)
            editText.setText(state.draft.about)
            if (state.fieldErrors.about) setError(getString(R.string.community_settings_error_required))
            onTextChanged = { controller.onAboutChanged(it) }
            editText.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) controller.saveAboutOnBlur(clanId)
            }
        }
        fieldsLayout.addView(aboutInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 16f))

        // Vanity URL field
        val vanityHint = TextView(ctx).apply {
            text = getString(R.string.community_settings_vanity_hint)
            textSize = 12f
            setTextColor(themeColors.onSurfaceVariant)
            setPadding(0, 0, 0, LayoutHelper.dp(4f))
        }
        fieldsLayout.addView(vanityHint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 4f))

        val vanityInput = InputCell(ctx, themeColors).apply {
            setLabel(getString(R.string.community_settings_vanity_label), required = true)
            setMaxCharacter(50)
            setShowCharacterCount(true)
            editText.hint = "my-awesome-community"
            editText.setText(state.draft.shortUrl)
            if (state.fieldErrors.shortUrl) setError(getString(R.string.community_settings_error_required))
            onTextChanged = { controller.onShortUrlChanged(it) }
            editText.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) controller.saveShortUrlOnBlur(clanId)
            }
        }
        fieldsLayout.addView(vanityInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 24f))

        // Bottom buttons
        val bottomBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = LayoutHelper.dp(16)
            setPadding(pad, 0, pad, pad)
        }

        if (enableMode) {
            bottomBar.addView(
                buildPrimaryButton(ctx, getString(R.string.community_settings_enable_and_save)) {
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
                },
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52)
            )
        } else {
            if (state.showSaveBar) {
                bottomBar.addView(
                    buildPrimaryButton(ctx, getString(R.string.common_save)) {
                        controller.saveChanges(clanId) { ok, msg ->
                            if (ok) {
                                MezonToast.show(this@CommunitySettingsFragment, ToastOverlay.ToastType.SUCCESS, getString(R.string.community_settings_saved_success))
                            } else if (msg != null) {
                                MezonToast.show(this@CommunitySettingsFragment, ToastOverlay.ToastType.ERROR, msg)
                            } else {
                                MezonToast.show(this@CommunitySettingsFragment, ToastOverlay.ToastType.ERROR, getString(R.string.community_settings_error_fill_required))
                            }
                        }
                    },
                    LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 8f)
                )
                bottomBar.addView(
                    buildSecondaryButton(ctx, getString(R.string.common_reset)) {
                        controller.resetDraft()
                    },
                    LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 8f)
                )
            }
            bottomBar.addView(
                buildDestructiveButton(ctx, getString(R.string.community_settings_disable)) {
                    if (!state.showSaveBar) {
                        controller.disableCommunity(clanId) { ok, msg ->
                            if (!ok && msg != null) {
                                MezonToast.show(this@CommunitySettingsFragment, ToastOverlay.ToastType.ERROR, msg)
                            }
                        }
                    }
                },
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48)
            )
        }

        outerLayout.addView(bottomBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        return outerLayout
    }

    private fun buildBannerSection(ctx: Context, state: CommunityUiState): View {
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        container.addView(
            TextView(ctx).apply {
                text = getString(R.string.community_settings_banner_label)
                textSize = 14f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(themeColors.onSurfaceVariant)
                setPadding(0, 0, 0, LayoutHelper.dp(8f))
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)
        )

        val bannerHeight = LayoutHelper.dp(140f)
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
        }

        val bannerImageView = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            id = View.generateViewId()
        }
        bannerFrame.addView(
            bannerImageView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        val previewUrl = state.draft.bannerPreviewUrl
        if (!previewUrl.isNullOrBlank()) {
            MezonImageLoader.getInstance(ctx).load(previewUrl, 0, bannerHeight, onSuccess = { bitmap ->
                bannerImageView.setImageBitmap(bitmap)
            })
        } else {
            val cameraIcon = ImageView(ctx).apply {
                setImageDrawable(MezonIcon.cameraIcon.getDrawable(ctx, themeColors.onSurfaceVariant))
                scaleType = ImageView.ScaleType.CENTER
            }
            bannerFrame.addView(
                cameraIcon,
                FrameLayout.LayoutParams(LayoutHelper.dp(32f), LayoutHelper.dp(32f), Gravity.CENTER)
            )
        }

        if (!previewUrl.isNullOrBlank()) {
            val removeBtn = ImageView(ctx).apply {
                setImageDrawable(MezonIcon.circleXIcon.getDrawable(ctx, themeColors.error))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                elevation = LayoutHelper.dpf(4f)
                isClickable = true
                setOnClickListener { controller.removeBanner(clanId) }
            }
            bannerFrame.addView(
                removeBtn,
                FrameLayout.LayoutParams(LayoutHelper.dp(28f), LayoutHelper.dp(28f), Gravity.TOP or Gravity.END).apply {
                    topMargin = LayoutHelper.dp(8f)
                    rightMargin = LayoutHelper.dp(8f)
                }
            )
        }

        container.addView(
            bannerFrame,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 140)
        )

        container.addView(
            TextView(ctx).apply {
                text = getString(R.string.community_settings_banner_hint)
                textSize = 12f
                setTextColor(themeColors.onSurfaceVariant)
                setPadding(0, LayoutHelper.dp(4f), 0, 0)
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)
        )

        return container
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
        try {
            val inputStream = ctx.contentResolver.openInputStream(uri) ?: return
            val bytes = inputStream.readBytes()
            inputStream.close()
            if (bytes.size > MAX_BANNER_BYTES) {
                MezonToast.show(this, ToastOverlay.ToastType.ERROR, getString(R.string.community_settings_error_banner_size))
                return
            }
            val mimeType = ctx.contentResolver.getType(uri) ?: "image/jpeg"
            if (!mimeType.startsWith("image/")) {
                MezonToast.show(this, ToastOverlay.ToastType.ERROR, getString(R.string.community_settings_error_banner_type))
                return
            }
            val ext = when (mimeType) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                else -> "jpg"
            }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            controller.onBannerPicked(bytes, mimeType, ext, uri.toString())
            if (bitmap != null) {
                val currentView = currentContentView
                if (currentView != null) {
                    val bannerImg = currentView.findViewWithTag<ImageView?>("communityBannerImg")
                    bannerImg?.setImageBitmap(bitmap)
                }
            }
        } catch (_: Exception) {
            MezonToast.show(this, ToastOverlay.ToastType.ERROR, getString(R.string.community_settings_error_banner))
        }
    }
}
