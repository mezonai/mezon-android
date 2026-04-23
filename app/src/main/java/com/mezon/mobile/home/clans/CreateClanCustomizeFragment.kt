package com.mezon.mobile.home.clans

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.provider.OpenableColumns
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.AlertDialog
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.profile.AccountController
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.cells.ToastOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CreateClanCustomizeFragment : BaseFragment() {

    companion object {
        private const val REQUEST_PICK_IMAGE = 7311
        private const val MAX_LOGO_SIZE_BYTES = 1 * 1024 * 1024
        private val CLAN_NAME_REGEX = Regex("^(?![_\\-\\s])[a-zA-Z0-9\\p{L}\\p{N}_\\-\\s]{1,64}$")
    }

    var selectedTemplate: ClanTemplateSpec? = null

    private lateinit var clansController: ClansController
    private lateinit var accountController: AccountController

    private lateinit var nameInput: EditText
    private lateinit var clearNameButton: ImageView
    private lateinit var errorText: TextView
    private lateinit var createButton: TextView
    private lateinit var createButtonContainer: FrameLayout
    private lateinit var createProgress: ProgressBar
    private lateinit var uploadContainer: FrameLayout
    private lateinit var uploadText: TextView
    private lateinit var avatarImage: ImageView
    private lateinit var plusIcon: ImageView
    private lateinit var cameraIcon: ImageView
    private lateinit var contentContainer: LinearLayout

    private var logoUrl: String = ""
    private var isSubmitting = false
    private var isUploadingImage = false
    private var baseTopPadding = 0
    private var baseHorizontalPadding = 0
    private var baseBottomPadding = 0

    override fun onInject(entryPoint: FragmentEntryPoint) {
        clansController = entryPoint.clansController()
        accountController = entryPoint.accountController()
    }

    override fun createView(context: Context): View {
        val root = FrameLayout(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                CreateClanRnUiTokens.screenGradientColors(themeColors)
            )
        }
        baseTopPadding = LayoutHelper.dp(20)
        baseHorizontalPadding = LayoutHelper.dp(20)
        baseBottomPadding = LayoutHelper.dp(24)
        contentContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(baseHorizontalPadding, baseTopPadding, baseHorizontalPadding, baseBottomPadding)
            clipChildren = false
            clipToPadding = false
        }
        contentContainer.addView(buildHeader(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            bottomMargin = LayoutHelper.dp(40)
        })
        contentContainer.addView(buildAvatarSection(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            topMargin = 0
        })
        contentContainer.addView(buildInputSection(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            topMargin = 0
            bottomMargin = LayoutHelper.dp(10)
        })
        contentContainer.addView(buildGuidelineText(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            bottomMargin = LayoutHelper.dp(6)
        })
        contentContainer.addView(buildCreateButton(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            topMargin = LayoutHelper.dp(10)
        })
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false
            clipToPadding = false
            addView(contentContainer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        root.clipChildren = false
        root.addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        applySafeAreaTop(AndroidUtilities.statusBarHeight)
        fragmentView = root
        updateCreateButtonState()
        return root
    }

    override fun onInsets(insets: Rect) {
        super.onInsets(insets)
        applySafeAreaTop(maxOf(insets.top, AndroidUtilities.statusBarHeight))
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_PICK_IMAGE || resultCode != Activity.RESULT_OK) {
            return
        }
        val uri = data?.data ?: return
        val context = getContext() ?: return
        val size = getFileSize(context, uri)
        if (size > MAX_LOGO_SIZE_BYTES) {
            showErrorToast(getString(R.string.clan_image_too_large, 1))
            return
        }
        avatarImage.setImageURI(uri)
        avatarImage.visibility = View.VISIBLE
        plusIcon.visibility = View.GONE
        cameraIcon.visibility = View.GONE
        uploadText.visibility = View.GONE
        isUploadingImage = true
        updateCreateButtonState()
        accountController.uploadAvatar(uri, context.contentResolver) { success, avatarUrl ->
            isUploadingImage = false
            if (success && avatarUrl.isNotEmpty()) {
                logoUrl = avatarUrl
            } else {
                logoUrl = ""
                showErrorToast(getString(R.string.clan_create_failed))
            }
            updateCreateButtonState()
        }
    }

    private fun buildHeader(context: Context): View {
        val container = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        val backWrap = FrameLayout(context).apply {
            isClickable = true
            isFocusable = true
            clipChildren = false
            val rippleMask = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFFFFFFF.toInt())
            }
            background = RippleDrawable(
                ColorStateList.valueOf(CreateClanRnUiTokens.menuText(themeColors) and 0x1AFFFFFF),
                ColorDrawable(Color.TRANSPARENT),
                rippleMask
            )
            setOnClickListener { finishFragment() }
        }
        backWrap.addView(
            ImageView(context).apply {
                setImageDrawable(MezonIcon.arrowLargeLeftIcon.getDrawable(context, CreateClanRnUiTokens.menuText(themeColors)))
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
            },
            FrameLayout.LayoutParams(LayoutHelper.dp(26), LayoutHelper.dp(26), Gravity.CENTER)
        )
        container.addView(
            backWrap,
            FrameLayout.LayoutParams(LayoutHelper.dp(44), LayoutHelper.dp(44), Gravity.TOP or Gravity.START).apply {
                marginStart = 0
                topMargin = -LayoutHelper.dp(8)
            }
        )
        val title = TextView(context).apply {
            text = getString(R.string.clan_customize_title)
            setTextColor(CreateClanRnUiTokens.menuText(themeColors))
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, 600, false)
            gravity = Gravity.CENTER
        }
        content.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            bottomMargin = LayoutHelper.dp(10)
        })
        val description = TextView(context).apply {
            text = getString(R.string.clan_customize_subtitle)
            setTextColor(CreateClanRnUiTokens.textDisabled(themeColors))
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, 500, false)
            gravity = Gravity.CENTER
        }
        content.addView(description, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        container.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        return container
    }

    private fun buildAvatarSection(context: Context): View {
        val avatarDp = LayoutHelper.dp(120)
        val holder = FrameLayout(context).apply {
            layoutParams = LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.dp(180), Gravity.CENTER_HORIZONTAL)
        }
        val wrapper = FrameLayout(context).apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { openImagePicker() }
        }
        uploadContainer = wrapper
        wrapper.setBackgroundColor(Color.TRANSPARENT)
        val overlay = object : View(context) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = themeColors.tabIconDetail
                style = Paint.Style.STROKE
                strokeWidth = LayoutHelper.dp(1).toFloat()
                pathEffect = DashPathEffect(floatArrayOf(LayoutHelper.dp(4).toFloat(), LayoutHelper.dp(3).toFloat()), 0f)
            }
            override fun onDraw(canvas: Canvas) {
                val inset = paint.strokeWidth / 2f + LayoutHelper.dp(1).toFloat()
                canvas.drawOval(inset, inset, width - inset, height - inset, paint)
            }
        }
        wrapper.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        avatarImage = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        wrapper.addView(avatarImage, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        cameraIcon = ImageView(context).apply {
            setImageDrawable(MezonIcon.cameraIcon.getDrawable(context, Color.WHITE))
            colorFilter = PorterDuffColorFilter(themeColors.createClanCameraGray, PorterDuff.Mode.SRC_IN)
        }
        column.addView(
            cameraIcon,
            LayoutHelper.createLinear(LayoutHelper.dp(20), LayoutHelper.dp(20), 0f, Gravity.CENTER_HORIZONTAL)
        )
        uploadText = TextView(context).apply {
            text = getString(R.string.clan_upload_label)
            setTextColor(CreateClanRnUiTokens.textDisabled(themeColors))
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, 500, false)
            includeFontPadding = false
            gravity = Gravity.CENTER_HORIZONTAL
        }
        column.addView(
            uploadText,
            LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT,
                LayoutHelper.WRAP_CONTENT,
                0f,
                Gravity.CENTER_HORIZONTAL
            ).apply {
                topMargin = LayoutHelper.dp(8)
            }
        )
        plusIcon = ImageView(context).apply {
            setImageDrawable(MezonIcon.circlePlusPrimaryIcon.getDrawable(context, themeColors.createClanDiscordBlurple))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        wrapper.addView(
            column,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        )
        wrapper.addView(
            plusIcon,
            FrameLayout.LayoutParams(LayoutHelper.dp(30), LayoutHelper.dp(30), Gravity.TOP or Gravity.END)
        )
        holder.addView(wrapper, FrameLayout.LayoutParams(avatarDp, avatarDp, Gravity.CENTER))
        return holder
    }

    private fun buildInputSection(context: Context): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val label = TextView(context).apply {
            text = getString(R.string.clan_name_label)
            setTextColor(CreateClanRnUiTokens.menuText(themeColors))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        }
        container.addView(label, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            bottomMargin = LayoutHelper.dp(10)
        })
        val fakeInputBg = GradientDrawable().apply {
            setColor(themeColors.secondaryInputBackground)
            cornerRadius = LayoutHelper.dp(10).toFloat()
            setStroke(LayoutHelper.dp(1), themeColors.border)
        }
        val inputRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = fakeInputBg
            setPadding(LayoutHelper.dp(4), LayoutHelper.dp(10), LayoutHelper.dp(4), LayoutHelper.dp(10))
        }
        nameInput = EditText(context).apply {
            hint = getString(R.string.clan_name_placeholder)
            setHintTextColor(themeColors.textDisabled)
            setTextColor(themeColors.textStrong)
            setBackgroundColor(Color.TRANSPARENT)
            maxLines = 1
            isSingleLine = true
            setPadding(LayoutHelper.dp(10), 0, LayoutHelper.dp(10), 0)
            textSize = 14f
        }
        clearNameButton = ImageView(context).apply {
            visibility = View.GONE
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(MezonIcon.circleXIcon.getDrawable(context, Color.WHITE))
            setOnClickListener {
                nameInput.setText("")
            }
        }
        inputRow.addView(nameInput, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))
        inputRow.addView(
            clearNameButton,
            LayoutHelper.createLinear(18, 18, 0f, Gravity.CENTER_VERTICAL, 0f, 0f, 10f, 0f)
        )
        nameInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val hasText = !nameInput.text.isNullOrEmpty()
                clearNameButton.visibility = if (hasText && !isSubmitting && !isUploadingImage) View.VISIBLE else View.GONE
                val valid = isNameValid()
                errorText.visibility = if (nameInput.text.toString().isBlank() || valid) View.GONE else View.VISIBLE
                updateCreateButtonState()
            }
        })
        container.addView(inputRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            bottomMargin = LayoutHelper.dp(6)
        })
        errorText = TextView(context).apply {
            text = getString(R.string.clan_name_error)
            setTextColor(themeColors.badgeRed)
            textSize = 12f
            visibility = View.GONE
        }
        container.addView(errorText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        return container
    }

    private fun buildGuidelineText(context: Context): View {
        val prefix = getString(R.string.clan_create_community_guide_prefix)
        val linkText = getString(R.string.clan_create_community_guide_link)
        val builder = SpannableStringBuilder()
        val bodyColor = CreateClanRnUiTokens.menuText(themeColors)
        val linkColor = CreateClanRnUiTokens.communityGuidelinesLinkAzureBlue
        builder.append(prefix)
        builder.setSpan(
            ForegroundColorSpan(bodyColor),
            0,
            prefix.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        val linkStart = builder.length
        builder.append(linkText)
        builder.setSpan(
            ForegroundColorSpan(linkColor),
            linkStart,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return TextView(context).apply {
            text = builder
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, 500, false)
            setLineSpacing(LayoutHelper.dp(2).toFloat(), 1f)
        }
    }

    private fun buildCreateButton(context: Context): View {
        val radius = LayoutHelper.dp(10).toFloat()
        val btnFill = GradientDrawable().apply {
            setColor(themeColors.createClanDiscordBlurple)
            cornerRadius = radius
        }
        val rippleMask = GradientDrawable().apply {
            setColor(0xFFFFFFFF.toInt())
            cornerRadius = radius
        }
        createButton = TextView(context).apply {
            isClickable = true
            isFocusable = true
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, 600, false)
            text = getString(R.string.clan_create)
            val p = LayoutHelper.dp(12)
            setPadding(p, p, p, p)
            background = RippleDrawable(
                ColorStateList.valueOf(Color.WHITE and 0x33FFFFFF),
                btnFill,
                rippleMask
            )
            setOnClickListener { onCreateClicked() }
        }
        val progressSize = LayoutHelper.dp(20)
        createProgress = ProgressBar(context).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
            visibility = View.GONE
            isClickable = false
            isFocusable = false
        }
        createButtonContainer = FrameLayout(context).apply {
            addView(
                createButton,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                createProgress,
                FrameLayout.LayoutParams(progressSize, progressSize, Gravity.CENTER)
            )
        }
        return createButtonContainer
    }

    private fun onCreateClicked() {
        if (!isNameValid() || isSubmitting || isUploadingImage) {
            return
        }
        if (clansController.getClanCount() >= CLAN_CREATE_LIMIT) {
            val activity = getParentActivity() ?: return
            AlertDialog.Builder(activity)
                .setTitle(getString(R.string.clan_create_limit_reached_title))
                .setMessage(getString(R.string.clan_create_limit_reached_message))
                .setPositiveButton(getString(R.string.common_ok), null)
                .show()
            return
        }
        val clanName = nameInput.text.toString().trim()
        isSubmitting = true
        updateCreateButtonState()
        fragmentScope.launch(Dispatchers.Main) {
            val isDuplicate = clansController.isDuplicateClanName(clanName)
            if (isDuplicate) {
                isSubmitting = false
                updateCreateButtonState()
                showErrorToast(getString(R.string.clan_duplicate_name_error))
                return@launch
            }
            runCatching {
                clansController.createClan(clanName = clanName, logo = logoUrl, template = selectedTemplate)
            }.onSuccess {
                parentLayout?.findFragment(CreateClanTemplateFragment::class.java)
                    ?.let { parentLayout?.removeFragmentFromStack(it) }
                finishFragment()
            }.onFailure {
                showErrorToast(getString(R.string.clan_create_failed))
            }
            isSubmitting = false
            updateCreateButtonState()
        }
    }

    private fun isNameValid(): Boolean {
        val value = nameInput.text.toString().trim()
        return CLAN_NAME_REGEX.matches(value)
    }

    private fun updateCreateButtonState() {
        if (!::createButton.isInitialized) return
        val enabled = isNameValid() && !isSubmitting && !isUploadingImage
        createButton.isEnabled = enabled
        createButton.alpha = if (enabled) 1f else 0.6f
        if (::createProgress.isInitialized) {
            createProgress.visibility = if (isSubmitting) View.VISIBLE else View.GONE
            createButton.text = if (isSubmitting) "" else getString(R.string.clan_create)
        }
        if (::clearNameButton.isInitialized) {
            val hasText = nameInput.text.isNullOrEmpty().not()
            clearNameButton.visibility = if (hasText && !isSubmitting && !isUploadingImage) View.VISIBLE else View.GONE
        }
        if (::nameInput.isInitialized) {
            nameInput.isEnabled = !isSubmitting
        }
    }

    private fun openImagePicker() {
        val intent = Intent(Intent.ACTION_PICK).apply { type = "image/*" }
        startActivityForResult(intent, REQUEST_PICK_IMAGE)
    }

    private fun getFileSize(context: Context, uri: Uri): Long {
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst()) {
                    cursor.getLong(index)
                } else {
                    0L
                }
            } ?: 0L
        }.getOrDefault(0L)
    }

    private fun showErrorToast(message: String) {
        val context = getContext() ?: return
        val root = getParentActivity()?.findViewById<ViewGroup>(android.R.id.content) ?: return
        ToastOverlay(context, themeColors).show(root, ToastOverlay.ToastType.ERROR, message)
    }

    private fun applySafeAreaTop(topInset: Int) {
        if (!::contentContainer.isInitialized) return
        contentContainer.setPadding(
            baseHorizontalPadding,
            baseTopPadding + topInset,
            baseHorizontalPadding,
            baseBottomPadding
        )
    }
}
