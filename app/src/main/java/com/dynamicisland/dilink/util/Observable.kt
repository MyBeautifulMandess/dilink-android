package com.dynamicisland.dilink.util

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

private val mainHandler = Handler(Looper.getMainLooper())

/** Выполняет блок в главном потоке: сеть и файлы живут на фоновых, UI — здесь. */
fun onMain(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
}

/** Отложенный вызов в главном потоке; повторный вызов отменяет предыдущий. */
class Debounce(private val delayMillis: Long, private val block: () -> Unit) {
    private val runnable = Runnable { block() }
    fun schedule() {
        mainHandler.removeCallbacks(runnable)
        mainHandler.postDelayed(runnable, delayMillis)
    }
}

/** Отложенная задача в главном или фоновом потоке — для отмены по идентификатору. */
fun later(delayMillis: Long, block: () -> Unit) {
    mainHandler.postDelayed(block, delayMillis)
}

/**
 * Наблюдаемое значение вместо LiveData: подписчики получают последнее состояние
 * сразу при подписке, а изменения приходят в главном потоке.
 */
class Observable<T>(initial: T) {

    @Volatile
    private var value: T = initial

    private val listeners = CopyOnWriteArrayList<(T) -> Unit>()

    fun get(): T = value

    fun set(next: T) {
        if (value == next) return
        value = next
        onMain { listeners.forEach { runCatching { it(next) } } }
    }

    fun observe(listener: (T) -> Unit): () -> Unit {
        listeners.add(listener)
        onMain { runCatching { listener(value) } }
        return { listeners.remove(listener) }
    }
}
