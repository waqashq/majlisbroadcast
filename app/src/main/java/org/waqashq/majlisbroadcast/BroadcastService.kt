package org.waqashq.majlisbroadcast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Typed `microphone` foreground service: owns the BroadcastEngine, the
 * persistent notification (with Stop), and the wake/wifi locks. See
 * majlisbroadcast.md sections 5 and 7.
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

        @Volatile var state: BroadcastEngine.State = BroadcastEngine.State.IDLE
            private set
        @Volatile var lastError: String? = null
            private set
        @Volatile var dropCount: Long = 0
            private set
        @Volatile var reconnectCount: Int = 0
            private set

        fun start(context: Context) {
            val intent = Intent(context, BroadcastService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, BroadcastService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }

    private var engine: BroadcastEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopBroadcast()
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

            engine = BroadcastEngine(
                BuildConfig.AZURACAST_HOST,
                BuildConfig.AZURACAST_PORT,
                BuildConfig.AZURACAST_USERNAME,
                BuildConfig.AZURACAST_PASSWORD,
                this
            ).also { it.start() }
        }
        return START_NOT_STICKY
    }

    private fun stopBroadcast() {
        // Teardown order matters (section 5): detach the foreground
        // notification BEFORE releasing the mic. Releasing the mic while
        // still an active `microphone` FGS can trip the Android 14+
        // watchdog.
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        engine?.stop()
        engine = null
        releaseLocks()
        state = BroadcastEngine.State.STOPPED
        stopSelf()
    }

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        engine?.stop()
        engine = null
        releaseLocks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ================= BroadcastEngine.Listener =================

    override fun onStateChanged(newState: BroadcastEngine.State, error: String?) {
        state = newState
        lastError = error
        updateNotification(newState)
    }

    override fun onTelemetry(drops: Long, reconnects: Int) {
        dropCount = drops
        reconnectCount = reconnects
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

    private fun statusTextFor(state: BroadcastEngine.State): String = when (state) {
        BroadcastEngine.State.CONNECTING -> getString(
            R.string.status_live_connecting, BuildConfig.AZURACAST_HOST, BuildConfig.AZURACAST_PORT
        )
        BroadcastEngine.State.LIVE -> getString(R.string.status_live_on_air)
        BroadcastEngine.State.RECONNECTING -> getString(R.string.status_live_reconnecting)
        BroadcastEngine.State.ERROR -> getString(R.string.status_live_error, lastError ?: "unknown")
        BroadcastEngine.State.STOPPED, BroadcastEngine.State.IDLE -> getString(R.string.status_live_stopped)
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
