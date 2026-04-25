package com.unstop.aivoicedetector

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel


private val MONO = FontFamily.Monospace
private val GAP  = 8.dp
private val PAD  = 12.dp

@Composable
fun ConnectedGlassDashboard(
    vm: FinalViewModel = viewModel(),
) {
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) vm.startLive() }

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.analyseFile(it, it.lastPathSegment ?: "audio") } }

    val orbParams by rememberOrbRenderParams(vm.orbState)
    val orbAnim    = rememberOrbAnimationState()
    OrbAnimationDriver(orbAnim, orbParams)
    val orbRead    = rememberOrbReadState(orbAnim, orbParams)

    val inf = rememberInfiniteTransition(label = "bg")
    val particlePhase by inf.animateFloat(0f, 1f,
        infiniteRepeatable(tween(18000, easing = LinearEasing)), label = "ptcl")
    val bgScan by inf.animateFloat(0f, 1f,
        infiniteRepeatable(tween(3500, easing = LinearEasing)), label = "scan")

    val header   by vm.headerState.collectAsStateWithLifecycle()
    val render   by vm.renderPhase.collectAsStateWithLifecycle()
    val forensic by vm.forensicReport.collectAsStateWithLifecycle()

    val bgTint by animateFloatAsState(
        if (header.score > 0.65f) 0.08f else 0f, tween(800), label = "tint"
    )

    AIVoiceDetectorTheme {
        Box(Modifier.fillMaxSize().background(Abyss)) {

            Canvas(Modifier.fillMaxSize()) {
                drawParticleField(particlePhase, header.score)
                drawScanLine(bgScan, header.score)
                if (bgTint > 0.001f) drawRect(Crimson.copy(bgTint))
                drawRect(Brush.radialGradient(
                    listOf(Color.Transparent, Abyss.copy(0.7f)),
                    center = Offset(size.width / 2f, size.height / 2f),
                    radius = size.maxDimension * 0.75f,
                ))
            }

            Column(Modifier.fillMaxSize()) {

                ConnectedHeader(vm, orbRead)
                ConnectedStatsBar(vm, render)
                ConnectedSparkline(vm)

                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = PAD),
                    verticalArrangement = Arrangement.spacedBy(GAP),
                ) {
                    // Row 1: Orb + right panels
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(GAP)) {

                        // LEFT: Orb glass card + weight bars
                        Column(Modifier.weight(0.42f),
                            verticalArrangement = Arrangement.spacedBy(GAP)) {

                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .glassCard(header.score)
                                    .glowBorder(header.score)
                                    .padding(PAD),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    ThreatOrb(
                                        modifier = Modifier.size(190.dp),
                                        read     = orbRead,
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    ConnectedEnsembleMeta(vm)
                                }
                            }

                            ConnectedWeightBars(vm)
                        }

                        // RIGHT: Waveform + Radar + Escalation
                        Column(Modifier.weight(0.58f),
                            verticalArrangement = Arrangement.spacedBy(GAP)) {
                            ConnectedWaveform(vm, render)
                            ConnectedBioRadar(vm, render)
                            ConnectedEscalation(vm)
                        }
                    }

                    // Row 2: Attribution + Pitch
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(GAP)) {
                        ConnectedAttribution(vm, Modifier.weight(0.62f))
                        ConnectedPitch(vm, Modifier.weight(0.38f))
                    }

                    // Row 3: Timeline
                    ConnectedTimeline(vm)

                    // Row 4: Perf overlay
                    ConnectedPerfOverlay(vm)

                    // Row 5: Forensic report
                    if (forensic.isNotEmpty()) {
                        ForensicCard(forensic, header.score)
                    }

                    Spacer(Modifier.height(80.dp))
                }
            }

            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                ConnectedActionBar(
                    vm     = vm,
                    onLive = { permLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    onFile = { fileLauncher.launch(arrayOf("audio/*")) },
                )
            }

            DeepfakeAlertOverlay(
                score       = header.score,
                attribution = vm.attrState.value.attribution,
                isVisible   = header.showAlert,
                onDismiss   = { vm.dismissAlert() },
            )
        }
    }
}


