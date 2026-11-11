package com.dynamicisland.dilink

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * Точка входа. Держит только то, что нужно всему приложению сразу: имя
 * устройства для сопряжения и каналы уведомлений создаёт сервис.
 */
class LinkApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: LinkApp
            private set

        /** Имя, которое увидят в списке устройств на компьютере. */
        fun deviceName(context: Context): String {
            val manufacturer = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
            val model = Build.MODEL.orEmpty()
            val label = "$manufacturer $model".trim()
            return label.ifEmpty { "Android" }
        }

        /** Есть ли у приложения доступ к уведомлениям: он же открывает медиасессии. */
        fun hasNotificationAccess(context: Context): Boolean {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            return manager.isNotificationListenerAccessGranted(
                android.content.ComponentName(context, com.dynamicisland.dilink.notify.NotificationRelay::class.java)
            )
        }
    }
}
