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

        /**
         * Разбирает данные сопряжения из строки. Принимает и полную ссылку
         * `dilink://v1?ip=…&port=…&token=…`, и голый набор параметров, и адрес
         * вида `192.168.1.4:19532` — в последнем случае ключ берётся отдельно.
         *
         * Камера телефона не всегда умеет открывать нестандартные схемы: она
         * лишь распознаёт код и предлагает скопировать ссылку. Поэтому строка
         * должна пониматься максимально терпимо.
         */
        fun parse(raw: String?, fallbackToken: String? = null): Pairing? {
            if (raw.isNullOrBlank()) return null
            var text = raw.trim()
            if (text.isEmpty()) return null

            // Ссылка могла прийти с кавычками или переносом строки.
            text = text.trim('"', '\'', ' ', '\n', '\r', '\t')

            var values = HashMap<String, String>()

            val schemeAt = text.indexOf(SCHEME, ignoreCase = true)
            if (schemeAt >= 0) {
                val rest = text.substring(schemeAt + SCHEME.length)
                val queryStart = rest.indexOf('?')
                if (queryStart >= 0) {
                    val version = rest.substring(0, queryStart).trim('/').lowercase()
                    if (version.isNotEmpty() && version != "v1") return null
                    values = parseQuery(rest.substring(queryStart + 1))
                }
            } else if (text.contains('=')) {
                // Голые параметры без схемы: ip=…&port=…&token=…
                values = parseQuery(text.substringAfter('?', text))
            }

            var address = values["ip"] ?: values["host"] ?: values["addr"] ?: values["address"]
            var port = values["port"]?.toIntOrNull()
            var token = values["token"]

            // Голый адрес вида 192.168.1.4:19532 — без параметров и без схемы.
            if (address == null && schemeAt < 0 && !text.contains('=')) {
                address = text
            }

            // Адрес вида 192.168.1.4:19532 без отдельного параметра port.
            if (address != null && port == null) {
                val colon = address.lastIndexOf(':')
                if (colon > 0) {
                    val maybePort = address.substring(colon + 1).toIntOrNull()
                    if (maybePort != null) {
                        address = address.substring(0, colon)
                        port = maybePort
                    }
                }
            }

            if (address.isNullOrBlank()) return null
            if (port == null) port = DEFAULT_PORT
            if (port !in 1..65535) return null
            if (token.isNullOrBlank()) token = fallbackToken?.trim()?.takeIf { it.isNotEmpty() }
            if (token.isNullOrBlank()) return null
            return Pairing(address.trim(), port, token.trim())
        }

        private fun parseQuery(query: String): HashMap<String, String> {
            val values = HashMap<String, String>()
            query.split('&', ';').forEach { pair ->
                if (pair.isEmpty()) return@forEach
                val eq = pair.indexOf('=')
                if (eq <= 0) return@forEach
                val key = pair.substring(0, eq).trim().lowercase()
                val value = runCatching { URLDecoder.decode(pair.substring(eq + 1).trim(), "UTF-8") }
                    .getOrDefault(pair.substring(eq + 1).trim())
                if (key.isNotEmpty()) values[key] = value
            }
            return values
        }

        /** Собирает ссылку сопряжения — используется в тестах и при ручном вводе. */
        fun build(address: String, port: Int, token: String): String =
            "$SCHEME" + "v1?ip=${URLEncoder.encode(address, "UTF-8")}" +
                "&port=$port&token=${URLEncoder.encode(token, "UTF-8")}"

        const val DEFAULT_PORT = 19532
    }
}
