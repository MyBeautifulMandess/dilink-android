package com.dynamicisland.dilink.link

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Конверт протокола DI Link. Один формат на все сообщения, как на стороне
 * компьютера: `{v, id, ts, type, payload}`.
 */
data class Envelope(
    val type: String,
    val payload: JSONObject,
    val id: String = UUID.randomUUID().toString().replace("-", ""),
    val ts: Long = System.currentTimeMillis() / 1000
) {
    fun text(): String = JSONObject().apply {
        put("v", VERSION)
        put("id", id)
        put("ts", ts)
        put("type", type)
        put("payload", payload)
    }.toString()

    fun str(name: String): String = payload.optString(name, "")

    fun long(name: String, fallback: Long = 0L): Long =
        if (payload.has(name) && !payload.isNull(name)) payload.optLong(name, fallback) else fallback

    fun double(name: String, fallback: Double = 0.0): Double =
        if (payload.has(name) && !payload.isNull(name)) payload.optDouble(name, fallback) else fallback

    fun bool(name: String, fallback: Boolean = false): Boolean =
        if (payload.has(name) && !payload.isNull(name)) payload.optBoolean(name, fallback) else fallback

    fun array(name: String): JSONArray? = payload.optJSONArray(name)

    companion object {
        const val VERSION = 1
    }
}

object Protocol {
    /** Разбирает текст кадра. null — мусор или чужой формат, такое молча отбрасывается. */
    fun parse(text: String): Envelope? = try {
        val root = JSONObject(text)
        val type = root.optString("type", "")
        if (type.isEmpty()) null else Envelope(
            type = type,
            payload = root.optJSONObject("payload") ?: JSONObject(),
            id = root.optString("id", ""),
            ts = root.optLong("ts", 0L)
        )
    } catch (e: Exception) {
        null
    }

    fun build(type: String, payload: JSONObject = JSONObject()): String =
        Envelope(type, payload).text()

    /** Ограничение входящей строки, чтобы собеседник не выел память. */
    fun clamp(value: String?, limit: Int): String =
        when {
            value == null -> ""
            value.length <= limit -> value
            else -> value.substring(0, limit)
        }
}
