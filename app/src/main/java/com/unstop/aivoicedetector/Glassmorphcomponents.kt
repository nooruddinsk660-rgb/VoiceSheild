package com.unstop.aivoicedetector

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*
import kotlin.math.*


private val PAD  = 12.dp
private val PAD_S = 8.dp
private val MONO = FontFamily.Monospace

@Composable
fun GlassLabel(text: String) {
    Text(
        text          = text,
        fontFamily    = MONO,
        fontSize      = GlassTokens.MicroSp.sp,
        letterSpacing = 2.5.sp,
        color         = InkMid,
        modifier      = Modifier.padding(bottom = 6.dp),
    )
}

private val WEIGHT_COLORS = mapOf(
    "AASIST" to Arctic,
    "BIO"    to Phosphor,
    "ATTR"   to Amber,
    "CONT"   to Color(0xFFFF66AA),  // pink — continuity
)

@Composable
fun EnsembleWeightBars(
    weights: Map<String, Float>,
    score:   Float,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .glassPanel(score)
            .padding(PAD),
    ) {
        GlassLabel("ENSEMBLE WEIGHTS")
        weights.entries.forEach { (key, value) ->
            val color     = WEIGHT_COLORS[key] ?: InkBright
            val animWidth by animateFloatAsState(value, tween(500), label = key)
            Row(
                modifier       = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(key, fontFamily = MONO, fontSize = 7.sp, letterSpacing = 1.5.sp,
                    color = InkMid, modifier = Modifier.width(48.dp))
                Box(
                    Modifier
                        .weight(1f)
                        .height(3.dp)
                        .background(AbyssStroke2, RoundedCornerShape(2.dp))
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(animWidth)
                            .background(
                                Brush.horizontalGradient(listOf(color.copy(0.7f), color)),
                                RoundedCornerShape(2.dp),
                            )
                    )
                }
                Text(
                    "${(value * 100).toInt()}%",
                    fontFamily = MONO, fontSize = 8.sp, letterSpacing = 0.5.sp,
                    color = color, modifier = Modifier.width(36.dp).padding(start = 6.dp),
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

data class RadarAxis(val label: String, val value: Float)

private fun AudioBiomarkers.toRadarAxes(): List<RadarAxis> = listOf(
    RadarAxis("FLUX",    spectralFlux.coerceIn(0f, 1f)),
    RadarAxis("ENTROPY", spectralEntropy.coerceIn(0f, 1f)),
    RadarAxis("ZCR",     (zeroCrossRate / 8000f).coerceIn(0f, 1f)),
    RadarAxis("TONAL",   toneratio.coerceIn(0f, 1f)),
    RadarAxis("SPREAD",  (spectralSpread / 4000f).coerceIn(0f, 1f)),
    RadarAxis("ENERGY",  ((energyDb + 96f) / 96f).coerceIn(0f, 1f)),
)

@Composable
fun BiomarkerRadar(
    biomarkers: AudioBiomarkers?,
    score:      Float,
    modifier:   Modifier = Modifier,
) {
    val axes = biomarkers?.toRadarAxes() ?: List(6) { RadarAxis("", 0f) }
    val col  = score.toThreatColor()

    // Animate each axis value smoothly
    val animValues = axes.map { ax ->
        val anim by animateFloatAsState(ax.value, tween(600), label = ax.label)
        anim
    }

    val textMeasurer = rememberTextMeasurer()

    Box(
        modifier = modifier
            .glassPanel(score)
            .padding(PAD),
    ) {
        GlassLabel("ACOUSTIC BIOMARKERS")
        Canvas(Modifier.fillMaxSize().padding(top = 18.dp)) {
            val cx    = size.width  / 2f
            val cy    = size.height / 2f
            val R     = minOf(cx, cy) * 0.68f
            val n     = axes.size
            val step  = 360f / n

            // Grid rings (3 levels: 33%, 66%, 100%)
            for (ring in 1..3) {
                val rr = R * ring / 3f
                val pts = (0 until n).map { i ->
                    val a = (i * step - 90f) * PI.toFloat() / 180f
                    Offset(cx + rr * cos(a), cy + rr * sin(a))
                }
                val path = Path().apply {
                    moveTo(pts[0].x, pts[0].y)
                    pts.drop(1).forEach { lineTo(it.x, it.y) }
                    close()
                }
                drawPath(path, AbyssStroke, style = Stroke(0.5f))
            }

            // Axis lines
            for (i in 0 until n) {
                val a = (i * step - 90f) * PI.toFloat() / 180f
                drawLine(AbyssStroke2, Offset(cx, cy), Offset(cx + R * cos(a), cy + R * sin(a)), 0.5f)
            }

            // Data polygon (filled + stroked)
            val dataPath = Path().apply {
                animValues.forEachIndexed { i, v ->
                    val a  = (i * step - 90f) * PI.toFloat() / 180f
                    val px = cx + R * v * cos(a)
                    val py = cy + R * v * sin(a)
                    if (i == 0) moveTo(px, py) else lineTo(px, py)
                }
                close()
            }
            drawPath(dataPath, col.copy(alpha = 0.12f))
            drawPath(dataPath, col, style = Stroke(1.5f))

            // Data point dots
            animValues.forEachIndexed { i, v ->
                val a  = (i * step - 90f) * PI.toFloat() / 180f
                val px = cx + R * v * cos(a)
                val py = cy + R * v * sin(a)
                drawCircle(col, 3.5f, Offset(px, py))
                drawCircle(col.copy(0.3f), 7f, Offset(px, py))
            }

            // Axis labels
            for (i in axes.indices) {
                val a    = (i * step - 90f) * PI.toFloat() / 180f
                val dist = R + 18f
                val lx   = cx + dist * cos(a)
                val ly   = cy + dist * sin(a)
                val measured = textMeasurer.measure(
                    axes[i].label,
                    TextStyle(fontFamily = MONO, fontSize = 6.5.sp, color = InkMid, letterSpacing = 1.5.sp),
                )
                drawText(measured, topLeft = Offset(lx - measured.size.width / 2f,
                    ly - measured.size.height / 2f))
            }
        }
    }
}

@Composable
fun WaveformDisplay(
    pcm:     FloatArray,
    score:   Float,
    snrDb:   Float,
    modifier: Modifier = Modifier,
) {
    val col  = score.toThreatColor()
    val inf  = rememberInfiniteTransition(label = "wv")
    val scan by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "wv_scan")

    Column(modifier = modifier.glassPanel(score).padding(PAD)) {
        GlassLabel("AUDIO WAVEFORM")
        Canvas(Modifier.fillMaxWidth().height(60.dp)) {
            if (pcm.isEmpty()) return@Canvas

            // Background tint
            drawRect(AbyssPanel)

            // Grid
            drawLine(AbyssStroke, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), 0.5f)

            // Scan line
            val sy = scan * size.height
            drawRect(col.copy(0.18f), Offset(0f, sy - 0.5f), Size(size.width, 1.5f))

            // Waveform path (downsample pcm to canvas width)
            val step = (pcm.size / size.width).coerceAtLeast(1f)
            val path = Path()
            val cy2  = size.height / 2f
            for (px in 0 until size.width.toInt()) {
                val idx = (px * step).toInt().coerceIn(0, pcm.lastIndex)
                val y   = cy2 - pcm[idx] * cy2 * 0.85f
                if (px == 0) path.moveTo(px.toFloat(), y) else path.lineTo(px.toFloat(), y)
            }
            drawPath(path, col.copy(0.85f), style = Stroke(1.4f, cap = StrokeCap.Round, join = StrokeJoin.Round))

            // Glow copy (wider, transparent)
            drawPath(path, col.copy(0.15f), style = Stroke(4f, cap = StrokeCap.Round))
        }
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            MetaChip("SNR", "${snrDb.toInt()} dB")
            Spacer(Modifier.width(10.dp))
            MetaChip("SAMPLES", "${pcm.size}")
        }
    }
}

