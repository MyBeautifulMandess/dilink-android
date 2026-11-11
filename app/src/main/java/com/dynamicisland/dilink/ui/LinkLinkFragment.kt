package com.dynamicisland.dilink.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.dynamicisland.dilink.R
import com.dynamicisland.dilink.databinding.FragmentLinkBinding
import com.dynamicisland.dilink.link.LinkService
import com.dynamicisland.dilink.link.LinkState
import com.dynamicisland.dilink.link.Pairing
import com.dynamicisland.dilink.store.Settings
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject

/**
 * Экран связи: сопряжение по QR, состояние канала, последние передачи и
 * ручная отправка буфера — на случай, если буфер читается не в фоне.
 */
class LinkLinkFragment : Fragment() {

    private var _binding: FragmentLinkBinding? = null
    private val binding get() = _binding!!
    private lateinit var settings: Settings
    private lateinit var adapter: TransferAdapter

    private val scanner = registerForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@registerForActivityResult
        pair(text)
    }

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

        adapter = TransferAdapter()
        binding.transfers.layoutManager = LinearLayoutManager(requireContext())
        binding.transfers.adapter = adapter

        binding.scan.setOnClickListener { scan() }
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
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt(getString(R.string.action_scan))
            setBeepEnabled(false)
            setOrientationLocked(false)
            setBarcodeImageEnabled(false)
        }
        scanner.launch(options)
    }

    /** Ключ из QR становится постоянным: сервер сохраняет его как ключ устройства. */
    private fun pair(raw: String) {
        val pairing = Pairing.parse(raw)
        if (pairing == null) {
            showError("Это не код DI Link")
            return
        }
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
