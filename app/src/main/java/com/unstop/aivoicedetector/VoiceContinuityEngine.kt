package com.unstop.aivoicedetector

import kotlin.math.sqrt

class VoiceContinuityEngine {

    private var baseline:    FloatArray? = null
    private var windowsSeen  = 0
    private val DRIFT_THRESHOLD  = 0.62f    // recalibrated for 39-dim feature space
    private val BASELINE_WINDOWS = 15
    private val EMA_ALPHA        = 0.15f    // baseline update rate after lock-in

    private val simHistory = ArrayDeque<Float>(30)

    fun reset() { baseline = null; windowsSeen = 0; simHistory.clear() }

    /**
     * Process one 39-dim MFCC+delta+delta-delta vector.
     * Falls back gracefully to 13-dim MFCC if full vector not available.
     */
    fun process(features: FloatArray): DriftResult {
        windowsSeen++
        val normed = zNorm(features)

        if (windowsSeen <= BASELINE_WINDOWS) {
            baseline = when (val base = baseline) {
                null -> normed.copyOf()
                else -> {
                    // EMA update — recent windows weighted more than early ones
                    val len = minOf(base.size, normed.size)
                    FloatArray(len) { i ->
                        (1f - EMA_ALPHA) * base[i] + EMA_ALPHA * normed[i]
                    }
                }
            }
            simHistory.addLast(1f)
            return DriftResult(1f, 0f, false)
        }

        val base = baseline ?: return DriftResult(1f, 0f, false)
        val sim  = cosineSimilarity(base, normed)

        // Continue EMA update of baseline — tracks slow voice changes (fatigue, emotion)
        // but not sudden swaps (deepfake attack)
        val len2 = minOf(base.size, normed.size)
        if (sim > DRIFT_THRESHOLD) {
            // Only update baseline when voice matches — prevents poisoning on attack
            baseline = FloatArray(len2) { i ->
                (1f - EMA_ALPHA) * base[i] + EMA_ALPHA * normed[i]
            }
        }

        val drift = ((1f - sim) * 100f).coerceIn(0f, 100f)
        val alert = sim < DRIFT_THRESHOLD

        if (simHistory.size >= 30) simHistory.removeFirst()
        simHistory.addLast(sim)

        return DriftResult(
            similarity   = sim,
            driftPercent = drift,
            alert        = alert,
            alertMessage = if (alert) "VOICE IDENTITY CHANGED — possible mid-call switch" else "",
        )
    }

    fun similarityHistory(): List<Float> = simHistory.toList()

    // Removes global energy/loudness bias from cosine comparison
    private fun zNorm(v: FloatArray): FloatArray {
        if (v.isEmpty()) return v
        val mean = v.average().toFloat()
        val std  = sqrt(v.map { (it - mean) * (it - mean) }.average().toFloat())
            .coerceAtLeast(1e-8f)
        return FloatArray(v.size) { i -> (v[i] - mean) / std }
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        val len = minOf(a.size, b.size)
        if (len == 0) return 0f
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in 0 until len) { dot += a[i]*b[i]; na += a[i]*a[i]; nb += b[i]*b[i] }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom < 1e-9) 0f else (dot / denom).toFloat().coerceIn(-1f, 1f)
    }
}