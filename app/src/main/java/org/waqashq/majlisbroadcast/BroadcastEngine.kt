package org.waqashq.majlisbroadcast

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.IOException

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
    private val audioManager: AudioManager,
    private val listener: Listener
) {
    enum class State { IDLE, CONNECTING, LIVE, RECONNECTING, STOPPED, ERROR }

    interface Listener {
        fun onStateChanged(state: State, error: String?)
        fun onTelemetry(
            dropCount: Long, reconnectCount: Int, scoRefusalCount: Int, focusLost: Boolean,
            queueDepth: Int, burstDropEvents: Int
        )
        /** level: 0-100 peak scale. Throttled to ~150ms; safe to call often. */
        fun onLevelUpdate(level: Int, clipping: Boolean)
    }

    companion object {
        private const val SAMPLE_RATE = 44100
        private const val BIT_RATE = 64_000
        private const val CHANNEL_COUNT = 1
        private const val MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC
        private val SAMPLE_RATE_TABLE = intArrayOf(
            96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
            16000, 12000, 11025, 8000, 7350
        )

        // ~150 frames at ~23ms/frame (1024 samples @ 44.1kHz) is a ~3.4s
        // real-time cushion: enough to absorb a brief stall without
        // unbounded memory growth.
        private const val QUEUE_CAPACITY_FRAMES = 150
        private const val MS_PER_FRAME = 23

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
        // manual gain compensates. ~4x (~12dB) is a starting point based on
        // on-device comparison against AGC'd apps; adjust to taste. Clamped
        // to avoid hard digital clipping on loud peaks.
        private const val GAIN_FACTOR = 4.0f
    }

    @Volatile private var running = false
    private var captureThread: Thread? = null
    private var writerThread: Thread? = null
    private val queue = FrameQueue(QUEUE_CAPACITY_FRAMES)

    @Volatile private var reconnectCount = 0
    @Volatile private var scoRefusalCount = 0
    @Volatile private var focusLost = false
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
        SAMPLE_RATE_TABLE.indexOf(SAMPLE_RATE).let { if (it < 0) 4 else it }
    @Volatile private var adtsChannelConfig = CHANNEL_COUNT

    fun start() {
        if (running) return
        running = true
        queue.clear()
        DebugLog.log("Engine starting")
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
        state = State.STOPPED
        DebugLog.log("Engine stopped")
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
            queue.size(), queue.burstDropEvents
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
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) throw IllegalStateException("getMinBufferSize failed ($minBuf)")
            val bufferSize = minBuf * 3

            audioRecord = createAudioRecord(bufferSize)
                ?: throw IllegalStateException("AudioRecord failed to initialize")

            val format = MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE, CHANNEL_COUNT).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
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
                    if (focusLost) {
                        // Phone call / focus loss (section 6): don't touch a
                        // mic telephony may have claimed exclusively.
                        // Synthesize perfectly-timed silence instead, paced
                        // to real time so the sample clock stays continuous
                        // and the harbor slot stays alive without a
                        // reconnect.
                        java.util.Arrays.fill(pcmBuf, 0.toByte())
                        read = pcmBuf.size
                        val pacingMs = (read / 2).toLong() * 1000L / SAMPLE_RATE
                        Thread.sleep(pacingMs)
                        reportLevel(0, false)
                    } else {
                        read = audioRecord.read(pcmBuf, 0, pcmBuf.size, AudioRecord.READ_BLOCKING)
                        if (read > 0) {
                            val (level, clipped) = applyGainAndMeasure(pcmBuf, read)
                            reportLevel(level, clipped)
                        }
                    }
                    val inputBuffer = codec.getInputBuffer(inIndex)
                    if (inputBuffer != null && read > 0) {
                        inputBuffer.clear()
                        inputBuffer.put(pcmBuf, 0, read)
                        val ptsUs = sampleCount * 1_000_000L / SAMPLE_RATE
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
                    val ptsUs = sampleCount * 1_000_000L / SAMPLE_RATE
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
        }
    }

    /**
     * Boosts raw 16-bit little-endian PCM samples in place by GAIN_FACTOR
     * (clamped to avoid hard-clipping distortion), and in the same pass
     * measures the buffer's peak level (0-100) and whether any sample
     * actually hit the clamp -- feeds the UI's mic level/clipping meter
     * (section 8) without a second pass over the buffer.
     */
    private fun applyGainAndMeasure(buf: ByteArray, byteCount: Int): Pair<Int, Boolean> {
        var peak = 0
        var clipped = false
        var i = 0
        while (i + 1 < byteCount) {
            val sample = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort()
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
                    source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
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
                            // Enqueue only -- never touches the network.
                            // A stalled/reconnecting socket cannot block this.
                            queue.offer(wrapAdts(raw))
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
                    val u = IcecastUploader(host, port, username, password)
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
                approxMs += MS_PER_FRAME
            }
            if (coalesced.size() == 0) continue

            try {
                currentUploader?.writeAdtsFrame(coalesced.toByteArray())
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
