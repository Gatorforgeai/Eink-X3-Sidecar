package com.gatorforge.sidecar.trackb

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/*
 * DESIGN INTENT — BleSoakService
 * DONE WHEN: a connectedDevice foreground service holds a live BLE GATT link to the stand-in peer,
 *   survives app backgrounding + screen-off, and — after any drop — silently re-establishes GATT
 *   with capped backoff, logging every CONNECTION-state transition. Observable proof: conn_log.csv
 *   shows CONNECTED, continuous HEARTBEAT rows while backgrounded, and DISCONNECTED -> RECONNECTED
 *   with no user action after the peer is toggled. If the service died, HEARTBEATs would stop and
 *   the notification would vanish.
 * INPUTS:
 *   - START (any startCommand): starts the FGS and begins scan->connect. Idempotent — a second
 *     START while running is ignored.
 *   - ACTION_STOP intent: tears down GATT, stops the FGS, exits.
 *   - GATT callback states: CONNECTED -> log + start heartbeat; DISCONNECTED -> log + schedule
 *     reconnect; non-zero status -> GATT_ERROR then treated as a disconnect.
 *   - ScanResult matching Config peer -> stop scan, connect. No match within SCAN_TIMEOUT_S ->
 *     log a scan miss and retry the scan (backoff).
 *   - Missing BLE permission at call site: methods are @SuppressLint; the Activity MUST have
 *     granted BLUETOOTH_SCAN/CONNECT before START. If not granted, the OS throws SecurityException
 *     which is caught, logged GATT_ERROR, and retried — never a silent swallow.
 * FAILS WHEN: adapter off/absent -> log GATT_ERROR, retry after backoff (retryable). startForeground
 *   rejected (policy) -> propagates (that is the very failure Track B exists to detect). Reconnect
 *   loop is capped only in DELAY, not attempts — it retries forever until STOP (a soak wants that).
 * USER SEES: one persistent notification while running (never per-event); everything else is the
 *   CSV. No writes to vault/To Do/inbox/calendar.
 */
class BleSoakService : Service() {

    companion object {
        const val ACTION_STOP = "com.gatorforge.sidecar.trackb.STOP"

        data class SoakState(
            val status: String = "idle",
            val connected: Boolean = false,
            val drops: Int = 0,
            val reconnects: Int = 0,
            val connectedSinceMs: Long = 0L,
            val lastRssi: Int? = null
        )

        private val _state = MutableStateFlow(SoakState())
        val state: StateFlow<SoakState> = _state
    }

