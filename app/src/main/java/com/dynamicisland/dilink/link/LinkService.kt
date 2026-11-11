package com.dynamicisland.dilink.link

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.dynamicisland.dilink.R
import com.dynamicisland.dilink.media.MediaBridge
import com.dynamicisland.dilink.planner.PlannerRepository
import com.dynamicisland.dilink.planner.PlannerStore
import com.dynamicisland.dilink.reminder.ReminderReconciler
import com.dynamicisland.dilink.store.Settings
import com.dynamicisland.dilink.ui.MainActivity
import com.dynamicisland.dilink.util.Observable
import org.json.JSONObject
import java.io.File

/**
 * Постоянное соединение с островком и вся логика, которая к нему привязана:
 * уведомления, буфер обмена, медиа, файлы, календарь и напоминания.
 *
 * Сервис живёт, пока пользователь не отключится сам: Android любит усыплять
 * фоновые процессы, поэтому канал держится foreground-уведомлением. Всё, что
 * приходит без связи (записи планера, файлы в очереди), остаётся на диске и
 * уходит при первом же подключении.
 */
class LinkService : Service() {

    companion object {
        private const val CHANNEL_SERVICE = "di_link_service"
        private const val CHANNEL_TRANSFER = "di_link_transfer"
        private const val NOTIFICATION_ID = 41
        const val ACTION_STOP = "com.dynamicisland.dilink.STOP"
        const val ACTION_DISCONNECT = "com.dynamicisland.dilink.DISCONNECT"

        /** Экземпляр для ответов из слушателя уведомлений и окна «Поделиться». */
        @Volatile
        var instance: LinkService? = null
            private set

        val state = Observable(LinkState.DISCONNECTED)
        val statusText = Observable("Не подключено")
        val transfers = Observable<List<FileTransfer.Progress>>(emptyList())
        val lastError = Observable("")
        val serverName = Observable("")

        fun start(context: Context) {
            val intent = Intent(context, LinkService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LinkService::class.java))
        }
    }

    private lateinit var settings: Settings
    private lateinit var planner: PlannerStore
    private var client: LinkClient? = null
    private var files: FileTransfer? = null
    private var clipboard: ClipboardMonitor? = null
    private var media: MediaBridge? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val transferList = LinkedHashMap<String, FileTransfer.Progress>()

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        planner = PlannerRepository.get(this)
        planner.seedPending()
        createChannels()
        startForeground(NOTIFICATION_ID, buildNotification("Не подключено", ""))

        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "di-link:socket")
            .apply { setReferenceCounted(false) }

        clipboard = ClipboardMonitor(this).apply {
            onLocal = { text -> send("clipboard.push", JSONObject().apply { put("text", text.take(20_000)); put("origin", "phone") }) }
        }
        media = MediaBridge(this).apply {
            onState = { payload -> send("media.state", payload) }
        }

        if (settings.isPaired && settings.autoConnect) connect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            disconnect()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            disconnect()
            stopSelf()
            return START_NOT_STICKY
        }
        if (settings.isPaired && client == null) connect()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        disconnect()
        super.onDestroy()
    }

    /** Сохранённое сопряжение: адрес, порт и ключ устройства. */
    private fun connect() {
        val token = settings.deviceToken
        if (token.isEmpty() || settings.address.isEmpty()) {
            statusText.set("Устройство не сопряжено")
            return
        }
        val target = Pairing(settings.address, settings.port, token)
        val old = client
        old?.disconnect()
        val link = LinkClient(object : LinkClient.Callbacks {
            override fun onState(state: LinkState, detail: String) {
                Companion.state.set(state)
                when (state) {
                    LinkState.CONNECTED -> {
                        statusText.set("Подключено к ${target.display}")
                        notifyBar("Подключено", target.display)
                    }
                    LinkState.CONNECTING -> {
                        statusText.set("Подключение…")
                        notifyBar("Подключение…", target.display)
                    }
                    LinkState.REJECTED -> {
                        statusText.set("Ключ не принят — сопрягите заново")
                        notifyBar("Ключ не принят", "Сопрягите телефон заново")
                    }
                    LinkState.DISCONNECTED -> {
                        statusText.set("Не подключено")
                        stopForegroundCompat()
                    }
                }
            }

            override fun onEnvelope(envelope: Envelope) = handle(envelope)

            override fun onBinary(payload: ByteArray) {
                files?.onBinary(payload)
            }

            override fun onAuthOk(deviceId: String, name: String) {
                if (deviceId.isNotEmpty()) settings.deviceId = deviceId
                serverName.set(name)
                statusText.set("Подключено к ${target.display}")
                // Полный снимок планера: всё, что накопилось без связи, уходит разом.
                if (settings.plannerSync) {
                    send("planner.sync", JSONObject().apply { put("records", planner.snapshot()) })
                }
                startExtras()
            }

            override fun onRejected(reason: String) {
                statusText.set(reason.ifEmpty { "Ключ не принят" })
                settings.forget()
            }
        })
        client = link

        files = FileTransfer(link, contentResolver) { downloadsFolder() }.apply {
            onProgress = { progress -> updateTransfer(progress) }
            onFinished = { progress, file ->
                updateTransfer(progress)
                notifyTransfer(progress, file)
            }
            onFailed = { message -> lastError.set(message) }
        }

        wakeLock?.acquire(10 * 60 * 1000L)
        link.connect(target, token)
    }

    private fun disconnect() {
        if (::settings.isInitialized) clipboard?.stop()
        media?.stop()
        client?.disconnect()
        client = null
        files = null
        Companion.state.set(LinkState.DISCONNECTED)
        statusText.set("Не подключено")
        wakeLock?.let { if (it.isHeld) it.release() }
        stopForegroundCompat()
    }

    /** Включает то, что имеет смысл только при живом канале. */
    private fun startExtras() {
        if (settings.clipboardSync) clipboard?.start() else clipboard?.stop()
        ReminderReconciler.sync(this)
        if (settings.mediaSync && media?.hasAccess == true) media?.start() else media?.stop()
    }

    private fun handle(envelope: Envelope) {
        when (envelope.type) {
            "ping" -> send("pong", JSONObject().put("ts", System.currentTimeMillis() / 1000))
            "clipboard.push" -> {
                if (!settings.clipboardSync) return
                val text = Protocol.clamp(envelope.str("text"), 20_000)
                if (text.isEmpty()) return
                clipboard?.applyRemote(text)
            }
            "notification.push" -> Unit
            "notify.dismissed" -> Unit
            "file.offer", "file.chunk", "file.done", "file.cancel", "file.ack" ->
                if (settings.fileTransfer) files?.handle(envelope)
            "media.command" -> {
                if (!settings.mediaSync) return
                media?.command(envelope.str("action"), envelope.double("position"))
            }
            "media.state" -> Unit
            "planner.push", "planner.sync" -> {
                if (!settings.plannerSync) return
                val incoming = PlannerStore.readPayload(envelope.payload)
                if (incoming.isNotEmpty() && planner.merge(incoming).isNotEmpty()) {
                    // Запись могла прийти из компьютера вместе с новым сроком:
                    // будильник и уведомление пересобираются сразу.
                    ReminderReconciler.sync(this)
                }
            }
        }
    }

    /** Отправка кадра; при отсутствии связи он копится в очереди клиента. */
    fun send(type: String, payload: JSONObject = JSONObject()) {
        client?.send(type, payload)
    }

    fun pushNotification(payload: JSONObject) {
        if (!settings.notifications) return
        if (!allowedOnCurrentNetwork()) return
        client?.send("notification.push", payload)
    }

    fun pushDismissed(key: String) {
        if (!settings.notifications) return
        client?.send("notify.dismissed", JSONObject().put("key", key.take(160)))
    }

    /** Файл, выбранный в «Поделиться» или в самом приложении. */
    fun sendFile(uri: Uri) {
        if (!settings.fileTransfer) {
            lastError.set("Передача файлов выключена в настройках")
            return
        }
        val transfer = files
        if (transfer == null || client?.isAuthorized != true) {
            lastError.set("Нет связи с компьютером")
            return
        }
        transfer.send(uri)
    }

    fun plannerStore(): PlannerStore = planner

    fun settingsStore(): Settings = settings

    /** Записи, изменённые на телефоне, уходят сразу; без связи ждут подключения. */
    fun pushPlanner(record: com.dynamicisland.dilink.planner.PlannerRecord) {
        if (!settings.plannerSync) return
        send("planner.push", JSONObject().apply { put("records", org.json.JSONArray().put(record.toJson())) })
    }

    fun reconnect() {
        disconnect()
        if (settings.isPaired) connect()
    }

    /** Кнопка «Отключиться»: рвём канал, но сопряжение остаётся. */
    fun disconnectNow() {
        disconnect()
    }

    /** Текст системного буфера — для ручной отправки с экрана связи. */
    fun currentClipboard(): String = clipboard?.currentText() ?: ""

    /**
     * Перечитывает доступы. Вызывается при возврате в приложение: пользователь
     * мог только что разрешить уведомления, а вместе с ними и медиасессии.
     */
    fun refreshAccess() {
        startExtras()
    }

    fun updateSettings(changed: (Settings) -> Unit) {
        changed(settings)
        startExtras()
    }

    /** Сотовый трафик не расходуем, если пользователь включил режим «только Wi-Fi». */
    private fun allowedOnCurrentNetwork(): Boolean {
        if (!settings.wifiOnly) return true
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun updateTransfer(progress: FileTransfer.Progress) {
        transferList[progress.id] = progress
        val snapshot = transferList.values.toList()
        transfers.set(snapshot)
    }

    private fun downloadsFolder(): File {
        val public = File(File(android.os.Environment.getExternalStorageDirectory(), "Download"), "DI Link")
        return if (public.canWrite() || public.mkdirs()) public else File(getExternalFilesDir(null), "received")
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, getString(R.string.channel_service), NotificationManager.IMPORTANCE_MIN).apply {
                description = getString(R.string.channel_service_desc)
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_TRANSFER, getString(R.string.channel_transfer), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.channel_transfer_desc)
            }
        )
    }

    private fun buildNotification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, LinkService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_status)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .addAction(0, getString(R.string.action_disconnect), stop)
            .build()
    }

    private fun notifyBar(title: String, text: String) {
        if (!foreground) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    private fun notifyTransfer(progress: FileTransfer.Progress, file: File?) {
        if (progress.failed != null) return
        val manager = getSystemService(NotificationManager::class.java)
        val text = if (progress.incoming) {
            file?.let { "Сохранено: ${it.name}" } ?: progress.name
        } else "Отправлено: ${progress.name}"
        manager.notify(
            progress.id.hashCode(),
            NotificationCompat.Builder(this, CHANNEL_TRANSFER)
                .setSmallIcon(R.drawable.ic_status)
                .setContentTitle(if (progress.incoming) "Файл с компьютера" else "Файл отправлен")
                .setContentText(text)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        )
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
    }

    private val foreground: Boolean
        get() = Companion.state.get() != LinkState.DISCONNECTED
}
