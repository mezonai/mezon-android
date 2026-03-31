package com.mezon.mobile.home.profile

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.ImageView
import android.widget.Toast
import com.mezon.mobile.R
import com.mezon.mobile.core.AlertsCreator
import com.mezon.mobile.core.AvatarDrawable
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.ui.cells.ActionButton
import com.mezon.mobile.ui.cells.AvatarView
import com.mezon.mobile.ui.cells.InputCell
import com.mezon.mobile.ui.cells.ToastOverlay
import com.mezon.mobile.ui.cells.MezonBottomSheetDialog
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.util.ColorUtilities
import android.view.ViewGroup

class EditProfileFragment : BaseFragment() {

    companion object {
        private const val REQUEST_CODE_PICK_AVATAR = 2001
        private const val REQUEST_CODE_PICK_DM_LOGO = 2002
        private const val MAX_AVATAR_SIZE_BYTES = 10L * 1024 * 1024 // 10MB
        private const val MAX_DM_LOGO_SIZE_BYTES = 1L * 1024 * 1024 // 1MB
    }

    private lateinit var accountController: AccountController
    private lateinit var userController: UserController

    var onSaved: (() -> Unit)? = null

    private lateinit var displayNameCell: InputCell
    private lateinit var aboutMeCell: InputCell
    private lateinit var loadingView: View
    private lateinit var bannerView: View
    private lateinit var avatarView: AvatarView
    private lateinit var nameView: TextView
    private lateinit var usernameSubView: TextView

    private var currentAvatarUrl: String = ""

    override fun onInject(entryPoint: FragmentEntryPoint) {
        accountController = entryPoint.accountController()
        userController = entryPoint.userController()
    }

    private var currentDmLogoUrl: String = ""
    private lateinit var dmLogoView: AvatarView
    private lateinit var saveButtonView: View
    private var isUploadingAvatar = false
    private var isUploadingDmLogo = false

