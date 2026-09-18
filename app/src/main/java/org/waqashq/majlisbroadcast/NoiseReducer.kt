package org.waqashq.majlisbroadcast

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Phase 11h: light-touch noise reduction for the mic input, shared by the
 * live path (BroadcastEngine) and the idle mic preview (MicPreview) so the
 * meter shows what listeners actually hear.
 *
 * Two deliberately conservative stages, because a majlis is a long quiet
 * voice recording and over-processing is worse than a little hiss:
 *
 *  1. HIGH-PASS at 85Hz. Removes fan/AC rumble, handling thumps and desk
 *     bumps, which sit below the voice and waste bitrate. Nothing in speech
 *     lives down there, so this is essentially free.
 *
 *  2. GENTLE GATE. Tracks the room's own noise floor and, when the signal
 *     drops to roughly that level (between sentences), fades the level down
 *     by at most -12dB. It does NOT cut to silence: a hard gate chews the
 *     tails off soft words and sounds like the stream is dropping out,
 *     which is far more noticeable than the hiss it removes. Attack is
 *     fast (5ms) so the start of a word is never clipped; release is slow
 *     (250ms) so it never pumps mid-sentence.
 *
 * Deliberately NOT here: spectral subtraction (can add watery "musical
 * noise" artifacts and wants tuning against a real recording of the room),
 * and Android's own NoiseSuppressor (part of the telephony processing chain
 * this app avoids on purpose -- see the UNPROCESSED choice in
 * majlisbroadcast.md section 3).
 *
 * All state is per-instance and no allocation happens in [process], which
 * runs per sample on the capture thread.
 */
class NoiseReducer(sampleRate: Int) {

    private companion object {
        const val HIGHPASS_HZ = 85.0
        const val HIGHPASS_Q = 0.707 // Butterworth: flat, no resonant bump
        /** Envelope follower time constant -- how fast "current loudness" tracks. */
        const val ENVELOPE_MS = 40.0
        const val ATTACK_MS = 5.0
        const val RELEASE_MS = 250.0
        /** Gate opens once the signal is this much above the measured noise floor. */
        const val OPEN_RATIO = 3.0 // ~+10dB
        /** Deepest attenuation while gated -- -12dB, not silence. */
        const val GATE_FLOOR_GAIN = 0.25
        /** Noise floor tracking: quick to follow a drop, very slow to rise. */
        const val FLOOR_DOWN = 0.05
        const val FLOOR_UP = 1.0000015
        const val FLOOR_MIN = 12.0 // in 16-bit sample units; avoids a zero floor in dead silence
    }

    // Biquad coefficients (RBJ high-pass) and state.
    private val b0: Double
    private val b1: Double
    private val b2: Double
    private val a1: Double
    private val a2: Double
    // Two identical stages in series (4th order). Measured on synthetic
    // rumble: one stage gives -9.7dB at 50Hz, two give -19.4dB, and speech
    // is unaffected either way -- worth the second stage for fan/AC noise.
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0
    private var x1b = 0.0
    private var x2b = 0.0
    private var y1b = 0.0
    private var y2b = 0.0

    private val envCoeff = exp(-1.0 / (sampleRate * (ENVELOPE_MS / 1000.0)))
    private val attackCoeff = exp(-1.0 / (sampleRate * (ATTACK_MS / 1000.0)))
    private val releaseCoeff = exp(-1.0 / (sampleRate * (RELEASE_MS / 1000.0)))

    private var envelope = 0.0
    private var noiseFloor = 200.0
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

    /** Clears filter/gate state -- call when capture restarts so a stale envelope can't gate the first word. */
    fun reset() {
        x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0
        x1b = 0.0; x2b = 0.0; y1b = 0.0; y2b = 0.0
        envelope = 0.0
        noiseFloor = 200.0
        gain = 1.0
    }

    /** One 16-bit sample in, processed sample out (same scale). */
    fun process(sample: Double): Double {
        // --- stage 1: high-pass, twice ---
        val hp1 = b0 * sample + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = sample
        y2 = y1; y1 = hp1
        val hp = b0 * hp1 + b1 * x1b + b2 * x2b - a1 * y1b - a2 * y2b
        x2b = x1b; x1b = hp1
        y2b = y1b; y1b = hp

        // --- stage 2: gate ---
        val level = abs(hp)
        envelope = if (level > envelope) level else envelope * envCoeff + level * (1 - envCoeff)

        noiseFloor = if (envelope < noiseFloor) {
            noiseFloor * (1 - FLOOR_DOWN) + envelope * FLOOR_DOWN
        } else {
            // Creeps up only very slowly, so a long sentence can't drag the
            // floor up to the level of the voice and gate the speech itself.
            noiseFloor * FLOOR_UP
        }.coerceAtLeast(FLOOR_MIN)

        val target = if (envelope > noiseFloor * OPEN_RATIO) 1.0 else GATE_FLOOR_GAIN
        val coeff = if (target > gain) attackCoeff else releaseCoeff
        gain = target + (gain - target) * coeff

        return hp * gain
    }
}
