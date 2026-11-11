package com.dynamicisland.dilink.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.dynamicisland.dilink.R
import com.dynamicisland.dilink.databinding.FragmentPlannerBinding
import com.dynamicisland.dilink.link.LinkService
import com.dynamicisland.dilink.planner.PlannerRecord
import com.dynamicisland.dilink.planner.PlannerStore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Календарь и напоминания телефона.
 *
 * Записи полноценно живут на телефоне: их можно создать без компьютера, они
 * сохранятся и уйдут на островок при подключении. Всё, что пришло с компьютера,
 * оказывается здесь же — слияние идёт по времени правки.
 */
class PlannerFragment : Fragment() {

    private var _binding: FragmentPlannerBinding? = null
    private val binding get() = _binding!!
    private var store: PlannerStore? = null
    private lateinit var adapter: PlannerAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentPlannerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        adapter = PlannerAdapter(
            onDone = { record, done ->
                val service = LinkService.instance
                val store = store ?: service?.plannerStore() ?: return@PlannerAdapter
                store.setDone(record.id, done)?.let { service?.pushPlanner(it) }
                render()
            },
            onRemove = { record ->
                val service = LinkService.instance
                val store = store ?: service?.plannerStore() ?: return@PlannerAdapter
                store.delete(record.id)?.let { service?.pushPlanner(it) }
                render()
            }
        )
        binding.plannerList.layoutManager = LinearLayoutManager(requireContext())
        binding.plannerList.adapter = adapter

        binding.addEvent.setOnClickListener { createDialog(PlannerRecord.KIND_EVENT) }
        binding.addReminder.setOnClickListener { createDialog(PlannerRecord.KIND_REMINDER) }
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun currentStore(): PlannerStore? {
        val found = store ?: LinkService.instance?.plannerStore()
        if (found != null && store !== found) {
            store = found
            found.onChanged = { com.dynamicisland.dilink.util.onMain { render() } }
        }
        return found
    }

    private fun render() {
        val store = currentStore() ?: run {
            binding.plannerStatus.text = getString(R.string.planner_local_only)
            return
        }
        val items = store.items()
        adapter.submit(items)
        binding.plannerEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        val connected = LinkService.state.get() == com.dynamicisland.dilink.link.LinkState.CONNECTED
        binding.plannerStatus.text = if (connected) {
            "${items.size} записей · синхронизировано с компьютером"
        } else {
            "${items.size} записей · уйдут на компьютер при подключении"
        }
    }

    /** Быстрое создание: название, дата и время — больше для поездки в одну руку не нужно. */
    private fun createDialog(kind: String) {
        val context = requireContext()
        val pad = (16 * resources.displayMetrics.density).toInt()
        val title = EditText(context).apply {
            hint = getString(R.string.planner_field_title)
            setTextColor(0xFFF2F2F5.toInt())
            setHintTextColor(0xFF9A9AA5.toInt())
        }
        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(title)
        }

        val calendar = Calendar.getInstance().apply {
            if (kind == PlannerRecord.KIND_EVENT) {
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.HOUR_OF_DAY, 1)
            } else {
                add(Calendar.HOUR_OF_DAY, 1)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
        }
        val whenText = android.widget.TextView(context).apply {
            text = format(calendar.timeInMillis)
            setTextColor(0xFF5B8CFF.toInt())
            textSize = 15f
            setPadding(0, pad / 2, 0, pad / 2)
            setOnClickListener {
                DatePickerDialog(context, { _, year, month, day ->
                    calendar.set(year, month, day)
                    TimePickerDialog(context, { _, hour, minute ->
                        calendar.set(Calendar.HOUR_OF_DAY, hour)
                        calendar.set(Calendar.MINUTE, minute)
                        text = format(calendar.timeInMillis)
                    }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), true).show()
                }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
            }
        }
        wrapper.addView(whenText)

        AlertDialog.Builder(context)
            .setTitle(
                if (kind == PlannerRecord.KIND_EVENT) R.string.planner_dialog_event
                else R.string.planner_dialog_reminder
            )
            .setView(wrapper)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val text = title.text.toString().trim().ifEmpty { "Без названия" }
                val at = calendar.timeInMillis
                val record = if (kind == PlannerRecord.KIND_EVENT) {
                    PlannerRecord(
                        id = "",
                        kind = PlannerRecord.KIND_EVENT,
                        title = text,
                        start = at,
                        end = at + 60 * 60 * 1000,
                        allDay = false,
                        reminderMinutes = 10
                    )
                } else {
                    PlannerRecord(
                        id = "",
                        kind = PlannerRecord.KIND_REMINDER,
                        title = text,
                        due = at,
                        source = "phone"
                    )
                }
                val store = currentStore() ?: return@setPositiveButton
                val saved = store.upsert(record)
                LinkService.instance?.pushPlanner(saved)
                render()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun format(millis: Long): String =
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(millis))

    override fun onDestroyView() {
        store?.onChanged = null
        super.onDestroyView()
        _binding = null
    }
}
