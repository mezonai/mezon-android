package com.mezon.mobile.home.clans.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.os.Build
import android.net.Uri
import android.content.DialogInterface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.CheckBox
import android.widget.TextView
import android.view.inputmethod.EditorInfo
import androidx.core.widget.CompoundButtonCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.BuildConfig
import com.mezon.mobile.R
import com.mezon.mobile.core.AlertDialog
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.ClanMember
import com.mezon.mobile.home.chat.EmojiController
import com.mezon.mobile.home.chat.EmojiItem
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.home.clans.ClansController
import com.mezon.mobile.home.clans.CreateClanRnUiTokens
import com.mezon.mobile.home.clans.RoleController
import com.mezon.mobile.home.profile.UserController
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.session.SessionManager
import com.mezon.mobile.ui.MezonToast
import com.mezon.mobile.ui.cells.InputCell
import com.mezon.mobile.ui.cells.ActionBarView
import com.mezon.mobile.ui.cells.AvatarView
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.cells.ToastOverlay
import com.mezon.mobile.home.UserClanController
import com.mezon.mobile.util.isClanEmojiNameValid
import com.mezon.mobile.util.getEmojiUrl
import com.mezon.mobile.util.AttachmentUploader
import com.mezon.mobile.util.CLAN_EMOJI_NAME_MAX_LENGTH
import com.mezon.mobile.util.CLAN_EMOJI_NAME_MIN_LENGTH
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ThreadLocalRandom

class EmojiSettingFragment : BaseFragment() {

    companion object {
        private const val ARG_CLAN_ID = "clanId"
        private const val REQUEST_PICK_EMOJI = 4021
        private const val MAX_CLAN_EMOJI_SLOTS = 250
        private const val MAX_UPLOAD_BYTES = 256 * 1024
        private const val EMOJI_ROW_IMAGE_WEIGHT = 0.22f
        private const val EMOJI_ROW_NAME_WEIGHT = 0.5f
        private const val EMOJI_ROW_UPLOADER_WEIGHT = 0.28f

        fun newInstance(clanId: Long): EmojiSettingFragment =
            EmojiSettingFragment().apply {
                arguments = Bundle().apply { putLong(ARG_CLAN_ID, clanId) }
            }

        fun newEmojiNumericId(): Long =
            ThreadLocalRandom.current().nextLong(10_000_000_000_000L, Long.MAX_VALUE / 4)
    }

    private var clanId = 0L

    private lateinit var emojiController: EmojiController
    private lateinit var api: MezonApi
    private lateinit var sessionManager: SessionManager
    private lateinit var userClanController: UserClanController
    private lateinit var roleController: RoleController
    private lateinit var clansController: ClansController
    private lateinit var userController: UserController
    private lateinit var ioDispatcher: CoroutineDispatcher
    private lateinit var mainDispatcher: CoroutineDispatcher

    private var permState: ClanSettingsPermissionState? = null
    private var emojiListBindEpoch = 0

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: EmojiSettingAdapter
    private var blockingOverlay: FrameLayout? = null

    override fun onInject(entryPoint: FragmentEntryPoint) {
        emojiController = entryPoint.emojiController()
        api = entryPoint.mezonApi()
        sessionManager = entryPoint.sessionManager()
        userClanController = entryPoint.userClanController()
        roleController = entryPoint.roleController()
        clansController = entryPoint.clansController()
        userController = entryPoint.userController()
        ioDispatcher = entryPoint.ioDispatcher()
        mainDispatcher = entryPoint.mainDispatcher()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        clanId = arguments?.getLong(ARG_CLAN_ID) ?: 0L
        if (clanId == 0L) return false

        observe(NotificationCenter.emojisNeedReload) { _, _, _ ->
            if (isPaused) return@observe
            reloadListUi()
        }
        observe(NotificationCenter.clanEmojiCropExportReady) { _, _, args ->
            val cid = args.getOrNull(0) as? Long ?: return@observe
            if (cid != clanId) return@observe
            val path = args.getOrNull(1) as? String ?: return@observe
            AndroidUtilities.runOnUIThread {
                if (isFinished) return@runOnUIThread
                whenFullyVisible(Runnable {
                    if (!isFinished) showUploadPreviewDialog(File(path), isGif = false)
                })
            }
        }
        observe(NotificationCenter.clanMembersDidLoad) { _, _, args ->
            if (isPaused) return@observe
            val id = args.firstOrNull() as? Long ?: return@observe
            if (id == clanId) refreshPermissionsAndList()
        }
        observe(NotificationCenter.clanRolesDidLoad) { _, _, args ->
            if (isPaused) return@observe
            val id = args.firstOrNull() as? Long ?: return@observe
            if (id == clanId) refreshPermissionsAndList()
        }

        if (clanId != 0L) {
            roleController.loadRolesForClan(clanId, force = false)
            userClanController.loadClanMembers(clanId)
        }
        emojiController.loadEmojis()
        return true
    }

