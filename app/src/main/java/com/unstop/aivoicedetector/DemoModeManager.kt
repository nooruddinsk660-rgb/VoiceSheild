package com.unstop.aivoicedetector

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import kotlin.math.*
import kotlin.random.Random

/**
 * Bug #18 FIX — getDemoScore() called multiple times per window returned different values.
 *
 * `currentClipAttribution()` internally called `getDemoScore()` again, so the confidence
 * score reported in attribution ≠ detection score displayed in the UI for the same window.
 *
 * Fix: cache the score in `cachedWindowScore` and refresh it only when `currentWindow`
 * increments.  Both getDemoScore() and currentClipAttribution() now read the same value.
 */
class DemoModeManager(private val onAudioData: (FloatArray) -> Unit) {

    private val scope   = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    var currentClipIndex = 0; private set
    var currentClipName  = "";  private set
    var currentWindow    = 0;   private set

    // Bug #18 fix: single cached score per window
    private var cachedWindowScore = 0f
    private var cachedForWindow   = -1

    val clips = listOf(
        DemoClip("Real Human Caller",     fake = false, pattern = "natural",  label = ""),
        DemoClip("ElevenLabs Deepfake",   fake = true,  pattern = "vocoder",  label = "ElevenLabs"),
        DemoClip("Real Human (Noisy)",    fake = false, pattern = "natural",  label = ""),
        DemoClip("Tortoise-TTS Fake",     fake = true,  pattern = "tts",      label = "TortoiseTS"),
        DemoClip("Real-Time Voice Clone", fake = true,  pattern = "hybrid",   label = "RealTime")
    )

    fun startDemo() {
        running          = true
        currentClipIndex = 0
        playClip(0)
    }

    /**
     * Returns a deterministic score for the current window.
     * Refreshes the cached value only when the window index changes.
     */
    fun getDemoScore(): Float {
        if (cachedForWindow != currentWindow) {
            cachedWindowScore = computeFreshScore()
            cachedForWindow   = currentWindow
        }
        return cachedWindowScore
    }

    private fun computeFreshScore(): Float {
        val clip = clips.getOrNull(currentClipIndex) ?: return 0f
        return when {
            !clip.fake -> Random.nextFloat() * 0.10f

            clip.pattern == "vocoder" ->
                (0.60f + currentWindow * 0.06f).coerceIn(0f, 0.96f) + Random.nextFloat() * 0.02f

            clip.pattern == "tts" ->
                (0.55f + currentWindow * 0.05f).coerceIn(0f, 0.92f) + Random.nextFloat() * 0.02f

            clip.pattern == "hybrid" ->
                if (currentWindow < 2) Random.nextFloat() * 0.12f
                else (0.82f + currentWindow * 0.015f).coerceIn(0f, 0.97f) + Random.nextFloat() * 0.02f

            else -> 0f
        }
    }

    fun currentClipAttribution(): Attribution? {
        val clip = clips.getOrNull(currentClipIndex) ?: return null
        if (!clip.fake || clip.label.isEmpty()) return null

        // Bug #18 fix: read the same cached score rather than calling getDemoScore() again
        val score = getDemoScore()

        return when (clip.pattern) {
            "vocoder" -> Attribution("ElevenLabs", score, "Neural vocoder artefacts in 2–4 kHz band")
            "tts"     -> Attribution("TortoiseTS", score, "F0 flatness detected (pitch variance < 30 Hz)")
            "hybrid"  -> Attribution("RealTime",   score, "Inter-frame phase discontinuities detected")
            else      -> null
        }
    }

    private fun playClip(idx: Int) {
        if (!running || idx >= clips.size) { stopDemo(); return }
        currentClipIndex  = idx
        currentClipName   = clips[idx].name
        currentWindow     = 0
        cachedForWindow   = -1   // invalidate cache for new clip

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
        when (clip.pattern) {
            "natural" -> for (i in buf.indices) {
                val x = i / 16000f
                buf[i] = (0.3f * sin(2 * PI * (120f + 15f * sin(2 * PI * 0.5f * x)) * x)
                        + 0.15f * sin(2 * PI * 240f * x)
                        + (Math.random() - 0.5) * 0.06).toFloat()
            }
            "vocoder" -> {
                val amp = (0.5f + window * 0.05f).coerceAtMost(0.95f)
                for (i in buf.indices) {
                    val x = i / 16000f
                    buf[i] = (amp * 0.45f * sin(2 * PI * 150f * x)
                            + amp * 0.35f * sin(2 * PI * 3000f * x)
                            + (Math.random() - 0.5) * 0.008).toFloat()
                }
            }
            "tts" -> for (i in buf.indices) {
                val x = i / 16000f
                buf[i] = (0.40f * sin(2 * PI * 130f * x)
                        + 0.30f * sin(2 * PI * 260f * x)
                        + 0.20f * sin(2 * PI * 3900f * x) * (window * 0.08f)).toFloat()
            }
            else -> for (i in buf.indices) {
                val x = i / 16000f
                buf[i] = if (window < 2)
                    (0.3f * sin(2 * PI * 120f * x) + (Math.random() - 0.5) * 0.07).toFloat()
                else
                    (0.48f * sin(2 * PI * 148f * x) + (Math.random() - 0.5) * 0.01).toFloat()
            }
        }
        return buf
    }

    fun stopDemo() {
        running = false
        scope.coroutineContext.cancelChildren()
    }

    fun destroy() = stopDemo()
}

data class DemoClip(
    val name:    String,
    val fake:    Boolean,
    val pattern: String,
    val label:   String
)