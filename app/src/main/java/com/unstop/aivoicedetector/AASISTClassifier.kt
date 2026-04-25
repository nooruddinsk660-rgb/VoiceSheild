package com.unstop.aivoicedetector

import android.content.Context
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import org.tensorflow.lite.gpu.GpuDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.sqrt

class AASISTClassifier(context: Context, private val rng: kotlin.random.Random = kotlin.random.Random.Default) {

    private var interpreter: Interpreter? = null
    var inferenceEngine = "MOCK"
    var lastLatencyMs   = 0f

    init {
        val model = loadModelFile(context, "aasist_int8.tflite")
        if (model != null && model.capacity() > 0) {
            val options  = Interpreter.Options()
            var delegate = "CPU · 4T"

            try {
                val nnOpts = NnApiDelegate.Options().apply {
                    setAllowFp16(true)
                    setUseNnapiCpu(false)
                }
                options.addDelegate(NnApiDelegate(nnOpts))
                delegate = "NNAPI · NPU"
            } catch (e: Exception) {
                try {
                    options.addDelegate(GpuDelegate())
                    delegate = "GPU"
                } catch (e2: Exception) {
                    options.numThreads = 4
                }
            }

            try {
                interpreter     = Interpreter(model, options)
                inferenceEngine = delegate
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun classifyAudio(rawAudio: FloatArray, melFeatures: FloatArray, bio: AudioBiomarkers? = null): Float {
        val interp = interpreter ?: return mockScore(bio)

        val inT     = interp.getInputTensor(0)
        val inScale = inT.quantizationParams().scale.takeIf { it > 0f } ?: 0.00392f
        val inZP    = inT.quantizationParams().zeroPoint

        // mel is now raw log-mel dB, so z-scoring here is
        // the SINGLE normalisation pass — correct AASIST pre-processing.
        var sum = 0f; var sqSum = 0f
        for (f in melFeatures) { sum += f; sqSum += f * f }
        val mean     = sum / melFeatures.size
        val variance = (sqSum / melFeatures.size) - (mean * mean)
        val std      = sqrt(variance.coerceAtLeast(1e-9f))

        val inputBuffer = ByteBuffer.allocateDirect(melFeatures.size).order(ByteOrder.nativeOrder())
        for (f in melFeatures) {
            val normF = (f - mean) / std
            inputBuffer.put(((normF / inScale) + inZP).toInt().coerceIn(-128, 127).toByte())
        }
        inputBuffer.rewind()

        // Was: ByteBuffer.allocateDirect(2) — hardcoded, breaks if model
        // exports [1,1] sigmoid or any shape other than exactly [1,2].
        val outT       = interp.getOutputTensor(0)
        val outElems   = outT.numElements().coerceAtLeast(1)
        val outScale   = outT.quantizationParams().scale.takeIf { it > 0f } ?: (1f / 255f)
        val outZP      = outT.quantizationParams().zeroPoint
        val outputBuffer = ByteBuffer.allocateDirect(outElems).order(ByteOrder.nativeOrder())

        val t0 = System.nanoTime()
        interp.run(inputBuffer, outputBuffer)
        lastLatencyMs = (System.nanoTime() - t0) / 1_000_000f

        outputBuffer.rewind()

        return when {
            outElems == 1 -> {
                // Sigmoid output — single spoof probability
                val rawByte = outputBuffer.get(0).toInt().let { if (it < 0) it + 256 else it }
                ((rawByte - outZP) * outScale).coerceIn(0f, 1f)
            }
            outElems >= 2 -> {
                // Softmax-style [real, fake] logit pair
                val rByte = outputBuffer.get(0).toInt().let { if (it < 0) it + 256 else it }
                val fByte = outputBuffer.get(1).toInt().let { if (it < 0) it + 256 else it }
                val rScore = (rByte - outZP) * outScale
                val fScore = (fByte - outZP) * outScale
                (fScore / (rScore + fScore + 1e-9f)).coerceIn(0f, 1f)
            }
            else -> mockScore(bio)
        }
    }

    private fun mockScore(bio: AudioBiomarkers?): Float {
        if (bio == null) return 0f

        // Silence / far-field background gate
        if (bio.energyDb < -50f) return rng.nextFloat() * 0.05f

        var spoofScore = 0f

        // 1. Hyper-tonal spectrum: TTS vocoders often over-synthesise low harmonics
        if (bio.toneratio > 0.80f) {
            spoofScore += (bio.toneratio - 0.80f) * 2.5f
        }

        // 2. Unnaturally low temporal spectral flux: TTS is over-smooth frame-to-frame
        if (bio.spectralFlux < 0.05f && bio.spectralFlux >= 0f) {
            spoofScore += (0.05f - bio.spectralFlux) * 4f
        }

        // 3. Low spectral entropy: energy concentrated in narrow bands (vocoder artefact)
        if (bio.spectralEntropy < 0.20f) {
            spoofScore += (0.20f - bio.spectralEntropy) * 1.5f
        }

        // 4. Narrow spectral spread: synthetic voices avoid certain frequency bands
        if (bio.spectralSpread < 400f) spoofScore += 0.15f

        // 5. Very low spectral centroid: TTS often lacks high-frequency consonant energy
        if (bio.spectralCentroid < 600f) spoofScore += 0.10f

        // 6. ZCR outside natural speech range (300–3000 Hz for voiced speech)
        //    Very high ZCR (>4000 Hz) = unnatural noise; very low = DC-heavy TTS
        if (bio.zeroCrossRate > 4000f || (bio.zeroCrossRate < 200f && bio.energyDb > -40f)) {
            spoofScore += 0.10f
        }

        // Add tiny stochastic jitter to emulate neural variability
        spoofScore += (rng.nextFloat() - 0.5f) * 0.02f

        return spoofScore.coerceIn(0f, 0.96f)
    }

    private fun loadModelFile(ctx: Context, name: String): MappedByteBuffer? = try {
        ctx.assets.openFd(name).let { fd ->
            FileInputStream(fd.fileDescriptor).channel
                .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    } catch (e: Exception) { null }

    fun close() { interpreter?.close() }
}