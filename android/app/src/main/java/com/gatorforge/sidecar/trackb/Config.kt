package com.gatorforge.sidecar.trackb

/*
 * DESIGN INTENT — Config
 * DONE WHEN: every tunable of the harness (peer identity, heartbeat cadence, reconnect backoff)
 *   is a named constant here and nowhere else; changing the target earbuds is a one-line edit.
 * INPUTS: none at runtime — compile-time constants only. PEER_NAME_SUBSTRING is matched
 *   case-insensitively against advertised device names; PEER_MAC (if non-blank) wins over the name.
 * FAILS WHEN: n/a (no logic). A wrong PEER value simply means the scanner never matches — visible
 *   as the log staying on SCAN with no CONNECTED, not a crash.
 * USER SEES: nothing directly; these values shape what the service logs.
 */
object Config {
    // Stand-in BLE peer. Set ONE: leave PEER_MAC blank to match by name, or fill it to pin a MAC.
    const val PEER_NAME_SUBSTRING = "Shokz"   // also try "OpenRun", "FiiO", "FW" per your buds
    const val PEER_MAC = ""                   // e.g. "AA:BB:CC:DD:EE:FF"; blank => match by name

    const val HEARTBEAT_INTERVAL_S = 30L
    const val RECONNECT_BACKOFF_MIN_S = 2L
    const val RECONNECT_BACKOFF_MAX_S = 60L

    const val SCAN_TIMEOUT_S = 30L            // give up a scan pass after this, then retry
    const val LOG_FILE_NAME = "conn_log.csv"
    const val NOTIF_CHANNEL_ID = "trackb_soak"
    const val NOTIF_ID = 4201
}
