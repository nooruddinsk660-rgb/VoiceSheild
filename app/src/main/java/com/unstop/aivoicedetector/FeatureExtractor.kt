package com.unstop.aivoicedetector

import be.tarsos.dsp.util.fft.FFT
import kotlin.math.*

/**
 * Transforms 16kHz raw audio samples into an 80-bin Log-Mel Spectrogram,
 * simulating the pre-processing required by models like AASIST/RawNet2.
 *
 * FIXES applied:
 *  - Bug #1: computeSNR now estimates actual noise floor from quietest frames
 *  - Bug #2: extractMelSpectrogram returns RAW log-mel dB (no global z-score).
 *            The AASIST classifier already applies instance normalization before
 *            INT8 quantization — double-normalizing collapsed spectral variance to ~0.
 *  - Bug #3: numBins = nFft/2 (256) matches TarsosDSP modulus output size exactly.
 *  - Bug #4: Added extractMfccFromMel() to avoid recomputing mel spectrogram twice.
 */
class FeatureExtractor {

    private val sampleRate = 16000f
    private val nFft       = 512
    private val nMels      = 80
    private val minFreq    = 0f
    private val maxFreq    = 8000f   // Nyquist for 16 kHz

    // TarsosDSP FFT instance
    private val fft = FFT(nFft)

    // Bug #3 fix: numBins = nFft/2, matching TarsosDSP modulus output length
    // (was nFft/2 + 1 = 257, causing silent Nyquist bin skip and filterbank mismatch)
    private val numBins = nFft / 2   // 256

    // Precomputed triangular Mel filterbank [nMels × numBins]
    private val melFilterbank: Array<FloatArray> = buildMelFilterbank()

    // Pre-allocated buffers — avoids GC pressure during real-time extraction
    private val frameAudio  = FloatArray(nFft)
    private val amplitudes  = FloatArray(numBins)   // 256
    private val melEnergies = FloatArray(nMels)

    // Hamming window coefficients — precomputed once
    private val hammingCoeffs = FloatArray(nFft) { j ->
        (0.54 - 0.46 * cos(2.0 * PI * j / (nFft - 1))).toFloat()
    }

    // ── Mel ↔ Hz conversions ────────────────────────────────────────────

    private fun hzToMel(hz: Float): Float = 2595f * log10(1f + hz / 700f)
    private fun melToHz(mel: Float): Float = 700f * (10f.pow(mel / 2595f) - 1f)

    // ── Filterbank ──────────────────────────────────────────────────────

    /**
     * Builds the triangular Mel filterbank matrix.
     *
     * Each of the 80 filters is a triangle centred on a Mel-spaced frequency.
     * Lower frequencies get narrower filters (finer resolution);
     * higher frequencies get wider filters — matching human hearing.
     *
     * Bug #3 fix: numBins = nFft/2 so bin indices are clamped to [0, numBins-1].
     */
    private fun buildMelFilterbank(): Array<FloatArray> {
        val minMel = hzToMel(minFreq)
        val maxMel = hzToMel(maxFreq)

        // nMels + 2 equally-spaced points on the Mel scale
        val melPoints = FloatArray(nMels + 2) { i ->
            minMel + i * (maxMel - minMel) / (nMels + 1)
        }
        val hzPoints = FloatArray(nMels + 2) { melToHz(melPoints[it]) }

        // Convert Hz to FFT bin indices, clamped to valid range
        val bins = IntArray(nMels + 2) { i ->
            floor(hzPoints[i] * nFft / sampleRate).toInt().coerceIn(0, numBins - 1)
        }

        return Array(nMels) { m ->
            val fLeft   = bins[m]
            val fCenter = bins[m + 1]
            val fRight  = bins[m + 2]
            FloatArray(numBins) { k ->
                when {
                    k < fLeft    -> 0f
                    k <= fCenter -> if (fCenter == fLeft) 1f
                                    else (k - fLeft).toFloat() / (fCenter - fLeft)
                    k <= fRight  -> if (fRight == fCenter) 0f
                                    else (fRight - k).toFloat() / (fRight - fCenter)
                    else         -> 0f
                }
            }
        }
    }

    // ── SNR ─────────────────────────────────────────────────────────────

