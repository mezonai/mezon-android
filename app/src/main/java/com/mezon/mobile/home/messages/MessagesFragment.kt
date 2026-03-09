package com.mezon.mobile.home.messages

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.home.DialogsController
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MessagesFragment : BaseFragment() {

    @Inject lateinit var controller: DialogsController

    private lateinit var recyclerView: RecyclerView
    private lateinit var loadingView: ProgressBar
    private lateinit var emptyView: TextView
    private lateinit var errorView: TextView
    private lateinit var adapter: DmListAdapter

    var onOpenChat: ((channelId: Long, channelName: String, clanId: Long, channelType: Int) -> Unit)? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = FrameLayout(requireContext()).apply {
            setBackgroundColor(themeColors.background)
        }

        recyclerView = RecyclerView(requireContext()).apply {
            layoutManager = LinearLayoutManager(context)
            setHasFixedSize(true)
            visibility = View.GONE
        }
        root.addView(recyclerView, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT
        ))

        loadingView = ProgressBar(requireContext()).apply {
            visibility = View.GONE
        }
        root.addView(loadingView, LayoutHelper.createFrame(48, 48, Gravity.CENTER))

        emptyView = TextView(requireContext()).apply {
            text = getString(com.mezon.mobile.R.string.dm_no_messages)
            setTextColor(themeColors.onSurfaceVariant)
            textSize = 16f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(emptyView, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT
        ))

        errorView = TextView(requireContext()).apply {
            setTextColor(themeColors.error)
            textSize = 14f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(errorView, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT
        ))

        adapter = DmListAdapter(themeColors) { dm ->
            onOpenChat?.invoke(dm.channelId, dm.displayName.ifEmpty { dm.label }, 0L, dm.type)
        }
        recyclerView.adapter = adapter

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        updateDialogsList()
        controller.loadDialogs()

        observe(NotificationCenter.themeChanged) { _, _ ->
            view.setBackgroundColor(themeColors.background)
            emptyView.setTextColor(themeColors.onSurfaceVariant)
            adapter.notifyDataSetChanged()
        }

        observe(NotificationCenter.dialogsNeedReload) { _, _ ->
            updateDialogsList()
        }

        observe(NotificationCenter.onlineStatusChanged) { _, _ ->
            adapter.updateVisibleRows(recyclerView, controller.getDialogs())
        }

        observe(NotificationCenter.dialogsLoadError) { _, args ->
            val list = controller.getDialogs()
            if (list.isEmpty()) {
                showError(args.firstOrNull() as? String ?: "Failed to load")
            }
        }
    }

    private fun updateDialogsList() {
        val list = controller.getDialogs()
        when {
            list.isNotEmpty() -> showList(list)
            !controller.dialogsLoaded -> showLoading()
            else -> showEmpty()
        }
    }

    private fun showLoading() {
        loadingView.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE
        emptyView.visibility = View.GONE
        errorView.visibility = View.GONE
    }

    private fun showEmpty() {
        loadingView.visibility = View.GONE
        recyclerView.visibility = View.GONE
        emptyView.visibility = View.VISIBLE
        errorView.visibility = View.GONE
    }

    private fun showError(message: String) {
        loadingView.visibility = View.GONE
        recyclerView.visibility = View.GONE
        emptyView.visibility = View.GONE
        errorView.visibility = View.VISIBLE
        errorView.text = message
    }

    private fun showList(messages: List<DirectMessage>) {
        loadingView.visibility = View.GONE
        recyclerView.visibility = View.VISIBLE
        emptyView.visibility = View.GONE
        errorView.visibility = View.GONE
        adapter.setData(messages)
    }
}
