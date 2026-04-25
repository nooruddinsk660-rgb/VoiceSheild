package com.unstop.aivoicedetector

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import kotlin.math.*
import kotlin.random.Random

class DemoModeManager(private val onAudioData: (FloatArray) -> Unit) {

    private val scope   = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    var currentClipIndex = 0; private set
    var currentClipName  = ""; private set
    var currentWindow    = 0;  private set

    private var cachedWindowScore = 0f
    private var cachedForWindow   = -1

    val clips = listOf(
        DemoClip("Real Human Caller",          fake = false, pattern = "natural",    label = ""),
        DemoClip("ElevenLabs v3 Deepfake",     fake = true,  pattern = "vocoder",    label = "ElevenLabs"),
        DemoClip("Real Human (Conversational)",fake = false, pattern = "natural_v2", label = ""),
        DemoClip("TortoiseTS Monotone",         fake = true,  pattern = "tts",        label = "TortoiseTS"),
        DemoClip("Live Voice Clone + Codec",    fake = true,  pattern = "codec_tts",  label = "Codec-TTS"),
    )

    fun startDemo() { running = true; currentClipIndex = 0; playClip(0) }

    fun getDemoScore(): Float {
        if (cachedForWindow != currentWindow) {
            cachedWindowScore = computeFreshScore(); cachedForWindow = currentWindow
        }
        return cachedWindowScore
    }

    private fun computeFreshScore(): Float {
        val clip = clips.getOrNull(currentClipIndex) ?: return 0f
        return when {
            !clip.fake -> Random.nextFloat() * 0.12f
            clip.pattern == "vocoder" ->
                (0.55f + currentWindow * 0.055f).coerceIn(0f, 0.95f) + Random.nextFloat() * 0.03f
            clip.pattern == "tts" ->
                (0.50f + currentWindow * 0.05f).coerceIn(0f, 0.92f) + Random.nextFloat() * 0.02f
            clip.pattern == "codec_tts" ->
                if (currentWindow < 3) Random.nextFloat() * 0.15f
                else (0.75f + currentWindow * 0.02f).coerceIn(0f, 0.96f) + Random.nextFloat() * 0.02f
            else -> 0f
        }
    }

    fun currentClipAttribution(): Attribution? {
        val clip = clips.getOrNull(currentClipIndex) ?: return null
        if (!clip.fake || clip.label.isEmpty()) return null
        val score = getDemoScore()
        return when (clip.pattern) {
            "vocoder"   -> Attribution("ElevenLabs", score, "ElevenLabs v3 mid-band emphasis + high-freq rolloff")
            "tts"       -> Attribution("TortoiseTS", score, "Uniform harmonic envelope — zero jitter/shimmer detected")
            "codec_tts" -> Attribution("Codec-TTS",  score, "Codec compression fingerprint (OPUS/AAC bandwidth cutoff)")
            else        -> null
        }
    }

    private fun playClip(idx: Int) {
        if (!running || idx >= clips.size) { stopDemo(); return }
        currentClipIndex = idx; currentClipName = clips[idx].name
        currentWindow = 0; cachedForWindow = -1

        scope.launch {
            repeat(12) { window ->
                if (!running) return@launch
                currentWindow = window
                onAudioData(generateAudio(clips[idx], window))
                delay(500L)
            }
            handler.postDelayed({ if (running) playClip(idx + 1) }, 1200L)
        }
    }

