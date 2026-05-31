package com.mezon.mobile.home.clans.settings

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Typeface
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.R
import com.mezon.mobile.core.AlertDialog
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.chat.EmojiController
import com.mezon.mobile.home.chat.StickerItem
import com.mezon.mobile.home.clans.ClansController
import com.mezon.mobile.home.clans.PermissionPolicy
import com.mezon.mobile.home.clans.RoleController
import com.mezon.mobile.home.profile.UserController
import com.mezon.mobile.home.UserClanController
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.session.SessionManager
import com.mezon.mobile.ui.MezonToast
import com.mezon.mobile.ui.cells.ActionBarView
import com.mezon.mobile.ui.cells.InputCell
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.cells.ToastOverlay
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private class SoundItemViews(
    val playBtn: ImageView,
    val nameTv: TextView,
    val editBtn: ImageView,
    val deleteBtn: ImageView,
)

class SoundEffectSettingsFragment : BaseFragment() {

    companion object {
        private const val ARG_CLAN_ID = "clanId"
        private const val REQUEST_PICK_SOUND = 5010

        fun newInstance(clanId: Long): SoundEffectSettingsFragment =
            SoundEffectSettingsFragment().apply {
                arguments = Bundle().apply { putLong(ARG_CLAN_ID, clanId) }
            }
    }

    private var clanId = 0L
    private lateinit var controller: SoundEffectSettingsController
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
    private lateinit var adapter: SoundListAdapter
    private var blockingOverlay: FrameLayout? = null
    private var mediaPlayer: MediaPlayer? = null
    private var activeSoundUrl: String? = null
    private var activePlayButton: ImageView? = null
    private var pendingPickReplaceSound: StickerItem? = null

    override fun onInject(entryPoint: FragmentEntryPoint) {
        controller = entryPoint.soundEffectSettingsController()
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
        controller.load(clanId)
        return true
    }

    override fun onBecomeFullyVisible() {
        super.onBecomeFullyVisible()
        roleController.loadRolesForClan(clanId, force = false)
        userClanController.loadClanMembers(clanId)
        refreshPermissionsAndList()
        controller.load(clanId)
    }

    override fun onPause() {
        super.onPause()
        stopCurrentPlayback()
    }

    override fun onFragmentDestroy() {
        super.onFragmentDestroy()
        stopCurrentPlayback()
    }

    private fun stopCurrentPlayback() {
        activePlayButton?.let { btn ->
            btn.setImageDrawable(MezonIcon.playIcon.getDrawable(btn.context, themeColors.colorText))
        }
        activePlayButton = null
        activeSoundUrl = null
        mediaPlayer?.apply {
            try {
                if (isPlaying) stop()
                release()
            } catch (_: Exception) {
                try {
                    release()
                } catch (_: Exception) { }
            }
        }
        mediaPlayer = null
    }

