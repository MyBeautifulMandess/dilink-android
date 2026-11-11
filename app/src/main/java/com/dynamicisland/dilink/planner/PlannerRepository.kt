package com.dynamicisland.dilink.planner

import android.content.Context
import java.io.File

/**
 * Единственный экземпляр [PlannerStore] на всё приложение.
 *
 * Календарь, напоминания и служба связи — три разных потребителя одного набора
 * записей. Если бы каждый открывал свой файл, правка на одном экране не была бы
 * видна на другом, а слияние с компьютером затирало бы свежие данные. Поэтому
 * хранилище одно, а подписчиков сколько угодно.
 */
object PlannerRepository {

    @Volatile
    private var store: PlannerStore? = null

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun get(context: Context): PlannerStore {
        store?.let { return it }
        synchronized(this) {
            store?.let { return it }
            val created = PlannerStore(File(context.applicationContext.filesDir, "planner.json"))
            created.onChanged = { listeners.forEach { runCatching { it() } } }
            store = created
            return created
        }
    }

    /** Подписка на изменения; возвращает функцию отписки. */
    fun observe(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    /** Уже открытое хранилище — для службы, которая работает без окна. */
    fun current(): PlannerStore? = store
}
