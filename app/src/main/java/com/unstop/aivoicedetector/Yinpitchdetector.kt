package com.unstop.aivoicedetector

import kotlin.math.*

data class PitchResult(
    val frequency:     Float,
    val confidence:    Float,
    val isPitchFlat:   Boolean,
    val jitterRaw:     Float = 0f,   // YIN-based instantaneous jitter estimate
    val pitchVariance: Float = 0f,   // variance over historySize windows (for UI sparkline)
)

class YINPitchDetector(private val sampleRate: Float = 16000f) {

    private val threshold   = 0.12f
    private val minFreq     = 75f
    private val maxFreq     = 500f
    private val historySize = 12
    private val MIN_VOICED_FRAMES = 6
    private val FLAT_VARIANCE_THR = 400f

    private val pitchHistory  = ArrayDeque<Float>()
    private val voicedHistory = ArrayDeque<Boolean>()  // track which frames are voiced

    fun detect(pcm: FloatArray): PitchResult {
        val windowSize = minOf(2048, pcm.size)
        if (windowSize < 256) return PitchResult(0f, 0f, false)

        // Hann-windowed frame
        val w = FloatArray(windowSize) { i ->
            pcm[i] * (0.5f - 0.5f * cos(2.0 * PI * i / (windowSize - 1)).toFloat())
        }

        val minLag = (sampleRate / maxFreq).toInt()
        val maxLag = (sampleRate / minFreq).toInt()

        // YIN difference function
        val d = FloatArray(maxLag + 1)
        for (tau in 1..maxLag) {
            var sum = 0.0
            for (i in 0 until windowSize - tau) {
                val diff = (w[i] - w[i + tau]).toDouble()
                sum += diff * diff
            }
            d[tau] = sum.toFloat()
        }

        // Cumulative mean normalised difference function
        val cmndf = FloatArray(d.size)
        cmndf[0] = 1f; var runSum = 0f
        for (tau in 1 until d.size) {
            runSum += d[tau]
            cmndf[tau] = if (runSum < 1e-9f) 1f else d[tau] * tau / runSum
        }

        // Best lag
        var bestLag = -1
        outer@ for (tau in minLag..maxLag) {
            if (cmndf[tau] < threshold) {
                var local = tau
                while (local + 1 <= maxLag && cmndf[local + 1] < cmndf[local]) local++
                bestLag = local; break@outer
            }
        }
        if (bestLag == -1) {
            bestLag = (minLag..maxLag).minByOrNull { cmndf[it] } ?: minLag
            if (cmndf[bestLag] > 0.45f) {
                voicedHistory.update(false)
                return PitchResult(0f, 0f, false)
            }
        }

        val refined = parabolicInterp(cmndf, bestLag)
        val freq    = (sampleRate / refined).coerceIn(minFreq, maxFreq)
        val conf    = (1f - cmndf[bestLag].coerceIn(0f, 1f)).coerceIn(0f, 1f)

        // Instantaneous jitter from consecutive lag difference
        val jitterRaw = if (bestLag > 1 && bestLag < maxLag) {
            abs(refined - bestLag) / refined
        } else 0f

        val isVoiced = conf > 0.4f
        voicedHistory.update(isVoiced)
        if (isVoiced) pitchHistory.update(freq)

        val pitchVar = computePitchVariance()
        val flat     = isPitchSuspiciouslyFlat()

        return PitchResult(freq, conf, flat, jitterRaw, pitchVar)
    }

    private fun isPitchSuspiciouslyFlat(): Boolean {
        if (pitchHistory.size < 4) return false
        val voicedCount = voicedHistory.count { it }
        if (voicedCount < MIN_VOICED_FRAMES) return false
        val mean     = pitchHistory.average().toFloat()
        val variance = pitchHistory.map { (it - mean) * (it - mean) }.average().toFloat()
        return variance < FLAT_VARIANCE_THR
    }

    private fun computePitchVariance(): Float {
        if (pitchHistory.size < 2) return 0f
        val mean = pitchHistory.average().toFloat()
        return pitchHistory.map { (it - mean) * (it - mean) }.average().toFloat()
    }

    private fun parabolicInterp(d: FloatArray, tau: Int): Float {
        if (tau <= 0 || tau >= d.size - 1) return tau.toFloat()
        val s0 = d[tau-1]; val s1 = d[tau]; val s2 = d[tau+1]
        val denom = s0 + s2 - 2f * s1
        return if (abs(denom) < 1e-6f) tau.toFloat() else tau + 0.5f * (s0 - s2) / denom
    }

    // Extension to update bounded deque
    private fun <T> ArrayDeque<T>.update(v: T) {
        if (size >= historySize) removeFirst(); addLast(v)
    }

    fun reset() { pitchHistory.clear(); voicedHistory.clear() }
}