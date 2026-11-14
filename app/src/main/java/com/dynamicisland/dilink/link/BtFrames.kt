package com.dynamicisland.dilink.link

import java.io.InputStream
import java.io.OutputStream

/**
 * Кадры канала Bluetooth: пятибайтовый заголовок — вид (1 — текст, 2 — данные)
 * и длина big-endian. Формат один и для переноса файлов, и для рукопожатия
 * при первом знакомстве.
 */
internal object BtFrames {
    const val HEADER_SIZE = 5
    const val KIND_TEXT = 1
    const val KIND_BINARY = 2
    const val MAX_PAYLOAD = 1024 * 1024

    fun encode(kind: Int, payload: ByteArray): ByteArray {
        val frame = ByteArray(HEADER_SIZE + payload.size)
        frame[0] = kind.toByte()
        frame[1] = (payload.size ushr 24).toByte()
        frame[2] = (payload.size ushr 16).toByte()
        frame[3] = (payload.size ushr 8).toByte()
        frame[4] = payload.size.toByte()
        System.arraycopy(payload, 0, frame, HEADER_SIZE, payload.size)
        return frame
    }

    /** Читает кадр целиком. null — поток закрыт или пришёл мусор. */
    fun read(input: InputStream): Pair<Int, ByteArray>? {
        val header = ByteArray(HEADER_SIZE)
        if (!readFully(input, header)) return null
        val kind = header[0].toInt() and 0xFF
        val length = ((header[1].toLong() and 0xFF) shl 24) or
            ((header[2].toLong() and 0xFF) shl 16) or
            ((header[3].toLong() and 0xFF) shl 8) or
            (header[4].toLong() and 0xFF)
        if (length < 0 || length > MAX_PAYLOAD) return null
        val payload = ByteArray(length.toInt())
        if (payload.isNotEmpty() && !readFully(input, payload)) return null
        return kind to payload
    }

    fun write(output: OutputStream, kind: Int, payload: ByteArray) {
        output.write(encode(kind, payload))
        output.flush()
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read <= 0) return false
            offset += read
        }
        return true
    }
}