@Composable
fun EscalationPanel(
    result:  EscalationResult?,
    score:   Float,
    modifier: Modifier = Modifier,
) {
    val col   = score.toThreatColor()
    val state = result?.state
    val label = when (state) {
        is EscalationState.Imminent    -> "IMMINENT  ·  ${state.windowsAway}w"
        is EscalationState.Warning     -> "WARNING   ·  ${state.windowsAway}w"
        EscalationState.Declining      -> "DECLINING"
        EscalationState.Stable         -> "STABLE"
        EscalationState.Insufficient   -> "COLLECTING"
        null                           -> "—"
    }
    val stateColor = when (state) {
        is EscalationState.Imminent -> Crimson
        is EscalationState.Warning  -> Amber
        EscalationState.Declining   -> Arctic
        else                        -> Phosphor
    }

    Column(modifier = modifier.glassPanel(score).padding(PAD)) {
        GlassLabel("THREAT ESCALATION")
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            // Status dot
            val inf = rememberInfiniteTransition(label = "esc")
            val a by inf.animateFloat(0.4f, 1f,
                infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "esc_a")
            Canvas(Modifier.size(8.dp)) {
                drawCircle(stateColor.copy(alpha = a), radius = size.minDimension / 2f)
            }
            Spacer(Modifier.width(8.dp))
            Text(label, fontFamily = MONO, fontSize = 9.sp, letterSpacing = 2.sp,
                color = stateColor, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            StatTile("SLOPE",     "${((result?.slope ?: 0f) * 100).let { if (it >= 0) "+${"%.1f".format(it)}" else "${"%.1f".format(it)}" }}/w")
            Spacer(Modifier.width(8.dp))
            StatTile("PRED ×5",   "${((result?.predicted5 ?: 0f) * 100).toInt()}%")
            Spacer(Modifier.width(8.dp))
            StatTile("PROTECT",   "${result?.protectionPct ?: 100}%")
        }
        Spacer(Modifier.height(8.dp))
        // Threat density meter
        val density = result?.threatDensity ?: 0f
        GlassLabel("THREAT DENSITY  ${"%4.1f".format(density)} evt/min")
        Box(Modifier.fillMaxWidth().height(2.dp).background(AbyssStroke2, RoundedCornerShape(1.dp))) {
            val animD by animateFloatAsState((density / 10f).coerceIn(0f, 1f), tween(400), label = "dens")
            Box(Modifier.fillMaxHeight().fillMaxWidth(animD)
                .background(Brush.horizontalGradient(listOf(Phosphor, col)), RoundedCornerShape(1.dp)))
        }
    }
}

