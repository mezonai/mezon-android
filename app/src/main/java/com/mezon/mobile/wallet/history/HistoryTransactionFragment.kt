package com.mezon.mobile.wallet.history

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mmn.IndexerClient
import com.mezon.mmn.Transaction
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.profile.UserController
import com.mezon.mobile.wallet.WalletController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

class HistoryTransactionFragment : BaseFragment() {

    private lateinit var walletController: WalletController
    private lateinit var userController: UserController
    private lateinit var userClanController: com.mezon.mobile.home.UserClanController

    private lateinit var adapter: HistoryTransactionAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var tvAccountName: TextView
    private lateinit var tvBalance: TextView
    private lateinit var tabAll: TextView
    private lateinit var tabIncoming: TextView
    private lateinit var tabOutgoing: TextView
    private lateinit var progressBar: ProgressBar

    data class TabState(
        val filter: Int,
        var transactions: List<Transaction> = emptyList(),
        var hasMore: Boolean = true,
        var isLoading: Boolean = false,
        var isInitialLoaded: Boolean = false,
        var layoutManagerState: android.os.Parcelable? = null
    )

    private val tabs = listOf(
        TabState(filter = IndexerClient.FILTER_ALL),
        TabState(filter = IndexerClient.FILTER_RECEIVED),
        TabState(filter = IndexerClient.FILTER_SENT)
    )

    private var activeTabIndex = 0
    private var currentAddress: String = ""
    private var fetchJob: Job? = null

    override fun onInject(entryPoint: FragmentEntryPoint) {
        walletController = entryPoint.walletController()
        userController = entryPoint.userController()
        userClanController = entryPoint.userClanController()
    }

    override fun createView(context: Context): View {
        actionBar = createActionBar(context).apply {
            setBackButtonImage(R.drawable.ic_arrow_back)
            setTitle(getString(R.string.profile_history_transaction))
            setMenuOnItemClick(object : com.mezon.mobile.ui.cells.ActionBarView.ActionBarMenuOnItemClick() {
                override fun onItemClick(id: Int) {
                    if (id == -1) {
                        finishFragment()
                    }
                }
            })
        }

        val rootLinear = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(com.mezon.mobile.core.ThemeColors.instance.background)
        }
        rootLinear.addView(actionBar, com.mezon.mobile.core.LayoutHelper.createLinear(com.mezon.mobile.core.LayoutHelper.MATCH_PARENT, com.mezon.mobile.core.LayoutHelper.WRAP_CONTENT))

        val view = android.view.LayoutInflater.from(context).inflate(R.layout.fragment_history_transaction, null, false)
        rootLinear.addView(view, com.mezon.mobile.core.LayoutHelper.createLinear(com.mezon.mobile.core.LayoutHelper.MATCH_PARENT, 0, 1f))
        
        fragmentView = rootLinear

