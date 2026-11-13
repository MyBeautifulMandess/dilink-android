package com.dynamicisland.dilink.reminder

import android.content.Context
import android.content.SharedPreferences

/**
 * Память о том, какие напоминания уже показаны.
 *
 * Ключ — идентификатор записи, значение — срок, для которого показывали.
 * Срок в значении нужен, чтобы перенос напоминания («отложить на 10 минут»
 * или правка на компьютере) снова вооружил уведомление: старый срок больше не
 * совпадает с новым, и напоминание честно придёт ещё раз.
 *
 * Хранилище локальное и намеренно не входит в формат синхронизации: на
 * компьютере та же метка не нужна, важно только не повторять уведомления на
 * телефоне.
 */
object NotifiedStore {

    private const val PREFS = "di_link_notified"

    /** Больше меток держать бессмысленно: столько напоминаний не набирается. */
    private const val LIMIT = 200

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Уже показывали это напоминание именно с таким сроком? */
    fun isNotified(context: Context, id: String, due: Long): Boolean {
        if (id.isEmpty()) return false
        return prefs(context).getLong(id, Long.MIN_VALUE) == due
    }

    /** Запомнить, что напоминание показано. */
    fun markNotified(context: Context, id: String, due: Long) {
        if (id.isEmpty()) return
        val prefs = prefs(context)
        prefs.edit().putLong(id, due).apply()
        if (prefs.all.size > LIMIT) prune(prefs)
    }

    /** Снять отметку: напоминание снова сможет сработать. */
    fun clear(context: Context, id: String) {
        if (id.isEmpty()) return
        prefs(context).edit().remove(id).apply()
    }

    /** Подчистить старые метки, чтобы хранилище не росло бесконечно. */
    private fun prune(prefs: SharedPreferences) {
        val stale = prefs.all.entries
            .filter { it.value is Long }
            .sortedBy { it.value as Long }
            .take(prefs.all.size - LIMIT)
            .map { it.key }
        if (stale.isEmpty()) return
        prefs.edit().apply { stale.forEach { remove(it) } }.apply()
    }
}
