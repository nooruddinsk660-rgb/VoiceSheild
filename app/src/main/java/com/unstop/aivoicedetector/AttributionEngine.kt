package com.unstop.aivoicedetector

import kotlin.math.abs
import kotlin.math.pow

data class Attribution(
    val engine:     String,
    val confidence: Float,
    val reason:     String,
)

class AttributionEngine {

    fun attribute(melSpec: FloatArray, codecScore: Float = 0f): Attribution {
        if (melSpec.size < 80) return Attribution("Unknown", 0f, "")

        val numFrames = melSpec.size / 80
        val m = FloatArray(80)
        for (b in 0 until 80) {
            var sum = 0f
            for (f in 0 until numFrames) sum += maxOf(0f, melSpec[f * 80 + b] + 80f)
            m[b] = sum / numFrames
        }

        val scores = mapOf(
            "ElevenLabs" to detectElevenLabsV3(m),
            "TortoiseTS" to detectTortoiseTS(m),
            "VALL-E"     to detectPhonemeGlitch(melSpec, numFrames),
            "RealTime"   to detectRealTimeClone(melSpec, numFrames),
            "Codec-TTS"  to detectCodecCompressed(m, codecScore),
        )

        val top = scores.maxByOrNull { it.value } ?: return Attribution("Unknown", 0f, "")
        if (top.value < 0.25f) return Attribution("Unknown", top.value, "")
        return Attribution(top.key, top.value, getReason(top.key))
    }

    private fun detectElevenLabsV3(m: FloatArray): Float {
        val fundamental = m.slice(5..14).average().toFloat().coerceAtLeast(1f)
        val midBand     = m.slice(15..35).average().toFloat()
        val highFreq    = m.slice(65..79).average().toFloat()
        val midRatio    = midBand / fundamental
        // ElevenLabs v3: midRatio elevated + high-freq attenuated
        var score = 0f
        if (midRatio > 1.3f) score += ((midRatio - 1.3f) / 0.7f * 0.55f).coerceAtMost(0.55f)
        if (highFreq < midBand * 0.35f) score += 0.45f * (1f - highFreq / (midBand * 0.35f).coerceAtLeast(1f))
        return score.coerceIn(0f, 1f)
    }

    private fun detectTortoiseTS(m: FloatArray): Float {
        val region   = m.slice(1..12)
        val mean     = region.average()
        val variance = region.map { (it - mean).pow(2) }.average()
        // Recalibrated: natural speech variance > 200; TortoiseTS < 120
        val flatnessScore = (1f - (variance.toFloat() / 120f)).coerceIn(0f, 1f)
        // Harmonic envelope uniformity: max/min ratio of bins 2-8
        val sub  = m.slice(2..8)
        val hMax = sub.maxOrNull() ?: 1f
        val hMin = sub.minOrNull()?.coerceAtLeast(1f) ?: 1f
        val uniformity = 1f - ((hMax - hMin) / hMax.coerceAtLeast(1f)).toFloat()
        return (flatnessScore * 0.6f + uniformity * 0.4f).coerceIn(0f, 1f)
    }

    private fun detectPhonemeGlitch(melSpec: FloatArray, numFrames: Int): Float {
        if (numFrames < 2) return 0f
        var diffSum = 0f
        for (f in 1 until numFrames) {
            var d = 0f
            for (b in 0 until 80) d += abs(melSpec[f*80+b] - melSpec[(f-1)*80+b])
            diffSum += d / 80f
        }
        return (diffSum / numFrames / 15f).coerceIn(0f, 1f)
    }

    private fun detectRealTimeClone(melSpec: FloatArray, numFrames: Int): Float {
        if (numFrames < 6) return 0f
        val n = minOf(8, numFrames / 3)
        // High-freq variance in first N frames
        var varStart = 0f
        for (f in 0 until n) {
            val hfSum = (60..79).sumOf { b -> maxOf(0f, melSpec[f*80+b] + 80f).toDouble() }.toFloat()
            varStart += hfSum
        }
        // High-freq variance in last N frames
        var varEnd = 0f
        for (f in (numFrames-n) until numFrames) {
            val hfSum = (60..79).sumOf { b -> maxOf(0f, melSpec[f*80+b] + 80f).toDouble() }.toFloat()
            varEnd += hfSum
        }
        val diff = abs(varEnd - varStart) / (varStart.coerceAtLeast(1f))
        // Real-time clones: upper-band behaviour inconsistent between start and end
        return (diff * 0.40f).coerceIn(0f, 1f)
    }

    private fun detectCodecCompressed(m: FloatArray, codecScore: Float): Float {
        // Confirm with mel band shape: steep rolloff above bin 70 (≈6.5kHz)
        if (m.size < 75) return codecScore * 0.5f
        val mid  = m.slice(30..60).average().toFloat().coerceAtLeast(1f)
        val high = m.slice(70..79).average().toFloat()
        val rolloff = 1f - (high / mid).coerceIn(0f, 1f)
        // Combined: codec biomarker score + mel-confirmed rolloff
        return (codecScore * 0.6f + rolloff * 0.4f).coerceIn(0f, 1f)
    }

    private fun getReason(engine: String) = when (engine) {
        "ElevenLabs" -> "ElevenLabs v3 mid-band emphasis + high-frequency rolloff"
        "TortoiseTS" -> "Uniform harmonic envelope in low band (TortoiseTS autoregressive synthesis)"
        "VALL-E"     -> "Phoneme boundary discontinuities (VALL-E discrete token boundaries)"
        "RealTime"   -> "Upper-band streaming artefacts (real-time voice clone latency)"
        "Codec-TTS"  -> "Codec compression fingerprint (OPUS/AAC bandwidth cutoff detected)"
        else         -> ""
    }
}