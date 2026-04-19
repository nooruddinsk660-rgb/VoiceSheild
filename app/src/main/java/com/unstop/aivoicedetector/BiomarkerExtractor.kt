package com.unstop.aivoicedetector

import kotlin.math.*

data class AudioBiomarkers(
    val spectralCentroid: Float,   // Hz — weighted mean freq; TTS compresses this
    val spectralSpread:   Float,   // Hz — variance around centroid; synthetic = narrower
    val spectralFlux:     Float,   // 0-1 — TRUE frame-to-frame energy delta; TTS = unnaturally smooth
    val toneratio:        Float,   // 0-1 — low-freq dominance (0=flat, 1=very tonal)
    val energyDb:         Float,   // dBFS — overall loudness
    val zeroCrossRate:    Float,   // Hz — zero-crossing rate from raw PCM
    val f0Estimate:       Float,   // Hz — rough pitch via autocorrelation
    val spectralEntropy:  Float,   // 0-1 — disorder in spectrum; synthetic = low entropy
)

/**
 * FIXES applied:
 *  - Bug #5: spectralFlux now computes TRUE temporal flux (frame-to-frame difference)
 *            by storing the previous mel frame.  Was erroneously computing bin-to-bin
 *            variation within a single frame.
 *  - Bug #6: zeroCrossRate extra /2f removed — was reporting half the true ZCR.
 *  - Bug #7: toneratio now normalised by /4f so values spread across [0,1] instead
 *            of always collapsing to 1.0 due to low/high energy ratio always > 1.
 *  - Object → Class: state (prevMelFrame) needed for temporal flux.
 *            Call reset() at the start of every new session.
 *  - Biomarker correctness improved now that FeatureExtractor returns raw log-mel dB
 *    (Bug #2 fix), so the +80f shift correctly converts dB values to a positive scale.
 */
class BiomarkerExtractor {

    private val SR     = 16000f
    private val N_MELS = 80

    // State for temporal spectral flux — Bug #5 fix
    private var prevMelFrame: FloatArray? = null

    /** Call at every session boundary to clear stale previous-frame state. */
    fun reset() {
        prevMelFrame = null
    }

    // ── Public entry point ───────────────────────────────────────────────

    fun extract(rawPcm: FloatArray, melBands: FloatArray): AudioBiomarkers {
        val centroid = spectralCentroid(melBands)
        val spread   = spectralSpread(melBands, centroid)
        val flux     = spectralFlux(melBands)           // uses & updates prevMelFrame
        val tonal    = toneratio(melBands)
        val energy   = energyDb(rawPcm)
        val zcr      = zeroCrossRate(rawPcm)
        val f0       = f0Estimate(rawPcm)
        val entropy  = spectralEntropy(melBands)

        // Save current frame for next call's flux computation
        prevMelFrame = melBands.copyOf()

        return AudioBiomarkers(centroid, spread, flux, tonal, energy, zcr, f0, entropy)
    }

    // ── Feature implementations ──────────────────────────────────────────

    /**
     * Weighted mean frequency across mel bands.
     * Each bin's energy is the linear amplitude derived from log-mel dB (+80f shift
     * moves the floor from ≈–80 dB to ≈0, giving a non-negative linear-ish scale).
     */
    private fun spectralCentroid(mel: FloatArray): Float {
        if (mel.isEmpty()) return 0f
        var weightedSum = 0f
        var totalEnergy = 0f
        mel.forEachIndexed { i, v ->
            val e  = max(0f, v + 80f)
            val hz = melBinToHz(i, mel.size)
            weightedSum += hz * e
            totalEnergy += e
        }
        return if (totalEnergy < 1e-9f) 0f else weightedSum / totalEnergy
    }

    private fun spectralSpread(mel: FloatArray, centroid: Float): Float {
        if (mel.isEmpty()) return 0f
        var weightedVar = 0f
        var totalEnergy = 0f
        mel.forEachIndexed { i, v ->
            val e  = max(0f, v + 80f)
            val hz = melBinToHz(i, mel.size)
            weightedVar += (hz - centroid).pow(2) * e
            totalEnergy += e
        }
        return if (totalEnergy < 1e-9f) 0f else sqrt(weightedVar / totalEnergy)
    }

    /**
     * Bug #5 FIX — TRUE temporal spectral flux.
     *
     * Was: `abs(mel[i] - mel[i-1])` across bins in a SINGLE frame
     *   → measured bin-to-bin roughness, not temporal change.
     *
     * Now: Euclidean distance between the current averaged mel frame and the
     *      previous one, normalised to [0, 1].  Returns 0 on the very first window
     *      (no previous frame yet).  TTS/vocoders have unnaturally low flux (<0.05);
     *      natural speech is typically 0.10–0.35.
     */
    private fun spectralFlux(mel: FloatArray): Float {
        val prev = prevMelFrame ?: return 0f
        if (mel.size != prev.size) return 0f
        var diff = 0f
        for (i in mel.indices) diff += abs(mel[i] - prev[i])
        // Normalise: each dB difference can be ~1–20 dB; 80-bin array
        return (diff / mel.size / 20f).coerceIn(0f, 1f)
    }