@Composable
fun AttributionPanel(
    attribution: Attribution?,
    drift:       DriftResult?,
    score:       Float,
    isKnown:     Boolean,
    modifier:    Modifier = Modifier,
) {
    val detected  = attribution != null && attribution.engine != "Unknown"
    val col       = score.toThreatColor()

    Column(modifier = modifier.glassPanel(score).padding(PAD)) {
        GlassLabel("ENGINE FINGERPRINT")

        AnimatedContent(
            targetState = detected,
            transitionSpec = {
                fadeIn(tween(300)) togetherWith fadeOut(tween(200))
            },
            label = "attrAnim",
        ) { isDetected ->
            if (isDetected && attribution != null) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            attribution.engine.uppercase(),
                            fontFamily = MONO, fontSize = 22.sp, fontWeight = FontWeight.Black,
                            letterSpacing = 3.sp, color = Amber,
                        )
                        Spacer(Modifier.width(10.dp))
                        GlassChip("CONFIRMED", Crimson)
                        if (isKnown) {
                            Spacer(Modifier.width(6.dp))
                            GlassChip("KNOWN THREAT", CrimsonMid)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(attribution.reason, fontFamily = MONO, fontSize = 8.sp,
                        color = InkBright, letterSpacing = 0.5.sp)
                    Spacer(Modifier.height(8.dp))
                    Row {
                        StatTile("CONFIDENCE", "${(attribution.confidence * 100).toInt()}%")
                        Spacer(Modifier.width(8.dp))
                        StatTile("DRIFT", drift?.let { "${it.driftPercent.toInt()}%" } ?: "—")
                        Spacer(Modifier.width(8.dp))
                        StatTile("ALERT", if (drift?.alert == true) "YES" else "NO",
                            valueColor = if (drift?.alert == true) Crimson else Phosphor)
                    }
                }
            } else {
                Column {
                    Text("AUTHENTIC", fontFamily = MONO, fontSize = 20.sp,
                        fontWeight = FontWeight.Black, letterSpacing = 4.sp, color = Phosphor)
                    Spacer(Modifier.height(4.dp))
                    Text("No synthetic signatures in current window", fontFamily = MONO,
                        fontSize = 8.sp, color = InkMid, letterSpacing = 0.5.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("DRIFT ${drift?.let { "${"%.2f".format(it.driftPercent)}%" } ?: "—"} · " +
                         "SIM ${drift?.let { "${"%.2f".format(it.similarity)}" } ?: "—"}",
                        fontFamily = MONO, fontSize = 7.sp, color = InkMid, letterSpacing = 1.5.sp)
                }
            }
        }
    }
}

