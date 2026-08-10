package org.waqashq.majlisbroadcast

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Phase 3 pipeline: capture + AAC-LC + ADTS on a dedicated capture/encode
 * thread, decoupled via a bounded drop-oldest FrameQueue from a separate
 * writer thread that owns the socket, coalesces frames, and handles
 * reconnect + backoff on its own. See majlisbroadcast.md sections 3 and 5.
 *
 * The capture thread NEVER touches the network. A stalled or reconnecting
 * socket cannot block it -- that decoupling is the whole point of the
 * queue.
 */
class BroadcastEngine(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
    private val mount: String,
    private val sampleRate: Int,
    private val bitRateBps: Int,
    initialBassLevel: Int,
    initialEchoLevel: Int,
    private val audioManager: AudioManager,
    private val listener: Listener
) {
    enum class State { IDLE, CONNECTING, LIVE, RECONNECTING, STOPPED, ERROR }

    interface Listener {
        fun onStateChanged(state: State, error: String?)
        fun onTelemetry(
            dropCount: Long, reconnectCount: Int, scoRefusalCount: Int, focusLost: Boolean,
            queueDepth: Int, burstDropEvents: Int, manuallyMuted: Boolean
        )
        /** level: 0-100 peak scale. Throttled to ~150ms; safe to call often. */
        fun onLevelUpdate(level: Int, clipping: Boolean)
    }

    companion object {
        private const val CHANNEL_COUNT = 1
        private const val MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC
        private val SAMPLE_RATE_TABLE = intArrayOf(
            96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
            16000, 12000, 11025, 8000, 7350
        )

        // ~150 frames is enough of a real-time cushion to absorb a brief
        // stall without unbounded memory growth, at any configured sample
        // rate (frame duration -- and so the cushion's real-time length --
        // scales with sample rate; see msPerFrame below).
        private const val QUEUE_CAPACITY_FRAMES = 150

        // Coalesce frames into one socket write, capped per section 5's
        // 100-250ms rule.
        private const val COALESCE_TARGET_MS = 200
        private const val COALESCE_MAX_WAIT_MS = 250L

        // Liquidsoap holds a dropped mount slot ~10-30s on a clean
        // disconnect, but Phase 6 long-run testing showed an *unclean*
        // network-loss drop (no FIN/RST reaches the server -- it only
        // notices via its own read timeout) can hold the slot for closer
        // to 45-50s. Back off across that wider window instead of
        // spamming reconnects and racing a still-held mount (section 5).
        private const val BACKOFF_MIN_MS = 10_000L
        private const val BACKOFF_MAX_MS = 45_000L

        // Minimum grace period before honoring an early wake-up from
        // notifyNetworkAvailable(). Without this floor, a fast Wi-Fi
        // handover fires the "network back" signal almost instantly,
        // triggering an immediate reconnect attempt before the server has
        // had any chance to notice the old TCP connection died -- which
        // just gets rejected with "403 Mountpoint already taken",
        // wasting an attempt and extending the real outage. This keeps
        // the fast-path's benefit for genuinely brief blips while
        // preventing the too-eager retry seen in testing.
        private const val RECONNECT_GRACE_MS = 5_000L

        // UNPROCESSED deliberately has no AGC (that's what keeps voice full
        // instead of thin -- see section 3), but that also means no
        // automatic loudness boost the way MIC-source apps get. This
        // manual gain compensates. Started at 4x (~12dB) based on
        // on-device comparison against AGC'd apps; dialed back to 3x
        // (~9.5dB) after Phase 6 testing linked a persistent post-reconnect
        // whistle to how hard/often loud peaks were hitting the hard clamp
        // below -- less gain means fewer, smaller clamp events. Still
        // clamped to avoid unbounded digital clipping on loud peaks.
        private const val GAIN_FACTOR = 3.0f

        // Phase 9: fixed slap-delay time for the optional Echo effect --
        // short enough to read as an intentional room echo rather than a
        // confusing double-voice, at any echo intensity the knob allows.
        private const val ECHO_DELAY_MS = 180

        // Echo feedback is capped well under 1.0 so repeats always decay
        // out rather than building up into runaway resonance/clipping,
        // even with the knob all the way up.
        private const val ECHO_MAX_FEEDBACK = 0.45
    }

    @Volatile private var running = false
    private var captureThread: Thread? = null
    private var writerThread: Thread? = null
    private val queue = FrameQueue(QUEUE_CAPACITY_FRAMES)

    @Volatile private var reconnectCount = 0
    @Volatile private var scoRefusalCount = 0
    @Volatile private var focusLost = false
    // Phase 7+: user-initiated mute, distinct from focusLost (a phone call).
    // Both use the exact same silence-substitution mechanism below --
    // this is deliberately a separate flag so the UI/notification can tell
    // "you muted yourself" apart from "a call is in progress".
    @Volatile private var manuallyMuted = false
    @Volatile private var networkAvailableSignal = false
    @Volatile private var lastError: String? = null
    @Volatile private var currentUploader: IcecastUploader? = null

    @Volatile private var state: State = State.IDLE
        set(value) {
            field = value
            listener.onStateChanged(value, lastError)
        }

    @Volatile private var adtsProfile = 1
    @Volatile private var adtsSampleRateIndex =
        SAMPLE_RATE_TABLE.indexOf(sampleRate).let { if (it < 0) 4 else it }

    // AAC-LC always encodes 1024 samples/frame regardless of sample rate,
    // so the real-time duration of one frame scales with sampleRate --
    // this replaces what used to be a constant tuned only for 44.1kHz.
    private val msPerFrame: Int = (1024_000L / sampleRate).toInt().coerceAtLeast(1)
    @Volatile private var adtsChannelConfig = CHANNEL_COUNT

    // Phase 7+: optional local recording, entirely independent of the
    // network side. Forks the same already-encoded ADTS frames that go to
    // the queue (see drainEncoder below) rather than running a second
    // AudioRecord/MediaCodec session -- concurrent AudioRecord capture is
    // unreliable across OEMs (this project already hit real device-specific
    // socket/capture quirks on the Honor test device), so reusing the one
    // proven capture pipeline is much safer than a second one.
    @Volatile private var localRecordingOut: OutputStream? = null

    // Phase 9: cumulative bytes handed to the socket this session, for a
    // rough "data used" readout on the Broadcast screen. Read-only from
    // outside via bytesUploaded().
    @Volatile private var bytesUploadedTotal: Long = 0L

    // ---- Bass boost (Phase 9): RBJ-cookbook low-shelf biquad on the raw
    // mic samples, applied before the existing fixed gain/clamp so the
    // clamp still protects the final output regardless of how much extra
    // level the shelf adds. Coefficients only recomputed when the level
    // knob actually changes (setBassLevel), not per-sample. level 0 keeps
    // the filter an exact identity pass-through (bit-identical to Phase 8
    // behavior) so this is fully opt-in.
    @Volatile private var bassLevel = initialBassLevel.coerceIn(0, 100)
    @Volatile private var bassB0 = 1.0
    @Volatile private var bassB1 = 0.0
    @Volatile private var bassB2 = 0.0
    @Volatile private var bassA1 = 0.0
    @Volatile private var bassA2 = 0.0
    // Filter history (x[n-1], x[n-2], y[n-1], y[n-2]) -- touched only by
    // the capture thread, so these don't need @Volatile.
    private var bassX1 = 0.0
    private var bassX2 = 0.0
    private var bassY1 = 0.0
    private var bassY2 = 0.0

    // ---- Echo (Phase 9): single delay line with decaying feedback, fixed
    // ECHO_DELAY_MS delay time, knob controls both wet mix and how many
    // repeats you hear before it decays out. Buffer size depends only on
    // sampleRate (fixed for this engine's lifetime), so it's allocated
    // once here and never touched by any thread but the capture thread --
    // only echoFeedback (the actual per-sample multiplier) is shared
    // across threads, and that's a single @Volatile Double.
    @Volatile private var echoLevel = initialEchoLevel.coerceIn(0, 100)
    @Volatile private var echoFeedback = (initialEchoLevel.coerceIn(0, 100) / 100.0) * ECHO_MAX_FEEDBACK
    private val echoBuf = ShortArray((ECHO_DELAY_MS.toLong() * sampleRate / 1000).toInt().coerceAtLeast(1))
    private var echoWriteIndex = 0

    // ---- Self-monitor (Phase 9): optional live playback of the same
    // post-effects audio being broadcast, so the broadcaster can hear what
    // listeners hear. Off by default; routed via USAGE_VOICE_COMMUNICATION
    // so it plays through the earpiece (quiet, directional) rather than
    // the loud bottom speaker when no headphones are connected -- reduces
    // (but doesn't eliminate) feedback-howl risk without headphones. The
    // AudioTrack itself is created/destroyed only by the capture thread in
    // reaction to this flag, so it's never touched from two threads at once.
    @Volatile private var selfMonitorEnabled = false
    private var monitorTrack: AudioTrack? = null

    init {
        recomputeBassCoefficients()
    }

    /** Cumulative bytes written to the socket so far this session (Phase 9 data-usage readout). */
    fun bytesUploaded(): Long = bytesUploadedTotal

    /** 0 = off (identity filter, zero CPU/behavior change). Up to +12dB low-shelf boost at 100. */
    fun setBassLevel(level: Int) {
        bassLevel = level.coerceIn(0, 100)
        recomputeBassCoefficients()
    }

    /** 0 = off (no delay line applied at all). Controls both echo loudness and how long it decays. */
    fun setEchoLevel(level: Int) {
        echoLevel = level.coerceIn(0, 100)
        echoFeedback = (echoLevel / 100.0) * ECHO_MAX_FEEDBACK
    }

    /** Toggles local playback of your own mic (via the earpiece unless headphones are connected). */
    fun setSelfMonitor(enabled: Boolean) {
        selfMonitorEnabled = enabled
        DebugLog.log(if (enabled) "Self-monitor enabled" else "Self-monitor disabled")
    }

    private fun recomputeBassCoefficients() {
        if (bassLevel <= 0) {
            bassB0 = 1.0; bassB1 = 0.0; bassB2 = 0.0; bassA1 = 0.0; bassA2 = 0.0
            return
        }
        val dB = bassLevel / 100.0 * 12.0
        val f0 = 150.0
        val fs = sampleRate.toDouble()
        val a = Math.pow(10.0, dB / 40.0)
        val w0 = 2.0 * Math.PI * f0 / fs
        val cosW0 = Math.cos(w0)
        val sinW0 = Math.sin(w0)
        val shelfSlope = 1.0
        val alpha = sinW0 / 2.0 * Math.sqrt((a + 1.0 / a) * (1.0 / shelfSlope - 1.0) + 2.0)
        val sqrtA = Math.sqrt(a)

        val b0 = a * ((a + 1) - (a - 1) * cosW0 + 2 * sqrtA * alpha)
        val b1 = 2 * a * ((a - 1) - (a + 1) * cosW0)
        val b2 = a * ((a + 1) - (a - 1) * cosW0 - 2 * sqrtA * alpha)
        val a0 = (a + 1) + (a - 1) * cosW0 + 2 * sqrtA * alpha
        val a1 = -2.0 * ((a - 1) + (a + 1) * cosW0)
        val a2 = (a + 1) + (a - 1) * cosW0 - 2 * sqrtA * alpha

        bassB0 = b0 / a0; bassB1 = b1 / a0; bassB2 = b2 / a0
        bassA1 = a1 / a0; bassA2 = a2 / a0
    }

    private fun bassFilter(x: Double): Double {
        val y = bassB0 * x + bassB1 * bassX1 + bassB2 * bassX2 - bassA1 * bassY1 - bassA2 * bassY2
        bassX2 = bassX1; bassX1 = x
        bassY2 = bassY1; bassY1 = y
        return y
    }

    private fun echoEffect(x: Double): Double {
        val delayed = echoBuf[echoWriteIndex].toDouble()
        val mixed = x + delayed * echoFeedback
        val clamped = mixed.coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble())
        echoBuf[echoWriteIndex] = clamped.toInt().toShort()
        echoWriteIndex = (echoWriteIndex + 1) % echoBuf.size
        return mixed
    }

    private fun createMonitorTrack(): AudioTrack? {
        return try {
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) return null
            val attrs = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()
            val track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track.play()
            DebugLog.log("Self-monitor AudioTrack started")
            track
        } catch (t: Throwable) {
            DebugLog.log("Self-monitor failed to start: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    private fun releaseMonitorTrack() {
        val track = monitorTrack ?: return
        monitorTrack = null
        try { track.stop() } catch (_: Throwable) {}
        try { track.release() } catch (_: Throwable) {}
    }

    fun start() {
        if (running) return
        running = true
        queue.clear()
        bytesUploadedTotal = 0L
        DebugLog.log("Engine starting")
        // Confirms exactly which Settings values this session is actually
        // using (host/port hidden from the log deliberately -- username/
        // password already aren't logged anywhere; this is diagnostic
        // only, in response to a report that a changed bit rate didn't
        // seem to take effect). If the app logs one value here but the
        // AzuraCast dashboard/listener reports a different bitrate, the
        // mismatch is on the server side (e.g. a mount/station audio
        // processing setting that re-encodes the incoming stream),
        // not this app.
        DebugLog.log("Session config: mount='${mount.ifBlank { "/" }}', sampleRate=${sampleRate}Hz, bitRate=${bitRateBps / 1000}kbps")
        state = State.CONNECTING

        captureThread = Thread({ runCapture() }, "BroadcastEngine-capture").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        writerThread = Thread({ runWriter() }, "BroadcastEngine-writer").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /**
     * Stops both threads. Force-closes any in-flight socket immediately
     * (rather than waiting out its write-timeout) so shutdown is prompt --
     * important since the service's onDestroy calls this synchronously.
     */
    fun stop() {
        running = false
        currentUploader?.close()
        captureThread?.join(3000)
        writerThread?.join(3000)
        captureThread = null
        writerThread = null
        // Safety net: the capture thread's own finally-style teardown
        // doesn't touch this (it's not the one that opened it in the
        // spirit of ownership), so make sure a still-open recording file
        // gets flushed and closed here rather than leaking a handle or
        // losing buffered-but-unflushed tail audio.
        closeLocalRecording()
        state = State.STOPPED
        DebugLog.log("Engine stopped")
    }

    /**
     * Starts (or restarts) writing a local copy of the same encoded ADTS
     * stream that's being broadcast, independent of network/LIVE state --
     * recording keeps working through a reconnect gap. The caller (Service)
     * owns where the stream points -- a publicly-visible MediaStore entry on
     * modern Android, a legacy public file on very old Android. Returns
     * false if recording couldn't be started.
     */
    fun startLocalRecording(out: OutputStream): Boolean {
        return try {
            localRecordingOut = out
            DebugLog.log("Local recording started")
            true
        } catch (t: Throwable) {
            DebugLog.log("Local recording failed to start: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    fun stopLocalRecording() {
        if (localRecordingOut != null) {
            DebugLog.log("Local recording stopped")
        }
        closeLocalRecording()
    }

    private fun closeLocalRecording() {
        val out = localRecordingOut ?: return
        localRecordingOut = null
        try { out.flush() } catch (_: Throwable) {}
        try { out.close() } catch (_: Throwable) {}
    }

    /**
     * Called by the Service's audio-focus listener. Section 6: on focus
     * loss (e.g. a phone call), don't disconnect -- the capture thread
     * substitutes perfectly-timed silence so the harbor slot stays alive.
     */
    fun setFocusLost(lost: Boolean) {
        if (focusLost != lost) {
            DebugLog.log(if (lost) "Audio focus lost -- muting (call in progress)" else "Audio focus regained -- resuming mic")
        }
        focusLost = lost
        reportTelemetry()
    }

    /** Called by the mic-mute toggle on the Broadcast screen (Phase 7+). */
    fun setManuallyMuted(muted: Boolean) {
        if (manuallyMuted != muted) {
            DebugLog.log(if (muted) "Mic manually muted" else "Mic manually unmuted")
        }
        manuallyMuted = muted
        reportTelemetry()
    }

    /**
     * Called by the Service's ConnectivityManager callback when the active
     * network is lost. Force-closes the socket immediately so the writer
     * thread notices and starts reconnecting right away, instead of only
     * finding out up to ~8s later when an in-flight write times out.
     */
    fun notifyNetworkLost() {
        DebugLog.log("Network lost -- forcing reconnect")
        currentUploader?.close()
    }

    /**
     * Called when a new network becomes available. If the writer thread is
     * mid-backoff, this wakes it early to retry sooner rather than waiting
     * out the rest of an already-scheduled delay.
     */
    fun notifyNetworkAvailable() {
        networkAvailableSignal = true
    }

    private fun reportTelemetry() {
        listener.onTelemetry(
            queue.totalDropped, reconnectCount, scoRefusalCount, focusLost,
            queue.size(), queue.burstDropEvents, manuallyMuted
        )
    }

    private var lastLevelReportMs = 0L
    private fun reportLevel(level: Int, clipped: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLevelReportMs < 150) return
        lastLevelReportMs = now
        listener.onLevelUpdate(level, clipped)
    }

    // ================= Capture + encode thread =================

    private fun runCapture() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

        var audioRecord: AudioRecord? = null
        var codec: MediaCodec? = null
        var sampleCount = 0L

        try {
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) throw IllegalStateException("getMinBufferSize failed ($minBuf)")
            val bufferSize = minBuf * 3

            audioRecord = createAudioRecord(bufferSize)
                ?: throw IllegalStateException("AudioRecord failed to initialize")

            val format = MediaFormat.createAudioFormat(MIME_TYPE, sampleRate, CHANNEL_COUNT).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRateBps)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize)
            }
            codec = MediaCodec.createEncoderByType(MIME_TYPE).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            audioRecord.startRecording()
            checkRoutedDevice(audioRecord)

            val pcmBuf = ByteArray(bufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            while (running) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val read: Int
                    if (focusLost || manuallyMuted) {
                        // Phone call / focus loss (section 6) or a manual
                        // mute (Phase 7+): don't touch a mic telephony may
                        // have claimed exclusively, or that the user has
                        // deliberately silenced. Synthesize perfectly-timed
                        // silence instead, paced to real time so the sample
                        // clock stays continuous and the harbor slot stays
                        // alive without a reconnect.
                        java.util.Arrays.fill(pcmBuf, 0.toByte())
                        read = pcmBuf.size
                        val pacingMs = (read / 2).toLong() * 1000L / sampleRate
                        Thread.sleep(pacingMs)
                        reportLevel(0, false)
                    } else {
                        read = audioRecord.read(pcmBuf, 0, pcmBuf.size, AudioRecord.READ_BLOCKING)
                        if (read > 0) {
                            val (level, clipped) = applyEffectsAndMeasure(pcmBuf, read)
                            reportLevel(level, clipped)
                            if (selfMonitorEnabled) {
                                if (monitorTrack == null) monitorTrack = createMonitorTrack()
                                // WRITE_NON_BLOCKING is essential here: this
                                // write sits in the capture thread's hot
                                // loop, and a stalled/blocked monitor
                                // AudioTrack must never be able to stall
                                // capture (and so the actual broadcast) --
                                // worst case a non-blocking write just drops
                                // some monitor audio, which is a self-
                                // monitoring nicety, not the broadcast
                                // itself.
                                try {
                                    monitorTrack?.write(pcmBuf, 0, read, AudioTrack.WRITE_NON_BLOCKING)
                                } catch (_: Throwable) {}
                            } else if (monitorTrack != null) {
                                releaseMonitorTrack()
                            }
                        }
                    }
                    val inputBuffer = codec.getInputBuffer(inIndex)
                    if (inputBuffer != null && read > 0) {
                        inputBuffer.clear()
                        inputBuffer.put(pcmBuf, 0, read)
                        val ptsUs = sampleCount * 1_000_000L / sampleRate
                        codec.queueInputBuffer(inIndex, 0, read, ptsUs, 0)
                        sampleCount += read / 2 // 16-bit mono: 2 bytes per sample
                    } else if (inputBuffer != null) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, 0)
                    }
                }
                drainEncoder(codec, bufferInfo, waitForEos = false)
            }

            var eosSent = false
            while (!eosSent) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val ptsUs = sampleCount * 1_000_000L / sampleRate
                    codec.queueInputBuffer(inIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    eosSent = true
                }
            }
            drainEncoder(codec, bufferInfo, waitForEos = true)
        } catch (t: Throwable) {
            // A capture/encoder failure is fatal for this session -- surface
            // it and let the writer thread wind down too. Per section 5,
            // recovery from a dead service/engine is user-initiated, not
            // automatic.
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            DebugLog.log("FATAL capture error: $lastError")
            running = false
            state = State.ERROR
        } finally {
            try { audioRecord?.stop() } catch (_: Throwable) {}
            audioRecord?.release()
            try { codec?.stop() } catch (_: Throwable) {}
            codec?.release()
            releaseMonitorTrack()
        }
    }

    /**
     * Runs each raw 16-bit little-endian PCM sample through the optional
     * Bass Boost / Echo effects (Phase 9, each a no-op when its level is 0),
     * then the existing fixed GAIN_FACTOR boost + hard clamp (unchanged
     * from Phase 3-8 -- this still protects the final broadcast output from
     * digital clipping no matter how much the effects add), and in the same
     * pass measures the buffer's peak level (0-100) and whether any sample
     * actually hit the clamp -- feeds the UI's mic level/clipping meter
     * (section 8) without a second pass over the buffer. With both effects
     * at level 0 this produces bit-identical output to the pre-Phase-9
     * behavior.
     */
    private fun applyEffectsAndMeasure(buf: ByteArray, byteCount: Int): Pair<Int, Boolean> {
        var peak = 0
        var clipped = false
        var i = 0
        while (i + 1 < byteCount) {
            var sample = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toDouble()
            if (bassLevel > 0) sample = bassFilter(sample)
            if (echoLevel > 0) sample = echoEffect(sample)

            val rawBoosted = (sample * GAIN_FACTOR).toInt()
            if (rawBoosted > Short.MAX_VALUE || rawBoosted < Short.MIN_VALUE) clipped = true
            val boosted = rawBoosted.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            buf[i] = (boosted and 0xFF).toByte()
            buf[i + 1] = ((boosted shr 8) and 0xFF).toByte()
            val abs = kotlin.math.abs(boosted)
            if (abs > peak) peak = abs
            i += 2
        }
        val level = (peak * 100 / Short.MAX_VALUE).coerceIn(0, 100)
        return level to clipped
    }

    private fun createAudioRecord(bufferSize: Int): AudioRecord? {
        for (source in intArrayOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.CAMCORDER)) {
            try {
                val record = AudioRecord(
                    source, sampleRate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufferSize
                )
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    pinToBuiltInMic(record)
                    return record
                }
                record.release()
            } catch (_: Throwable) {
                // try next source
            }
        }
        return null
    }

    /**
     * Section 6 v1 policy: refuse a Bluetooth SCO route (forced 8/16kHz,
     * would pitch/scramble a 44.1kHz encoder) and stay on the built-in mic,
     * rather than reconfigure live. Proactively pins the preferred input
     * device before recording starts, which is the direct way to prevent
     * the system from ever routing this AudioRecord to SCO in the first
     * place.
     */
    private fun pinToBuiltInMic(record: AudioRecord) {
        try {
            val builtInMic = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            builtInMic?.let { record.setPreferredDevice(it) }
        } catch (_: Throwable) {
            // Best-effort -- if this fails, checkRoutedDevice() after
            // startRecording() still catches an actual SCO route.
        }
    }

    /** Defensive check after startRecording(): confirms we didn't end up on SCO anyway. */
    private fun checkRoutedDevice(record: AudioRecord) {
        try {
            if (record.routedDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                scoRefusalCount++
                DebugLog.log("Bluetooth SCO route detected -- refused, re-pinned to built-in mic (#$scoRefusalCount)")
                reportTelemetry()
                pinToBuiltInMic(record)
            }
        } catch (_: Throwable) {
        }
    }

    private fun drainEncoder(codec: MediaCodec, info: MediaCodec.BufferInfo, waitForEos: Boolean) {
        while (true) {
            val timeoutUs = if (waitForEos) 10_000L else 0L
            val outIndex = codec.dequeueOutputBuffer(info, timeoutUs)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> { if (waitForEos) continue else return }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { }
                outIndex >= 0 -> {
                    val encoded = codec.getOutputBuffer(outIndex)
                    if (encoded != null && info.size > 0) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            parseCodecConfig(encoded, info)
                        } else {
                            encoded.position(info.offset)
                            encoded.limit(info.offset + info.size)
                            val raw = ByteArray(info.size)
                            encoded.get(raw)
                            val frame = wrapAdts(raw)
                            // Enqueue for the network side -- never touches
                            // the network directly here. A stalled/
                            // reconnecting socket cannot block this.
                            queue.offer(frame)
                            // Fork the same frame to a local recording file
                            // if one is open (Phase 7+). Best-effort: a
                            // write failure stops recording rather than
                            // taking down capture/streaming.
                            localRecordingOut?.let { out ->
                                try {
                                    out.write(frame)
                                } catch (t: Throwable) {
                                    DebugLog.log("Local recording write failed, stopping: ${t.javaClass.simpleName}: ${t.message}")
                                    closeLocalRecording()
                                }
                            }
                        }
                    }
                    val isEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(outIndex, false)
                    if (isEos) return
                }
            }
        }
    }

    private fun parseCodecConfig(buffer: java.nio.ByteBuffer, info: MediaCodec.BufferInfo) {
        if (info.size < 2) return
        val b0 = buffer.get(info.offset).toInt() and 0xFF
        val b1 = buffer.get(info.offset + 1).toInt() and 0xFF
        val audioObjectType = (b0 shr 3) and 0x1F
        val sampleRateIdx = ((b0 and 0x7) shl 1) or ((b1 shr 7) and 0x1)
        val channelConfig = (b1 shr 3) and 0xF
        adtsProfile = (audioObjectType - 1).coerceIn(0, 3)
        adtsSampleRateIndex = sampleRateIdx
        adtsChannelConfig = if (channelConfig in 1..7) channelConfig else CHANNEL_COUNT
    }

    private fun wrapAdts(raw: ByteArray): ByteArray {
        val frameLength = raw.size + 7
        val header = ByteArray(7)
        header[0] = 0xFF.toByte()
        header[1] = 0xF1.toByte()
        header[2] = (((adtsProfile and 0x3) shl 6) or
                ((adtsSampleRateIndex and 0xF) shl 2) or
                ((adtsChannelConfig shr 2) and 0x1)).toByte()
        header[3] = (((adtsChannelConfig and 0x3) shl 6) or
                ((frameLength shr 11) and 0x3)).toByte()
        header[4] = ((frameLength shr 3) and 0xFF).toByte()
        header[5] = (((frameLength and 0x7) shl 5) or 0x1F).toByte()
        header[6] = 0xFC.toByte()
        val out = ByteArray(header.size + raw.size)
        System.arraycopy(header, 0, out, 0, header.size)
        System.arraycopy(raw, 0, out, header.size, raw.size)
        return out
    }

    // ================= Writer + reconnect thread =================

    private fun runWriter() {
        var backoffMs = BACKOFF_MIN_MS

        while (running) {
            // --- (Re)connect if needed ---
            if (currentUploader == null) {
                if (state == State.RECONNECTING) {
                    val waitStart = SystemClock.elapsedRealtime()
                    val deadline = waitStart + backoffMs
                    val graceDeadline = waitStart + RECONNECT_GRACE_MS
                    while (running && SystemClock.elapsedRealtime() < deadline) {
                        val now = SystemClock.elapsedRealtime()
                        if (networkAvailableSignal && now >= graceDeadline) {
                            // A new network appeared and we've waited out
                            // the minimum grace period -- retry now
                            // instead of waiting out the rest of the
                            // backoff (section 7: ConnectivityManager
                            // drives reconnect on handover).
                            networkAvailableSignal = false
                            break
                        }
                        Thread.sleep(200)
                    }
                    if (!running) break
                }
                try {
                    val u = IcecastUploader(host, port, username, password, mount)
                    u.connectAndHandshake()
                    currentUploader = u
                    // Don't try to "catch up" on whatever backlog piled up
                    // in the queue during the outage. The coalescing loop
                    // below only paces itself against real *incoming*
                    // frames -- if there's already a backlog sitting in
                    // the queue when we reconnect, it drains it in a rapid
                    // burst (network write is far faster than the audio's
                    // real-time rate), handing the server several seconds
                    // of audio much faster than real-time. That appears to
                    // be what was behind the persistent pitch-shifted
                    // "whistle" reported after reconnects (a burst like
                    // that is exactly the kind of thing that throws off a
                    // decoder-side clock-recovery/resync assumption) --
                    // acoustic feedback was ruled out (happened even 10m
                    // away), so this is the next most likely cause given
                    // it only ever showed up right after a reconnect and
                    // the encoder itself is never restarted. Starting
                    // clean costs a few seconds of stale audio that would
                    // have sounded delayed and out of place next to live
                    // content anyway.
                    queue.clear()
                    state = State.LIVE
                    DebugLog.log("Connected -- live (queue cleared, resuming live)")
                    backoffMs = BACKOFF_MIN_MS
                } catch (e: IOException) {
                    lastError = "${e.javaClass.simpleName}: ${e.message}"
                    // Guard against a stop()-triggered close racing in here:
                    // don't report a spurious RECONNECTING state (and the
                    // notification update it triggers) once we're already
                    // shutting down.
                    if (running) {
                        reconnectCount++
                        DebugLog.log("Connect failed: $lastError -- reconnecting (attempt $reconnectCount)")
                        reportTelemetry()
                        state = State.RECONNECTING
                        if (lastError?.contains("Mountpoint already taken") == true) {
                            // The server has told us directly the old mount
                            // isn't free yet -- doubling from BACKOFF_MIN_MS
                            // just wastes one or two more guaranteed-to-fail
                            // cycles (each one its own brief on-air blip on
                            // the eventual reconnect). Jump straight to the
                            // ceiling instead of ramping up to it.
                            backoffMs = BACKOFF_MAX_MS
                        } else {
                            backoffMs = (backoffMs * 2).coerceAtMost(BACKOFF_MAX_MS)
                        }
                    }
                    continue
                }
            }

            // --- Coalesce whole frames up to ~200ms, then one write ---
            val coalesced = ByteArrayOutputStream()
            val deadline = SystemClock.elapsedRealtime() + COALESCE_MAX_WAIT_MS
            var approxMs = 0
            while (running && approxMs < COALESCE_TARGET_MS) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0) break
                val frame = queue.poll(remaining) ?: break
                coalesced.write(frame)
                approxMs += msPerFrame
            }
            if (coalesced.size() == 0) continue

            try {
                val payload = coalesced.toByteArray()
                currentUploader?.writeAdtsFrame(payload)
                bytesUploadedTotal += payload.size
            } catch (e: IOException) {
                lastError = "${e.javaClass.simpleName}: ${e.message}"
                currentUploader?.close()
                currentUploader = null
                // Same guard as above -- stop() force-closes the uploader,
                // which lands here. Don't flip to RECONNECTING for that.
                if (running) {
                    reconnectCount++
                    DebugLog.log("Write failed: $lastError -- reconnecting (attempt $reconnectCount)")
                    reportTelemetry()
                    state = State.RECONNECTING
                }
            }
        }
        currentUploader?.close()
        currentUploader = null
    }
}
