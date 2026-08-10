package org.waqashq.majlisbroadcast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Typed `microphone` foreground service: owns the BroadcastEngine, the
 * persistent notification (with Stop), the wake/wifi locks, and (Phase 4)
 * the OS-level interruption listeners -- ConnectivityManager for network
 * handover and AudioManager focus for phone calls. See majlisbroadcast.md
 * sections 5, 6, and 7.
 *
 * Deliberately NOT bound/IBinder-based -- MainActivity talks to it only via
 * the static start()/stop() helpers and polls the companion state, same
 * lightweight pattern used since Phase 2. Phase 5 can formalize this if the
 * real UI needs push updates instead of polling.
 */
class BroadcastService : Service(), BroadcastEngine.Listener {

    companion object {
        private const val CHANNEL_ID = "majlis_broadcast"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "org.waqashq.majlisbroadcast.action.STOP"
        private const val ACTION_START_RECORDING = "org.waqashq.majlisbroadcast.action.START_RECORDING"
        private const val ACTION_STOP_RECORDING = "org.waqashq.majlisbroadcast.action.STOP_RECORDING"
        private const val ACTION_MUTE_MIC = "org.waqashq.majlisbroadcast.action.MUTE_MIC"
        private const val ACTION_UNMUTE_MIC = "org.waqashq.majlisbroadcast.action.UNMUTE_MIC"
        private const val ACTION_SET_SELF_MONITOR = "org.waqashq.majlisbroadcast.action.SET_SELF_MONITOR"
        private const val ACTION_SET_BASS_LEVEL = "org.waqashq.majlisbroadcast.action.SET_BASS_LEVEL"
        private const val ACTION_SET_ECHO_LEVEL = "org.waqashq.majlisbroadcast.action.SET_ECHO_LEVEL"
        private const val EXTRA_ENABLED = "enabled"
        private const val EXTRA_LEVEL = "level"

        // Phase 7: how often to poll AzuraCast's now-playing API while
        // live. This is a cosmetic nicety, not part of the streaming
        // pipeline -- 30s is plenty fresh for a listener count display and
        // keeps it from being a meaningful battery/data cost.
        private const val LISTENER_POLL_INTERVAL_MS = 30_000L

        /** Phase 9: cadence for the lighter-weight bytes-uploaded mirror while live. */
        private const val STATS_TICK_MS = 2_000L

        @Volatile var state: BroadcastEngine.State = BroadcastEngine.State.IDLE
            private set
        @Volatile var lastError: String? = null
            private set
        @Volatile var dropCount: Long = 0
            private set
        @Volatile var reconnectCount: Int = 0
            private set
        @Volatile var scoRefusalCount: Int = 0
            private set
        @Volatile var focusLost: Boolean = false
            private set
        /** True while the user has manually muted the mic (Phase 7+, distinct from a call). */
        @Volatile var manuallyMuted: Boolean = false
            private set
        @Volatile var queueDepth: Int = 0
            private set
        @Volatile var burstDropEvents: Int = 0
            private set
        @Volatile var micLevel: Int = 0
            private set
        @Volatile var micClipping: Boolean = false
            private set
        /** SystemClock.elapsedRealtime() when Go Live was tapped, or 0 if never started this session. */
        @Volatile var sessionStartRealtime: Long = 0
            private set
        /** System.currentTimeMillis() companion to sessionStartRealtime, for a real date in the history log. */
        @Volatile private var sessionStartWallClock: Long = 0
        /** Current listener count from AzuraCast's now-playing API, or null if unknown/unavailable (Phase 7). */
        @Volatile var listenerCount: Int? = null
            private set
        /** Public listen-page URL from AzuraCast's now-playing API, or null if unknown/unavailable (Phase 7+). */
        @Volatile var publicPlayerUrl: String? = null
            private set
        /** Whether a local recording is currently being written (Phase 7+). */
        @Volatile var isRecording: Boolean = false
            private set
        /** File name of the most recent local recording (started or completed), or null if none this app run. */
        @Volatile var lastRecordingFileName: String? = null
            private set
        /**
         * Human-readable description of where the last recording was saved (e.g. "Music/Malfoozat e Akhtar"),
         * shown to the user so they know where to look for it -- Phase 8c fix for recordings being written to
         * app-private storage that's invisible in normal file browsing.
         */
        @Volatile var lastRecordingLocation: String? = null
            private set
        /** Highest listener count seen this session, for the session-history log (Phase 9). */
        @Volatile var peakListenerCount: Int = 0
            private set
        /** Cumulative bytes uploaded this session, mirrored from the engine every poll tick (Phase 9). */
        @Volatile var bytesUploadedTotal: Long = 0
            private set
        /** Whether self-monitor playback (hear your own mic) is currently on (Phase 9, off by default each session). */
        @Volatile var selfMonitorEnabled: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, BroadcastService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, BroadcastService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }

