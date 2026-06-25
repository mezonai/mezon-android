package com.mezon.mobile.wallet.history

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mmn.Transaction
import com.mezon.mobile.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryTransactionAdapter(
    var currentWalletAddress: String = "",
    private val onItemClick: (Transaction) -> Unit
) : ListAdapter<Transaction, HistoryTransactionAdapter.ViewHolder>(TransactionDiffCallback()) {

    private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_transaction, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val transaction = getItem(position)
        holder.bind(transaction)
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val iconContainer: View = itemView.findViewById(R.id.iconContainer)
        private val ivIcon: ImageView = itemView.findViewById(R.id.ivIcon)
        private val tvAmount: TextView = itemView.findViewById(R.id.tvAmount)
        private val tvStatus: TextView = itemView.findViewById(R.id.tvStatus)
        private val tvId: TextView = itemView.findViewById(R.id.tvId)
        private val tvTime: TextView = itemView.findViewById(R.id.tvTime)

        init {
            itemView.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(getItem(position))
                }
            }
        }

        fun bind(transaction: Transaction) {
            val isIncoming = transaction.toAddress.equals(currentWalletAddress, ignoreCase = true)
            
            itemView.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = com.mezon.mobile.core.LayoutHelper.dp(12f).toFloat()
                setColor(com.mezon.mobile.core.ThemeColors.instance.surfaceVariant)
            }

            if (isIncoming) {
                ivIcon.setImageResource(R.drawable.ic_chevron_left_24)
                ivIcon.setColorFilter(Color.parseColor("#22C55E"))
                iconContainer.background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = com.mezon.mobile.core.LayoutHelper.dp(8f).toFloat()
                    setColor(Color.parseColor("#1A22C55E"))
                }
                tvAmount.text = "+ ${HistoryTransactionFragment.formatAmount(itemView.context, transaction.value)}"
                tvAmount.setTextColor(Color.parseColor("#22C55E"))
                tvStatus.text = itemView.context.getString(R.string.transaction_status_received)
            } else {
                ivIcon.setImageResource(R.drawable.ic_chevron_right_24)
                ivIcon.setColorFilter(Color.parseColor("#EF4444"))
                iconContainer.background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = com.mezon.mobile.core.LayoutHelper.dp(8f).toFloat()
                    setColor(Color.parseColor("#1AEF4444"))
                }
                tvAmount.text = "- ${HistoryTransactionFragment.formatAmount(itemView.context, transaction.value)}"
                tvAmount.setTextColor(Color.parseColor("#EF4444"))
                tvStatus.text = itemView.context.getString(R.string.transaction_status_sent)
            }

            val themeColors = com.mezon.mobile.core.ThemeColors.instance
            tvStatus.setTextColor(themeColors.onSurfaceVariant)
            tvId.setTextColor(themeColors.onSurfaceVariant)
            tvTime.setTextColor(themeColors.onSurfaceVariant)

            val displayHash = if (transaction.hash.length > 8) {
                transaction.hash.takeLast(8)
            } else {
                transaction.hash
            }
            tvId.text = "ID: #$displayHash"

            if (transaction.transactionTimestamp > 0) {
                val date = Date(transaction.transactionTimestamp * 1000L)
                tvTime.text = dateFormat.format(date)
            } else {
                tvTime.text = "--/--/---- --:--"
            }
        }
    }

    class TransactionDiffCallback : DiffUtil.ItemCallback<Transaction>() {
        override fun areItemsTheSame(oldItem: Transaction, newItem: Transaction): Boolean {
            return oldItem.hash == newItem.hash
        }

        override fun areContentsTheSame(oldItem: Transaction, newItem: Transaction): Boolean {
            return oldItem == newItem
        }
    }
}
