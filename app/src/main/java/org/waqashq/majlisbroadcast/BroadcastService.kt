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

        // Phase 7: how often to poll AzuraCast's now-playing API while
        // live. This is a cosmetic nicety, not part of the streaming
        // pipeline -- 30s is plenty fresh for a listener count display and
        // keeps it from being a meaningful battery/data cost.
        private const val LISTENER_POLL_INTERVAL_MS = 30_000L

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
    }

    private var engine: BroadcastEngine? = null
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

            engine = BroadcastEngine(
                BuildConfig.AZURACAST_HOST,
                BuildConfig.AZURACAST_PORT,
                BuildConfig.AZURACAST_USERNAME,
                BuildConfig.AZURACAST_PASSWORD,
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
        isRecording = false
        state = BroadcastEngine.State.STOPPED
        sessionStartRealtime = 0
        stopSelf()
    }

    /** Starts a local recording of the current session. No-op if not live or already recording. */
    private fun beginLocalRecording() {
        val e = engine ?: return
        if (isRecording) return
        val dir = File(getExternalFilesDir(null), "recordings")
        val name = "majlis_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".aac"
        val file = File(dir, name)
        if (e.startLocalRecording(file)) {
            isRecording = true
            lastRecordingFileName = name
        }
    }

    private fun endLocalRecording() {
        if (!isRecording) return
        engine?.stopLocalRecording()
        isRecording = false
    }

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        engine?.stop()
        engine = null
        unregisterNetworkCallback()
        abandonAudioFocus()
        releaseLocks()
        stopListenerPolling()
        isRecording = false
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
        depth: Int, bursts: Int
    ) {
        dropCount = drops
        reconnectCount = reconnects
        scoRefusalCount = scoRefusals
        queueDepth = depth
        burstDropEvents = bursts
        val focusChanged = focusLost != muted
        focusLost = muted
        if (focusChanged) updateNotification(state)
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
                R.string.status_live_connecting, BuildConfig.AZURACAST_HOST, BuildConfig.AZURACAST_PORT
            )
            BroadcastEngine.State.LIVE -> getString(R.string.status_live_on_air)
            BroadcastEngine.State.RECONNECTING -> getString(R.string.status_live_reconnecting)
            BroadcastEngine.State.ERROR -> getString(R.string.status_live_error, lastError ?: "unknown")
            BroadcastEngine.State.STOPPED, BroadcastEngine.State.IDLE -> getString(R.string.status_live_stopped)
        }
        return if (focusLost && state == BroadcastEngine.State.LIVE) {
            base + getString(R.string.status_muted_suffix)
        } else {
            base
        }
    }

    // ================= Listener count (Phase 7) =================

    /**
     * Polls AzuraCast's now-playing API every LISTENER_POLL_INTERVAL_MS
     * while live. Runs on its own plain thread rather than the writer/
     * capture threads -- this must never share a thread with, block, or
     * otherwise affect the actual streaming pipeline (section 0: "touches
     * nothing in the streaming pipeline"). Any fetch failure just leaves
     * listenerCount as whatever it last was (or null); never surfaced as
     * an error to the broadcaster.
     */
    private fun startListenerPolling() {
        listenerPolling = true
        listenerPollThread = Thread({
            while (listenerPolling) {
                if (state == BroadcastEngine.State.LIVE) {
                    val info = ListenerCountFetcher.fetch(BuildConfig.AZURACAST_API_BASE_URL)
                    listenerCount = info?.listenerCount
                    if (info?.publicPlayerUrl != null) publicPlayerUrl = info.publicPlayerUrl
                }
                try {
                    Thread.sleep(LISTENER_POLL_INTERVAL_MS)
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
