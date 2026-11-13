package com.dynamicisland.dilink.link

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID

/**
 * Обмен файлами с компьютером.
 *
 * Входящие пишутся во временный `.part`, а переименовываются только после
 * подтверждения `file.done`: оборванная передача не оставляет битых файлов в
 * «Загрузках». Имена санируются, поэтому `..\..\evil.exe` не выйдет за папку.
 */
class FileTransfer(
    private val client: LinkClient,
    private val resolver: ContentResolver?,
    private val targetDirectory: () -> File,
    private val gallery: GallerySaver? = null
) {
    data class Progress(
        val id: String,
        val name: String,
        val incoming: Boolean,
        val transferred: Long,
        val total: Long,
        val done: Boolean = false,
        val failed: String? = null,
        /** Куда лег файл: абсолютный путь на диске. */
        val savedPath: String? = null,
        /** То же самое словами: «Pictures/DI Link» или «Download/DI Link». */
        val savedLocation: String? = null,
        /** Адрес в галерее, если файл туда попал. */
        val savedUri: String? = null,
        val inGallery: Boolean = false
    ) {
        val percent: Int get() = if (total <= 0) 0 else ((transferred * 100) / total).toInt().coerceIn(0, 100)
    }

    private data class Incoming(
        val id: String,
        val name: String,
        val target: File,
        val part: File,
        val total: Long,
        var received: Long = 0
    )

    private data class Outgoing(
        val id: String,
        val name: String,
        val uri: Uri,
        val total: Long,
        var started: Boolean = false
    )

    private val incoming = HashMap<String, Incoming>()
    private val outgoing = HashMap<String, Outgoing>()
    private val lock = Any()
    private var pendingChunk: Pair<String, Long>? = null

    /** Колбэк прогресса вызывается из сетевого потока. */
    var onProgress: ((Progress) -> Unit)? = null
    var onFinished: ((Progress) -> Unit)? = null
    var onFailed: ((String) -> Unit)? = null

    fun handle(envelope: Envelope) {
        when (envelope.type) {
            "file.offer" -> accept(envelope)
            "file.chunk" -> prepareChunk(envelope)
            "file.done" -> finish(envelope.str("id"))
            "file.cancel" -> cancelIncoming(envelope.str("id"))
            "file.ack" -> acknowledge(envelope)
        }
    }

    private fun accept(envelope: Envelope) {
        if (!envelope.str("dir").equals("pc-to-phone", ignoreCase = true)) return
        val id = envelope.str("id")
        val name = safeName(envelope.str("name"))
        val size = envelope.long("size")
        if (id.isEmpty() || name.isEmpty()) return
        if (size <= 0 || size > MAX_BYTES) {
            client.send("file.cancel", org.json.JSONObject().apply { put("id", id); put("reason", "size") })
            onFailed?.invoke("Файл больше 2 ГБ не поддерживается")
            return
        }
        val folder = targetDirectory()
        folder.mkdirs()
        val target = uniqueFile(folder, name)
        val part = File(folder, ".di-$id.part")
        synchronized(lock) {
            incoming[id] = Incoming(id, name, target, part, size)
            pendingChunk = null
        }
        client.send("file.ack", org.json.JSONObject().apply { put("id", id); put("offset", 0) })
        report(Progress(id, name, true, 0, size))
    }

    private fun prepareChunk(envelope: Envelope) {
        val id = envelope.str("id")
        if (id.isEmpty()) return
        synchronized(lock) { pendingChunk = id to envelope.long("offset") }
    }

    /** Бинарный кадр не несёт id: он относится к последнему заголовку `file.chunk`. */
    fun onBinary(payload: ByteArray) {
        if (payload.isEmpty()) return
        var item: Incoming? = null
        synchronized(lock) {
            val pending = pendingChunk
            pendingChunk = null
            if (pending != null) item = incoming[pending.first]
        }
        val current = item ?: return
        try {
            current.part.parentFile?.mkdirs()
            java.io.RandomAccessFile(current.part, "rw").use { file ->
                if (current.received == 0L) file.setLength(0)
                file.seek(current.received)
                val room = minOf(payload.size.toLong(), current.total - current.received)
                if (room <= 0) return
                file.write(payload, 0, room.toInt())
            }
            current.received += minOf(payload.size.toLong(), current.total - current.received)
            val received = current.received
            val shouldAck = received % ACK_EVERY < CHUNK || received >= current.total
            if (shouldAck) {
                client.send("file.ack", org.json.JSONObject().apply { put("id", current.id); put("offset", received) })
            }
            report(Progress(current.id, current.name, true, received, current.total))
        } catch (e: Exception) {
            fail(current.id, "Запись файла прервана: ${e.message}")
        }
    }

    private fun finish(id: String) {
        val item = synchronized(lock) { incoming.remove(id) } ?: return
        try {
            val length = if (item.part.exists()) item.part.length() else 0L
            if (length < item.total) {
                item.part.delete()
                fail(id, "Передача прервалась — файл не сохранён")
                return
            }
            if (item.target.exists()) item.target.delete()
            if (!item.part.renameTo(item.target)) {
                item.part.copyTo(item.target, overwrite = true)
                item.part.delete()
            }
            // Картинки сразу уезжают в галерею, остальные остаются в «Загрузках».
            val saved = if (gallery != null && GallerySaver.isImage(item.name)) {
                gallery.save(item.target, GallerySaver.mimeFor(item.name))
            } else {
                null
            }
            if (saved != null) item.target.delete()
            val done = Progress(
                id = id,
                name = item.name,
                incoming = true,
                transferred = item.total,
                total = item.total,
                done = true,
                savedPath = saved?.path ?: item.target.absolutePath,
                savedLocation = saved?.location ?: item.target.parent,
                savedUri = saved?.uri?.toString(),
                inGallery = saved != null
            )
            report(done)
            onFinished?.invoke(done)
        } catch (e: Exception) {
            fail(id, "Не удалось сохранить файл: ${e.message}")
        }
    }

    private fun cancelIncoming(id: String) {
        val item = synchronized(lock) { incoming.remove(id) } ?: return
        item.part.delete()
        onFailed?.invoke("Передача отменена")
    }

    /** Компьютер начал передачу после нашего согласия — качаем по чанкам. */
    private fun acknowledge(envelope: Envelope) {
        val id = envelope.str("id")
        val item = synchronized(lock) {
            val found = outgoing[id] ?: return@synchronized null
            if (found.started) return@synchronized null
            if (envelope.long("offset") != 0L) return@synchronized null
            found.started = true
            found
        } ?: return
        Thread { pump(item) }.apply { isDaemon = true }.start()
    }

    private fun pump(item: Outgoing) {
        try {
            val input = resolver?.openInputStream(item.uri) ?: run {
                fail(item.id, "Не удалось открыть файл")
                return
            }
            input.use { stream ->
                val buffer = ByteArray(CHUNK)
                var offset = 0L
                while (offset < item.total) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    val chunk = if (read == buffer.size) buffer.copyOf() else buffer.copyOf(read)
                    val meta = org.json.JSONObject().apply {
                        put("id", item.id)
                        put("offset", offset)
                        put("length", chunk.size)
                        put("binary", true)
                    }
                    client.sendChunk("file.chunk", meta, chunk)
                    offset += chunk.size
                    report(Progress(item.id, item.name, false, offset, item.total))
                    if (offset % ACK_EVERY < CHUNK) Thread.sleep(15)
                }
            }
            client.send("file.done", org.json.JSONObject().apply { put("id", item.id) })
            val done = Progress(item.id, item.name, false, item.total, item.total, done = true)
            report(done)
            onFinished?.invoke(done)
        } catch (e: Exception) {
            fail(item.id, "Отправка прервалась: ${e.message}")
            client.send("file.cancel", org.json.JSONObject().apply { put("id", item.id); put("reason", "read") })
        } finally {
            synchronized(lock) { outgoing.remove(item.id) }
        }
    }

    /** Отправка файла с телефона: сначала предложение, данные — после `file.ack`. */
    fun send(uri: Uri) {
        val name = queryName(uri) ?: "file"
        val size = querySize(uri)
        if (size <= 0) {
            onFailed?.invoke("Не удалось определить размер файла")
            return
        }
        if (size > MAX_BYTES) {
            onFailed?.invoke("Файл больше 2 ГБ не поддерживается")
            return
        }
        val id = UUID.randomUUID().toString().replace("-", "")
        synchronized(lock) { outgoing[id] = Outgoing(id, safeName(name), uri, size) }
        val meta = org.json.JSONObject().apply {
            put("id", id)
            put("name", safeName(name))
            put("size", size)
            put("mime", resolver?.getType(uri) ?: "application/octet-stream")
            put("dir", "phone-to-pc")
        }
        client.send("file.offer", meta)
        report(Progress(id, safeName(name), false, 0, size))
    }

    fun cancel(id: String) {
        val out = synchronized(lock) { outgoing.remove(id) }
        if (out != null) {
            client.send("file.cancel", org.json.JSONObject().apply { put("id", id); put("reason", "user") })
            return
        }
        val inc = synchronized(lock) { incoming.remove(id) } ?: return
        inc.part.delete()
        client.send("file.cancel", org.json.JSONObject().apply { put("id", id); put("reason", "user") })
    }

    private fun report(progress: Progress) = onProgress?.invoke(progress)

    private fun fail(id: String, message: String) {
        onFailed?.invoke(message)
        onProgress?.invoke(Progress(id, "", true, 0, 0, failed = message))
    }

    private fun queryName(uri: Uri): String? = try {
        resolver?.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    } catch (e: Exception) {
        null
    }

    private fun querySize(uri: Uri): Long = try {
        resolver?.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getLong(index) else -1L
        } ?: -1L
    } catch (e: Exception) {
        -1L
    }

    companion object {
        const val CHUNK = 64 * 1024
        const val ACK_EVERY = 512 * 1024L
        const val MAX_BYTES = 2L * 1024 * 1024 * 1024

        private val RESERVED = setOf(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
        )

        /** Оставляет только имя: без пути, запрещённых символов и зарезервированных имён. */
        fun safeName(raw: String): String {
            val stripped = raw.substringAfterLast('/').substringAfterLast('\\')
            val cleaned = stripped
                .replace(Regex("""[<>:"/\\|?*\u0000-\u001F]"""), "_")
                .trim('.', ' ')
            val name = cleaned.ifEmpty { "file" }.take(120)
            val base = name.substringBeforeLast('.').uppercase()
            return if (base in RESERVED) "_$name" else name
        }

        fun uniqueFile(folder: File, name: String): File {
            var candidate = File(folder, name)
            if (!candidate.exists()) return candidate
            val dot = name.lastIndexOf('.')
            val stem = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ""
            var index = 1
            while (true) {
                candidate = File(folder, "$stem ($index)$ext")
                if (!candidate.exists()) return candidate
                index++
            }
        }
    }
}
