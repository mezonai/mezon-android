package com.mezon.mobile.home.profile

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.R
import com.mezon.mobile.auth.AuthRepository
import com.mezon.mobile.core.AlertsCreator
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.RecyclerListView
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.ui.cells.HeaderCell
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.cells.ShadowSectionCell
import com.mezon.mobile.ui.cells.TextSettingsCell
import kotlinx.coroutines.launch

class AccountSettingFragment : BaseFragment() {

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_ITEM = 1
        private const val VIEW_TYPE_SHADOW = 2
    }

    private lateinit var accountController: AccountController
    private lateinit var userController: UserController
    private lateinit var authRepository: AuthRepository

    var onNavigateUpdateEmail: ((currentEmail: String) -> Unit)? = null
    var onNavigateUpdatePhone: ((currentPhone: String) -> Unit)? = null
    var onNavigateBlockedUsers: (() -> Unit)? = null

    private var rowCount = 0
    private var headerAccountInfoRow = -1
    private var usernameRow = -1
    private var displayNameRow = -1
    private var emailRow = -1
    private var phoneRow = -1
    private var shadowAccountRow = -1
    private var headerUsersRow = -1
    private var blockedUsersRow = -1
    private var shadowUsersRow = -1
    private var headerManagementRow = -1
    private var setPasswordRow = -1
    private var deleteAccountRow = -1
    private var shadowManagementRow = -1

    private lateinit var listView: RecyclerListView
    private lateinit var listAdapter: ListAdapter
    private var pendingInterfaceMask = 0
    private var pendingFullRefresh = false

    override fun onInject(entryPoint: FragmentEntryPoint) {
        accountController = entryPoint.accountController()
        userController = entryPoint.userController()
        authRepository = entryPoint.authRepository()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()

        observe(NotificationCenter.accountInfoLoaded) { _, _, _ ->
            if (fragmentView != null) updateRows()
        }
        observe(NotificationCenter.updateInterfaces) { _, _, args ->
            if (fragmentView == null) return@observe
            val mask = args.firstOrNull() as? Int ?: 0
            if (isPaused) {
                if (mask == 0) pendingFullRefresh = true
                else pendingInterfaceMask = pendingInterfaceMask or mask
                return@observe
            }
            updateVisibleRows(mask)
        }
        observe(NotificationCenter.themeChanged) { _, _, _ ->
            if (fragmentView == null) return@observe
            fragmentView?.setBackgroundColor(themeColors.background)
            listAdapter.notifyDataSetChanged()
        }

        accountController.loadAccount()
        return true
    }

    override fun createView(context: Context): View {
        listAdapter = ListAdapter()
        listView = RecyclerListView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = listAdapter
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            setPadding(0, LayoutHelper.dp(16), 0, LayoutHelper.dp(16))
            clipToPadding = false
            setBackgroundColor(themeColors.background)
            setOnItemClickListener(RecyclerListView.OnItemClickListener { _, position ->
                onItemClick(position)
            })
            itemAnimator = null
        }

        updateRows()
        return wrapWithActionBar(getString(R.string.account_settings_title), listView)
    }

    override fun onBecomeFullyVisible() {
        super.onBecomeFullyVisible()
        if (fragmentView == null) return
        if (pendingFullRefresh) {
            pendingFullRefresh = false
            pendingInterfaceMask = 0
            updateVisibleRows(0)
            return
        }
        if (pendingInterfaceMask != 0) {
            val mask = pendingInterfaceMask
            pendingInterfaceMask = 0
            updateVisibleRows(mask)
        }
    }

    private fun updateVisibleRows(mask: Int) {
        if (mask == 0) {
            updateRows()
            return
        }
        if ((mask and NotificationCenter.UPDATE_MASK_NAME) != 0) {
            if (usernameRow >= 0) listAdapter.notifyItemChanged(usernameRow)
            if (displayNameRow >= 0) listAdapter.notifyItemChanged(displayNameRow)
        }
    }

    private fun updateRows() {
        rowCount = 0
        headerAccountInfoRow = rowCount++
        usernameRow = rowCount++
        displayNameRow = rowCount++
        emailRow = rowCount++
        phoneRow = rowCount++
        headerUsersRow = rowCount++
        blockedUsersRow = rowCount++
        headerManagementRow = rowCount++
        setPasswordRow = rowCount++
        deleteAccountRow = rowCount++
        listAdapter.notifyDataSetChanged()
    }

    private fun onItemClick(position: Int) {
        when (position) {
            usernameRow, displayNameRow -> presentFragment(EditProfileFragment())
            emailRow -> {
                val email = accountController.accountInfo.value.email
                onNavigateUpdateEmail?.invoke(email)
                    ?: presentFragment(UpdateEmailFragment.newInstance(email))
            }
            phoneRow -> {
                val phone = accountController.accountInfo.value.phoneNumber
                onNavigateUpdatePhone?.invoke(phone)
                    ?: presentFragment(UpdatePhoneFragment.newInstance(phone))
            }
            blockedUsersRow -> {
                onNavigateBlockedUsers?.invoke() ?: presentFragment(BlockedUsersFragment())
            }
            setPasswordRow -> {
                val email = accountController.accountInfo.value.email
                if (email.isEmpty()) {
                    AlertsCreator.createSimpleAlert(
                        requireContext(),
                        getString(R.string.account_set_password_link_email_required_title),
                        getString(R.string.account_set_password_link_email_required_desc),
                        getString(R.string.account_go_to_email)
                    ) { onNavigateUpdateEmail?.invoke("") ?: presentFragment(UpdateEmailFragment.newInstance("")) }.show()
                } else {
                    presentFragment(SetPasswordFragment())
                }
            }
            deleteAccountRow -> confirmDeleteAccount()
        }
    }

    private fun confirmDeleteAccount() {
        AlertsCreator.createConfirmDialog(
            requireContext(),
            getString(R.string.account_delete_title),
            getString(R.string.account_delete_description),
            confirmText = getString(R.string.account_delete_confirm),
            cancelText = getString(R.string.common_cancel),
            destructive = true
        ) {
            accountController.deleteAccount { success ->
                if (success) {
                    fragmentScope.launch {
                        authRepository.logout()
                        notificationCenter.postNotificationOnMainThread(NotificationCenter.appDidLogout)
                    }
                } else {
                    AlertsCreator.showSimpleAlert(
                        requireContext(),
                        getString(R.string.common_error),
                        getString(R.string.account_delete_error)
                    )
                }
            }
        }.show()
    }

    private fun maskEmail(email: String): String {
        if (email.isEmpty()) return ""
        val at = email.indexOf('@')
        if (at <= 1) return email
        return email[0] + "*".repeat(at - 1) + email.substring(at)
    }

    private fun maskPhone(phone: String): String {
        if (phone.length < 6) return phone
        return phone.take(3) + "****" + phone.takeLast(3)
    }

    private inner class ListAdapter : RecyclerListView.SelectionAdapter() {

        override fun isEnabled(holder: RecyclerView.ViewHolder): Boolean {
            val type = getItemViewType(holder.adapterPosition)
            return type == VIEW_TYPE_ITEM
        }

        override fun getItemCount() = rowCount

        override fun getItemViewType(position: Int) = when (position) {
            headerAccountInfoRow, headerUsersRow, headerManagementRow -> VIEW_TYPE_HEADER
            shadowAccountRow, shadowUsersRow, shadowManagementRow -> VIEW_TYPE_SHADOW
            else -> VIEW_TYPE_ITEM
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view: View = when (viewType) {
                VIEW_TYPE_HEADER -> HeaderCell(parent.context, themeColors)
                VIEW_TYPE_SHADOW -> ShadowSectionCell(parent.context, themeColors)
                else -> TextSettingsCell(parent.context, themeColors)
            }
            if (view.layoutParams == null) {
                view.layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                )
            }
            return object : RecyclerView.ViewHolder(view) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val info = accountController.accountInfo.value
            when (getItemViewType(position)) {
                VIEW_TYPE_HEADER -> {
                    val cell = holder.itemView as HeaderCell
                    cell.textView.setTextColor(themeColors.onSurfaceVariant)
                    cell.setTopPadding(if (position == headerAccountInfoRow) 0 else 16)
                    cell.setSideMargin(16) 
                    when (position) {
                        headerAccountInfoRow -> cell.setText(getString(R.string.account_info_title))
                        headerUsersRow -> cell.setText(getString(R.string.account_users_title))
                        headerManagementRow -> cell.setText(getString(R.string.account_management_title))
                    }
                }
                VIEW_TYPE_ITEM -> {
                    val cell = holder.itemView as TextSettingsCell
                    cell.setTitleColor(0)
                    cell.setTitleBold(true)
                    cell.setIcon(null)
                    when (position) {
                        usernameRow -> {
                            cell.setTextAndValue(getString(R.string.account_username), userController.username.ifEmpty { info.username }, divider = true)
                            cell.setBackgroundType(TextSettingsCell.BG_TYPE_TOP)
                            cell.setCanClick(true)
                            cell.setWarn(false)
                        }
                        displayNameRow -> {
                            cell.setTextAndValue(getString(R.string.account_display_name), userController.displayName.ifEmpty { info.displayName }, divider = true)
                            cell.setBackgroundType(TextSettingsCell.BG_TYPE_MIDDLE)
                            cell.setCanClick(true)
                            cell.setWarn(false)
                        }
                        emailRow -> {
                            val emailValue = userController.email.ifEmpty { info.email }
                            val isUnlinked = emailValue.isEmpty()
                            val emailDesc = if (isUnlinked) getString(R.string.account_link_email) else maskEmail(emailValue)
                            cell.setTextAndValue(getString(R.string.account_email), emailDesc, divider = true)
                            cell.setBackgroundType(TextSettingsCell.BG_TYPE_MIDDLE)
                            cell.setCanClick(true)
                            cell.setWarn(isUnlinked)
                        }
                        phoneRow -> {
                            val phoneValue = userController.phoneNumber.ifEmpty { info.phoneNumber }
                            val isUnlinked = phoneValue.isEmpty()
                            val phoneDesc = if (isUnlinked) getString(R.string.account_link_phone) else maskPhone(phoneValue)
                            cell.setTextAndValue(getString(R.string.account_phone), phoneDesc)
                            cell.setBackgroundType(TextSettingsCell.BG_TYPE_BOTTOM)
                            cell.setCanClick(true)
                            cell.setWarn(isUnlinked)
                        }
                        blockedUsersRow -> {
                            cell.setTextAndValue(getString(R.string.account_blocked_users))
                            cell.setBackgroundType(TextSettingsCell.BG_TYPE_ISOLATED)
                            cell.setCanClick(true)
                            cell.setWarn(false)
                        }
                        setPasswordRow -> {
                            cell.setTextAndValue(getString(R.string.account_set_password), divider = true)
                            cell.setBackgroundType(TextSettingsCell.BG_TYPE_TOP)
                            cell.setCanClick(true)
                            cell.setWarn(false)
                        }
                        deleteAccountRow -> {
                            cell.setTextAndValue(getString(R.string.account_delete_account))
                            cell.setBackgroundType(TextSettingsCell.BG_TYPE_BOTTOM)
                            cell.setTitleColor(themeColors.error)
                            cell.setCanClick(true)
                            cell.setWarn(false)
                        }
                    }
                }
            }
        }
    }
}