    override fun createView(context: Context): View {
        val info = accountController.accountInfo.value
        val userId = info.userId.takeIf { it != 0L } ?: userController.userId
        val displayName = info.displayName.ifEmpty { userController.displayName }
        val username = info.username.ifEmpty { userController.username }
        currentAvatarUrl = info.avatarUrl.ifEmpty { userController.avatarUrl }
        currentDmLogoUrl = info.logo

        actionBar = createActionBar(context).apply {
            setBackButtonImage(R.drawable.ic_close_icon)
            setTitle(getString(R.string.edit_profile_title))
            setCenterTitle(true)
            val saveItem = createMenu().addItem(1, getString(R.string.edit_profile_save))
            saveButtonView = TextView(context).apply {
                text = getString(R.string.edit_profile_save)
                setTextColor(themeColors.primary)
                textSize = 16f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_VERTICAL
                setPadding(LayoutHelper.dp(16), 0, LayoutHelper.dp(16), 0)
            }
            saveItem.addView(saveButtonView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER_VERTICAL, 0f, 3f, 0f, 0f))
            getBackButtonView()?.apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
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
        val rootLinear = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.background)
        }
        rootLinear.addView(actionBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val tabBg = GradientDrawable().apply {
            setColor(if (themeColors.resolvedMode == com.mezon.mobile.ui.theme.ThemeMode.LIGHT) 0xFFF0F0F0.toInt() else 0xFF1C1D23.toInt())
            cornerRadius = LayoutHelper.dpf(20f)
        }
        val tabContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = tabBg
            val pad = LayoutHelper.dp(4)
            setPadding(pad, pad, pad, pad)
        }
        val tabPersonal = ActionButton(context, themeColors).apply {
            setText(getString(R.string.edit_profile_tab_personal))
        }
        val tabClan = TextView(context).apply {
            text = getString(R.string.edit_profile_tab_clan)
            setTextColor(themeColors.onSurfaceVariant)
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        tabContainer.addView(tabPersonal, LayoutHelper.createLinear(0, 32, 1f))
        tabContainer.addView(tabClan, LayoutHelper.createLinear(0, 32, 1f))
        
        val tabWrapper = FrameLayout(context).apply {
            setPadding(LayoutHelper.dp(16), LayoutHelper.dp(8), LayoutHelper.dp(16), LayoutHelper.dp(16))
        }
        tabWrapper.addView(tabContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 40))
        rootLinear.addView(tabWrapper, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        val bannerContainer = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
        }

        val bannerColor = AvatarDrawable.getColorForId(userId)
        bannerView = View(context).apply {
            setBackgroundColor(bannerColor)
        }
        bannerContainer.addView(bannerView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 150))
        if (currentAvatarUrl.isNotEmpty()) {
            loadBannerFromAvatar(currentAvatarUrl)
        }

        avatarView = AvatarView(context).apply {
            setSizeDp(100)
            setInfo(userId, displayName)
            if (currentAvatarUrl.isNotEmpty()) setImageUrl(currentAvatarUrl)
        }
        val avatarBorderColor = if (themeColors.resolvedMode == com.mezon.mobile.ui.theme.ThemeMode.LIGHT) 0xFFFFFFFF.toInt() else 0xFF0D0D18.toInt()
        val avatarBorderBg = GradientDrawable().apply {
            setColor(0x00000000)
            cornerRadius = LayoutHelper.dpf(999f)
            setStroke(LayoutHelper.dp(5), avatarBorderColor)
        }
        val avatarWrapper = FrameLayout(context).apply {
            background = avatarBorderBg
            setOnClickListener { showAvatarBottomSheet() }
        }
        avatarWrapper.addView(avatarView, LayoutHelper.createFrame(100, 100, Gravity.CENTER))
        val cameraIconBg = GradientDrawable().apply {
            setColor(themeColors.outlineVariant)
            cornerRadius = LayoutHelper.dpf(999f)
            setStroke(LayoutHelper.dp(2), themeColors.background)
        }
        val cameraIcon = View(context).apply {
            background = cameraIconBg
        }
        avatarWrapper.addView(cameraIcon, LayoutHelper.createFrame(20, 20, Gravity.BOTTOM or Gravity.END, 0f, 0f, 8f, 8f))

        bannerContainer.addView(avatarWrapper, LayoutHelper.createFrame(
            110, 110, Gravity.START or Gravity.BOTTOM,
            16f, 0f, 0f, -40f
        ))
        content.addView(bannerContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 150))
        content.addView(View(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 60))

        val infoCardBg = GradientDrawable().apply {
            setColor(themeColors.getColor(ThemeColors.key_sheetItemBackground))
            cornerRadius = LayoutHelper.dpf(12f)
        }
        val inputGroupCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = infoCardBg
            val pad = LayoutHelper.dp(16)
            setPadding(pad, pad, pad, pad)
        }

        nameView = TextView(context).apply {
            text = displayName.ifEmpty { username }
            setTextColor(themeColors.onSurface)
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        inputGroupCard.addView(nameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        usernameSubView = TextView(context).apply {
            text = username
            setTextColor(themeColors.onSurfaceVariant)
            textSize = 15f
        }
        inputGroupCard.addView(usernameSubView, LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0, 0f, 2f, 0f, 16f
        ))

        displayNameCell = InputCell(context, themeColors).apply {
            setLabel(getString(R.string.edit_profile_display_name_label)) 
            setHint(getString(R.string.edit_profile_display_name_hint))
            setText(displayName)
            setMaxCharacter(32)
            setCellBackgroundColor(themeColors.surface)
            setCellStrokeColor(0x00000000)
            onTextChanged = { text -> 
                nameView.text = text.ifEmpty { username } 
            }
        }
        inputGroupCard.addView(displayNameCell, LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0, 0f, 0f, 0f, 16f
        ))

        aboutMeCell = InputCell(context, themeColors).apply {
            setLabel(getString(R.string.edit_profile_about_me))
            setHint(getString(R.string.edit_profile_about_me_hint))
            setTextarea(true, 128)
            setText(info.aboutMe)
            setCellBackgroundColor(themeColors.surface)
            setCellStrokeColor(0x00000000)
            editText.gravity = Gravity.TOP or Gravity.START
        }
        inputGroupCard.addView(aboutMeCell, LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT
        ))

        content.addView(inputGroupCard, LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0, 16f, 0f, 16f, 16f
        ))
        val logoCardBg = GradientDrawable().apply {
            setColor(themeColors.getColor(ThemeColors.key_sheetItemBackground))
            cornerRadius = LayoutHelper.dpf(12f)
        }
        val dmGroupCard = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = logoCardBg
            gravity = Gravity.CENTER_VERTICAL
            val pad = LayoutHelper.dp(16)
            setPadding(pad, pad, pad, pad)
        }
        val dmTitle = TextView(context).apply {
            text = getString(R.string.edit_profile_dm_icon_label)
            setTextColor(themeColors.onSurface)
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
        }
        dmGroupCard.addView(dmTitle, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL))

        dmLogoView = AvatarView(context).apply {
            setSizeDp(50)
            setInfo(userId, "D")
            if (currentDmLogoUrl.isNotEmpty()) setImageUrl(currentDmLogoUrl)
            setOnClickListener { openDmLogoPicker() }
        }
        val dmWrapper = FrameLayout(context)
        dmWrapper.addView(dmLogoView, LayoutHelper.createFrame(50, 50))
        
        val removeIconBg = GradientDrawable().apply {
            setColor(themeColors.error)
            cornerRadius = LayoutHelper.dpf(999f)
        }
        val removeIcon = ImageView(context).apply {
            background = removeIconBg
            setImageResource(R.drawable.ic_close_icon) // generic close
            setColorFilter(0xFFFFFFFF.toInt())
            setPadding(LayoutHelper.dp(2), LayoutHelper.dp(2), LayoutHelper.dp(2), LayoutHelper.dp(2))
            setOnClickListener { removeDmLogo() }
        }
        dmWrapper.addView(removeIcon, LayoutHelper.createFrame(16, 16, Gravity.TOP or Gravity.END))
        dmGroupCard.addView(dmWrapper, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))

        content.addView(dmGroupCard, LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0, 16f, 0f, 16f, 24f
        ))
        val scrollView = ScrollView(context).apply {
            isFillViewport = true
            clipToPadding = false
            clipChildren = false
        }
        scrollView.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        rootLinear.addView(scrollView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        val rootFrame = FrameLayout(context)
        rootFrame.addView(rootLinear, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        loadingView = View(context).apply {
            setBackgroundColor(0x88000000.toInt())
            visibility = View.GONE
            isClickable = true
        }
        rootFrame.addView(loadingView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        if (accountController.accountInfo.value.username.isEmpty()) accountController.loadAccount()

        return rootFrame
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()

        observe(NotificationCenter.accountInfoLoaded) { _, _, _ ->
            if (fragmentView == null) return@observe
            val updatedInfo = accountController.accountInfo.value
            if (displayNameCell.getText().isEmpty()) displayNameCell.setText(updatedInfo.displayName)
            if (aboutMeCell.getText().isEmpty()) aboutMeCell.setText(updatedInfo.aboutMe)
            if (currentAvatarUrl.isEmpty() && updatedInfo.avatarUrl.isNotEmpty()) {
                currentAvatarUrl = updatedInfo.avatarUrl
                avatarView.setImageUrl(currentAvatarUrl)
            }
            if (currentDmLogoUrl.isEmpty() && updatedInfo.logo.isNotEmpty()) {
                currentDmLogoUrl = updatedInfo.logo
                dmLogoView.setImageUrl(currentDmLogoUrl)
            }
            val displayName = updatedInfo.displayName.ifEmpty { updatedInfo.username }
            nameView.text = displayName
            usernameSubView.text = updatedInfo.username
        }
        observe(NotificationCenter.themeChanged) { _, _, _ ->
            fragmentView?.setBackgroundColor(themeColors.background)
        }

        return true
    }

    private fun showAvatarBottomSheet() {
        val context = getContext() ?: return
        var dialog: com.google.android.material.bottomsheet.BottomSheetDialog? = null
        dialog = MezonBottomSheetDialog.create(
            context, themeColors, title = null, scrollable = false
        ) { container ->
            val changeButton = TextView(context).apply {
                text = getString(R.string.edit_profile_change_avatar)
                setTextColor(themeColors.onSurface)
                textSize = 16f
                val pad = LayoutHelper.dp(14)
                setPadding(0, pad, 0, pad)
                setOnClickListener {
                    dialog?.dismiss()
                    openImagePicker()
                }
            }
            container.addView(changeButton, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT
            ))

            val removeButton = TextView(context).apply {
                text = getString(R.string.edit_profile_remove_avatar)
                setTextColor(themeColors.error)
                textSize = 16f
                val pad = LayoutHelper.dp(14)
                setPadding(0, pad, 0, pad)
                setOnClickListener {
                    dialog?.dismiss()
                    removeAvatar()
                }
            }
            container.addView(removeButton, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT
            ))
        }
        dialog.show()
    }

    private fun openImagePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        getParentActivity()?.startActivityForResult(
            Intent.createChooser(intent, getString(R.string.edit_profile_change_avatar)),
            REQUEST_CODE_PICK_AVATAR
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            if (requestCode == REQUEST_CODE_PICK_AVATAR) {
                handleAvatarPicked(uri)
            } else if (requestCode == REQUEST_CODE_PICK_DM_LOGO) {
                handleDmLogoPicked(uri)
            }
        }
    }

    private fun openDmLogoPicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        getParentActivity()?.startActivityForResult(
            Intent.createChooser(intent, getString(R.string.edit_profile_dm_icon_label)),
            REQUEST_CODE_PICK_DM_LOGO
        )
    }

    private fun handleDmLogoPicked(uri: Uri) {
        val context = getContext() ?: return
        try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val sizeIndex = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (sizeIndex != -1 && it.getLong(sizeIndex) > MAX_DM_LOGO_SIZE_BYTES) {
                        val overlay = ToastOverlay(context, themeColors)
                        getParentActivity()?.findViewById<ViewGroup>(android.R.id.content)?.let { root ->
                            overlay.show(root, ToastOverlay.ToastType.ERROR, getString(R.string.edit_profile_dm_logo_too_large))
                        }
                        return
                    }
                }
            }
        } catch (_: Exception) {}

        loadingView.visibility = View.VISIBLE
        isUploadingDmLogo = true
        updateSaveButtonState()
        com.mezon.mobile.core.AndroidUtilities.hideKeyboard(loadingView)
        accountController.uploadAvatar(uri, context.contentResolver) { success, cdnUrl ->
            isUploadingDmLogo = false
            updateSaveButtonState()
            loadingView.visibility = View.GONE
            if (success) {
                currentDmLogoUrl = cdnUrl
                dmLogoView.setImageUrl(cdnUrl)
            } else {
                val overlay = ToastOverlay(context, themeColors)
                getParentActivity()?.findViewById<ViewGroup>(android.R.id.content)?.let { root ->
                    overlay.show(root, ToastOverlay.ToastType.ERROR, getString(R.string.edit_profile_save_error))
                }
            }
        }
    }

    private fun removeDmLogo() {
        currentDmLogoUrl = com.mezon.mobile.BuildConfig.MEZON_LOGO_URL
        dmLogoView.setImageUrl(currentDmLogoUrl)
    }

    private fun updateSaveButtonState() {
        if (!::saveButtonView.isInitialized) return
        saveButtonView.alpha = if (isUploadingAvatar || isUploadingDmLogo) 0.6f else 1.0f
    }

    private fun handleAvatarPicked(uri: Uri) {
        val context = getContext() ?: return
        try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val sizeIndex = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (sizeIndex != -1) {
                        val size = it.getLong(sizeIndex)
                        if (size > MAX_AVATAR_SIZE_BYTES) {
                            val overlay = ToastOverlay(context, themeColors)
                            val rootView = getParentActivity()?.findViewById<ViewGroup>(android.R.id.content)
                            if (rootView != null) {
                                overlay.show(rootView, ToastOverlay.ToastType.ERROR, getString(R.string.edit_profile_avatar_too_large))
                            }
                            return
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            val maxPx = LayoutHelper.dp(100)
            opts.inSampleSize = calculateInSampleSize(opts.outWidth, opts.outHeight, maxPx, maxPx)
            opts.inJustDecodeBounds = false
            opts.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            var bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            if (bitmap != null) {
                var rotation = 0f
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                            val ei = android.media.ExifInterface(input)
                            val orientation = ei.getAttributeInt(
                                android.media.ExifInterface.TAG_ORIENTATION,
                                android.media.ExifInterface.ORIENTATION_NORMAL
                            )
                            rotation = when (orientation) {
                                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                                else -> 0f
                            }
                        }
                    }
                } catch (e: Exception) {}

                if (rotation != 0f) {
                    val matrix = android.graphics.Matrix()
                    matrix.postRotate(rotation)
                    val rotatedBitmap = android.graphics.Bitmap.createBitmap(
                        bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                    )
                    if (rotatedBitmap != bitmap) {
                        bitmap.recycle()
                        bitmap = rotatedBitmap
                    }
                }
                
                avatarView.setPhoto(bitmap)
                updateBannerColor(bitmap)
            }
        } catch (_: Exception) {}
        isUploadingAvatar = true
        updateSaveButtonState()
        loadingView.visibility = View.VISIBLE
        com.mezon.mobile.core.AndroidUtilities.hideKeyboard(loadingView)
        accountController.uploadAvatar(uri, context.contentResolver) { success, cdnUrl ->
            isUploadingAvatar = false
            updateSaveButtonState()
            loadingView.visibility = View.GONE
            if (success) {
                currentAvatarUrl = cdnUrl
                avatarView.setImageUrl(cdnUrl)
                loadBannerFromAvatar(cdnUrl)
            } else {
                val overlay = ToastOverlay(context, themeColors)
                val rootView = getParentActivity()?.findViewById<ViewGroup>(android.R.id.content)
                if (rootView != null) {
                    overlay.show(rootView, ToastOverlay.ToastType.ERROR, getString(R.string.edit_profile_save_error))
                }
            }
        }
    }

    private fun removeAvatar() {
        currentAvatarUrl = com.mezon.mobile.BuildConfig.MEZON_LOGO_URL
        avatarView.setImageUrl(currentAvatarUrl)
        loadBannerFromAvatar(currentAvatarUrl)
    }

    private fun handleSave() {
        if (isUploadingAvatar || isUploadingDmLogo) return
        val displayName = displayNameCell.getText().trim()
        val aboutMe = aboutMeCell.getText().trim()

        loadingView.visibility = View.VISIBLE

        accountController.updateProfile(
            displayName,
            currentAvatarUrl,
            aboutMe,
            currentDmLogoUrl
        ) { success, errorMsg ->
            loadingView.visibility = View.GONE
            if (success) {
                userController.updateFromAccount(accountController.accountInfo.value)
                finishFragment()
                onSaved?.invoke()
            } else {
                AlertsCreator.showSimpleAlert(
                    requireContext(),
                    getString(R.string.common_error),
                    errorMsg.ifEmpty { getString(R.string.edit_profile_save_error) }
                )
            }
        }
    }

    private fun calculateInSampleSize(w: Int, h: Int, reqW: Int, reqH: Int): Int {
        var sample = 1
        if (reqW <= 0 || reqH <= 0) return 1
        while (w / (sample * 2) >= reqW && h / (sample * 2) >= reqH) sample *= 2
        return sample
    }

    private fun loadBannerFromAvatar(url: String?) {
        if (url.isNullOrEmpty()) return
        MezonImageLoader.getInstance(requireContext()).load(url, 100, 100,
            onSuccess = { bmp ->
                updateBannerColor(bmp)
            }
        )
    }

    private fun updateBannerColor(bitmap: android.graphics.Bitmap) {
        val dominant = ColorUtilities.getDominantColor(bitmap)
        bannerView.setBackgroundColor(dominant)
    }

    override fun onResume() {
        super.onResume()
        if (currentAvatarUrl.isNotEmpty()) loadBannerFromAvatar(currentAvatarUrl)
    }
}