    override fun onBecomeFullyVisible() {
        super.onBecomeFullyVisible()
        if (clanId != 0L) {
            roleController.loadRolesForClan(clanId, force = false)
            userClanController.loadClanMembers(clanId)
        }
        refreshPermissionsAndList()
        emojiController.loadEmojis()
    }

    override fun createView(context: Context): View {
        val root = FrameLayout(context).apply {
            setBackgroundColor(themeColors.background)
        }

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        actionBar = ActionBarView(context, themeColors).apply {
            setTitle(getString(R.string.menu_clan_emoji))
            setBackButtonImage(R.drawable.ic_close_24)
            setBackButtonContentDescription(getString(R.string.common_close))
            setCenterTitle(true)
            setMenuOnItemClick(object : ActionBarView.ActionBarMenuOnItemClick() {
                override fun onItemClick(id: Int) {
                    if (id == -1) finishFragment()
                }
            })
        }
        actionBar!!.backButton.apply {
            scaleType = ImageView.ScaleType.CENTER
            layoutParams = (layoutParams as android.widget.FrameLayout.LayoutParams).apply {
                width = LayoutHelper.dp(48f)
                height = LayoutHelper.dp(48f)
            }
        }
        column.addView(actionBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        recycler = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        adapter = EmojiSettingAdapter()
        recycler.adapter = adapter

        column.addView(recycler, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        root.addView(column, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))

        blockingOverlay = FrameLayout(context).apply {
            visibility = View.GONE
            isClickable = true
            setBackgroundColor(0x88000000.toInt())
            val pb = ProgressBar(context).apply {
                isIndeterminate = true
                indeterminateTintList = android.content.res.ColorStateList.valueOf(themeColors.colorText)
            }
            addView(
                pb,
                FrameLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER),
            )
        }
        root.addView(
            blockingOverlay,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )

        fragmentView = root
        refreshPermissionsAndList()
        return root
    }

    private fun setBlocking(active: Boolean) {
        blockingOverlay?.visibility = if (active) View.VISIBLE else View.GONE
    }

    private fun refreshPermissionsAndList() {
        val members = userClanController.getClanMembers(clanId)
        val roles = roleController.getRoles(clanId)
        val clan = clansController.clans.value.firstOrNull { it.clanId == clanId }
        permState = ClanSettingsPermissionState.evaluateForClanSettings(
            userController,
            clanId,
            members,
            roles,
            clan?.creatorId ?: 0L,
        )
        emojiListBindEpoch++
        reloadListUi()
    }

    private fun reloadListUi() {
        if (!::adapter.isInitialized) return
        adapter.submit(buildClanEmojiRows())
    }

    private fun buildClanEmojiRows(): List<EmojiItem> {
        val cid = clanId.toString()
        if (cid.isBlank() || cid == "0") return emptyList()
        return synchronized(emojiController) {
            emojiController.emojis.filter { it.clanId == cid }
        }
    }

