package com.unstop.aivoicedetector

import kotlin.math.*

data class EnsembleResult(
    val score:            Float,
    val aasistScore:      Float,
    val bioScore:         Float,
    val attributionScore: Float,
    val continuityScore:  Float,
    val conflictFlag:     Boolean,
    val dominantSignal:   String,
    val confidenceBand:   Float
)

class EnsembleScorer {

    private var wAasist      = 0.55f
    private var wBio         = 0.25f
    private var wAttribution = 0.10f
    private var wContinuity  = 0.10f

    private val LEARNING_RATE = 0.008f
    private var confirmedFakes = 0

    private val scoreHistory = ArrayDeque<Float>(8)

    fun score(
        aasist:      Float,
        bio:         AudioBiomarkers,
        attribution: Attribution?,
        drift:       DriftResult,
        modelActive: Boolean
    ): EnsembleResult {

        val bioScore   = computeBioScore(bio)
        val attrScore  = if (attribution != null && attribution.engine != "Unknown")
                            attribution.confidence.coerceIn(0f, 1f) else 0f
        val driftScore = if (drift.alert) ((1f - drift.similarity) * 0.85f).coerceIn(0f, 1f) else 0f

        val effectiveWAasist = if (modelActive) wAasist else 0f
        val totalW = effectiveWAasist + wBio + wAttribution + wContinuity

        val ensemble = (effectiveWAasist * aasist +
                        wBio * bioScore +
                        wAttribution * attrScore +
                        wContinuity * driftScore) / totalW.coerceAtLeast(1e-6f)

        val conflictFlag = modelActive && abs(aasist - bioScore) > 0.42f
        val finalScore = if (conflictFlag) maxOf(aasist, bioScore) else ensemble

        val dominant = when {
            driftScore > 0.5f                                   -> "CONTINUITY"
            effectiveWAasist * aasist >= wBio * bioScore &&
            effectiveWAasist * aasist >= wAttribution * attrScore -> "AASIST"
            wBio * bioScore >= wAttribution * attrScore          -> "BIOMARKER"
            attrScore > 0.1f                                     -> "ATTRIBUTION"
            else                                                 -> "ENSEMBLE"
        }

        if (scoreHistory.size >= 8) scoreHistory.removeFirst()
        scoreHistory.addLast(finalScore)
        val band = computeConfidenceBand()

        return EnsembleResult(
            finalScore.coerceIn(0f, 1f), aasist, bioScore,
            attrScore, driftScore, conflictFlag, dominant, band
        )
    }

    private fun computeBioScore(bio: AudioBiomarkers): Float {
        if (bio.energyDb < -50f) return 0f
        var s = 0f
        if (bio.spectralFlux < 0.05f && bio.spectralFlux >= 0f)
            s += (0.05f - bio.spectralFlux) / 0.05f * 0.28f
        if (bio.toneratio > 0.80f)
            s += (bio.toneratio - 0.80f) / 0.20f * 0.24f
        if (bio.spectralEntropy < 0.25f)
            s += (0.25f - bio.spectralEntropy) / 0.25f * 0.20f
        if (bio.spectralSpread < 500f)
            s += (500f - bio.spectralSpread) / 500f * 0.14f
        if (bio.f0Estimate in 90f..115f)
            s += 0.08f
        if (bio.zeroCrossRate < 200f && bio.energyDb > -40f)
            s += 0.06f
        return s.coerceIn(0f, 1f)
    }

    private fun computeConfidenceBand(): Float {
        if (scoreHistory.size < 3) return 0f
        val mean = scoreHistory.average().toFloat()
        val variance = scoreHistory.map { (it - mean) * (it - mean) }.average().toFloat()
        return sqrt(variance).coerceIn(0f, 0.5f)
    }

    fun onConfirmedFake(result: EnsembleResult) {
        confirmedFakes++
        when (result.dominantSignal) {
            "AASIST"      -> wAasist      = (wAasist + LEARNING_RATE).coerceAtMost(0.72f)
            "BIOMARKER"   -> wBio         = (wBio + LEARNING_RATE).coerceAtMost(0.50f)
            "ATTRIBUTION" -> wAttribution = (wAttribution + LEARNING_RATE).coerceAtMost(0.25f)
        }
        val total = wAasist + wBio + wAttribution + wContinuity
        wAasist /= total; wBio /= total; wAttribution /= total; wContinuity /= total
    }

    fun weights(): Map<String, Float> = mapOf(
        "AASIST" to wAasist, "BIO" to wBio,
        "ATTR" to wAttribution, "CONT" to wContinuity
    )

    fun reset() {
        wAasist = 0.55f; wBio = 0.25f; wAttribution = 0.10f; wContinuity = 0.10f
        confirmedFakes = 0; scoreHistory.clear()
    }
}