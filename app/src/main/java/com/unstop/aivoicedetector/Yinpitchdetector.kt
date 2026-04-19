package com.unstop.aivoicedetector

import kotlin.math.*

data class PitchResult(
    val frequency:  Float,
    val confidence: Float,
    val isPitchFlat: Boolean
)

class YINPitchDetector(private val sampleRate: Float = 16000f) {

    private val threshold    = 0.12f
    private val minFreq      = 75f
    private val maxFreq      = 500f
    private val historySize  = 8
    private val pitchHistory = ArrayDeque<Float>()

    fun detect(pcm: FloatArray): PitchResult {
        val windowSize = minOf(2048, pcm.size)
        if (windowSize < 256) return PitchResult(0f, 0f, false)

        val w = FloatArray(windowSize) { i ->
            pcm[i] * (0.5f - 0.5f * cos(2.0 * PI * i / (windowSize - 1)).toFloat())
        }

        val minLag = (sampleRate / maxFreq).toInt()
        val maxLag = (sampleRate / minFreq).toInt()

        val d = FloatArray(maxLag + 1)
        for (tau in 1..maxLag) {
            var sum = 0.0
            for (i in 0 until windowSize - tau) {
                val diff = (w[i] - w[i + tau]).toDouble()
                sum += diff * diff
            }
            d[tau] = sum.toFloat()
        }

        val cmndf = FloatArray(d.size)
        cmndf[0] = 1f
        var runSum = 0f
        for (tau in 1 until d.size) {
            runSum += d[tau]
            cmndf[tau] = if (runSum < 1e-9f) 1f else d[tau] * tau / runSum
        }

        var bestLag = -1
        outer@ for (tau in minLag..maxLag) {
            if (cmndf[tau] < threshold) {
                var local = tau
                while (local + 1 <= maxLag && cmndf[local + 1] < cmndf[local]) local++
                bestLag = local
                break@outer
            }
        }

        if (bestLag == -1) {
            bestLag = (minLag..maxLag).minByOrNull { cmndf[it] } ?: minLag
            if (cmndf[bestLag] > 0.45f) return PitchResult(0f, 0f, false)
        }

        val refined = parabolicInterp(cmndf, bestLag)
        val freq = (sampleRate / refined).coerceIn(minFreq, maxFreq)
        val conf = (1f - cmndf[bestLag].coerceIn(0f, 1f)).coerceIn(0f, 1f)

        if (conf > 0.4f) {
            if (pitchHistory.size >= historySize) pitchHistory.removeFirst()
            pitchHistory.addLast(freq)
        }

        val flat = isPitchSuspiciouslyFlat()
        return PitchResult(freq, conf, flat)
    }

    private fun isPitchSuspiciouslyFlat(): Boolean {
        if (pitchHistory.size < 4) return false
        val mean = pitchHistory.average().toFloat()
        val variance = pitchHistory.map { (it - mean) * (it - mean) }.average().toFloat()
        return variance < 25f
    }

    private fun parabolicInterp(d: FloatArray, tau: Int): Float {
        if (tau <= 0 || tau >= d.size - 1) return tau.toFloat()
        val s0 = d[tau - 1]; val s1 = d[tau]; val s2 = d[tau + 1]
        val denom = s0 + s2 - 2f * s1
        return if (abs(denom) < 1e-6f) tau.toFloat()
        else tau + 0.5f * (s0 - s2) / denom
    }

    fun reset() { pitchHistory.clear() }
}