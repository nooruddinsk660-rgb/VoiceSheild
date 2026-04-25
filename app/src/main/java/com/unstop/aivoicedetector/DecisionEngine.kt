package com.unstop.aivoicedetector

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

enum class Sensitivity(val threshold: Float) {
    LOW(0.80f), MEDIUM(0.60f), HIGH(0.45f)
}

data class AnomalyEvent(
    val timestampMs:   Long,
    val timeLabel:     String,
    val smoothedScore: Float,
    val rawScore:      Float,
)

data class SessionLog(
    val startTime: Long,
    var endTime:   Long?                     = null,
    val events:    MutableList<AnomalyEvent> = mutableListOf(),
    var maxConfidence:        Float = 0f,
    var totalWindowsAnalyzed: Int  = 0,
    var flaggedWindows:       Int  = 0,
)

class AdaptiveKalmanFilter {
    private var estimate         = 0f
    private var errorCovariance  = 1f
    private val processNoise     = 0.04f     // Q — process noise (rate of change)

    // Adaptive measurement noise — updated each step based on innovation
    private var measurementNoise = 0.12f     // R — starts at conservative value
    private val R_MIN            = 0.02f     // minimum R (fast response on attack)
    private val R_MAX            = 0.35f     // maximum R (strong smoothing on silence)
    private val INNOVATION_SCALE = 2.5f      // how much innovation affects R

    fun update(measurement: Float): Float {
        // Innovation = how surprising is this measurement?
        val innovation    = measurement - estimate
        val innovationSq  = innovation * innovation

        // IEKF: adapt R — large surprise → lower R (trust measurement)
        //                  small surprise → higher R (trust prior)
        measurementNoise = (R_MIN + R_MAX * exp(-innovationSq * INNOVATION_SCALE))
            .toFloat().coerceIn(R_MIN, R_MAX)

        errorCovariance  += processNoise
        val kalmanGain    = errorCovariance / (errorCovariance + measurementNoise)
        estimate         += kalmanGain * innovation
        errorCovariance  *= (1f - kalmanGain)
        return estimate.coerceIn(0f, 1f)
    }

    fun reset() { estimate = 0f; errorCovariance = 1f; measurementNoise = 0.12f }
    fun currentEstimate() = estimate
}

class SlidingWindow(private val size: Int = 10) {
    private val buffer = ArrayDeque<Float>()
    fun add(v: Float) { if (buffer.size >= size) buffer.removeFirst(); buffer.addLast(v) }
    fun mean()  = if (buffer.isEmpty()) 0f else buffer.average().toFloat()
    fun max()   = buffer.maxOrNull() ?: 0f
    fun stddev(): Float {
        if (buffer.size < 2) return 0f
        val m = mean()
        return sqrt(buffer.map { (it-m)*(it-m) }.average().toFloat())
    }
    fun vote(threshold: Float) = buffer.count { it > threshold } > size / 2
    fun clear() = buffer.clear()
}

class ConfidenceTracker {
    private var confidence     = 0f
    private var lastUpdateTime = System.currentTimeMillis()
    private val decayRate      = 0.95f

    fun update(s: Float): Float {
        val now   = System.currentTimeMillis()
        val delta = (now - lastUpdateTime) / 1000f
        confidence    *= Math.pow(decayRate.toDouble(), delta.toDouble()).toFloat()
        confidence     = max(confidence, s)
        lastUpdateTime = now
        return confidence
    }
    fun reset() { confidence = 0f; lastUpdateTime = System.currentTimeMillis() }
}

class DecisionEngine {

    var currentSensitivity = Sensitivity.MEDIUM

    private var kalman            = AdaptiveKalmanFilter()
    private var window            = SlidingWindow(10)
    private var confidenceTracker = ConfidenceTracker()

    var currentSession: SessionLog? = null
        private set

    fun startSession() {
        kalman.reset(); window.clear(); confidenceTracker.reset()
        currentSession = SessionLog(startTime = System.currentTimeMillis())
    }

    fun stopSession() { currentSession?.endTime = System.currentTimeMillis() }

    private fun attentionWeightedScore(signals: List<Float>): Float {
        if (signals.isEmpty()) return 0f
        if (signals.size == 1) return signals[0].coerceIn(0f, 1f)
        val weights    = signals.map { exp(it.toDouble() * 3.0).toFloat() }  // temperature=3 for sharper peaks
        val sumWeights = weights.sum().coerceAtLeast(1e-6f)
        return signals.zip(weights).sumOf { (s, w) -> (s * w).toDouble() }
            .toFloat() / sumWeights
    }

    fun processBatch(signals: List<Float>): Float {
        val attentionScore  = attentionWeightedScore(signals)
        val clamped         = attentionScore.coerceIn(0f, 1f)
        val kalmanScore     = kalman.update(clamped)

        window.add(kalmanScore)
        val meanScore  = window.mean()
        val maxScore   = window.max()
        val stdScore   = window.stddev()

        // Blend mean + max, attenuated by std dev (noisy = less confident)
        val finalScore = (0.6f * meanScore + 0.4f * maxScore) * (1f - stdScore * 0.3f)

        val decayedConf = confidenceTracker.update(finalScore)

        val session = currentSession ?: return decayedConf
        session.totalWindowsAnalyzed++
        if (decayedConf > session.maxConfidence) session.maxConfidence = decayedConf

        val baseThreshold = currentSensitivity.threshold
        // Adaptive lift capped at 15% above base
        val adaptiveLift  = (session.maxConfidence * 0.75f).coerceAtMost(baseThreshold * 1.15f)
        val threshold     = max(baseThreshold, adaptiveLift)

        if (window.vote(threshold) && decayedConf > threshold) {
            session.flaggedWindows++
            val now   = System.currentTimeMillis()
            val label = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now))
            if (session.events.isEmpty() || now - session.events.last().timestampMs > 500L) {
                session.events.add(AnomalyEvent(now, label, decayedConf, attentionScore))
            }
        }
        return decayedConf
    }

    fun currentKalmanEstimate() = kalman.currentEstimate()
}