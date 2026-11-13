package com.dynamicisland.dilink

import com.dynamicisland.dilink.planner.PlannerDays
import com.dynamicisland.dilink.planner.PlannerRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** Правило «событие попадает на день» — его используют и сетка, и список дня. */
class CalendarDaysTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int = 12): Long {
        val calendar = Calendar.getInstance()
        calendar.clear()
        calendar.set(year, month, day, hour, 0, 0)
        return calendar.timeInMillis
    }

    private fun event(start: Long, end: Long) = PlannerRecord(
        id = "e1",
        kind = PlannerRecord.KIND_EVENT,
        title = "Встреча",
        start = start,
        end = end
    )

    @Test
    fun `event falls on its own day`() {
        val record = event(at(2026, Calendar.NOVEMBER, 13), at(2026, Calendar.NOVEMBER, 13, 14))
        assertTrue(PlannerDays.onDay(record, 2026, Calendar.NOVEMBER, 13))
        assertFalse(PlannerDays.onDay(record, 2026, Calendar.NOVEMBER, 14))
    }

    @Test
    fun `event without end still counts on its day`() {
        val record = event(at(2026, Calendar.NOVEMBER, 13), 0L)
        assertTrue(PlannerDays.onDay(record, 2026, Calendar.NOVEMBER, 13))
        assertFalse(PlannerDays.onDay(record, 2026, Calendar.NOVEMBER, 12))
    }

    @Test
    fun `long event covers every day it spans`() {
        val record = event(at(2026, Calendar.NOVEMBER, 13, 22), at(2026, Calendar.NOVEMBER, 15, 3))
        assertTrue(PlannerDays.onDay(record, 2026, Calendar.NOVEMBER, 13))
        assertTrue(PlannerDays.onDay(record, 2026, Calendar.NOVEMBER, 14))
        assertTrue(PlannerDays.onDay(record, 2026, Calendar.NOVEMBER, 15))
        assertFalse(PlannerDays.onDay(record, 2026, Calendar.NOVEMBER, 16))
    }

    @Test
    fun `reminders never show up as events`() {
        val reminder = PlannerRecord(
            id = "r1",
            kind = PlannerRecord.KIND_REMINDER,
            title = "Позвонить",
            due = at(2026, Calendar.NOVEMBER, 13)
        )
        assertFalse(PlannerDays.onDay(reminder, 2026, Calendar.NOVEMBER, 13))
    }

    @Test
    fun `events of the day are sorted by start`() {
        val first = event(at(2026, Calendar.NOVEMBER, 13, 9), at(2026, Calendar.NOVEMBER, 13, 10))
        val second = event(at(2026, Calendar.NOVEMBER, 13, 15), at(2026, Calendar.NOVEMBER, 13, 16))
        val other = event(at(2026, Calendar.NOVEMBER, 14, 9), at(2026, Calendar.NOVEMBER, 14, 10))
        val list = PlannerDays.eventsOnDay(listOf(second, other, first), 2026, Calendar.NOVEMBER, 13)
        assertEquals(2, list.size)
        assertEquals(first.id, list[0].id)
        assertEquals(second.id, list[1].id)
    }

    @Test
    fun `marked days collect only the current month`() {
        val inside = event(at(2026, Calendar.NOVEMBER, 7), at(2026, Calendar.NOVEMBER, 7, 1))
        val outside = event(at(2026, Calendar.DECEMBER, 1), at(2026, Calendar.DECEMBER, 1, 1))
        val marked = PlannerDays.markedDays(listOf(inside, outside), 2026, Calendar.NOVEMBER)
        assertEquals(setOf(7), marked)
    }
}
