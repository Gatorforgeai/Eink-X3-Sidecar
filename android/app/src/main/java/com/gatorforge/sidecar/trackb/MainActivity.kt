package com.gatorforge.sidecar.trackb

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import com.gatorforge.sidecar.trackb.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.io.File

/*
 * DESIGN INTENT — MainActivity
 * DONE WHEN: the user can grant BLE + notification permissions, start/stop the soak service, watch
 *   live status (connected/uptime/drops/reconnects/RSSI) that mirrors BleSoakService.state, open the
 *   two exemption screens, and share the CSV. Observable proof: tapping Start makes the FGS
 *   notification appear and the status text move off "idle"; Share opens a chooser with conn_log.csv.
 * INPUTS:
 *   - Runtime permission grants/denials: on grant -> Start enabled; on deny -> a status line tells
 *     the user which permission is missing (not silently ignored).
 *   - Start button: starts BleSoakService (foreground). Stop: sends ACTION_STOP.
 *   - Exemption buttons: delegate to ExemptionHelper; the exemption state is (re)read on resume and
 *     shown.
 *   - Share button with no CSV yet -> status line "no log yet" rather than crash.
 * FAILS WHEN: BLE unsupported -> Start disabled with a reason. FileProvider misconfig -> share
 *   wrapped in runCatching, surfaced as a status line (retryable). No permanent failure path here.
 * USER SEES: only this on-screen UI + the system share sheet. No writes to vault/To Do/inbox/calendar.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val denied = result.filterValues { !it }.keys
        b.status.text = if (denied.isEmpty()) "permissions granted — ready" else "missing: ${denied.joinToString()}"
        b.startBtn.isEnabled = denied.isEmpty()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.grantPermsBtn.setOnClickListener { requestPerms() }
        b.startBtn.setOnClickListener {
            ContextCompat.startForegroundService(this, Intent(this, BleSoakService::class.java))
        }
        b.stopBtn.setOnClickListener {
            startService(Intent(this, BleSoakService::class.java).setAction(BleSoakService.ACTION_STOP))
        }
        b.batteryBtn.setOnClickListener { ExemptionHelper.requestIgnoreBatteryOptimizations(this) }
        b.samsungBtn.setOnClickListener { ExemptionHelper.openSamsungBatterySettings(this) }
        b.shareBtn.setOnClickListener { shareLog() }

        b.startBtn.isEnabled = hasAllPerms()
        collectState()
    }

    override fun onResume() {
        super.onResume()
        val exempt = ExemptionHelper.isIgnoringBatteryOptimizations(this)
        b.exemptState.text = "battery-optimisation exempt: $exempt"
    }

    private fun collectState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                BleSoakService.state.collect { s ->
                    val up = if (s.connectedSinceMs > 0) (System.currentTimeMillis() - s.connectedSinceMs) / 1000 else 0
                    b.status.text = "status: ${s.status}"
                    b.stats.text = "connected=${s.connected}  uptime=${up}s  drops=${s.drops}  " +
                        "reconnects=${s.reconnects}  rssi=${s.lastRssi ?: "-"}"
                }
            }
        }
    }

    private fun requiredPerms(): Array<String> {
        val p = mutableListOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) p.add(Manifest.permission.POST_NOTIFICATIONS)
        return p.toTypedArray()
    }

    private fun hasAllPerms(): Boolean = requiredPerms().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPerms() = permLauncher.launch(requiredPerms())

    private fun shareLog() {
        val csv = getExternalFilesDir(null)?.let { File(it, Config.LOG_FILE_NAME) }
        if (csv == null || !csv.exists()) {
            b.status.text = "no log yet — start a soak first"
            return
        }
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", csv)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "Share conn_log.csv"))
        }.onFailure { b.status.text = "share failed: ${it.message}" }
    }
}