        /** No-op if not currently live -- recording only makes sense while the engine is capturing audio. */
        fun startRecording(context: Context) {
            val intent = Intent(context, BroadcastService::class.java).apply { action = ACTION_START_RECORDING }
            context.startService(intent)
        }

        fun stopRecording(context: Context) {
            val intent = Intent(context, BroadcastService::class.java).apply { action = ACTION_STOP_RECORDING }
            context.startService(intent)
        }

        fun setMicMuted(context: Context, muted: Boolean) {
            val intent = Intent(context, BroadcastService::class.java).apply {
                action = if (muted) ACTION_MUTE_MIC else ACTION_UNMUTE_MIC
            }
            context.startService(intent)
        }

        /** No-op if not currently live -- there's no engine to monitor when idle. */
        fun setSelfMonitor(context: Context, enabled: Boolean) {
            val intent = Intent(context, BroadcastService::class.java).apply {
                action = ACTION_SET_SELF_MONITOR
                putExtra(EXTRA_ENABLED, enabled)
            }
            context.startService(intent)
        }

        fun setBassLevel(context: Context, level: Int) {
            val intent = Intent(context, BroadcastService::class.java).apply {
                action = ACTION_SET_BASS_LEVEL
                putExtra(EXTRA_LEVEL, level)
            }
            context.startService(intent)
        }

