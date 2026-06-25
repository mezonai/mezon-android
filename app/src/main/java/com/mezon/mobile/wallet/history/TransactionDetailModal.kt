package com.mezon.mobile.wallet.history

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.mezon.mmn.Transaction
import com.mezon.mobile.R
import android.app.Dialog
import com.mezon.mobile.home.profile.UserController
import com.mezon.mobile.ui.cells.ToastOverlay
import com.mezon.mobile.wallet.WalletController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import dagger.hilt.android.EntryPointAccessors
import com.mezon.mobile.di.FragmentEntryPoint
class TransactionDetailModal(
    ctx: Context,
    private val transactionHash: String,
    private val walletController: WalletController,
    private val userController: UserController,
    private val userClanController: com.mezon.mobile.home.UserClanController,
    private val currentAddress: String
) : Dialog(ctx) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val messagesController = EntryPointAccessors.fromApplication(
        ctx.applicationContext,
        FragmentEntryPoint::class.java
    ).messagesController()

    private val friendController = EntryPointAccessors.fromApplication(
        ctx.applicationContext,
        FragmentEntryPoint::class.java
    ).friendController()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.bottom_sheet_transaction_detail, null, false)
        
        view.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = com.mezon.mobile.core.LayoutHelper.dp(16f).toFloat()
            setColor(com.mezon.mobile.core.ThemeColors.instance.surfaceVariant)
        }

        val btnClose = view.findViewById<ImageView>(R.id.btnClose)
        val btnCopy = view.findViewById<ImageView>(R.id.btnCopy)
        val tvIdValue = view.findViewById<TextView>(R.id.tvIdValue)
        val tvTimeValue = view.findViewById<TextView>(R.id.tvTimeValue)
        val tvSenderValue = view.findViewById<TextView>(R.id.tvSenderValue)
        val tvReceiverValue = view.findViewById<TextView>(R.id.tvReceiverValue)
        val tvAmountDetailValue = view.findViewById<TextView>(R.id.tvAmountDetailValue)
        val tvNoteValue = view.findViewById<TextView>(R.id.tvNoteValue)

        val themeColors = com.mezon.mobile.core.ThemeColors.instance
        view.findViewById<TextView>(R.id.tvIdLabel).setTextColor(themeColors.onSurface)
        tvIdValue.setTextColor(themeColors.onSurfaceVariant)
        view.findViewById<TextView>(R.id.tvTimeLabel).setTextColor(themeColors.onSurface)
        tvTimeValue.setTextColor(themeColors.onSurfaceVariant)
        view.findViewById<TextView>(R.id.tvSenderLabel).setTextColor(themeColors.onSurface)
        tvSenderValue.setTextColor(themeColors.onSurfaceVariant)
        view.findViewById<TextView>(R.id.tvReceiverLabel).setTextColor(themeColors.onSurface)
        tvReceiverValue.setTextColor(themeColors.onSurfaceVariant)
        view.findViewById<TextView>(R.id.tvAmountDetailLabel).setTextColor(themeColors.onSurface)
        tvAmountDetailValue.setTextColor(themeColors.onSurfaceVariant)
        view.findViewById<TextView>(R.id.tvNoteLabel).setTextColor(themeColors.onSurface)
        tvNoteValue.setTextColor(themeColors.onSurfaceVariant)
        view.findViewById<TextView>(R.id.tvSheetTitle).setTextColor(themeColors.onSurface)
        btnClose.setColorFilter(themeColors.onSurfaceVariant)
        btnCopy.setColorFilter(themeColors.onSurfaceVariant)
        view.findViewById<View>(R.id.divider).setBackgroundColor(themeColors.outlineVariant)

        btnClose.setOnClickListener { dismiss() }

        btnCopy.setOnClickListener {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Transaction Hash", tvIdValue.text)
            clipboard.setPrimaryClip(clip)
            val themeColors = com.mezon.mobile.core.ThemeColors.instance
            
            val activity = com.mezon.mobile.core.AndroidUtilities.findActivity(context)
            val parentView = activity?.findViewById<android.view.ViewGroup>(android.R.id.content) 
                ?: view as android.view.ViewGroup
                
            ToastOverlay(context, themeColors).show(
                parentView,
                ToastOverlay.ToastType.SUCCESS,
                context.getString(R.string.invite_copied)
            )
        }

        scope.launch {
            try {
                val tx = walletController.indexer.getTransactionByHash(transactionHash)
                if (tx != null) {
                    tvIdValue.text = tx.hash
                    
                    if (tx.transactionTimestamp > 0) {
                        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                        tvTimeValue.text = sdf.format(Date(tx.transactionTimestamp * 1000L))
                    } else {
                        tvTimeValue.text = "--/--/---- --:--"
                    }

                    tvSenderValue.text = resolveName(tx, isSender = true)
                    tvReceiverValue.text = resolveName(tx, isSender = false)
                    
                    tvAmountDetailValue.text = HistoryTransactionFragment.formatAmount(context, tx.value)
                    
                    val note = tx.textData.ifEmpty { context.getString(R.string.advanced_transfer_funds) }
                    tvNoteValue.text = note
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        setContentView(view)
        window?.let { w ->
            w.setBackgroundDrawableResource(android.R.color.transparent)
            val lp = w.attributes
            lp.width = (context.resources.displayMetrics.widthPixels * 0.9f).toInt()
            lp.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT
            lp.gravity = android.view.Gravity.CENTER
            w.attributes = lp
        }
    }

    override fun dismiss() {
        super.dismiss()
        scope.cancel()
    }

    private data class ExtraIds(
        val senderId: Long? = null,
        val receiverId: Long? = null,
        val senderUsername: String? = null,
        val receiverUsername: String? = null
    )

    private fun extractExtraInfo(extraInfo: String?): ExtraIds {
        if (extraInfo.isNullOrEmpty()) return ExtraIds()
        return try {
            val json = JSONObject(extraInfo)
            val senderId = json.optLong("UserSenderId", -1L).takeIf { it != -1L }
                ?: json.optString("UserSenderId", "").toLongOrNull()
            val receiverId = json.optLong("UserReceiverId", -1L).takeIf { it != -1L }
                ?: json.optString("UserReceiverId", "").toLongOrNull()
            val senderUsername = json.optString("UserSenderUsername", "").takeIf { it.isNotEmpty() }
            val receiverUsername = json.optString("UserReceiverUsername", "").takeIf { it.isNotEmpty() }
            ExtraIds(senderId, receiverId, senderUsername, receiverUsername)
        } catch (e: Exception) {
            ExtraIds()
        }
    }

    private fun resolveName(tx: Transaction, isSender: Boolean): String {
        val addressToCheck = if (isSender) tx.fromAddress else tx.toAddress

        if (addressToCheck.equals(currentAddress, ignoreCase = true)) {
            val currentName = userController.displayName.ifEmpty { userController.username }
            if (currentName.isNotEmpty()) return currentName
        }

        val fallbackName = if (isSender) {
            tx.fromUsername?.takeIf { it.isNotEmpty() }
                ?: tx.senderName?.takeIf { it.isNotEmpty() }
                ?: tx.fromUser?.username?.takeIf { it.isNotEmpty() }
                ?: tx.fromUser?.name?.takeIf { it.isNotEmpty() }
                ?: addressToCheck
        } else {
            tx.toUsername?.takeIf { it.isNotEmpty() }
                ?: tx.receiverName?.takeIf { it.isNotEmpty() }
                ?: tx.toUser?.username?.takeIf { it.isNotEmpty() }
                ?: tx.toUser?.name?.takeIf { it.isNotEmpty() }
                ?: addressToCheck
        }

        var finalName = fallbackName

        val extra = extractExtraInfo(tx.extraInfo)
        val targetId = if (isSender) extra.senderId else extra.receiverId
        val extraUsername = if (isSender) extra.senderUsername else extra.receiverUsername

        if (targetId != null) {
            val clanUser = userClanController.getUserById(targetId)
            val globalUser = messagesController.getUser(targetId)
            val friendUser = friendController.findFriendByUserId(targetId)
            
            if (clanUser != null) {
                val dbName = clanUser.displayName.ifEmpty { clanUser.username }
                if (dbName.isNotEmpty()) {
                    finalName = dbName
                }
            } else if (globalUser != null) {
                val dbName = globalUser.displayName.ifEmpty { globalUser.username }
                if (dbName.isNotEmpty()) {
                    finalName = dbName
                }
            } else if (friendUser != null) {
                val dbName = friendUser.user.displayName.ifEmpty { friendUser.user.username }
                if (dbName.isNotEmpty()) {
                    finalName = dbName
                }
            } else if (!extraUsername.isNullOrEmpty()) {
                finalName = extraUsername
            }
        } else if (!extraUsername.isNullOrEmpty()) {
            finalName = extraUsername
        }

        return finalName
    }
}
