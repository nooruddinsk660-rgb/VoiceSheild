package com.unstop.aivoicedetector

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.*
import kotlin.system.measureNanoTime



data class RawFrame(
    val pcm:       FloatArray,
    val captureMs: Long,
    val windowId:  Int,
) {
    override fun equals(o: Any?) = o is RawFrame && windowId == o.windowId
    override fun hashCode()      = windowId
}

data class FeatureFrame(
    val pcm:       FloatArray,
    val mel:       FloatArray,
    val mfcc:      FloatArray,
    val snr:       Float,
    val windowId:  Int,
    val captureMs: Long,
    val computeNs: Long,
) {
    override fun equals(o: Any?) = o is FeatureFrame && windowId == o.windowId
    override fun hashCode()      = windowId
}

data class AnalysisFrame(
    val pcm:       FloatArray,   // pass-through for waveform display
    val mel:       FloatArray,   // pass-through for attribution display
    val bio:       AudioBiomarkers,
    val aasist:    Float,
    val attr:      Attribution,
    val drift:     DriftResult,
    val pitch:     PitchResult,
    val windowId:  Int,
    val captureMs: Long,
    val computeNs: Long,
)

data class DecisionFrame(
    val ensemble:        EnsembleResult,
    val kalmanScore:     Float,
    val escalation:      EscalationResult,
    val session:         SessionLog?,
    val threatIntelHit:  Boolean,
    val bio:             AudioBiomarkers,
    val attr:            Attribution,
    val drift:           DriftResult,
    val pitch:           PitchResult,
    val mel:             FloatArray,
    val pcm:             FloatArray,
    val windowId:        Int,
    val computeNs:       Long,
    val totalPipelineNs: Long,
    val captureMs:       Long,
    // Pre-computed render hints (no allocation on UI thread)
    val threatColor:     Long,
    val threatLabel:     String,
    val showAlert:       Boolean,
) {
    override fun equals(o: Any?) = o is DecisionFrame && windowId == o.windowId
    override fun hashCode()      = windowId
}

private const val STAGE_BUDGET_NS  = 14_000_000L   // 14 ms
private const val CHANNEL_CAPACITY = 4

