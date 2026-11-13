package com.dynamicisland.dilink.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.dynamicisland.dilink.R
import com.dynamicisland.dilink.databinding.FragmentLinkBinding
import com.dynamicisland.dilink.link.FileTransfer
import com.dynamicisland.dilink.link.LinkService
import com.dynamicisland.dilink.link.LinkState
import com.dynamicisland.dilink.link.Pairing
import com.dynamicisland.dilink.store.Settings
import org.json.JSONObject
import java.io.File

/**
 * Экран связи: сопряжение, состояние канала, последние передачи и ручная
 * отправка буфера — на случай, если буфер читается не в фоне.
 */
class LinkLinkFragment : Fragment() {

    private var _binding: FragmentLinkBinding? = null
    private val binding get() = _binding!!
    private lateinit var settings: Settings
    private lateinit var adapter: TransferAdapter

    private val picker = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        uris.forEach { uri ->
            val service = LinkService.instance
            if (service == null) {
                showError("Служба связи не запущена")
                return@forEach
            }
            service.sendFile(uri)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentLinkBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        settings = Settings(requireContext())

        adapter = TransferAdapter(
            onOpen = { item -> openFile(item) },
            onLocate = { item -> locateFile(item) }
        )
        binding.transfers.layoutManager = LinearLayoutManager(requireContext())
        binding.transfers.adapter = adapter

        binding.scan.setOnClickListener { scan() }
        binding.paste.setOnClickListener { pasteFromClipboard() }
        binding.manualConnect.setOnClickListener { pairManual() }
        binding.connect.setOnClickListener {
            if (settings.isPaired) LinkService.instance?.reconnect() else scan()
        }
        binding.forget.setOnClickListener {
            settings.forget()
            LinkService.instance?.disconnectNow()
            render()
        }
        binding.pickFile.setOnClickListener { picker.launch("*/*") }
        binding.sendClipboard.setOnClickListener {
            val service = LinkService.instance ?: return@setOnClickListener
            val text = service.currentClipboard()
            if (text.isBlank()) {
                showError("Буфер пуст")
                return@setOnClickListener
            }
            service.send("clipboard.push", JSONObject().apply {
                put("text", text.take(20_000))
                put("origin", "phone")
            })
            binding.error.visibility = View.GONE
        }

        LinkService.state.observe { render() }
        LinkService.lastError.observe { message ->
            if (message.isNotEmpty()) showError(message)
        }
        LinkService.transfers.observe { list ->
            adapter.submit(list)
            binding.transferEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val paired = settings.isPaired
        val state = LinkService.state.get()
        binding.pairTitle.text = if (paired) {
            getString(R.string.status_paired, settings.address, settings.port)
        } else {
            getString(R.string.status_paired_none)
        }
        binding.connect.isEnabled = paired
        binding.forget.isEnabled = paired
        binding.connect.text = if (state == LinkState.CONNECTED) {
            getString(R.string.action_disconnect)
        } else {
            getString(R.string.action_connect)
        }
        if (state == LinkState.CONNECTED) {
            binding.connect.setOnClickListener { LinkService.instance?.disconnectNow() }
        } else {
            binding.connect.setOnClickListener {
                if (paired) LinkService.instance?.reconnect() else scan()
            }
        }
    }

    private fun scan() {
        // Камеру телефона зовём системную: свой сканер внутри приложения давал
        // две проблемы — лишние килбайты в APK и вечное разрешение на камеру.
        // Обычная камера сама узнаёт QR-код и открывает страницу сопряжения.
        val camera = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        val capture = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        for (intent in listOf(camera, capture)) {
            try {
                startActivity(intent)
                return
            } catch (e: ActivityNotFoundException) {
                // На этом телефоне нет такого приложения — пробуем следующий вариант.
            }
        }
        showError(getString(R.string.camera_unavailable))
    }

    /**
     * Вставка из буфера: камера телефона часто только распознаёт ссылку и
     * предлагает её скопировать — этот путь закрывает сопряжение без камеры.
     */
    private fun pasteFromClipboard() {
        val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as? android.content.ClipboardManager
        val text = clipboard?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(requireContext())
            ?.toString()
            .orEmpty()
        if (text.isBlank()) {
            showError("В буфере обмена пусто")
            return
        }

        val pairing = Pairing.parse(text)
        if (pairing != null) {
            binding.manualLink.setText(text.trim())
            binding.manualToken.setText(pairing.token)
            applyPairing(pairing)
            return
        }

        // В буфере может лежать голый ключ — тогда адрес берём из поля ввода.
        val key = text.trim()
        if (key.length >= 32 && key.all { it.isLetterOrDigit() }) {
            binding.manualToken.setText(key)
            showError("Ключ вставлен. Укажите адрес компьютера и нажмите «Подключиться по ссылке»")
            return
        }
        showError("Это не ссылка DI Link")
    }

    /** Ручной ввод: адрес и ключ из полей. */
    private fun pairManual() {
        val link = binding.manualLink.text.toString().trim()
        val token = binding.manualToken.text.toString().trim()
        if (link.isEmpty()) {
            showError("Укажите адрес компьютера")
            return
        }
        val pairing = Pairing.parse(link, token)
        if (pairing == null) {
            showError("Не получилось разобрать адрес и ключ")
            return
        }
        applyPairing(pairing)
    }

    private fun applyPairing(pairing: Pairing) {
        settings.address = pairing.address
        settings.port = pairing.port
        settings.deviceToken = pairing.token
        settings.deviceId = ""
        binding.error.visibility = View.GONE
        LinkService.instance?.reconnect() ?: LinkService.start(requireContext())
        render()
    }

    private fun showError(message: String) {
        binding.error.text = message
        binding.error.visibility = View.VISIBLE
    }

    /**
     * Открывает полученный файл: картинка — из галереи, остальное — через
     * FileProvider, чтобы систему не ругалась на «небезопасный» файл.
     */
    private fun openFile(item: FileTransfer.Progress) {
        val path = item.savedPath ?: return
        val context = requireContext()
        val uri = item.savedUri?.let { Uri.parse(it) } ?: try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
        } catch (e: Exception) {
            null
        }
        if (uri == null) {
            showError(getString(R.string.transfer_no_app))
            return
        }
        val mime = context.contentResolver.getType(uri) ?: mimeOf(item.name)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            showError(getString(R.string.transfer_no_app))
        }
    }

    /** Показывает, где файл лежит, и кладёт путь в буфер — его можно вставить в «Проводник». */
    private fun locateFile(item: FileTransfer.Progress) {
        val path = item.savedPath ?: return
        val location = item.savedLocation.orEmpty()
        val text = if (location.isEmpty()) path else "$location\n$path"
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.transfer_locate_title)
            .setMessage(text)
            .setPositiveButton(R.string.transfer_locate_copy) { _, _ ->
                val clipboard = requireContext()
                    .getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as? android.content.ClipboardManager
                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText(path, path))
                showError(getString(R.string.transfer_locate_copied))
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "pdf" -> "application/pdf"
        "txt", "log" -> "text/plain"
        "zip" -> "application/zip"
        "mp3" -> "audio/mpeg"
        "mp4" -> "video/mp4"
        else -> "*/*"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
