package com.dynamicisland.dilink.store

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Настройки телефона. Хранятся в SharedPreferences: секретов здесь нет, ключ
 * устройства лежит отдельно в [DeviceStore].
 */
class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("di_link_settings", Context.MODE_PRIVATE)

    var address: String
        get() = prefs.getString(KEY_ADDRESS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ADDRESS, value).apply()

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    var clipboardSync: Boolean
        get() = prefs.getBoolean(KEY_CLIPBOARD, true)
        set(value) = prefs.edit().putBoolean(KEY_CLIPBOARD, value).apply()

    var notifications: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFICATIONS, value).apply()

    var mediaSync: Boolean
        get() = prefs.getBoolean(KEY_MEDIA, true)
        set(value) = prefs.edit().putBoolean(KEY_MEDIA, value).apply()

    var fileTransfer: Boolean
        get() = prefs.getBoolean(KEY_FILES, true)
        set(value) = prefs.edit().putBoolean(KEY_FILES, value).apply()

    var plannerSync: Boolean
        get() = prefs.getBoolean(KEY_PLANNER, true)
        set(value) = prefs.edit().putBoolean(KEY_PLANNER, value).apply()

    /** Слушать уведомления только в Wi-Fi: мобильный трафик не расходуется. */
    var wifiOnly: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, true)
        set(value) = prefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply()

    /** Уведомления этих пакетов не отправляются на компьютер. */
    var ignoredPackages: Set<String>
        get() = (prefs.getString(KEY_IGNORED, "") ?: "")
            .split('\n', ',', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        set(value) = prefs.edit().putString(KEY_IGNORED, value.joinToString("\n")).apply()

    var ignoredText: String
        get() = prefs.getString(KEY_IGNORED, "") ?: ""
        set(value) = prefs.edit().putString(KEY_IGNORED, value).apply()

    /** Автозапуск связи после загрузки телефона. */
    var autoConnect: Boolean
        get() = prefs.getBoolean(KEY_AUTO, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO, value).apply()

    /** Последний успешный ключ устройства: по нему идёт переподключение без QR. */
    var deviceToken: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    var deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DEVICE_ID, value).apply()

    val isPaired: Boolean get() = address.isNotEmpty() && deviceToken.isNotEmpty()

    fun forget() {
        prefs.edit()
            .remove(KEY_ADDRESS)
            .remove(KEY_PORT)
            .remove(KEY_TOKEN)
            .remove(KEY_DEVICE_ID)
            .apply()
    }

    private companion object {
        const val DEFAULT_PORT = 19532
        const val KEY_ADDRESS = "address"
        const val KEY_PORT = "port"
        const val KEY_CLIPBOARD = "clipboard"
        const val KEY_NOTIFICATIONS = "notifications"
        const val KEY_MEDIA = "media"
        const val KEY_FILES = "files"
        const val KEY_PLANNER = "planner"
        const val KEY_WIFI_ONLY = "wifi_only"
        const val KEY_IGNORED = "ignored"
        const val KEY_AUTO = "auto_connect"
        const val KEY_TOKEN = "token"
        const val KEY_DEVICE_ID = "device_id"
    }
}