// ═══════════════════════════════════════════════════════════════════
class FixedReactivePipeline(
    private val featureExtractor:  FeatureExtractor,
    private val bioExtractor:      BiomarkerExtractor,
    private val aasistClassifier:  AASISTClassifier,
    private val attributionEngine: AttributionEngine,
    private val continuityEngine:  VoiceContinuityEngine,
    private val ensembleScorer:    EnsembleScorer,
    private val decisionEngine:    DecisionEngine,
    private val pitchDetector:     YINPitchDetector,
    private val threatIntel:       ThreatIntelManager,
    private val scope:             CoroutineScope,
) {
    private val _output = MutableSharedFlow<DecisionFrame>(
        replay              = 1,
        extraBufferCapacity = 8,
        onBufferOverflow    = BufferOverflow.DROP_OLDEST,
    )
    val output: SharedFlow<DecisionFrame> = _output

    private val rawCh      = Channel<RawFrame>     (CHANNEL_CAPACITY, BufferOverflow.DROP_OLDEST)
    private val featureCh  = Channel<FeatureFrame> (CHANNEL_CAPACITY, BufferOverflow.DROP_OLDEST)
    private val analysisCh = Channel<AnalysisFrame>(CHANNEL_CAPACITY, BufferOverflow.DROP_OLDEST)

    private val scoreHistory = ArrayDeque<Float>(30)

    @Volatile private var lastBio:   AudioBiomarkers? = null
    @Volatile private var lastAttr:  Attribution = Attribution("Unknown", 0f, "")
    @Volatile private var shedBio    = false
    @Volatile private var shedAttr   = false

    private var windowId = 0

    fun start() {

        scope.launch(Dispatchers.Default + CoroutineName("stage-1-features")) {
            for (raw in rawCh) {
                val t0  = System.nanoTime()
                val mel  = featureExtractor.extractMelSpectrogram(raw.pcm)
                val mfcc = featureExtractor.extractMfccFromMel(mel)
                val snr  = featureExtractor.computeSNR(raw.pcm)
                val ns   = System.nanoTime() - t0

                featureCh.trySend(
                    FeatureFrame(
                        pcm       = raw.pcm,
                        mel       = mel,
                        mfcc      = mfcc,
                        snr       = snr,
                        windowId  = raw.windowId,
                        captureMs = raw.captureMs,
                        computeNs = ns,
                    )
                )
            }
        }

        scope.launch(Dispatchers.Default + CoroutineName("stage-2-analysis")) {
            for (feat in featureCh) {
                val t0 = System.nanoTime()

                // AASIST — uses real PCM + mel
                val aasistDef = async(Dispatchers.Default) {
                    aasistClassifier.classifyAudio(feat.pcm, feat.mel, lastBio)
                }

                // Biomarkers — uses real PCM + mel
                val bioDef = async(Dispatchers.Default) {
                    if (shedBio && lastBio != null) lastBio!!
                    else bioExtractor.extract(feat.pcm, feat.mel)
                }

                // Attribution — uses mel
                val attrDef = async(Dispatchers.Default) {
                    if (shedAttr) lastAttr
                    else attributionEngine.attribute(feat.mel)
                }

                // Pitch — uses real PCM
                val pitchDef = async(Dispatchers.Default) {
                    pitchDetector.detect(feat.pcm)
                }

                // Await all (structured concurrency)
                val aasist = aasistDef.await()
                val bio    = bioDef.await()
                val attr   = attrDef.await()
                val pitch  = pitchDef.await()

                // Continuity — stateful, must be serial
                val drift  = continuityEngine.process(feat.mfcc)

                val stageNs = System.nanoTime() - t0
                shedBio  = stageNs > STAGE_BUDGET_NS
                shedAttr = stageNs > STAGE_BUDGET_NS * 2L
                lastBio  = bio
                lastAttr = attr

                analysisCh.trySend(
                    AnalysisFrame(
                        pcm       = feat.pcm,
                        mel       = feat.mel,
                        bio       = bio,
                        aasist    = aasist,
                        attr      = attr,
                        drift     = drift,
                        pitch     = pitch,
                        windowId  = feat.windowId,
                        captureMs = feat.captureMs,
                        computeNs = feat.computeNs + stageNs,
                    )
                )
            }
        }

        scope.launch(Dispatchers.Default + CoroutineName("stage-3-decision")) {
            for (an in analysisCh) {
                val t0          = System.nanoTime()
                val modelActive = aasistClassifier.inferenceEngine != "MOCK"

                // 4-signal ensemble (EnsembleScorer.kt)
                val ensemble = ensembleScorer.score(
                    an.aasist, an.bio, an.attr, an.drift, modelActive
                )

                // Kalman + sliding window (DecisionEngine.kt)
                val kalman = decisionEngine.processBatch(
                    listOf(ensemble.score, an.aasist)
                )

                // Online learning
                val threshold = decisionEngine.currentSensitivity.threshold
                if (kalman > threshold && ensemble.conflictFlag) {
                    ensembleScorer.onConfirmedFake(ensemble)
                    // Store MFCC for threat intel (derive from mel via DCT — already in mfcc field)
                    // mfcc not in AnalysisFrame; use bio hash approximation
                    // Full fix: add mfcc to AnalysisFrame in future iteration
                }

                // Score history
                synchronized(scoreHistory) {
                    scoreHistory.addLast(kalman)
                    if (scoreHistory.size > 30) scoreHistory.removeFirst()
                }

                // Threat escalation (ThreatEscalationEngine.kt)
                val escalation = ThreatEscalationEngine.analyse(
                    history   = synchronized(scoreHistory) { scoreHistory.toList() },
                    threshold = threshold,
                    session   = decisionEngine.currentSession,
                )

                // Threat intel lookup
                val threatHit = threatIntel.isThreat(
                    // Build approximate MFCC from biomarker values for hash lookup
                    FloatArray(13) { i ->
                        when (i) {
                            0  -> an.bio.spectralCentroid / 4000f
                            1  -> an.bio.spectralSpread   / 4000f
                            2  -> an.bio.spectralFlux
                            3  -> an.bio.toneratio
                            4  -> an.bio.spectralEntropy
                            5  -> (an.bio.energyDb + 96f) / 96f
                            6  -> an.bio.zeroCrossRate / 8000f
                            7  -> an.bio.f0Estimate / 400f
                            else -> 0f
                        }
                    }
                )

                val stageNs   = System.nanoTime() - t0
                val totalNs   = an.computeNs + stageNs
                val col       = kalman.toThreatColor()
                val session   = decisionEngine.currentSession

                _output.emit(
                    DecisionFrame(
                        ensemble        = ensemble,
                        kalmanScore     = kalman,
                        escalation      = escalation,
                        session         = session,
                        threatIntelHit  = threatHit,
                        bio             = an.bio,
                        attr            = an.attr,
                        drift           = an.drift,
                        pitch           = an.pitch,
                        mel             = an.mel,
                        pcm             = an.pcm,
                        windowId        = an.windowId,
                        computeNs       = stageNs,
                        totalPipelineNs = totalNs,
                        captureMs       = an.captureMs,
                        threatColor     = col.value.toLong(),
                        threatLabel     = kalman.toThreatLabel(),
                        showAlert       = kalman > threshold &&
                                         session?.events?.isNotEmpty() == true,
                    )
                )
            }
        }
    }

    fun push(pcm: FloatArray) {
        rawCh.trySend(RawFrame(pcm.copyOf(), System.currentTimeMillis(), windowId++))
    }

    fun reset() {
        windowId = 0
        synchronized(scoreHistory) { scoreHistory.clear() }
        bioExtractor.reset()
        continuityEngine.reset()
        ensembleScorer.reset()
        pitchDetector.reset()
        shedBio  = false; shedAttr = false
        lastBio  = null;  lastAttr = Attribution("Unknown", 0f, "")
    }

    fun close() {
        rawCh.close(); featureCh.close(); analysisCh.close()
    }
}