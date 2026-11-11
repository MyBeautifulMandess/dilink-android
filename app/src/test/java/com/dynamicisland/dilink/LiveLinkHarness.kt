package com.dynamicisland.dilink

import com.dynamicisland.dilink.link.Envelope
import com.dynamicisland.dilink.link.FileTransfer
import com.dynamicisland.dilink.link.LinkClient
import com.dynamicisland.dilink.link.LinkState
import com.dynamicisland.dilink.link.Pairing
import com.dynamicisland.dilink.planner.PlannerRecord
import com.dynamicisland.dilink.planner.PlannerStore
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Живой прогон клиента против настоящего сервера островка.
 *
 * Запускается только вручную: `LiveLinkHarness <url>`. Использует те же классы,
 * что и приложение (LinkClient, PlannerStore, FileTransfer), поэтому проверяет
 * не «похожий» код, а ровно тот, что уйдёт в APK.
 */
class LiveLinkHarness {

    /**
     * Живой прогон против запущенного островка. Адрес берётся из DI_LIVE_URL:
     * без переменной тест пропускается, поэтому обычный `check` остаётся зелёным.
     */
    @org.junit.Test
    fun liveRun() {
        val url = System.getenv("DI_LIVE_URL") ?: ""
        org.junit.Assume.assumeTrue("DI_LIVE_URL не задан", url.isNotEmpty())
        run(url)
    }

    private fun run(raw: String) {
        val pairing = Pairing.parse(raw) ?: error("URL не разобрался: $raw")

        val authorized = CountDownLatch(1)
        val rejected = CountDownLatch(1)
        val notifications = LinkedBlockingQueue<String>()
        val media = LinkedBlockingQueue<String>()
        val plannerLatch = CountDownLatch(1)
        val transferDone = CountDownLatch(1)
        val errors = AtomicInteger()

        val plannerFile = File.createTempFile("live-planner", ".json")
        val planner = PlannerStore(plannerFile)

        lateinit var client: LinkClient
        lateinit var transfer: FileTransfer

        client = LinkClient(object : LinkClient.Callbacks {
            override fun onState(state: LinkState, detail: String) {
                println("STATE $state $detail")
                if (state == LinkState.REJECTED) rejected.countDown()
            }

            override fun onEnvelope(envelope: Envelope) {
                when (envelope.type) {
                    "file.offer", "file.chunk", "file.done", "file.cancel", "file.ack" ->
                        transfer.handle(envelope)
                    "media.command" -> {
                        media.add("command ${envelope.str("action")} ${envelope.double("position")}")
                        println("MEDIA_COMMAND ${envelope.str("action")}")
                    }
                    "notification.push" ->
                        notifications.add("${envelope.str("app")}: ${envelope.str("title")} / ${envelope.str("body")}")
                    "media.state" ->
                        media.add("${envelope.str("title")} — ${envelope.str("artist")} playing=${envelope.bool("isPlaying")}")
                    "planner.sync", "planner.push" -> {
                        val incoming = PlannerStore.readPayload(envelope.payload)
                        val applied = planner.merge(incoming)
                        println("PLANNER ${envelope.type} пришло=${incoming.size} применено=${applied.size}")
                        applied.forEach { println("  · ${it.kind}: ${it.title}") }
                        plannerLatch.countDown()
                    }
                    else -> println("ENVELOPE ${envelope.type}")
                }
            }

            override fun onBinary(payload: ByteArray) = transfer.onBinary(payload)

            override fun onAuthOk(deviceId: String, name: String) {
                println("AUTH_OK device=$deviceId name=$name")
                authorized.countDown()
            }

            override fun onRejected(reason: String) {
                println("AUTH_REJECTED $reason")
                rejected.countDown()
            }
        })

        transfer = FileTransfer(client, null) { File(System.getProperty("java.io.tmpdir"), "di-live-downloads") }
        transfer.onProgress = { }
        transfer.onFinished = { progress, file ->
            println("FILE_OK ${progress.name} → ${file?.absolutePath} (${file?.length()} байт)")
            transferDone.countDown()
        }
        transfer.onFailed = { message ->
            println("FILE_FAIL $message")
            errors.incrementAndGet()
        }

        client.connect(pairing, pairing.token)
        if (!authorized.await(10, TimeUnit.SECONDS)) {
            println("RESULT auth=FAIL")
            client.close()
            return
        }

        // Снимок планера — как это делает LinkService при подключении.
        val local = planner.upsert(
            PlannerRecord(id = "", title = "Запись с телефона", start = System.currentTimeMillis() + 3_600_000, end = System.currentTimeMillis() + 7_200_000)
        )
        println("PLANNER локальная запись ${local.title} updated=${local.updated}")
        client.send("planner.sync", JSONObject().put("records", planner.snapshot()))

        // Уведомление и медиа — так их отправляют NotificationRelay и MediaBridge.
        client.send("notification.push", JSONObject().apply {
            put("key", "live-1")
            put("app", "DI Link")
            put("package", "com.dynamicisland.dilink")
            put("title", "Проверка связи")
            put("body", "Уведомление с телефона")
            put("ts", System.currentTimeMillis() / 1000)
        })
        client.send("media.state", JSONObject().apply {
            put("hasMedia", true)
            put("isPlaying", true)
            put("title", "Test Track")
            put("artist", "DI Link")
            put("album", "Live")
            put("duration", 210.0)
            put("position", 12.0)
            put("app", "DI Link")
        })

        plannerLatch.await(6, TimeUnit.SECONDS)
        val note = notifications.poll(6, TimeUnit.SECONDS)
        println("NOTIFICATION ${note ?: "не пришла"}")
        val mediaState = media.poll(6, TimeUnit.SECONDS)
        println("MEDIA ${mediaState ?: "не пришло"}")

        // Файл туда и обратно: предлагаем островку, ждём ack и льём чанки.
        val sample = File.createTempFile("di-live", ".txt").apply { writeText("DI Link live transfer\n".repeat(4000)) }
        println("SEND_FILE ${sample.name} ${sample.length()} байт")
        client.send("file.offer", JSONObject().apply {
            put("id", "live-file-1")
            put("name", sample.name)
            put("size", sample.length())
            put("mime", "text/plain")
            put("dir", "phone-to-pc")
        })

        // Ждём ack=0 и льём вручную: у FileTransfer отправка привязана к content:// URI.
        Thread.sleep(1500)
        val buffer = sample.readBytes()
        var offset = 0
        var seq = 0
        while (offset < buffer.size) {
            val chunk = buffer.copyOfRange(offset, minOf(offset + 64 * 1024, buffer.size))
            client.sendChunk("file.chunk", JSONObject().apply {
                put("id", "live-file-1")
                put("offset", offset)
                put("length", chunk.size)
                put("binary", true)
            }, chunk)
            offset += chunk.size
            seq++
            Thread.sleep(40)
        }
        client.send("file.done", JSONObject().put("id", "live-file-1"))
        println("SEND_DONE чанков=$seq байт=$offset")

        Thread.sleep(3000)
        println("ERRORS ${errors.get()}")
        println("RESULT auth=OK planner=${planner.count()} notifications=${if (note != null) 1 else 0}")
        client.close()
        plannerFile.delete()
    }
}
