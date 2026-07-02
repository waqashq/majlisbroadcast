package org.waqashq.majlisbroadcast

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Rolling, in-memory, on-device-only debug log (section 9). Captures the
 * *why* behind the numeric telemetry: reconnects, encoder state changes,
 * queue overflows, focus/route changes, socket failures. No cloud logging
 * -- this never leaves the device unless the user explicitly exports it.
 *
 * A plain object (not tied to any Context) so any class -- BroadcastEngine,
 * BroadcastService, MainActivity -- can log to it directly.
 */
object DebugLog {
    private const val MAX_ENTRIES = 200
    private val entries = ArrayDeque<String>(MAX_ENTRIES)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun log(event: String) {
        val line = "${timeFormat.format(Date())}  $event"
        if (entries.size >= MAX_ENTRIES) {
            entries.pollFirst()
        }
        entries.addLast(line)
    }

    @Synchronized
    fun snapshot(): List<String> = entries.toList()

    @Synchronized
    fun clear() {
        entries.clear()
    }

    /** Writes the current log to a local file and returns it, for sharing via FileProvider. */
    fun export(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "logs")
        dir.mkdirs()
        val name = "majlis_log_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt"
        val file = File(dir, name)
        FileOutputStream(file).use { out ->
            snapshot().forEach { line -> out.write((line + "\n").toByteArray(Charsets.UTF_8)) }
        }
        return file
    }
}
