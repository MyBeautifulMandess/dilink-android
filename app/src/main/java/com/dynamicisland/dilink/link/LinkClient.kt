package com.dynamicisland.dilink.link

import android.content.Context
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/** Состояние канала для интерфейса. */
enum class LinkState { DISCONNECTED, CONNECTING, CONNECTED, REJECTED }

/**
 * Клиент канала до островка. Отвечает за авторизацию ключом устройства,
 * переподключение с нарастающей паузой и очередь кадров.
 *
 * Транспорты не заменяют друг друга, а работают параллельно: Wi-Fi и Bluetooth
 * поднимаются одновременно, у каждого свой цикл переподключения. Пока жив хотя
 * бы один, связь с компьютером есть, и обрыв второго её не рвёт — раньше клиент
 * держал один транспорт и при появлении второго закрывал первый, из-за чего
 * связь «мигала» (подключался Bluetooth, отваливался Wi-Fi, и наоборот).
 *
 * Отправка идёт по одному каналу — лучшему живому, поэтому компьютер не
 * получает кадр дважды. Колбэки вызываются на фоновом потоке, подписчики сами
 * решают, как перейти в главный.
 */
class LinkClient(
    private val callbacks: Callbacks,
    /** Bluetooth-транспорт создаётся лениво: без контекста его не собрать. */
    private val context: Context? = null
) {
    interface Callbacks {
        fun onState(state: LinkState, detail: String)
        fun onEnvelope(envelope: Envelope)
        fun onBinary(payload: ByteArray)
        fun onAuthOk(deviceId: String, name: String, token: String, transport: String)
        fun onRejected(reason: String)
    }

    /**
     * Один транспорт и его собственный цикл переподключения. Каналы друг о
     * друге не знают: обрыв одного не трогает второй.
     */
    private inner class Channel(val transport: LinkTransport) {
        @Volatile var authorized = false

        /** Закрытие уже обработано: не даёт колбэку закрытия зациклиться. */
        @Volatile var closing = false

        /** Номер попытки: колбэки от прошлой попытки игнорируются. */
        @Volatile var generation = 0
        @Volatile var attempt = 0
        @Volatile var retry: Thread? = null

        val name: String get() = transport.name
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

    /** Первый вход после подключения: полный снимок уходит один раз, а не на каждый канал. */
    private val authReported = AtomicBoolean(false)

    private val channels = CopyOnWriteArrayList<Channel>()

    @Volatile private var pairing: Pairing? = null
    @Volatile private var token: String = ""

    /** Предпочтительный транспорт для отправки. Оба канала при этом остаются живыми. */
    @Volatile var preferredTransport: Transports = Transports.AUTO

    /** Живые каналы: «Wi-Fi», «Bluetooth» или «Wi-Fi + Bluetooth». */
    val activeTransport: String get() = connectedChannels.joinToString(" + ")

    val connectedChannels: List<String>
        get() = channels.filter { it.authorized }.map { it.name }

    @Volatile var state: LinkState = LinkState.DISCONNECTED
        private set

    val isAuthorized: Boolean get() = channels.any { it.authorized }

    val deviceLabel: String = DeviceIdentity.label()

    /**
     * Постоянный идентификатор установки. Компьютер заводит по нему одну запись
     * устройства: повторное сопряжение по QR не создаёт вторую копию.
     */
    val instanceId: String = context?.let { DeviceIdentity.instanceId(it) } ?: ""

    /**
     * Аппаратный идентификатор: переживает переустановку приложения, поэтому
     * по нему компьютер сливает записи одного телефона в одну.
     */
    val hardwareId: String = context?.let { DeviceIdentity.hardwareId(it) } ?: ""

    fun connect(pairing: Pairing, deviceToken: String) {
        this.pairing = pairing
        token = deviceToken
        running.set(true)
        authReported.set(false)
        closeChannels()
        val targets = transports(pairing)
        if (targets.isEmpty()) {
            publishState(LinkState.DISCONNECTED, "Нет адреса компьютера")
            return
        }
        val created = targets.map { Channel(it) }
        channels.addAll(created)
        publishState(LinkState.CONNECTING, "${pairing.display} · ${created.joinToString(" + ") { it.name }}")
        created.forEach { open(it) }
    }

    fun disconnect() {
        running.set(false)
        authReported.set(false)
        queue.clear()
        closeChannels()
        publishState(LinkState.DISCONNECTED, "Отключено")
    }

    /** Полное закрытие: сокеты, пул соединений и потоки. Нужно при выходе из сервиса. */
    fun close() {
        disconnect()
        runCatching {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    /**
     * Каналы, которые нужно поднять. Когда известны и адрес, и Bluetooth,
     * поднимаются оба: сеть быстрее, Bluetooth работает без общей сети и
     * остаётся, когда телефон уходит из Wi-Fi. Массив упорядочен предпочтением
     * для отправки, а не для подключения — подключаются все сразу.
     */
    private fun transports(target: Pairing): List<LinkTransport> {
        val ctx = context
        val bluetooth =
            if (ctx != null && target.hasBluetooth) BluetoothTransport(ctx, target.bluetoothAddress) else null
        val network = if (target.address.isNotEmpty()) WebSocketTransport(target, http) else null
        return when (preferredTransport) {
            Transports.BLUETOOTH -> listOfNotNull(bluetooth, network)
            else -> listOfNotNull(network, bluetooth)
        }
    }

    private fun open(channel: Channel) {
        if (!running.get() || !channels.contains(channel)) return
        val generation = ++channel.generation
        channel.closing = false
        // Второй канал может переподключаться, пока первый жив: не показываем
        // «Подключение…», иначе строка состояния мигала бы на ровном месте.
        if (!isAuthorized) publishState(LinkState.CONNECTING)
        val listener = object : LinkTransport.Listener {
            override fun onOpen() {
                if (!current(channel, generation)) return
                channel.attempt = 0
                sendAuth(channel)
            }

            override fun onText(text: String) {
                if (!current(channel, generation)) return
                onFrame(channel, text)
            }

            override fun onBinary(data: ByteArray) = callbacks.onBinary(data)

            override fun onClosed(reason: String) {
                if (!current(channel, generation)) return
                onChannelClosed(channel, generation, reason)
            }
        }
        if (!channel.transport.open(listener)) onChannelClosed(channel, generation, "транспорт недоступен")
    }

    private fun current(channel: Channel, generation: Int): Boolean =
        running.get() && channel.generation == generation

    /**
     * Обрыв одного канала. Переподключается только он: второй продолжает
     * работать, поэтому связь с компьютером не пропадает.
     */
    private fun onChannelClosed(channel: Channel, generation: Int, reason: String) {
        if (channel.closing) return
        channel.closing = true
        channel.authorized = false
        runCatching { channel.transport.close() }
        if (!running.get() || channel.generation != generation) return

        val delay = RECONNECT[min(channel.attempt, RECONNECT.lastIndex)]
        channel.attempt++
        if (!isAuthorized) publishState(LinkState.CONNECTING, reason)

        val retry = Thread {
            try {
                Thread.sleep(delay)
            } catch (ignored: InterruptedException) {
                return@Thread
            }
            if (!running.get() || channel.generation != generation || !channels.contains(channel)) return@Thread
            open(channel)
        }.apply {
            isDaemon = true
            name = "di-link-retry-${channel.name}"
        }
        channel.retry = retry
        retry.start()
    }

    private fun publishState(next: LinkState, detail: String = "") {
        state = next
        val description = detail.ifEmpty { activeTransport.ifEmpty { pairing?.display ?: "" } }
        callbacks.onState(next, description)
    }

    fun send(type: String, payload: JSONObject = JSONObject()) {
        val text = Protocol.build(type, payload)
        if (sendText(text)) return
        // Пока канал не готов, копим самое ценное — правки и буфер. Очередь
        // ограничена, чтобы офлайн-сессия не съела память.
        if (queue.size < MAX_QUEUE) queue.add(text)
    }

    private fun sendText(text: String): Boolean {
        for (channel in orderedChannels()) {
            if (channel.transport.send(text)) return true
        }
        return false
    }

    /**
     * Отправка заголовка и бинарного тела одной записью — как ждёт сервер.
     * Возвращает false, если ни один канал не принял кадр: отправитель тогда
     * не считает чанк доставленным и не уходит вперёд.
     */
    fun sendChunk(type: String, payload: JSONObject, data: ByteArray): Boolean {
        val text = Protocol.build(type, payload)
        for (channel in orderedChannels()) {
            if (channel.transport.sendAndBinary(text, data)) return true
        }
        return false
    }

    /**
     * Живые каналы, предпочтительный первым. Отправка идёт в первый живой,
     * поэтому кадр не дублируется на второй канал.
     */
    private fun orderedChannels(): List<Channel> {
        val preferred = if (preferredTransport == Transports.BLUETOOTH) "Bluetooth" else "Wi-Fi"
        return channels.filter { it.authorized && !it.closing }.sortedBy { if (it.name == preferred) 0 else 1 }
    }

    private fun flushQueue() {
        // Два канала могут авторизоваться почти одновременно: без блокировки
        // одно и то же сообщение ушло бы дважды.
        synchronized(queue) {
            while (true) {
                val next = queue.peek() ?: return
                if (!sendText(next)) return
                queue.poll()
            }
        }
    }

    private fun sendAuth(channel: Channel) {
        val payload = JSONObject().apply {
            put("token", token)
            put("name", deviceLabel)
            put("instance", instanceId)
            // Аппаратный признак: по нему компьютер не заводит вторую запись,
            // если приложение переустановили и идентификатор установки сменился.
            if (hardwareId.isNotEmpty()) put("hwid", hardwareId)
        }
        channel.transport.send(Protocol.build("auth", payload))
    }

    private fun onFrame(channel: Channel, text: String) {
        val envelope = Protocol.parse(text) ?: return
        when (envelope.type) {
            "auth.ok" -> {
                channel.authorized = true
                channel.closing = false
                val transport = envelope.str("transport").ifEmpty { channel.name }
                val first = authReported.compareAndSet(false, true)
                publishState(LinkState.CONNECTED, activeTransport)
                // Второй канал — это не новое подключение: снимок планера и
                // запуск функций уже сделаны, ему нужно только подтверждение.
                if (first) {
                    callbacks.onAuthOk(
                        envelope.str("deviceId"),
                        envelope.str("name"),
                        envelope.str("token"),
                        transport
                    )
                }
                flushQueue()
            }
            "auth.rejected" -> {
                channel.authorized = false
                running.set(false)
                closeChannels()
                publishState(LinkState.REJECTED, envelope.str("reason"))
                callbacks.onRejected(envelope.str("reason"))
            }
            "pong" -> Unit
            // Пинг подтверждается тем же каналом, по которому пришёл: иначе
            // компьютер считал бы молчащий канал мёртвым и закрывал его.
            "ping" -> channel.transport.send(
                Protocol.build("pong", JSONObject().put("ts", System.currentTimeMillis() / 1000))
            )
            else -> callbacks.onEnvelope(envelope)
        }
    }

    private fun closeChannels() {
        for (channel in channels) {
            channel.closing = true
            channel.generation++
            runCatching { channel.retry?.interrupt() }
            runCatching { channel.transport.close() }
        }
        channels.clear()
    }

    companion object {
        private const val MAX_QUEUE = 40

        /**
         * Паузы между попытками. Список не обрывает переподключение: пока канал
         * не выключен человеком, телефон пробует снова и снова с самой длинной
         * паузой. Так Bluetooth-связь живёт, пока устройства в зоне друг друга.
         */
        val RECONNECT = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000, 30_000)
    }
}

/** Какой транспорт предпочесть для отправки. Подключаются всегда оба. */
enum class Transports { AUTO, WEBSOCKET, BLUETOOTH }
