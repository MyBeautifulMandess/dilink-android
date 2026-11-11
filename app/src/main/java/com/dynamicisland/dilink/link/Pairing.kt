package com.dynamicisland.dilink.link

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Данные сопряжения из QR-кода островка:
 * `dilink://v1?ip=192.168.1.68&port=19532&token=<64 hex>`.
 */
data class Pairing(
    val address: String,
    val port: Int,
    val token: String
) {
    val webSocketUrl: String get() = "ws://$address:$port/di/"

    val display: String get() = "$address:$port"

    companion object {
        private const val SCHEME = "dilink://"

        fun parse(raw: String?): Pairing? {
            if (raw.isNullOrBlank()) return null
            val text = raw.trim()
            if (!text.startsWith(SCHEME, ignoreCase = true)) return null

            val rest = text.substring(SCHEME.length)
            val queryStart = rest.indexOf('?')
            if (queryStart < 0) return null

            val version = rest.substring(0, queryStart).trim('/').lowercase()
            if (version.isNotEmpty() && version != "v1") return null

            val values = HashMap<String, String>()
            rest.substring(queryStart + 1).split('&').forEach { pair ->
                if (pair.isEmpty()) return@forEach
                val eq = pair.indexOf('=')
                if (eq <= 0) return@forEach
                val key = pair.substring(0, eq)
                val value = runCatching { URLDecoder.decode(pair.substring(eq + 1), "UTF-8") }
                    .getOrDefault(pair.substring(eq + 1))
                values[key.trim().lowercase()] = value
            }

            val address = values["ip"] ?: values["host"] ?: return null
            val port = values["port"]?.toIntOrNull() ?: return null
            val token = values["token"] ?: return null
            if (address.isBlank() || token.isBlank()) return null
            if (port !in 1..65535) return null
            return Pairing(address, port, token)
        }

        /** Собирает ссылку сопряжения — используется в тестах и при ручном вводе. */
        fun build(address: String, port: Int, token: String): String =
            "$SCHEME" + "v1?ip=${URLEncoder.encode(address, "UTF-8")}" +
                "&port=$port&token=${URLEncoder.encode(token, "UTF-8")}"
    }
}
