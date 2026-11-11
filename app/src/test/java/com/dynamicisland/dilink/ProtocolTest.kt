package com.dynamicisland.dilink

import com.dynamicisland.dilink.link.Envelope
import com.dynamicisland.dilink.link.LinkClient
import com.dynamicisland.dilink.link.LinkState
import com.dynamicisland.dilink.link.Pairing
import com.dynamicisland.dilink.planner.PlannerRecord
import com.dynamicisland.dilink.planner.PlannerStore
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Проверка клиента против сервера, который говорит ровно так, как это делает
 * `PhoneLinkService` островка: рукопожатие `auth` → `auth.ok`, пинг раз в
 * 15 секунд, снимок планера сразу после авторизации.
 *
 * Android-классы в unit-тесте не поднимаются, поэтому вместо сервиса связки
 * собирается маленький «сервер» на MockWebServer.
 */
class ProtocolTest {

    private lateinit var server: MockWebServer
    private val received = LinkedBlockingQueue<String>()
    private val binary = LinkedBlockingQueue<ByteString>()
    private val socketRef = java.util.concurrent.atomic.AtomicReference<WebSocket>()
    private val clients = ArrayList<LinkClient>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    socketRef.set(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    received.add(text)
                    val envelope = JSONObject(text)
                    when (envelope.optString("type")) {
                        "auth" -> webSocket.send(
                            """{"v":1,"id":"a","ts":0,"type":"auth.ok","payload":{"deviceId":"dev1","name":"Pixel","server":"Dynamic Island","version":"1.3.0"}}"""
                        )
                        "ping" -> Unit
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    binary.add(bytes)
                }
            })
        )
    }

    @After
    fun tearDown() {
        // Клиент держит пул соединений, а сервер — свои веб-сокеты: закрываем обе
        // стороны, иначе MockWebServer не успевает остановить очередь и падает
        // уже после успешных проверок.
        clients.forEach { it.close() }
        clients.clear()
        runCatching { socketRef.get()?.close(1000, "bye") }
        Thread.sleep(150)
        runCatching { server.shutdown() }
    }

    private fun client(callbacks: LinkClient.Callbacks): LinkClient =
        LinkClient(callbacks).also { clients.add(it) }

    @Test
    fun pairingParsesQrFromIsland() {
        val qr = "dilink://v1?ip=192.168.1.68&port=19532&token=abc123"
        val pairing = Pairing.parse(qr)
        assertNotNull(pairing)
        assertEquals("192.168.1.68", pairing!!.address)
        assertEquals(19532, pairing.port)
        assertEquals("abc123", pairing.token)
        assertEquals("ws://192.168.1.68:19532/di/", pairing.webSocketUrl)
        assertEquals(qr, Pairing.build(pairing.address, pairing.port, pairing.token))
    }

    @Test
    fun junkIsNotAPairingCode() {
        assertEquals(null, Pairing.parse("https://example.com"))
        assertEquals(null, Pairing.parse("dilink://v1?ip=1.2.3.4"))
        assertEquals(null, Pairing.parse(null))
    }

    @Test
    fun clientHandshakesAndSendsPlannerSnapshot() {
        val authorized = CountDownLatch(1)
        val snapshot = LinkedBlockingQueue<String>()

        val client = client(object : LinkClient.Callbacks {
            override fun onState(state: LinkState, detail: String) = Unit
            override fun onEnvelope(envelope: Envelope) = Unit
            override fun onBinary(payload: ByteArray) = Unit
            override fun onAuthOk(deviceId: String, name: String) {
                authorized.countDown()
            }
            override fun onRejected(reason: String) = Unit
        })

        client.connect(Pairing(server.hostName, server.port, "token-1"), "token-1")
        assertTrue("авторизация не прошла", authorized.await(5, TimeUnit.SECONDS))

        // Первый кадр — auth с ключом устройства и именем телефона.
        val authText = received.poll(5, TimeUnit.SECONDS)
        assertNotNull(authText)
        val auth = JSONObject(authText!!)
        assertEquals("auth", auth.getString("type"))
        assertEquals("token-1", auth.getJSONObject("payload").getString("token"))

        // Ответ на auth.ok — полный снимок планера, как делает сервис.
        client.send("planner.sync", JSONObject().put("records", org.json.JSONArray()))
        snapshot.add(received.poll(5, TimeUnit.SECONDS))
        assertEquals("planner.sync", JSONObject(snapshot.take()).getString("type"))

    }

    @Test
    fun chunkHeaderAndBinaryFrameTravelTogether() {
        val authorized = CountDownLatch(1)
        val client = client(object : LinkClient.Callbacks {
            override fun onState(state: LinkState, detail: String) = Unit
            override fun onEnvelope(envelope: Envelope) = Unit
            override fun onBinary(payload: ByteArray) = Unit
            override fun onAuthOk(deviceId: String, name: String) = authorized.countDown()
            override fun onRejected(reason: String) = Unit
        })
        client.connect(Pairing(server.hostName, server.port, "token-2"), "token-2")
        assertTrue(authorized.await(5, TimeUnit.SECONDS))
        received.clear()

        val payload = ByteArray(1024) { (it % 251).toByte() }
        client.sendChunk(
            "file.chunk",
            JSONObject().apply {
                put("id", "t1")
                put("offset", 0)
                put("length", payload.size)
                put("binary", true)
            },
            payload
        )

        val header = received.poll(5, TimeUnit.SECONDS)
        assertNotNull(header)
        assertEquals("file.chunk", JSONObject(header!!).getString("type"))
        val body = binary.poll(5, TimeUnit.SECONDS)
        assertNotNull(body)
        assertTrue(body!!.size == payload.size)
    }

    @Test
    fun plannerKeepsNewestVersionAndRemembersDeletion() {
        val file = File.createTempFile("planner", ".json")
        val store = PlannerStore(file)

        val local = store.upsert(PlannerRecord(id = "e1", title = "Встреча", start = 1_000, end = 2_000))
        assertEquals("Встреча", store.find("e1")?.title)

        // Компьютер прислал более новую версию — она побеждает.
        val newer = local.copy(title = "Встреча (перенесена)", updated = local.updated + 5_000)
        val applied = store.merge(listOf(newer))
        assertEquals(1, applied.size)
        assertEquals("Встреча (перенесена)", store.find("e1")?.title)

        // Старая версия с компьютера не затирает локальную правку.
        assertTrue(store.merge(listOf(local.copy(title = "Старое"))).isEmpty())
        assertEquals("Встреча (перенесена)", store.find("e1")?.title)

        // Удаление остаётся надгробием, чтобы дойти до второй стороны.
        val grave = store.delete("e1")
        assertNotNull(grave)
        assertTrue(grave!!.deleted)
        assertTrue(store.items().isEmpty())
        assertTrue(store.merge(listOf(local.copy(updated = local.updated))).isEmpty())

        file.delete()
    }

    @Test
    fun plannerSnapshotRoundTrip() {
        val file = File.createTempFile("planner", ".json")
        val store = PlannerStore(file)
        store.upsert(PlannerRecord(id = "r1", kind = PlannerRecord.KIND_REMINDER, title = "Позвонить", due = 5_000))

        val parsed = PlannerStore.readPayload(JSONObject().put("records", store.snapshot()))
        assertEquals(1, parsed.size)
        assertEquals("Позвонить", parsed[0].title)
        assertEquals(PlannerRecord.KIND_REMINDER, parsed[0].kind)
        assertEquals(5_000L, parsed[0].due)
        file.delete()
    }

    @Test
    fun eventsAndRemindersLiveApart() {
        val file = File.createTempFile("planner", ".json")
        val store = PlannerStore(file)

        store.upsert(PlannerRecord(id = "e1", title = "Созвон", start = 5_000, end = 6_000))
        store.upsert(PlannerRecord(id = "r1", kind = PlannerRecord.KIND_REMINDER, title = "Раньше", due = 7_000))
        store.upsert(PlannerRecord(id = "r2", kind = PlannerRecord.KIND_REMINDER, title = "Позже", due = 9_000))
        store.upsert(PlannerRecord(id = "r3", kind = PlannerRecord.KIND_REMINDER, title = "Старое", due = 1_000))

        // Вкладки не видят чужие записи.
        assertEquals(listOf("Созвон"), store.events().map { it.title })
        assertTrue(store.reminders().none { it.isEvent })
        assertEquals(3, store.reminders().size)

        // Невыполненные идут по возрастанию срока.
        assertEquals(listOf("Старое", "Раньше", "Позже"), store.reminders().map { it.title })

        // Выполненное уезжает в конец, остальные сохраняют порядок.
        store.setDone("r3", true)
        assertEquals(listOf("Раньше", "Позже", "Старое"), store.reminders().map { it.title })

        // Будильники ставятся только на невыполненные сроки.
        assertEquals(listOf("Раньше", "Позже"), store.pendingReminders().map { it.title })
        store.setDone("r1", true)
        assertEquals(listOf("Позже"), store.pendingReminders().map { it.title })

        // Перенос срока не снимает отметку: запись остаётся выполненной.
        store.upsert(store.find("r1")!!.copy(due = 20_000))
        assertTrue(store.find("r1")!!.done)

        // Удалённое напоминание не возвращается в список будильников.
        store.delete("r2")
        assertTrue(store.pendingReminders().isEmpty())
        assertEquals(listOf("Созвон"), store.events().map { it.title })

        file.delete()
    }

    private fun ByteString.size(): Int = toByteArray().size
}
