package com.unstop.aivoicedetector

import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.StateFlow



data class OrbRenderParams(
    val score:        Float = 0f,
    val color:        Color = Phosphor,
    val glowColor:    Color = GlassTokens.Orb.SafeGlow,
    val rimColor:     Color = GlassTokens.RimSafe,
    val label:        String = "AUTHENTIC",
    val ringSpeedMs:  Int   = 5000,           // orbital ring rotation period
    val hasDrift:     Boolean = false,
    val isImminent:   Boolean = false,
    val pitchPhaseHz: Float = 0f,             // drives sphere grid animation
)

data class WaveRenderParams(
    val pcm:          FloatArray = FloatArray(0),
    val color:        Color      = Phosphor,
    val windowId:     Int        = 0,
) {
    override fun equals(other: Any?) = other is WaveRenderParams && windowId == other.windowId
    override fun hashCode()          = windowId
}

data class RadarRenderParams(
    val axes:    List<Pair<String, Float>> = emptyList(),  // (label, value 0-1)
    val color:   Color                     = Phosphor,
)

class OrbAnimationState {
    // Score: animated at 600 ms — smooth visual, not data-rate
    val animScore     = Animatable(0f)

    // Glow pulse alpha: infinite loop, rate changes per threat state
    val pulseAlpha    = Animatable(0.55f)

    // Scan line: 0..1 continuous sweep
    val scanProgress  = Animatable(0f)

    // Ring rotation: 0..360 continuous
    val ringAngle     = Animatable(0f)
    val ring2Angle    = Animatable(0f)

    // Corona rotation
    val coronaAngle   = Animatable(0f)

    // Sphere grid phase (pitch-driven)
    val gridPhase     = Animatable(0f)

    // Drift wobble
    val driftWobble   = Animatable(0f)
}

@Composable
fun rememberOrbAnimationState(): OrbAnimationState = remember { OrbAnimationState() }


@Composable
fun rememberOrbRenderParams(orbFlow: StateFlow<OrbState>): State<OrbRenderParams> {
    val orbState by orbFlow.collectAsState()

    // derivedStateOf: only recomputes when orbState fields it READS change.
    // If only `pitch` changes, `color`/`label`/`isImminent` block is skipped.
    return remember {
        derivedStateOf {
            val s         = orbState.score
            val color     = s.toThreatColor()
            val isImminent = orbState.escalation?.state is EscalationState.Imminent
            OrbRenderParams(
                score        = s,
                color        = color,
                glowColor    = s.toThreatGlowColor(),
                rimColor     = s.toRimColor(),
                label        = orbState.threatLabel,
                ringSpeedMs  = if (isImminent) 2400 else if (s > 0.35f) 3600 else 5000,
                hasDrift     = orbState.drift?.alert == true,
                isImminent   = isImminent,
                pitchPhaseHz = orbState.pitch?.frequency ?: 0f,
            )
        }
    }
}

@Composable
fun rememberWaveRenderParams(waveFlow: StateFlow<WaveState>, scoreFlow: StateFlow<OrbState>): State<WaveRenderParams> {
    val wave  by waveFlow.collectAsState()
    val score by scoreFlow.collectAsState()
    return remember {
        derivedStateOf {
            WaveRenderParams(wave.pcm, score.score.toThreatColor(), wave.windowId)
        }
    }
}

@Composable
fun rememberRadarRenderParams(bioFlow: StateFlow<BioState>): State<RadarRenderParams> {
    val bio by bioFlow.collectAsState()
    return remember {
        derivedStateOf {
            val b = bio.biomarkers
            RadarRenderParams(
                axes = if (b == null) emptyList() else listOf(
                    "FLUX"    to b.spectralFlux.coerceIn(0f, 1f),
                    "ENTROPY" to b.spectralEntropy.coerceIn(0f, 1f),
                    "ZCR"     to (b.zeroCrossRate / 8000f).coerceIn(0f, 1f),
                    "TONAL"   to b.toneratio.coerceIn(0f, 1f),
                    "SPREAD"  to (b.spectralSpread / 4000f).coerceIn(0f, 1f),
                    "ENERGY"  to ((b.energyDb + 96f) / 96f).coerceIn(0f, 1f),
                ),
                color = bio.score.toThreatColor(),
            )
        }
    }
}