        fun setEchoLevel(context: Context, level: Int) {
            val intent = Intent(context, BroadcastService::class.java).apply {
                action = ACTION_SET_ECHO_LEVEL
                putExtra(EXTRA_LEVEL, level)
            }
            context.startService(intent)
        }
    }

    // Phase 9: now also read from the listenerPoll background thread (for
    // the bytes-uploaded mirror), in addition to the main-thread-only
    // callers this already had -- @Volatile makes that cross-thread read
    // well-defined instead of relying on incidental JVM/ART behavior.
    @Volatile private var engine: BroadcastEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var listenerPollThread: Thread? = null
    @Volatile private var listenerPolling = false

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> engine?.setFocusLost(true)
            AudioManager.AUDIOFOCUS_GAIN -> engine?.setFocusLost(false)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopBroadcast()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_START_RECORDING) {
            beginLocalRecording()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP_RECORDING) {
            endLocalRecording()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_MUTE_MIC) {
            engine?.setManuallyMuted(true)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_UNMUTE_MIC) {
            engine?.setManuallyMuted(false)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_SET_SELF_MONITOR) {
            val enabled = intent.getBooleanExtra(EXTRA_ENABLED, false)
            engine?.setSelfMonitor(enabled)
            selfMonitorEnabled = enabled
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_SET_BASS_LEVEL) {
            val level = intent.getIntExtra(EXTRA_LEVEL, 0)
            engine?.setBassLevel(level)
            AppSettings.saveBassLevel(this, level)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_SET_ECHO_LEVEL) {
            val level = intent.getIntExtra(EXTRA_LEVEL, 0)
            engine?.setEchoLevel(level)
            AppSettings.saveEchoLevel(this, level)
            return START_NOT_STICKY
        }

        if (engine == null) {
            // Must be called within seconds of startForegroundService() --
            // do it first, before anything that could be slow.
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(BroadcastEngine.State.CONNECTING),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
            acquireLocks()
            sessionStartRealtime = SystemClock.elapsedRealtime()

            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            audioManager = am

            peakListenerCount = 0
            bytesUploadedTotal = 0
            selfMonitorEnabled = false
            sessionStartWallClock = System.currentTimeMillis()

            engine = BroadcastEngine(
                AppSettings.host(this),
                AppSettings.port(this),
                AppSettings.username(this),
                AppSettings.password(this),
                AppSettings.mount(this),
                AppSettings.sampleRate(this),
                AppSettings.bitRateBps(this),
                AppSettings.bassLevel(this),
                AppSettings.echoLevel(this),
                am,
                this
            ).also { it.start() }

            requestAudioFocus(am)
            registerNetworkCallback()
            startListenerPolling()
        }
        return START_NOT_STICKY
    }

    private fun stopBroadcast() {
        // Teardown order matters (section 5): detach the foreground
        // notification BEFORE releasing the mic. Releasing the mic while
        // still an active `microphone` FGS can trip the Android 14+
        // watchdog.
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        engine?.stop() // also flushes/closes any open local recording
        engine = null
        unregisterNetworkCallback()
        abandonAudioFocus()
        releaseLocks()
        stopListenerPolling()
        recordSessionHistoryIfMeaningful()
        isRecording = false
        manuallyMuted = false
        selfMonitorEnabled = false
        state = BroadcastEngine.State.STOPPED
        sessionStartRealtime = 0
        stopSelf()
    }

    /**
     * Phase 9: logs this session to SessionHistory once it's actually
     * ending, using the wall-clock start time paired with
     * sessionStartRealtime for an accurate elapsed duration. Skips
     * sessions under 3s -- almost certainly an accidental tap, not a real
     * majlis, and not worth cluttering the history screen with.
     */
    private fun recordSessionHistoryIfMeaningful() {
        if (sessionStartRealtime == 0L) return
        val durationMs = SystemClock.elapsedRealtime() - sessionStartRealtime
        if (durationMs < 3_000L) return
        SessionHistory.record(
            this,
            SessionHistory.Entry(
                startedAtMs = sessionStartWallClock,
                durationMs = durationMs,
                peakListeners = peakListenerCount,
                bytesUploaded = bytesUploadedTotal
            )
        )
    }

    /** MediaStore Uri of the recording currently in progress (Android 10+ only), for finalizing on stop. */
    private var recordingUri: Uri? = null

    /**
     * Starts a local recording of the current session. No-op if not live or already recording.
     *
     * Phase 8c fix: recordings used to be written to app-specific external storage
     * (getExternalFilesDir), which Android 11+ hides from the Files app and every other
     * file browser -- that's why finished recordings were "nowhere to be found". Recordings
     * are now saved to the public Music/Malfoozat e Akhtar folder via RecordingStorage
     * (MediaStore on Android 10+, a direct public-directory file on very old Android), so
     * they show up in Files, in Music apps, and are pickable from any file picker.
     */
    private fun beginLocalRecording() {
        val e = engine ?: return
        if (isRecording) return
        val name = "majlis_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val target = RecordingStorage.open(this, name)
        if (target != null && e.startLocalRecording(target.out)) {
            isRecording = true
            lastRecordingFileName = "$name.aac"
            lastRecordingLocation = "Music/${RecordingStorage.SUBFOLDER}"
            recordingUri = target.uri
        } else {
            recordingUri = null
            DebugLog.log("Local recording could not be started (storage unavailable)")
        }
    }

    private fun endLocalRecording() {
        if (!isRecording) return
        engine?.stopLocalRecording()
        isRecording = false
        RecordingStorage.finalize(this, recordingUri)
        recordingUri = null
    }

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        engine?.stop()
        engine = null
        unregisterNetworkCallback()
        abandonAudioFocus()
        releaseLocks()
        stopListenerPolling()
        recordSessionHistoryIfMeaningful()
        isRecording = false
        manuallyMuted = false
        selfMonitorEnabled = false
        sessionStartRealtime = 0
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ================= BroadcastEngine.Listener =================

    override fun onStateChanged(newState: BroadcastEngine.State, error: String?) {
        state = newState
        lastError = error
        if (newState == BroadcastEngine.State.STOPPED || newState == BroadcastEngine.State.IDLE) {
            // Don't resurrect the notification here. Teardown order (section
            // 5) requires stopForeground() to run BEFORE engine.stop()
            // releases the mic -- but engine.stop() itself sets the
            // engine's state to STOPPED as its last step, which reaches
            // this callback right after the notification was already
            // removed. Without this guard, that re-posts it via notify()
            // a moment after stopForeground() just took it away.
            return
        }
        updateNotification(newState)
    }

    override fun onTelemetry(
        drops: Long, reconnects: Int, scoRefusals: Int, muted: Boolean,
        depth: Int, bursts: Int, manualMute: Boolean
    ) {
        dropCount = drops
        reconnectCount = reconnects
        scoRefusalCount = scoRefusals
        queueDepth = depth
        burstDropEvents = bursts
        val changed = focusLost != muted || manuallyMuted != manualMute
        focusLost = muted
        manuallyMuted = manualMute
        if (changed) updateNotification(state)
    }

    override fun onLevelUpdate(level: Int, clipping: Boolean) {
        micLevel = level
        micClipping = clipping
    }

    // ================= Network handover (section 7) =================

    private fun registerNetworkCallback() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager = cm

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                engine?.notifyNetworkLost()
            }
            override fun onAvailable(network: Network) {
                engine?.notifyNetworkAvailable()
            }
        }
        networkCallback = callback
        try {
            cm.registerNetworkCallback(request, callback)
        } catch (_: Throwable) {
            // Best-effort -- the writer thread's own write-timeout still
            // catches a dead connection even if this registration fails.
        }
    }

    private fun unregisterNetworkCallback() {
        val cm = connectivityManager ?: return
        val callback = networkCallback ?: return
        try { cm.unregisterNetworkCallback(callback) } catch (_: Throwable) {}
        connectivityManager = null
        networkCallback = null
    }

    // ================= Audio focus / silence generator (section 6) =================

    private fun requestAudioFocus(am: AudioManager) {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(focusChangeListener)
            .build()
        focusRequest = request
        try { am.requestAudioFocus(request) } catch (_: Throwable) {}
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        val request = focusRequest ?: return
        try { am.abandonAudioFocusRequest(request) } catch (_: Throwable) {}
        focusRequest = null
        audioManager = null
    }

    // ================= Notification =================

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(state: BroadcastEngine.State): Notification {
        val stopIntent = Intent(this, BroadcastService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusTextFor(state))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(0, getString(R.string.btn_stop_live), stopPendingIntent)
            .build()
    }

    private fun updateNotification(state: BroadcastEngine.State) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun statusTextFor(state: BroadcastEngine.State): String {
        val base = when (state) {
            BroadcastEngine.State.CONNECTING -> getString(
                R.string.status_live_connecting, AppSettings.host(this), AppSettings.port(this)
            )
            BroadcastEngine.State.LIVE -> getString(R.string.status_live_on_air)
            BroadcastEngine.State.RECONNECTING -> getString(R.string.status_live_reconnecting)
            BroadcastEngine.State.ERROR -> getString(R.string.status_live_error, lastError ?: "unknown")
            BroadcastEngine.State.STOPPED, BroadcastEngine.State.IDLE -> getString(R.string.status_live_stopped)
        }
        return if (state != BroadcastEngine.State.LIVE) {
            base
        } else if (focusLost) {
            base + getString(R.string.status_muted_suffix)
        } else if (manuallyMuted) {
            base + getString(R.string.status_muted_suffix_manual)
        } else {
            base
        }
    }

    // ================= Listener count (Phase 7) =================

    /**
     * Polls AzuraCast's now-playing API every LISTENER_POLL_INTERVAL_MS
     * while live, and (Phase 9) also mirrors the engine's cumulative
     * bytes-uploaded counter and tracks the session's peak listener count
     * on a much shorter STATS_TICK_MS cadence so the Broadcast screen's
     * data-used readout stays reasonably live. Runs on its own plain
     * thread rather than the writer/capture threads -- this must never
     * share a thread with, block, or otherwise affect the actual streaming
     * pipeline (section 0: "touches nothing in the streaming pipeline").
     * Any fetch failure just leaves listenerCount as whatever it last was
     * (or null); never surfaced as an error to the broadcaster.
     */
    private fun startListenerPolling() {
        listenerPolling = true
        listenerPollThread = Thread({
            var msSincePoll = LISTENER_POLL_INTERVAL_MS // fetch immediately on the first tick
            while (listenerPolling) {
                if (state == BroadcastEngine.State.LIVE) {
                    bytesUploadedTotal = engine?.bytesUploaded() ?: bytesUploadedTotal
                    if (msSincePoll >= LISTENER_POLL_INTERVAL_MS) {
                        msSincePoll = 0
                        val info = ListenerCountFetcher.fetch(
                            AppSettings.apiBaseUrl(this@BroadcastService),
                            BuildConfig.AZURACAST_STATION_SHORTCODE.takeIf { it.isNotBlank() }
                        )
                        listenerCount = info?.listenerCount
                        if (info?.publicPlayerUrl != null) publicPlayerUrl = info.publicPlayerUrl
                        val count = info?.listenerCount
                        if (count != null && count > peakListenerCount) peakListenerCount = count
                    }
                }
                try {
                    Thread.sleep(STATS_TICK_MS)
                    msSincePoll += STATS_TICK_MS
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "BroadcastService-listenerPoll").apply { start() }
    }

    private fun stopListenerPolling() {
        listenerPolling = false
        listenerPollThread?.interrupt()
        listenerPollThread = null
        listenerCount = null
    }

    // ================= Wake / WiFi locks =================

    private fun acquireLocks() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MajlisBroadcast:capture").apply { acquire() }

        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MajlisBroadcast:wifi").apply { acquire() }
    }

    private fun releaseLocks() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Throwable) {}
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Throwable) {}
        wakeLock = null
        wifiLock = null
    }
}
