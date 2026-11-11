package com.dynamicisland.dilink.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.dynamicisland.dilink.R
import com.dynamicisland.dilink.link.LinkService
import com.dynamicisland.dilink.planner.PlannerRepository
import com.dynamicisland.dilink.ui.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Показ напоминания на телефоне и кнопки прямо в уведомлении. */
object ReminderNotifier {

    private const val CHANNEL = "di_link_reminders"

    fun show(context: Context, record: com.dynamicisland.dilink.planner.PlannerRecord) {
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            record.id.hashCode() and 0x7fffffff,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_TAB, 2)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val done = actionIntent(context, record.id, ACTION_DONE)
        val snooze = actionIntent(context, record.id, ACTION_SNOOZE)
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(record.due))

        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_status)
            .setContentTitle(record.title.ifEmpty { "Напоминание" })
            .setContentText("Срок — $time")
            .setStyle(NotificationCompat.BigTextStyle().bigText(record.title.ifEmpty { "Напоминание" }))
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .addAction(0, context.getString(R.string.action_reminder_done), done)
            .addAction(0, context.getString(R.string.action_reminder_snooze), snooze)
            .build()

        runCatching {
            NotificationManagerCompat.from(context)
                .notify(record.id.hashCode() and 0x7fffffff, notification)
        }
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.channel_reminders_desc)
                enableVibration(true)
            }
        )
    }

    fun dismiss(context: Context, id: String) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(id.hashCode() and 0x7fffffff)
        }
    }

    private fun actionIntent(context: Context, id: String, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            (id + action).hashCode() and 0x7fffffff,
            Intent(context, ReminderActionReceiver::class.java).apply {
                this.action = action
                putExtra("record_id", id)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    const val ACTION_DONE = "com.dynamicisland.dilink.REMINDER_DONE"
    const val ACTION_SNOOZE = "com.dynamicisland.dilink.REMINDER_SNOOZE"
    private const val SNOOZE_MINUTES = 10L
}

/**
 * Кнопки уведомления. Правка идёт через то же хранилище, что и в интерфейсе,
 * поэтому изменение сразу уходит на компьютер, если он подключён.
 */
class ReminderActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("record_id").orEmpty()
        if (id.isEmpty()) return
        val store = PlannerRepository.get(context)
        val record = store.find(id) ?: return

        when (intent.action) {
            ReminderNotifier.ACTION_DONE -> {
                store.setDone(id, true)?.let { LinkService.instance?.pushPlanner(it) }
                ReminderNotifier.dismiss(context, id)
            }
            ReminderNotifier.ACTION_SNOOZE -> {
                val moved = store.upsert(
                    record.copy(
                        due = System.currentTimeMillis() + SNOOZE_MINUTES * 60_000,
                        done = false,
                        completedAt = null
                    )
                )
                LinkService.instance?.pushPlanner(moved)
                ReminderNotifier.dismiss(context, id)
            }
        }
        ReminderScheduler.reschedule(context)
    }

    private companion object {
        const val SNOOZE_MINUTES = 10L
    }
}
