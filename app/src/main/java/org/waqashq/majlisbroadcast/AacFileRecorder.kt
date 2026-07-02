package org.waqashq.majlisbroadcast

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Process
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 1: capture from the mic, encode to AAC-LC, wrap in ADTS, write to a
 * local file. No networking yet -- that comes in Phase 2. See
 * majlisbroadcast.md sections 3 and 5 for the requirements this implements.
 *
 * Threading: capture + encode both happen on one dedicated thread at
 * THREAD_PRIORITY_AUDIO, using synchronous MediaCodec (explicit dequeue/queue
 * calls), not the async MediaCodec.Callback path -- the brief calls out the
 * three-way thread sync of the callback API as not worth it here.
 */
class AacFileRecorder(private val outputFile: File) {

    companion object {
        private const val SAMPLE_RATE = 44100
        private const val BIT_RATE = 64_000
        private const val CHANNEL_COUNT = 1 // mono
        private const val MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC

        // ISO 14496-3 Table 1.16 sampling_frequency_index. 44100 -> 4 (see
        // majlisbroadcast.md section 5: this must be the index, not the raw
        // integer, or the mount rejects audio right after a clean handshake).
        private val SAMPLE_RATE_TABLE = intArrayOf(
            96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
            16000, 12000, 11025, 8000, 7350
        )

        // See BroadcastEngine's GAIN_FACTOR doc -- same reasoning, kept
        // consistent between the two test harnesses.
        private const val GAIN_FACTOR = 4.0f
    }

    @Volatile
    var lastError: String? = null
        private set

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    // ADTS header fields, filled in from the encoder's own codec-config
    // buffer once it arrives (not assumed from what we requested -- a device
    // encoder is allowed to differ from the request).
    @Volatile private var adtsProfile = 1 // AAC LC fallback: objectType(2) - 1
    @Volatile private var adtsSampleRateIndex =
        SAMPLE_RATE_TABLE.indexOf(SAMPLE_RATE).let { if (it < 0) 4 else it }
    @Volatile private var adtsChannelConfig = CHANNEL_COUNT

    private var sampleCount = 0L