@Composable
private fun ConnectedHeader(vm: FinalViewModel, orbRead: OrbReadState) {
    val h by vm.headerState.collectAsStateWithLifecycle()
    val inf = rememberInfiniteTransition(label = "hdr")
    val dot by inf.animateFloat(0.4f, 1f,
        infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "dot")

    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(GlassTokens.Layer3, Color.Transparent)))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(8.dp)) {
            val col = h.score.toThreatColor()
            drawCircle(col.copy(dot))
            drawCircle(col.copy(dot * 0.2f), size.minDimension)
        }
        Spacer(Modifier.width(8.dp))
        Text("VOICESHIELD", fontFamily = MONO, fontSize = 13.sp, fontWeight = FontWeight.Black,
            letterSpacing = 4.sp, color = h.score.toThreatColor())
        Spacer(Modifier.width(10.dp))
        AnimatedContent(h.mode, transitionSpec = {
            slideInVertically { -it } togetherWith slideOutVertically { it }
        }, label = "mode") { mode: DetectionMode ->
            val mc = when (mode) {
                DetectionMode.LIVE -> Crimson
                DetectionMode.DEMO -> Amber
                DetectionMode.FILE -> Arctic
                DetectionMode.IDLE -> InkMid
                else -> InkMid
            }
            GlassChip(mode.name, mc)
        }
        if (h.clipName.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(h.clipName, fontFamily = MONO, fontSize = 7.sp, color = InkMid,
                letterSpacing = 1.sp, maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        ThreatOrbMini(read = orbRead)
        Spacer(Modifier.width(8.dp))
        if (h.threatCount > 0) {
            GlassChip("DB ${h.threatCount}", CrimsonMid)
            Spacer(Modifier.width(6.dp))
        }
        Text(h.engine, fontFamily = MONO, fontSize = 6.sp, color = Arctic, letterSpacing = 1.5.sp)
    }
}

@Composable
private fun ConnectedStatsBar(vm: FinalViewModel, render: RenderPhase) {
    val s by vm.sessionState.collectAsStateWithLifecycle()
    if (render == RenderPhase.SKELETON) {
        SkeletonCard(Modifier.fillMaxWidth().padding(horizontal = PAD, vertical = GAP),
            lines = 1, phase = render)
        return
    }
    SessionStatsRow(
        session   = s.session,
        ensemble  = s.ensemble,
        snr       = s.snr,
        engine    = s.engine,
        latencyMs = s.latencyMs,
        score     = s.score,
        modifier  = Modifier.fillMaxWidth().padding(horizontal = PAD, vertical = GAP),
    )
}

@Composable
private fun ConnectedSparkline(vm: FinalViewModel) {
    val s by vm.sparkState.collectAsStateWithLifecycle()
    ScoreSparkline(s.history, s.score,
        Modifier.fillMaxWidth().padding(horizontal = PAD).padding(bottom = GAP))
}

@Composable
private fun ConnectedEnsembleMeta(vm: FinalViewModel) {
    val s by vm.sessionState.collectAsStateWithLifecycle()
    val e = s.ensemble ?: return
    Row(Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically) {
        Text("RAW ${(e.aasistScore * 100).toInt()}%",
            fontFamily = MONO, fontSize = 7.sp, color = InkMid, letterSpacing = 1.sp)
        ConfidenceBandBadge(e.confidenceBand)
        Text("BIO ${(e.bioScore * 100).toInt()}%",
            fontFamily = MONO, fontSize = 7.sp, color = InkMid, letterSpacing = 1.sp)
    }
}

@Composable
private fun ConnectedWeightBars(vm: FinalViewModel) {
    val w by vm.weightState.collectAsStateWithLifecycle()
    EnsembleWeightBars(w.weights, w.score, Modifier.fillMaxWidth())
}

@Composable
private fun ConnectedWaveform(vm: FinalViewModel, render: RenderPhase) {
    val w by vm.waveState.collectAsStateWithLifecycle()
    if (render == RenderPhase.SKELETON) {
        SkeletonCard(Modifier.fillMaxWidth(), lines = 2, phase = render); return
    }
    val score by vm.orbState.collectAsStateWithLifecycle()
    WaveformDisplay(w.pcm, score.score, w.snr, Modifier.fillMaxWidth())
}

@Composable
private fun ConnectedBioRadar(vm: FinalViewModel, render: RenderPhase) {
    val b by vm.bioState.collectAsStateWithLifecycle()
    if (render == RenderPhase.SKELETON) {
        SkeletonCard(Modifier.fillMaxWidth().height(200.dp), lines = 3, phase = render); return
    }
    BiomarkerRadar(b.biomarkers, b.score, Modifier.fillMaxWidth().height(200.dp))
}

