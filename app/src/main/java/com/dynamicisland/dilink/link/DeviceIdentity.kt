package com.dynamicisland.dilink.link

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * Личность телефона: имя для человека, постоянный идентификатор установки и
 * аппаратный признак.
 *
 * Идентификатор установки нужен компьютеру, чтобы не заводить вторую запись
 * устройства при повторном сопряжении. Аппаратный признак переживает
 * переустановку приложения: именно по нему компьютер сливает копии одного
 * телефона, а не показывает три строки вместо одной.
 */
object DeviceIdentity {

    private const val PREFS = "di_link_settings"
    private const val KEY_INSTANCE = "instance_id"

    /** Название модели: «TECNO SPARK 30 Pro». */
    fun label(): String =
        (Build.MANUFACTURER + " " + Build.MODEL).trim().ifBlank { "Android" }

    fun instanceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_INSTANCE, null)?.let { if (it.isNotEmpty()) return it }
        val value = java.util.UUID.randomUUID().toString().replace("-", "")
        prefs.edit().putString(KEY_INSTANCE, value).apply()
        return value
    }

    /**
     * Аппаратный идентификатор: Android ID. Он свой у каждой установки ОС и не
     * меняется при переустановке приложения. До Android 8 не передаётся, но там
     * и записей-дублей меньше — остаётся идентификатор установки.
     */
    fun hardwareId(context: Context): String = try {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
    } catch (e: Exception) {
        ""
    }
}