    /**
     * Bug #1 FIX — was: `noisePower = signalPower * 0.1f` → always returned ~10 dB.
     *
     * Now: divides signal into 512-sample frames, computes per-frame RMS,
     * treats the quietest 10% as the noise floor, and measures how far the
     * overall RMS sits above it.  Returns dB in [0, 60].
     *
     * Silence / near-silence returns 0 dB; normal speech returns 20–40 dB.
     */
    fun computeSNR(signal: FloatArray): Float {
        if (signal.isEmpty()) return 0f
        val frameSize = 512
        val numFrames = (signal.size / frameSize).coerceAtLeast(1)

        // Per-frame RMS
        val frameRms = (0 until numFrames).map { f ->
            val start = f * frameSize
            val end   = minOf(start + frameSize, signal.size)
            var sumSq = 0f
            for (i in start until end) sumSq += signal[i] * signal[i]
            sqrt(sumSq / (end - start))
        }.sorted()

        // Noise floor = average of quietest 10% of frames (minimum 1 frame)
        val noiseCount = (numFrames * 0.10f).toInt().coerceAtLeast(1)
        val noiseFloor = frameRms.take(noiseCount).average().toFloat()
            .coerceAtLeast(1e-9f)

        val signalRms = frameRms.average().toFloat().coerceAtLeast(1e-9f)

        return (20f * log10(signalRms / noiseFloor)).coerceIn(0f, 60f)
    }

    // ── Log-Mel Spectrogram ──────────────────────────────────────────────

    /**
     * Converts a 2-second audio buffer (32 000 samples) to a raw Log-Mel Spectrogram.
     *
     * Returns the FLATTENED [numFrames × nMels] array in dB (typically –180 to +20 dB).
     *
     * Bug #2 FIX — was applying global z-score normalization which:
     *   (a) destroyed relative spectral shape used by every downstream consumer, and
     *   (b) double-normalised data that AASISTClassifier normalises again internally.
     * Now returns raw log-mel so each consumer (biomarkers, attribution, classifier)
     * can apply the normalization appropriate to its own task.
     */
    fun extractMelSpectrogram(audioWindow: FloatArray): FloatArray {
        val windowSize = nFft    // 512 samples
        val hopSize    = 160     // 10 ms hop at 16 kHz
        val numFrames  = ((audioWindow.size - windowSize) / hopSize + 1).coerceAtLeast(1)
        val melSpec    = FloatArray(numFrames * nMels)

        for (i in 0 until numFrames) {
            val start = i * hopSize

            // 1. Apply pre-computed Hamming window; zero-pad remainder to nFft
            for (j in 0 until windowSize) {
                frameAudio[j] = if (start + j < audioWindow.size)
                    audioWindow[start + j] * hammingCoeffs[j] else 0f
            }
            for (j in windowSize until nFft) frameAudio[j] = 0f

            // 2. FFT — result packed in frameAudio as (re, im) pairs
            fft.forwardTransform(frameAudio)
            fft.modulus(frameAudio, amplitudes)   // 256 magnitude values

            // 3. Triangular Mel filterbank
            for (m in 0 until nMels) {
                var sum    = 0f
                val filter = melFilterbank[m]
                for (k in 0 until numBins) sum += filter[k] * amplitudes[k]
                melEnergies[m] = sum
            }

            // 4. Log scale (dB) — no global normalisation (see Fix #2 above)
            for (m in 0 until nMels) {
                melSpec[i * nMels + m] = 20f * log10(max(melEnergies[m], 1e-9f))
            }
        }

        return melSpec   // raw dB values, typically –180 to +20
    }

    // ── MFCC ─────────────────────────────────────────────────────────────

    /**
     * Bug #4 FIX — accepts a pre-computed mel spectrogram so the caller
     * does NOT have to run extractMelSpectrogram twice.
     *
     * Averages the log-mel bands across time and applies DCT-II to get
     * 13 MFCC coefficients.
     */
    fun extractMfccFromMel(melSpectrogram: FloatArray): FloatArray {
        val nFrames = melSpectrogram.size / nMels
        if (nFrames == 0) return FloatArray(13)

        // Time-average across all frames
        val avgMel = FloatArray(nMels) { b ->
            var sum = 0f
            for (f in 0 until nFrames) sum += melSpectrogram[f * nMels + b]
            sum / nFrames
        }

        // DCT-II: 13 MFCC coefficients
        val nMfcc = 13
        return FloatArray(nMfcc) { i ->
            var sum = 0.0
            for (j in 0 until nMels) {
                sum += avgMel[j] * cos(PI * i * (j + 0.5) / nMels)
            }
            sum.toFloat()
        }
    }

    /**
     * Convenience overload: computes mel internally, then extracts MFCCs.
     * Use extractMfccFromMel(mel) instead when mel is already available.
     */
    fun extractMfcc(audioWindow: FloatArray): FloatArray =
        extractMfccFromMel(extractMelSpectrogram(audioWindow))
}