    /**
     * Bug #7 FIX — toneratio normalised to [0,1].
     *
     * Was: low/high ratio coerceIn(0,1).  Since low-frequency energy almost always
     *   exceeds high-frequency in speech (ratio typically 1.5–3.0), the value was
     *   ALWAYS clamped to 1.0 — completely uninformative.
     *
     * Fix: divide by 4f so the normal speech range (1.5–2.5) maps to ~0.38–0.62,
     *   and hyper-tonal TTS (ratio >3) maps to >0.75.
     *   0 = spectrally flat (unusual); 1 = extreme low-freq dominance.
     */
    private fun toneratio(mel: FloatArray): Float {
        if (mel.size < 20) return 0f
        val low  = mel.slice(0 until 20).map { max(0f, it + 80f) }.average().toFloat()
        val high = mel.slice(20 until mel.size).map { max(0f, it + 80f) }
                     .average().toFloat().coerceAtLeast(1e-9f)
        val ratio = low / high
        return (ratio / 4f).coerceIn(0f, 1f)
    }

    private fun energyDb(pcm: FloatArray): Float {
        if (pcm.isEmpty()) return -96f
        val rms = sqrt(pcm.map { it * it }.average()).toFloat()
        return if (rms < 1e-9f) -96f else (20f * log10(rms)).coerceIn(-96f, 0f)
    }

    /**
     * Bug #6 FIX — removed erroneous `/ 2f`.
     *
     * Correct formula: ZCR (Hz) = crossings × SR / numSamples
     * Was: crossings / numSamples × SR / 2  → exactly half the true value.
     */
    private fun zeroCrossRate(pcm: FloatArray): Float {
        if (pcm.size < 2) return 0f
        var crossings = 0
        for (i in 1 until pcm.size) {
            if ((pcm[i] >= 0f) != (pcm[i - 1] >= 0f)) crossings++
        }
        // ZCR in Hz = zero-crossings per second
        return (crossings.toFloat() * SR / pcm.size).coerceIn(0f, SR / 2f)
    }

    /**
     * Autocorrelation-based F0 estimate (80–400 Hz).
     * Returns 0 if signal is unvoiced or energy is too low.
     */
    fun f0Estimate(pcm: FloatArray): Float {
        val windowSize = minOf(2048, pcm.size)
        if (windowSize < 128) return 0f
        val window = pcm.take(windowSize).toFloatArray()

        // Apply Hann window to reduce edge effects
        val hannedWindow = FloatArray(windowSize) { i ->
            window[i] * (0.5f - 0.5f * cos(2.0 * PI * i / (windowSize - 1)).toFloat())
        }

        val minLag = (SR / 400f).toInt()   // lag for 400 Hz
        val maxLag = (SR / 80f).toInt()    // lag for 80 Hz

        // Compute total energy for normalisation
        var totalEnergy = 0f
        for (s in hannedWindow) totalEnergy += s * s
        if (totalEnergy < 1e-6f) return 0f   // unvoiced / silence

        var bestLag  = minLag
        var bestCorr = Float.MIN_VALUE
        for (lag in minLag..minOf(maxLag, windowSize / 2)) {
            var corr = 0f
            for (i in 0 until windowSize - lag) corr += hannedWindow[i] * hannedWindow[i + lag]
            if (corr > bestCorr) { bestCorr = corr; bestLag = lag }
        }

        // Normalised correlation must be reasonably strong
        return if (bestCorr / totalEnergy < 0.1f) 0f
               else (SR / bestLag).coerceIn(80f, 400f)
    }

    /**
     * Spectral entropy — measures uniformity of energy distribution.
     * TTS/vocoders concentrate energy in narrow bands → low entropy.
     * Natural speech is spectrally diverse → higher entropy.
     */
    private fun spectralEntropy(mel: FloatArray): Float {
        if (mel.isEmpty()) return 1f
        val energies = mel.map { max(0f, it + 80f) }
        val total    = energies.sum().coerceAtLeast(1e-9f)
        val probs    = energies.map { it / total }
        val entropy  = -probs.sumOf { p ->
            if (p < 1e-12) 0.0 else p * log2(p.toDouble())
        }
        val maxEntropy = log2(mel.size.toDouble())
        return (entropy / maxEntropy).toFloat().coerceIn(0f, 1f)
    }

    // ── Utilities ────────────────────────────────────────────────────────

    private fun melBinToHz(bin: Int, nBins: Int): Float {
        val melMax  = 2595f * log10(1f + 8000f / 700f)
        val melFreq = melMax * bin / nBins.toFloat()
        return 700f * (10f.pow(melFreq / 2595f) - 1f)
    }

    private fun max(a: Float, b: Float) = if (a > b) a else b
}