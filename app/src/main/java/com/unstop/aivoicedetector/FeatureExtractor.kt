package com.unstop.aivoicedetector

import be.tarsos.dsp.util.fft.FFT
import kotlin.math.*

class FeatureExtractor {

    private val sampleRate  = 16000f
    private val nFft        = 512
    private val nMels       = 80
    private val nLinear     = 40          // LFCC linear filterbank bins
    private val minFreq     = 0f
    private val maxFreq     = 8000f
    private val hopSize     = 160         // 10ms hop at 16kHz
    private val preEmphasis = 0.97f       // standard pre-emphasis coefficient

    // FFT instance — shared but protected by local buffer pattern
    private val fft = FFT(nFft)

    private val numBins = nFft / 2        // 256 — matches TarsosDSP modulus output

    // Precomputed filterbanks (computed once at construction, immutable)
    private val melFilterbank:    Array<FloatArray> = buildMelFilterbank()
    private val linearFilterbank: Array<FloatArray> = buildLinearFilterbank()

    // Hamming window coefficients
    private val hammingCoeffs = FloatArray(nFft) { j ->
        (0.54 - 0.46 * cos(2.0 * PI * j / (nFft - 1))).toFloat()
    }

    private fun hzToMel(hz: Float) = 2595f * log10(1f + hz / 700f)
    private fun melToHz(mel: Float) = 700f * (10f.pow(mel / 2595f) - 1f)

    private fun buildMelFilterbank(): Array<FloatArray> {
        val minMel = hzToMel(minFreq); val maxMel = hzToMel(maxFreq)
        val melPts = FloatArray(nMels + 2) { i -> minMel + i * (maxMel - minMel) / (nMels + 1) }
        val hzPts  = FloatArray(nMels + 2) { melToHz(melPts[it]) }
        val bins   = IntArray(nMels + 2) { i ->
            floor(hzPts[i] * nFft / sampleRate).toInt().coerceIn(0, numBins - 1)
        }
        return Array(nMels) { m ->
            val fL = bins[m]; val fC = bins[m+1]; val fR = bins[m+2]
            FloatArray(numBins) { k ->
                when {
                    k < fL    -> 0f
                    k <= fC   -> if (fC == fL) 1f else (k - fL).toFloat() / (fC - fL)
                    k <= fR   -> if (fR == fC) 0f else (fR - k).toFloat() / (fR - fC)
                    else      -> 0f
                }
            }
        }
    }

    private fun buildLinearFilterbank(): Array<FloatArray> {
        val step = maxFreq / (nLinear + 1)
        val centerHz = FloatArray(nLinear + 2) { i -> i * step }
        val bins = IntArray(nLinear + 2) { i ->
            floor(centerHz[i] * nFft / sampleRate).toInt().coerceIn(0, numBins - 1)
        }
        return Array(nLinear) { m ->
            val fL = bins[m]; val fC = bins[m+1]; val fR = bins[m+2]
            FloatArray(numBins) { k ->
                when {
                    k < fL    -> 0f
                    k <= fC   -> if (fC == fL) 1f else (k - fL).toFloat() / (fC - fL)
                    k <= fR   -> if (fR == fC) 0f else (fR - k).toFloat() / (fR - fC)
                    else      -> 0f
                }
            }
        }
    }

    fun computeSNR(signal: FloatArray): Float {
        if (signal.isEmpty()) return 0f
        val frameSize = 512; val numFrames = (signal.size / frameSize).coerceAtLeast(1)
        val rms = (0 until numFrames).map { f ->
            val s = f * frameSize; val e = minOf(s + frameSize, signal.size)
            var ss = 0f; for (i in s until e) ss += signal[i] * signal[i]
            sqrt(ss / (e - s))
        }.sorted()
        val noiseCount = (numFrames * 0.1f).toInt().coerceAtLeast(1)
        val noise  = rms.take(noiseCount).average().toFloat().coerceAtLeast(1e-9f)
        val signal2 = rms.average().toFloat().coerceAtLeast(1e-9f)
        return (20f * log10(signal2 / noise)).coerceIn(0f, 60f)
    }

    private fun applyPreEmphasis(pcm: FloatArray): FloatArray {
        val out = FloatArray(pcm.size)
        out[0] = pcm[0]
        for (i in 1 until pcm.size) out[i] = pcm[i] - preEmphasis * pcm[i - 1]
        return out
    }

    private fun computeFrameSpectrum(frame: FloatArray, localBuffer: FloatArray,
                                      amps: FloatArray) {
        for (j in frame.indices) localBuffer[j] = frame[j] * hammingCoeffs[j]
        for (j in frame.size until nFft) localBuffer[j] = 0f
        fft.forwardTransform(localBuffer)
        fft.modulus(localBuffer, amps)
    }

    fun extractMelSpectrogram(audioWindow: FloatArray): FloatArray {
        val emphasized = applyPreEmphasis(audioWindow)
        val numFrames  = ((emphasized.size - nFft) / hopSize + 1).coerceAtLeast(1)
        val melSpec    = FloatArray(numFrames * nMels)

        // LOCAL buffers — thread-safe, enables true parallel Stage 2
        val localBuf = FloatArray(nFft)
        val localAmp = FloatArray(numBins)

        for (i in 0 until numFrames) {
            val start = i * hopSize
            val frame = FloatArray(nFft) { j ->
                if (start + j < emphasized.size) emphasized[start + j] else 0f
            }
            computeFrameSpectrum(frame, localBuf, localAmp)

            for (m in 0 until nMels) {
                var sum = 0f
                melFilterbank[m].forEachIndexed { k, w -> sum += w * localAmp[k] }
                melSpec[i * nMels + m] = 20f * log10(max(sum, 1e-9f))
            }
        }
        return melSpec
    }

