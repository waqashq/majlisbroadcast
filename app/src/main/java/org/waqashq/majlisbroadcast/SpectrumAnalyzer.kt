package org.waqashq.majlisbroadcast

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Phase 11e: turns a chunk of raw 16-bit PCM into per-band levels for the
 * Broadcast screen's frequency visualizer. This is a real (small) FFT, not
 * decoration -- the bars actually track which frequencies the voice is
 * hitting, unlike the previous level-history strip which only had one
 * overall peak number to work with.
 *
 * Deliberately cheap, because analyze() runs on the capture/encode thread:
 * a 512-point FFT of one already-in-memory buffer, at most once per level
 * report (~150ms), is a few tens of thousands of float ops -- negligible
 * next to the AAC encode happening on the same thread, and it never blocks
 * or allocates (all scratch buffers are reused).
 *
 * Band edges are log-spaced across [MIN_HZ, MAX_HZ] because pitch is
 * perceived logarithmically -- linear bins would put almost every voice
 * harmonic in the first two bars and leave the rest permanently flat.
 */
class SpectrumAnalyzer(private val sampleRate: Int, private val bandCount: Int) {

    companion object {
        /**
         * Power of two. 2048 samples @44.1k = ~46ms window, ~21Hz per bin.
         * 512 was tried first and was too coarse: its 86Hz bins were wider
         * than the low bands themselves, so every band below ~800Hz got
         * forced onto consecutive bins and peaks landed in the wrong bar
         * (a 1kHz tone showed up in the 660-800Hz bar).
         */
        private const val FFT_SIZE = 2048
        private const val MIN_HZ = 100.0
        private const val MAX_HZ = 8000.0
        /** Magnitudes below this (in dB, 0 = full scale) read as silence. */
        private const val FLOOR_DB = -62.0
    }

    private val re = FloatArray(FFT_SIZE)
    private val im = FloatArray(FFT_SIZE)
    private val window = FloatArray(FFT_SIZE) { i ->
        // Hann window: without it, the edges of the chunk act like a step
        // and smear energy across every bin ("spectral leakage").
        (0.5 - 0.5 * cos(2.0 * Math.PI * i / (FFT_SIZE - 1))).toFloat()
    }
    /** bandEdges[b]..bandEdges[b+1]-1 are the FFT bins belonging to band b. */
    private val bandEdges = IntArray(bandCount + 1).also { edges ->
        val nyquistBins = FFT_SIZE / 2
        val binHz = sampleRate.toDouble() / FFT_SIZE
        for (b in 0..bandCount) {
            val hz = MIN_HZ * Math.pow(MAX_HZ / MIN_HZ, b.toDouble() / bandCount)
            edges[b] = (hz / binHz).toInt().coerceIn(1, nyquistBins - 1)
        }
        // Guarantee every band owns at least one bin, so no bar is dead.
        for (b in 1..bandCount) {
            if (edges[b] <= edges[b - 1]) edges[b] = (edges[b - 1] + 1).coerceAtMost(nyquistBins)
        }
    }

    /**
     * Fills [out] (size [bandCount]) with 0-100 levels for the newest
     * FFT_SIZE samples of [pcm] (little-endian 16-bit mono, [byteCount]
     * valid bytes). Leaves [out] untouched and returns false if there
     * aren't enough samples yet.
     */
    fun analyze(pcm: ByteArray, byteCount: Int, out: IntArray): Boolean {
        val samples = byteCount / 2
        if (samples < FFT_SIZE) return false
        // Newest window in the buffer -- the most recent audio is what the
        // meter should reflect.
        val startSample = samples - FFT_SIZE
        for (i in 0 until FFT_SIZE) {
            val idx = (startSample + i) * 2
            val s = ((pcm[idx + 1].toInt() shl 8) or (pcm[idx].toInt() and 0xFF)).toShort().toInt()
            re[i] = (s / 32768f) * window[i]
            im[i] = 0f
        }
        fft(re, im)

        for (b in 0 until bandCount) {
            val from = bandEdges[b]
            val to = bandEdges[b + 1].coerceAtLeast(from + 1)
            // Strongest bin in the band rather than the average: averaging
            // dilutes a narrow peak across the band's bins and leaves visible
            // gaps between bars on tonal sounds.
            var mag = 0.0
            for (bin in from until to) {
                // Single-sided amplitude: 2/N scaling, and the Hann window
                // removes half the energy on average (coherent gain 0.5),
                // hence the extra x2.
                val m = hypot(re[bin].toDouble(), im[bin].toDouble()) * 4.0 / FFT_SIZE
                if (m > mag) mag = m
            }
            val db = 20.0 * log10(mag + 1e-9)
            val norm = ((db - FLOOR_DB) / -FLOOR_DB).coerceIn(0.0, 1.0)
            // sqrt lifts quiet detail without letting loud bands clip flat,
            // so normal speech uses most of the bar height.
            out[b] = (sqrt(norm) * 100.0).toInt().coerceIn(0, 100)
        }
        return true
    }

    /** In-place iterative radix-2 Cooley-Tukey FFT; size must be a power of two. */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        // Bit-reversal permutation.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wRe = cos(ang).toFloat()
            val wIm = kotlin.math.sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var curRe = 1f
                var curIm = 0f
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val vIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe
                    im[i + k + len / 2] = uIm - vIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** True if every band is effectively silent -- used to park the visualizer at idle. */
    fun isSilent(out: IntArray): Boolean = out.all { abs(it) < 2 }
}