@Composable
fun OrbAnimationDriver(
    anim:   OrbAnimationState,
    params: OrbRenderParams,
) {
    // Score animation — restarts on new target
    LaunchedEffect(params.score) {
        anim.animScore.animateTo(
            params.score,
            tween(600, easing = FastOutSlowInEasing),
        )
    }

    // Pulse — restarts when threat state changes (changes period)
    LaunchedEffect(params.isImminent, params.score > 0.65f) {
        while (true) {
            anim.pulseAlpha.animateTo(
                if (params.isImminent) 1.0f else 0.80f,
                tween(if (params.isImminent) 350 else 1800, easing = FastOutSlowInEasing),
            )
            anim.pulseAlpha.animateTo(
                0.40f,
                tween(if (params.isImminent) 350 else 1800, easing = FastOutSlowInEasing),
            )
        }
    }

    // Ring 1 — restarts when speed changes
    LaunchedEffect(params.ringSpeedMs) {
        while (true) {
            val remaining = 360f - (anim.ringAngle.value % 360f)
            anim.ringAngle.animateTo(
                anim.ringAngle.value + remaining,
                tween((remaining / 360f * params.ringSpeedMs).toInt(), easing = LinearEasing),
            )
            // Reset without animation to avoid Float drift
            anim.ringAngle.snapTo(0f)
        }
    }

    // Ring 2 — counter-rotate, fixed speed
    LaunchedEffect(Unit) {
        while (true) {
            anim.ring2Angle.animateTo(
                anim.ring2Angle.value + 360f,
                tween(7500, easing = LinearEasing),
            )
            anim.ring2Angle.snapTo(0f)
        }
    }

    // Scan line — always running
    LaunchedEffect(Unit) {
        while (true) {
            anim.scanProgress.animateTo(1f, tween(2200, easing = LinearEasing))
            anim.scanProgress.snapTo(0f)
        }
    }

    // Corona
    LaunchedEffect(Unit) {
        while (true) {
            anim.coronaAngle.animateTo(
                anim.coronaAngle.value + 360f,
                tween(12000, easing = LinearEasing),
            )
            anim.coronaAngle.snapTo(0f)
        }
    }

    // Grid phase (pitch-driven: higher pitch = faster grid)
    LaunchedEffect(params.pitchPhaseHz) {
        val period = if (params.pitchPhaseHz > 0f)
            (3000f * (200f / params.pitchPhaseHz.coerceIn(80f, 400f))).toInt()
        else 3000
        while (true) {
            anim.gridPhase.animateTo(1f, tween(period, easing = LinearEasing))
            anim.gridPhase.snapTo(0f)
        }
    }

    // Drift wobble — only when voice identity shift detected
    LaunchedEffect(params.hasDrift) {
        if (params.hasDrift) {
            while (true) {
                anim.driftWobble.animateTo( 5f, tween(600, easing = FastOutSlowInEasing))
                anim.driftWobble.animateTo(-5f, tween(600, easing = FastOutSlowInEasing))
            }
        } else {
            anim.driftWobble.animateTo(0f, tween(400))
        }
    }
}

data class OrbReadState(
    val score:        Float,
    val pulseAlpha:   Float,
    val scanProgress: Float,
    val ringAngle:    Float,
    val ring2Angle:   Float,
    val coronaAngle:  Float,
    val gridPhase:    Float,
    val driftWobble:  Float,
    val color:        Color,
    val glowColor:    Color,
    val rimColor:     Color,
    val label:        String,
    val hasDrift:     Boolean,
)

@Composable
fun rememberOrbReadState(anim: OrbAnimationState, params: OrbRenderParams): OrbReadState {
    // Each Animatable.value is Compose snapshot state — reading it here subscribes
    // this derivedStateOf to each independent animation clock.
    return remember(params) {
        derivedStateOf {
            OrbReadState(
                score        = anim.animScore.value,
                pulseAlpha   = anim.pulseAlpha.value,
                scanProgress = anim.scanProgress.value,
                ringAngle    = anim.ringAngle.value,
                ring2Angle   = anim.ring2Angle.value,
                coronaAngle  = anim.coronaAngle.value,
                gridPhase    = anim.gridPhase.value,
                driftWobble  = anim.driftWobble.value,
                color        = params.color,
                glowColor    = params.glowColor,
                rimColor     = params.rimColor,
                label        = params.label,
                hasDrift     = params.hasDrift,
            )
        }
    }.value
}