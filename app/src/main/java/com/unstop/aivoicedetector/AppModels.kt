package com.unstop.aivoicedetector


enum class DetectionMode { LIVE, DEMO, FILE, IDLE }

data class DriftResult(
    val similarity:   Float,
    val driftPercent: Float,
    val alert:        Boolean,
    val alertMessage: String = "",
)

data class OrbState(
    val score:       Float           = 0f,
    val threatLabel: String          = "AUTHENTIC",
    val escalation:  EscalationResult? = null,
    val drift:       DriftResult?    = null,
    val pitch:       PitchResult?    = null,
)

data class WaveState(
    val pcm:      FloatArray = FloatArray(0),
    val snr:      Float      = 0f,
    val windowId: Int        = 0,
) {
    override fun equals(other: Any?) = other is WaveState && windowId == other.windowId
    override fun hashCode()          = windowId
}

data class BioState(
    val biomarkers: AudioBiomarkers? = null,
    val score:      Float            = 0f,
)

data class WeightState(
    val weights: Map<String, Float> = mapOf(
        "AASIST" to 0.55f, "BIO" to 0.25f, "ATTR" to 0.10f, "CONT" to 0.10f
    ),
    val score:   Float              = 0f,
)

data class AttrState(
    val attribution:  Attribution? = null,
    val drift:        DriftResult  = DriftResult(1f, 0f, false),
    val score:        Float        = 0f,
    val isKnownThreat: Boolean     = false,
)

data class EscalState(
    val escalation: EscalationResult? = null,
    val score:      Float             = 0f,
)

data class SessionState(
    val session:   SessionLog?     = null,
    val ensemble:  EnsembleResult? = null,
    val snr:       Float           = 0f,
    val engine:    String          = "MOCK",
    val latencyMs: Float           = 0f,
    val score:     Float           = 0f,
)

data class EventState(
    val events: List<AnomalyEvent> = emptyList(),
    val score:  Float              = 0f,
)

data class PitchState(
    val pitch:    PitchResult? = null,
    val conflict: Boolean      = false,   // ensemble conflict flag
    val score:    Float        = 0f,
)

data class HeaderState(
    val score:       Float         = 0f,
    val mode:        DetectionMode = DetectionMode.IDLE,
    val clipName:    String        = "",
    val engine:      String        = "MOCK",
    val threatCount: Int           = 0,
    val showAlert:   Boolean       = false,
)

data class SparkState(
    val history: List<Float> = emptyList(),
    val score:   Float       = 0f,
)