    fun start() {
        if (running.getAndSet(true)) return
        lastError = null
        thread = Thread({ runCaptureEncodeLoop() }, "AacFileRecorder").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /** Blocks briefly while the encoder flushes its tail -- avoids truncation. */
    fun stop() {
        running.set(false)
        thread?.join(3000)
        thread = null
    }

    private fun runCaptureEncodeLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

        var audioRecord: AudioRecord? = null
        var codec: MediaCodec? = null
        var out: FileOutputStream? = null

        try {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) {
                lastError = "getMinBufferSize failed ($minBuf)"
                return
            }
            // Oversized cushion (2-3x min) for real-time safety margin.
            val bufferSize = minBuf * 3

            audioRecord = createAudioRecord(bufferSize)
            if (audioRecord == null || audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                lastError = "AudioRecord failed to initialize (UNPROCESSED and CAMCORDER both unavailable)"
                return
            }

            val format = MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE, CHANNEL_COUNT).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize)
            }
            codec = MediaCodec.createEncoderByType(MIME_TYPE).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            outputFile.parentFile?.mkdirs()
            out = FileOutputStream(outputFile)

            audioRecord.startRecording()

            val pcmBuf = ByteArray(bufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            // --- Main loop: blocking read -> feed encoder -> drain output ---
            while (running.get()) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val read = audioRecord.read(pcmBuf, 0, pcmBuf.size, AudioRecord.READ_BLOCKING)
                    val inputBuffer = codec.getInputBuffer(inIndex)
                    if (inputBuffer != null && read > 0) {
                        applyGain(pcmBuf, read)
                        inputBuffer.clear()
                        inputBuffer.put(pcmBuf, 0, read)
                        val ptsUs = sampleCount * 1_000_000L / SAMPLE_RATE
                        codec.queueInputBuffer(inIndex, 0, read, ptsUs, 0)
                        sampleCount += read / 2 // 16-bit mono: 2 bytes per sample
                    } else if (inputBuffer != null) {
                        // Zero-byte read: just resubmit an empty non-EOS buffer
                        // rather than busy-looping.
                        codec.queueInputBuffer(inIndex, 0, 0, 0, 0)
                    }
                }
                drainEncoder(codec, bufferInfo, out, waitForEos = false)
            }

            // --- Stop requested: push EOS and flush cleanly (no tail cut) ---
            var eosSent = false
            while (!eosSent) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val ptsUs = sampleCount * 1_000_000L / SAMPLE_RATE
                    codec.queueInputBuffer(inIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    eosSent = true
                }
            }
            drainEncoder(codec, bufferInfo, out, waitForEos = true)
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message}"
        } finally {
            try { audioRecord?.stop() } catch (_: Throwable) {}
            audioRecord?.release()
            try { codec?.stop() } catch (_: Throwable) {}
            codec?.release()
            try { out?.flush() } catch (_: Throwable) {}
            try { out?.close() } catch (_: Throwable) {}
        }
    }

    /** UNPROCESSED preferred; CAMCORDER fallback. Never MIC -- its AGC/high-pass thins out voice. */
    /**
     * Boosts raw 16-bit little-endian PCM samples in place by GAIN_FACTOR,
     * clamped to the valid Short range to avoid hard clipping distortion.
     */
    private fun applyGain(buf: ByteArray, byteCount: Int) {
        var i = 0
        while (i + 1 < byteCount) {
            val sample = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort()
            val boosted = (sample * GAIN_FACTOR).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            buf[i] = (boosted and 0xFF).toByte()
            buf[i + 1] = ((boosted shr 8) and 0xFF).toByte()
            i += 2
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

    private fun drainEncoder(
        codec: MediaCodec,
        info: MediaCodec.BufferInfo,
        out: FileOutputStream?,
        waitForEos: Boolean
    ) {
        while (true) {
            val timeoutUs = if (waitForEos) 10_000L else 0L
            val outIndex = codec.dequeueOutputBuffer(info, timeoutUs)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (waitForEos) continue else return
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // Ignored -- ADTS headers are built from the codec-config
                    // buffer below, not from this callback.
                }
                outIndex >= 0 -> {
                    val encoded = codec.getOutputBuffer(outIndex)
                    if (encoded != null && info.size > 0) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            parseCodecConfig(encoded, info)
                            // Codec-config buffer is consumed for ADTS fields
                            // only, never written as audio (section 5).
                        } else {
                            encoded.position(info.offset)
                            encoded.limit(info.offset + info.size)
                            val raw = ByteArray(info.size)
                            encoded.get(raw)
                            out?.write(wrapAdts(raw))
                        }
                    }
                    val isEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(outIndex, false)
                    if (isEos) return
                }
            }
        }
    }

    /**
     * Parses the AudioSpecificConfig from the encoder's codec-config buffer
     * (2 bytes for plain AAC-LC, no SBR/PS): 5 bits object type, 4 bits
     * sampling-frequency index, 4 bits channel config, 3 bits padding.
     */
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

    /**
     * Builds the 7-byte ADTS header. protection_absent = 1 (no CRC), so
     * frame_length = 7 + raw AAC payload size, and no 2-byte CRC is appended.
     */
    private fun wrapAdts(raw: ByteArray): ByteArray {
        val frameLength = raw.size + 7
        val header = ByteArray(7)
        header[0] = 0xFF.toByte()
        header[1] = 0xF1.toByte() // sync(4) + MPEG-4(1) + layer(2)=00 + protection_absent(1)=1
        header[2] = (((adtsProfile and 0x3) shl 6) or
                ((adtsSampleRateIndex and 0xF) shl 2) or
                ((adtsChannelConfig shr 2) and 0x1)).toByte()
        header[3] = (((adtsChannelConfig and 0x3) shl 6) or
                ((frameLength shr 11) and 0x3)).toByte()
        header[4] = ((frameLength shr 3) and 0xFF).toByte()
        header[5] = (((frameLength and 0x7) shl 5) or 0x1F).toByte() // + buffer_fullness top bits (VBR = all 1s)
        header[6] = 0xFC.toByte() // buffer_fullness low bits (VBR) + num_raw_data_blocks-1 = 0
        val out = ByteArray(header.size + raw.size)
        System.arraycopy(header, 0, out, 0, header.size)
        System.arraycopy(raw, 0, out, header.size, raw.size)
        return out
    }
}
