package com.dynamicisland.dilink.planner

import org.json.JSONObject

/**
 * Запись планера — событие календаря или напоминание. Формат совпадает с
 * `PlannerRecord` на стороне островка: имена полей camelCase, время в
 * миллисекундах Unix.
 *
 * [updated] — единственное, что решает спор версий при слиянии. Удаление хранится
 * надгробием ([deleted]), иначе стёртая на телефоне запись вернулась бы с
 * компьютера при следующей синхронизации.
 */
data class PlannerRecord(
    val id: String,
    val kind: String = KIND_EVENT,
    val title: String = "",
    val updated: Long = 0L,
    val deleted: Boolean = false,
    val start: Long = 0L,
    val end: Long = 0L,
    val allDay: Boolean = false,
    val reminderMinutes: Int = 0,
    val reminderId: String = "",
    val due: Long = 0L,
    val done: Boolean = false,
    val completedAt: Long? = null,
    val source: String = "",
    val eventId: String = ""
) {
    val isEvent: Boolean get() = kind == KIND_EVENT

    /** Время, по которому запись сортируется и показывается. */
    val anchor: Long get() = if (isEvent) start else due

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("kind", kind)
        put("title", title)
        put("updated", updated)
        put("deleted", deleted)
        put("start", start)
        put("end", end)
        put("allDay", allDay)
        put("reminderMinutes", reminderMinutes)
        put("reminderId", reminderId)
        put("due", due)
        put("done", done)
        put("completedAt", completedAt ?: JSONObject.NULL)
        put("source", source)
        put("eventId", eventId)
    }

    companion object {
        const val KIND_EVENT = "event"
        const val KIND_REMINDER = "reminder"

        /** Читает запись от компьютера. null — мусор без id, такое место не занимает. */
        fun from(json: JSONObject): PlannerRecord? {
            val id = json.optString("id", "")
            if (id.isEmpty()) return null
            val kind = json.optString("kind", KIND_EVENT)
            return PlannerRecord(
                id = id,
                kind = if (kind == KIND_REMINDER) KIND_REMINDER else KIND_EVENT,
                title = json.optString("title", "").take(200),
                updated = json.optLong("updated", 0L),
                deleted = json.optBoolean("deleted", false),
                start = json.optLong("start", 0L),
                end = json.optLong("end", 0L),
                allDay = json.optBoolean("allDay", false),
                reminderMinutes = json.optInt("reminderMinutes", 0),
                reminderId = json.optString("reminderId", ""),
                due = json.optLong("due", 0L),
                done = json.optBoolean("done", false),
                completedAt = if (json.isNull("completedAt")) null else json.optLong("completedAt", 0L),
                source = json.optString("source", "").take(40),
                eventId = json.optString("eventId", "")
            )
        }
    }
}
