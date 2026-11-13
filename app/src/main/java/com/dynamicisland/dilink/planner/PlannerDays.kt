package com.dynamicisland.dilink.planner

import java.util.Calendar

/**
 * Событие попадает на день, если оно началось или продолжается в этот день.
 *
 * Вынесено отдельно от интерфейса, потому что правило легко проверить тестом:
 * месячная сетка и список дня должны считать дни одинаково.
 */
object PlannerDays {

    /** Начало суток [day] месяца [month] года [year]. */
    fun dayStart(year: Int, month: Int, day: Int): Long {
        val calendar = Calendar.getInstance()
        calendar.clear()
        calendar.set(year, month, day, 0, 0, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    /** Событие видно в этом дне? Учитываются многодневные события. */
    fun onDay(record: PlannerRecord, year: Int, month: Int, day: Int): Boolean {
        if (!record.isEvent || record.start <= 0) return false
        val from = dayStart(year, month, day)
        val until = Calendar.getInstance().apply {
            timeInMillis = from
            add(Calendar.DAY_OF_MONTH, 1)
        }.timeInMillis
        val end = if (record.end > record.start) record.end else record.start
        return record.start < until && end >= from
    }

    /** События дня по времени начала. */
    fun eventsOnDay(records: List<PlannerRecord>, year: Int, month: Int, day: Int): List<PlannerRecord> =
        records.filter { onDay(it, year, month, day) }.sortedBy { it.start }

    /** Дни месяца, в которых есть события: номера без нуля. */
    fun markedDays(records: List<PlannerRecord>, year: Int, month: Int): Set<Int> {
        val calendar = Calendar.getInstance()
        val result = HashSet<Int>()
        for (record in records) {
            if (!record.isEvent || record.start <= 0) continue
            calendar.timeInMillis = record.start
            if (calendar.get(Calendar.YEAR) == year && calendar.get(Calendar.MONTH) == month) {
                result.add(calendar.get(Calendar.DAY_OF_MONTH))
            }
        }
        return result
    }
}
