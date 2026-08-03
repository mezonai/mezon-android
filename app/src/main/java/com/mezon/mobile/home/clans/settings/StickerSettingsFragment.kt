package com.mezon.mobile.home.clans.settings

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.BuildConfig
import com.mezon.mobile.R
import com.mezon.mobile.core.AlertDialog
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.chat.EmojiController
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.home.chat.StickerItem
import com.mezon.mobile.home.clans.ClansController
import com.mezon.mobile.home.clans.PermissionPolicy
import com.mezon.mobile.home.clans.RoleController
import com.mezon.mobile.home.profile.UserController
import com.mezon.mobile.home.UserClanController
import com.mezon.mobile.ui.MezonToast
import com.mezon.mobile.ui.cells.ActionBarView
import com.mezon.mobile.ui.cells.InputCell
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.cells.ToastOverlay
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

private class StickerTileViews(
    val imageView: ImageView,
    val nameTv: TextView,
    val forSaleBadge: ImageView,
    val manageOverlay: LinearLayout,
    val editBtn: ImageView,
    val deleteBtn: ImageView,
)

class StickerSettingsFragment : BaseFragment() {

    companion object {
        private const val ARG_CLAN_ID = "clanId"
        private const val REQUEST_PICK_STICKER = 5020
        private const val GRID_SPAN = 3

        fun newInstance(clanId: Long): StickerSettingsFragment =
            StickerSettingsFragment().apply {
                arguments = Bundle().apply { putLong(ARG_CLAN_ID, clanId) }
            }
    }

    private var clanId = 0L
    private lateinit var controller: StickerSettingsController
    private lateinit var emojiController: EmojiController
    private lateinit var clansController: ClansController
    private lateinit var userController: UserController
    private lateinit var userClanController: UserClanController
    private lateinit var roleController: RoleController
    private lateinit var permissionPolicy: PermissionPolicy
    private lateinit var ioDispatcher: CoroutineDispatcher
    private lateinit var mainDispatcher: CoroutineDispatcher

    private var permState: ClanSettingsPermissionState? = null
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: StickerGridAdapter
    private var blockingOverlay: FrameLayout? = null

    override fun onInject(entryPoint: FragmentEntryPoint) {
        controller = entryPoint.stickerSettingsController()
        emojiController = entryPoint.emojiController()
        clansController = entryPoint.clansController()
        userController = entryPoint.userController()
        userClanController = entryPoint.userClanController()
        roleController = entryPoint.roleController()
        permissionPolicy = entryPoint.permissionPolicy()
        ioDispatcher = entryPoint.ioDispatcher()
        mainDispatcher = entryPoint.mainDispatcher()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        clanId = arguments?.getLong(ARG_CLAN_ID) ?: 0L
        if (clanId == 0L) return false

        observe(NotificationCenter.stickersNeedReload) { _, _, _ ->
            if (isPaused) return@observe
            reloadListUi()
        }
        observe(NotificationCenter.clanMembersDidLoad) { _, _, args ->
            if (isPaused) return@observe
            val id = args.firstOrNull() as? Long ?: return@observe
            if (id == clanId) refreshPermissionsAndList()
        }

        roleController.loadRolesForClan(clanId, force = false)
        userClanController.loadClanMembers(clanId)
        emojiController.loadStickers()
        return true
    }

    override fun onBecomeFullyVisible() {
        super.onBecomeFullyVisible()
        roleController.loadRolesForClan(clanId, force = false)
        userClanController.loadClanMembers(clanId)
        refreshPermissionsAndList()
        emojiController.loadStickers()
    }