    private val scope = CoroutineScope(SupervisorJob())
    private lateinit var logger: ConnLogger
    private var adapter: BluetoothAdapter? = null
    private var gatt: BluetoothGatt? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private val started = AtomicBoolean(false)
    private var backoffS = Config.RECONNECT_BACKOFF_MIN_S

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            teardownAndStop()
            return START_NOT_STICKY
        }
        if (started.compareAndSet(false, true)) {
            logger = ConnLogger(this)
            adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            startForeground(Config.NOTIF_ID, buildNotification("starting…"))
            scope.launch { connectLoopEntry() }
        }
        return START_STICKY
    }

    /** Entry point: kick off the first scan->connect; the GATT callback drives everything after. */
    private suspend fun connectLoopEntry() {
        updateStatus("scanning")
        logger.log(ConnLogger.Event.SCAN, "looking for peer")
        startScanOrConnectByMac()
    }

    @SuppressLint("MissingPermission")
    private fun startScanOrConnectByMac() {
        val a = adapter
        if (a == null || !a.isEnabled) {
            logger.log(ConnLogger.Event.GATT_ERROR, "adapter off/absent")
            scheduleReconnect()
            return
        }
        if (Config.PEER_MAC.isNotBlank()) {
            runCatching { connect(a.getRemoteDevice(Config.PEER_MAC)) }
                .onFailure { onGattProblem("bad MAC ${Config.PEER_MAC}: ${it.message}") }
            return
        }
        val scanner = a.bluetoothLeScanner ?: run {
            onGattProblem("no scanner"); return
        }
        val filters = listOf(ScanFilter.Builder().build()) // name filter applied in callback (substring)
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = runCatching { result.device.name }.getOrNull() ?: return
                if (name.contains(Config.PEER_NAME_SUBSTRING, ignoreCase = true)) {
                    runCatching { scanner.stopScan(this) }
                    connect(result.device)
                }
            }
            override fun onScanFailed(errorCode: Int) {
                onGattProblem("scan failed $errorCode")
            }
        }
        runCatching { scanner.startScan(filters, settings, cb) }
            .onFailure { onGattProblem("startScan: ${it.message}") }
        // Scan timeout -> retry
        scope.launch {
            delay(Config.SCAN_TIMEOUT_S * 1000)
            if (gatt == null) {
                runCatching { scanner.stopScan(cb) }
                if (started.get() && gatt == null) {
                    logger.log(ConnLogger.Event.SCAN, "no match in ${Config.SCAN_TIMEOUT_S}s, retrying")
                    scheduleReconnect()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: android.bluetooth.BluetoothDevice) {
        runCatching {
            // TRANSPORT_LE lives on BluetoothDevice, not BluetoothGatt (D-035).
            gatt = device.connectGatt(this, false, gattCallback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
        }.onFailure { onGattProblem("connectGatt: ${it.message}") }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onGattProblem("status=$status")
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> onConnected()
                BluetoothProfile.STATE_DISCONNECTED -> onDisconnected()
            }
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val since = _state.value.connectedSinceMs
                _state.value = _state.value.copy(lastRssi = rssi)
                logger.log(ConnLogger.Event.HEARTBEAT, "alive", rssi, since)
            }
        }
    }

    private fun onConnected() {
        val first = _state.value.connectedSinceMs == 0L && _state.value.reconnects == 0 && _state.value.drops == 0
        val since = System.currentTimeMillis()
        _state.value = _state.value.copy(
            status = "connected", connected = true, connectedSinceMs = since,
            reconnects = if (first) 0 else _state.value.reconnects + 1
        )
        backoffS = Config.RECONNECT_BACKOFF_MIN_S
        if (first) logger.log(ConnLogger.Event.CONNECTED, "peer connected", connectedSinceMs = since)
        else logger.log(ConnLogger.Event.RECONNECTED, "reconnected", connectedSinceMs = since)
        updateNotification("connected")
        startHeartbeat()
    }

    @SuppressLint("MissingPermission")
    private fun onDisconnected() {
        if (!_state.value.connected && _state.value.connectedSinceMs == 0L) return
        heartbeatJob?.cancel()
        _state.value = _state.value.copy(
            status = "disconnected", connected = false, connectedSinceMs = 0L,
            drops = _state.value.drops + 1
        )
        logger.log(ConnLogger.Event.DISCONNECTED, "link dropped")
        updateNotification("reconnecting…")
        runCatching { gatt?.close() }
        gatt = null
        scheduleReconnect()
    }

    @SuppressLint("MissingPermission")
    private fun onGattProblem(msg: String) {
        logger.log(ConnLogger.Event.GATT_ERROR, msg)
        runCatching { gatt?.close() }
        gatt = null
        _state.value = _state.value.copy(status = "error: $msg", connected = false)
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val wait = backoffS
            logger.log(ConnLogger.Event.RECONNECT_ATTEMPT, "in ${wait}s")
            updateStatus("reconnect in ${wait}s")
            delay(wait * 1000)
            backoffS = (backoffS * 2).coerceAtMost(Config.RECONNECT_BACKOFF_MAX_S)
            if (started.get() && gatt == null) startScanOrConnectByMac()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && _state.value.connected) {
                runCatching { gatt?.readRemoteRssi() }
                delay(Config.HEARTBEAT_INTERVAL_S * 1000)
            }
        }
    }

    private fun updateStatus(s: String) { _state.value = _state.value.copy(status = s) }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(Config.NOTIF_CHANNEL_ID, "BLE Soak", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, Config.NOTIF_CHANNEL_ID)
            .setContentTitle("Track B soak")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(Config.NOTIF_ID, buildNotification(text))
    }

    @SuppressLint("MissingPermission")
    private fun teardownAndStop() {
        started.set(false)
        heartbeatJob?.cancel(); reconnectJob?.cancel()
        runCatching { gatt?.close() }; gatt = null
        _state.value = SoakState(status = "stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
