package com.gatorforge.sidecar.trackb

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

class ConnLogger(context: Context) {

    enum class Event {
        SCAN,
        CONNECTED,
        RECONNECTED,
        DISCONNECTED,
        GATT_ERROR,
        RECONNECT_ATTEMPT,
        HEARTBEAT
    }

    companion object {
        private const val TAG = "ConnLogger"
        private const val HEADER = "timestamp_iso,uptime_ms,event,detail,rssi,connected_since_ms"
    }

    val logFile: File
    private val fileLock = Any()

    init {
        val externalDir = context.getExternalFilesDir(null)
        if (externalDir != null) {
            logFile = File(externalDir, Config.LOG_FILE_NAME)
            Log.i(TAG, "Using external files dir for ${Config.LOG_FILE_NAME}: ${externalDir.absolutePath}")
        } else {
            Log.e(TAG, "getExternalFilesDir(null) returned null; falling back to internal filesDir")
            val internalDir = context.filesDir
            logFile = File(internalDir, Config.LOG_FILE_NAME)
            Log.i(TAG, "Using internal filesDir for ${Config.LOG_FILE_NAME}: ${internalDir.absolutePath}")
        }
    }

    // Thread safety: all file access is serialized through a single lock.
    fun log(event: Event, detail: String, rssi: Int? = null, connectedSinceMs: Long = 0L) {
        synchronized(fileLock) {
            // ISO-8601 timestamp with offset (via OffsetDateTime.now + ISO_OFFSET_DATE_TIME).
            // Locale-independent and includes the offset, allowing rows to be aligned to wall-clock events.
            val timestamp = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            val uptime = SystemClock.elapsedRealtime()
            val rssiField = rssi?.toString() ?: ""
            val escapedDetail = escapeCsv(detail)
            val line = listOf(
                timestamp,
                uptime.toString(),
                event.name,
                escapedDetail,
                rssiField,
                connectedSinceMs.toString()
            ).joinToString(",")

            runCatching {
                val needsHeader = !logFile.exists() || logFile.length() == 0L
                FileOutputStream(logFile, true).use { fos ->
                    val writer = BufferedWriter(OutputStreamWriter(fos, StandardCharsets.UTF_8))
                    writer.use { w ->
                        if (needsHeader) {
                            w.write(HEADER)
                            w.newLine()
                        }
                        w.write(line)
                        w.newLine()
                        w.flush()
                    }
                }
            }.onFailure { e ->
                Log.e(TAG, "Failed to write row to ${logFile.absolutePath}", e)
            }
        }
    }

    private fun escapeCsv(value: String): String {
        return if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
    }
}
