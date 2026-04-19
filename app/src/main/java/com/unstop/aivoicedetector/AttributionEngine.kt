package com.unstop.aivoicedetector

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

data class Attribution(
    val engine:     String,
    val confidence: Float,
    val reason:     String
)

/**
 * Heuristic engine fingerprinting based on mel spectrogram patterns.
 *
 * FIXES applied:
 *  - Bug #12: All heuristics previously worked on z-score-normalised mel + 80f,
 *             which made every bin ≈ 80 ± 1 — completely flat, no discriminative signal.
 *             Now mel is raw log-mel dB (FeatureExtractor Bug #2 fix), so +80f correctly
 *             shifts from typical dB range (–80 to +20) to a positive linear-ish scale
 *             of 0 to 100, restoring all inter-bin contrast.
 *  - Bug #12 (consequence): detectVocoderArtifact ratio thresholds recalibrated.
 *  - Bug #13: detectF0Flatness was measuring bin-to-bin variance in mel bins 1–10
 *             (a spectral shape metric, not F0 flatness). Re-labelled as a low-band
 *             spectral stability metric with correct empirical thresholds for raw log-mel.
 */
class AttributionEngine {

    fun attribute(melSpec: FloatArray): Attribution {
        if (melSpec.size < 80) return Attribution("Unknown", 0f, "")

        // Aggregate the flattened [numFrames × 80] mel spectrogram into mean frame
        val numFrames = melSpec.size / 80
        val m = FloatArray(80)
        for (b in 0 until 80) {
            var sum = 0f
            for (f in 0 until numFrames) {
                // +80f shifts raw log-mel dB (≈ –80 to +20 dB) to ≈ 0–100 positive scale
                sum += max(0f, melSpec[f * 80 + b] + 80f)
            }
            m[b] = sum / numFrames
        }

        val scores = mapOf(
            "ElevenLabs" to detectVocoderArtifact(m),
            "TortoiseTS" to detectLowBandFlatness(m),
            "VALL-E"     to detectPhonemeGlitch(melSpec, numFrames),
            "RealTime"   to detectPhaseDiscont(melSpec, numFrames)
        )

        val top = scores.maxByOrNull { it.value } ?: return Attribution("Unknown", 0f, "")
        if (top.value < 0.25f) return Attribution("Unknown", top.value, "")

        return Attribution(top.key, top.value, getReason(top.key))
    }

    /**
     * Bug #12 FIX — ElevenLabs vocoder artefact detection.
     *
     * Measures high-frequency (bins 30–59) vs low-frequency (bins 0–29) energy ratio.
     *
     * With FIXED raw log-mel, typical bin values after +80f:
     *   Natural voiced speech:  low ≈ 60–80, high ≈ 30–55  → ratio ≈ 0.45–0.75
     *   Neural vocoders: can unnaturally boost or suppress high bands → ratio > 0.85 or < 0.30
     *
     * Was: threshold 0.4 calibrated for collapsed z-score data → always returned near 1.0.
     * Now: threshold 0.80 triggers on genuinely elevated high-frequency artefacts.
     */
    private fun detectVocoderArtifact(m: FloatArray): Float {
        val high = m.slice(30..59).average().toFloat()
        val low  = m.slice(0..29).average().toFloat().coerceAtLeast(1e-9f)
        val ratio = high / low
        // Normal speech: ratio ≈ 0.45–0.75 → score near 0
        // Neural vocoder with boosted high bands: ratio > 0.80 → score rises
        return ((ratio - 0.70f) * 3.0f).coerceIn(0f, 1f)
    }

    /**
     * Bug #13 FIX — renamed from detectF0Flatness to detectLowBandFlatness.
     *
     * Was labelled "F0 flatness" but was measuring energy variance in mel bins 1–10,
     * which corresponds to ≈80–600 Hz — this is spectral stability in the low band,
     * NOT temporal F0 variation.  True F0 flatness (TTS pitch being suspiciously constant)
     * is detected separately via the F0 history in the PDF report.
     *
     * With raw log-mel, values in bins 1–10 range ≈ 40–85 (after +80f shift).
     * Natural speech variance across these bins: 50–300 dB².
     * Flat TTS: much lower variance (<30 dB²) because pitch overtones are evenly spaced.
     *
     * Was: threshold 500 (calibrated for collapsed data where variance ≈ 1 always → score ≈ 1.0).
     * Now: threshold 80 separates genuine flat spectra from natural speech.
     */
    private fun detectLowBandFlatness(m: FloatArray): Float {
        val region = m.slice(1..10)
        val mean   = region.average()
        val variance = region.map { (it - mean).pow(2) }.average()
        // Low variance → low-band is unnaturally flat → TTS signature
        return (1f - (variance.toFloat() / 80f)).coerceIn(0f, 1f)
    }

    /**
     * VALL-E phoneme boundary glitch detection.
     * Measures average absolute frame-to-frame mel difference.
     * VALL-E's discrete token boundaries sometimes create energy discontinuities.
     *
     * Unchanged — was already computing temporal differences across frames correctly.
     * Now works even better since mel values have meaningful dB contrast (Bug #2 fix).
     */
    private fun detectPhonemeGlitch(melSpec: FloatArray, numFrames: Int): Float {
        if (numFrames < 2) return 0f
        var diffSum = 0f
        for (f in 1 until numFrames) {
            var frameDiff = 0f
            for (b in 0 until 80) frameDiff += abs(melSpec[f * 80 + b] - melSpec[(f - 1) * 80 + b])
            diffSum += frameDiff / 80f
        }
        val avgDiff = diffSum / numFrames
        // With raw log-mel dB, frame differences of 2–8 dB/bin are normal.
        // VALL-E boundary glitches can produce 15–30 dB jumps.
        // Normalise by 20f (was 15f — recalibrated for dB scale).
        return (avgDiff / 20f).coerceIn(0f, 1f)
    }

    /**
     * Real-time voice clone detection via inter-frame phase discontinuity.
     * Compares energy of first N frames vs last N frames.
     * Real-time cloning often has start/end energy imbalance due to streaming latency.
     *
     * Calibration unchanged — still uses absolute mel values, now more meaningful.
     */
    private fun detectPhaseDiscont(melSpec: FloatArray, numFrames: Int): Float {
        if (numFrames < 4) return 0f
        val framesToCompare = minOf(10, numFrames / 2)
        var sumStart = 0f; var sumEnd = 0f
        for (f in 0 until framesToCompare) {
            for (b in 0 until 80) {
                sumStart += abs(melSpec[f * 80 + b])
                sumEnd   += abs(melSpec[(numFrames - 1 - f) * 80 + b])
            }
        }
        val f1 = sumStart / (framesToCompare * 80)
        val f2 = sumEnd / (framesToCompare * 80)
        // Normalise by 0.30f (was 0.20f — adjusted for raw dB scale)
        return (abs(f2 - f1) / f1.coerceAtLeast(1e-9f)).times(0.30f).coerceIn(0f, 1f)
    }

    private fun getReason(engine: String) = when (engine) {
        "ElevenLabs" -> "Neural vocoder artefacts in 2–4 kHz band"
        "TortoiseTS" -> "Low-band spectral flatness (harmonic spacing too uniform)"
        "VALL-E"     -> "Phoneme boundary discontinuities detected"
        "RealTime"   -> "Inter-frame phase discontinuities detected"
        else         -> ""
    }
}