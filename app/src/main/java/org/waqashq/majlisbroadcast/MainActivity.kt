package org.waqashq.majlisbroadcast

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
 * Test harness ONLY -- not the real app UI (see majlisbroadcast.md section 8
 * / Phase 5). Two independent sections: the Phase 1 local-file test, and the
 * Phase 2 live-streaming test.
 */
class MainActivity : AppCompatActivity() {

    // --- Phase 1: local file test ---
    private lateinit var statusText: TextView
    private lateinit var recordButton: Button
    private lateinit var playButton: Button
    private lateinit var shareButton: Button

    private var recorder: AacFileRecorder? = null
    private var isRecording = false
    private var lastFile: File? = null
    private var mediaPlayer: MediaPlayer? = null

    // --- Phase 2: live stream test ---
    private lateinit var liveStatusText: TextView
    private lateinit var goLiveButton: Button
    private var streamer: AacNetworkStreamer? = null
    private var isLive = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private val livePoller = object : Runnable {
        override fun run() {
            pollLiveState()
            uiHandler.postDelayed(this, 500)
        }
    }

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

        // --- Phase 1 section ---
        val fileSectionLabel = TextView(this).apply { text = getString(R.string.section_file_test); textSize = 14f }
        statusText = TextView(this).apply { text = getString(R.string.status_ready); textSize = 16f }
        recordButton = Button(this).apply { text = getString(R.string.btn_start_recording) }
        playButton = Button(this).apply { text = getString(R.string.btn_play); isEnabled = false }
        shareButton = Button(this).apply { text = getString(R.string.btn_share); isEnabled = false }

        recordButton.setOnClickListener { onRecordClicked() }
        playButton.setOnClickListener { onPlayClicked() }
        shareButton.setOnClickListener { onShareClicked() }

        // --- Phase 2 section ---
        val liveSectionLabel = TextView(this).apply { text = getString(R.string.section_live_test); textSize = 14f }
        liveStatusText = TextView(this).apply { text = ""; textSize = 16f }
        goLiveButton = Button(this).apply { text = getString(R.string.btn_go_live) }
        goLiveButton.setOnClickListener { onGoLiveClicked() }

        listOf(
            fileSectionLabel, statusText, recordButton, playButton, shareButton,
            liveSectionLabel, liveStatusText, goLiveButton
        ).forEach {
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

    // ================= Phase 1: local file test =================

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

    // ================= Phase 2: live stream test =================

    private fun onGoLiveClicked() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        if (!isLive) {
            if (BuildConfig.AZURACAST_HOST.isBlank() || BuildConfig.AZURACAST_PORT == 0) {
                liveStatusText.text = getString(R.string.status_live_missing_secrets)
                return
            }
            val uploader = IcecastUploader(
                BuildConfig.AZURACAST_HOST,
                BuildConfig.AZURACAST_PORT,
                BuildConfig.AZURACAST_USERNAME,
                BuildConfig.AZURACAST_PASSWORD
            )
            streamer = AacNetworkStreamer(uploader).also { it.start() }
            isLive = true
            goLiveButton.text = getString(R.string.btn_stop_live)
            liveStatusText.text = getString(
                R.string.status_live_connecting, BuildConfig.AZURACAST_HOST, BuildConfig.AZURACAST_PORT
            )
            uiHandler.post(livePoller)
        } else {
            streamer?.stop()
            isLive = false
            goLiveButton.text = getString(R.string.btn_go_live)
            liveStatusText.text = getString(R.string.status_live_stopped)
            uiHandler.removeCallbacks(livePoller)
        }
    }

    private fun pollLiveState() {
        val s = streamer ?: return
        when (s.state) {
            AacNetworkStreamer.State.CONNECTING -> liveStatusText.text = getString(
                R.string.status_live_connecting, BuildConfig.AZURACAST_HOST, BuildConfig.AZURACAST_PORT
            )
            AacNetworkStreamer.State.LIVE -> liveStatusText.text = getString(R.string.status_live_on_air)
            AacNetworkStreamer.State.ERROR -> {
                liveStatusText.text = getString(R.string.status_live_error, s.lastError ?: "unknown")
                isLive = false
                goLiveButton.text = getString(R.string.btn_go_live)
                uiHandler.removeCallbacks(livePoller)
            }
            AacNetworkStreamer.State.STOPPED, AacNetworkStreamer.State.IDLE -> { /* no-op */ }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        recorder?.stop()
        streamer?.stop()
        uiHandler.removeCallbacks(livePoller)
    }
}