@Composable
private fun ConnectedEscalation(vm: FinalViewModel) {
    val e by vm.escalState.collectAsStateWithLifecycle()
    EscalationPanel(e.escalation, e.score, Modifier.fillMaxWidth())
}

@Composable
private fun ConnectedAttribution(vm: FinalViewModel, modifier: Modifier) {
    val a by vm.attrState.collectAsStateWithLifecycle()
    AttributionPanel(a.attribution, a.drift, a.score, a.isKnownThreat, modifier)
}

@Composable
private fun ConnectedPitch(vm: FinalViewModel, modifier: Modifier) {
    val p by vm.pitchState.collectAsStateWithLifecycle()
    PitchPanel(p.pitch, p.conflict, p.score, modifier)
}

@Composable
private fun ConnectedTimeline(vm: FinalViewModel) {
    val e by vm.eventState.collectAsStateWithLifecycle()
    AnomalyTimeline(e.events, e.score, Modifier.fillMaxWidth())
}

@Composable
private fun ConnectedPerfOverlay(vm: FinalViewModel) {
    val t by vm.telemetry.collectAsStateWithLifecycle()
    if (t != null) PerfOverlay(vm.telemetry)
}

@Composable
private fun ForensicCard(report: String, score: Float) {
    Column(Modifier.fillMaxWidth().glassPanel(score).padding(PAD)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassLabel("FORENSIC REPORT")
            Spacer(Modifier.width(8.dp))
            GlassChip("RSA-2048 SIGNED", Arctic)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            report.take(800) + if (report.length > 800) "\n…" else "",
            fontFamily = MONO, fontSize = 7.sp, color = InkBright,
            letterSpacing = 0.3.sp, lineHeight = 13.sp,
            modifier = Modifier.fillMaxWidth()
                .background(AbyssPanel, RoundedCornerShape(4.dp)).padding(8.dp),
        )
    }
}

@Composable
private fun ConnectedActionBar(
    vm:     FinalViewModel,
    onLive: () -> Unit,
    onFile: () -> Unit,
) {
    val h by vm.headerState.collectAsStateWithLifecycle()
    val isRunning = h.mode != DetectionMode.IDLE

    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, AbyssSurface, Abyss)))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        ActionBtn("LIVE", if (h.mode == DetectionMode.LIVE) Crimson else InkMid,
            !isRunning || h.mode == DetectionMode.LIVE) {
            if (isRunning) vm.stopAll() else onLive()
        }
        ActionBtn("DEMO", if (h.mode == DetectionMode.DEMO) Amber else InkMid,
            !isRunning || h.mode == DetectionMode.DEMO) {
            if (isRunning) vm.stopAll() else vm.startDemo()
        }
        ActionBtn("FILE", if (h.mode == DetectionMode.FILE) Arctic else InkMid,
            !isRunning || h.mode == DetectionMode.FILE) {
            if (isRunning) vm.stopAll() else onFile()
        }
        Spacer(Modifier.weight(1f))
        Sensitivity.values().forEach { s ->
            val active = vm.decisionEngine.currentSensitivity == s
            Box(
                Modifier
                    .border(if (active) 1.dp else 0.5.dp,
                        if (active) h.score.toThreatColor() else AbyssStroke,
                        RoundedCornerShape(3.dp))
                    .background(
                        if (active) h.score.toThreatColor().copy(0.12f) else Color.Transparent,
                        RoundedCornerShape(3.dp))
                    .clickable { vm.setSensitivity(s) }
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            ) {
                Text(s.name[0].toString(), fontFamily = MONO, fontSize = 8.sp,
                    color = if (active) h.score.toThreatColor() else InkMid,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    letterSpacing = 1.sp)
            }
        }
        Spacer(Modifier.width(4.dp))
        ActionBtn("RPT", Arctic, !isRunning) { vm.generateForensicReport() }
    }
}

@Composable
private fun ActionBtn(
    label:   String,
    color:   Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.93f else 1f, tween(80), label = "btn_$label")
    Box(
        Modifier.scale(scale)
            .border(1.dp, color.copy(if (enabled) 0.7f else 0.25f), RoundedCornerShape(4.dp))
            .background(color.copy(if (enabled) 0.12f else 0.04f), RoundedCornerShape(4.dp))
            .clickable(enabled = enabled) { pressed = true; onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontFamily = MONO, fontSize = 8.sp,
            color = color.copy(if (enabled) 1f else 0.35f),
            letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
    }
    LaunchedEffect(pressed) {
        if (pressed) { kotlinx.coroutines.delay(100); pressed = false }
    }
}