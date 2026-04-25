package com.unstop.aivoicedetector

import kotlin.math.exp

sealed class EscalationState {
    object Stable       : EscalationState()
    object Insufficient : EscalationState()
    object Declining    : EscalationState()
    data class Warning (val windowsAway: Int, val predictedScore: Float) : EscalationState()
    data class Imminent(val windowsAway: Int, val predictedScore: Float) : EscalationState()
}

data class EscalationResult(
    val state:         EscalationState,
    val slope:         Float,
    val predicted5:    Float,
    val protectionPct: Int,
    val threatDensity: Float,
)

object ThreatEscalationEngine {

    private const val MIN_HISTORY = 8
    private const val LOOK_BACK   = 12
    private const val EWMA_ALPHA  = 0.25f // exponential weighting factor

    fun analyse(
        history:   List<Float>,
        threshold: Float,
        session:   SessionLog?,
    ): EscalationResult {
        val protection = computeProtection(session)
        val density    = computeDensity(session)

        if (history.size < MIN_HISTORY) {
            return EscalationResult(EscalationState.Insufficient, 0f,
                history.lastOrNull() ?: 0f, protection, density)
        }

        val window   = history.takeLast(LOOK_BACK)
        val smoothed = ewmaSmooth(window)
        val slope    = ewmaSlope(smoothed)
        val last     = smoothed.last()

        // Sigmoid-projected 5-window prediction — respects [0,1] naturally
        val rawPred5 = last + slope * 5f
        val p5       = sigmoid(rawPred5)

        val windowsUntilThreshold = when {
            slope > 0.001f && last < threshold ->
                ((threshold - last) / slope).toInt().coerceAtLeast(0)
            else -> Int.MAX_VALUE
        }

        val state = when {
            slope < -0.008f               -> EscalationState.Declining
            slope < 0.005f               -> EscalationState.Stable
            windowsUntilThreshold <= 3   -> EscalationState.Imminent(windowsUntilThreshold, p5)
            windowsUntilThreshold <= 8   -> EscalationState.Warning(windowsUntilThreshold, p5)
            else                         -> EscalationState.Stable
        }

        return EscalationResult(state, slope, p5, protection, density)
    }

    private fun ewmaSmooth(values: List<Float>): List<Float> {
        if (values.isEmpty()) return values
        val result = mutableListOf(values[0])
        for (i in 1 until values.size) {
            result.add(EWMA_ALPHA * values[i] + (1f - EWMA_ALPHA) * result[i - 1])
        }
        return result
    }

    // Recent observations get exponentially higher weight
    private fun ewmaSlope(values: List<Float>): Float {
        val n = values.size.toFloat()
        if (n < 2) return 0f
        var wSum = 0f; var wxSum = 0f; var wySum = 0f; var wxySum = 0f; var wxxSum = 0f
        values.forEachIndexed { i, v ->
            val w = exp(EWMA_ALPHA * (i - n + 1)).toFloat()   // exponential weight
            wSum  += w; wxSum  += w * i; wySum  += w * v
            wxySum += w * i * v; wxxSum += w * i * i
        }
        val denom = wSum * wxxSum - wxSum * wxSum
        return if (denom < 1e-9f) 0f else (wSum * wxySum - wxSum * wySum) / denom
    }

    // Converts unbounded prediction into [0,1], preventing overshoot
    private fun sigmoid(x: Float): Float = (1f / (1f + exp(-10f * (x - 0.5f)))).toFloat()

    private fun computeProtection(session: SessionLog?): Int {
        if (session == null) return 100
        val total    = session.totalWindowsAnalyzed.coerceAtLeast(1)
        val flagPct  = session.flaggedWindows.toFloat() / total
        val rawPct   = ((1f - flagPct * 2f) * 100f).coerceIn(0f, 100f)
        val confPen  = (session.maxConfidence * 30f).coerceIn(0f, 30f)
        return (rawPct - confPen).toInt().coerceIn(0, 100)
    }

    // 0.5 at 4 events/min (soft alert), 1.0 saturates at 8+ events/min.
    private fun computeDensity(session: SessionLog?): Float {
        if (session == null || session.events.isEmpty()) return 0f
        val dur  = ((session.endTime ?: System.currentTimeMillis()) - session.startTime)
            .coerceAtLeast(1L) / 60_000f
        val raw  = session.events.size / dur   // events/minute
        return (1f / (1f + exp(-(raw - 4f)))).toFloat()   // sigmoid centred at 4/min
    }
}