package com.dynamicisland.dilink.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.dynamicisland.dilink.LinkApp
import com.dynamicisland.dilink.R
import com.dynamicisland.dilink.link.LinkService
import com.dynamicisland.dilink.link.LinkState
import com.dynamicisland.dilink.store.Settings

/**
 * Приём файлов из меню «Поделиться». Экран прозрачный: пользователь видит только
 * тост, а файл уходит на компьютер.
 */
class ShareActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = Settings(this)
        if (!settings.isPaired || !settings.fileTransfer) {
            Toast.makeText(this, R.string.share_no_link, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        if (LinkService.instance == null) LinkService.start(this)

        val uris = collectUris()
        if (uris.isEmpty()) {
            finish()
            return
        }
        if (LinkService.state.get() != LinkState.CONNECTED) {
            Toast.makeText(this, R.string.share_no_link, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        uris.forEach { LinkService.instance?.sendFile(it) }
        Toast.makeText(this, R.string.share_sending, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun collectUris(): List<Uri> {
        val intent = intent ?: return emptyList()
        val single: Uri? = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        if (single != null) return listOf(single)
        val many: ArrayList<Uri>? = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
        }
        return many ?: emptyList()
    }
}
