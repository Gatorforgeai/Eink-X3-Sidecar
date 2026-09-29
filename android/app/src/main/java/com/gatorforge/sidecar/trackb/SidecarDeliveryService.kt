package com.gatorforge.sidecar.trackb

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.ArrayDeque
import java.util.UUID
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.CRC32

/**
 * DESIGN INTENT: Deliver the latest requested content reliably in the background. Protocol-v4
 * devices replace bounded atomic groups across successive connections; protocol-v3 devices receive
 * one atomic bundle and v1/v2 devices retain compatible behavior. Missing acknowledgement retries.
 */
class SidecarDeliveryService : Service() {
    companion object {
        private const val TAG = "SidecarDelivery"
        private const val ACTION_QUEUE = "com.gatorforge.sidecar.trackb.QUEUE_SIDECAR"
        private const val ACTION_CANCEL = "com.gatorforge.sidecar.trackb.CANCEL_SIDECAR"
        private const val EXTRA_MODULE = "module"
        private const val EXTRA_NOTE = "note"
        private const val EXTRA_PAGE_INDEX = "page_index"
        private const val EXTRA_AUTOMATIC = "automatic_bundle"
        private const val EXTRA_V5_CANARY = "v5_collection_canary"
        private const val KEY_BACKGROUND_SUPPORTED = "background_bundle_supported"
        private const val KEY_BUNDLE_PAGES = "last_bundle_pages"
        private const val KEY_UPLOAD_MS = "last_upload_ms"
        private const val PREFS = "sidecar_pending"
        private const val KEY_PENDING = "pending"
        private const val KEY_MODULE = "module"
        private const val KEY_NOTE = "note"
        private const val KEY_RENDER_CONTENT = "render_content"
        private const val KEY_GENERATION = "generation"
        private const val KEY_PAGE_INDEX = "page_index"
        private const val KEY_LAST_BUNDLE_SIGNATURE = "last_bundle_signature"
        private const val KEY_GROUP_START = "group_start"
        private const val KEY_NEXT_ROTATION_REFRESH_AT = "next_rotation_refresh_at"
        private const val KEY_V5_CANARY = "v5_collection_canary"
        private const val KEY_V5_CANARY_SESSION = "v5_collection_canary_session"
        private const val KEY_V5_CANARY_COLLECTION = "v5_collection_canary_collection"
        private const val KEY_V5_CANARY_CRCS = "v5_collection_canary_crcs"
        private const val KEY_V5_CANARY_CANDIDATE = "v5_collection_canary_candidate"
        private const val KEY_V5_PLAYLIST_CHAIN = "v5_playlist_chain"
        private const val KEY_V5_PAGE_ENCODING = "v5_page_encoding"
        private const val KEY_V5_INTERRUPT_AFTER_DATA = "v5_interrupt_after_data_packets"
        private const val KEY_V5_SIGNATURE_PREFIX = "v5_signature:"
        private const val PAGE_RECONNECT_DELAY_MS = 30_000L
        private const val ROTATION_OFFLINE_RETRY_MS = 2_000L
        private const val CHANNEL_ID = "sidecar_delivery"
        private const val NOTIFICATION_ID = 2077
        private const val CONNECTION_TIMEOUT_MS = 30_000L
        private const val BUNDLE_COMPLETION_TIMEOUT_MS = 120_000L
        private const val SCAN_TIMEOUT_MS = 150_000L
        private const val MAX_RETRIES = 5
        private const val CONNECTION_SETTLE_MS = 5_000L
        private const val PAGE_BEGIN_GUARD_MS = 2_000L
        private const val PAGE_END_GUARD_MS = 375L
        private const val V5_STORAGE_GUARD_MS = 8_000L

        fun queue(context: Context, module: String, note: String, pageIndex: Int = 0, automatic: Boolean = false) {
            val intent = Intent(context, SidecarDeliveryService::class.java)
                .setAction(ACTION_QUEUE)
                .putExtra(EXTRA_MODULE, module)
                .putExtra(EXTRA_NOTE, note)
                .putExtra(EXTRA_PAGE_INDEX, pageIndex.coerceAtLeast(0))
                .putExtra(EXTRA_AUTOMATIC, automatic)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Explicit bench-only path. It never participates in automatic playlist refresh. */
        fun queueCollectionCanary(context: Context, module: String, note: String) {
            val intent = Intent(context, SidecarDeliveryService::class.java)
                .setAction(ACTION_QUEUE)
                .putExtra(EXTRA_MODULE, module)
                .putExtra(EXTRA_NOTE, note)
                .putExtra(EXTRA_V5_CANARY, true)
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context) {
            val intent = Intent(context, SidecarDeliveryService::class.java).setAction(ACTION_CANCEL)
            ContextCompat.startForegroundService(context, intent)
        }

        fun resumeRotation(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, SidecarDeliveryService::class.java))
        }
    }

    private val serviceUuid = UUID.fromString("6e6b1a00-9f1d-4b7a-9c2e-1f0a2d3c4b5a")
    private val rxUuid = UUID.fromString("6e6b1a01-9f1d-4b7a-9c2e-1f0a2d3c4b5a")
    private val infoUuid = UUID.fromString("6e6b1a02-9f1d-4b7a-9c2e-1f0a2d3c4b5a")
    private val statusUuid = UUID.fromString("6e6b1a03-9f1d-4b7a-9c2e-1f0a2d3c4b5a")
    private val inventoryUuid = UUID.fromString("6e6b1a04-9f1d-4b7a-9c2e-1f0a2d3c4b5a")
    private val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val adapter by lazy { (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter }

    private var scanCallback: ScanCallback? = null
    private var gatt: BluetoothGatt? = null
    private var queue: ArrayDeque<ByteArray>? = null
    private var mtu = 23
    private var activeGeneration = 0L
    private var activeModule = ""
    private var activeNote = ""
    private var activePageIndex = 0
    private var activePageCount = 1
    private var deliveredAsBundle = false
    private var backgroundBundle = false
    private var uploadFinishedMs = 0L
    private var transferStartMs = 0L
    private var timeout: Runnable? = null
    private var scanTimeout: Runnable? = null
    private var completionPoll: Runnable? = null
    private var reconnect: Runnable? = null
    private var retryCount = 0
    private var awaitingCompletion = false
    private var statusBaseline: Long? = null
    private var completionObserved = false
    private var activeBundleSignature = ""
    private var groupedTransfer = false
    private var activeGroupStart = 0
    private var activeGroupCount = 0
    private var pendingInfo: SidecarFrame.DeviceInfo? = null
    private var inventoryAssembler: SidecarCollectionsWire.InventoryAssembler? = null
    private var activeV5Canary = false
    private var canaryPhase = CanaryPhase.NONE
    private var v5DataAcksThisAttempt = 0

    private enum class CanaryPhase { NONE, CONFIGURING, ABORTING, STAGING, COMMITTING }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification("Waiting for X3 check-in"))
        if (intent?.action == ACTION_CANCEL) {
            prefs.edit()
                .putBoolean(KEY_PENDING, false)
                .remove(KEY_NEXT_ROTATION_REFRESH_AT)
                .apply()
            stopCurrentTransport()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_QUEUE) {
            retryCount = 0
            // Latest request wins. Cancel an older scan/transfer before changing its generation.
            stopCurrentTransport()
            val module = intent.getStringExtra(EXTRA_MODULE).orEmpty()
            val note = intent.getStringExtra(EXTRA_NOTE).orEmpty()
            val generation = prefs.getLong(KEY_GENERATION, 0L) + 1L
            val pageIndex = intent.getIntExtra(EXTRA_PAGE_INDEX, 0).coerceAtLeast(0)
            prefs.edit()
                .putBoolean(KEY_PENDING, true)
                .putString(KEY_MODULE, module)
                .putString(KEY_NOTE, note)
                .putLong(KEY_GENERATION, generation)
                .putInt(KEY_PAGE_INDEX, pageIndex)
                .putBoolean(EXTRA_AUTOMATIC, intent.getBooleanExtra(EXTRA_AUTOMATIC, false))
                .putBoolean(KEY_V5_CANARY, intent.getBooleanExtra(EXTRA_V5_CANARY, false))
                .putBoolean(KEY_V5_PLAYLIST_CHAIN, module == PlaylistModule.displayName)
                .remove(KEY_GROUP_START)
                .remove(KEY_NEXT_ROTATION_REFRESH_AT)
                .apply()
            Log.i(TAG, "queued module=$module generation=$generation")
            if (SidecarLiveData.needsRefresh(module)) {
                updateNotification("Updating $module")
                Thread {
                    val refreshed = SidecarLiveData.refresh(this, module, note,
                        forcePlaylist = !intent.getBooleanExtra(EXTRA_AUTOMATIC, false))
                    if (prefs.getLong(KEY_GENERATION, -1L) == generation) {
                        val cacheKey = "$KEY_RENDER_CONTENT:$module"
                        val content = refreshed ?: prefs.getString(cacheKey, null) ?: note
                        prefs.edit().putString(cacheKey, content).apply()
                        handler.post {
                            if (prefs.getLong(KEY_GENERATION, -1L) != generation) return@post
                            val oldSignature = prefs.getString(KEY_LAST_BUNDLE_SIGNATURE, null)
                            val newSignature = if (module == PlaylistModule.displayName) {
                                SidecarPlaylist.snapshotSignature(this)
                            } else null
                            val unchangedLocalBundle = module == PlaylistModule.displayName &&
                                SidecarPlaylist.isRotationActive(this) &&
                                oldSignature == newSignature
                            if (module == PlaylistModule.displayName) {
                                Log.i(TAG, "playlist content old=${oldSignature?.take(8)} new=${newSignature?.take(8)} changed=${oldSignature != newSignature}")
                            }
                            if (unchangedLocalBundle) {
                                prefs.edit().putBoolean(KEY_PENDING, false).apply()
                                scheduleLocalRotationRefresh("No content changes; X3 keeps cycling locally", unchanged = true)
                            } else if (gatt == null && scanCallback == null && prefs.getBoolean(KEY_PENDING, false)) {
                                startScan()
                            }
                        }
                    }
                }.start()
                return START_STICKY
            }
            prefs.edit().putString("$KEY_RENDER_CONTENT:$module", note).apply()
        }
        if (gatt == null && scanCallback == null && prefs.getBoolean(KEY_PENDING, false)) startScan()
        if (!prefs.getBoolean(KEY_PENDING, false)) {
            if (SidecarPlaylist.isRotationActive(this)) {
                restoreLocalRotationRefresh()
            } else {
                stopWithoutResult()
            }
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun stopCurrentTransport() {
        reconnect?.let(handler::removeCallbacks)
        reconnect = null
        scanCallback?.let { callback ->
            runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
        }
        scanCallback = null
        scanTimeout?.let(handler::removeCallbacks)
        scanTimeout = null
        closeGatt()
    }


    private fun scheduleScan(delayMs: Long) {
        reconnect?.let(handler::removeCallbacks)
        reconnect = Runnable {
            reconnect = null
            if (prefs.getBoolean(KEY_PENDING, false) && gatt == null && scanCallback == null) startScan()
        }.also { handler.postDelayed(it, delayMs) }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            failFinished("Bluetooth permission denied")
            return
        }
        val bluetoothAdapter = adapter ?: run {
            failFinished("Bluetooth is unavailable on this phone")
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            retry("Bluetooth is off")
            return
        }
        val scanner = bluetoothAdapter.bluetoothLeScanner ?: run {
            retry("BLE scanner unavailable")
            return
        }
        updateNotification("Waiting for X3 check-in")
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (scanCallback !== this) return
                Log.i(TAG, "X3 check-in found; connecting")
                runCatching { scanner.stopScan(this) }
                if (scanCallback === this) scanCallback = null
                scanTimeout?.let(handler::removeCallbacks)
                scanTimeout = null
                connect(result.device)
            }

            override fun onScanFailed(errorCode: Int) {
                if (scanCallback === this) scanCallback = null
                scanTimeout?.let(handler::removeCallbacks)
                scanTimeout = null
                retry("scan failed $errorCode")
            }
        }
        scanCallback = callback
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceUuid)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scanner.startScan(listOf(filter), settings, callback) }
            .onFailure {
                scanCallback = null
                retry("scan start failed: ${it.message}")
            }
            .onSuccess {
                scanTimeout = Runnable {
                    if (scanCallback === callback) {
                        runCatching { scanner.stopScan(callback) }
                        scanCallback = null
                        if (isPersistentRotationRequest()) {
                            Log.i(TAG, "X3 offline; keeping rotation queued")
                            updateNotification("X3 is offline; rotation will resume automatically")
                            scheduleScan(ROTATION_OFFLINE_RETRY_MS)
                        } else {
                            failFinished("X3 not found within ${SCAN_TIMEOUT_MS / 1000}s")
                        }
                    }
                }.also { handler.postDelayed(it, SCAN_TIMEOUT_MS) }
            }
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        loadActiveRequest()
        updateNotification("Sending $activeModule")
        val connected = runCatching { device.connectGatt(this, false, callback, BluetoothDevice.TRANSPORT_LE) }
            .getOrElse {
                retry("connect failed: ${it.message}")
                return
            }
        gatt = connected
        restartTimeout()
    }

    private fun loadActiveRequest() {
        activeGeneration = prefs.getLong(KEY_GENERATION, 0L)
        activeModule = prefs.getString(KEY_MODULE, StatusModule.displayName).orEmpty()
        activeNote = prefs.getString("$KEY_RENDER_CONTENT:$activeModule", prefs.getString(KEY_NOTE, "")).orEmpty()
        activePageIndex = prefs.getInt(KEY_PAGE_INDEX, 0)
        activeV5Canary = prefs.getBoolean(KEY_V5_CANARY, false)
        activeBundleSignature = if (activeModule == PlaylistModule.displayName) {
            SidecarPlaylist.snapshotSignature(this)
        } else ""
    }

    @SuppressLint("MissingPermission")
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (gatt !== g) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                retry("GATT state error $status")
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> if (!g.requestMtu(517)) retry("MTU request rejected")
                BluetoothProfile.STATE_DISCONNECTED -> retry("disconnected before completion")
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, value: Int, status: Int) {
            if (gatt !== g) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                retry("MTU failed $status")
                return
            }
            mtu = value
            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            if (!g.discoverServices()) retry("service discovery rejected")
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (gatt !== g) return
            val service = if (status == BluetoothGatt.GATT_SUCCESS) g.getService(serviceUuid) else null
            val statusChar = service?.getCharacteristic(statusUuid)
            val cccd = statusChar?.getDescriptor(cccdUuid)
            if (statusChar == null || cccd == null) {
                retry("completion status unsupported")
                return
            }
            if (!g.setCharacteristicNotification(statusChar, true)) {
                retry("notification enable rejected")
                return
            }
            val rc = g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            if (rc != BluetoothGatt.GATT_SUCCESS) retry("CCCD write rejected $rc")
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (gatt !== g || descriptor.uuid != cccdUuid) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                retry("CCCD write failed $status")
                return
            }
            val statusCharacteristic = g.getService(serviceUuid)?.getCharacteristic(statusUuid)
            if (statusCharacteristic == null || !g.readCharacteristic(statusCharacteristic)) {
                retry("STATUS freshness read rejected")
            }
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (gatt !== g) return
            if (characteristic.uuid == statusUuid) {
                val counter = SidecarFrame.statusCounter(value)
                if (awaitingCompletion && queue == null) {
                    val state = value.firstOrNull()?.toInt()?.and(0xff)
                    val expectedCounter = statusBaseline?.let { (it + 1L) and 0xffffffffL }
                    when {
                        status == BluetoothGatt.GATT_SUCCESS && state == 1 && counter == expectedCounter ->
                            handleStatus(g, value)
                        status == BluetoothGatt.GATT_SUCCESS && state == 0 && counter == statusBaseline ->
                            scheduleCompletionRead(g, 500L)
                        else -> retry("completion STATUS invalid status=$status state=$state counter=$counter expected=$expectedCounter")
                    }
                    return
                }
                if (status != BluetoothGatt.GATT_SUCCESS || value.firstOrNull()?.toInt()?.and(0xff) != 0 || counter == null) {
                    retry("STATUS freshness barrier failed status=$status value=${value.firstOrNull()}")
                    return
                }
                statusBaseline = counter
                val infoCharacteristic = g.getService(serviceUuid)?.getCharacteristic(infoUuid)
                if (infoCharacteristic == null || !g.readCharacteristic(infoCharacteristic)) retry("INFO read rejected")
                return
            }
            if (characteristic.uuid == infoUuid) {
                val info = if (status == BluetoothGatt.GATT_SUCCESS) SidecarFrame.parseInfo(value) else null
                if (info == null) {
                    retry("INFO invalid status=$status bytes=${value.size}")
                    return
                }
                restartTimeout()
                if (info.supportsCollections) {
                    pendingInfo = info
                    inventoryAssembler = SidecarCollectionsWire.InventoryAssembler()
                    val inventory = g.getService(serviceUuid)?.getCharacteristic(inventoryUuid)
                    if (inventory == null || !g.readCharacteristic(inventory)) {
                        Log.w(TAG, "v5 inventory unavailable; continuing compatible v4 delivery")
                        if (activeV5Canary) failFinished("V5 canary requires X3 inventory support")
                        else continueAfterInventoryDiscovery(g)
                    }
                } else {
                    scheduleTransfer(g, info)
                }
                return
            }
            if (characteristic.uuid == inventoryUuid) {
                val complete = if (status == BluetoothGatt.GATT_SUCCESS) {
                    inventoryAssembler?.append(0, value)
                } else null
                val inventory = complete?.let(SidecarCollectionsWire::parseInventory)
                if (inventory == null) {
                    Log.w(TAG, "v5 inventory invalid status=$status bytes=${value.size}; continuing compatible v4 delivery")
                    if (activeV5Canary) {
                        failFinished("V5 canary requires a valid X3 inventory")
                        return
                    }
                } else {
                    Log.i(TAG, "v5 inventory root=${inventory.rootGeneration} collections=${inventory.entries.size} pages=${inventory.totalPages} session=${inventory.session != null}")
                    if (activeV5Canary) {
                        val v5Info = pendingInfo ?: return
                        pendingInfo = null
                        inventoryAssembler = null
                        startCollectionCanary(g, v5Info, inventory)
                        return
                    }
                    if (activeModule == PlaylistModule.displayName &&
                        prefs.getBoolean(KEY_V5_PLAYLIST_CHAIN, false)) {
                        val v5Info = pendingInfo ?: return
                        pendingInfo = null
                        inventoryAssembler = null
                        startNextPlaylistCollection(g, v5Info, inventory)
                        return
                    }
                }
                continueAfterInventoryDiscovery(g)
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (gatt !== g || characteristic.uuid != rxUuid) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                retry("frame write failed $status")
                return
            }
            val packets = queue ?: return
            val sent = packets.pollFirst()
            val sentOpcode = sent?.firstOrNull()?.toInt()?.and(0xff)
            if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
                activeV5Canary && canaryPhase == CanaryPhase.STAGING &&
                sentOpcode == SidecarFrame.OP_DATA) {
                ++v5DataAcksThisAttempt
                val interruptAfter = prefs.getInt(KEY_V5_INTERRUPT_AFTER_DATA, 0)
                if (interruptAfter > 0 && v5DataAcksThisAttempt >= interruptAfter) {
                    prefs.edit().putInt(KEY_V5_INTERRUPT_AFTER_DATA, 0).commit()
                    Log.w(TAG, "debug one-shot interruption after $v5DataAcksThisAttempt V5 data packet(s)")
                    queue = null
                    closeGatt()
                    scheduleScan(2_000L)
                    return
                }
            }
            if (packets.isEmpty()) {
                queue = null
                if (canaryPhase == CanaryPhase.CONFIGURING || canaryPhase == CanaryPhase.ABORTING ||
                    canaryPhase == CanaryPhase.STAGING ||
                    canaryPhase == CanaryPhase.COMMITTING) {
                    val committing = canaryPhase == CanaryPhase.COMMITTING
                    // The ATT acknowledgement only proves the packet reached NimBLE. Keep the
                    // connection alive until the X3 loop has durably stored the final page or root.
                    updateNotification(when {
                        committing -> "Collection committed; verifying inventory"
                        canaryPhase == CanaryPhase.ABORTING -> "Obsolete collection update cleared; verifying inventory"
                        canaryPhase == CanaryPhase.CONFIGURING -> "Collection settings saved; verifying inventory"
                        else -> "Collection pages stored; checking durable session"
                    })
                    restartTimeout()
                    handler.postDelayed({
                        if (gatt === g) {
                            closeGatt()
                            scheduleScan(2_000L)
                        }
                    }, V5_STORAGE_GUARD_MS)
                    return
                }
                uploadFinishedMs = System.currentTimeMillis()
                awaitingCompletion = true
                if (completionObserved) {
                    completeDelivery(g)
                } else {
                    updateNotification("Waiting for X3 render confirmation")
                    restartTimeout(if (canaryPhase == CanaryPhase.COMMITTING) BUNDLE_COMPLETION_TIMEOUT_MS
                    else if (backgroundBundle) maxOf(BUNDLE_COMPLETION_TIMEOUT_MS,
                        2L * SidecarFrame.MAX_BUNDLE_PAGES * (SidecarPlaylist.pageSeconds(this@SidecarDeliveryService) * 1000L + 1000L) + 120_000L)
                        else if (deliveredAsBundle) BUNDLE_COMPLETION_TIMEOUT_MS else CONNECTION_TIMEOUT_MS)
                    scheduleCompletionRead(g, 500L)
                }
            } else {
                val guardMs = if (activeV5Canary || canaryPhase == CanaryPhase.CONFIGURING ||
                    canaryPhase == CanaryPhase.ABORTING) when (sentOpcode) {
                    // Both operations durably update FAT-backed state on the X3 loop task. The
                    // GATT acknowledgement only confirms receipt by NimBLE, not completion of
                    // that storage work, so do not stream the next packet immediately.
                    SidecarFrame.OP_BEGIN_COLLECTION_SESSION -> V5_STORAGE_GUARD_MS
                    SidecarFrame.OP_BEGIN_COLLECTION_PAGE -> PAGE_BEGIN_GUARD_MS
                    SidecarFrame.OP_END -> V5_STORAGE_GUARD_MS
                    SidecarFrame.OP_SET_COLLECTION_ENABLED -> V5_STORAGE_GUARD_MS
                    SidecarFrame.OP_ABORT_COLLECTION_SESSION -> V5_STORAGE_GUARD_MS
                    // The X3 persists collection chunks as they arrive. FAT maintenance can
                    // occasionally stall its loop for several seconds, so leave enough room in
                    // the bounded receive queue to absorb that pause without losing sequences.
                    SidecarFrame.OP_DATA -> 250L
                    else -> 0L
                } else if (!deliveredAsBundle) 0L else when (sentOpcode) {
                    SidecarFrame.OP_BEGIN_PAGE -> if (backgroundBundle) 250L else PAGE_BEGIN_GUARD_MS
                    SidecarFrame.OP_BEGIN_GROUP_PAGE ->
                        if ((sent?.getOrNull(19)?.toInt() ?: -1) == 0) 8_000L else 250L
                    SidecarFrame.OP_END -> PAGE_END_GUARD_MS
                    // GATT write-with-response already provides receiver backpressure. A small
                    // guard keeps background transfers gentle without adding ~39 s to 15 pages.
                    SidecarFrame.OP_DATA -> if (backgroundBundle) 5L else 0L
                    else -> 0L
                }
                if (guardMs > 0L) {
                    restartTimeout()
                    handler.postDelayed({ if (gatt === g) writeNext(g) }, guardMs)
                } else {
                    writeNext(g)
                }
            }
        }

        private fun handleStatus(g: BluetoothGatt, value: ByteArray) {
            val counter = SidecarFrame.statusCounter(value)
            val expectedCounter = statusBaseline?.let { (it + 1L) and 0xffffffffL }
            if (gatt !== g || transferStartMs == 0L ||
                value.firstOrNull()?.toInt()?.and(0xff) != 1 || counter == null || counter != expectedCounter) return
            completionObserved = true
            if (!awaitingCompletion || queue != null) {
                Log.i(TAG, "X3 completion arrived before final write acknowledgement; holding it")
                return
            }
            completeDelivery(g)
        }

        private fun completeDelivery(g: BluetoothGatt) {
            if (gatt !== g || !completionObserved || !awaitingCompletion || queue != null) return
            awaitingCompletion = false
            completionObserved = false
            val elapsed = System.currentTimeMillis() - transferStartMs
            Log.i(TAG, "module=$activeModule device rendered generation=$activeGeneration ms=$elapsed")
        if (canaryPhase == CanaryPhase.COMMITTING) {
            prefs.edit().putBoolean(KEY_PENDING, false).putBoolean(KEY_V5_CANARY, false)
                .remove(KEY_V5_CANARY_SESSION).remove(KEY_V5_CANARY_COLLECTION)
                .remove(KEY_V5_CANARY_CRCS).remove(KEY_V5_CANARY_CANDIDATE).apply()
            canaryPhase = CanaryPhase.NONE
            if (prefs.getBoolean(KEY_V5_PLAYLIST_CHAIN, false)) {
                completePlaylistCollection(g)
                return
            }
            closeGatt()
            updateNotification("V5 canary collection published")
            stopFinished()
            return
        }
            val sameGeneration = prefs.getLong(KEY_GENERATION, -1L) == activeGeneration
            val moduleAdvances = SidecarModules.find(activeModule)?.autoAdvanceWhenQueued != false
            val localRotation = sameGeneration && activeModule == PlaylistModule.displayName &&
                deliveredAsBundle && SidecarPlaylist.isRotationActive(this@SidecarDeliveryService)
            val resumeRotation = sameGeneration && activeModule != PlaylistModule.displayName &&
                moduleAdvances && SidecarPlaylist.isRotationActive(this@SidecarDeliveryService)
            val moreGroups = groupedTransfer && activeGroupStart + activeGroupCount < activePageCount
            if (moreGroups && sameGeneration) {
                prefs.edit().putInt(KEY_GROUP_START, activeGroupStart + activeGroupCount)
                    .putBoolean(KEY_PENDING, true).apply()
            } else if (resumeRotation) {
                prefs.edit().putBoolean(KEY_PENDING, false).apply()
            } else if (sameGeneration && activePageCount > 1 && moduleAdvances && !deliveredAsBundle) {
                prefs.edit()
                    .putInt(KEY_PAGE_INDEX, (activePageIndex + 1).mod(activePageCount))
                    .putBoolean(KEY_PENDING, true)
                    .apply()
            } else if (sameGeneration) {
                prefs.edit().putBoolean(KEY_PENDING, false).apply()
            }
            closeGatt()
            if (moreGroups && sameGeneration) {
                updateNotification("Updated pages ${activeGroupStart + 1}-${activeGroupStart + activeGroupCount}; continuing")
                scheduleScan(2_000L)
                return
            }
            if (sameGeneration && activeModule == PlaylistModule.displayName && !localRotation) {
                SidecarLiveData.refreshDuePlaylistAsync(this@SidecarDeliveryService)
            }
            if (localRotation) {
                prefs.edit().putString(KEY_LAST_BUNDLE_SIGNATURE, activeBundleSignature)
                    .remove(KEY_GROUP_START)
                    .putInt(KEY_BUNDLE_PAGES, activePageCount)
                    .putLong(KEY_UPLOAD_MS, (uploadFinishedMs - transferStartMs).coerceAtLeast(0L)).apply()
                scheduleLocalRotationRefresh("X3 is cycling $activePageCount pages locally")
            } else if (resumeRotation) {
                updateNotification("Temporary page delivered; returning to rotation")
                reconnect = Runnable {
                    reconnect = null
                    if (SidecarPlaylist.isRotationActive(this@SidecarDeliveryService)) {
                        queue(this@SidecarDeliveryService, PlaylistModule.displayName, "")
                    } else {
                        stopFinished()
                    }
                }.also { handler.postDelayed(it, PAGE_RECONNECT_DELAY_MS) }
            } else if (prefs.getBoolean(KEY_PENDING, false)) {
                val delay = if (sameGeneration && activePageCount > 1 && moduleAdvances && !deliveredAsBundle) PAGE_RECONNECT_DELAY_MS else 0L
                updateNotification("Page ${activePageIndex + 1}/$activePageCount delivered; waiting for next X3 check-in")
                scheduleScan(delay)
            } else {
                stopFinished()
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == statusUuid) handleStatus(g, value)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == statusUuid) handleStatus(g, characteristic.value ?: ByteArray(0))
        }
    }

    @SuppressLint("MissingPermission")
    private fun scheduleCompletionRead(g: BluetoothGatt, delayMs: Long) {
        completionPoll?.let(handler::removeCallbacks)
        completionPoll = Runnable {
            completionPoll = null
            if (gatt !== g || !awaitingCompletion || queue != null) return@Runnable
            val statusCharacteristic = g.getService(serviceUuid)?.getCharacteristic(statusUuid)
            if (statusCharacteristic == null || !g.readCharacteristic(statusCharacteristic)) {
                retry("completion STATUS read rejected")
            }
        }.also { handler.postDelayed(it, delayMs) }
    }

    @SuppressLint("MissingPermission")
    private fun startTransfer(g: BluetoothGatt, info: SidecarFrame.DeviceInfo) {
        if (info.width <= 0 || info.height <= 0 || info.bpp != 1 || info.maxChunk <= 0 || info.protocolVer !in 1..5) {
            retry("unsupported INFO geometry=${info.width}x${info.height} bpp=${info.bpp} chunk=${info.maxChunk} protocol=${info.protocolVer}")
            return
        }
        awaitingCompletion = false
        completionObserved = false
        val module = SidecarModules.find(activeModule) ?: StatusModule
        activePageCount = module.pageCount(this, info.height, info.width, activeNote)
        activePageIndex = activePageIndex.mod(activePageCount)
        val chunkSize = minOf(info.maxChunk, 509, mtu - 6)
        if (chunkSize <= 0) {
            retry("invalid chunk size $chunkSize")
            return
        }
        deliveredAsBundle = module.supportsLocalPages && when {
            info.protocolVer >= 3 -> activePageCount in 1..SidecarFrame.MAX_BUNDLE_PAGES
            info.protocolVer >= 2 -> activePageCount == 2
            else -> false
        }
        val automaticPlaylist = activeModule == PlaylistModule.displayName && prefs.getBoolean(EXTRA_AUTOMATIC, false)
        groupedTransfer = info.supportsGroupedUpdates && automaticPlaylist && deliveredAsBundle &&
            prefs.getInt(KEY_BUNDLE_PAGES, 0) == activePageCount &&
            !prefs.getString(KEY_LAST_BUNDLE_SIGNATURE, null).isNullOrEmpty()
        activeGroupStart = if (groupedTransfer) prefs.getInt(KEY_GROUP_START, 0)
            .coerceIn(0, activePageCount - 1) else 0
        activeGroupCount = if (groupedTransfer) minOf(info.maxGroupPages, activePageCount - activeGroupStart)
            else activePageCount
        prefs.edit().putBoolean(KEY_BACKGROUND_SUPPORTED, info.supportsBackgroundBundle).apply()
        backgroundBundle = info.supportsBackgroundBundle && deliveredAsBundle && !groupedTransfer &&
            automaticPlaylist
        var payloadBytes = 0
        val packets = ArrayDeque<ByteArray>()
        if (info.supportsPageCadence) {
            packets.add(SidecarFrame.pageCadencePacket(
                if (module.supportsRotation) SidecarPlaylist.pageSeconds(this) else 0
            ))
        }
        if (backgroundBundle) packets.add(byteArrayOf(0x07, 2))
        val pages = when {
            groupedTransfer -> activeGroupStart until activeGroupStart + activeGroupCount
            deliveredAsBundle -> 0 until activePageCount
            else -> activePageIndex..activePageIndex
        }
        try {
            pages.forEach { page ->
                val portrait = module.renderPage(this, info.height, info.width, activeNote, page)
                val bits = SidecarFrame.portraitToPanelBits(portrait, info.width, info.height)
                portrait.recycle()
                payloadBytes += bits.size
                packets.add(when {
                    groupedTransfer -> SidecarFrame.beginGroupPagePacket(
                        0, 0, info.width, info.height, bits, page, activePageCount, activePageIndex,
                        activeGroupStart, activeGroupCount, page - activeGroupStart
                    )
                    deliveredAsBundle -> SidecarFrame.beginPagePacket(
                        0, 0, info.width, info.height, bits, page, activePageCount, activePageIndex
                    )
                    else -> SidecarFrame.beginPacket(0, 0, info.width, info.height, bits.size)
                })
                packets.addAll(SidecarFrame.dataPackets(bits, chunkSize))
                packets.add(SidecarFrame.endPacket())
            }
        } catch (error: Exception) {
            failFinished("render/encode failed: ${error.message ?: error.javaClass.simpleName}")
            return
        }
        queue = packets
        transferStartMs = System.currentTimeMillis()
        uploadFinishedMs = transferStartMs
        Log.i(TAG, "sending module=$activeModule page=${activePageIndex + 1}/$activePageCount bundle=$deliveredAsBundle grouped=$groupedTransfer range=$activeGroupStart+$activeGroupCount generation=$activeGeneration packets=${packets.size} bytes=$payloadBytes")
        writeNext(g)
    }

    private fun continueAfterInventoryDiscovery(g: BluetoothGatt) {
        val info = pendingInfo ?: return
        pendingInfo = null
        inventoryAssembler = null
        scheduleTransfer(g, info)
    }

    private fun startCollectionCanary(
        g: BluetoothGatt,
        info: SidecarFrame.DeviceInfo,
        inventory: SidecarCollectionsWire.Inventory
    ) {
        if (!activeV5Canary) return
        val module = SidecarModules.find(activeModule) ?: run {
            failFinished("Unknown canary module")
            return
        }
        val descriptor = runCatching { SidecarCollections.descriptorFor(module) }.getOrElse {
            failFinished("Module has no collection identity")
            return
        }
        activePageCount = module.pageCount(this, SidecarFrame.SUPPORTED_PANEL_HEIGHT,
            SidecarFrame.SUPPORTED_PANEL_WIDTH, activeNote)
        if (activePageCount !in 1..SidecarCollections.MAX_COLLECTION_PAGES) {
            failFinished("Canary has $activePageCount pages; maximum is ${SidecarCollections.MAX_COLLECTION_PAGES}")
            return
        }
        val pageBits = ArrayList<ByteArray>(activePageCount)
        try {
            repeat(activePageCount) { page ->
                val portrait = module.renderPage(this, SidecarFrame.SUPPORTED_PANEL_HEIGHT,
                    SidecarFrame.SUPPORTED_PANEL_WIDTH, activeNote, page)
                pageBits += SidecarFrame.portraitToPanelBits(portrait,
                    SidecarFrame.SUPPORTED_PANEL_WIDTH, SidecarFrame.SUPPORTED_PANEL_HEIGHT)
                portrait.recycle()
            }
        } catch (error: Exception) {
            failFinished("Canary render failed: ${error.message ?: error.javaClass.simpleName}")
            return
        }
        val pageCrcs = pageBits.map { CRC32().apply { update(it) }.value }
        val crcIdentity = pageCrcs.joinToString(",") { it.toString(16) }
        val published = inventory.entries.firstOrNull { it.collectionId == descriptor.id.toLong() }
        val savedSession = prefs.getLong(KEY_V5_CANARY_SESSION, 0L)
        val savedCollection = prefs.getInt(KEY_V5_CANARY_COLLECTION, 0)
        val savedCrcs = prefs.getString(KEY_V5_CANARY_CRCS, null)
        val savedCandidate = prefs.getLong(KEY_V5_CANARY_CANDIDATE, 0L)
        val publishedExpectedCandidate = savedCandidate != 0L &&
            published?.collectionGeneration == savedCandidate
        // Compatibility for the first installed canary build, which persisted the session and
        // CRC identity before the candidate-generation preference existed. A create can only
        // publish generation one.
        val publishedLegacyCreate = savedCandidate == 0L &&
            published?.collectionGeneration == 1L
        if (inventory.session == null && savedSession != 0L && savedCollection == descriptor.id &&
            savedCrcs == crcIdentity && published?.pageCount == activePageCount &&
            (publishedExpectedCandidate || publishedLegacyCreate)) {
            Log.i(TAG, "v5 canary published collection=${descriptor.id} generation=${published.collectionGeneration}")
            prefs.edit().putBoolean(KEY_PENDING, false).putBoolean(KEY_V5_CANARY, false)
                .remove(KEY_V5_CANARY_SESSION).remove(KEY_V5_CANARY_COLLECTION)
                .remove(KEY_V5_CANARY_CRCS).remove(KEY_V5_CANARY_CANDIDATE).apply()
            activeV5Canary = false
            canaryPhase = CanaryPhase.NONE
            if (prefs.getBoolean(KEY_V5_PLAYLIST_CHAIN, false)) {
                completePlaylistCollection(g)
                return
            }
            closeGatt()
            updateNotification("V5 canary collection published")
            stopFinished()
            return
        }
        val projectedTotal = inventory.totalPages - (published?.pageCount ?: 0) + activePageCount
        if (activePageCount > info.maxCollectionPages || projectedTotal > info.maxTotalPublishedPages ||
            (published == null && inventory.entries.size >= info.maxCollections)) {
            failFinished("Canary exceeds the X3 collection limits")
            return
        }
        val existingSession = inventory.session
        if (existingSession != null && existingSession.collectionId != descriptor.id.toLong()) {
            failFinished("X3 has another collection update in progress")
            return
        }
        // BEGIN is acknowledged by BLE before the X3's storage worker necessarily exposes the
        // new journal through inventory.  A quick reconnect can therefore still report no
        // session.  Reuse the durable phone-side identity for the same collection payload so a
        // delayed BEGIN cannot later appear as an "obsolete" transaction and force an abort loop.
        val savedIdentityMatches = savedSession != 0L &&
            savedCollection == descriptor.id && savedCrcs == crcIdentity
        val sessionId = existingSession?.sessionId ?:
            if (savedIdentityMatches) savedSession else randomSessionId()
        val plan = runCatching {
            SidecarCollectionsWire.planFullSession(sessionId, descriptor.id.toLong(),
                inventory.rootGeneration, published?.collectionGeneration ?: 0L, pageCrcs)
        }.getOrElse {
            failFinished("Canary plan failed: ${it.message}")
            return
        }
        if (existingSession != null) {
            if (savedSession != existingSession.sessionId || savedCollection != descriptor.id ||
                savedCrcs != crcIdentity || existingSession.mode != plan.session.mode ||
                existingSession.candidateGeneration != plan.session.candidateGeneration ||
                existingSession.requiredBitmap != plan.session.requiredBitmap) {
                if (prefs.getBoolean(KEY_V5_PLAYLIST_CHAIN, false)) {
                    queue = ArrayDeque<ByteArray>().apply {
                        add(SidecarCollectionsWire.abortPacket(
                            SidecarCollectionsWire.Abort(existingSession.sessionId)))
                    }
                    canaryPhase = CanaryPhase.ABORTING
                    transferStartMs = System.currentTimeMillis()
                    prefs.edit().putBoolean(KEY_PENDING, true).putBoolean(KEY_V5_CANARY, false)
                        .putString(KEY_MODULE, PlaylistModule.displayName)
                        .remove(KEY_V5_CANARY_SESSION).remove(KEY_V5_CANARY_COLLECTION)
                        .remove(KEY_V5_CANARY_CRCS).remove(KEY_V5_CANARY_CANDIDATE).apply()
                    activeModule = PlaylistModule.displayName
                    activeV5Canary = false
                    Log.i(TAG, "v5 playlist aborting obsolete session=${existingSession.sessionId} collection=${existingSession.collectionId}")
                    updateNotification("Clearing obsolete collection update")
                    writeNext(g)
                    return
                }
                failFinished("Existing X3 session cannot be safely resumed by this canary")
                return
            }
            if (savedCandidate == 0L) {
                prefs.edit().putLong(KEY_V5_CANARY_CANDIDATE,
                    existingSession.candidateGeneration).apply()
            }
        } else {
            prefs.edit().putLong(KEY_V5_CANARY_SESSION, sessionId)
                .putInt(KEY_V5_CANARY_COLLECTION, descriptor.id)
                .putString(KEY_V5_CANARY_CRCS, crcIdentity)
                .putLong(KEY_V5_CANARY_CANDIDATE, plan.session.candidateGeneration).apply()
        }
        val received = existingSession?.receivedBitmap ?: 0
        val packets = ArrayDeque<ByteArray>()
        val thisConnection = if (existingSession == null) emptyList()
            else SidecarCollectionsWire.nextMissingPages(activePageCount, received)
        if (existingSession == null) {
            // Session creation is a durable checkpoint. Reconnect and verify it through inventory
            // before sending a page instead of relying on an ATT acknowledgement plus a delay.
            packets.add(plan.beginPacket)
            canaryPhase = CanaryPhase.STAGING
        } else if (thisConnection.isEmpty()) {
            packets.add(plan.commitPacket)
            canaryPhase = CanaryPhase.COMMITTING
        } else {
            val chunkSize = minOf(info.maxChunk, 509, mtu - 6)
            if (chunkSize <= 0) {
                failFinished("Invalid V5 canary chunk size")
                return
            }
            thisConnection.forEach { page ->
                val bits = pageBits[page]
                var encoding = selectedPageEncoding(info)
                var encoded = runCatching {
                    SidecarCollectionsWire.encodePage(bits, SidecarFrame.SUPPORTED_PANEL_WIDTH,
                        SidecarFrame.SUPPORTED_PANEL_HEIGHT, encoding)
                }.getOrElse { error ->
                    Log.w(TAG, "v5 page=$page ${encoding.name} failed; using RAW", error)
                    encoding = SidecarCollectionsWire.PageEncoding.RAW
                    bits
                }
                if (encoding != SidecarCollectionsWire.PageEncoding.RAW && encoded.size >= bits.size) {
                    Log.i(TAG, "v5 page=$page ${encoding.name} did not shrink; using RAW")
                    encoding = SidecarCollectionsWire.PageEncoding.RAW
                    encoded = bits
                }
                packets.add(SidecarCollectionsWire.beginPagePacket(SidecarCollectionsWire.BeginPage(
                    0, 0, SidecarFrame.SUPPORTED_PANEL_WIDTH, SidecarFrame.SUPPORTED_PANEL_HEIGHT,
                    encoded.size.toLong(), sessionId, page, pageCrcs[page],
                    plan.session.candidateGeneration, encoding)))
                packets.addAll(SidecarFrame.dataPackets(encoded, chunkSize))
                packets.add(SidecarFrame.endPacket())
                Log.i(TAG, "v5 page=$page encoding=${encoding.name} bytes=${encoded.size} raw=${bits.size}")
            }
            canaryPhase = CanaryPhase.STAGING
            v5DataAcksThisAttempt = 0
        }
        queue = packets
        transferStartMs = System.currentTimeMillis()
        awaitingCompletion = canaryPhase == CanaryPhase.COMMITTING
        completionObserved = false
        Log.i(TAG, "v5 canary collection=${descriptor.id} session=$sessionId received=0x${received.toString(16)} sending=${thisConnection.joinToString()}")
        updateNotification(if (canaryPhase == CanaryPhase.COMMITTING) "Publishing V5 canary" else "Sending V5 canary pages")
        writeNext(g)
    }

    private fun selectedPageEncoding(info: SidecarFrame.DeviceInfo): SidecarCollectionsWire.PageEncoding {
        return when (prefs.getString(KEY_V5_PAGE_ENCODING, "auto")?.lowercase()) {
            "raw" -> SidecarCollectionsWire.PageEncoding.RAW
            "png" -> if (info.supportsPngPages) SidecarCollectionsWire.PageEncoding.PNG_1BPP
                else SidecarCollectionsWire.PageEncoding.RAW
            "deflate" -> if (info.supportsDeflatePages) SidecarCollectionsWire.PageEncoding.DEFLATE_RAW
                else SidecarCollectionsWire.PageEncoding.RAW
            else -> when {
                info.supportsDeflatePages -> SidecarCollectionsWire.PageEncoding.DEFLATE_RAW
                info.supportsPngPages -> SidecarCollectionsWire.PageEncoding.PNG_1BPP
                else -> SidecarCollectionsWire.PageEncoding.RAW
            }
        }
    }

    private fun randomSessionId(): Long {
        var value = 0L
        while (value == 0L) value = SecureRandom().nextLong()
        return value
    }

    private fun startNextPlaylistCollection(
        g: BluetoothGatt,
        info: SidecarFrame.DeviceInfo,
        inventory: SidecarCollectionsWire.Inventory
    ) {
        val enabledModules = SidecarPlaylist.enabledModules(this)
        val interrupted = inventory.session?.let { session ->
            enabledModules.firstOrNull { module ->
                SidecarCollections.descriptorFor(module).id.toLong() == session.collectionId
            }
        }
        if (interrupted != null) {
            activeModule = interrupted.displayName
            activeNote = SidecarPlaylist.renderContent(this, interrupted)
            activeV5Canary = true
            prefs.edit().putString(KEY_MODULE, activeModule)
                .putString("$KEY_RENDER_CONTENT:$activeModule", activeNote)
                .putBoolean(KEY_V5_CANARY, true).apply()
            Log.i(TAG, "v5 playlist resuming collection=${inventory.session?.collectionId} module=${interrupted.displayName}")
            startCollectionCanary(g, info, inventory)
            return
        }
        val enabledIds = enabledModules.map { SidecarCollections.descriptorFor(it).id.toLong() }.toSet()
        val flagMismatch = inventory.entries.firstOrNull { entry ->
            val shouldEnable = entry.collectionId in enabledIds
            val isEnabled = entry.flags and 1 != 0
            shouldEnable != isEnabled
        }
        if (flagMismatch != null) {
            queue = ArrayDeque<ByteArray>().apply {
                add(SidecarCollectionsWire.setEnabledPacket(flagMismatch.collectionId,
                    inventory.rootGeneration, flagMismatch.collectionId in enabledIds))
            }
            canaryPhase = CanaryPhase.CONFIGURING
            transferStartMs = System.currentTimeMillis()
            Log.i(TAG, "v5 playlist collection=${flagMismatch.collectionId} enabled=${flagMismatch.collectionId in enabledIds}")
            updateNotification("Updating collection switches")
            writeNext(g)
            return
        }
        val next = enabledModules
            .map { module -> SidecarCollections.descriptorFor(module) to module }
            .sortedBy { it.first.order }
            .firstOrNull { (descriptor, module) ->
                val content = SidecarPlaylist.renderContent(this, module)
                val pages = module.pageCount(this, info.height, info.width, content)
                val published = inventory.entries.firstOrNull { it.collectionId == descriptor.id.toLong() }
                published == null || published.pageCount != pages ||
                    prefs.getString("$KEY_V5_SIGNATURE_PREFIX${descriptor.id}", null) !=
                    collectionSignature(module, content, pages)
            }
        if (next == null) {
            prefs.edit().putBoolean(KEY_PENDING, false).putBoolean(KEY_V5_CANARY, false)
                .putInt(KEY_BUNDLE_PAGES, inventory.totalPages)
                .remove(KEY_V5_PLAYLIST_CHAIN).apply()
            closeGatt()
            scheduleLocalRotationRefresh("X3 collections are current", unchanged = true)
            return
        }
        val (descriptor, module) = next
        val content = SidecarPlaylist.renderContent(this, module)
        activeModule = module.displayName
        activeNote = content
        activeV5Canary = true
        prefs.edit().putString(KEY_MODULE, activeModule)
            .putString("$KEY_RENDER_CONTENT:$activeModule", content)
            .putBoolean(KEY_V5_CANARY, true).apply()
        Log.i(TAG, "v5 playlist updating collection=${descriptor.id} module=${module.displayName}")
        startCollectionCanary(g, info, inventory)
    }

    private fun collectionSignature(module: Module, content: String, pages: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("v5-layout-1:${module.displayName}:$pages:".toByteArray(Charsets.UTF_8))
        digest.update(content.toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun completePlaylistCollection(g: BluetoothGatt) {
        val module = SidecarModules.find(activeModule)
        if (module != null) {
            val descriptor = SidecarCollections.descriptorFor(module)
            val pages = module.pageCount(this, SidecarFrame.SUPPORTED_PANEL_HEIGHT,
                SidecarFrame.SUPPORTED_PANEL_WIDTH, activeNote)
            prefs.edit().putString("$KEY_V5_SIGNATURE_PREFIX${descriptor.id}",
                collectionSignature(module, activeNote, pages)).apply()
        }
        prefs.edit().putString(KEY_MODULE, PlaylistModule.displayName)
            .putBoolean(KEY_PENDING, true).putBoolean(KEY_V5_CANARY, false)
            .remove(KEY_V5_CANARY_SESSION).remove(KEY_V5_CANARY_COLLECTION)
            .remove(KEY_V5_CANARY_CRCS).remove(KEY_V5_CANARY_CANDIDATE).apply()
        activeV5Canary = false
        canaryPhase = CanaryPhase.NONE
        closeGatt()
        updateNotification("Collection published; checking remaining updates")
        scheduleScan(2_000L)
    }

    private fun scheduleTransfer(g: BluetoothGatt, info: SidecarFrame.DeviceInfo) {
        restartTimeout()
        handler.postDelayed({ if (gatt === g) startTransfer(g, info) }, CONNECTION_SETTLE_MS)
    }

    private fun scheduleLocalRotationRefresh(message: String, unchanged: Boolean = false) {
        // Start preparation early. Firmware owns the actual two-cycle boundary; this estimate
        // merely supplies enough lead time and never publishes an incomplete replacement.
        val pages = prefs.getInt(KEY_BUNDLE_PAGES, 1).coerceIn(1, SidecarFrame.MAX_BUNDLE_PAGES)
        val twoCycles = 2L * pages * (SidecarPlaylist.pageSeconds(this) * 1000L + 600L)
        val delay = if (prefs.getBoolean(KEY_BACKGROUND_SUPPORTED, false)) {
            if (unchanged) minOf(twoCycles, SidecarLiveData.nextRefreshDelayMillis(this)).coerceAtLeast(10_000L)
            else (twoCycles - prefs.getLong(KEY_UPLOAD_MS, pages * 7000L) - 15_000L).coerceAtLeast(1_000L)
        } else SidecarLiveData.nextRefreshDelayMillis(this)
        val nextRefreshAt = System.currentTimeMillis() + delay
        prefs.edit().putLong(KEY_NEXT_ROTATION_REFRESH_AT, nextRefreshAt).apply()
        scheduleLocalRotationRefreshAt(message, nextRefreshAt)
    }

    private fun restoreLocalRotationRefresh() {
        val now = System.currentTimeMillis()
        val saved = prefs.getLong(KEY_NEXT_ROTATION_REFRESH_AT, 0L)
        val nextRefreshAt = if (saved > 0L) saved else now + SidecarLiveData.nextRefreshDelayMillis(this)
        prefs.edit().putLong(KEY_NEXT_ROTATION_REFRESH_AT, nextRefreshAt).apply()
        Log.i(TAG, "restored local rotation refresh in ${maxOf(0L, nextRefreshAt - now)} ms")
        scheduleLocalRotationRefreshAt("X3 keeps cycling locally", nextRefreshAt)
    }

    private fun scheduleLocalRotationRefreshAt(message: String, nextRefreshAt: Long) {
        reconnect?.let(handler::removeCallbacks)
        updateNotification(message)
        reconnect = Runnable {
            reconnect = null
            if (SidecarPlaylist.isRotationActive(this)) {
                queue(this, PlaylistModule.displayName, "", automatic = true)
            } else {
                stopFinished()
            }
        }.also { handler.postDelayed(it, maxOf(0L, nextRefreshAt - System.currentTimeMillis())) }
    }

    @SuppressLint("MissingPermission")
    private fun writeNext(g: BluetoothGatt) {
        val packet = queue?.peekFirst() ?: return
        val rx = g.getService(serviceUuid)?.getCharacteristic(rxUuid)
        val rc = if (rx == null) BluetoothGatt.GATT_FAILURE
        else g.writeCharacteristic(rx, packet, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        if (rc != BluetoothGatt.GATT_SUCCESS) retry("frame write rejected $rc") else restartTimeout()
    }

    private fun restartTimeout(delayMs: Long = CONNECTION_TIMEOUT_MS) {
        timeout?.let(handler::removeCallbacks)
        timeout = Runnable { retry("connection timed out") }.also {
            handler.postDelayed(it, delayMs)
        }
    }

    @SuppressLint("MissingPermission")
    private fun retry(reason: String) {
        Log.w(TAG, "$reason; retaining generation=${prefs.getLong(KEY_GENERATION, 0L)}")
        retryCount += 1
        if (retryCount >= MAX_RETRIES) {
            if (isPersistentRotationRequest()) {
                retryCount = 0
                updateNotification("X3 is offline; rotation will resume automatically")
                closeGatt()
                scheduleScan(ROTATION_OFFLINE_RETRY_MS)
                return
            }
            failFinished(reason)
            return
        }
        updateNotification("Retrying when X3 checks in")
        closeGatt()
        scheduleScan(2_000L)
    }

    private fun isPersistentRotationRequest(): Boolean =
        prefs.getBoolean(KEY_PENDING, false) &&
            (prefs.getString(KEY_MODULE, "") == PlaylistModule.displayName ||
                prefs.getBoolean(KEY_V5_PLAYLIST_CHAIN, false)) &&
            SidecarPlaylist.isRotationActive(this)

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        timeout?.let(handler::removeCallbacks)
        timeout = null
        completionPoll?.let(handler::removeCallbacks)
        completionPoll = null
        scanTimeout?.let(handler::removeCallbacks)
        scanTimeout = null
        queue = null
        awaitingCompletion = false
        statusBaseline = null
        completionObserved = false
        pendingInfo = null
        inventoryAssembler = null
        if (!activeV5Canary) canaryPhase = CanaryPhase.NONE
        val old = gatt
        gatt = null
        runCatching { old?.disconnect() }
        runCatching { old?.close() }
    }

    private fun notification(text: String): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Eink Sidecar delivery", NotificationManager.IMPORTANCE_LOW)
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Eink Sidecar")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification(text))
    }

    private fun stopFinished() {
        updateNotification("Delivered")
        handler.postDelayed({
            if (prefs.getBoolean(KEY_PENDING, false)) {
                if (gatt == null && scanCallback == null) startScan()
            } else {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }, 1_500L)
    }

    private fun stopWithoutResult() {
        stopCurrentTransport()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun failFinished(reason: String) {
        prefs.edit().putBoolean(KEY_PENDING, false).apply()
        stopCurrentTransport()
        updateNotification("Not delivered: $reason")
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        reconnect?.let(handler::removeCallbacks)
        reconnect = null
        scanCallback?.let { callback ->
            runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
        }
        scanCallback = null
        scanTimeout?.let(handler::removeCallbacks)
        scanTimeout = null
        closeGatt()
        super.onDestroy()
    }
}
