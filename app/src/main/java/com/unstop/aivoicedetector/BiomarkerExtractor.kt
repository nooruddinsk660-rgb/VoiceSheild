package com.unstop.aivoicedetector

import kotlin.math.*

data class AudioBiomarkers(
    val spectralCentroid: Float,  // Hz
    val spectralSpread:   Float,  // Hz
    val spectralFlux:     Float,  // 0-1 temporal delta
    val toneratio:        Float,  // 0-1 low-freq dominance
    val energyDb:         Float,  // dBFS
    val zeroCrossRate:    Float,  // Hz
    val f0Estimate:       Float,  // Hz autocorrelation
    val spectralEntropy:  Float,  // 0-1 disorder
    // NEW features
    val spectralFlatness: Float,  // 0-1 Wiener entropy — codec artefact indicator
    val jitter:           Float,  // F0 cycle-to-cycle variation — TTS has near-zero jitter
    val shimmer:          Float,  // amplitude cycle-to-cycle variation — TTS is too smooth
    val harmonicRatio:    Float,  // HNR: harmonic-to-noise — low = codec noise floor
    val codecScore:       Float,  // 0-1 composite codec fingerprint score
)

class BiomarkerExtractor {

    private val SR      = 16000f
    private val N_MELS  = 80
    private val TARGET_RMS = 0.08f    // normalise all windows to this RMS before extraction

    private var prevMelFrame: FloatArray? = null
    private var smoothedFlux  = 0f        // RASTA-like EMA on flux
    private val FLUX_ALPHA    = 0.72f     // smoothing factor (higher = more inertia)

    fun reset() { prevMelFrame = null; smoothedFlux = 0f }

    fun extract(rawPcm: FloatArray, melBands: FloatArray,
                spectralFlatness: Float = 0f): AudioBiomarkers {

        // Energy-normalise raw PCM before feature extraction
        val normPcm = energyNormalise(rawPcm)

        val centroid  = spectralCentroid(melBands)
        val spread    = spectralSpread(melBands, centroid)
        val rawFlux   = spectralFlux(melBands)
        val tonal     = toneratio(melBands)
        val energy    = energyDb(normPcm)   // dBFS of normalised signal
        val zcr       = zeroCrossRate(normPcm)
        val f0        = f0Estimate(normPcm)
        val entropy   = spectralEntropy(melBands)

        // RASTA-like smoothing on flux — removes single-frame noise spikes
        smoothedFlux = if (prevMelFrame == null) rawFlux
                       else FLUX_ALPHA * smoothedFlux + (1f - FLUX_ALPHA) * rawFlux

        val jit   = computeJitter(normPcm, f0)
        val shim  = computeShimmer(normPcm, f0)
        val hnr   = computeHNR(melBands)
        val codec = computeCodecScore(spectralFlatness, melBands, hnr)

        prevMelFrame = melBands.copyOf()

        return AudioBiomarkers(centroid, spread, smoothedFlux, tonal, energy, zcr,
            f0, entropy, spectralFlatness, jit, shim, hnr, codec)
    }

    private fun energyNormalise(pcm: FloatArray): FloatArray {
        if (pcm.isEmpty()) return pcm
        val rms = sqrt(pcm.map { it * it }.average()).toFloat()
        if (rms < 1e-9f) return pcm
        val gain = (TARGET_RMS / rms).coerceAtMost(20f)   // cap gain at 26dB
        return FloatArray(pcm.size) { i -> pcm[i] * gain }
    }

    private fun spectralCentroid(mel: FloatArray): Float {
        var wSum = 0f; var tSum = 0f
        mel.forEachIndexed { i, v ->
            val e = max(0f, v + 80f)
            wSum += melBinToHz(i, mel.size) * e; tSum += e
        }
        return if (tSum < 1e-9f) 0f else wSum / tSum
    }

    private fun spectralSpread(mel: FloatArray, centroid: Float): Float {
        var wVar = 0f; var tSum = 0f
        mel.forEachIndexed { i, v ->
            val e = max(0f, v + 80f); val hz = melBinToHz(i, mel.size)
            wVar += (hz - centroid).pow(2) * e; tSum += e
        }
        return if (tSum < 1e-9f) 0f else sqrt(wVar / tSum)
    }

    private fun spectralFlux(mel: FloatArray): Float {
        val prev = prevMelFrame ?: return 0f
        if (mel.size != prev.size) return smoothedFlux
        var diff = 0f
        for (i in mel.indices) diff += abs(mel[i] - prev[i])
        return (diff / mel.size / 20f).coerceIn(0f, 1f)
    }

    private fun toneratio(mel: FloatArray): Float {
        if (mel.size < 20) return 0f
        val low  = mel.slice(0 until 20).map { max(0f, it + 80f) }.average().toFloat()
        val high = mel.slice(20 until mel.size).map { max(0f, it + 80f) }
            .average().toFloat().coerceAtLeast(1e-9f)
        return (low / high / 4f).coerceIn(0f, 1f)
    }

    private fun energyDb(pcm: FloatArray): Float {
        if (pcm.isEmpty()) return -96f
        val rms = sqrt(pcm.map { it * it }.average()).toFloat()
        return if (rms < 1e-9f) -96f else (20f * log10(rms)).coerceIn(-96f, 0f)
    }

    private fun zeroCrossRate(pcm: FloatArray): Float {
        if (pcm.size < 2) return 0f
        var c = 0
        for (i in 1 until pcm.size) if ((pcm[i] >= 0f) != (pcm[i-1] >= 0f)) c++
        return (c.toFloat() * SR / pcm.size).coerceIn(0f, SR / 2f)
    }

