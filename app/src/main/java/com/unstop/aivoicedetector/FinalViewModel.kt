package com.unstop.aivoicedetector

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*


class FinalViewModel(app: Application) : AndroidViewModel(app) {

    // ── SupervisorJob: stage crash isolated from ViewModel ───────
    private val pipeScope = CoroutineScope(
        viewModelScope.coroutineContext + SupervisorJob() + Dispatchers.Default
    )

    // ── Engine instances ─────────────────────────────────────────
    private val featureExtractor   = FeatureExtractor()
    private val bioExtractor       = BiomarkerExtractor()
    val          aasistClassifier  = AASISTClassifier(app)   // internal visible for engine label
    private val attributionEngine  = AttributionEngine()
    private val continuityEngine   = VoiceContinuityEngine()
    private val ensembleScorer     = EnsembleScorer()
    val          decisionEngine    = DecisionEngine()         // internal visible for sensitivity
    private val pitchDetector      = YINPitchDetector()
    private val threatIntel        = ThreatIntelManager(app)
    private val forensicSigner     = ForensicSigner(app)

    // ── Audio I/O ─────────────────────────────────────────────────
    private val captureHelper = AudioCaptureHelper(app)
    private var demoManager:    DemoModeManager? = null

    // ── Integration layer ─────────────────────────────────────────
    private val pipeline = FixedReactivePipeline(
        featureExtractor  = featureExtractor,
        bioExtractor      = bioExtractor,
        aasistClassifier  = aasistClassifier,
        attributionEngine = attributionEngine,
        continuityEngine  = continuityEngine,
        ensembleScorer    = ensembleScorer,
        decisionEngine    = decisionEngine,
        pitchDetector     = pitchDetector,
        threatIntel       = threatIntel,
        scope             = pipeScope,
    )

    val stateHub         = GranularStateHub(viewModelScope)
    val budgetTracker    = FrameBudgetTracker()
    val renderController = ProgressiveRenderController()

    private val jitterBuffer = JitterBuffer(
        targetPeriodMs = 500L,
        capacity       = 3,
        scope          = pipeScope,
    )

    // ── Public state flows (UI reads these) ───────────────────────
    val orbState     get() = stateHub.orb
    val waveState    get() = stateHub.wave
    val bioState     get() = stateHub.bio
    val weightState  get() = stateHub.weights
    val attrState    get() = stateHub.attr
    val escalState   get() = stateHub.escal
    val sessionState get() = stateHub.session
    val eventState   get() = stateHub.events
    val pitchState   get() = stateHub.pitch
    val headerState  get() = stateHub.header
    val sparkState   get() = stateHub.spark
    val telemetry    get() = budgetTracker.telemetry
    val renderPhase  get() = renderController.phase

    private val _forensicReport = MutableStateFlow("")
    val forensicReport: StateFlow<String> = _forensicReport