    private fun generateAudio(clip: DemoClip, window: Int): FloatArray {
        val buf = FloatArray(32000)
        val sr  = 16000f

        when (clip.pattern) {

            // Natural speech: formant structure (F1~800Hz, F2~1500Hz, F3~2500Hz)
            // with realistic pitch jitter (~1.5%) and shimmer (~3%)
            "natural", "natural_v2" -> {
                val f0Base = if (clip.pattern == "natural") 130f else 115f
                for (i in buf.indices) {
                    val t = i / sr
                    // Pitch with natural jitter (±1.5%)
                    val jitter    = 1f + 0.015f * sin(2f * PI * 3.7f * t).toFloat()
                    val f0        = f0Base * jitter
                    // Voiced source: sum of harmonics with decreasing amplitude
                    var voiced    = 0f
                    for (h in 1..8) {
                        val hAmp = 1f / h * (1f + 0.03f * Random.nextFloat())  // shimmer
                        voiced  += hAmp * sin(2f * PI * f0 * h * t).toFloat()
                    }
                    // Formant shaping via resonance multipliers
                    val f1 = sin(2f * PI * 800f * t).toFloat()
                    val f2 = 0.5f * sin(2f * PI * 1500f * t).toFloat()
                    val f3 = 0.25f * sin(2f * PI * 2500f * t).toFloat()
                    // Breath noise + unvoiced component
                    val noise = (Random.nextFloat() - 0.5f) * 0.06f
                    buf[i] = (voiced * 0.5f + (f1 + f2 + f3) * 0.3f + noise)
                        .coerceIn(-1f, 1f)
                }
            }

            // ElevenLabs v3: mid-band over-emphasis, smooth amplitude, high-freq rolloff
            // No natural jitter or shimmer — perfectly periodic
            "vocoder" -> {
                val amp  = (0.45f + window * 0.04f).coerceAtMost(0.90f)
                val f0   = 148f   // ElevenLabs tends to synthesise near 148Hz
                for (i in buf.indices) {
                    val t = i / sr
                    // Perfectly periodic — NO jitter (TTS hallmark)
                    var voiced = 0f
                    for (h in 1..6) voiced += (1f / h) * sin(2f * PI * f0 * h * t).toFloat()
                    // Mid-band boost (F2 region, ~1.2kHz) — ElevenLabs v3 signature
                    val midBoost = 0.8f * sin(2f * PI * 1200f * t).toFloat()
                    // High-freq cutoff — very little energy above 6kHz
                    val hf = 0.02f * sin(2f * PI * 7000f * t).toFloat()   // near-zero
                    // Near-zero noise — TTS is over-smooth
                    val noise = (Random.nextFloat() - 0.5f) * 0.005f
                    buf[i] = (amp * (voiced * 0.4f + midBoost * 0.5f + hf) + noise)
                        .coerceIn(-1f, 1f)
                }
            }

            // TortoiseTS: monotone pitch, uniform harmonic amplitudes, zero shimmer
            // isPitchFlat should trigger after 6 voiced frames
            "tts" -> {
                val f0 = 130f   // fixed monotone pitch — no variation
                for (i in buf.indices) {
                    val t = i / sr
                    // Perfectly uniform harmonics (no shimmer, no jitter)
                    var voiced = 0f
                    for (h in 1..5) voiced += (1f / h) * sin(2f * PI * f0 * h * t).toFloat()
                    // Very small noise
                    val noise = (Random.nextFloat() - 0.5f) * 0.004f
                    buf[i] = (voiced * 0.55f + noise).coerceIn(-1f, 1f)
                }
            }

            // NEW: Codec-compressed TTS — simulates OPUS 32kbps compression artifacts
            // Triggers codecScore: spectral flatness + sharp high-freq cutoff + HNR drop
            "codec_tts" -> {
                val f0 = 140f; val amp = if (window < 3) 0.3f else 0.75f
                for (i in buf.indices) {
                    val t = i / sr
                    // TTS base (clean)
                    var voiced = 0f
                    for (h in 1..5) voiced += (1f / h) * sin(2f * PI * f0 * h * t).toFloat()
                    // Codec noise floor — characteristic quantisation noise
                    val codecNoise = (Random.nextFloat() - 0.5f) * 0.04f *
                        sin(2f * PI * 3800f * t).toFloat()   // noise concentrated at codec edge
                    // Hard cutoff simulation: zero content above 7kHz
                    // (already implicit — no harmonics above 700Hz * 10 = 7kHz)
                    buf[i] = (amp * voiced + codecNoise).coerceIn(-1f, 1f)
                }
                // Apply simple codec simulation: quantise to 8-bit resolution
                if (window >= 3) {
                    for (i in buf.indices) {
                        buf[i] = (buf[i] * 128).toInt().toFloat() / 128f   // 8-bit quantisation
                    }
                }
            }

            else -> { /* no-op */ }
        }
        return buf
    }

    fun stopDemo()  { running = false; scope.coroutineContext.cancelChildren() }
    fun destroy()   = stopDemo()
}

data class DemoClip(
    val name:    String,
    val fake:    Boolean,
    val pattern: String,
    val label:   String,
)