    fun f0Estimate(pcm: FloatArray): Float {
        val ws = minOf(2048, pcm.size)
        if (ws < 128) return 0f
        val w = FloatArray(ws) { i -> pcm[i] * (0.5f - 0.5f * cos(2.0 * PI * i / (ws-1)).toFloat()) }
        val minLag = (SR / 400f).toInt(); val maxLag = (SR / 80f).toInt()
        var totalE = 0f; for (s in w) totalE += s * s
        if (totalE < 1e-6f) return 0f
        var best = minLag; var bestC = Float.MIN_VALUE
        for (lag in minLag..minOf(maxLag, ws/2)) {
            var c = 0f; for (i in 0 until ws - lag) c += w[i] * w[i + lag]
            if (c > bestC) { bestC = c; best = lag }
        }
        return if (bestC / totalE < 0.1f) 0f else (SR / best).coerceIn(80f, 400f)
    }

    private fun spectralEntropy(mel: FloatArray): Float {
        val energies = mel.map { max(0f, it + 80f) }
        val total = energies.sum().coerceAtLeast(1e-9f)
        val probs = energies.map { it / total }
        val entropy = -probs.sumOf { p -> if (p < 1e-12) 0.0 else p * log2(p.toDouble()) }
        return (entropy / log2(mel.size.toDouble())).toFloat().coerceIn(0f, 1f)
    }

    // Natural speech: 0.5–2%. TTS synthesises perfectly periodic pitch → near 0.
    private fun computeJitter(pcm: FloatArray, f0: Float): Float {
        if (f0 < 80f || pcm.size < 256) return 0f
        val periodSamples = (SR / f0).toInt().coerceAtLeast(1)
        val cycles = mutableListOf<Int>()
        var pos = periodSamples
        while (pos + periodSamples < pcm.size) {
            // Find zero-crossing near expected period boundary
            var zc = pos
            for (k in -5..5) {
                val idx = pos + k
                if (idx > 0 && idx < pcm.size - 1 &&
                    pcm[idx - 1] < 0f && pcm[idx] >= 0f) { zc = idx; break }
            }
            cycles.add(zc); pos += periodSamples
        }
        if (cycles.size < 3) return 0f
        val periods = (1 until cycles.size).map { (cycles[it] - cycles[it-1]).toFloat() }
        val meanP = periods.average().toFloat()
        val jitter = periods.map { abs(it - meanP) }.average().toFloat() / meanP
        return jitter.coerceIn(0f, 0.20f)   // cap at 20%
    }

    // Natural speech: 1–5%. TTS is amplitude-smooth → near 0.
    private fun computeShimmer(pcm: FloatArray, f0: Float): Float {
        if (f0 < 80f || pcm.size < 256) return 0f
        val periodSamples = (SR / f0).toInt().coerceAtLeast(1)
        val amps = mutableListOf<Float>()
        var pos = 0
        while (pos + periodSamples < pcm.size) {
            var peak = 0f
            for (k in 0 until periodSamples) peak = maxOf(peak, abs(pcm[pos + k]))
            amps.add(peak); pos += periodSamples
        }
        if (amps.size < 3) return 0f
        val diffs = (1 until amps.size).map { abs(amps[it] - amps[it-1]) }
        val meanA = amps.average().toFloat().coerceAtLeast(1e-9f)
        return (diffs.average().toFloat() / meanA).coerceIn(0f, 0.30f)
    }

    // Codec compression introduces a noise floor in upper bands → lowers HNR.
    private fun computeHNR(mel: FloatArray): Float {
        if (mel.size < 40) return 1f
        // Approximate: energy in voiced bands (bins 5-25, ~200-1200Hz)
        // vs energy in upper noise-prone bands (bins 60-79, >6kHz)
        val voiced = mel.slice(5..25).map { max(0f, it + 80f) }.average().toFloat()
        val noise  = mel.slice(60..minOf(79, mel.lastIndex))
            .map { max(0f, it + 80f) }.average().toFloat().coerceAtLeast(1e-9f)
        return (voiced / noise / 8f).coerceIn(0f, 1f)
    }

    // Detects OPUS/AAC/MP3-compressed TTS being replayed over microphone.
    // Three signals: (1) high spectral flatness, (2) cutoff above 7kHz,
    // (3) low HNR (codec noise floor).
    private fun computeCodecScore(flatness: Float, mel: FloatArray, hnr: Float): Float {
        var score = 0f

        // Signal 1: Wiener entropy > 0.55 suggests codec-flattened spectrum
        if (flatness > 0.55f) score += (flatness - 0.55f) / 0.45f * 0.35f

        // Signal 2: Band-limited cutoff — codec at <32kbps cuts above 7kHz
        // Mel bin 76+ ≈ >7kHz; very low energy there suggests codec cutoff
        if (mel.size > 76) {
            val highE = mel.slice(76..mel.lastIndex).map { max(0f, it + 80f) }.average().toFloat()
            val midE  = mel.slice(30..59).map { max(0f, it + 80f) }.average().toFloat().coerceAtLeast(1e-9f)
            val ratio = highE / midE
            if (ratio < 0.08f) score += (0.08f - ratio) / 0.08f * 0.35f
        }

        // Signal 3: Low HNR → codec noise floor introduced
        if (hnr < 0.35f) score += (0.35f - hnr) / 0.35f * 0.30f

        return score.coerceIn(0f, 1f)
    }

    private fun melBinToHz(bin: Int, nBins: Int): Float {
        val melMax  = 2595f * log10(1f + 8000f / 700f)
        val melFreq = melMax * bin / nBins.toFloat()
        return 700f * (10f.pow(melFreq / 2595f) - 1f)
    }

    private fun max(a: Float, b: Float) = if (a > b) a else b
}