@Composable
fun SessionStatsRow(
    session:    SessionLog?,
    ensemble:   EnsembleResult?,
    snr:        Float,
    engine:     String,
    latencyMs:  Float,
    score:      Float,
    modifier:   Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .glassPanel(score, layer = GlassTokens.Layer1)
            .padding(horizontal = PAD, vertical = PAD_S),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        val flagged  = session?.flaggedWindows ?: 0
        val total    = session?.totalWindowsAnalyzed ?: 0
        val maxConf  = session?.maxConfidence ?: 0f

        SessionStat("WINDOWS",  "$total")
        SessionStat("FLAGGED",  "$flagged",
            color = if (flagged > 0) Crimson else InkBright)
        SessionStat("MAX CONF", "${(maxConf * 100).toInt()}%",
            color = maxConf.toThreatColor())
        SessionStat("SNR",      "${snr.toInt()} dB")
        SessionStat("LATENCY",  "${latencyMs.toInt()} ms")
        SessionStat("ENGINE",   engine, color = Arctic)
        ensemble?.let { e ->
            SessionStat("DOMINANT", e.dominantSignal,
                color = WEIGHT_COLORS[e.dominantSignal] ?: InkBright)
        }
    }
}

@Composable
fun AnomalyTimeline(
    events:  List<AnomalyEvent>,
    score:   Float,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.glassPanel(score).padding(PAD)) {
        GlassLabel("ANOMALY TIMELINE")
        if (events.isEmpty()) {
            Text("· · · NO FLAGGED EVENTS · · ·", fontFamily = MONO, fontSize = 8.sp,
                color = InkMid, letterSpacing = 2.sp, modifier = Modifier.padding(top = 8.dp))
        } else {
            events.take(8).forEach { ev ->
                val ec = ev.smoothedScore.toThreatColor()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .glassPanel(ev.smoothedScore, GlassTokens.Layer1)
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(2.dp).height(28.dp).background(ec, RoundedCornerShape(1.dp)))
                    Spacer(Modifier.width(8.dp))
                    Text(ev.timeLabel, fontFamily = MONO, fontSize = 7.5.sp, color = InkMid,
                        modifier = Modifier.width(52.dp))
                    Text("${(ev.smoothedScore * 100).toInt()}%", fontFamily = MONO,
                        fontSize = 11.sp, color = ec, fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(40.dp))
                    Box(
                        Modifier.weight(1f).height(3.dp)
                            .background(AbyssStroke2, RoundedCornerShape(1.dp))
                    ) {
                        Box(
                            Modifier.fillMaxHeight()
                                .fillMaxWidth(ev.smoothedScore)
                                .background(Brush.horizontalGradient(listOf(ec.copy(0.6f), ec)),
                                    RoundedCornerShape(1.dp))
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("${(ev.rawScore * 100).toInt()}", fontFamily = MONO, fontSize = 7.sp,
                        color = InkMid)
                }
            }
        }
    }
}

