package com.dynamicisland.dilink

import com.dynamicisland.dilink.planner.PlannerRecord
import com.dynamicisland.dilink.reminder.ReminderScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Выбор ближайшего срока для будильника.
 *
 * Проверяем главное: уже показанное напоминание не должно снова попадать в
 * будильник — именно из-за этого уведомления приходили десятки раз в минуту.
 */
class ReminderSchedulingTest {

    private fun reminder(id: String, due: Long) =
        PlannerRecord(id = id, kind = PlannerRecord.KIND_REMINDER, title = id, due = due)

    @Test
    fun shownReminderIsNotScheduledAgain() {
        val now = 1_000_000L
        val records = listOf(reminder("r1", now - 5_000), reminder("r2", now + 60_000))

        // Показанное «r1» пропускается, будильник встаёт на следующий срок.
        val next = ReminderScheduler.nextDue(records, now) { id, due ->
            id == "r1" && due == now - 5_000
        }
        assertEquals("r2", next?.id)
    }

    @Test
    fun allShownRemindersLeaveNoAlarm() {
        val now = 1_000_000L
        val records = listOf(reminder("r1", now - 5_000), reminder("r2", now - 1_000))
        val next = ReminderScheduler.nextDue(records, now) { _, _ -> true }
        assertNull(next)
    }

    @Test
    fun movedReminderFiresAgain() {
        val now = 1_000_000L
        val old = reminder("r1", now - 5_000)
        // Отметка о показанном сроке не совпадает с новым — напоминание снова в деле.
        val moved = reminder("r1", now + 600_000)
        val next = ReminderScheduler.nextDue(listOf(moved), now) { _, due -> due == old.due }
        assertEquals("r1", next?.id)
    }

    @Test
    fun oldRemindersAreNotShown() {
        val now = 1_000_000L
        // Срок старше допуска минуты: будильник на него не ставится.
        val next = ReminderScheduler.nextDue(
            listOf(reminder("r1", now - ReminderScheduler.GRACE - 1_000)),
            now
        ) { _, _ -> false }
        assertNull(next)
    }
}
