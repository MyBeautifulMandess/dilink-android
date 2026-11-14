package com.dynamicisland.dilink.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings as SystemSettings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.dynamicisland.dilink.BuildConfig
import com.dynamicisland.dilink.LinkApp
import com.dynamicisland.dilink.R
import com.dynamicisland.dilink.databinding.FragmentSettingsBinding
import com.dynamicisland.dilink.link.LinkService
import com.dynamicisland.dilink.store.Settings

/** Переключатели синхронизации и доступы системы. */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var settings: Settings
    private var bindingUp = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        settings = Settings(requireContext())
        binding.version.text = getString(R.string.version_line, BuildConfig.VERSION_NAME)

        bind(binding.switchClipboard, { settings.clipboardSync }, { settings.clipboardSync = it })
        bind(binding.switchNotifications, { settings.notifications }, { settings.notifications = it })
        bind(binding.switchMedia, { settings.mediaSync }, { settings.mediaSync = it })
        bind(binding.switchFiles, { settings.fileTransfer }, { settings.fileTransfer = it })
        bind(binding.switchPlanner, { settings.plannerSync }, { settings.plannerSync = it })
        bind(binding.switchWifi, { settings.wifiOnly }, { settings.wifiOnly = it })
        bind(binding.switchAuto, { settings.autoConnect }, { settings.autoConnect = it })

        bind(binding.switchBluetooth, { settings.preferBluetooth }, { settings.preferBluetooth = it })
        // Смена предпочтения — это смена транспорта: канал пересобирается сразу,
        // иначе переключатель выглядел бы рабочим, а связь оставалась прежней.
        binding.switchBluetooth.setOnCheckedChangeListener { _, value ->
            if (bindingUp) return@setOnCheckedChangeListener
            settings.preferBluetooth = value
            renderTransport()
            LinkService.instance?.reconnect()
        }

        bind(binding.switchIdle, { settings.idleDisconnect }, { settings.idleDisconnect = it })
        binding.switchIdle.setOnCheckedChangeListener { _, value ->
            if (bindingUp) return@setOnCheckedChangeListener
            settings.idleDisconnect = value
            renderIdle()
        }
        renderTransport()
        renderIdle()

        binding.idleMinutes.setText(settings.idleMinutes.toString())
        binding.idleMinutes.setOnFocusChangeListener { _, focused ->
            if (!focused) saveIdleMinutes()
        }
        binding.idleMinutes.setOnEditorActionListener { _, _, _ ->
            saveIdleMinutes()
            false
        }

        binding.grantNotifications.setOnClickListener {
            val intent = Intent(SystemSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { startActivity(intent) }
        }
        binding.addIgnore.setOnClickListener { addIgnored() }
        renderAccess()
        renderIgnored()
    }

    override fun onResume() {
        super.onResume()
        renderAccess()
    }

    private fun bind(
        switch: com.google.android.material.materialswitch.MaterialSwitch,
        read: () -> Boolean,
        write: (Boolean) -> Unit
    ) {
        switch.isChecked = read()
        switch.setOnCheckedChangeListener { _, value ->
            if (bindingUp) return@setOnCheckedChangeListener
            write(value)
            LinkService.instance?.refreshAccess()
        }
    }

    /** Запоминает минуты простоя: пустое или нулевое поле возвращает прежнее значение. */
    private fun saveIdleMinutes() {
        val value = binding.idleMinutes.text.toString().toIntOrNull()
        if (value == null || value <= 0) {
            binding.idleMinutes.setText(settings.idleMinutes.toString())
            return
        }
        settings.idleMinutes = value
        binding.idleMinutes.setText(settings.idleMinutes.toString())
    }

    /**
     * Bluetooth включается только тогда, когда телефон знает адрес адаптера
     * компьютера: без него запасного пути нет, и переключатель был бы ложью.
     * Адрес приходит в QR-коде или при знакомстве по Bluetooth.
     */
    private fun renderTransport() {
        val available = settings.bluetoothAddress.isNotEmpty()
        val switch = binding.switchBluetooth
        if (!available && switch.isChecked) {
            bindingUp = true
            switch.isChecked = false
            bindingUp = false
            settings.preferBluetooth = false
        }
        switch.isEnabled = available
        binding.bluetoothHint.text = getString(
            if (available) R.string.prefer_bluetooth_hint else R.string.bluetooth_address_missing
        )
    }

    /** Поле минут нужно только при включённом автозакрытии: иначе это шум в настройках. */
    private fun renderIdle() {
        val on = settings.idleDisconnect
        binding.idleRow.visibility = if (on) View.VISIBLE else View.GONE
        binding.idleHint.visibility = if (on) View.VISIBLE else View.GONE
    }

    private fun addIgnored() {
        val value = binding.ignoreInput.text.toString().trim()
        if (value.isEmpty()) return
        val list = settings.ignoredPackages + value
        settings.ignoredPackages = list
        binding.ignoreInput.setText("")
        renderIgnored()
    }

    private fun renderIgnored() {
        val list = settings.ignoredPackages
        binding.ignoreList.text = if (list.isEmpty()) {
            "Исключений нет"
        } else {
            list.joinToString("\n") { "• $it" }
        }
        binding.ignoreList.setOnClickListener(null)
    }

    private fun renderAccess() {
        val granted = LinkApp.hasNotificationAccess(requireContext())
        binding.accessNotifications.text =
            getString(if (granted) R.string.access_granted else R.string.access_missing)
        binding.accessNotifications.setTextColor(
            resources.getColor(if (granted) R.color.success else R.color.danger, null)
        )
        binding.grantNotifications.visibility = if (granted) View.GONE else View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
