package com.dynamicisland.dilink.link

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Канал по Bluetooth (RFCOMM). Компьютер публикует службу с этим UUID, телефон
 * подключается к ней как клиент: так же устроены Quick Share и связь с
 * телефоном у Windows, где доверие даёт системная пара устройств.
 *
 * Wi-Fi сеть здесь не нужна вовсе. Канал держится до тех пор, пока устройства
 * физически на связи: чтение блокируется на сокете, поэтому обрыв замечается
 * сразу, а не по таймауту простоя.
 */
class BluetoothTransport(
    private val context: Context,
    private val address: String
) : LinkTransport {

    override val name: String = "Bluetooth"

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var input: InputStream? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var listener: LinkTransport.Listener? = null
    @Volatile private var closed = false
    private val writeLock = Any()
    private var reader: Thread? = null

    /**
     * Служба, по которой удалось подключиться. Если телефон не сопряжён с
     * компьютером в системных настройках, работает открытая служба знакомства —
     * значит, и обмен данными пойдёт по ней же.
     */
    @Volatile private var workingUuid: UUID? =
        BtService.workingUuid(context, address)

    override fun open(listener: LinkTransport.Listener): Boolean {
        this.listener = listener
        closed = false
        reader = Thread { runChannel(listener) }.apply {
            isDaemon = true
            name = "di-link-bt"
        }.also { it.start() }
        return true
    }

    private fun runChannel(listener: LinkTransport.Listener) {
        try {
            val device = remoteDevice() ?: run {
                listener.onClosed("Bluetooth-адрес компьютера не задан")
                return
            }
            var lastError = "Bluetooth недоступен"
            // Сначала та служба, по которой уже шёл обмен, потом основная
            // (зашифрованная) и, наконец, открытая — на случай, когда телефон
            // ещё не сопряжён с компьютером в системных настройках.
            val candidates = listOfNotNull(workingUuid) + listOf(
                BluetoothPairing.SERVICE_UUID,
                BluetoothPairing.PAIRING_UUID
            ).filter { it != workingUuid }
            for (uuid in candidates.distinct()) {
                if (closed) return
                try {
                    val channel = device.createRfcommSocketToServiceRecord(uuid)
                    socket = channel
                    try {
                        channel.connect()
                    } catch (e: SecurityException) {
                        listener.onClosed("Нет разрешения на Bluetooth")
                        return
                    }
                    workingUuid = uuid
                    BtService.rememberUuid(context, address, uuid)
                    input = channel.inputStream
                    output = channel.outputStream
                    listener.onOpen()
                    readLoop(listener)
                    return
                } catch (e: IOException) {
                    lastError = e.message ?: lastError
                    runCatching { socket?.close() }
                    socket = null
                    input = null
                    output = null
                }
            }
            listener.onClosed(lastError)
        } catch (e: IOException) {
            listener.onClosed(e.message ?: "Bluetooth недоступен")
        } catch (e: Exception) {
            listener.onClosed(e.message ?: "Bluetooth недоступен")
        } finally {
            release()
        }
    }

    private fun readLoop(listener: LinkTransport.Listener) {
        val stream = input ?: return
        while (!closed) {
            val frame = BtFrames.read(stream) ?: break
            when (frame.first) {
                BtFrames.KIND_TEXT -> listener.onText(String(frame.second, Charsets.UTF_8))
                BtFrames.KIND_BINARY -> listener.onBinary(frame.second)
            }
        }
        if (!closed) listener.onClosed("Bluetooth-канал закрыт")
    }

    override fun send(text: String): Boolean =
        writeFrame(BtFrames.KIND_TEXT, text.toByteArray(Charsets.UTF_8))

    /** Заголовок и данные уходят под одной блокировкой: иначе чанк склеится с чужим кадром. */
    override fun sendAndBinary(text: String, data: ByteArray): Boolean = synchronized(writeLock) {
        writeFrame(BtFrames.KIND_TEXT, text.toByteArray(Charsets.UTF_8)) &&
            writeFrame(BtFrames.KIND_BINARY, data)
    }

    private fun writeFrame(kind: Int, payload: ByteArray): Boolean = synchronized(writeLock) {
        val stream = output ?: return false
        return try {
            BtFrames.write(stream, kind, payload)
            true
        } catch (e: IOException) {
            false
        }
    }

    private fun remoteDevice(): BluetoothDevice? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter = manager?.adapter ?: return null
        if (!adapter.isEnabled) return null
        return try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    override fun close() {
        closed = true
        release()
    }

    private fun release() {
        try { socket?.close() } catch (ignored: IOException) { }
        socket = null
        input = null
        output = null
    }
}
