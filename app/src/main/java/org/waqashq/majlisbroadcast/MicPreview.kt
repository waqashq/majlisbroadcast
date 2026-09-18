package org.waqashq.majlisbroadcast

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.Process

/**
 * Phase 11f: local-only mic capture that drives the Broadcast screen's
 * frequency bars while NOT broadcasting, so the mic can be checked before
 * a majlis starts (majlisbroadcast.md section 8: "so the owner confirms
 * the mic is registering before speaking"). Nothing here touches the
 * network, the encoder or any file -- it reads PCM, runs the same
 * SpectrumAnalyzer the live path uses, and throws the audio away.
 *
 * Only one thing on Android can hold the mic at a time, so this MUST be
 * stopped before BroadcastService starts capturing -- see
 * MainActivity.startBroadcastNow(). It is also stopped whenever the screen
 * leaves the foreground, so it never runs in the background or costs
 * battery while the app isn't visible.
 *
 * Applies the same fixed gain as BroadcastEngine so the bars read the same
 * here as they do once live, rather than jumping when you go on air.
 */
class MicPreview(
    private val sampleRate: Int,
    /** Phase 11h: same NoiseReducer as the live path, so the bars match what listeners will hear. */
    private val noiseReduction: Boolean,
    /** bands + whether any sample hit the ceiling since the last callback (Phase 11k). */
    private val onBands: (IntArray, Boolean) -> Unit
) {
    private companion object {
        /** Matches BroadcastEngine.GAIN_FACTOR so preview and live levels agree. */
        const val GAIN_FACTOR = 3.0f
        /** Needs to comfortably exceed SpectrumAnalyzer's FFT window (2048 samples). */
        const val MIN_BUFFER_BYTES = 8192
    }

    private var thread: Thread? = null
    @Volatile private var running = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private val analyzer = SpectrumAnalyzer(sampleRate, SpectrumView.BAND_COUNT)
    private val bands = IntArray(SpectrumView.BAND_COUNT)
    private val noiseReducer = NoiseReducer(sampleRate)

    /** No-op if already running. Caller must hold RECORD_AUDIO. */
    fun start() {
        if (running) return
        running = true
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val bufSize = maxOf(minBuf * 2, MIN_BUFFER_BYTES)
            val record = createAudioRecord(bufSize) ?: run {
                running = false
                return@Thread
            }
            val buf = ByteArray(bufSize)
            var clippedSinceReport = false
            try {
                record.startRecording()
                while (running) {
                    val read = record.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                    if (read <= 0) continue
                    if (applyGain(buf, read)) clippedSinceReport = true
                    if (analyzer.analyze(buf, read, bands)) {
                        val snapshot = bands.copyOf()
                        val clipped = clippedSinceReport
                        clippedSinceReport = false
                        uiHandler.post { if (running) onBands(snapshot, clipped) }
                    }
                }
            } catch (_: Throwable) {
                // Mic unavailable (taken by a call, another app, or the
                // broadcast itself): just stop previewing, silently -- this
                // is a convenience, never something to surface as an error.
            } finally {
                try { record.stop() } catch (_: Throwable) {}
                record.release()
            }
        }, "MicPreview").apply { start() }
    }

    /** Stops capture and releases the mic. Safe to call repeatedly. */
    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    /** Noise reduction (if on) then the same fixed gain as BroadcastEngine. Returns true if anything clipped. */
    private fun applyGain(buf: ByteArray, byteCount: Int): Boolean {
        var clipped = false
        var i = 0
        while (i + 1 < byteCount) {
            var sample = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toDouble()
            if (noiseReduction) sample = noiseReducer.process(sample)
            val raw = (sample * GAIN_FACTOR).toInt()
            if (raw > Short.MAX_VALUE || raw < Short.MIN_VALUE) clipped = true
            val boosted = raw.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            buf[i] = (boosted and 0xFF).toByte()
            buf[i + 1] = ((boosted shr 8) and 0xFF).toByte()
            i += 2
        }
        return clipped
    }

    /** Same source preference as BroadcastEngine: UNPROCESSED, else CAMCORDER. Never MIC. */
    private fun createAudioRecord(bufferSize: Int): AudioRecord? {
        for (source in intArrayOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.CAMCORDER)) {
            try {
                val record = AudioRecord(
                    source, sampleRate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufferSize
                )
                if (record.state == AudioRecord.STATE_INITIALIZED) return record
                record.release()
            } catch (_: SecurityException) {
                return null
            } catch (_: Throwable) {
                // try next source
            }
        }
        return null
    }
}
