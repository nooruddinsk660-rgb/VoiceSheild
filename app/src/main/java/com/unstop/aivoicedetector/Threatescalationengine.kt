package com.unstop.aivoicedetector

sealed class EscalationState {
    object Stable       : EscalationState()
    object Insufficient : EscalationState()
    object Declining    : EscalationState()
    data class Warning (val windowsAway: Int, val predictedScore: Float) : EscalationState()
    data class Imminent(val windowsAway: Int, val predictedScore: Float) : EscalationState()
}

data class EscalationResult(
    val state:         EscalationState,
    val slope:         Float,    // EMA units per window (rate of rise)
    val predicted5:    Float,    // predicted score in 5 windows from now
    val protectionPct: Int,      // 0–100 composite session safety score
    val threatDensity: Float,    // flagged events per minute
)

object ThreatEscalationEngine {

    private const val MIN_HISTORY = 5
    private const val LOOK_BACK   = 10  // regression window

    fun analyse(history: List<Float>, threshold: Float, session: SessionLog?): EscalationResult {
        val protection = computeProtection(session)
        val density    = computeDensity(session)

        if (history.size < MIN_HISTORY) {
            return EscalationResult(EscalationState.Insufficient, 0f,
                history.lastOrNull() ?: 0f, protection, density)
        }

        val window = history.takeLast(LOOK_BACK)
        val slope  = linearSlope(window)
        val last   = window.last()
        val p5     = (last + slope * 5f).coerceIn(0f, 1f)

        // find how many windows until threshold crossed at current slope
        val windowsUntilThreshold = if (slope > 0.001f && last < threshold) {
            ((threshold - last) / slope).toInt().coerceAtLeast(0)
        } else Int.MAX_VALUE

        val state = when {
            slope < -0.005f                   -> EscalationState.Declining
            slope < 0.005f                    -> EscalationState.Stable
            windowsUntilThreshold <= 3        -> EscalationState.Imminent(windowsUntilThreshold, p5)
            windowsUntilThreshold <= 8        -> EscalationState.Warning(windowsUntilThreshold, p5)
            else                              -> EscalationState.Stable
        }

        return EscalationResult(state, slope, p5, protection, density)
    }

    // Ordinary least squares slope over y values (x = index)
    private fun linearSlope(values: List<Float>): Float {
        val n    = values.size.toFloat()
        val xBar = (n - 1f) / 2f
        val yBar = values.average().toFloat()
        var num  = 0f; var den = 0f
        values.forEachIndexed { i, v ->
            val dx = i - xBar; num += dx * (v - yBar); den += dx * dx
        }
        return if (den < 1e-9f) 0f else num / den
    }

    // 0=worst 100=best; penalise high flag density and low window count
    private fun computeProtection(session: SessionLog?): Int {
        if (session == null) return 100
        val total   = session.totalWindowsAnalyzed.coerceAtLeast(1)
        val flagPct = (session.flaggedWindows.toFloat() / total)
        val rawPct  = ((1f - flagPct * 2f) * 100f).coerceIn(0f, 100f)
        val confPenalty = (session.maxConfidence * 30f).coerceIn(0f, 30f)
        return (rawPct - confPenalty).toInt().coerceIn(0, 100)
    }

    private fun computeDensity(session: SessionLog?): Float {
        if (session == null || session.events.isEmpty()) return 0f
        val dur = ((session.endTime ?: System.currentTimeMillis()) - session.startTime)
            .coerceAtLeast(1L) / 60_000f   // minutes
        return session.events.size / dur
    }
}