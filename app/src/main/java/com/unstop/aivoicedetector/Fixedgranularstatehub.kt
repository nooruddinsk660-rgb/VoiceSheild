package com.unstop.aivoicedetector

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class GranularStateHub(@Suppress("UNUSED_PARAMETER") scope: CoroutineScope) {

    // ── 10 atomic StateFlows ─────────────────────────────────────
    private val _orb     = MutableStateFlow(OrbState())
    private val _wave    = MutableStateFlow(WaveState())
    private val _bio     = MutableStateFlow(BioState())
    private val _weights = MutableStateFlow(WeightState())
    private val _attr    = MutableStateFlow(AttrState())
    private val _escal   = MutableStateFlow(EscalState())
    private val _session = MutableStateFlow(SessionState())
    private val _events  = MutableStateFlow(EventState())
    private val _pitch   = MutableStateFlow(PitchState())
    private val _header  = MutableStateFlow(HeaderState())
    private val _spark   = MutableStateFlow(SparkState())

    val orb:     StateFlow<OrbState>     = _orb
    val wave:    StateFlow<WaveState>    = _wave
    val bio:     StateFlow<BioState>     = _bio
    val weights: StateFlow<WeightState>  = _weights
    val attr:    StateFlow<AttrState>    = _attr
    val escal:   StateFlow<EscalState>   = _escal
    val session: StateFlow<SessionState> = _session
    val events:  StateFlow<EventState>   = _events
    val pitch:   StateFlow<PitchState>   = _pitch
    val header:  StateFlow<HeaderState>  = _header
    val spark:   StateFlow<SparkState>   = _spark

    // Score history for sparkline — bounded ring buffer
    private val scoreRing = ArrayDeque<Float>(200)

    // ── Primary ingestion — called once per DecisionFrame ────────
    fun ingest(
        frame:       DecisionFrame,
        weights:     Map<String, Float>,
        threatCount: Int,
        engine:      String,
        latencyMs:   Float,
    ) {
        val score = frame.kalmanScore

        // 1. Orb
        _orb.value = OrbState(
            score       = score,
            threatLabel = score.toThreatLabel(),
            escalation  = frame.escalation,
            drift       = frame.drift,
            pitch       = frame.pitch,
        )

        // 2. Wave
        _wave.value = WaveState(
            pcm      = frame.pcm,
            snr      = frame.ensemble.bioScore,   // bio score as SNR proxy for waveform coloring
            windowId = frame.windowId,
        )

        // 3. Bio
        _bio.value = BioState(
            biomarkers = frame.bio,
            score      = score,
        )

        // 4. Weights
        _weights.value = WeightState(weights = weights, score = score)

        // 5. Attribution
        _attr.value = AttrState(
            attribution   = frame.attr.takeIf { it.engine != "Unknown" },
            drift         = frame.drift,
            score         = score,
            isKnownThreat = frame.threatIntelHit,
        )

        // 6. Escalation
        _escal.value = EscalState(
            escalation = frame.escalation,
            score      = score,
        )

        // 7. Session
        _session.value = SessionState(
            session   = frame.session,
            ensemble  = frame.ensemble,
            snr       = frame.ensemble.bioScore,
            engine    = engine,
            latencyMs = latencyMs,
            score     = score,
        )

        // 8. Events
        _events.value = EventState(
            events = frame.session?.events?.toList() ?: emptyList(),
            score  = score,
        )

        // 9. Pitch
        _pitch.value = PitchState(
            pitch    = frame.pitch,
            conflict = frame.ensemble.conflictFlag,
            score    = score,
        )

        // 10. Spark (maintain rolling history)
        scoreRing.addLast(score)
        if (scoreRing.size > 200) scoreRing.removeFirst()
        _spark.value = SparkState(history = scoreRing.toList(), score = score)

        // 11. Header — carry over mode/clipName, update score + alert
        val h = _header.value
        _header.value = h.copy(
            score       = score,
            threatCount = threatCount,
            engine      = engine,
            showAlert   = frame.showAlert,
        )
    }

    // ── Mode change (no pipeline data) ───────────────────────────
    fun setMode(
        mode:      DetectionMode,
        clipName:  String  = "",
        engine:    String  = _header.value.engine,
        showAlert: Boolean = false,
    ) {
        _header.value = _header.value.copy(
            mode      = mode,
            clipName  = clipName,
            engine    = engine,
            showAlert = showAlert,
        )
    }

    // ── Forensic done flag — reserved for future badge in header ─
    @Suppress("UNUSED_PARAMETER")
    fun setForensicDone(done: Boolean) {
        // Future: emit forensic badge state to a dedicated flow
    }

    // ── Full reset (new session) ─────────────────────────────────
    fun reset() {
        val mode = _header.value.mode  // preserve mode during reset
        scoreRing.clear()
        _orb.value     = OrbState()
        _wave.value    = WaveState()
        _bio.value     = BioState()
        _weights.value = WeightState()
        _attr.value    = AttrState()
        _escal.value   = EscalState()
        _session.value = SessionState()
        _events.value  = EventState()
        _pitch.value   = PitchState()
        _spark.value   = SparkState()
        _header.value  = HeaderState(mode = mode)
    }
}