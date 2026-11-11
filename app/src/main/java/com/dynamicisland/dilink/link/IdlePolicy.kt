package com.dynamicisland.dilink.link

/**
 * Когда канал пора закрыть по простою. Вынесено из [LinkService], чтобы
 * правило проверялось тестом без Android-сервиса.
 */
object IdlePolicy {

    /** Миллисекунды простоя, после которых соединение закрывается. */
    fun idleMillis(minutes: Int): Long = minutes.coerceIn(1, 240) * 60_000L

    /**
     * @param now текущее время, мс
     * @param lastActivity последний обмен с компьютером, мс
     * @param minutes настроенный порог в минутах
     * @param enabled включено ли автозакрытие
     * @param connected есть ли живое соединение
     */
    fun shouldDisconnect(
        now: Long,
        lastActivity: Long,
        minutes: Int,
        enabled: Boolean,
        connected: Boolean
    ): Boolean {
        if (!enabled || !connected) return false
        if (now < lastActivity) return false
        return now - lastActivity >= idleMillis(minutes)
    }
}