        val themeColors = com.mezon.mobile.core.ThemeColors.instance
        view.setBackgroundColor(themeColors.background)
        val baseColor = themeColors.primaryContainer
        val midColor = themeColors.surfaceVariant
        val lightColor = themeColors.secondaryLight
        view.findViewById<android.view.View>(R.id.walletCard).background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(baseColor, midColor, lightColor)
        ).apply {
            cornerRadius = com.mezon.mobile.core.LayoutHelper.dp(16f).toFloat()
        }
        view.findViewById<android.view.View>(R.id.tabContainer).background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = com.mezon.mobile.core.LayoutHelper.dp(12f).toFloat()
            setColor(themeColors.surfaceVariant)
        }

        tvAccountName = view.findViewById(R.id.tvAccountName)
        tvBalance = view.findViewById(R.id.tvBalance)
        tabAll = view.findViewById(R.id.tabAll)
        tabIncoming = view.findViewById(R.id.tabIncoming)
        tabOutgoing = view.findViewById(R.id.tabOutgoing)
        recyclerView = view.findViewById(R.id.recyclerView)
        progressBar = view.findViewById(R.id.progressBar)

        tvAccountName.setTextColor(themeColors.onSurface)
        tvBalance.setTextColor(themeColors.onSurface)
        view.findViewById<android.widget.TextView>(R.id.tvAccountLabel).setTextColor(themeColors.onSurfaceVariant)
        view.findViewById<android.widget.TextView>(R.id.tvBalanceLabel).setTextColor(themeColors.onSurfaceVariant)

        setupRecyclerView(context)
        setupTabs()

        tvAccountName.text = userController.displayName.ifEmpty { userController.username }

        fragmentScope.launch(Dispatchers.Main) {
            walletController.walletDetail.collect { detail ->
                if (detail != null) {
                    currentAddress = detail.address
                    tvBalance.text = formatAmount(requireContext(), detail.balance)
                    adapter.currentWalletAddress = currentAddress
                    adapter.notifyDataSetChanged()
                    
                    if (!tabs[0].isInitialLoaded) {
                        loadTransactions(0)
                    }
                }
            }
        }

        return fragmentView!!
    }

    private fun setupRecyclerView(context: Context) {
        val layoutManager = LinearLayoutManager(context)
        recyclerView.layoutManager = layoutManager
        
        adapter = HistoryTransactionAdapter(currentAddress) { transaction ->
            val modal = TransactionDetailModal(
                requireContext(),
                transaction.hash,
                walletController,
                userController,
                userClanController,
                currentAddress
            )
            modal.show()
        }
        recyclerView.adapter = adapter

        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (dy > 0) {
                    val visibleItemCount = layoutManager.childCount
                    val totalItemCount = layoutManager.itemCount
                    val pastVisibleItems = layoutManager.findFirstVisibleItemPosition()

                    if ((visibleItemCount + pastVisibleItems) >= totalItemCount - 5) {
                        loadMore()
                    }
                }
            }
        })
    }

    private fun setupTabs() {
        tabAll.setOnClickListener { setActiveTab(0) }
        tabIncoming.setOnClickListener { setActiveTab(1) }
        tabOutgoing.setOnClickListener { setActiveTab(2) }
        updateTabUI(activeTabIndex)
    }

    private fun updateTabUI(activeIndex: Int) {
        val themeColors = com.mezon.mobile.core.ThemeColors.instance
        val selectedBg = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = com.mezon.mobile.core.LayoutHelper.dp(12f).toFloat()
            setColor(themeColors.surface)
        }
        val selectedColor = themeColors.onSurface
        val unselectedColor = themeColors.onSurfaceVariant

        tabAll.background = if (activeIndex == 0) selectedBg else null
        tabAll.setTextColor(if (activeIndex == 0) selectedColor else unselectedColor)
        tabAll.typeface = if (activeIndex == 0) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT

        tabIncoming.background = if (activeIndex == 1) selectedBg else null
        tabIncoming.setTextColor(if (activeIndex == 1) selectedColor else unselectedColor)
        tabIncoming.typeface = if (activeIndex == 1) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT

        tabOutgoing.background = if (activeIndex == 2) selectedBg else null
        tabOutgoing.setTextColor(if (activeIndex == 2) selectedColor else unselectedColor)
        tabOutgoing.typeface = if (activeIndex == 2) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
    }

    private fun setActiveTab(index: Int) {
        if (activeTabIndex == index) return
        
        tabs[activeTabIndex].layoutManagerState = recyclerView.layoutManager?.onSaveInstanceState()
        
        activeTabIndex = index
        updateTabUI(index)
        
        val tabState = tabs[index]
        adapter.submitList(tabState.transactions) {
            tabState.layoutManagerState?.let { state ->
                recyclerView.layoutManager?.onRestoreInstanceState(state)
            }
        }
        
        fetchJob?.cancel()
        
        if (!tabState.isInitialLoaded && !tabState.isLoading) {
            fetchJob = fragmentScope.launch(Dispatchers.Main) {
                delay(300)
                loadTransactions(index)
            }
        }
        
        if (tabState.isLoading && tabState.transactions.isEmpty()) {
            progressBar.visibility = android.view.View.VISIBLE
        } else {
            progressBar.visibility = android.view.View.GONE
        }
    }

    private fun loadMore() {
        val tabState = tabs[activeTabIndex]
        if (tabState.isLoading || !tabState.hasMore) return
        loadTransactions(activeTabIndex, isLoadMore = true)
    }

    private fun loadTransactions(tabIndex: Int, isLoadMore: Boolean = false) {
        if (currentAddress.isEmpty()) return
        val tabState = tabs[tabIndex]
        if (tabState.isLoading) return
        tabState.isLoading = true
        
        if (activeTabIndex == tabIndex) {
            progressBar.visibility = if (tabState.transactions.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        }

        fragmentScope.launch(Dispatchers.Main) {
            try {
                var timestampLt: String? = null
                var lastHash: String? = null
                
                if (isLoadMore && tabState.transactions.isNotEmpty()) {
                    val lastTx = tabState.transactions.last()
                    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                    sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                    timestampLt = sdf.format(java.util.Date(lastTx.transactionTimestamp * 1000L))
                    lastHash = lastTx.hash
                }

                val response = walletController.indexer.getTransactionsByWalletBeforeTimestamp(
                    wallet = currentAddress,
                    filter = tabState.filter,
                    limit = 20,
                    timestampLt = timestampLt,
                    lastHash = lastHash
                )

                val newTransactions = if (isLoadMore) {
                    tabState.transactions + (response.data ?: emptyList())
                } else {
                    response.data ?: emptyList()
                }

                tabState.transactions = newTransactions
                tabState.hasMore = response.meta?.hasMore ?: false
                tabState.isInitialLoaded = true

            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                tabState.isLoading = false
                if (activeTabIndex == tabIndex) {
                    adapter.submitList(tabState.transactions)
                    progressBar.visibility = View.GONE
                }
            }
        }
    }

    companion object {
        fun formatAmount(context: Context, raw: String, decimals: Int = 6): String {
            val symbol = context.getString(R.string.send_token_currency_symbol)
            if (raw.isEmpty()) return "0 $symbol"
            return try {
                val rawDecimal = java.math.BigDecimal(raw)
                val divisor = java.math.BigDecimal.TEN.pow(decimals)
                val result = rawDecimal.divide(divisor)
                val symbols = java.text.DecimalFormatSymbols(java.util.Locale("vi", "VN"))
                val df = java.text.DecimalFormat("#,##0.######", symbols)
                "${df.format(result)} $symbol"
            } catch (e: Exception) {
                "0 $symbol"
            }
        }
    }
}
