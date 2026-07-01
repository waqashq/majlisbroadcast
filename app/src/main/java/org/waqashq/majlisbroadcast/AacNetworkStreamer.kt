package org.waqashq.majlisbroadcast

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 2: same capture + AAC-LC + ADTS pipeline as AacFileRecorder (Phase
 * 1), but writes frames to the live AzuraCast harbor over IcecastUploader
 * instead of a local file.
 *
 * Deliberately self-contained rather than sharing code with
 * AacFileRecorder: that class is already verified working on a real device,
 * and Phase 3 restructures this whole pipeline into a foreground service
 * with a bounded queue anyway, so a little duplication now beats an early
 * shared abstraction that gets thrown away next phase.
 *
 * No reconnect logic here -- if the connection drops, capture stops and the
 * error surfaces via lastError/state. Auto-reconnect, backoff, and the
 * bounded queue are Phase 3 scope (majlisbroadcast.md section 5).
 */
class AacNetworkStreamer(private val uploader: IcecastUploader) {

    enum class State { IDLE, CONNECTING, LIVE, STOPPED, ERROR }

    companion object {
        private const val SAMPLE_RATE = 44100
        private const val BIT_RATE = 64_000
        private const val CHANNEL_COUNT = 1
        private const val MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC
        private val SAMPLE_RATE_TABLE = intArrayOf(
            96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
            16000, 12000, 11025, 8000, 7350
        )
    }

    @Volatile var lastError: String? = null
        private set
    @Volatile var state: State = State.IDLE
        private set

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    @Volatile private var adtsProfile = 1
    @Volatile private var adtsSampleRateIndex =
        SAMPLE_RATE_TABLE.indexOf(SAMPLE_RATE).let { if (it < 0) 4 else it }
    @Volatile private var adtsChannelConfig = CHANNEL_COUNT
    private var sampleCount = 0L

    fun start() {
        if (running.getAndSet(true)) return
        lastError = null
        state = State.CONNECTING
        thread = Thread({ runLoop() }, "AacNetworkStreamer").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        running.set(false)
        thread?.join(3000)
        thread = null
    }

    private fun runLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

        var audioRecord: AudioRecord? = null
        var codec: MediaCodec? = null

        try {
            uploader.connectAndHandshake()
            state = State.LIVE

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

            val pcmBuf = ByteArray(bufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            while (running.get()) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val read = audioRecord.read(pcmBuf, 0, pcmBuf.size, AudioRecord.READ_BLOCKING)
                    val inputBuffer = codec.getInputBuffer(inIndex)
                    if (inputBuffer != null && read > 0) {
                        inputBuffer.clear()
                        inputBuffer.put(pcmBuf, 0, read)
                        val ptsUs = sampleCount * 1_000_000L / SAMPLE_RATE
                        codec.queueInputBuffer(inIndex, 0, read, ptsUs, 0)
                        sampleCount += read / 2
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
            state = State.STOPPED
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            state = State.ERROR
        } finally {
            try { audioRecord?.stop() } catch (_: Throwable) {}
            audioRecord?.release()
            try { codec?.stop() } catch (_: Throwable) {}
            codec?.release()
            uploader.close()
            running.set(false)
        }
    }

    private fun createAudioRecord(bufferSize: Int): AudioRecord? {
        for (source in intArrayOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.CAMCORDER)) {
            try {
                val record = AudioRecord(
                    source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufferSize
                )
                if (record.state == AudioRecord.STATE_INITIALIZED) return record
                record.release()
            } catch (_: Throwable) {
                // try next source
            }
        }
        return null
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
                            // Throws on stall / half-open / server rejection --
                            // caught in runLoop(), which tears everything down
                            // and surfaces lastError/state.
                            uploader.writeAdtsFrame(wrapAdts(raw))
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
}
