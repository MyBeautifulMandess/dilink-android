package com.dynamicisland.dilink.reminder

import android.app.AlarmManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.dynamicisland.dilink.planner.PlannerRepository

/**
 * Мост между правками напоминаний и будильниками.
 *
 * Вызывается после любого изменения набора — создание, выполнение, удаление,
 * слияние с компьютером. Решает две задачи: переставить ближайший будильник и
 * показать просроченные сроки, которые пришли с компьютера уже прошедшими
 * (например, пока телефон лежал выключенным).
 */
object ReminderReconciler {

    fun sync(context: Context) {
        val store = PlannerRepository.get(context)
        val now = System.currentTimeMillis()
        store.pendingReminders()
            .filter { it.due <= now && it.due > now - LOOKBACK }
            .forEach { ReminderNotifier.show(context, it) }
        ReminderScheduler.reschedule(context)
    }

    /** Android 12+ требует отдельного разрешения на точные будильники. */
    fun exactAlarmsBlocked(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return !manager.canScheduleExactAlarms() &&
            context.checkSelfPermission(android.Manifest.permission.SCHEDULE_EXACT_ALARM) !=
            PackageManager.PERMISSION_GRANTED
    }

    /** Показываем только то, что пропущено недавно: старое не должно будить ночью. */
    private const val LOOKBACK = 24 * 60 * 60 * 1000L
}
