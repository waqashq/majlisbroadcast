package org.waqashq.majlisbroadcast

import android.content.ContentUris
import android.content.Intent
import android.graphics.Typeface
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 9: in-app browser for local recordings, so you never have to leave
 * the app or dig through the Music folder to find/play/share one. Queries
 * MediaStore directly for anything saved under RecordingStorage.SUBFOLDER
 * (Android 10+, matching where RecordingStorage actually writes) --
 * content:// Uris from MediaStore are already shareable to other apps
 * without needing a FileProvider.
 */
class RecordingsActivity : AppCompatActivity() {

    private data class Recording(
        val uri: Uri,
        val displayName: String,
        val dateAddedSec: Long,
        val durationMs: Long,
        val sizeBytes: Long
    )

    private var player: MediaPlayer? = null
    private var playingUri: Uri? = null
    private var playingButton: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        buildUi()
    }

    override fun onPause() {
        super.onPause()
        stopPlayback()
    }

    override fun onDestroy() {
        stopPlayback()
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiTheme.STUDIO_BG)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(32, 56, 32, 24)
        }
        val backArrow = TextView(this).apply {
            text = "‹"
            textSize = 28f
            setTextColor(UiTheme.STUDIO_TEXT_PRIMARY)
            setPadding(16, 8, 32, 8)
            isClickable = true
            setOnClickListener { finish() }
        }
        val headerTitle = TextView(this).apply {
            text = getString(R.string.recordings_title)
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(UiTheme.STUDIO_TEXT_PRIMARY)
        }
        header.addView(backArrow)
        header.addView(headerTitle)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 8, 40, 48)
        }

        val recordings = queryRecordings()
        if (recordings.isEmpty()) {
            val empty = card()
            empty.addView(cardBody().apply {
                text = getString(R.string.recordings_empty)
                gravity = Gravity.CENTER
            })
            content.addView(
                empty,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            )
        } else {
            val dateFormat = SimpleDateFormat("MMM d, yyyy -- h:mm a", Locale.getDefault())
            recordings.forEachIndexed { index, rec ->
                val row = card()
                row.addView(
                    TextView(this).apply {
                        text = rec.displayName
                        textSize = 14f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(UiTheme.STUDIO_TEXT_PRIMARY)
                    }
                )
                row.addView(
                    cardBody().apply {
                        text = getString(
                            R.string.recordings_row_detail,
                            dateFormat.format(Date(rec.dateAddedSec * 1000)),
                            formatDuration(rec.durationMs),
                            formatMegabytes(rec.sizeBytes)
                        )
                        textSize = 12f
                    },
                    topMarginParams(6)
                )

                val buttonRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                val playButton = pillButton(getString(R.string.btn_play))
                val shareButton = pillButton(getString(R.string.btn_share))
                playButton.setOnClickListener { togglePlay(rec, playButton) }
                shareButton.setOnClickListener { shareRecording(rec) }
                buttonRow.addView(
                    playButton,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 12 }
                )
                buttonRow.addView(shareButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(buttonRow, topMarginParams(20))

                content.addView(
                    row,
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = if (index == 0) 0 else 20
                    }
                )
            }
        }

        val scrollView = ScrollView(this).apply { addView(content) }

        root.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(scrollView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    private fun queryRecordings(): List<Recording> {
        val out = ArrayList<Recording>()
        // RecordingStorage only inserts via MediaStore on Android 10+ (Q) --
        // RELATIVE_PATH itself doesn't exist as a column before that.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return out
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE
        )
        val selection = "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("%${RecordingStorage.SUBFOLDER}%")
        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"
        try {
            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, selectionArgs, sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    out.add(
                        Recording(
                            uri = uri,
                            displayName = cursor.getString(nameCol) ?: "recording.aac",
                            dateAddedSec = cursor.getLong(dateCol),
                            durationMs = cursor.getLong(durCol),
                            sizeBytes = cursor.getLong(sizeCol)
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            DebugLog.log("Recordings list query failed: ${t.javaClass.simpleName}: ${t.message}")
        }
        return out
    }

    private fun togglePlay(rec: Recording, button: Button) {
        if (playingUri == rec.uri) {
            stopPlayback()
            return
        }
        stopPlayback()
        try {
            val mp = MediaPlayer()
            mp.setDataSource(this, rec.uri)
            mp.setOnCompletionListener { stopPlayback() }
            mp.prepare()
            mp.start()
            player = mp
            playingUri = rec.uri
            playingButton = button
            button.text = getString(R.string.btn_stop_playback)
        } catch (t: Throwable) {
            DebugLog.log("Recording playback failed: ${t.javaClass.simpleName}: ${t.message}")
            Toast.makeText(this, getString(R.string.recordings_play_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopPlayback() {
        try { player?.stop() } catch (_: Throwable) {}
        try { player?.release() } catch (_: Throwable) {}
        player = null
        playingButton?.text = getString(R.string.btn_play)
        playingButton = null
        playingUri = null
    }

    private fun shareRecording(rec: Recording) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/aac"
            putExtra(Intent.EXTRA_STREAM, rec.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.recordings_share_chooser_title)))
    }

    private fun formatDuration(totalMs: Long): String {
        val totalSeconds = totalMs / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }

    private fun formatMegabytes(bytes: Long): String {
        val mb = bytes / 1024.0 / 1024.0
        return String.format(Locale.US, "%.1f", mb)
    }

    // ================= Small studio-styled building blocks (mirrors SettingsActivity) =================

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = UiTheme.studioCard()
        setPadding(36, 28, 36, 28)
    }

    private fun cardBody(): TextView = TextView(this).apply {
        textSize = 13f
        setTextColor(UiTheme.STUDIO_TEXT_MUTED)
    }

    private fun pillButton(text: String): Button = Button(this).apply {
        this.text = text
        textSize = 13f
        isAllCaps = false
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(UiTheme.STUDIO_TEXT_PRIMARY)
        background = UiTheme.outlinePillBackground(UiTheme.STUDIO_BORDER_TEAL)
        setPadding(0, 18, 0, 18)
    }

    private fun topMarginParams(marginPx: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = marginPx
        }
}