    override fun createView(context: Context): View {
        val root = FrameLayout(context).apply {
            setBackgroundColor(themeColors.background)
        }

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        actionBar = ActionBarView(context, themeColors).apply {
            setTitle(getString(R.string.menu_clan_sound))
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
            layoutManager = LinearLayoutManager(context)
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        adapter = SoundListAdapter()
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
        val sounds = emojiController.soundsForClan(clanId)
        val perm = permState
        val myUserId = userController.userId
        val clanCreatorId = clansController.clans.value.firstOrNull { it.clanId == clanId }?.creatorId ?: 0L
        val rows = buildRows(sounds, perm, myUserId, clanCreatorId)
        adapter.submit(rows)
    }

    private fun buildRows(
        sounds: List<StickerItem>,
        perm: ClanSettingsPermissionState?,
        myUserId: Long,
        clanCreatorId: Long,
    ): List<SoundRow> {
        val result = mutableListOf<SoundRow>()
        result.add(SoundRow.Header)
        if (sounds.isEmpty()) {
            result.add(SoundRow.Empty)
        } else {
            for (sound in sounds) {
                val canManage = (perm?.isClanOwner == true) ||
                    myUserId == clanCreatorId ||
                    sound.creatorId == myUserId.toString()
                result.add(SoundRow.SoundItem(sound, canManage))
            }
        }
        return result
    }

    private fun openFilePicker(replaceSound: StickerItem? = null) {
        pendingPickReplaceSound = replaceSound
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "audio/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/mpeg", "audio/mp3", "audio/wav", "audio/x-wav"))
        }
        startActivityForResult(Intent.createChooser(intent, getString(R.string.sound_pick_file)), REQUEST_PICK_SOUND)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_SOUND) return
        val replaceSound = pendingPickReplaceSound
        pendingPickReplaceSound = null
        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        handlePickedSound(uri, existingSound = replaceSound)
    }

    private fun handlePickedSound(uri: Uri, existingSound: StickerItem?) {
        fragmentScope.launch(ioDispatcher) {
            val ctx = getContext() ?: return@launch
            val cr = ctx.contentResolver ?: return@launch
            val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: return@launch
            if (bytes.size > MAX_SOUND_FILE_BYTES) {
                withContext(mainDispatcher) {
                    MezonToast.show(this@SoundEffectSettingsFragment, ToastOverlay.ToastType.ERROR, getString(R.string.sound_file_too_large))
                }
                return@launch
            }
            val tmpFile = File(ctx.cacheDir, "upload_sound_${System.currentTimeMillis()}.wav")
            tmpFile.writeBytes(bytes)
            withContext(mainDispatcher) {
                if (!isFinished) showUploadDialog(tmpFile, existingSound)
            }
        }
    }

    private fun showUploadDialog(file: File, existingSound: StickerItem?) {
        val ctx = getContext() ?: return
        val isEdit = existingSound != null

        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(16f), LayoutHelper.dp(8f), LayoutHelper.dp(16f), 0)
        }

        val nameCell = InputCell(ctx, themeColors).apply {
            setHint(getString(R.string.sound_name_hint))
            setMaxCharacter(62)
            if (isEdit) setText(existingSound!!.shortname)
        }
        body.addView(nameCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 8f))

        val infoTv = TextView(ctx).apply {
            text = getString(R.string.sound_upload_requirements)
            textSize = 12f
            setTextColor(themeColors.textDisabled)
        }
        body.addView(infoTv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val scroll = ScrollView(ctx)
        scroll.addView(body)

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(if (isEdit) getString(R.string.sound_edit_title) else getString(R.string.sound_upload_title))
            .setView(scroll, LayoutHelper.WRAP_CONTENT)
            .setDismissDialogByButtons(false)
            .setNegativeButton(getString(R.string.common_cancel)) { d, _ ->
                file.delete()
                d.dismiss()
            }
            .setPositiveButton(if (isEdit) getString(R.string.sound_btn_update) else getString(R.string.sound_btn_upload), null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setOnClickListener {
                val name = nameCell.getText().trim()
                if (name.length < 3 || name.length > 64) {
                    nameCell.setError(getString(R.string.sound_name_error))
                    return@setOnClickListener
                }
                nameCell.setError(null)
                dialog.dismiss()
                if (isEdit && existingSound != null) {
                    runReplaceSoundFileFlow(existingSound, name, file)
                } else {
                    runUploadFlow(clanId, name, file)
                }
            }
        }
        dialog.show()
    }

    fun showEditDialog(sound: StickerItem) {
        val ctx = getContext() ?: return
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(16f), LayoutHelper.dp(8f), LayoutHelper.dp(16f), 0)
        }
        val nameCell = InputCell(ctx, themeColors).apply {
            setHint(getString(R.string.sound_name_hint))
            setMaxCharacter(62)
            setText(sound.shortname)
        }
        body.addView(nameCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 8f))

        val orPickTv = TextView(ctx).apply {
            text = getString(R.string.sound_edit_or_upload_new)
            textSize = 13f
            setTextColor(themeColors.textLink)
            isClickable = true
        }
        body.addView(orPickTv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val scroll = ScrollView(ctx)
        scroll.addView(body)

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.sound_edit_title))
            .setView(scroll, LayoutHelper.WRAP_CONTENT)
            .setDismissDialogByButtons(false)
            .setNegativeButton(getString(R.string.common_cancel)) { d, _ -> d.dismiss() }
            .setPositiveButton(getString(R.string.sound_btn_update), null)
            .create()

        orPickTv.setOnClickListener {
            dialog.dismiss()
            openFilePicker(replaceSound = sound)
        }

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setOnClickListener {
                val name = nameCell.getText().trim()
                if (name.length < 3 || name.length > 64) {
                    nameCell.setError(getString(R.string.sound_name_error))
                    return@setOnClickListener
                }
                nameCell.setError(null)
                dialog.dismiss()
                blockingOverlay?.visibility = View.VISIBLE
                controller.updateNameOnly(clanId, sound, name) { ok, err ->
                    fragmentScope.launch(mainDispatcher) {
                        blockingOverlay?.visibility = View.GONE
                        if (!ok) MezonToast.show(this@SoundEffectSettingsFragment, ToastOverlay.ToastType.ERROR, err ?: getString(R.string.sound_edit_failed))
                    }
                }
            }
        }
        dialog.show()
    }

    private fun runUploadFlow(clanId: Long, name: String, file: File) {
        fragmentScope.launch(ioDispatcher) {
            val bytes = file.readBytes()
            file.delete()
            val recordId = java.util.concurrent.ThreadLocalRandom.current().nextLong(10_000_000_000_000L, Long.MAX_VALUE / 4)
            withContext(mainDispatcher) { blockingOverlay?.visibility = View.VISIBLE }
            controller.uploadNew(clanId, name, bytes, recordId) { ok, err ->
                fragmentScope.launch(mainDispatcher) {
                    blockingOverlay?.visibility = View.GONE
                    if (!ok) MezonToast.show(this@SoundEffectSettingsFragment, ToastOverlay.ToastType.ERROR, err ?: getString(R.string.sound_upload_failed))
                }
            }
        }
    }

    private fun runReplaceSoundFileFlow(sound: StickerItem, newName: String, file: File) {
        fragmentScope.launch(ioDispatcher) {
            val bytes = try {
                file.readBytes()
            } catch (_: Exception) {
                byteArrayOf()
            } finally {
                file.delete()
            }
            if (bytes.isEmpty()) {
                withContext(mainDispatcher) {
                    MezonToast.show(
                        this@SoundEffectSettingsFragment,
                        ToastOverlay.ToastType.ERROR,
                        getString(R.string.sound_edit_failed),
                    )
                }
                return@launch
            }
            withContext(mainDispatcher) { blockingOverlay?.visibility = View.VISIBLE }
            controller.replaceSoundFile(clanId, sound, newName, bytes) { ok, err ->
                fragmentScope.launch(mainDispatcher) {
                    blockingOverlay?.visibility = View.GONE
                    if (!ok) {
                        MezonToast.show(
                            this@SoundEffectSettingsFragment,
                            ToastOverlay.ToastType.ERROR,
                            err ?: getString(R.string.sound_edit_failed),
                        )
                    }
                }
            }
        }
    }

    fun confirmDelete(sound: StickerItem) {
        val ctx = getContext() ?: return
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.sound_delete_confirm_title))
            .setMessage(getString(R.string.sound_delete_confirm_msg, sound.shortname))
            .setNegativeButton(getString(R.string.common_cancel)) { d, _ -> d.dismiss() }
            .setPositiveButton(getString(R.string.common_delete)) { d, _ ->
                d.dismiss()
                controller.delete(clanId, sound) { ok, err ->
                    fragmentScope.launch(mainDispatcher) {
                        if (!ok) MezonToast.show(this@SoundEffectSettingsFragment, ToastOverlay.ToastType.ERROR, err ?: getString(R.string.sound_delete_failed))
                    }
                }
            }
            .show()
    }

    private fun playSound(url: String, playBtn: ImageView) {
        if (url.isBlank()) return

        if (url == activeSoundUrl && mediaPlayer != null) {
            stopCurrentPlayback()
            return
        }

        stopCurrentPlayback()
        activeSoundUrl = url
        activePlayButton = playBtn

        mediaPlayer = MediaPlayer().apply {
            try {
                setDataSource(url)
                prepareAsync()
                setOnPreparedListener { mp ->
                    mp.start()
                    playBtn.setImageDrawable(MezonIcon.pauseIcon.getDrawable(playBtn.context, themeColors.primary))
                }
                setOnCompletionListener {
                    stopCurrentPlayback()
                }
                setOnErrorListener { _, _, _ ->
                    stopCurrentPlayback()
                    true
                }
                playBtn.setImageDrawable(MezonIcon.pauseIcon.getDrawable(playBtn.context, themeColors.primary))
            } catch (_: Exception) {
                stopCurrentPlayback()
            }
        }
    }


    sealed class SoundRow {
        object Header : SoundRow()
        object Empty : SoundRow()
        data class SoundItem(val item: StickerItem, val canManage: Boolean) : SoundRow()
    }

    inner class SoundListAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val TYPE_HEADER = 0
        private val TYPE_EMPTY = 1
        private val TYPE_ITEM = 2

        private var rows: List<SoundRow> = emptyList()

        fun submit(newRows: List<SoundRow>) {
            val old = rows
            rows = newRows
            val result = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = old.size
                override fun getNewListSize() = newRows.size
                override fun areItemsTheSame(op: Int, np: Int): Boolean {
                    val o = old[op]
                    val n = newRows[np]
                    return when {
                        o is SoundRow.SoundItem && n is SoundRow.SoundItem -> o.item.id == n.item.id
                        o is SoundRow.Header && n is SoundRow.Header -> true
                        o is SoundRow.Empty && n is SoundRow.Empty -> true
                        else -> false
                    }
                }
                override fun areContentsTheSame(op: Int, np: Int) = old[op] == newRows[np]
            })
            result.dispatchUpdatesTo(this)
        }

        override fun getItemViewType(position: Int) = when (rows[position]) {
            is SoundRow.Header -> TYPE_HEADER
            is SoundRow.Empty -> TYPE_EMPTY
            is SoundRow.SoundItem -> TYPE_ITEM
        }

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val ctx = parent.context
            return when (viewType) {
                TYPE_HEADER -> HeaderVH(buildHeaderView(ctx))
                TYPE_EMPTY -> EmptyVH(buildEmptyView(ctx))
                else -> ItemVH(buildItemView(ctx))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is SoundRow.SoundItem -> (holder as ItemVH).bind(row)
                else -> Unit
            }
        }

        private fun buildHeaderView(ctx: Context): View {
            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(LayoutHelper.dp(16f), LayoutHelper.dp(16f), LayoutHelper.dp(16f), LayoutHelper.dp(8f))
            }

            val titleTv = TextView(ctx).apply {
                text = getString(R.string.sound_upload_instructions_title).uppercase()
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(themeColors.textDisabled)
                letterSpacing = 0.08f
            }
            root.addView(titleTv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 4f))

            val descTv = TextView(ctx).apply {
                text = getString(R.string.sound_upload_requirements)
                textSize = 13f
                setTextColor(themeColors.textDisabled)
            }
            root.addView(descTv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 12f))

            val uploadBtn = TextView(ctx).apply {
                text = getString(R.string.sound_upload_btn)
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
                setOnClickListener { openFilePicker() }
            }
            root.addView(uploadBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 8f))

            val divider = View(ctx).apply { setBackgroundColor(themeColors.borderDim) }
            root.addView(divider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1))

            return root
        }

        private fun buildEmptyView(ctx: Context): View {
            return TextView(ctx).apply {
                text = getString(R.string.sound_empty_state)
                textSize = 14f
                setTextColor(themeColors.textDisabled)
                gravity = Gravity.CENTER
                setPadding(LayoutHelper.dp(16f), LayoutHelper.dp(24f), LayoutHelper.dp(16f), LayoutHelper.dp(24f))
            }
        }

        private fun buildItemView(ctx: Context): LinearLayout {
            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(LayoutHelper.dp(16f), LayoutHelper.dp(12f), LayoutHelper.dp(16f), LayoutHelper.dp(12f))
            }

            val topRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val playBtn = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                isClickable = true
            }
            topRow.addView(playBtn, LayoutHelper.createLinear(24, 24, 0f, Gravity.CENTER_VERTICAL, 0f, 0f, 12f, 0f))

            val nameTv = TextView(ctx).apply {
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(themeColors.colorText)
                ellipsize = TextUtils.TruncateAt.END
                maxLines = 1
            }
            topRow.addView(nameTv, LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f))

            val editBtn = ImageView(ctx).apply {
                setImageDrawable(MezonIcon.pencilIcon.getDrawable(ctx, themeColors.textDisabled))
                scaleType = ImageView.ScaleType.FIT_CENTER
                isClickable = true
                visibility = View.GONE
            }
            topRow.addView(editBtn, LayoutHelper.createLinear(20, 20, 0f, Gravity.CENTER_VERTICAL, 8f, 0f, 0f, 0f))

            val deleteBtn = ImageView(ctx).apply {
                setImageDrawable(MezonIcon.trashIcon.getDrawable(ctx, themeColors.error))
                scaleType = ImageView.ScaleType.FIT_CENTER
                isClickable = true
                visibility = View.GONE
            }
            topRow.addView(deleteBtn, LayoutHelper.createLinear(20, 20, 0f, Gravity.CENTER_VERTICAL, 8f, 0f, 0f, 0f))

            root.addView(topRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

            val divider = View(ctx).apply { setBackgroundColor(themeColors.borderDim) }
            root.addView(divider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 0f, Gravity.NO_GRAVITY, 0f, 12f, 0f, 0f))

            root.tag = SoundItemViews(playBtn, nameTv, editBtn, deleteBtn)
            return root
        }

        inner class HeaderVH(v: View) : RecyclerView.ViewHolder(v)
        inner class EmptyVH(v: View) : RecyclerView.ViewHolder(v)

        inner class ItemVH(tileRoot: LinearLayout) : RecyclerView.ViewHolder(tileRoot) {
            private val root: LinearLayout = tileRoot
            private val views = tileRoot.tag as SoundItemViews

            fun bind(row: SoundRow.SoundItem) {
                val sound = row.item
                val ctx = root.context

                views.nameTv.text = sound.shortname
                patchPlayIcon(sound.src)

                views.playBtn.setOnClickListener { playSound(sound.src, views.playBtn) }

                if (row.canManage) {
                    views.editBtn.visibility = View.VISIBLE
                    views.deleteBtn.visibility = View.VISIBLE
                    views.editBtn.setOnClickListener { showEditDialog(sound) }
                    views.deleteBtn.setOnClickListener { confirmDelete(sound) }
                } else {
                    views.editBtn.visibility = View.GONE
                    views.deleteBtn.visibility = View.GONE
                    views.editBtn.setOnClickListener(null)
                    views.deleteBtn.setOnClickListener(null)
                }
            }

            private fun patchPlayIcon(url: String) {
                val ctx = root.context
                val playing = url == activeSoundUrl && mediaPlayer != null
                views.playBtn.setImageDrawable(
                    if (playing) {
                        MezonIcon.pauseIcon.getDrawable(ctx, themeColors.primary)
                    } else {
                        MezonIcon.playIcon.getDrawable(ctx, themeColors.colorText)
                    },
                )
            }
        }
    }
}
