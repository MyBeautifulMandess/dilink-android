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