    // ── Boot sequence ─────────────────────────────────────────────
    init {
        // 1. Pipeline stages start
        pipeline.start()

        // 2. Jitter buffer starts
        jitterBuffer.start()

        // 3. Pipeline output → jitter buffer
        pipeScope.launch {
            pipeline.output.collect { frame ->
                jitterBuffer.push(frame)
            }
        }

        // 4. Jitter buffer → hub + perf + notification
        viewModelScope.launch {
            var prevId = -1
            jitterBuffer.smoothed.collect { frame ->
                val dropped = (frame.windowId - prevId - 1).coerceAtLeast(0)
                prevId = frame.windowId

                budgetTracker.record(frame, dropped)
                renderController.onFrameReceived()

                stateHub.ingest(
                    frame       = frame,
                    weights     = ensembleScorer.weights(),
                    threatCount = threatIntel.threatCount(),
                    engine      = aasistClassifier.inferenceEngine,
                    latencyMs   = frame.computeNs / 1_000_000f,
                )

                DetectionService.updateNotification(
                    getApplication(),
                    frame.kalmanScore,
                    frame.attr.takeIf { it.engine != "Unknown" }?.engine ?: "",
                )
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Mode transitions
    // ═══════════════════════════════════════════════════════════════

    fun startLive() {
        stopAll()
        resetAll()
        decisionEngine.startSession()
        DetectionService.start(getApplication())
        stateHub.setMode(DetectionMode.LIVE, "LIVE MIC", aasistClassifier.inferenceEngine)
        // AudioCaptureHelper feeds FixedReactivePipeline directly
        captureHelper.startCapture { pcm -> pipeline.push(pcm) }
    }

    fun startDemo() {
        stopAll()
        resetAll()
        decisionEngine.startSession()
        DetectionService.start(getApplication())
        stateHub.setMode(DetectionMode.DEMO, "", aasistClassifier.inferenceEngine)
        demoManager = DemoModeManager { pcm ->
            // DemoModeManager callback → FixedReactivePipeline
            pipeline.push(pcm)
        }.also { dm ->
            dm.startDemo()
            // Mirror clip name → header state every 200 ms
            viewModelScope.launch {
                while (isActive && demoManager != null) {
                    stateHub.setMode(
                        DetectionMode.DEMO,
                        dm.currentClipName,
                        aasistClassifier.inferenceEngine,
                    )
                    delay(200L)
                }
            }
        }
    }

    fun analyseFile(uri: Uri, fileName: String) {
        stopAll()
        resetAll()
        decisionEngine.startSession()
        stateHub.setMode(DetectionMode.FILE, fileName, aasistClassifier.inferenceEngine)
        DetectionService.start(getApplication())

        viewModelScope.launch(Dispatchers.IO) {
            val decoded = AudioFileDecoder.decode(getApplication(), uri, fileName)
            if (decoded == null) {
                withContext(Dispatchers.Main) { stopAll() }
                return@launch
            }
            // Push each window; jitter buffer paces output at 500ms
            decoded.windows.forEach { window ->
                pipeline.push(window)
                delay(40L)  // faster than real-time; jitter smooths output
            }
            withContext(Dispatchers.Main) {
                decisionEngine.stopSession()
                generateForensicReport()
            }
        }
    }

    fun stopAll() {
        captureHelper.stopCapture()
        demoManager?.stopDemo()
        demoManager = null
        decisionEngine.stopSession()
        DetectionService.stop(getApplication())
        stateHub.setMode(DetectionMode.IDLE)
    }

    fun dismissAlert() {
        // Re-set mode to same values — causes HeaderState to emit without showAlert
        val h = stateHub.header.value
        stateHub.setMode(h.mode, h.clipName, h.engine, showAlert = false)
    }

    fun setSensitivity(s: Sensitivity) {
        decisionEngine.currentSensitivity = s
    }

    fun generateForensicReport() {
        viewModelScope.launch(Dispatchers.IO) {
            val session = decisionEngine.currentSession ?: return@launch
            val report  = ReportGenerator.generateReport(
                session         = session,
                attribution     = stateHub.attr.value.attribution,
                biomarkers      = stateHub.bio.value.biomarkers,
                escalation      = stateHub.escal.value.escalation,
                pitchResult     = stateHub.pitch.value.pitch,
                weights         = stateHub.weights.value.weights,
                snrDb           = stateHub.session.value.snr,
                inferenceEngine = aasistClassifier.inferenceEngine,
            )
            val sig = forensicSigner.signSession(report)
            withContext(Dispatchers.Main) {
                _forensicReport.value = "$report\nSIG: $sig"
                stateHub.setForensicDone(true)
            }
        }
    }

    private fun resetAll() {
        pipeline.reset()
        budgetTracker.reset()
        renderController.reset()
        stateHub.reset()
        _forensicReport.value = ""
    }

    override fun onCleared() {
        super.onCleared()
        stopAll()
        pipeline.close()
        jitterBuffer.close()
        aasistClassifier.close()
        pipeScope.cancel()
    }
}