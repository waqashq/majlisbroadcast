package org.waqashq.majlisbroadcast

import android.content.ContentUris
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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

    // Phase 11p: per-recording seek bar. The bar for the row being played is
    // driven by this ticker; dragging any bar seeks that recording (or, if
    // it isn't the one playing, decides where it will start from).
    private var playingSeekBar: SeekBar? = null
    private var playingPositionText: TextView? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    /** Where each recording should resume from, kept while this screen lives. */
    private val startPositions = HashMap<Uri, Int>()
    private val progressTicker = object : Runnable {
        override fun run() {
            val mp = player
            if (mp != null) {
                try {
                    val pos = mp.currentPosition
                    playingSeekBar?.progress = pos
                    playingPositionText?.text = positionLabel(pos.toLong(), mp.duration.toLong())
                    playingUri?.let { startPositions[it] = pos }
                } catch (_: Throwable) {
                    // MediaPlayer in a bad state -- the ticker just stops.
                }
                uiHandler.postDelayed(this, 250)
            }
        }
    }

    /**
     * Phase 11p: lets the list include recordings this app made before a
     * reinstall (MediaStore only hands back rows the CURRENT install owns).
     * Asked for once, on first open; declining just means the list shows the
     * recordings made since, and everything else here still works.
     */
    private val requestAudioRead = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { buildUi() }

    private var askedForAudioRead = false

    /**
     * Phase 11q: deleting a recording this install doesn't own (anything
     * made before a reinstall, or before app data was cleared) is refused by
     * MediaStore with a SecurityException -- which is exactly what "couldn't
     * delete this recording" was. Android's own answer is a system consent
     * dialog: this launches it and deletes on approval.
     */
    private var pendingDeleteName: String? = null
    private val deleteConsent = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val name = pendingDeleteName
        pendingDeleteName = null
        if (result.resultCode == RESULT_OK) {
            Toast.makeText(this, getString(R.string.recordings_deleted_toast), Toast.LENGTH_SHORT).show()
        } else if (name != null) {
            // Declined at the system dialog -- not an error worth alarming about.
            DebugLog.log("Recording delete declined at system prompt: $name")
        }
        buildUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
    }

    private fun audioReadPermission(): String? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> android.Manifest.permission.READ_MEDIA_AUDIO
        else -> android.Manifest.permission.READ_EXTERNAL_STORAGE
    }

    private fun maybeAskForAudioRead() {
        if (askedForAudioRead) return
        askedForAudioRead = true
        val permission = audioReadPermission() ?: return
        if (checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestAudioRead.launch(permission)
        }
    }

    override fun onResume() {
        super.onResume()
        // Rebuild (not just refresh) on every resume, not only on first
        // create -- launchMode="singleTask" means returning to this tab via
        // the bottom nav reuses this same instance rather than recreating
        // it, so this is the only reliable place to pick up recordings
        // added/removed while this screen was in the background.
        buildUi()
        maybeAskForAudioRead()
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

        val header = studioHeader(getString(R.string.recordings_title)) { finish() }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 8, 40, 48)
        }

        val recordings = queryRecordings()
        if (recordings.isEmpty()) {
            val empty = studioCard(28)
            empty.addView(studioCardBody().apply {
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
                val row = studioCard(28)
                row.addView(
                    TextView(this).apply {
                        text = rec.displayName
                        textSize = 14f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(UiTheme.STUDIO_TEXT_PRIMARY)
                    }
                )
                row.addView(
                    studioCardBody().apply {
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

                // Phase 11p: scrub bar + position readout for this recording.
                // Durations from MediaStore can be 0 on a file it hasn't
                // scanned properly; the real duration replaces it on play.
                val positionText = TextView(this).apply {
                    textSize = 11f
                    setTextColor(UiTheme.STUDIO_TEXT_MUTED)
                    text = positionLabel(startPositions[rec.uri]?.toLong() ?: 0L, rec.durationMs)
                }
                val seekBar = SeekBar(this).apply {
                    max = if (rec.durationMs > 0) rec.durationMs.toInt() else 1
                    progress = startPositions[rec.uri] ?: 0
                    progressTintList = ColorStateList.valueOf(UiTheme.STUDIO_BORDER_TEAL)
                    thumbTintList = ColorStateList.valueOf(UiTheme.STUDIO_BORDER_TEAL)
                }
                seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        startPositions[rec.uri] = progress
                        positionText.text = positionLabel(progress.toLong(), (bar?.max ?: 0).toLong())
                    }
                    override fun onStartTrackingTouch(bar: SeekBar?) {}
                    override fun onStopTrackingTouch(bar: SeekBar?) {
                        // Playing this one: jump there. Otherwise this is
                        // just where it will start when Play is tapped.
                        if (playingUri == rec.uri) {
                            try { player?.seekTo(bar?.progress ?: 0) } catch (_: Throwable) {}
                        }
                    }
                })
                row.addView(seekBar, topMarginParams(12))
                row.addView(positionText, topMarginParams(2))

                val buttonRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                val playButton = studioPillButton(getString(R.string.btn_play), 13f)
                val shareButton = studioPillButton(getString(R.string.btn_share), 13f)
                val deleteButton = studioPillButton(getString(R.string.btn_delete), 13f).apply {
                    setTextColor(UiTheme.STUDIO_STOP_RED)
                    background = UiTheme.outlinePillBackground(UiTheme.STUDIO_STOP_RED)
                }
                playButton.setOnClickListener { togglePlay(rec, playButton, seekBar, positionText) }
                shareButton.setOnClickListener { shareRecording(rec) }
                deleteButton.setOnClickListener { confirmDeleteRecording(rec) }
                buttonRow.addView(
                    playButton,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 12 }
                )
                buttonRow.addView(
                    shareButton,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 12 }
                )
                buttonRow.addView(deleteButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(buttonRow, topMarginParams(16))

                content.addView(
                    row,
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = if (index == 0) 0 else 20
                    }
                )
            }
        }

        val scrollView = ScrollView(this).apply { addView(content) }
        val nav = buildBottomNav(NavTab.RECORDINGS)

        root.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(scrollView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(nav, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

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

    private fun togglePlay(rec: Recording, button: Button, seekBar: SeekBar, positionText: TextView) {
        if (playingUri == rec.uri) {
            stopPlayback()
            return
        }
        stopPlayback()
        // Held outside the try so the catch block can release it on any
        // failure (setDataSource/prepare throwing on a corrupted or
        // unsupported file) -- without this, a failed MediaPlayer was never
        // released, leaking its native resources on every failed attempt.
        var mp: MediaPlayer? = null
        try {
            mp = MediaPlayer()
            mp.setDataSource(this, rec.uri)
            mp.setOnCompletionListener {
                // Finished: rewind, so Play starts from the top next time.
                startPositions.remove(rec.uri)
                seekBar.progress = 0
                positionText.text = positionLabel(0L, seekBar.max.toLong())
                stopPlayback()
            }
            mp.prepare()
            // MediaStore's duration can be missing/stale -- the decoder's own
            // is authoritative, so the bar is scaled to it.
            if (mp.duration > 0) seekBar.max = mp.duration
            val resumeFrom = (startPositions[rec.uri] ?: 0).coerceIn(0, seekBar.max)
            // Not from the very end: that would "play" a finished file.
            if (resumeFrom > 0 && resumeFrom < seekBar.max - 250) mp.seekTo(resumeFrom)
            mp.start()
            player = mp
            playingUri = rec.uri
            playingButton = button
            playingSeekBar = seekBar
            playingPositionText = positionText
            button.text = getString(R.string.btn_stop_playback)
            uiHandler.removeCallbacks(progressTicker)
            uiHandler.post(progressTicker)
        } catch (t: Throwable) {
            DebugLog.log("Recording playback failed: ${t.javaClass.simpleName}: ${t.message}")
            Toast.makeText(this, getString(R.string.recordings_play_failed), Toast.LENGTH_SHORT).show()
            try { mp?.release() } catch (_: Throwable) {}
        }
    }

    private fun stopPlayback() {
        uiHandler.removeCallbacks(progressTicker)
        // Remember where it got to, so Play resumes from there.
        try {
            val mp = player
            val uri = playingUri
            if (mp != null && uri != null) startPositions[uri] = mp.currentPosition
        } catch (_: Throwable) {}
        try { player?.stop() } catch (_: Throwable) {}
        try { player?.release() } catch (_: Throwable) {}
        player = null
        playingButton?.text = getString(R.string.btn_play)
        playingButton = null
        playingUri = null
        playingSeekBar = null
        playingPositionText = null
    }

    /** "1:05 / 8:30" for the row under the scrub bar. */
    private fun positionLabel(positionMs: Long, durationMs: Long): String =
        getString(R.string.recordings_position_format, formatDuration(positionMs), formatDuration(durationMs))

    private fun shareRecording(rec: Recording) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/aac"
            putExtra(Intent.EXTRA_STREAM, rec.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.recordings_share_chooser_title)))
    }

    private fun confirmDeleteRecording(rec: Recording) {
        androidx.appcompat.app.AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog)
            .setTitle(getString(R.string.recordings_delete_confirm_title))
            .setMessage(getString(R.string.recordings_delete_confirm_message, rec.displayName))
            .setPositiveButton(getString(R.string.btn_delete)) { _, _ -> deleteRecording(rec) }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun deleteRecording(rec: Recording) {
        if (playingUri == rec.uri) stopPlayback()
        try {
            val rows = contentResolver.delete(rec.uri, null, null)
            if (rows > 0) {
                Toast.makeText(this, getString(R.string.recordings_deleted_toast), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.recordings_delete_failed), Toast.LENGTH_SHORT).show()
            }
        } catch (t: Throwable) {
            // Not ours to delete outright (see deleteConsent): ask the system
            // to confirm with the user, which is the only way Android allows
            // it. Everything else still fails safely with the old message.
            DebugLog.log("Recording delete needs consent: ${t.javaClass.simpleName}: ${t.message}")
            if (t is SecurityException && requestDeleteConsent(rec, t)) return
            Toast.makeText(this, getString(R.string.recordings_delete_failed), Toast.LENGTH_SHORT).show()
        }
        buildUi()
    }

    /**
     * True if a system delete-confirmation was launched for [rec]. Android 11+
     * has a purpose-built request for this; Android 10 instead attaches the
     * dialog to the exception it threw ([cause]).
     */
    private fun requestDeleteConsent(rec: Recording, cause: SecurityException): Boolean {
        val sender = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                MediaStore.createDeleteRequest(contentResolver, listOf(rec.uri)).intentSender
            // API 29 only: RecoverableSecurityException doesn't exist below it,
            // and below 29 RecordingStorage writes plain files anyway, which
            // delete without any of this.
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                cause is android.app.RecoverableSecurityException ->
                cause.userAction.actionIntent.intentSender
            else -> null
        } ?: return false
        return try {
            pendingDeleteName = rec.displayName
            deleteConsent.launch(androidx.activity.result.IntentSenderRequest.Builder(sender).build())
            true
        } catch (t: Throwable) {
            DebugLog.log("Recording delete consent could not be shown: ${t.javaClass.simpleName}: ${t.message}")
            pendingDeleteName = null
            false
        }
    }

    // header/card/cardBody/pillButton/topMarginParams/formatDuration/
    // formatMegabytes moved to StudioUiKit.kt (shared with SettingsActivity/
    // HistoryActivity, which had their own near-identical copies) --
    // refactor only, no behavior change.
}
