package com.dynamicisland.dilink.link

import android.os.Build
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/** Состояние канала для интерфейса. */
enum class LinkState { DISCONNECTED, CONNECTING, CONNECTED, REJECTED }

/**
 * Клиент канала до островка. Отвечает за рукопожатие, авторизацию ключом
 * устройства, переподключение с нарастающей паузой и отправку кадров.
 *
 * Класс намеренно не знает про Android UI: колбэки вызываются на фоновом
 * потоке, а подписчики сами решают, как перейти в главный.
 */
class LinkClient(
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onState(state: LinkState, detail: String)
        fun onEnvelope(envelope: Envelope)
        fun onBinary(payload: ByteArray)
        fun onAuthOk(deviceId: String, name: String)
        fun onRejected(reason: String)
    }

    private val http = OkHttpClient.Builder()
        .pingInterval(0, TimeUnit.SECONDS)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val queue = ConcurrentLinkedQueue<String>()
    private val running = AtomicBoolean(false)
    private val authorized = AtomicBoolean(false)

    @Volatile private var socket: WebSocket? = null
    @Volatile private var pairing: Pairing? = null
    @Volatile private var token: String = ""
    @Volatile private var attempt = 0

    @Volatile var state: LinkState = LinkState.DISCONNECTED
        private set

    val isAuthorized: Boolean get() = authorized.get()

    val deviceLabel: String =
        (Build.MANUFACTURER + " " + Build.MODEL).trim().ifBlank { "Android" }

    /**
     * Подключение по сохранённому ключу. Токен — либо одноразовый из QR,
     * либо уже выданный ранее ключ устройства: для сервера это одно и то же поле.
     */
    fun connect(pairing: Pairing, deviceToken: String) {
        this.pairing = pairing
        this.token = deviceToken
        running.set(true)
        attempt = 0
        openSocket()
    }

    fun disconnect() {
        running.set(false)
        authorized.set(false)
        queue.clear()
        socket?.close(1000, "bye")
        socket = null
        setState(LinkState.DISCONNECTED, "Отключено")
    }

    /** Полное закрытие: сокет, пул соединений и потоки. Нужно при выходе из сервиса. */
    fun close() {
        disconnect()
        runCatching {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    private fun openSocket() {
        val target = pairing ?: return
        if (!running.get()) return
        setState(LinkState.CONNECTING, target.display)
        val request = Request.Builder().url(target.webSocketUrl).build()
        socket?.cancel()
        socket = http.newWebSocket(request, Listener())
    }

    private fun setState(next: LinkState, detail: String) {
        state = next
        callbacks.onState(next, detail)
    }

    fun send(type: String, payload: JSONObject = JSONObject()) {
        val text = Protocol.build(type, payload)
        val current = socket
        if (current == null || !authorized.get()) {
            // Пока канал не готов, копим самое ценное — правки и буфер. Очередь
            // ограничена, чтобы офлайн-сессия не съела память.
            if (queue.size < MAX_QUEUE) queue.add(text)
            return
        }
        if (!current.send(text)) queue.add(text)
    }

    fun sendBinary(payload: ByteArray) {
        val current = socket ?: return
        current.send(payload.toByteString())
    }

    /** Отправка заголовка и бинарного тела одним пакетом — как ждёт сервер. */
    fun sendChunk(type: String, payload: JSONObject, data: ByteArray) {
        val current = socket ?: return
        runCatching { current.send(Protocol.build(type, payload)) }
        current.send(data.toByteString())
    }

    private fun flushQueue() {
        val current = socket ?: return
        while (true) {
            val next = queue.poll() ?: return
            if (!current.send(next)) {
                queue.add(next)
                return
            }
        }
    }

    private fun authenticate() {
        val payload = JSONObject().apply {
            put("token", token)
            put("name", deviceLabel)
        }
        socket?.send(Protocol.build("auth", payload))
    }

    internal inner class Listener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            attempt = 0
            authenticate()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val envelope = Protocol.parse(text) ?: return
            when (envelope.type) {
                "auth.ok" -> {
                    authorized.set(true)
                    setState(LinkState.CONNECTED, envelope.str("server"))
                    callbacks.onAuthOk(envelope.str("deviceId"), envelope.str("name"))
                    flushQueue()
                }
                "auth.rejected" -> {
                    authorized.set(false)
                    running.set(false)
                    setState(LinkState.REJECTED, envelope.str("reason"))
                    callbacks.onRejected(envelope.str("reason"))
                }
                "pong" -> Unit
                else -> callbacks.onEnvelope(envelope)
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            callbacks.onBinary(bytes.toByteArray())
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            authorized.set(false)
            scheduleReconnect(t.message ?: "соединение прервано")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            authorized.set(false)
            if (running.get()) scheduleReconnect("закрыто: $reason") else setState(LinkState.DISCONNECTED, reason)
        }
    }

    private fun scheduleReconnect(reason: String) {
        if (!running.get()) {
            setState(LinkState.DISCONNECTED, reason)
            return
        }
        setState(LinkState.CONNECTING, reason)
        val delay = RECONNECT[min(attempt, RECONNECT.lastIndex)]
        attempt++
        Thread {
            try {
                Thread.sleep(delay)
            } catch (ignored: InterruptedException) {
                return@Thread
            }
            if (running.get()) openSocket()
        }.apply { isDaemon = true }.start()
    }

    private companion object {
        const val MAX_QUEUE = 40
        val RECONNECT = longArrayOf(1_000, 2_000, 4_000, 8_000, 16_000, 30_000)
    }
}
