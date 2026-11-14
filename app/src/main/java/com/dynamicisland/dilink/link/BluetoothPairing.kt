package com.dynamicisland.dilink.link

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/**
 * Знакомство с компьютером по Bluetooth.
 *
 * Телефон сам находит компьютер рядом и просит у него ключ сопряжения:
 * без камеры, без QR и без общей сети. Так же устроены Quick Share и связь
 * с телефоном у Windows — устройство нашли рядом, человек подтвердил, и пошёл
 * обмен.
 *
 * Сначала пробуется основной канал компьютера: он зашифрован и требует
 * системной пары устройств. Если телефон с компьютером ещё не сопряжен,
 * подключение идёт к открытой службе знакомства, где компьютер спрашивает
 * разрешение и выдаёт одноразовый ключ.
 */
class BluetoothPairing(private val context: Context) {

    /** Устройство рядом: имя для человека и адрес для подключения. */
    data class Device(val name: String, val address: String, val bonded: Boolean)

    /** Ответ компьютера: ключ и сетевой адрес для быстрого канала. */
    data class Offer(val pairing: Pairing, val computerName: String)

    /** Последняя ошибка подключения — для показа в интерфейсе. */
    var lastError: String = ""
        private set

    /** Разрешения, без которых Bluetooth недоступен. */
    fun requiredPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN
        )
        // До Android 12 поиск устройств требует доступ к местоположению.
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        else -> emptyArray()
    }

    fun hasPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun isBluetoothOn(): Boolean = adapter()?.isEnabled == true

    /** Устройства, которые телефон уже знает, плюс найденные рядом. */
    fun findDevices(timeoutMs: Long = 8_000): List<Device> {
        val adapter = adapter() ?: return emptyList()
        val found = LinkedHashMap<String, Device>()
        runCatching {
            adapter.bondedDevices?.forEach { device ->
                found[device.address] = Device(device.name ?: device.address, device.address, true)
            }
        }
        if (!hasPermissions()) return found.values.toList()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent?.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent?.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                } ?: return
                val name = device.name ?: found[device.address]?.name ?: device.address
                found[device.address] = Device(name, device.address, false)
            }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        try {
            runCatching { if (adapter.isDiscovering) adapter.cancelDiscovery() }
            adapter.startDiscovery()
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) Thread.sleep(200)
        } finally {
            runCatching { adapter.cancelDiscovery() }
            runCatching { context.unregisterReceiver(receiver) }
        }
        return found.values.toList()
    }

    /**
     * Просит у компьютера ключ сопряжения. Основной канал и служба знакомства
     * перебираются по очереди: какой сработает — тот и будет рабочим дальше.
     */
    fun requestOffer(address: String): Offer? {
        val adapter = adapter() ?: return null
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            lastError = "Неверный Bluetooth-адрес"
            return null
        }
        for (uuid in SERVICE_UUIDS) {
            try {
                return requestOffer(device, uuid)
            } catch (e: IOException) {
                lastError = e.message ?: "Bluetooth недоступен"
            } catch (e: SecurityException) {
                lastError = "Нет разрешения на Bluetooth"
                return null
            }
        }
        return null
    }

    /**
     * Рукопожатие с компьютером. У BluetoothSocket нет таймера чтения, поэтому
     * разговор идёт в отдельном потоке, а ожидание ограничено по времени:
     * иначе телефон ждал бы ответа компьютера вечно.
     */
    private fun requestOffer(device: BluetoothDevice, uuid: UUID): Offer {
        val socket = device.createRfcommSocketToServiceRecord(uuid)
        var result: Offer? = null
        var failure: Exception? = null
        val worker = Thread {
            try {
                socket.connect()
                val out = socket.outputStream
                val input = socket.inputStream
                val hello = Protocol.build("pairing.hello", JSONObject().apply {
                    put("name", DeviceIdentity.label())
                    put("instance", DeviceIdentity.instanceId(context))
                })
                BtFrames.write(out, BtFrames.KIND_TEXT, hello.toByteArray(Charsets.UTF_8))
                while (true) {
                    val frame = BtFrames.read(input) ?: throw IOException("Соединение закрыто")
                    if (frame.first != BtFrames.KIND_TEXT) continue
                    val envelope = Protocol.parse(String(frame.second, Charsets.UTF_8)) ?: continue
                    when (envelope.type) {
                        "pairing.offer" -> {
                            val token = envelope.str("token")
                            if (token.isEmpty()) throw IOException("Компьютер не прислал ключ")
                            val ip = envelope.str("ip")
                            val port = envelope.long("port", Pairing.DEFAULT_PORT.toLong()).toInt()
                            val name = envelope.str("name")
                            BtService.rememberUuid(context, device.address, uuid)
                            result = Offer(Pairing(ip, port, token, device.address), name)
                            return@Thread
                        }
                        "pairing.rejected" ->
                            throw IOException(envelope.str("reason").ifEmpty { "Сопряжение отклонено" })
                    }
                }
            } catch (e: Exception) {
                failure = e
            }
        }
        worker.isDaemon = true
        worker.start()
        worker.join(30_000)
        try {
            if (worker.isAlive) {
                // Компьютер молчит: закрываем сокет, чтобы разбудить чтение.
                runCatching { socket.close() }
                worker.join(1_000)
                throw IOException("Компьютер не ответил")
            }
            failure?.let { throw it }
            return result ?: throw IOException("Компьютер не ответил")
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun adapter(): BluetoothAdapter? {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter
    }

    companion object {
        /** Основной канал компьютера: зашифрованный, требует системной пары. */
        val SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")

        /** Служба знакомства: работает, пока телефон ещё не сопряжён с компьютером. */
        val PAIRING_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")

        private val SERVICE_UUIDS = listOf(SERVICE_UUID, PAIRING_UUID)
    }
}

/**
 * Какая служба сработала с этим компьютером. Запоминается, чтобы при каждом
 * переподключении не перебирать заново: если телефон не сопряжён с
 * компьютером в системных настройках, основной канал закрыт, и работает
 * открытая служба знакомства.
 */
internal object BtService {
    private const val PREFS = "di_link_settings"
    private const val KEY_PREFIX = "bt_service_uuid_"

    fun workingUuid(context: Context, address: String): UUID? {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PREFIX + address, null) ?: return null
        return runCatching { UUID.fromString(value) }.getOrNull()
    }

    fun rememberUuid(context: Context, address: String, uuid: UUID) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PREFIX + address, uuid.toString()).apply()
    }
}
