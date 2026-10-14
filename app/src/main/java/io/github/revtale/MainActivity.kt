package io.github.revtale

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var ui: LinkUiController
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (::ui.isInitialized) ui.onPermissionsResult()
    }
    private val saveBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) ui.writeBackup(uri)
    }
    private val openBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) ui.readBackup(uri)
    }
    private val photo = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok -> ui.onPhotoTaken(ok) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ui = LinkUiController(this, lifecycleScope) { permissions.launch(it) }
        ui.actions = object : ActivityActions {
            override fun exportBackup(suggestedName: String) = saveBackup.launch(suggestedName)
            override fun importBackup() = openBackup.launch(arrayOf("application/zip", "application/octet-stream"))
            override fun takePhoto(target: android.net.Uri) {
                runCatching { photo.launch(target) }.onFailure { ui.notice = "No camera app found." }
            }
            override fun share(intent: android.content.Intent) { runCatching { startActivity(intent) } }
        }
        setContent { LinkApp(ui) }
        openIntent(intent)
        devIntent(intent)
        sharedPlace(intent)
        androidx.core.content.ContextCompat.registerReceiver(this, updateStatus, Updater.statusFilter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        ui.checkForUpdate()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ui.resume()
                ui.reloadParking(); ui.reloadAway()
                ui.checkForUpdate()
                try {
                    while (isActive) {
                        ui.refresh()
                        delay(500)
                    }
                } finally {
                    ui.pause()
                }
            }
        }
    }

    private val updateStatus = Updater.StatusReceiver { if (::ui.isInitialized) ui.notice = it }

    override fun onDestroy() {
        runCatching { unregisterReceiver(updateStatus) }
        super.onDestroy()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        openIntent(intent)
        devIntent(intent)
        sharedPlace(intent)
    }

    /** Debug builds only: `am start -n io.github.revtale/.MainActivity --ez emulator true [--ez auto true]` starts
     *  the bike emulator, so the ride notification / Now Bar can be checked from a computer. */
    /** Google Maps → Share → RevTale: save that place. */
    private fun sharedPlace(intent: android.content.Intent?) {
        if (intent?.action != android.content.Intent.ACTION_SEND) return
        intent.getStringExtra(android.content.Intent.EXTRA_TEXT)?.let { ui.onSharedText(it) }
        intent.action = null // handled; not again after a rotation
    }

    /** A notification, widget or tile tap: go to its page ([Open]). */
    private fun openIntent(intent: android.content.Intent?) {
        intent?.getStringExtra(Open.EXTRA)?.let { ui.openRequest = it; intent.removeExtra(Open.EXTRA) }
    }

    private fun devIntent(intent: android.content.Intent?) {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0 || intent == null) return
        // `--ez awayDemo true`: a sample "ridden while you were away" card and notification
        if (intent.getBooleanExtra("awayDemo", false)) {
            val now = System.currentTimeMillis()
            val t = AwayTrip(now - 4 * 3600_000L - 35 * 60_000L, now - 10 * 60_000L, 23.4, 4, 2, 92, 104, "MG Road, Bengaluru", "MG Road, Bengaluru")
            Away.add(this, t); Away.notify(this, t); ui.reloadAway()
            Away.remember(this, BikeSnapshot(now - 2 * 3600_000L, 12_369.4, 4, 104))
            BleService.prefs(this).edit().putFloat("lastVolts", 12.6f).apply()
        }
        if (intent.hasExtra("emulator")) {
            if (intent.getBooleanExtra("emulator", false)) {
                val auto = intent.getBooleanExtra("auto", false)
                ui.startSimulator()
                lifecycleScope.launch { delay(1500); ui.simulate { it.auto = auto } }
            } else ui.stopSimulator()
        }
    }
}
