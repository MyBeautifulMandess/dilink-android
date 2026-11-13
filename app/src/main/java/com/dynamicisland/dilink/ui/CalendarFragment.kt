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
import com.dynamicisland.dilink.planner.PlannerDays
import com.dynamicisland.dilink.planner.PlannerRecord
import com.dynamicisland.dilink.planner.PlannerRepository
import com.dynamicisland.dilink.reminder.ReminderReconciler
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Календарь: месячная сетка и события выбранного дня.
 *
 * Напоминания живут на отдельной вкладке, потому что у них другой ритм работы
 * — их отмечают «выполнено», а не смотрят расписание.
 */
class CalendarFragment : Fragment() {

    private var _binding: FragmentCalendarBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: PlannerAdapter
    private lateinit var grid: MonthGrid
    private var stopObserving: (() -> Unit)? = null

    /** Месяц, который сейчас показан на сетке. */
    private val cursor = Calendar.getInstance()

    /** Выбранный день: по нему снизу показываются события. */
    private val selected = Calendar.getInstance()

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

        grid = MonthGrid(requireContext()) { year, month, day -> pickDay(year, month, day) }
        binding.calendarGrid.addView(grid.root)

        binding.prevMonth.setOnClickListener { shiftMonth(-1) }
        binding.nextMonth.setOnClickListener { shiftMonth(1) }
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
        val events = store().events()
        val marked = PlannerDays.markedDays(events, cursor.get(Calendar.YEAR), cursor.get(Calendar.MONTH))
        grid.render(
            cursor.get(Calendar.YEAR),
            cursor.get(Calendar.MONTH),
            selected.get(Calendar.YEAR),
            selected.get(Calendar.MONTH),
            selected.get(Calendar.DAY_OF_MONTH),
            marked
        )
        binding.monthTitle.text = monthTitle(cursor.get(Calendar.YEAR), cursor.get(Calendar.MONTH))
        binding.dayTitle.text = getString(R.string.calendar_day_title, dayTitle())

        val items = PlannerDays.eventsOnDay(
            events,
            selected.get(Calendar.YEAR),
            selected.get(Calendar.MONTH),
            selected.get(Calendar.DAY_OF_MONTH)
        )
        adapter.submit(items)
        binding.plannerEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        val connected = LinkService.state.get() == com.dynamicisland.dilink.link.LinkState.CONNECTED
        binding.plannerStatus.text = if (connected) {
            "${items.size} событий · синхронизировано"
        } else {
            "${items.size} событий · уйдут при подключении"
        }
    }

    /** Тап по дате: выбираем день и показываем его события. */
    private fun pickDay(year: Int, month: Int, day: Int) {
        selected.set(year, month, day)
        if (year != cursor.get(Calendar.YEAR) || month != cursor.get(Calendar.MONTH)) {
            cursor.set(year, month, day)
        }
        render()
    }

    /** Стрелки месяцев: выбранный день переезжает вместе с календарём. */
    private fun shiftMonth(step: Int) {
        val day = selected.get(Calendar.DAY_OF_MONTH)
        cursor.add(Calendar.MONTH, step)
        val max = cursor.getActualMaximum(Calendar.DAY_OF_MONTH)
        selected.set(
            cursor.get(Calendar.YEAR),
            cursor.get(Calendar.MONTH),
            minOf(day, max)
        )
        render()
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

        // По умолчанию ставим событие на выбранный день: так календарь читается
        // как календарь, а не как список без привязки к дате.
        val now = Calendar.getInstance()
        val startOfDay = Calendar.getInstance().apply {
            clear()
            set(
                selected.get(Calendar.YEAR),
                selected.get(Calendar.MONTH),
                selected.get(Calendar.DAY_OF_MONTH),
                0, 0, 0
            )
            set(Calendar.MILLISECOND, 0)
        }
        val isToday = now.get(Calendar.YEAR) == startOfDay.get(Calendar.YEAR) &&
            now.get(Calendar.MONTH) == startOfDay.get(Calendar.MONTH) &&
            now.get(Calendar.DAY_OF_MONTH) == startOfDay.get(Calendar.DAY_OF_MONTH)
        val calendar = (startOfDay.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, if (isToday) now.get(Calendar.HOUR_OF_DAY) + 1 else 12)
            set(Calendar.MINUTE, 0)
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
                selected.timeInMillis = start
                cursor.timeInMillis = start
                render()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** «Ноябрь 2026» — название месяца и год. */
    private fun monthTitle(year: Int, month: Int): String {
        val names = resources.getStringArray(R.array.month_names)
        return "${names.getOrElse(month) { "" }} $year"
    }

    /** «13 ноября 2026» — выбранный день словами. */
    private fun dayTitle(): String {
        val names = resources.getStringArray(R.array.month_names_gen)
        val month = names.getOrElse(selected.get(Calendar.MONTH)) { "" }
        return "${selected.get(Calendar.DAY_OF_MONTH)} $month ${selected.get(Calendar.YEAR)}"
    }

    private fun format(millis: Long): String =
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(millis))

    override fun onDestroyView() {
        stopObserving?.invoke()
        super.onDestroyView()
        _binding = null
    }
}
