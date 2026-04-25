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
    val confidenceBand:   Float,
)

class EnsembleScorer {

    private var wAasist      = 0.55f
    private var wBio         = 0.25f
    private var wAttribution = 0.10f
    private var wContinuity  = 0.10f

    private val LEARNING_RATE       = 0.008f
    private var confirmedFakes      = 0
    private val MIN_CONFIRMS_TO_LEARN = 3
    private val MIN_SCORE_TO_LEARN    = 0.70f

    private val scoreHistory = ArrayDeque<Float>(8)

    fun score(
        aasist:      Float,
        bio:         AudioBiomarkers,
        attribution: Attribution?,
        drift:       DriftResult,
        modelActive: Boolean,
    ): EnsembleResult {

        val bioScore   = computeBioScore(bio)
        val attrScore  = attribution?.takeIf { it.engine != "Unknown" }
                            ?.confidence?.coerceIn(0f, 1f) ?: 0f
        val driftScore = if (drift.alert)
                            ((1f - drift.similarity) * 0.85f).coerceIn(0f, 1f)
                         else 0f

        val effectiveWAasist = if (modelActive) wAasist else 0f
        val totalW = (effectiveWAasist + wBio + wAttribution + wContinuity)
            .coerceAtLeast(1e-6f)

        val ensemble = (effectiveWAasist * aasist +
                        wBio            * bioScore  +
                        wAttribution    * attrScore +
                        wContinuity     * driftScore) / totalW

        val conflictFlag = modelActive && abs(aasist - bioScore) > 0.42f
        val finalScore   = if (conflictFlag) maxOf(aasist, bioScore) else ensemble

        val dominant = when {
            driftScore > 0.5f                                        -> "CONTINUITY"
            effectiveWAasist * aasist >= wBio * bioScore
            && effectiveWAasist * aasist >= wAttribution * attrScore -> "AASIST"
            wBio * bioScore >= wAttribution * attrScore              -> "BIOMARKER"
            attrScore > 0.1f                                         -> "ATTRIBUTION"
            else                                                     -> "ENSEMBLE"
        }

        if (scoreHistory.size >= 8) scoreHistory.removeFirst()
        scoreHistory.addLast(finalScore)

        return EnsembleResult(
            finalScore.coerceIn(0f, 1f),
            aasist, bioScore, attrScore, driftScore,
            conflictFlag, dominant, computeConfidenceBand()
        )
    }

    private fun computeBioScore(bio: AudioBiomarkers): Float {
        if (bio.energyDb < -50f) return 0f
        var s = 0f

        // Original 6 features
        if (bio.spectralFlux < 0.05f && bio.spectralFlux >= 0f)
            s += (0.05f - bio.spectralFlux) / 0.05f * 0.18f
        if (bio.toneratio > 0.80f)
            s += (bio.toneratio - 0.80f) / 0.20f * 0.14f
        if (bio.spectralEntropy < 0.25f)
            s += (0.25f - bio.spectralEntropy) / 0.25f * 0.12f
        if (bio.spectralSpread < 500f)
            s += (500f - bio.spectralSpread) / 500f * 0.10f
        if (bio.f0Estimate in 90f..115f) s += 0.06f
        if (bio.zeroCrossRate < 200f && bio.energyDb > -40f) s += 0.04f

        // NEW: jitter (suspiciously low = TTS) — weight 0.14
        if (bio.jitter < 0.005f && bio.f0Estimate > 80f)
            s += (0.005f - bio.jitter) / 0.005f * 0.14f

        // NEW: shimmer (suspiciously low = TTS) — weight 0.12
        if (bio.shimmer < 0.008f && bio.f0Estimate > 80f)
            s += (0.008f - bio.shimmer) / 0.008f * 0.12f

        // NEW: codec score — weight 0.10
        s += bio.codecScore * 0.10f

        return s.coerceIn(0f, 1f)
    }

    private fun computeConfidenceBand(): Float {
        if (scoreHistory.size < 3) return 0f
        val mean     = scoreHistory.average().toFloat()
        val variance = scoreHistory.map { (it - mean) * (it - mean) }.average().toFloat()
        return sqrt(variance).coerceIn(0f, 0.5f)
    }

    fun onConfirmedFake(result: EnsembleResult) {
        confirmedFakes++

        if (confirmedFakes < MIN_CONFIRMS_TO_LEARN) return
        val domScore = when (result.dominantSignal) {
            "AASIST"      -> result.aasistScore
            "BIOMARKER"   -> result.bioScore
            "ATTRIBUTION" -> result.attributionScore
            "CONTINUITY"  -> result.continuityScore
            else          -> result.score
        }
        if (domScore < MIN_SCORE_TO_LEARN) return

        when (result.dominantSignal) {
            "AASIST"      -> wAasist      = (wAasist      + LEARNING_RATE).coerceAtMost(0.72f)
            "BIOMARKER"   -> wBio         = (wBio         + LEARNING_RATE).coerceAtMost(0.50f)
            "ATTRIBUTION" -> wAttribution = (wAttribution + LEARNING_RATE).coerceAtMost(0.25f)
        }

        // NaN guard — renormalise safely
        val total = wAasist + wBio + wAttribution + wContinuity
        if (total < 1e-6f) {
            wAasist = 0.55f; wBio = 0.25f; wAttribution = 0.10f; wContinuity = 0.10f
        } else {
            wAasist /= total; wBio /= total; wAttribution /= total; wContinuity /= total
        }
    }

    /** Expose individual sub-scores for multi-signal attention in DecisionEngine. */
    fun lastSignalVector(result: EnsembleResult): List<Float> =
        listOf(result.aasistScore, result.bioScore,
               result.attributionScore, result.continuityScore)

    fun weights(): Map<String, Float> = mapOf(
        "AASIST" to wAasist, "BIO" to wBio,
        "ATTR"   to wAttribution, "CONT" to wContinuity
    )

    fun reset() {
        wAasist = 0.55f; wBio = 0.25f; wAttribution = 0.10f; wContinuity = 0.10f
        confirmedFakes = 0; scoreHistory.clear()
    }
}