    private fun openImagePicker() {
        val current = buildClanEmojiRows().size
        if (current >= MAX_CLAN_EMOJI_SLOTS) {
            MezonToast.show(this, ToastOverlay.ToastType.ERROR, getString(R.string.clan_emoji_limit_slots))
            return
        }
        val pick = Intent(Intent.ACTION_PICK).apply { type = "image/*" }
        val getContent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        val chooser = Intent.createChooser(getContent, getString(R.string.clan_emoji_upload_button)).apply {
            putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(pick))
        }
        startActivityForResult(chooser, REQUEST_PICK_EMOJI)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_EMOJI || resultCode != Activity.RESULT_OK) return
        val uri = data?.clipData?.getItemAt(0)?.uri ?: data?.data ?: return
        val ctx = getContext() ?: return
        val mime = ctx.contentResolver.getType(uri).orEmpty()
        val isGif = mime.equals("image/gif", ignoreCase = true) ||
            uri.toString().contains(".gif", ignoreCase = true)

        fragmentScope.launch(ioDispatcher) {
            val size = runCatching {
                ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
            }.getOrDefault(0L)
            if (isGif && size > MAX_UPLOAD_BYTES) {
                withContext(mainDispatcher) {
                    MezonToast.show(this@EmojiSettingFragment, ToastOverlay.ToastType.ERROR, getString(R.string.clan_emoji_error_size_limit))
                }
                return@launch
            }
            withContext(mainDispatcher) {
                if (isGif) {
                    showUploadPreviewFromUri(uri, isGif = true)
                } else {
                    presentFragment(ClanEmojiCropFragment.newInstance(clanId, uri.toString()))
                }
            }
        }
    }

    private fun showUploadPreviewFromUri(uri: Uri, isGif: Boolean) {
        fragmentScope.launch(ioDispatcher) {
            val ctx = getContext() ?: return@launch
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { stream -> stream.readBytes() }
            if (bytes == null) {
                withContext(mainDispatcher) {
                    MezonToast.show(this@EmojiSettingFragment, ToastOverlay.ToastType.ERROR, getString(R.string.clan_emoji_upload_failed))
                }
                return@launch
            }
            if (bytes.size > MAX_UPLOAD_BYTES) {
                withContext(mainDispatcher) {
                    MezonToast.show(this@EmojiSettingFragment, ToastOverlay.ToastType.ERROR, getString(R.string.clan_emoji_error_size_limit))
                }
                return@launch
            }
            val tmp = File(ctx.cacheDir, "clan_emoji_pick_${System.currentTimeMillis()}.${if (isGif) "gif" else "bin"}")
            try {
                FileOutputStream(tmp).use { stream -> stream.write(bytes) }
                withContext(mainDispatcher) {
                    showUploadPreviewDialog(tmp, isGif = isGif)
                }
            } catch (_: Exception) {
                tmp.delete()
                withContext(mainDispatcher) {
                    MezonToast.show(this@EmojiSettingFragment, ToastOverlay.ToastType.ERROR, getString(R.string.clan_emoji_upload_failed))
                }
            }
        }
    }

    private fun showUploadPreviewDialog(file: File, isGif: Boolean) {
        val ctx = getContext() ?: run {
            file.delete()
            return
        }
        val pad = LayoutHelper.dp(16f)
        val scroll = ScrollView(ctx)
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val emojiSection = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        emojiSection.addView(
            TextView(ctx).apply {
                text = getString(R.string.clan_emoji_section_label)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_HORIZONTAL
                setTextColor(themeColors.onSurfaceVariant)
            },
            LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT,
                0f,
                Gravity.CENTER_HORIZONTAL,
                0f,
                0f,
                0f,
                0f,
            ),
        )

        val previewSide = LayoutHelper.dp(96f)
        val previewIv = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val previewFrame = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LayoutHelper.WRAP_CONTENT,
                LayoutHelper.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = LayoutHelper.dp(8f)
            }
        }
        previewFrame.addView(
            previewIv,
            FrameLayout.LayoutParams(previewSide, previewSide, Gravity.CENTER),
        )
        emojiSection.addView(previewFrame)

        body.addView(
            emojiSection,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT),
        )

        val nameCell = InputCell(ctx, themeColors).apply {
            setLabel(getString(R.string.clan_emoji_name_label))
            setHint(getString(R.string.clan_emoji_name_hint))
            setText("emoji_${System.currentTimeMillis()}")
            setMaxCharacter(CLAN_EMOJI_NAME_MAX_LENGTH)
            onTextChanged = { setError(null) }
        }
        body.addView(
            nameCell,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                topMargin = LayoutHelper.dp(14f)
            },
        )

        val saleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val saleCheck = CheckBox(ctx).apply {
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            CompoundButtonCompat.setButtonTintList(this, ColorStateList.valueOf(themeColors.onSurface))
        }
        saleRow.addView(
            saleCheck,
            LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT),
        )
        val saleLabel = TextView(ctx).apply {
            text = getString(R.string.clan_emoji_for_sale)
            textSize = 14f
            setTextColor(themeColors.colorText)
            setPadding(LayoutHelper.dp(6f), 0, 0, 0)
            setOnClickListener { saleCheck.toggle() }
        }
        saleRow.addView(
            saleLabel,
            LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT),
        )
        body.addView(
            saleRow,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START).apply {
                topMargin = LayoutHelper.dp(14f)
            },
        )

        scroll.addView(body)

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.clan_emoji_preview_dialog_title))
            .setView(scroll, LayoutHelper.WRAP_CONTENT)
            .setDismissDialogByButtons(false)
            .setNegativeButton(getString(R.string.common_cancel)) { d, _ ->
                file.delete()
                d.dismiss()
            }
            .setPositiveButton(getString(R.string.clan_emoji_confirm_upload), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setOnClickListener {
                val inner = nameCell.getText().trim()
                if (inner.length !in CLAN_EMOJI_NAME_MIN_LENGTH..CLAN_EMOJI_NAME_MAX_LENGTH || !isClanEmojiNameValid(inner)) {
                    nameCell.setError(
                        getString(R.string.clan_emoji_validate_name, CLAN_EMOJI_NAME_MIN_LENGTH, CLAN_EMOJI_NAME_MAX_LENGTH),
                    )
                    return@setOnClickListener
                }
                nameCell.setError(null)
                dialog.dismiss()
                runUploadFlow(file, isGif, inner, saleCheck.isChecked)
            }
        }
        dialog.show()
        fragmentScope.launch {
            val bmp = withContext(ioDispatcher) {
                decodePreviewBitmap(file, previewSide, isGif)
            } ?: return@launch
            withContext(mainDispatcher) {
                if (!isFinished && previewIv.isAttachedToWindow) {
                    previewIv.setImageBitmap(bmp)
                } else {
                    bmp.recycle()
                }
            }
        }
    }

    private fun runUploadFlow(file: File, isGif: Boolean, innerName: String, isForSale: Boolean) {
        fragmentScope.launch(ioDispatcher) {
            try {
                setBlockingUi(true)
                val primaryId = newEmojiNumericId()
                val bytes = file.readBytes()
                file.delete()

                val mime = when {
                    isGif -> "image/gif"
                    file.name.endsWith(".webp", true) -> "image/webp"
                    else -> "image/jpeg"
                }
                val ext = when {
                    isGif -> "gif"
                    mime == "image/webp" -> "webp"
                    else -> "jpg"
                }

                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                val w = opts.outWidth.takeIf { it > 0 } ?: 128
                val h = opts.outHeight.takeIf { it > 0 } ?: 128

                val primaryFilename = "emojis/$primaryId.$ext"
                val primaryUrl = uploadBytes(bytes, primaryFilename, mime, w, h)

                val emojiRecordId = if (isForSale) {
                    val thumbBmp = decodeSaleThumbBytes(bytes, w, h, isGif)
                    val scaled = if (thumbBmp.width == 35 && thumbBmp.height == 35) {
                        thumbBmp
                    } else {
                        Bitmap.createScaledBitmap(thumbBmp, 35, 35, true).also {
                            if (it !== thumbBmp) thumbBmp.recycle()
                        }
                    }
                    val thumbId = newEmojiNumericId()
                    val thumbBytes = encodeTinyJpeg(scaled)
                    scaled.recycle()
                    val thumbName = "emojis/$thumbId.jpg"
                    uploadBytes(thumbBytes, thumbName, "image/jpeg", 35, 35)
                    thumbId
                } else {
                    primaryId
                }

                val shortname = ":$innerName:"
                sessionManager.withAutoRefresh { session ->
                    api.createClanEmoji(
                        session.apiUrl,
                        session.token,
                        clanId,
                        emojiRecordId,
                        primaryUrl,
                        shortname,
                        "Custom",
                        isForSale,
                    )
                }
                withContext(mainDispatcher) {
                    setBlockingUi(false)
                    emojiController.invalidateEmojiCacheAndReload()
                }
            } catch (e: Exception) {
                withContext(mainDispatcher) {
                    setBlockingUi(false)
                    MezonToast.show(this@EmojiSettingFragment, ToastOverlay.ToastType.ERROR, getString(R.string.clan_emoji_upload_failed))
                }
            }
        }
    }

    private suspend fun uploadBytes(
        bytes: ByteArray,
        filename: String,
        mime: String,
        width: Int,
        height: Int,
    ): String {
        return sessionManager.withAutoRefresh { session ->
            AttachmentUploader.uploadAttachmentBytes(
                api,
                session.apiUrl,
                session.token,
                filename,
                mime,
                bytes,
                width.coerceAtLeast(1),
                height.coerceAtLeast(1),
                BuildConfig.MEZON_BASE_IMG_URL,
            ).cdnUrl
        }
    }

    private fun decodePreviewBitmap(file: File, maxPx: Int, isGif: Boolean): Bitmap? {
        if (isGif && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return try {
                val source = ImageDecoder.createSource(file)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.setTargetSize(maxPx, maxPx)
                }
            } catch (_: Exception) {
                null
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        if (srcW <= 0 || srcH <= 0) return null
        var sample = 1
        while (srcW / (sample * 2) >= maxPx && srcH / (sample * 2) >= maxPx) {
            sample *= 2
        }
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }

    private fun decodeSaleThumbBytes(bytes: ByteArray, srcW: Int, srcH: Int, isGif: Boolean): Bitmap {
        val targetPx = 35
        if (isGif && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
            return ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.setTargetSize(targetPx, targetPx)
            }
        }
        var sample = 1
        while (srcW / (sample * 2) >= targetPx && srcH / (sample * 2) >= targetPx) {
            sample *= 2
        }
        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: throw IllegalStateException("Decode thumbnail failed")
    }

    private fun encodeTinyJpeg(bitmap: Bitmap): ByteArray {
        var quality = 35
        while (quality >= 10) {
            val bout = java.io.ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, bout)
            val arr = bout.toByteArray()
            if (arr.isNotEmpty()) return arr
            quality -= 5
        }
        val bout = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 10, bout)
        return bout.toByteArray()
    }

    private suspend fun setBlockingUi(active: Boolean) {
        withContext(mainDispatcher) { setBlocking(active) }
    }

    private fun canEditOrDelete(item: EmojiItem): Boolean {
        val perm = permState ?: return false
        val uid = userController.userId
        if (uid == 0L) return false
        if (perm.hasAdminPermission || perm.isClanOwner || perm.hasManageClanPermission) return true
        val creator = item.creatorId.toLongOrNull() ?: return false
        return creator == uid
    }

    private fun resolveMember(userId: Long): ClanMember? =
        userClanController.getClanMembers(clanId).firstOrNull { it.userId == userId }

    private fun displayAuthor(item: EmojiItem): String {
        val creatorId = item.creatorId.toLongOrNull() ?: return ""
        val m = resolveMember(creatorId) ?: return ""
        return m.clanNick.ifBlank { m.displayName.ifBlank { m.username } }
    }

    private fun commitEmojiRename(item: EmojiItem, inner: String) {
        val trimmedName = inner.trim()
        if (trimmedName.length !in CLAN_EMOJI_NAME_MIN_LENGTH..CLAN_EMOJI_NAME_MAX_LENGTH || !isClanEmojiNameValid(trimmedName)) {
            MezonToast.show(this, ToastOverlay.ToastType.ERROR, getString(R.string.clan_emoji_validate_name, CLAN_EMOJI_NAME_MIN_LENGTH, CLAN_EMOJI_NAME_MAX_LENGTH))
            reloadListUi()
            return
        }
        val shortname = ":$trimmedName:"
        if (shortname == item.shortname) return
        val emojiId = item.id.toLongOrNull() ?: return
        fragmentScope.launch(ioDispatcher) {
            try {
                setBlockingUi(true)
                sessionManager.withAutoRefresh { session ->
                    api.updateClanEmojiById(session.apiUrl, session.token, emojiId, clanId, shortname)
                }
                withContext(mainDispatcher) {
                    setBlockingUi(false)
                    emojiController.invalidateEmojiCacheAndReload()
                }
            } catch (_: Exception) {
                withContext(mainDispatcher) {
                    setBlockingUi(false)
                    MezonToast.show(this@EmojiSettingFragment, ToastOverlay.ToastType.ERROR, getString(R.string.clan_emoji_update_failed))
                    reloadListUi()
                }
            }
        }
    }

    private fun confirmDelete(item: EmojiItem) {
        val ctx = getContext() ?: return
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.clan_emoji_delete_title))
            .setMessage(getString(R.string.clan_emoji_delete_message, item.shortname))
            .setNegativeButton(getString(R.string.common_cancel), null)
            .setPositiveButton(getString(R.string.clan_emoji_delete_confirm)) { _, _ ->
                deleteEmoji(item)
            }
            .show()
    }

    private fun deleteEmoji(item: EmojiItem) {
        val emojiId = item.id.toLongOrNull() ?: return
        fragmentScope.launch(ioDispatcher) {
            try {
                setBlockingUi(true)
                sessionManager.withAutoRefresh { session ->
                    api.deleteByIdClanEmoji(session.apiUrl, session.token, emojiId, clanId, item.shortname)
                }
                withContext(mainDispatcher) {
                    setBlockingUi(false)
                    emojiController.invalidateEmojiCacheAndReload()
                }
            } catch (_: Exception) {
                withContext(mainDispatcher) {
                    setBlockingUi(false)
                    MezonToast.show(this@EmojiSettingFragment, ToastOverlay.ToastType.ERROR, getString(R.string.clan_emoji_delete_failed))
                }
            }
        }
    }

    private inner class EmojiSettingAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val viewTypeHeader = 1
        private val viewTypeRow = 2
        private val viewTypeEmpty = 3
        private val emptyRowItemId = 1L

        private var rows: List<EmojiItem> = emptyList()
        private var appliedBindEpoch = 0

        init {
            setHasStableIds(true)
        }

        fun submit(newRows: List<EmojiItem>) {
            val oldRows = rows
            if (oldRows === newRows) return
            val oldEpoch = appliedBindEpoch
            val newEpoch = emojiListBindEpoch
            val result = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = if (oldRows.isEmpty()) 2 else 1 + oldRows.size
                override fun getNewListSize() = if (newRows.isEmpty()) 2 else 1 + newRows.size
                override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
                    if (oldPos == 0 && newPos == 0) return true
                    if (oldPos == 0 || newPos == 0) return false
                    val oldEmpty = oldRows.isEmpty()
                    val newEmpty = newRows.isEmpty()
                    if (oldEmpty && newEmpty) return true
                    if (oldEmpty || newEmpty) return false
                    return oldRows[oldPos - 1].id == newRows[newPos - 1].id
                }
                override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                    if (oldPos == 0 && newPos == 0) return true
                    if (oldPos == 0 || newPos == 0) return false
                    val oldEmpty = oldRows.isEmpty()
                    val newEmpty = newRows.isEmpty()
                    if (oldEmpty && newEmpty) return true
                    if (oldEmpty || newEmpty) return false
                    if (oldEpoch != newEpoch) return false
                    return oldRows[oldPos - 1] == newRows[newPos - 1]
                }
            })
            rows = newRows
            appliedBindEpoch = newEpoch
            result.dispatchUpdatesTo(this)
        }

        override fun getItemId(position: Int): Long {
            if (position == 0) return 0L
            if (rows.isEmpty()) return emptyRowItemId
            val rowIndex = position - 1
            if (rowIndex !in rows.indices) return RecyclerView.NO_ID
            return rows[rowIndex].id.toLongOrNull() ?: rows[rowIndex].id.hashCode().toLong()
        }

        override fun getItemCount(): Int = if (rows.isEmpty()) 2 else 1 + rows.size

        override fun getItemViewType(position: Int): Int {
            if (position == 0) return viewTypeHeader
            if (rows.isEmpty()) return viewTypeEmpty
            return viewTypeRow
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val ctx = parent.context
            if (viewType == viewTypeHeader) {
                val outer = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(LayoutHelper.dp(16f), LayoutHelper.dp(8f), LayoutHelper.dp(16f), LayoutHelper.dp(8f))
                }
                val uploadButton = TextView(ctx).apply {
                    text = "  " + getString(R.string.clan_emoji_upload_button)
                    textSize = 15f
                    setTextColor(android.graphics.Color.WHITE)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setPadding(LayoutHelper.dp(24f), 0, LayoutHelper.dp(24f), 0)
                    
                    val icon = MezonIcon.uploadPlusIcon.getDrawable(ctx, android.graphics.Color.WHITE)
                    val iconSize = LayoutHelper.dp(20f)
                    icon.setBounds(0, 0, iconSize, iconSize)
                    setCompoundDrawables(icon, null, null, null)

                    val r = LayoutHelper.dp(22f).toFloat()
                    background = RippleDrawable(
                        ColorStateList.valueOf(0x26FFFFFF),
                        GradientDrawable().apply {
                            setColor(themeColors.blurple)
                            cornerRadius = r
                        },
                        GradientDrawable().apply {
                            setColor(android.graphics.Color.WHITE)
                            cornerRadius = r
                        }
                    )
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { openImagePicker() }
                }
                outer.addView(uploadButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 44, gravity = Gravity.CENTER_HORIZONTAL).apply {
                    topMargin = LayoutHelper.dp(16f)
                    bottomMargin = LayoutHelper.dp(8f)
                })

                outer.addView(
                    TextView(ctx).apply {
                        text = getString(R.string.clan_emoji_description_body)
                        textSize = 13f
                        setTextColor(CreateClanRnUiTokens.textDisabled(themeColors))
                    },
                    LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                        topMargin = LayoutHelper.dp(12f)
                    },
                )
                outer.addView(
                    TextView(ctx).apply {
                        text = getString(R.string.clan_emoji_requirements_title)
                        textSize = 12f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(themeColors.colorText)
                    },
                    LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                        topMargin = LayoutHelper.dp(14f)
                    },
                )
                outer.addView(
                    TextView(ctx).apply {
                        text = getString(R.string.clan_emoji_requirements_list)
                        textSize = 12f
                        setTextColor(CreateClanRnUiTokens.textDisabled(themeColors))
                    },
                    LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                        topMargin = LayoutHelper.dp(6f)
                    },
                )

                val labels = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, LayoutHelper.dp(18f), 0, LayoutHelper.dp(6f))
                }
                labels.addView(
                    TextView(ctx).apply {
                        text = getString(R.string.clan_emoji_column_image)
                        textSize = 11f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(themeColors.textDisabled)
                    },
                    LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, EMOJI_ROW_IMAGE_WEIGHT),
                )
                labels.addView(
                    TextView(ctx).apply {
                        text = getString(R.string.clan_emoji_column_name)
                        textSize = 11f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(themeColors.textDisabled)
                    },
                    LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, EMOJI_ROW_NAME_WEIGHT),
                )
                labels.addView(
                    TextView(ctx).apply {
                        text = getString(R.string.clan_emoji_column_uploaded_by)
                        textSize = 11f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(themeColors.textDisabled)
                        gravity = Gravity.END
                    },
                    LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, EMOJI_ROW_UPLOADER_WEIGHT),
                )
                labels.addView(
                    View(ctx),
                    LayoutHelper.createLinear(40, LayoutHelper.WRAP_CONTENT),
                )
                outer.addView(labels, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

                outer.layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT,
                )
                return object : RecyclerView.ViewHolder(outer) {}
            }

            if (viewType == viewTypeEmpty) {
                val emptyTv = TextView(ctx).apply {
                    text = getString(R.string.clan_emoji_empty_list)
                    textSize = 15f
                    setTextColor(themeColors.textDisabled)
                    gravity = Gravity.CENTER
                    setPadding(0, LayoutHelper.dp(40f), 0, LayoutHelper.dp(40f))
                }
                emptyTv.layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT,
                )
                return object : RecyclerView.ViewHolder(emptyTv) {}
            }

            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = LayoutHelper.dp(60f)
                setPadding(LayoutHelper.dp(14f), LayoutHelper.dp(8f), LayoutHelper.dp(14f), LayoutHelper.dp(8f))
                setBackgroundColor(themeColors.border)
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT,
                )
            }

            val emojiCol = FrameLayout(ctx)
            val emojiIv = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            emojiCol.addView(
                emojiIv,
                FrameLayout.LayoutParams(LayoutHelper.dp(40), LayoutHelper.dp(40), Gravity.CENTER),
            )
            val forSaleBadge = ImageView(ctx).apply {
                setImageDrawable(MezonIcon.saleIcon.getDrawable(ctx))
                scaleType = ImageView.ScaleType.FIT_CENTER
                visibility = View.GONE
            }
            emojiCol.addView(
                forSaleBadge,
                FrameLayout.LayoutParams(
                    LayoutHelper.dp(16),
                    LayoutHelper.dp(16),
                    Gravity.TOP or Gravity.END,
                )
            )
            row.addView(
                emojiCol,
                LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, EMOJI_ROW_IMAGE_WEIGHT, Gravity.CENTER_VERTICAL),
            )

            val nameSlot = FrameLayout(ctx)
            val nameDisplay = TextView(ctx).apply {
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(themeColors.colorText)
                ellipsize = TextUtils.TruncateAt.END
                maxLines = 1
            }
            val nameEditor = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                visibility = View.GONE
            }
            val colonLabelStyle: TextView.() -> Unit = {
                text = ":"
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(themeColors.colorText)
                includeFontPadding = false
            }
            val colonSlot = LayoutHelper.dp(8f)
            val colonPrefix = TextView(ctx).apply {
                colonLabelStyle()
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
            val colonSuffix = TextView(ctx).apply {
                colonLabelStyle()
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
            }
            val edit = EditText(ctx).apply {
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(themeColors.colorText)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                isSingleLine = true
                maxLines = 1
                imeOptions = EditorInfo.IME_ACTION_DONE
                minimumWidth = 0
                setHorizontallyScrolling(true)
                setPadding(0, 0, 0, 0)
                setCompoundDrawables(null, null, null, null)
                compoundDrawablePadding = 0
            }
            nameEditor.addView(colonPrefix, LayoutHelper.createLinear(colonSlot, LayoutHelper.WRAP_CONTENT))
            nameEditor.addView(edit, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))
            nameEditor.addView(colonSuffix, LayoutHelper.createLinear(colonSlot, LayoutHelper.WRAP_CONTENT))
            nameSlot.addView(
                nameDisplay,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT),
            )
            nameSlot.addView(
                nameEditor,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT),
            )
            row.addView(
                nameSlot,
                LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, EMOJI_ROW_NAME_WEIGHT, Gravity.CENTER_VERTICAL),
            )

            val av = AvatarView(ctx).apply {
                setSizeDp(28)
                setRoundRadius(14f)
            }
            val uploaderCol = FrameLayout(ctx)
            uploaderCol.addView(
                av,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.END or Gravity.CENTER_VERTICAL,
                ),
            )
            row.addView(
                uploaderCol,
                LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, EMOJI_ROW_UPLOADER_WEIGHT, Gravity.CENTER_VERTICAL),
            )

            val deleteBtn = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                contentDescription = ctx.getString(R.string.clan_emoji_delete_title)
                setPadding(LayoutHelper.dp(6f), LayoutHelper.dp(6f), LayoutHelper.dp(6f), LayoutHelper.dp(6f))
            }
            val removeCol = FrameLayout(ctx)
            removeCol.addView(
                deleteBtn,
                FrameLayout.LayoutParams(
                    LayoutHelper.dp(28),
                    LayoutHelper.dp(28),
                    Gravity.CENTER,
                ),
            )
            row.addView(
                removeCol,
                LayoutHelper.createLinear(40, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL),
            )

            val vh = RowVH(row, emojiIv, nameDisplay, nameEditor, edit, av, deleteBtn, forSaleBadge)
            edit.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    val item = vh.item ?: return@OnFocusChangeListener
                    commitEmojiRename(item, edit.text?.toString().orEmpty())
                    vh.showNameDisplay(item.shortname)
                }
            }
            nameDisplay.setOnClickListener {
                if (edit.isEnabled) vh.beginNameEdit()
            }
            deleteBtn.setOnClickListener {
                vh.item?.let { confirmDelete(it) }
            }
            return vh
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val type = getItemViewType(position)
            if (type == viewTypeHeader || type == viewTypeEmpty) return
            val item = rows[position - 1]
            holder as RowVH
            holder.item = item

            holder.forSaleBadge.visibility = if (item.isForSale) View.VISIBLE else View.GONE

            val url = item.src.ifBlank { getEmojiUrl(item.id).orEmpty() }
            if (url.isNotBlank()) {
                holder.cancellable?.cancel()
                if (com.mezon.mobile.core.SharedConfig.deviceIsLow()) {
                    holder.cancellable = MezonImageLoader.getInstance(holder.emojiIv.context).load(
                        url,
                        LayoutHelper.dp(40f),
                        LayoutHelper.dp(40f),
                        onSuccess = { bmp -> holder.emojiIv.setImageBitmap(bmp) },
                        onError = errLow@{ _: Exception ->
                            val direct = com.mezon.mobile.util.getEmojiDirectUrl(item.id) ?: return@errLow
                            if (direct == url) return@errLow
                            holder.cancellable = MezonImageLoader.getInstance(holder.emojiIv.context).load(
                                direct,
                                LayoutHelper.dp(40f),
                                LayoutHelper.dp(40f),
                                onSuccess = { bmp -> holder.emojiIv.setImageBitmap(bmp) },
                                onError = { holder.emojiIv.setImageDrawable(null) }
                            )
                        },
                    )
                } else {
                    holder.cancellable = MezonImageLoader.getInstance(holder.emojiIv.context).loadDrawable(
                        url,
                        LayoutHelper.dp(40f),
                        LayoutHelper.dp(40f),
                        onSuccess = { d ->
                            holder.emojiIv.setImageDrawable(d)
                            if (d is android.graphics.drawable.AnimatedImageDrawable) {
                                d.start()
                            }
                        },
                        onError = errDrawable@{ _: Exception ->
                            val direct = com.mezon.mobile.util.getEmojiDirectUrl(item.id) ?: run {
                                holder.emojiIv.setImageDrawable(null)
                                return@errDrawable
                            }
                            if (direct == url) {
                                holder.emojiIv.setImageDrawable(null)
                                return@errDrawable
                            }
                            holder.cancellable = MezonImageLoader.getInstance(holder.emojiIv.context).loadDrawable(
                                direct,
                                LayoutHelper.dp(40f),
                                LayoutHelper.dp(40f),
                                onSuccess = { d ->
                                    holder.emojiIv.setImageDrawable(d)
                                    if (d is android.graphics.drawable.AnimatedImageDrawable) {
                                        d.start()
                                    }
                                },
                                onError = {
                                    holder.cancellable = MezonImageLoader.getInstance(holder.emojiIv.context).load(
                                        direct,
                                        LayoutHelper.dp(40f),
                                        LayoutHelper.dp(40f),
                                        onSuccess = { bmp -> holder.emojiIv.setImageBitmap(bmp) },
                                        onError = { holder.emojiIv.setImageDrawable(null) }
                                    )
                                }
                            )
                        },
                    )
                }
            } else {
                holder.cancellable?.cancel()
                holder.emojiIv.setImageDrawable(null)
            }

            holder.showNameDisplay(item.shortname)

            val allow = canEditOrDelete(item)
            holder.edit.isEnabled = allow
            holder.edit.isFocusable = allow
            holder.edit.isFocusableInTouchMode = allow
            holder.nameDisplay.isClickable = allow

            val creatorId = item.creatorId.toLongOrNull() ?: 0L
            val m = resolveMember(creatorId)
            val avUrl = m?.clanAvatar.orEmpty().ifBlank { m?.avatarUrl.orEmpty() }
            holder.avatarView.setInfo(creatorId, displayAuthor(item))
            if (avUrl.isNotBlank()) holder.avatarView.setImageUrl(avUrl) else holder.avatarView.setImageUrl(null)

            val del = MezonIcon.closeSmallBold.getDrawable(holder.deleteBtn.context, themeColors.error)
            holder.deleteBtn.setImageDrawable(del)
            holder.deleteBtn.visibility = if (allow) View.VISIBLE else View.INVISIBLE
        }
        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            super.onViewRecycled(holder)
            if (holder is RowVH) {
                holder.cancellable?.cancel()
                holder.cancellable = null
                val d = holder.emojiIv.drawable
                if (d is android.graphics.drawable.AnimatedImageDrawable) {
                    d.stop()
                }
                holder.emojiIv.setImageDrawable(null)
            }
        }

        private fun RowVH.showNameDisplay(shortname: String) {
            edit.clearFocus()
            edit.setText(shortname.trim(':'))
            nameDisplay.text = shortname
            nameDisplay.visibility = View.VISIBLE
            nameEditor.visibility = View.GONE
        }

        private fun RowVH.beginNameEdit() {
            nameDisplay.visibility = View.GONE
            nameEditor.visibility = View.VISIBLE
            edit.requestFocus()
            edit.post {
                edit.setSelection(edit.text?.length ?: 0)
                edit.layout?.let { layout ->
                    val scrollX = layout.getPrimaryHorizontal(edit.selectionStart).toInt()
                    edit.scrollTo(scrollX.coerceAtLeast(0), 0)
                }
            }
        }

        private inner class RowVH(
            view: View,
            val emojiIv: ImageView,
            val nameDisplay: TextView,
            val nameEditor: LinearLayout,
            val edit: EditText,
            val avatarView: AvatarView,
            val deleteBtn: ImageView,
            val forSaleBadge: ImageView,
        ) : RecyclerView.ViewHolder(view) {
            var item: EmojiItem? = null
            var cancellable: MezonImageLoader.Cancellable? = null
        }
    }
}
