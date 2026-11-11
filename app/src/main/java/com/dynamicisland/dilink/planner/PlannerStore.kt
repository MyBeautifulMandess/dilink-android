package com.dynamicisland.dilink.planner

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Локальные события и напоминания телефона.
 *
 * Записи живут в обычном JSON-файле приложения и никуда не деваются, пока
 * компьютер не подключён: телефон — полноценная сторона, а не пульт. При
 * подключении обе стороны обмениваются полными снимками, и слияние идёт по
 * правилу «новее — побеждает» ([updated]). Так же, как в `PlannerSyncService`
 * островка: без надгробий удаление возвращалось бы со второй стороны.
 */
class PlannerStore(private val file: File) {

    private val records = LinkedHashMap<String, PlannerRecord>()
    private val lock = Any()

    /** Вызывается после каждого изменения набора — снаружи перерисовывается список. */
    @Volatile var onChanged: (() -> Unit)? = null

    init {
        load()
    }

    /** Живые записи, отсортированные по времени: события и напоминания вместе. */
    fun items(): List<PlannerRecord> = synchronized(lock) {
        records.values
            .filter { !it.deleted && it.anchor > 0 }
            .sortedBy { it.anchor }
    }

    fun all(): List<PlannerRecord> = synchronized(lock) { records.values.toList() }

    fun find(id: String): PlannerRecord? = synchronized(lock) { records[id] }

    /** Число записей, которые ещё не подтверждены компьютером. */
    fun count(): Int = synchronized(lock) { records.values.count { !it.deleted } }

    /** Полный снимок для `planner.sync`. */
    fun snapshot(): JSONArray = JSONArray().apply {
        synchronized(lock) { records.values.forEach { put(it.toJson()) } }
    }

    /**
     * Добавляет или правит запись с телефона. Время правки ставится сейчас —
     * именно оно победит на компьютере.
     */
    fun upsert(record: PlannerRecord): PlannerRecord {
        val stamped = record.copy(
            id = record.id.ifEmpty { UUID.randomUUID().toString().replace("-", "") },
            updated = System.currentTimeMillis(),
            deleted = false
        )
        synchronized(lock) { records[stamped.id] = stamped }
        save()
        notifyChanged()
        return stamped
    }

    fun setDone(id: String, done: Boolean): PlannerRecord? {
        val existing = find(id) ?: return null
        if (existing.isEvent) return null
        return upsert(
            existing.copy(
                done = done,
                completedAt = if (done) System.currentTimeMillis() else null
            )
        )
    }

    /** Удаление оставляет надгробие: оно обязано дойти до компьютера. */
    fun delete(id: String): PlannerRecord? {
        val existing = find(id) ?: return null
        val grave = existing.copy(
            title = existing.title,
            deleted = true,
            updated = System.currentTimeMillis()
        )
        synchronized(lock) { records[id] = grave }
        save()
        notifyChanged()
        return grave
    }

    /**
     * Применяет записи компьютера. Возвращает те, что действительно новее
     * локальных, — их надо показать и, если нужно, разослать дальше.
     */
    fun merge(incoming: List<PlannerRecord>): List<PlannerRecord> {
        val changed = ArrayList<PlannerRecord>()
        synchronized(lock) {
            for (record in incoming) {
                val known = records[record.id]
                if (known != null && known.updated >= record.updated) continue
                records[record.id] = record
                changed.add(record)
            }
        }
        if (changed.isNotEmpty()) {
            save()
            notifyChanged()
        }
        return changed
    }

    /**
     * Записи, созданные до первого подключения, получают самый старый штамп:
     * если такая же запись уже есть на компьютере, победит его версия.
     */
    fun seedPending(): Int {
        var count = 0
        synchronized(lock) {
            for ((id, record) in records) {
                if (record.updated > 0) continue
                records[id] = record.copy(updated = 1L)
                count++
            }
        }
        if (count > 0) save()
        return count
    }

    private fun notifyChanged() = onChanged?.invoke()

    private fun load() {
        try {
            if (!file.exists()) return
            val array = JSONArray(file.readText())
            synchronized(lock) {
                for (index in 0 until array.length()) {
                    val record = PlannerRecord.from(array.optJSONObject(index) ?: continue) ?: continue
                    records[record.id] = record
                }
            }
        } catch (e: Exception) {
            // Битый файл не должен блокировать запуск: начинаем с пустого набора.
            file.renameTo(File(file.parentFile, file.name + ".broken"))
        }
    }

    private fun save() {
        try {
            file.parentFile?.mkdirs()
            val array = synchronized(lock) { JSONArray().apply { records.values.forEach { put(it.toJson()) } } }
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(array.toString())
            if (!temp.renameTo(file)) {
                file.writeText(array.toString())
                temp.delete()
            }
        } catch (e: Exception) {
            // Нехватка места не должна ронять связь: данные останутся в памяти до
            // следующей попытки сохранить.
        }
    }

    companion object {
        /** Разбирает `planner.sync` / `planner.push`: приходит либо массив, либо одна запись. */
        fun readPayload(payload: JSONObject): List<PlannerRecord> {
            val result = ArrayList<PlannerRecord>()
            payload.optJSONArray("records")?.let { array ->
                for (index in 0 until array.length()) {
                    PlannerRecord.from(array.optJSONObject(index) ?: continue)?.let { result.add(it) }
                }
            }
            payload.optJSONObject("record")?.let { single ->
                PlannerRecord.from(single)?.let { result.add(it) }
            }
            return result
        }
    }
}