    fun extractMfccFromMel(mel: FloatArray): FloatArray {
        val nFrames = mel.size / nMels
        if (nFrames == 0) return FloatArray(13)
        val avgMel = FloatArray(nMels) { b ->
            var s = 0f; for (f in 0 until nFrames) s += mel[f * nMels + b]; s / nFrames
        }
        // Apply CMS — subtract long-term cepstral mean to remove channel effects
        val nMfcc = 13
        val raw = FloatArray(nMfcc) { i ->
            var s = 0.0
            for (j in 0 until nMels) s += avgMel[j] * cos(PI * i * (j + 0.5) / nMels)
            s.toFloat()
        }
        // CMS: subtract mean (c0 kept as energy feature)
        val mean = raw.drop(1).average().toFloat()
        return FloatArray(nMfcc) { i -> if (i == 0) raw[0] else raw[i] - mean }
    }

    private fun computeDeltas(features: Array<FloatArray>): Array<FloatArray> {
        val n = features.size; val d = features[0].size
        return Array(n) { i ->
            FloatArray(d) { j ->
                val n2  = (2.0 * 2 * features.getOrElse(i+2){features.last()}[j]
                        -  1.0 * 1 * features.getOrElse(i+1){features.last()}[j]
                        +  0.0
                        -  1.0 * 1 * features.getOrElse(i-1){features.first()}[j]
                        -  2.0 * 2 * features.getOrElse(i-2){features.first()}[j])
                (n2 / 10.0).toFloat()   // denominator = 2*(1²+2²) = 10
            }
        }
    }

    fun extractMfccWithDeltas(audioWindow: FloatArray): FloatArray {
        val mel    = extractMelSpectrogram(audioWindow)
        val nF     = mel.size / nMels
        val frames = Array(nF) { f -> extractMfccFromMel(mel.copyOfRange(f * nMels, (f+1) * nMels)) }
        val delta  = computeDeltas(frames)
        val delta2 = computeDeltas(delta)

        // Time-average each feature type
        val nMfcc = 13
        val mfccAvg   = FloatArray(nMfcc) { j -> frames.map { it[j] }.average().toFloat() }
        val deltaAvg  = FloatArray(nMfcc) { j -> delta.map { it[j] }.average().toFloat() }
        val delta2Avg = FloatArray(nMfcc) { j -> delta2.map { it[j] }.average().toFloat() }

        return FloatArray(39) { i ->
            when { i < 13 -> mfccAvg[i]; i < 26 -> deltaAvg[i-13]; else -> delta2Avg[i-26] }
        }
    }

    fun extractLFCC(audioWindow: FloatArray): FloatArray {
        val emphasized = applyPreEmphasis(audioWindow)
        val numFrames  = ((emphasized.size - nFft) / hopSize + 1).coerceAtLeast(1)
        val localBuf   = FloatArray(nFft); val localAmp = FloatArray(numBins)
        val nLfcc      = 20

        // Accumulate average linear spectrum
        val avgLinear = FloatArray(nLinear)
        for (i in 0 until numFrames) {
            val start = i * hopSize
            val frame = FloatArray(nFft) { j ->
                if (start + j < emphasized.size) emphasized[start + j] else 0f
            }
            computeFrameSpectrum(frame, localBuf, localAmp)
            for (m in 0 until nLinear) {
                var s = 0f; linearFilterbank[m].forEachIndexed { k, w -> s += w * localAmp[k] }
                avgLinear[m] += ln(max(s, 1e-9f).toDouble()).toFloat()
            }
        }
        val scale = 1f / numFrames
        for (m in 0 until nLinear) avgLinear[m] *= scale

        // DCT-II → LFCC coefficients
        val lfcc = FloatArray(nLfcc) { i ->
            var s = 0.0
            for (j in 0 until nLinear) s += avgLinear[j] * cos(PI * i * (j + 0.5) / nLinear)
            s.toFloat()
        }

        // CMS on LFCC
        val mean = lfcc.drop(1).average().toFloat()
        return FloatArray(nLfcc) { i -> if (i == 0) lfcc[0] else lfcc[i] - mean }
    }

    fun computeSpectralFlatness(audioWindow: FloatArray): Float {
        val emphasized = applyPreEmphasis(audioWindow)
        val localBuf   = FloatArray(nFft); val localAmp = FloatArray(numBins)
        val frame      = FloatArray(nFft) { j ->
            if (j < emphasized.size) emphasized[j] * hammingCoeffs[j] else 0f
        }
        computeFrameSpectrum(frame, localBuf, localAmp)

        var logSum = 0.0; var linSum = 0.0; var count = 0
        for (k in 1 until numBins) {
            val v = localAmp[k].toDouble().coerceAtLeast(1e-9)
            logSum += ln(v); linSum += v; count++
        }
        if (count == 0 || linSum < 1e-9) return 0f
        val geometricMean = exp(logSum / count)
        val arithmeticMean = linSum / count
        return (geometricMean / arithmeticMean).toFloat().coerceIn(0f, 1f)
    }

    fun extractMfcc(audioWindow: FloatArray): FloatArray =
        extractMfccFromMel(extractMelSpectrogram(audioWindow))

    private fun max(a: Float, b: Float) = if (a > b) a else b
}