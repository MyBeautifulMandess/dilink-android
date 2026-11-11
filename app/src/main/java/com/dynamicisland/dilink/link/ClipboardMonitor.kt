package com.dynamicisland.dilink.link

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.dynamicisland.dilink.util.Debounce

/**
 * Двусторонний буфер обмена.
 *
 * Система разрешает читать буфер только приложению на экране, поэтому копия,
 * сделанная в другом приложении, уходит на компьютер тогда, когда DI Link
 * остаётся активным. Вставка с компьютера работает всегда: писать в буфер
 * разрешено и из фона.
 */
class ClipboardMonitor(private val context: Context) {

    private val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    /** Текст, который мы сами положили в буфер: эхо возвращать не нужно. */
    @Volatile private var suppressed: String? = null

    @Volatile private var lastRemote: String = ""

    /** Колбэк для текста, скопированного на телефоне. */
    var onLocal: ((String) -> Unit)? = null

    private val debounce = Debounce(500) { flush() }
    private val pending = java.util.concurrent.atomic.AtomicReference("")

    private val listener = ClipboardManager.OnPrimaryClipChangedListener { readLocal() }

    fun start() {
        manager.addPrimaryClipChangedListener(listener)
    }

    fun stop() {
        manager.removePrimaryClipChangedListener(listener)
    }

    /** Применяет текст с компьютера в системный буфер. */
    fun applyRemote(text: String) {
        if (text.isEmpty()) return
        lastRemote = text
        suppressed = text
        runCatching { manager.setPrimaryClip(ClipData.newPlainText("DI Link", text)) }
    }

    /** Текущий буфер — для кнопки «отправить буфер на компьютер». */
    fun currentText(): String {
        val clip = manager.primaryClip ?: return ""
        if (clip.itemCount == 0) return ""
        return clip.getItemAt(0).coerceToText(context)?.toString() ?: ""
    }

    private fun readLocal() {
        val text = currentText()
        if (text.isEmpty()) return
        if (text == suppressed) {
            suppressed = null
            return
        }
        if (text == lastRemote) return
        pending.set(text)
        debounce.schedule()
    }

    private fun flush() {
        val text = pending.getAndSet("")
        if (text.isEmpty() || text == lastRemote) return
        onLocal?.invoke(text)
    }
}
