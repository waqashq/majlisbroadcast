package org.waqashq.majlisbroadcast

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Light-touch noise reduction for the mic input, shared by the live path
 * (BroadcastEngine) and the idle mic preview (MicPreview) so the meter shows
 * what listeners actually hear.
 *
 * Phase 11i rewrite ("gentler"), after the user heard the Phase 11h version
 * turning the actual voice down. Re-testing with a lower, male-pitched,
 * continuously-speaking test voice (the first test used a 200Hz voice in
 * short bursts, which hid both problems) showed the old version cost the
 * voice ~1.6dB overall and 3.7dB at 100Hz:
 *  - its 85Hz high-pass was 4th order, which reaches up into the
 *    fundamental of a male voice (roughly 85-180Hz) -- the "body" of it;
 *  - its gate's noise-floor estimate crept upward the whole time someone
 *    talked, so over a long unbroken passage the floor rose toward the
 *    soft syllables and started dipping them.
 *
 * Phase 11j ("still a little too much"): the remaining voice loss came
 * almost entirely from the high-pass, so it moved 60Hz -> 40Hz; the gate also
 * got a 500ms hold and opens at +4dB instead of +6dB. Measured now: speech
 * -0.1dB at both normal and 12dB-quieter voice, 100Hz -0.1dB, real pauses
 * -3.8dB, 50Hz rumble only -1.5dB (rumble removal was knowingly traded away).
 *
 * Phase 11i numbers, for reference -- measured on the same lecture-style test signal: speech -0.2/-0.3dB
 * (inaudible), 100Hz -0.5dB, real pauses -4.7dB, 50Hz rumble -4.9dB, and a
 * voice 12dB quieter is still untouched. It trades away most of the old
 * rumble/pause reduction for leaving the voice alone, which is the right
 * way round for a lecture.
 *
 *  1. HIGH-PASS at 40Hz, 2nd order only (Phase 11j: was 60Hz). Clears
 *     sub-audible thumps and handling bumps; stays well clear of a male
 *     voice's fundamental.
 *
 *  2. GENTLE GATE with a "minimum statistics" noise floor: the floor is the
 *     QUIETEST envelope seen over the last ~2s (4 blocks of 500ms), so it
 *     finds the room's real background level from natural breathing pauses
 *     and cannot creep up during speech. As a second guard it is also
 *     capped ~22dB below recent speech. Gaps are faded down by at most -6dB
 *     (never to silence), with a 3ms attack, a 500ms hold so soft word
 *     endings aren't dipped, and a slow 400ms release so it never pumps.
 *
 * All state is per-instance, and [process] (called per sample on the
 * capture thread) never allocates.
 */
class NoiseReducer(sampleRate: Int) {

    private companion object {
        const val HIGHPASS_HZ = 40.0
        const val HIGHPASS_Q = 0.707 // Butterworth: flat, no resonant bump
        const val ENVELOPE_MS = 60.0
        const val ATTACK_MS = 3.0
        const val RELEASE_MS = 400.0
        const val HOLD_MS = 500.0
        /** Gate opens once the signal is this far above the noise floor (~+4dB) -- easy to stay open while talking. */
        const val OPEN_RATIO = 1.6
        /** Deepest attenuation while gated: -6dB. Never silence. */
        const val GATE_DEPTH_GAIN = 0.5
        const val BLOCK_MS = 500.0
        const val BLOCK_COUNT = 4
        /** How long "recent speech level" takes to decay, for the floor cap. */
        const val SPEECH_DECAY_MS = 3000.0
        /** Noise floor may never exceed this fraction of recent speech (~-22dB). */
        const val SPEECH_CAP = 0.08
    }

    // High-pass biquad (RBJ) coefficients and state.
    private val b0: Double
    private val b1: Double
    private val b2: Double
    private val a1: Double
    private val a2: Double
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    private val envCoeff = exp(-1.0 / (sampleRate * ENVELOPE_MS / 1000.0))
    private val attackCoeff = exp(-1.0 / (sampleRate * ATTACK_MS / 1000.0))
    private val releaseCoeff = exp(-1.0 / (sampleRate * RELEASE_MS / 1000.0))
    private val speechCoeff = exp(-1.0 / (sampleRate * SPEECH_DECAY_MS / 1000.0))
    private val holdSamples = (sampleRate * HOLD_MS / 1000.0).toInt()
    private val blockSamples = (sampleRate * BLOCK_MS / 1000.0).toInt()

    private val blockMins = DoubleArray(BLOCK_COUNT) { Double.MAX_VALUE }
    private var blockMinIndex = 0
    private var currentBlockMin = Double.MAX_VALUE
    private var blockCount = 0

    private var envelope = 0.0
    private var speechLevel = 0.0
    /** 0 = no estimate yet (gate stays open until the first block completes). */
    private var noiseFloor = 0.0
    private var holdLeft = 0
    private var gain = 1.0

    init {
        val w0 = 2.0 * Math.PI * HIGHPASS_HZ / sampleRate
        val cosW0 = cos(w0)
        val alpha = sin(w0) / (2.0 * HIGHPASS_Q)
        val a0 = 1.0 + alpha
        b0 = ((1.0 + cosW0) / 2.0) / a0
        b1 = (-(1.0 + cosW0)) / a0
        b2 = ((1.0 + cosW0) / 2.0) / a0
        a1 = (-2.0 * cosW0) / a0
        a2 = (1.0 - alpha) / a0
    }

    /** Clears filter/gate state -- call when capture restarts so stale state can't gate the first word. */
    fun reset() {
        x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0
        blockMins.fill(Double.MAX_VALUE)
        blockMinIndex = 0
        currentBlockMin = Double.MAX_VALUE
        blockCount = 0
        envelope = 0.0
        speechLevel = 0.0
        noiseFloor = 0.0
        holdLeft = 0
        gain = 1.0
    }

    /** One 16-bit sample in, processed sample out (same scale). */
    fun process(sample: Double): Double {
        // --- stage 1: gentle high-pass ---
        val hp = b0 * sample + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = sample
        y2 = y1; y1 = hp

        // --- stage 2: gate ---
        val level = abs(hp)
        envelope = if (level > envelope) level else envelope * envCoeff + level * (1 - envCoeff)
        speechLevel = if (envelope > speechLevel) envelope else speechLevel * speechCoeff

        // Minimum statistics: remember the quietest moment of each 500ms
        // block; the floor is the quietest of the last four blocks.
        if (envelope < currentBlockMin) currentBlockMin = envelope
        if (++blockCount >= blockSamples) {
            blockMins[blockMinIndex] = currentBlockMin
            blockMinIndex = (blockMinIndex + 1) % BLOCK_COUNT
            currentBlockMin = Double.MAX_VALUE
            blockCount = 0
            var minOfBlocks = Double.MAX_VALUE
            for (m in blockMins) if (m < minOfBlocks) minOfBlocks = m
            noiseFloor = if (minOfBlocks == Double.MAX_VALUE) 0.0 else minOfBlocks
            // Second guard: the floor can never sit within ~22dB of speech.
            val cap = speechLevel * SPEECH_CAP
            if (noiseFloor > cap) noiseFloor = cap
        }

        var open = noiseFloor <= 0.0 || envelope > noiseFloor * OPEN_RATIO
        if (open) {
            holdLeft = holdSamples
        } else if (holdLeft > 0) {
            holdLeft--
            open = true
        }

        val target = if (open) 1.0 else GATE_DEPTH_GAIN
        val coeff = if (target > gain) attackCoeff else releaseCoeff
        gain = target + (gain - target) * coeff

        return hp * gain
    }
}
