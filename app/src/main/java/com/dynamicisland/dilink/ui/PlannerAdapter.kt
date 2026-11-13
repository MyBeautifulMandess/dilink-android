package com.dynamicisland.dilink.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
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
        // Выполненная запись приглушается, но остаётся читаемой.
        holder.binding.root.alpha = if (item.done) 0.55f else 1f
    }

    class Holder(val binding: ItemPlannerBinding) : RecyclerView.ViewHolder(binding.root) {

        private val dateFormat = SimpleDateFormat("dd MMM", Locale.getDefault())
        private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        fun bind(item: PlannerRecord) {
            val context = binding.root.context
            binding.kind.text = context.getString(
                if (item.isEvent) R.string.planner_kind_event else R.string.planner_kind_reminder
            )
            binding.title.text = item.title
            binding.title.paintFlags = if (item.done) {
                binding.title.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                binding.title.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
            }
            binding.whenTime.text = whenTime(item)
            binding.icon.setImageResource(if (item.isEvent) R.drawable.ic_event else R.drawable.ic_bell)
            binding.icon.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.accent)
            )
        }

        /** У события — диапазон времени, у напоминания — дата и срок. */
        private fun whenTime(item: PlannerRecord): String {
            if (!item.isEvent) {
                return "${dateFormat.format(Date(item.due))} · ${timeFormat.format(Date(item.due))}"
            }
            val start = timeFormat.format(Date(item.start))
            if (item.end <= item.start) return start
            return "$start – ${timeFormat.format(Date(item.end))}"
        }
    }
}