    override fun createView(context: Context): View {
        val root = FrameLayout(context).apply {
            setBackgroundColor(themeColors.background)
        }

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        actionBar = ActionBarView(context, themeColors).apply {
            setTitle(getString(R.string.menu_clan_sticker))
            setBackButtonImage(R.drawable.ic_close_24)
            setBackButtonContentDescription(getString(R.string.common_close))
            setCenterTitle(true)
            setMenuOnItemClick(object : ActionBarView.ActionBarMenuOnItemClick() {
                override fun onItemClick(id: Int) {
                    if (id == -1) finishFragment()
                }
            })
        }
        column.addView(actionBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        recycler = RecyclerView(context).apply {
            val gridLM = GridLayoutManager(context, GRID_SPAN).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int {
                        return if (adapter?.getItemViewType(position) == 0) GRID_SPAN else 1
                    }
                }
            }
            layoutManager = gridLM
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        adapter = StickerGridAdapter()
        recycler.adapter = adapter

        column.addView(recycler, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        root.addView(column, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))

        blockingOverlay = FrameLayout(context).apply {
            visibility = View.GONE
            isClickable = true
            setBackgroundColor(0x88000000.toInt())
            addView(
                ProgressBar(context).apply {
                    isIndeterminate = true
                    indeterminateTintList = android.content.res.ColorStateList.valueOf(themeColors.colorText)
                },
                FrameLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER)
            )
        }
        root.addView(blockingOverlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))

        fragmentView = root
        refreshPermissionsAndList()
        return root
    }

    private fun refreshPermissionsAndList() {
        val members = userClanController.getClanMembers(clanId)
        val roles = roleController.getRoles(clanId)
        val clan = clansController.clans.value.firstOrNull { it.clanId == clanId }
        permState = ClanSettingsPermissionState.evaluateForClanSettings(
            userController, clanId, members, roles, clan?.creatorId ?: 0L,
        )
        reloadListUi()
    }

    private fun reloadListUi() {
        if (!::adapter.isInitialized) return
        val stickers = emojiController.imageStickersForClan(clanId)
        val perm = permState
        val myUserId = userController.userId
        val rows = buildRows(stickers, perm, myUserId)
        adapter.submit(rows)
    }

    private fun buildRows(
        stickers: List<StickerItem>,
        perm: ClanSettingsPermissionState?,
        myUserId: Long,
    ): List<StickerRow> {
        val result = mutableListOf<StickerRow>()
        result.add(StickerRow.Header)
        for (s in stickers) {
            val canManage = perm?.hasManageClanPermission == true || perm?.isClanOwner == true || s.creatorId == myUserId.toString()
            result.add(StickerRow.Item(s, canManage))
        }
        result.add(StickerRow.AddButton)
        return result
    }

    private fun openImagePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/jpeg", "image/png", "image/gif"))
        }
        startActivityForResult(Intent.createChooser(intent, getString(R.string.sticker_pick_file)), REQUEST_PICK_STICKER)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK || requestCode != REQUEST_PICK_STICKER) return
        val uri = data?.data ?: return
        handlePickedImage(uri)
    }

    private fun handlePickedImage(uri: Uri) {
        fragmentScope.launch(ioDispatcher) {
            val ctx = getContext() ?: return@launch
            val cr = ctx.contentResolver ?: return@launch
            val mimeType = cr.getType(uri) ?: "image/jpeg"
            val isGif = mimeType.equals("image/gif", ignoreCase = true)
            val rawBytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: return@launch

            val processedBytes: ByteArray
            if (isGif) {
                processedBytes = rawBytes
            } else {
                val scaled = resizeToFit(rawBytes, STICKER_DIMENSION)
                if (scaled == null) {
                    withContext(mainDispatcher) {
                        MezonToast.show(this@StickerSettingsFragment, ToastOverlay.ToastType.ERROR, getString(R.string.sticker_file_invalid))
                    }
                    return@launch
                }
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.WEBP, 90, out)
                scaled.recycle()
                processedBytes = out.toByteArray()
            }

            if (processedBytes.size > MAX_STICKER_FILE_BYTES) {
                withContext(mainDispatcher) {
                    MezonToast.show(this@StickerSettingsFragment, ToastOverlay.ToastType.ERROR, getString(R.string.sticker_file_too_large))
                }
                return@launch
            }

            val tmpFile = File(ctx.cacheDir, "sticker_upload_${System.currentTimeMillis()}.${if (isGif) "gif" else "webp"}")
            tmpFile.writeBytes(processedBytes)
            val finalMime = if (isGif) "image/gif" else "image/webp"

            withContext(mainDispatcher) {
                if (!isFinished) showUploadDialog(tmpFile, finalMime, isGif)
            }
        }
    }

    private fun resizeToFit(bytes: ByteArray, maxDim: Int): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            val srcW = opts.outWidth.coerceAtLeast(1)
            val srcH = opts.outHeight.coerceAtLeast(1)
            var sample = 1
            while (srcW / (sample * 2) >= maxDim && srcH / (sample * 2) >= maxDim) {
                sample *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts) ?: return null
            val scale = minOf(maxDim.toFloat() / decoded.width, maxDim.toFloat() / decoded.height, 1f)
            val tW = (decoded.width * scale).toInt().coerceAtLeast(1)
            val tH = (decoded.height * scale).toInt().coerceAtLeast(1)
            if (tW == decoded.width && tH == decoded.height) decoded
            else Bitmap.createScaledBitmap(decoded, tW, tH, true).also { if (it !== decoded) decoded.recycle() }
        } catch (_: Exception) {
            null
        }
    }

    private fun showUploadDialog(file: File, mimeType: String, isGif: Boolean) {
        val ctx = getContext() ?: return
        val previewSide = LayoutHelper.dp(80f)

        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(16f), LayoutHelper.dp(8f), LayoutHelper.dp(16f), 0)
        }

        val previewIv = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        body.addView(previewIv, LayoutHelper.createLinear(previewSide, previewSide, 0f, Gravity.CENTER_HORIZONTAL, 0f, 0f, 0f, 12f))

        val nameCell = InputCell(ctx, themeColors).apply {
            setHint(getString(R.string.sticker_name_hint))
            setMaxCharacter(62)
        }
        body.addView(nameCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 8f))

        val saleCheck = CheckBox(ctx).apply {
            text = getString(R.string.sticker_is_for_sale)
            setTextColor(themeColors.colorText)
            textSize = 14f
        }
        body.addView(saleCheck, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 4f))

        val infoTv = TextView(ctx).apply {
            text = getString(R.string.sticker_upload_requirements)
            textSize = 12f
            setTextColor(themeColors.textDisabled)
        }
        body.addView(infoTv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val scroll = ScrollView(ctx)
        scroll.addView(body)

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.sticker_upload_title))
            .setView(scroll, LayoutHelper.WRAP_CONTENT)
            .setDismissDialogByButtons(false)
            .setNegativeButton(getString(R.string.common_cancel)) { d, _ ->
                file.delete()
                d.dismiss()
            }
            .setPositiveButton(getString(R.string.sticker_btn_upload), null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setOnClickListener {
                val name = nameCell.getText().trim()
                if (name.length < 3 || name.length > 64) {
                    nameCell.setError(getString(R.string.sticker_name_error))
                    return@setOnClickListener
                }
                nameCell.setError(null)
                dialog.dismiss()
                runUploadFlow(file, mimeType, isGif, name, saleCheck.isChecked)
            }
        }
        dialog.show()

        fragmentScope.launch {
            if (isGif && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val drawable = withContext(ioDispatcher) {
                    try {
                        val source = ImageDecoder.createSource(file)
                        ImageDecoder.decodeDrawable(source) { decoder, _, _ ->
                            decoder.setTargetSize(previewSide, previewSide)
                        }
                    } catch (_: Exception) {
                        null
                    }
                }
                if (drawable != null) {
                    withContext(mainDispatcher) {
                        if (!isFinished && previewIv.isAttachedToWindow) {
                            previewIv.setImageDrawable(drawable)
                            if (drawable is android.graphics.drawable.AnimatedImageDrawable) {
                                drawable.start()
                            }
                        }
                    }
                    return@launch
                }
            }

            val bmp = withContext(ioDispatcher) {
                try {
                    val bytes = file.readBytes()
                    if (isGif && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        val src = ImageDecoder.createSource(file)
                        ImageDecoder.decodeBitmap(src) { dec, _, _ -> dec.setTargetSize(previewSide, previewSide) }
                    } else {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                } catch (_: Exception) { null }
            } ?: return@launch
            withContext(mainDispatcher) {
                if (!isFinished && previewIv.isAttachedToWindow) previewIv.setImageBitmap(bmp) else bmp.recycle()
            }
        }
    }

    private fun showRenameDialog(sticker: StickerItem) {
        val ctx = getContext() ?: return
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(16f), LayoutHelper.dp(8f), LayoutHelper.dp(16f), 0)
        }
        val nameCell = InputCell(ctx, themeColors).apply {
            setHint(getString(R.string.sticker_name_hint))
            setMaxCharacter(62)
            setText(sticker.shortname)
        }
        body.addView(nameCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val infoTv = TextView(ctx).apply {
            text = getString(R.string.sticker_edit_file_note)
            textSize = 12f
            setTextColor(themeColors.textDisabled)
        }
        body.addView(infoTv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 8f, 0f, 0f))

        val scroll = ScrollView(ctx)
        scroll.addView(body)

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.sticker_edit_title))
            .setView(scroll, LayoutHelper.WRAP_CONTENT)
            .setDismissDialogByButtons(false)
            .setNegativeButton(getString(R.string.common_cancel)) { d, _ -> d.dismiss() }
            .setPositiveButton(getString(R.string.sticker_btn_save), null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setOnClickListener {
                val name = nameCell.getText().trim()
                if (name.length < 3 || name.length > 64) {
                    nameCell.setError(getString(R.string.sticker_name_error))
                    return@setOnClickListener
                }
                if (name == sticker.shortname) { dialog.dismiss(); return@setOnClickListener }
                nameCell.setError(null)
                dialog.dismiss()
                controller.rename(clanId, sticker, name) { ok, err ->
                    fragmentScope.launch(mainDispatcher) {
                        blockingOverlay?.visibility = View.GONE
                        if (!ok) MezonToast.show(this@StickerSettingsFragment, ToastOverlay.ToastType.ERROR, err ?: getString(R.string.sticker_rename_failed))
                    }
                }
            }
        }
        dialog.show()
    }

    private fun confirmDelete(sticker: StickerItem) {
        val ctx = getContext() ?: return
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.sticker_delete_confirm_title))
            .setMessage(getString(R.string.sticker_delete_confirm_msg, sticker.shortname))
            .setNegativeButton(getString(R.string.common_cancel)) { d, _ -> d.dismiss() }
            .setPositiveButton(getString(R.string.common_delete)) { d, _ ->
                d.dismiss()
                controller.delete(clanId, sticker) { ok, err ->
                    fragmentScope.launch(mainDispatcher) {
                        if (!ok) MezonToast.show(this@StickerSettingsFragment, ToastOverlay.ToastType.ERROR, err ?: getString(R.string.sticker_delete_failed))
                    }
                }
            }
            .show()
    }

    private fun runUploadFlow(file: File, mimeType: String, isGif: Boolean, name: String, isForSale: Boolean) {
        fragmentScope.launch(ioDispatcher) {
            withContext(mainDispatcher) { blockingOverlay?.visibility = View.VISIBLE }
            val bytes = file.readBytes()
            file.delete()
            controller.create(clanId, name, bytes, mimeType, isGif, isForSale) { ok, err ->
                fragmentScope.launch(mainDispatcher) {
                    blockingOverlay?.visibility = View.GONE
                    if (!ok) MezonToast.show(this@StickerSettingsFragment, ToastOverlay.ToastType.ERROR, err ?: getString(R.string.sticker_upload_failed))
                }
            }
        }
    }

    sealed class StickerRow {
        object Header : StickerRow()
        object AddButton : StickerRow()
        data class Item(val sticker: StickerItem, val canManage: Boolean) : StickerRow()
    }

    inner class StickerGridAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        val TYPE_HEADER = 0
        val TYPE_ITEM = 1
        val TYPE_ADD = 2

        private var rows: List<StickerRow> = emptyList()

        fun submit(newRows: List<StickerRow>) {
            val old = rows
            rows = newRows
            val result = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = old.size
                override fun getNewListSize() = newRows.size
                override fun areItemsTheSame(op: Int, np: Int): Boolean {
                    val o = old[op]
                    val n = newRows[np]
                    return when {
                        o is StickerRow.Item && n is StickerRow.Item -> o.sticker.id == n.sticker.id
                        o is StickerRow.Header && n is StickerRow.Header -> true
                        o is StickerRow.AddButton && n is StickerRow.AddButton -> true
                        else -> false
                    }
                }
                override fun areContentsTheSame(op: Int, np: Int) = old[op] == newRows[np]
            })
            result.dispatchUpdatesTo(this)
        }

        override fun getItemViewType(position: Int) = when (rows[position]) {
            is StickerRow.Header -> TYPE_HEADER
            is StickerRow.AddButton -> TYPE_ADD
            is StickerRow.Item -> TYPE_ITEM
        }

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val ctx = parent.context
            return when (viewType) {
                TYPE_HEADER -> HeaderVH(buildHeaderView(ctx))
                TYPE_ADD -> AddVH(buildAddTile(ctx))
                else -> ItemVH(buildItemTile(ctx))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is ItemVH) {
                val row = rows[position] as StickerRow.Item
                holder.bind(row)
            }
        }

        private fun buildHeaderView(ctx: Context): View {
            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(LayoutHelper.dp(16f), LayoutHelper.dp(16f), LayoutHelper.dp(16f), LayoutHelper.dp(8f))
            }
            val titleTv = TextView(ctx).apply {
                text = getString(R.string.sticker_upload_instructions_title).uppercase()
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(themeColors.textDisabled)
                letterSpacing = 0.08f
            }
            root.addView(titleTv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 4f))
            val descTv = TextView(ctx).apply {
                text = getString(R.string.sticker_upload_requirements)
                textSize = 13f
                setTextColor(themeColors.textDisabled)
            }
            root.addView(descTv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 12f))
            val uploadBtn = TextView(ctx).apply {
                text = getString(R.string.sticker_upload_btn)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(themeColors.colorText)
                gravity = Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = LayoutHelper.dpf(8f)
                    setColor(themeColors.primary)
                }
                setPadding(LayoutHelper.dp(20f), LayoutHelper.dp(10f), LayoutHelper.dp(20f), LayoutHelper.dp(10f))
                isClickable = true
                setOnClickListener { openImagePicker() }
            }
            root.addView(uploadBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 8f))
            val divider = View(ctx).apply { setBackgroundColor(themeColors.borderDim) }
            root.addView(divider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1))
            return root
        }

        private fun makeTileOutlineProvider() = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, LayoutHelper.dpf(12f))
            }
        }

        private fun tileLayoutParams(): RecyclerView.LayoutParams {
            val gap = LayoutHelper.dp(5f)
            return RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                LayoutHelper.dp(110f)
            ).apply { setMargins(gap, gap, gap, gap) }
        }

        private fun buildAddTile(ctx: Context): View {
            return FrameLayout(ctx).apply {
                layoutParams = tileLayoutParams()
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = LayoutHelper.dpf(12f)
                    setStroke(LayoutHelper.dp(1.5f), themeColors.borderDim)
                    setColor(android.graphics.Color.TRANSPARENT)
                }
                clipToOutline = true
                outlineProvider = makeTileOutlineProvider()
                isClickable = true
                isFocusable = true
                setOnClickListener { openImagePicker() }

                val inner = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                }
                inner.addView(TextView(ctx).apply {
                    text = "+"
                    textSize = 28f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(themeColors.textDisabled)
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                inner.addView(TextView(ctx).apply {
                    text = getString(R.string.sticker_add_label)
                    textSize = 11f
                    setTextColor(themeColors.textDisabled)
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = LayoutHelper.dp(2f)
                })
                addView(inner, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            }
        }

        private fun buildItemTile(ctx: Context): FrameLayout {
            val root = FrameLayout(ctx).apply {
                layoutParams = tileLayoutParams()
                clipToOutline = true
                outlineProvider = makeTileOutlineProvider()
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = LayoutHelper.dpf(12f)
                    setColor(themeColors.border)
                    setStroke(LayoutHelper.dp(1f), themeColors.borderDim)
                }
            }

            val imageView = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            root.addView(imageView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ))

            val nameBar = FrameLayout(ctx).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    colors = intArrayOf(android.graphics.Color.TRANSPARENT, 0xDD000000.toInt())
                    gradientType = android.graphics.drawable.GradientDrawable.LINEAR_GRADIENT
                    orientation = android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM
                }
            }
            val nameTv = TextView(ctx).apply {
                textSize = 10f
                setTextColor(0xFFFFFFFF.toInt())
                gravity = Gravity.CENTER
                ellipsize = TextUtils.TruncateAt.END
                maxLines = 1
                setPadding(LayoutHelper.dp(6f), LayoutHelper.dp(4f), LayoutHelper.dp(6f), LayoutHelper.dp(5f))
            }
            nameBar.addView(nameTv, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ))
            root.addView(nameBar, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                LayoutHelper.dp(28f),
                Gravity.BOTTOM,
            ))

            val forSaleBadge = ImageView(ctx).apply {
                setImageDrawable(MezonIcon.saleIcon.getDrawable(ctx))
                scaleType = ImageView.ScaleType.FIT_CENTER
                visibility = View.GONE
            }
            root.addView(forSaleBadge, FrameLayout.LayoutParams(
                LayoutHelper.dp(24f),
                LayoutHelper.dp(24f),
                Gravity.TOP or Gravity.END,
            ).apply {
                setMargins(0, LayoutHelper.dp(5f), LayoutHelper.dp(5f), 0)
            })

            val manageOverlay = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setBackgroundColor(0xBB000000.toInt())
                visibility = View.GONE
            }
            val editBtn = ImageView(ctx).apply {
                setImageDrawable(MezonIcon.pencilIcon.getDrawable(ctx, 0xFFFFFFFF.toInt()))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(LayoutHelper.dp(10f), LayoutHelper.dp(10f), LayoutHelper.dp(10f), LayoutHelper.dp(10f))
                isClickable = true
            }
            val deleteBtn = ImageView(ctx).apply {
                setImageDrawable(MezonIcon.trashIcon.getDrawable(ctx, 0xFFFF6B6B.toInt()))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(LayoutHelper.dp(10f), LayoutHelper.dp(10f), LayoutHelper.dp(10f), LayoutHelper.dp(10f))
                isClickable = true
            }
            manageOverlay.addView(editBtn, LinearLayout.LayoutParams(LayoutHelper.dp(44f), LayoutHelper.dp(44f)))
            manageOverlay.addView(deleteBtn, LinearLayout.LayoutParams(LayoutHelper.dp(44f), LayoutHelper.dp(44f)).apply {
                leftMargin = LayoutHelper.dp(12f)
            })
            root.addView(manageOverlay, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ))

            root.tag = StickerTileViews(imageView, nameTv, forSaleBadge, manageOverlay, editBtn, deleteBtn)
            return root
        }

        inner class HeaderVH(v: View) : RecyclerView.ViewHolder(v)
        inner class AddVH(v: View) : RecyclerView.ViewHolder(v)

        inner class ItemVH(tileRoot: FrameLayout) : RecyclerView.ViewHolder(tileRoot) {
            private val root: FrameLayout = tileRoot
            private val views = tileRoot.tag as StickerTileViews

            fun bind(row: StickerRow.Item) {
                val ctx = root.context
                val sticker = row.sticker

                views.manageOverlay.visibility = View.GONE
                views.nameTv.text = sticker.shortname
                views.forSaleBadge.visibility = if (sticker.isForSale) View.VISIBLE else View.GONE

                views.imageView.setImageDrawable(null)
                val displayUrl = sticker.src.ifBlank {
                    "${BuildConfig.MEZON_BASE_IMG_URL}/stickers/${sticker.id}.webp"
                }
                val loadSize = LayoutHelper.dp(110f)
                MezonImageLoader.getInstance(ctx).loadDrawable(
                    displayUrl,
                    loadSize,
                    loadSize,
                    onSuccess = { d -> 
                        views.imageView.setImageDrawable(d)
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P && d is android.graphics.drawable.AnimatedImageDrawable) d.start()
                    },
                    onError = { _ -> views.imageView.setImageDrawable(null) },
                    cacheAnimated = true
                )

                if (row.canManage) {
                    views.editBtn.setOnClickListener {
                        views.manageOverlay.visibility = View.GONE
                        showRenameDialog(sticker)
                    }
                    views.deleteBtn.setOnClickListener {
                        views.manageOverlay.visibility = View.GONE
                        confirmDelete(sticker)
                    }
                    root.isClickable = true
                    root.isFocusable = true
                    root.setOnClickListener {
                        views.manageOverlay.visibility =
                            if (views.manageOverlay.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    }
                } else {
                    views.editBtn.setOnClickListener(null)
                    views.deleteBtn.setOnClickListener(null)
                    root.isClickable = false
                    root.isFocusable = false
                    root.setOnClickListener(null)
                }
            }
        }
    }
}