@Composable
fun PitchPanel(
    pitch:    PitchResult?,
    conflict: Boolean,
    score:    Float,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.glassPanel(score).padding(PAD),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            GlassLabel("PITCH  ·  YIN")
            Text(
                pitch?.let { "${it.frequency.toInt()} Hz" } ?: "— Hz",
                fontFamily = MONO, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                color = if (pitch?.isPitchFlat == true) Amber else score.toThreatColor(),
            )
            Text(
                pitch?.let { "CONF ${"%.2f".format(it.confidence)}" } ?: "",
                fontFamily = MONO, fontSize = 7.sp, color = InkMid, letterSpacing = 1.sp,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            if (pitch?.isPitchFlat == true) GlassChip("FLAT PITCH", Amber)
            if (conflict) GlassChip("CONFLICT", Crimson)
        }
    }
}

@Composable
fun ScoreSparkline(history: List<Float>, score: Float, modifier: Modifier = Modifier) {
    val col = score.toThreatColor()
    Canvas(modifier.height(28.dp).fillMaxWidth()) {
        if (history.size < 2) return@Canvas
        val w = size.width; val h = size.height
        // Threshold reference line (0.65 = danger zone)
        val thY = h - 0.65f * h
        drawLine(Crimson.copy(0.3f), Offset(0f, thY), Offset(w, thY), 0.5f)
        drawLine(Amber.copy(0.25f), Offset(0f, h - 0.35f * h), Offset(w, h - 0.35f * h), 0.5f)

        val path = Path()
        history.forEachIndexed { i, v ->
            val x = i.toFloat() / (history.size - 1) * w
            val y = h - v * h
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        // Fill
        val fill = Path().also { fp ->
            fp.addPath(path)
            fp.lineTo(w, h); fp.lineTo(0f, h); fp.close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(col.copy(0.18f), Color.Transparent)))
        drawPath(path, col, style = Stroke(1.5f, cap = StrokeCap.Round))
    }
}


@Composable
fun GlassChip(text: String, color: Color) {
    Box(
        Modifier
            .border(1.dp, color, RoundedCornerShape(3.dp))
            .background(color.copy(0.10f), RoundedCornerShape(3.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(text, fontFamily = MONO, fontSize = 6.sp, color = color,
            letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun RowScope.StatTile(label: String, value: String, valueColor: Color = InkBright) {
    Column(
        Modifier
            .weight(1f)
            .background(AbyssPanel, RoundedCornerShape(4.dp))
            .border(0.5.dp, AbyssStroke, RoundedCornerShape(4.dp))
            .padding(6.dp),
    ) {
        Text(label, fontFamily = MONO, fontSize = 6.sp, color = InkMid, letterSpacing = 1.5.sp)
        Text(value, fontFamily = MONO, fontSize = 10.sp, color = valueColor, letterSpacing = 0.5.sp,
            fontWeight = FontWeight.Bold)
    }
}

@Composable
fun MetaChip(key: String, value: String) {
    Row {
        Text("$key ", fontFamily = MONO, fontSize = 7.sp, color = InkMid, letterSpacing = 1.sp)
        Text(value, fontFamily = MONO, fontSize = 7.sp, color = InkBright, letterSpacing = 0.5.sp)
    }
}

@Composable
fun SessionStat(label: String, value: String, color: Color = InkBright) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontFamily = MONO, fontSize = 11.sp, color = color, fontWeight = FontWeight.Bold)
        Text(label, fontFamily = MONO, fontSize = 5.5.sp, color = InkMid, letterSpacing = 1.sp)
    }
}

@Composable
fun ConfidenceBandBadge(band: Float) {
    if (band < 0.02f) return
    GlassChip("± ${"%.2f".format(band)}", if (band > 0.15f) Amber else Arctic)
}