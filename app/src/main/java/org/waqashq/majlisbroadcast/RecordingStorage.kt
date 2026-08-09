package org.waqashq.majlisbroadcast

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Shared logic for writing a recording file into the public Music/Malfoozat e Akhtar
 * folder (Phase 8c). Used both for new live recordings (BroadcastService) and for
 * migrating old recordings that were mistakenly written to app-private storage before
 * that fix (SettingsActivity's "Move Old Recordings" action) -- app-private storage
 * is hidden from the Files app and every file browser on Android 11+, which is why
 * finished recordings used to be "nowhere to be found".
 */
object RecordingStorage {

    const val SUBFOLDER = "Malfoozat e Akhtar"

    data class NewRecording(val out: OutputStream, val uri: Uri?)
    data class MigrationResult(val moved: Int, val failed: Int)

    /** Opens a new output stream for "$name.aac" in the public Music folder. Null on failure. */
    fun open(context: Context, name: String): NewRecording? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            openMediaStore(context, name)
        } else {
            openLegacy(context, name)
        }
    }

    /** Marks a MediaStore recording as no longer pending (visible/scannable). No-op for legacy files. */
    fun finalize(context: Context, uri: Uri?) {
        uri ?: return
        try {
            val values = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
            context.contentResolver.update(uri, values, null, null)
        } catch (t: Throwable) {
            DebugLog.log("Recording: MediaStore finalize failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun openMediaStore(context: Context, name: String): NewRecording? {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, "$name.aac")
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/aac")
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/" + SUBFOLDER)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                DebugLog.log("Recording: MediaStore insert returned null Uri")
                return null
            }
            val out = context.contentResolver.openOutputStream(uri)
            if (out == null) {
                DebugLog.log("Recording: MediaStore openOutputStream returned null")
                return null
            }
            NewRecording(out, uri)
        } catch (t: Throwable) {
            DebugLog.log("Recording: MediaStore insert failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    private fun openLegacy(context: Context, name: String): NewRecording? {
        val granted = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            DebugLog.log("Recording: WRITE_EXTERNAL_STORAGE not granted on this Android version")
            return null
        }
        return try {
            @Suppress("DEPRECATION")
            val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            val dir = File(musicDir, SUBFOLDER)
            dir.mkdirs()
            NewRecording(FileOutputStream(File(dir, "$name.aac")), null)
        } catch (t: Throwable) {
            DebugLog.log("Recording: legacy file open failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    /**
     * One-off migration (Phase 8c): copies any recordings left behind in the old
     * app-private location (getExternalFilesDir/recordings, from before this fix)
     * into the new public Music folder, then deletes the old copy once the copy is
     * confirmed complete. Cheap and safe to call on every app start -- it only
     * touches files that still exist in the old location, so once they're all
     * moved this becomes a no-op directory listing.
     */
    fun migrateOldRecordings(context: Context): MigrationResult {
        val oldDir = File(context.getExternalFilesDir(null), "recordings")
        val files = oldDir.listFiles { f -> f.isFile && f.name.endsWith(".aac") } ?: emptyArray()
        var moved = 0
        var failed = 0
        for (old in files) {
            val name = old.name.removeSuffix(".aac")
            val target = open(context, name)
            if (target == null) {
                failed++
                continue
            }
            var ok = false
            try {
                old.inputStream().use { input ->
                    target.out.use { output -> input.copyTo(output) }
                }
                ok = true
            } catch (t: Throwable) {
                DebugLog.log("Recording migration failed for ${old.name}: ${t.javaClass.simpleName}: ${t.message}")
            }
            if (ok) {
                finalize(context, target.uri)
                if (!old.delete()) {
                    DebugLog.log("Recording migration: copied ${old.name} but couldn't delete the old copy")
                }
                moved++
            } else {
                failed++
            }
        }
        if (moved > 0 || failed > 0) {
            DebugLog.log("Recording migration: moved=$moved failed=$failed")
        }
        return MigrationResult(moved, failed)
    }
}
