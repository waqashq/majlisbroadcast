package org.waqashq.majlisbroadcast

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 1 test harness ONLY. This is not the real app UI (see
 * majlisbroadcast.md section 8 / Phase 5) -- just enough to record a clip,
 * play it back, and confirm the voice sounds full, not thin, before we ever
 * touch networking.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var recordButton: Button
    private lateinit var playButton: Button
    private lateinit var shareButton: Button

    private var recorder: AacFileRecorder? = null
    private var isRecording = false
    private var lastFile: File? = null
    private var mediaPlayer: MediaPlayer? = null

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) statusText.text = getString(R.string.status_mic_denied)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }

        statusText = TextView(this).apply { text = getString(R.string.status_ready); textSize = 16f }
        recordButton = Button(this).apply { text = getString(R.string.btn_start_recording) }
        playButton = Button(this).apply { text = getString(R.string.btn_play); isEnabled = false }
        shareButton = Button(this).apply { text = getString(R.string.btn_share); isEnabled = false }

        recordButton.setOnClickListener { onRecordClicked() }
        playButton.setOnClickListener { onPlayClicked() }
        shareButton.setOnClickListener { onShareClicked() }

        listOf(statusText, recordButton, playButton, shareButton).forEach {
            root.addView(
                it,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 24 }
            )
        }

        setContentView(root)

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun onRecordClicked() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        if (!isRecording) {
            val dir = File(getExternalFilesDir(null), "recordings")
            dir.mkdirs()
            val name = "test_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".aac"
            val file = File(dir, name)

            recorder = AacFileRecorder(file).also { it.start() }
            lastFile = file
            isRecording = true
            recordButton.text = getString(R.string.btn_stop_recording)
            statusText.text = getString(R.string.status_recording)
            playButton.isEnabled = false
            shareButton.isEnabled = false
        } else {
            recorder?.stop()
            val error = recorder?.lastError
            isRecording = false
            recordButton.text = getString(R.string.btn_start_recording)
            if (error != null) {
                statusText.text = getString(R.string.status_failed, error)
            } else {
                statusText.text = getString(R.string.status_saved, lastFile?.name)
                playButton.isEnabled = true
                shareButton.isEnabled = true
            }
        }
    }

    private fun onPlayClicked() {
        val file = lastFile ?: return
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                start()
            }
            statusText.text = getString(R.string.status_playing, file.name)
        } catch (t: Throwable) {
            statusText.text = getString(R.string.status_playback_failed, t.message)
        }
    }

    private fun onShareClicked() {
        val file = lastFile ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/aac"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_chooser_title)))
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        recorder?.stop()
    }
}
