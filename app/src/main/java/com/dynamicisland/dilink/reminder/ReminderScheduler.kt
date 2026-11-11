package com.dynamicisland.dilink.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.dynamicisland.dilink.planner.PlannerRepository

/**
 * Будильники напоминаний.
 *
 * Один `AlarmManager`-будильник на ближайший невыполненный срок: так система
 * не ограничивает нас лимитом в 500 будильников, а перенос любого напоминания
 * просто переставляет единственный. `setExactAndAllowWhileIdle` нужен потому,
 * что без него Doze откладывает срабатывание на десятки минут, а напоминание
 * должно приходить вовремя даже с погашенным экраном.
 */
object ReminderScheduler {

    private const val ACTION_FIRE = "com.dynamicisland.dilink.REMINDER"
    private const val EXTRA_ID = "record_id"

    /** Переставляет будильник на ближайший срок. Вызывать после любой правки. */
    fun reschedule(context: Context) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = pendingIntent(context, "")
        manager.cancel(pending)

        val store = PlannerRepository.current() ?: return
        val next = store.pendingReminders()
            .firstOrNull { it.due > System.currentTimeMillis() - GRACE }
            ?: return
        val trigger = maxOf(next.due, System.currentTimeMillis() + 1_000)
        val intent = pendingIntent(context, next.id)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !manager.canScheduleExactAlarms()) {
                // Точные будильники запрещены настройками: лучше неточный, чем никакой.
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, intent)
            } else {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, intent)
            }
        } catch (e: SecurityException) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, intent)
        }
    }

    /** Отменяет будильник удалённого или выполненного напоминания. */
    fun cancel(context: Context, id: String) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        manager.cancel(pendingIntent(context, id))
    }

    private fun pendingIntent(context: Context, id: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_ID, id)
        }
        return PendingIntent.getBroadcast(
            context,
            id.hashCode() and 0x7fffffff,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** Допуск: напоминание, срок которого прошёл минуту назад, всё равно покажем. */
    private const val GRACE = 60_000L
}

/**
 * Срабатывание будильника: показывает уведомление и заводит следующий.
 *
 * Получатель объявлен в манифесте, поэтому работает и при закрытом приложении —
 * в этом смысл отдельного будильника вместо таймера в службе.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("record_id").orEmpty()
        val store = PlannerRepository.get(context)
        val record = if (id.isNotEmpty()) store.find(id) else null
        val target = record?.takeIf { !it.done && !it.deleted }
            ?: store.pendingReminders().firstOrNull { it.due <= System.currentTimeMillis() + 60_000 }

        if (target != null) {
            ReminderNotifier.show(context, target)
        }
        ReminderScheduler.reschedule(context)
    }
}

/** Сроки, у которых не сработал будильник (перезагрузка, выгрузка): догоняем при запуске. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        ReminderScheduler.reschedule(context)
    }
}
