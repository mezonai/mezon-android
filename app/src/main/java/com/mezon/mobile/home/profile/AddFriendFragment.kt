package com.mezon.mobile.home.profile

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.RecyclerListView
import com.mezon.mobile.di.FragmentEntryPoint

class AddFriendFragment : BaseFragment() {

    private lateinit var accountController: AccountController
    private lateinit var userController: UserController
    private lateinit var listView: RecyclerListView
    private lateinit var emptyView: TextView
    private lateinit var adapter: IncomingAdapter
    private var rows: List<FriendRequestEntry> = emptyList()

    override fun onInject(entryPoint: FragmentEntryPoint) {
        accountController = entryPoint.accountController()
        userController = entryPoint.userController()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        observe(NotificationCenter.friendRequestsLoaded) { _, _, _ -> if (fragmentView != null) reloadData() }
        accountController.loadFriendRequests(noCache = true)
        return true
    }

    override fun createView(context: Context): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.background)
            setPadding(LayoutHelper.dp(16), LayoutHelper.dp(12), LayoutHelper.dp(16), LayoutHelper.dp(12))
        }

        val addRow = TextView(context).apply {
            text = getString(R.string.friends_add_by_username)
            textSize = 15f
            setPadding(LayoutHelper.dp(12), LayoutHelper.dp(12), LayoutHelper.dp(12), LayoutHelper.dp(12))
            setBackgroundColor(themeColors.getColor(com.mezon.mobile.core.ThemeColors.key_sheetItemBackground))
            setTextColor(themeColors.onSurface)
            setOnClickListener { showAddFriendModal() }
        }
        root.addView(addRow, LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        listView = RecyclerListView(context).apply {
            layoutManager = LinearLayoutManager(context)
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        adapter = IncomingAdapter()
        listView.adapter = adapter
        root.addView(listView, LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, 0, 1f).apply {
            topMargin = LayoutHelper.dp(12)
        })

        emptyView = TextView(context).apply {
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(themeColors.onSurfaceVariant)
            visibility = View.GONE
        }
        root.addView(emptyView, LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            topMargin = LayoutHelper.dp(20)
        })

        reloadData()
        return wrapWithActionBar(getString(R.string.friends_add_title), root)
    }

    private fun reloadData() {
        rows = accountController.friendRequests.value.filter { it.state == 2 }
        adapter.notifyDataSetChanged()
        val empty = rows.isEmpty()
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        listView.visibility = if (empty) View.GONE else View.VISIBLE
        emptyView.text = getString(R.string.friends_empty_received)
    }

    private fun showAddFriendModal() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.friends_add_placeholder)
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(LayoutHelper.dp(12), LayoutHelper.dp(10), LayoutHelper.dp(12), LayoutHelper.dp(10))
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.friends_add_send_request))
            .setView(input)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.common_login_send) { _, _ ->
                val raw = input.text?.toString() ?: ""
                val checkValue = raw.trim()
                if (checkValue.isEmpty()) return@setPositiveButton

                if (checkValue.equals(userController.username, ignoreCase = true)) {
                    Toast.makeText(requireContext(), getString(R.string.friends_toast_send_fail), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val existing = accountController.friendRequests.value.firstOrNull {
                    it.username.equals(checkValue, ignoreCase = true)
                }
                if (existing != null) {
                    when (existing.state) {
                        3 -> Toast.makeText(requireContext(), getString(R.string.friends_error_blocked), Toast.LENGTH_SHORT).show()
                        0 -> Toast.makeText(requireContext(), getString(R.string.friends_error_already_friend), Toast.LENGTH_SHORT).show()
                        1 -> Toast.makeText(requireContext(), getString(R.string.friends_error_wait_accept), Toast.LENGTH_SHORT).show()
                        else -> Toast.makeText(requireContext(), getString(R.string.friends_toast_send_fail), Toast.LENGTH_SHORT).show()
                    }
                    return@setPositiveButton
                }

                accountController.sendFriendRequest(raw) { success ->
                    val msgRes = if (success) R.string.friends_toast_send_success else R.string.friends_toast_send_fail
                    Toast.makeText(requireContext(), getString(msgRes), Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private inner class IncomingAdapter : RecyclerListView.SelectionAdapter() {
        override fun isEnabled(holder: RecyclerView.ViewHolder): Boolean = true
        override fun getItemCount(): Int = rows.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val v = TextView(parent.context).apply {
                textSize = 15f
                setPadding(LayoutHelper.dp(12), LayoutHelper.dp(12), LayoutHelper.dp(12), LayoutHelper.dp(12))
                setBackgroundColor(themeColors.getColor(com.mezon.mobile.core.ThemeColors.key_sheetItemBackground))
                setTextColor(themeColors.onSurface)
            }
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = rows[position]
            (holder.itemView as TextView).text = item.displayName
            holder.itemView.setOnClickListener {
                accountController.acceptFriendRequest(item) { success ->
                    if (!success) Toast.makeText(requireContext(), R.string.common_something_went_wrong, Toast.LENGTH_SHORT).show()
                }
            }
            holder.itemView.setOnLongClickListener {
                accountController.deleteFriendRelation(item) { success ->
                    if (!success) Toast.makeText(requireContext(), R.string.common_something_went_wrong, Toast.LENGTH_SHORT).show()
                }
                true
            }
        }
    }
}
