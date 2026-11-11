package com.dynamicisland.dilink.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.dynamicisland.dilink.databinding.ItemTransferBinding
import com.dynamicisland.dilink.link.FileTransfer

/** Строка передачи: имя, процент и тонкая полоса прогресса. */
class TransferAdapter : RecyclerView.Adapter<TransferAdapter.Holder>() {

    private val items = ArrayList<FileTransfer.Progress>()

    fun submit(list: List<FileTransfer.Progress>) {
        items.clear()
        // Новые передачи сверху: свежая отправка должна быть на виду.
        items.addAll(list.asReversed())
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemTransferBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    class Holder(private val binding: ItemTransferBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: FileTransfer.Progress) {
            binding.transferName.text = item.name.ifEmpty { "Передача" }
            binding.transferBar.progress = item.percent
            binding.transferPercent.text = when {
                item.failed != null -> item.failed
                item.done -> if (item.incoming) "получен" else "отправлен"
                else -> "${item.percent}%"
            }
        }
    }
}
