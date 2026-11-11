package com.dynamicisland.dilink.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.dynamicisland.dilink.link.Protocol
import com.dynamicisland.dilink.notify.NotificationRelay
import java.io.ByteArrayOutputStream

/**
 * Отдаёт компьютеру то, что играет на телефоне, и принимает от него команды.
 *
 * Активные сессии видны только приложению с доступом к уведомлениям — без него
 * телефония просто не показывает плееры другим программам. Поэтому мост
 * включается вместе с [NotificationRelay] и молча ждёт, пока доступ дадут.
 */
class MediaBridge(private val context: Context) {

    private val sessions =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager

    private val listenerComponent = ComponentName(context, NotificationRelay::class.java)

    private val handler = Handler(Looper.getMainLooper())

    private var controller: MediaController? = null
    private var lastPayload: String = ""
    private var lastArtworkTrack: String = ""
    private var lastArtwork: String = ""

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onSessionDestroyed() {
            controller = null
            publish()
        }
    }

    /** Отправка состояния на компьютер. Вызывается на главном потоке. */
    var onState: ((org.json.JSONObject) -> Unit)? = null

    val hasAccess: Boolean
        get() = runCatching {
            sessions.getActiveSessions(listenerComponent)
            true
        }.getOrDefault(false)

    fun start() {
        refresh()
        handler.postDelayed(poll, POLL)
    }

    fun stop() {
        handler.removeCallbacks(poll)
        controller?.unregisterCallback(callback)
        controller = null
    }

    private val poll = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, POLL)
        }
    }

    /** Пересобирает привязку к активной сессии: плеер мог смениться. */
    fun refresh() {
        val active = runCatching { sessions.getActiveSessions(listenerComponent) }
            .getOrElse { emptyList() }
            .filter { it.playbackState != null }
            .sortedByDescending { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            .firstOrNull()

        if (active?.sessionToken == controller?.sessionToken) {
            publish()
            return
        }
        controller?.unregisterCallback(callback)
        controller = active
        active?.registerCallback(callback, handler)
        publish()
    }

    /** Команда с компьютера: toggle, next, prev, seek. */
    fun command(action: String, positionSeconds: Double) {
        val transport = controller?.transportControls ?: run {
            refresh()
            controller?.transportControls
        } ?: return
        when (action) {
            "toggle" -> {
                val state = controller?.playbackState?.state
                if (state == PlaybackState.STATE_PLAYING) transport.pause() else transport.play()
            }
            "next" -> transport.skipToNext()
            "prev" -> transport.skipToPrevious()
            "seek" -> transport.seekTo((positionSeconds * 1000).toLong().coerceAtLeast(0))
        }
    }

    private fun publish() {
        val current = controller
        if (current == null) {
            onState?.invoke(org.json.JSONObject().apply { put("hasMedia", false) })
            lastPayload = ""
            return
        }
        val metadata = current.metadata
        val state = current.playbackState
        if (metadata == null || state == null) {
            onState?.invoke(org.json.JSONObject().apply { put("hasMedia", false) })
            lastPayload = ""
            return
        }

        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.take(200) ?: ""
        val artist = (metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST))?.take(120) ?: ""
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)?.take(120) ?: ""
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION) / 1000.0
        val isPlaying = state.state == PlaybackState.STATE_PLAYING
        // Оценка позиции: состояние приходит не каждый кадр, поэтому доигрываем
        // его сами от времени последнего обновления.
        val elapsed = if (isPlaying) {
            (android.os.SystemClock.elapsedRealtime() - state.lastPositionUpdateTime)
                .coerceAtLeast(0) / 1000.0
        } else 0.0
        val position = (state.position / 1000.0 + elapsed).coerceAtLeast(0.0)

        val app = packageLabel(current.packageName)
        val track = "$title\u0000$artist"
        val artwork = artworkBase64(metadata, track)

        val payload = org.json.JSONObject().apply {
            put("hasMedia", true)
            put("isPlaying", isPlaying)
            put("title", title)
            put("artist", artist)
            put("album", album)
            put("duration", duration)
            put("position", position)
            put("app", app)
            if (artwork.isNotEmpty()) put("artwork", artwork)
        }

        // Одинаковые состояния не гоняем по сети — только позиция меняется,
        // компьютер доигрывает её сам.
        val signature = "$track\u0000$isPlaying\u0000${duration.toInt()}\u0000$position".take(400)
        if (signature == lastPayload) return
        lastPayload = signature
        onState?.invoke(payload)
    }

    /** Обложка кэшируется по треку: base64 на 60 КБ каждый кадр — лишний трафик. */
    private fun artworkBase64(metadata: MediaMetadata, track: String): String {
        if (track == lastArtworkTrack) return lastArtwork
        val bitmap: Bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: return ""
        val scaled = runCatching { Bitmap.createScaledBitmap(bitmap, 240, 240, true) }.getOrNull()
            ?: return ""
        val bytes = ByteArrayOutputStream().use { output ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, output)
            output.toByteArray()
        }
        if (bytes.size > MAX_ARTWORK) return ""
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        lastArtworkTrack = track
        lastArtwork = encoded
        return encoded
    }

    private fun packageLabel(packageName: String): String = runCatching {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName).take(60)

    private companion object {
        const val POLL = 2_000L
        const val MAX_ARTWORK = 60_000
    }
}
