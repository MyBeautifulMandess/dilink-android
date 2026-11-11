package com.dynamicisland.dilink.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.dynamicisland.dilink.R
import com.dynamicisland.dilink.databinding.ItemPlannerBinding
import com.dynamicisland.dilink.planner.PlannerRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Карточка записи: вид, время, название и действия. */
class PlannerAdapter(
    private val onDone: (PlannerRecord, Boolean) -> Unit,
    private val onRemove: (PlannerRecord) -> Unit
) : RecyclerView.Adapter<PlannerAdapter.Holder>() {

    private val items = ArrayList<PlannerRecord>()
    private val format = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())

    fun submit(list: List<PlannerRecord>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemPlannerBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.bind(item)
        holder.binding.done.setOnCheckedChangeListener(null)
        holder.binding.done.isChecked = item.done
        holder.binding.done.visibility = if (item.isEvent) View.GONE else View.VISIBLE
        holder.binding.done.setOnCheckedChangeListener { _, checked ->
            if (checked != item.done) onDone(item, checked)
        }
        holder.binding.remove.setOnClickListener { onRemove(item) }
        // Удалённые записи остаются в хранилище надгробием, но в списке их нет.
        holder.binding.root.alpha = if (item.done) 0.55f else 1f
    }

    class Holder(val binding: ItemPlannerBinding) : RecyclerView.ViewHolder(binding.root) {
        private val format = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())

        fun bind(item: PlannerRecord) {
            binding.kind.text = binding.root.context.getString(
                if (item.isEvent) R.string.planner_kind_event else R.string.planner_kind_reminder
            )
            binding.title.text = item.title
            binding.title.paintFlags = if (item.done) {
                binding.title.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                binding.title.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
            }
            binding.whenTime.text = format.format(Date(item.anchor))
        }
    }
}
