package com.dynamicisland.dilink.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings as SystemSettings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.dynamicisland.dilink.LinkApp
import com.dynamicisland.dilink.R
import com.dynamicisland.dilink.databinding.ActivityMainBinding
import com.dynamicisland.dilink.link.LinkService
import com.dynamicisland.dilink.link.LinkState
import com.dynamicisland.dilink.link.Pairing
import com.dynamicisland.dilink.store.Settings
import com.google.android.material.tabs.TabLayoutMediator

/**
 * Главное окно: три вкладки в одном стиле с островком. Нижней навигации нет
 * намеренно — вкладок мало, а так экран не спорит с системными жестами.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val tabs: List<Pair<Int, Fragment>>
        get() = listOf(
            R.string.tab_connection to LinkLinkFragment(),
            R.string.tab_calendar to CalendarFragment(),
            R.string.tab_reminders to ReminderFragment(),
            R.string.tab_settings to SettingsFragment()
        )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val pages = tabs
        binding.pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = pages.size
            override fun createFragment(position: Int): Fragment = pages[position].second
        }
        binding.pager.offscreenPageLimit = 2
        TabLayoutMediator(binding.tabs, binding.pager) { tab, position ->
            tab.setText(pages[position].first)
        }.attach()
        binding.pager.setCurrentItem(intent.getIntExtra(EXTRA_OPEN_TAB, 0), false)

        LinkService.state.observe { state -> renderState(state) }
        LinkService.statusText.observe { text ->
            binding.status.text = text
            renderState(LinkService.state.get())
        }

        handlePairingLink(intent)

        requestNotificationPermission()
        requestBluetoothPermission()
        if (LinkApp.hasNotificationAccess(this).not()) {
            // Первый запуск: без доступа не будет ни уведомлений, ни медиаплеера.
            // Просим один раз молча, экран настроек всё равно объяснит подробнее.
            runCatching {
                startActivity(
                    Intent(SystemSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
        if (LinkService.instance == null) LinkService.start(this)
    }

    private fun renderState(state: LinkState) {
        val color = when (state) {
            LinkState.CONNECTED -> R.color.success
            LinkState.CONNECTING -> R.color.accent
            LinkState.REJECTED -> R.color.danger
            LinkState.DISCONNECTED -> R.color.text_secondary
        }
        val tint = ContextCompat.getColor(this, color)
        runCatching {
            binding.statusDot.background?.setTint(tint)
            binding.statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(tint)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
    }

    /**
     * Bluetooth-канал требует BLUETOOTH_CONNECT на Android 12+. Просим сразу:
     * без разрешения запасной транспорт просто не поднимется, а пользователь
     * решит, что связь без Wi-Fi не работает.
     */
    private fun requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_BLUETOOTH)
    }

    override fun onResume() {
        super.onResume()
        // Возврат из системных настроек уведомлений: мост медиа и уведомления
        // могли только что получить доступ.
        LinkService.instance?.refreshAccess()
    }

    /**
     * Ссылка `dilink://v1?...` из QR: камера открыла приложение, и оно сразу
     * сопрягается — без ручного сканирования внутри приложения.
     */
    private fun handlePairingLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val raw = intent.dataString ?: return
        val pairing = Pairing.parse(raw) ?: return

        val settings = Settings(this)
        settings.address = pairing.address
        settings.port = pairing.port
        settings.deviceToken = pairing.token
        settings.deviceId = ""

        LinkService.start(this)
        LinkService.instance?.reconnect()
        LinkService.statusText.set("Сопряжение: ${pairing.display}")
        binding.pager.setCurrentItem(0, false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePairingLink(intent)
    }

    companion object {
        /** С какой вкладки открыться: из уведомления напоминания сразу в список. */
        const val EXTRA_OPEN_TAB = "open_tab"
        private const val REQUEST_NOTIFICATIONS = 11
        private const val REQUEST_BLUETOOTH = 12
    }
}
