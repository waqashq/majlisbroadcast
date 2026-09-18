package org.waqashq.majlisbroadcast

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Noise reduction for the mic input, shared by the live path
 * (BroadcastEngine) and the idle mic preview (MicPreview) so the meter shows
 * what listeners actually hear.
 *
 * How it works (Phase 11m):
 *
 *  1. HIGH-PASS at 40Hz, 2nd order. Clears sub-audible thumps and handling
 *     bumps; stays well clear of a male voice's fundamental (~85-180Hz).
 *
 *  2. VOICE DETECTOR listening only to 300-3000Hz. That's where speech has
 *     most of its energy and where rooms are usually quiet -- room noise
 *     (fans, AC, traffic, building rumble) lives mostly below 300Hz. Its
 *     noise floor is the quietest moment of the last ~2s ("minimum
 *     statistics": 4 blocks of 500ms), but never below MIN_FLOOR, so the
 *     near-digital-silence of a good mic in a quiet room can't make every
 *     breath or rustle count as speech. Voice = detector more than +12dB
 *     above that floor.
 *
 *  3. TWO-BAND GATE. The signal is split at 300Hz (Linkwitz-Riley, so the
 *     two bands add back up to the original when untouched). While no voice
 *     is detected, the low band -- where the rumble is -- goes down -20dB,
 *     the rest -12dB (never to silence). The moment a voice appears both
 *     bands open fully in 3ms; a 300ms hold keeps soft word endings intact
 *     and a 250ms release avoids pumping. So during speech nothing is cut --
 *     the voice keeps its full low "body" -- and in the gaps the rumble drops
 *     away.
 *
 * History, because every step was driven by listening:
 *  - 11h: first version (85Hz 4th-order high-pass + gate) turned the voice
 *    itself down. 11i/11j made it gentler (40Hz 2nd order, floor that can't
 *    creep during speech).
 *  - 11k ("doesn't seem to work at all"): opening threshold was too close
 *    to the floor; raised.
 *  - 11l ("the toggle sometimes works"): a floor cap tied to recent speech
 *    disabled the gate in any pause >2s and right after switching ON;
 *    removed, and the reducer now runs even while switched off.
 *  - 11m ("more noise now"): tested on 30s of REAL room audio from the
 *    user's phone for the first time. 99% of that room's noise was rumble
 *    at 40-320Hz whose level swung ~30dB several times a second -- the old
 *    full-band detector kept mistaking the swings for speech and never
 *    closed (0dB reduction in the recording). Measured on the same
 *    recording now: quiet part -15dB, speech untouched except in its real
 *    pauses, also with the recording 12dB quieter. Synthetic benches:
 *    silence after switching ON / after talking -17dB, speech -0.1dB.
 *
 * All state is per-instance, and [process] (called per sample on the
 * capture thread) never allocates.
 */
class NoiseReducer(sampleRate: Int) {

    private companion object {
        const val HIGHPASS_HZ = 40.0
        /** Voice detector band. */
        const val DETECT_LOW_HZ = 300.0
        const val DETECT_HIGH_HZ = 3000.0
        /** Crossover between the "rumble" band and the rest. */
        const val SPLIT_HZ = 300.0
        const val BUTTERWORTH_Q = 0.707
        const val ENVELOPE_MS = 60.0
        const val ATTACK_MS = 3.0
        const val RELEASE_MS = 250.0
        const val HOLD_MS = 300.0
        /** Voice = detector this far above the noise floor (+12dB). */
        const val OPEN_RATIO = 4.0
        /**
         * Lowest the floor may go, in 16-bit sample units of the 300-3000Hz
         * detector envelope (~-64dBFS; so voice needs ~-52dBFS peaks in that
         * band). Measured on the user's phone: a quiet room's breaths and
         * rustles peak at 30-80 here, speech at hundreds to thousands.
         */
        const val MIN_FLOOR = 20.0
        /** Gap attenuation above / below the split: -12dB and -20dB. Never silence. */
        const val HIGH_DEPTH_GAIN = 0.25
        const val LOW_DEPTH_GAIN = 0.1
        const val BLOCK_MS = 500.0
        const val BLOCK_COUNT = 4
    }

    /** One RBJ 2nd-order Butterworth low- or high-pass section. */
    private class Biquad(sampleRate: Int, hz: Double, highPass: Boolean) {
        private val b0: Double
        private val b1: Double
        private val b2: Double
        private val a1: Double
        private val a2: Double
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        init {
            val w0 = 2.0 * Math.PI * hz / sampleRate
            val cosW0 = cos(w0)
            val alpha = sin(w0) / (2.0 * BUTTERWORTH_Q)
            val a0 = 1.0 + alpha
            val bEdge = (if (highPass) 1.0 + cosW0 else 1.0 - cosW0) / 2.0
            b0 = bEdge / a0
            b1 = (if (highPass) -2.0 * bEdge else 2.0 * bEdge) / a0
            b2 = bEdge / a0
            a1 = (-2.0 * cosW0) / a0
            a2 = (1.0 - alpha) / a0
        }

        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x
            y2 = y1; y1 = y
            return y
        }
    }

    private val highPass = Biquad(sampleRate, HIGHPASS_HZ, highPass = true)
    private val detectHp1 = Biquad(sampleRate, DETECT_LOW_HZ, highPass = true)
    private val detectHp2 = Biquad(sampleRate, DETECT_LOW_HZ, highPass = true)
    private val detectLp = Biquad(sampleRate, DETECT_HIGH_HZ, highPass = false)
    // Linkwitz-Riley 4th order = two identical Butterworth sections per band.
    private val splitLow1 = Biquad(sampleRate, SPLIT_HZ, highPass = false)
    private val splitLow2 = Biquad(sampleRate, SPLIT_HZ, highPass = false)
    private val splitHigh1 = Biquad(sampleRate, SPLIT_HZ, highPass = true)
    private val splitHigh2 = Biquad(sampleRate, SPLIT_HZ, highPass = true)

    private val envCoeff = exp(-1.0 / (sampleRate * ENVELOPE_MS / 1000.0))
    private val attackCoeff = exp(-1.0 / (sampleRate * ATTACK_MS / 1000.0))
    private val releaseCoeff = exp(-1.0 / (sampleRate * RELEASE_MS / 1000.0))
    private val holdSamples = (sampleRate * HOLD_MS / 1000.0).toInt()
    private val blockSamples = (sampleRate * BLOCK_MS / 1000.0).toInt()

    private val blockMins = DoubleArray(BLOCK_COUNT) { Double.MAX_VALUE }
    private var blockMinIndex = 0
    private var currentBlockMin = Double.MAX_VALUE
    private var blockCount = 0

    private var envelope = 0.0
    /** 0 = no estimate yet (gate stays open until the first block completes). */
    private var noiseFloor = 0.0
    private var holdLeft = 0
    private var highGain = 1.0
    private var lowGain = 1.0

    /** One 16-bit sample in, processed sample out (same scale). */
    fun process(sample: Double): Double {
        val hp = highPass.process(sample)

        // --- voice detector (300-3000Hz) ---
        val detect = abs(detectLp.process(detectHp2.process(detectHp1.process(hp))))
        envelope = if (detect > envelope) detect else envelope * envCoeff + detect * (1 - envCoeff)

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
            noiseFloor = if (minOfBlocks == Double.MAX_VALUE) 0.0 else maxOf(minOfBlocks, MIN_FLOOR)
        }

        var open = noiseFloor <= 0.0 || envelope > noiseFloor * OPEN_RATIO
        if (open) {
            holdLeft = holdSamples
        } else if (holdLeft > 0) {
            holdLeft--
            open = true
        }

        // --- two-band gate ---
        val highTarget = if (open) 1.0 else HIGH_DEPTH_GAIN
        val lowTarget = if (open) 1.0 else LOW_DEPTH_GAIN
        highGain = highTarget + (highGain - highTarget) * (if (highTarget > highGain) attackCoeff else releaseCoeff)
        lowGain = lowTarget + (lowGain - lowTarget) * (if (lowTarget > lowGain) attackCoeff else releaseCoeff)

        val low = splitLow2.process(splitLow1.process(hp))
        val high = splitHigh2.process(splitHigh1.process(hp))
        return high * highGain + low * lowGain
    }
}
