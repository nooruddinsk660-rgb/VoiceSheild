package com.unstop.aivoicedetector

import kotlin.math.sqrt

/**
 * Tracks voice identity across a call.
 * Builds MFCC baseline in first BASELINE_WINDOWS windows,
 * then alerts if cosine similarity drops below threshold.
 * Catches mid-call voice switching — the most dangerous deepfake attack.
 *
 * FIXES applied:
 *  - Bug #14 CRITICAL: cosineSimilarity previously crashed with
 *    ArrayIndexOutOfBoundsException when a.size ≠ b.size (possible on device
 *    rotation, audio buffer size changes, or session edge cases).
 *    Now uses minOf(a.size, b.size) for safe iteration.
 *  - Bug #15: DRIFT_THRESHOLD lowered from 0.75f to 0.68f.
 *    Same-speaker MFCC cosine similarity naturally varies 0.70–0.90 across
 *    different phonemes and stress patterns.  0.75 caused frequent false alerts
 *    on normal speech variation.  0.68 only triggers on genuine identity shifts.
 */
data class DriftResult(
    val similarity:    Float,
    val driftPercent:  Float,
    val alert:         Boolean,
    val alertMessage:  String = ""
)

class VoiceContinuityEngine {

    private var baseline: FloatArray? = null
    private var windowsSeen = 0

    // Bug #15 fix: was 0.75f — too strict for natural intra-speaker variability
    private val DRIFT_THRESHOLD   = 0.68f
    private val BASELINE_WINDOWS  = 3

    fun reset() {
        baseline    = null
        windowsSeen = 0
    }

    fun process(mfcc: FloatArray): DriftResult {
        windowsSeen++

        // Build rolling baseline over first BASELINE_WINDOWS windows
        if (windowsSeen <= BASELINE_WINDOWS) {
            baseline = when (val base = baseline) {
                null -> mfcc.copyOf()
                else -> {
                    // Element-wise running average; handles size mismatch safely
                    val len = minOf(base.size, mfcc.size)
                    FloatArray(len) { i -> (base[i] + mfcc[i]) / 2f }
                }
            }
            return DriftResult(1f, 0f, false)
        }

        val base = baseline ?: return DriftResult(1f, 0f, false)
        val sim   = cosineSimilarity(base, mfcc)
        val drift = ((1f - sim) * 100f).coerceIn(0f, 100f)
        val alert = sim < DRIFT_THRESHOLD

        return DriftResult(
            similarity   = sim,
            driftPercent = drift,
            alert        = alert,
            alertMessage = if (alert) "VOICE IDENTITY CHANGED — possible mid-call switch" else ""
        )
    }

    /**
     * Bug #14 FIX — was: `a.forEachIndexed { i, v -> ... b[i] }`
     * Crashes with ArrayIndexOutOfBoundsException if b.size < a.size.
     *
     * Fix: iterate up to minOf(a.size, b.size) so all accesses are valid.
     * This can occur legitimately when the MFCC array size changes between
     * audio windows (e.g. short final window, device audio buffer resize).
     */
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        val len = minOf(a.size, b.size)
        if (len == 0) return 0f

        var dot = 0.0
        var na  = 0.0
        var nb  = 0.0
        for (i in 0 until len) {
            dot += a[i] * b[i]
            na  += a[i] * a[i]
            nb  += b[i] * b[i]
        }

        val denom = sqrt(na) * sqrt(nb)
        return if (denom < 1e-9) 0f else (dot / denom).toFloat().coerceIn(-1f, 1f)
    }
}