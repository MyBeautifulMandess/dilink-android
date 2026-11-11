package com.dynamicisland.dilink.notify

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.dynamicisland.dilink.link.LinkService
import com.dynamicisland.dilink.store.Settings
import org.json.JSONObject

/**
 * Пересылает уведомления телефона на компьютер.
 *
 * Служба слушает только те уведомления, что показываются пользователю: постоянные
 * (навигация, зарядка, сам DI Link) отбрасываются, иначе островок засорился бы
 * служебным мусором. Пакеты из списка исключений не уходят вовсе.
 *
 * Этот же доступ к уведомлениям открывает приложению список активных медиасессий —
 * без него Android не показывает чужие плееры.
 */
class NotificationRelay : NotificationListenerService() {

    private lateinit var settings: Settings

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        if (!settings.notifications) return
        if (sbn.packageName == packageName) return
        if (sbn.isOngoing) return
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (sbn.packageName in settings.ignoredPackages) return

        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()?.take(120) ?: ""
        val body = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()?.take(400) ?: ""
        val sub = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim()?.take(120) ?: ""
        if (title.isEmpty() && body.isEmpty()) return

        val payload = JSONObject().apply {
            put("key", sbn.key.take(160))
            put("app", appLabel(sbn.packageName))
            put("package", sbn.packageName.take(80))
            put("title", title)
            put("body", if (body.isEmpty()) sub else body)
            put("ts", sbn.postTime / 1000)
        }
        LinkService.instance?.pushNotification(payload)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val key = sbn?.key ?: return
        LinkService.instance?.pushDismissed(key)
    }

    private fun appLabel(packageName: String): String = runCatching {
        val info = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName).take(40)
}
