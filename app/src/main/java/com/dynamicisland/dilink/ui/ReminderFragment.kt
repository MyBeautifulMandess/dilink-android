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
import com.dynamicisland.dilink.databinding.FragmentRemindersBinding
import com.dynamicisland.dilink.link.LinkService
import com.dynamicisland.dilink.planner.PlannerRecord
import com.dynamicisland.dilink.planner.PlannerRepository
import com.dynamicisland.dilink.reminder.NotifiedStore
import com.dynamicisland.dilink.reminder.ReminderNotifier
import com.dynamicisland.dilink.reminder.ReminderReconciler
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Напоминания телефона: отметка «выполнено», повтор срока и будильник.
 *
 * Срок ставится на `AlarmManager`, поэтому уведомление приходит и при закрытом
 * приложении, и с погашенным экраном. Отметка «выполнено» здесь, в уведомлении
 * и на компьютере — одна и та же правка: она уходит в планер и синхронизируется.
 */
class ReminderFragment : Fragment() {

    private var _binding: FragmentRemindersBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: PlannerAdapter
    private var stopObserving: (() -> Unit)? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentRemindersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        adapter = PlannerAdapter(
            onDone = { record, done -> markDone(record, done) },
            onRemove = { record -> remove(record) }
        )
        binding.reminderList.layoutManager = LinearLayoutManager(requireContext())
        binding.reminderList.adapter = adapter
        binding.addReminder.setOnClickListener { createDialog() }
        binding.reminderNotice.setOnClickListener {
            // На Android 12+ точные будильники выдаются отдельно от обычных разрешений.
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                android.net.Uri.parse("package:" + requireContext().packageName)
            )
            runCatching { startActivity(intent) }
        }
        stopObserving = PlannerRepository.observe { com.dynamicisland.dilink.util.onMain { render() } }
        render()
    }

    override fun onResume() {
        super.onResume()
        ReminderReconciler.sync(requireContext())
        render()
    }

    private fun store() = PlannerRepository.get(requireContext())

    private fun render() {
        val items = store().reminders()
        adapter.submit(items)
        binding.remindersEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        val connected = LinkService.state.get() == com.dynamicisland.dilink.link.LinkState.CONNECTED
        val pending = items.count { !it.done }
        binding.remindersStatus.text = if (connected) {
            "$pending активных · синхронизировано"
        } else {
            "$pending активных · уйдут при подключении"
        }
        binding.reminderNotice.visibility =
            if (ReminderReconciler.exactAlarmsBlocked(requireContext())) View.VISIBLE else View.GONE
    }

    private fun markDone(record: PlannerRecord, done: Boolean) {
        val saved = store().setDone(record.id, done) ?: return
        LinkService.instance?.pushPlanner(saved)
        // Снятие отметки «выполнено» снова вооружает напоминание.
        NotifiedStore.clear(requireContext(), record.id)
        if (done) ReminderNotifier.dismiss(requireContext(), record.id)
        ReminderReconciler.sync(requireContext())
        render()
    }

    private fun remove(record: PlannerRecord) {
        val grave = store().delete(record.id) ?: return
        LinkService.instance?.pushPlanner(grave)
        NotifiedStore.clear(requireContext(), record.id)
        ReminderNotifier.dismiss(requireContext(), record.id)
        ReminderReconciler.sync(requireContext())
        render()
    }

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
            add(Calendar.HOUR_OF_DAY, 1)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
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
            .setTitle(R.string.planner_dialog_reminder)
            .setView(wrapper)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val text = title.text.toString().trim().ifEmpty { "Напоминание" }
                val saved = store().upsert(
                    PlannerRecord(
                        id = "",
                        kind = PlannerRecord.KIND_REMINDER,
                        title = text,
                        due = calendar.timeInMillis,
                        source = "phone"
                    )
                )
                LinkService.instance?.pushPlanner(saved)
                ReminderReconciler.sync(requireContext())
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
