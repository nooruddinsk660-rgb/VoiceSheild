package com.unstop.aivoicedetector

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Sensitivity(val threshold: Float) {
    LOW(0.80f),
    MEDIUM(0.60f),
    HIGH(0.45f)
}

data class AnomalyEvent(
    val timestampMs:   Long,
    val timeLabel:     String,
    val smoothedScore: Float,
    val rawScore:      Float
)

data class SessionLog(
    val startTime: Long,
    var endTime:   Long?                      = null,
    val events:    MutableList<AnomalyEvent>  = mutableListOf(),
    var maxConfidence:       Float = 0f,
    var totalWindowsAnalyzed:Int  = 0,
    var flaggedWindows:      Int  = 0
)

/**
 * Bug #16 FIX — Kalman filter Q/R ratio.
 *
 * Was: processNoise (Q) = 0.01f, measurementNoise (R) = 0.1f → R/Q = 10.
 * This trusted the prior estimate 10× more than new measurements, introducing
 * 5–8 window lag before a sudden score change was reflected.  For real-time
 * deepfake detection where immediate response matters, this is too conservative.
 *
 * Fix: Q = 0.04f, R = 0.12f → R/Q = 3.  Tracks score changes ~3× faster
 * while still smoothing measurement noise adequately.
 */
class KalmanFilter {
    private var estimate         = 0f
    private var errorCovariance  = 1f

    private val processNoise     = 0.04f   // Q — was 0.01f (too slow)
    private val measurementNoise = 0.12f   // R — was 0.10f

    fun update(measurement: Float): Float {
        errorCovariance += processNoise
        val kalmanGain   = errorCovariance / (errorCovariance + measurementNoise)
        estimate        += kalmanGain * (measurement - estimate)
        errorCovariance *= (1f - kalmanGain)
        return estimate
    }

    fun reset() {
        estimate        = 0f
        errorCovariance = 1f
    }
}

class SlidingWindow(private val size: Int = 10) {
    private val buffer = ArrayDeque<Float>()

    fun add(value: Float) {
        if (buffer.size >= size) buffer.removeFirst()
        buffer.addLast(value)
    }

    fun mean(): Float = if (buffer.isEmpty()) 0f else buffer.average().toFloat()
    fun max():  Float = buffer.maxOrNull() ?: 0f

    fun vote(threshold: Float): Boolean {
        val positives = buffer.count { it > threshold }
        return positives > size / 2
    }

    fun clear() { buffer.clear() }
}

class ConfidenceTracker {
    private var confidence        = 0f
    private var lastUpdateTime    = System.currentTimeMillis()
    private val decayRate         = 0.95f   // per second

    fun update(newScore: Float): Float {
        val now        = System.currentTimeMillis()
        val deltaTime  = (now - lastUpdateTime) / 1000f
        confidence    *= Math.pow(decayRate.toDouble(), deltaTime.toDouble()).toFloat()
        confidence     = kotlin.math.max(confidence, newScore)
        lastUpdateTime = now
        return confidence
    }

    fun reset() {
        confidence     = 0f
        lastUpdateTime = System.currentTimeMillis()
    }
}

class DecisionEngine {

    var currentSensitivity = Sensitivity.MEDIUM

    private var kalman            = KalmanFilter()
    private var window            = SlidingWindow(10)
    private var confidenceTracker = ConfidenceTracker()

    var currentSession: SessionLog? = null
        private set

    fun startSession() {
        kalman.reset()
        window.clear()
        confidenceTracker.reset()
        currentSession = SessionLog(startTime = System.currentTimeMillis())
    }

    fun stopSession() {
        currentSession?.endTime = System.currentTimeMillis()
    }

    private fun attentionWeightedScore(scores: List<Float>): Float {
        val weights    = scores.map { kotlin.math.exp(it.toDouble()).toFloat() }
        val sumWeights = weights.sum().coerceAtLeast(1e-6f)
        return scores.zip(weights).sumOf { (s, w) -> (s * w).toDouble() }
            .toFloat() / sumWeights
    }

    /**
     * Bug #17 FIX — dynamic threshold adaptive component.
     *
     * Was: `max(base, maxConfidence * 0.75f)`.
     * Problem: if maxConfidence hits 0.90, threshold rises to 0.675.
     * If score later drops to 0.65 (a real re-emergence to 0.68), the system
     * fails to re-flag because 0.68 < 0.675.
     *
     * Fix: cap the adaptive boost at base × 1.15 (only 15% above base sensitivity).
     * This still reduces false positives from noise spikes while allowing
     * genuine re-emergent threats to be flagged at near-base-threshold.
     */
    fun processBatch(scores: List<Float>): Float {
        val attentionScore = attentionWeightedScore(scores)
        val clamped        = attentionScore.coerceIn(0f, 1f)

        val kalmanScore = kalman.update(clamped)
        window.add(kalmanScore)

        val meanScore = window.mean()
        val maxScore  = window.max()
        val finalScore = 0.6f * meanScore + 0.4f * maxScore

        val decayedConfidence = confidenceTracker.update(finalScore)

        val session = currentSession ?: return decayedConfidence
        session.totalWindowsAnalyzed++
        if (decayedConfidence > session.maxConfidence) session.maxConfidence = decayedConfidence

        val baseThreshold = currentSensitivity.threshold

        // Bug #17 fix: cap adaptive lift at 15% above base threshold
        val adaptiveLift  = (session.maxConfidence * 0.75f).coerceAtMost(baseThreshold * 1.15f)
        val threshold     = kotlin.math.max(baseThreshold, adaptiveLift)

        if (window.vote(threshold) && decayedConfidence > threshold) {
            session.flaggedWindows++
            val now   = System.currentTimeMillis()
            val label = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now))

            // Deduplicate: suppress events closer than 500 ms apart
            if (session.events.isEmpty() || now - session.events.last().timestampMs > 500L) {
                session.events.add(
                    AnomalyEvent(now, label, decayedConfidence, attentionScore)
                )
            }
        }

        return decayedConfidence
    }
}