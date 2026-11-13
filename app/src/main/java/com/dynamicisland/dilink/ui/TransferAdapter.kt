package com.dynamicisland.dilink.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.dynamicisland.dilink.R
import com.dynamicisland.dilink.databinding.ItemTransferBinding
import com.dynamicisland.dilink.link.FileTransfer

/**
 * Строка передачи: имя, процент и тонкая полоса прогресса.
 *
 * У полученного файла появляются кнопки «Открыть» и «Где лежит»: с компьютера
 * прилетает много разного, и человек должен одним касанием либо открыть файл,
 * либо посмотреть, куда он попал.
 */
class TransferAdapter(
    private val onOpen: (FileTransfer.Progress) -> Unit,
    private val onLocate: (FileTransfer.Progress) -> Unit
) : RecyclerView.Adapter<TransferAdapter.Holder>() {

    private val items = ArrayList<FileTransfer.Progress>()

    fun submit(list: List<FileTransfer.Progress>) {
        items.clear()
        // Новые передачи сверху: свежая отправка должна быть на виду.
        items.addAll(list.asReversed())
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(
            ItemTransferBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            onOpen,
            onLocate
        )

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    class Holder(
        private val binding: ItemTransferBinding,
        private val onOpen: (FileTransfer.Progress) -> Unit,
        private val onLocate: (FileTransfer.Progress) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: FileTransfer.Progress) {
            val context = binding.root.context
            binding.transferName.text = item.name.ifEmpty { "Передача" }
            binding.transferBar.progress = item.percent
            binding.transferPercent.text = when {
                item.failed != null -> item.failed
                item.done -> if (item.incoming) "получен" else "отправлен"
                else -> "${item.percent}%"
            }

            val location = item.savedLocation
            binding.transferLocation.visibility = if (item.done && !location.isNullOrEmpty()) View.VISIBLE else View.GONE
            binding.transferLocation.text = when {
                location.isNullOrEmpty() -> ""
                item.inGallery -> context.getString(R.string.transfer_saved_gallery, location, item.name)
                else -> context.getString(R.string.transfer_saved_file, location, item.name)
            }

            val actionable = item.done && item.incoming && item.savedPath != null
            binding.transferActions.visibility = if (actionable) View.VISIBLE else View.GONE
            binding.transferOpen.setOnClickListener { if (actionable) onOpen(item) }
            binding.transferLocate.setOnClickListener { if (actionable) onLocate(item) }
        }
    }
}
