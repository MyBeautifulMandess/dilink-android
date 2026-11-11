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
import com.dynamicisland.dilink.databinding.FragmentCalendarBinding
import com.dynamicisland.dilink.link.LinkService
import com.dynamicisland.dilink.planner.PlannerRecord
import com.dynamicisland.dilink.planner.PlannerRepository
import com.dynamicisland.dilink.reminder.ReminderReconciler
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Календарь: только события. Напоминания живут на отдельной вкладке, потому что
 * у них другой ритм работы — их отмечают «выполнено», а не смотрят расписание.
 */
class CalendarFragment : Fragment() {

    private var _binding: FragmentCalendarBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: PlannerAdapter
    private var stopObserving: (() -> Unit)? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentCalendarBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        adapter = PlannerAdapter(
            onDone = { _, _ -> },
            onRemove = { record -> remove(record) }
        )
        binding.plannerList.layoutManager = LinearLayoutManager(requireContext())
        binding.plannerList.adapter = adapter
        binding.addEvent.setOnClickListener { createDialog() }
        stopObserving = PlannerRepository.observe { com.dynamicisland.dilink.util.onMain { render() } }
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun store() = PlannerRepository.get(requireContext())

    private fun render() {
        val items = store().events()
        adapter.submit(items)
        binding.plannerEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        val connected = LinkService.state.get() == com.dynamicisland.dilink.link.LinkState.CONNECTED
        binding.plannerStatus.text = if (connected) {
            "${items.size} событий · синхронизировано"
        } else {
            "${items.size} событий · уйдут при подключении"
        }
    }

    private fun remove(record: PlannerRecord) {
        val grave = store().delete(record.id) ?: return
        LinkService.instance?.pushPlanner(grave)
        ReminderReconciler.sync(requireContext())
        render()
    }

    /** Событие: название, начало и длительность в один час. */
    private fun createDialog() {
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
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.HOUR_OF_DAY, 1)
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
            .setTitle(R.string.planner_dialog_event)
            .setView(wrapper)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val text = title.text.toString().trim().ifEmpty { "Без названия" }
                val start = calendar.timeInMillis
                val saved = store().upsert(
                    PlannerRecord(
                        id = "",
                        kind = PlannerRecord.KIND_EVENT,
                        title = text,
                        start = start,
                        end = start + 60 * 60 * 1000,
                        reminderMinutes = 10
                    )
                )
                LinkService.instance?.pushPlanner(saved)
                render()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun format(millis: Long): String =
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(millis))

    override fun onDestroyView() {
        stopObserving?.invoke()
        super.onDestroyView()
        _binding = null
    }
}
