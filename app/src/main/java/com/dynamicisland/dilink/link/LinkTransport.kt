package com.dynamicisland.dilink.link

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * Один транспорт до компьютера. Сокет не важен: WebSocket по Wi-Fi и RFCOMM
 * по Bluetooth несут одни и те же кадры протокола, поэтому вся логика
 * сопряжения и очередей живёт в [LinkClient], а не в транспорте.
 */
interface LinkTransport {

    /** Имя для интерфейса: «Wi-Fi» или «Bluetooth». */
    val name: String

    /** Начинает подключение. События приходят в [Listener] на фоновом потоке. */
    fun open(listener: Listener): Boolean

    fun send(text: String): Boolean

    /** Описание чанка и его данные одной записью — так их ждёт компьютер. */
    fun sendAndBinary(text: String, data: ByteArray): Boolean

    fun close()

    interface Listener {
        fun onOpen()
        fun onText(text: String)
        fun onBinary(data: ByteArray)
        fun onClosed(reason: String)
    }
}

/** Клиент WebSocket поверх OkHttp — прежний путь через локальную сеть. */
class WebSocketTransport(
    private val pairing: Pairing,
    private val http: OkHttpClient
) : LinkTransport {

    override val name: String = "Wi-Fi"

    @Volatile private var socket: WebSocket? = null
    @Volatile private var listener: LinkTransport.Listener? = null
    @Volatile private var closing = false

    override fun open(listener: LinkTransport.Listener): Boolean {
        this.listener = listener
        closing = false
        val request = Request.Builder().url(pairing.webSocketUrl).build()
        socket = http.newWebSocket(request, Inner())
        return true
    }

    override fun send(text: String): Boolean = socket?.send(text) ?: false

    override fun sendAndBinary(text: String, data: ByteArray): Boolean {
        val current = socket ?: return false
        val sent = current.send(text)
        current.send(data.toByteString())
        return sent
    }

    override fun close() {
        closing = true
        socket?.close(1000, "bye")
        socket = null
    }

    private inner class Inner : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            listener?.onOpen()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            listener?.onText(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            listener?.onBinary(bytes.toByteArray())
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            listener?.onClosed(t.message ?: "соединение прервано")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            listener?.onClosed(if (closing) "закрыто" else "закрыто: $reason")
        }
    }
}
