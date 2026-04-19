package com.unstop.aivoicedetector

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.*

@Composable
fun SessionTimelineRuler(
    startMs: Long, elapsedMs: Long, totalMs: Long,
    events: List<AnomalyEvent>,
    fileWindow: Int = -1, totalWindows: Int = 0,
    modifier: Modifier = Modifier,
) {
    val inf = rememberInfiniteTransition(label = "tl")
    val dot     by inf.animateFloat(.35f, 1f, infiniteRepeatable(tween(750, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "td")
    val shimmer by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "ts")

    val progress = when {
        totalMs > 0                          -> (elapsedMs.toFloat() / totalMs).coerceIn(0f, 1f)
        totalWindows > 0 && fileWindow >= 0  -> (fileWindow.toFloat() / totalWindows).coerceIn(0f, 1f)
        else                                 -> 0f
    }

    Canvas(modifier) {
        val w = size.width; val h = size.height
        val ty = h * 0.56f; val th = 3.5.dp.toPx()

        drawRect(
            Brush.horizontalGradient(listOf(
                PhosphorDim.copy(shimmer * 0.28f + 0.08f),
                Phosphor.copy(0.18f),
                PhosphorDim.copy((1f - shimmer) * 0.28f + 0.08f)
            ), 0f, w),
            Offset(0f, ty), Size(w, th)
        )

        val fw = (w * progress).coerceAtLeast(0f)
        if (fw > 1f) {
            drawRect(
                Brush.horizontalGradient(listOf(Phosphor.copy(0.5f), Phosphor, Phosphor.copy(0.85f)), 0f, fw),
                Offset(0f, ty), Size(fw, th)
            )
        }

        repeat(21) { i ->
            val tx = w * i / 20f; val maj = i % 5 == 0
            drawLine(
                if (maj) AbyssStroke2 else AbyssStroke,
                Offset(tx, ty - if (maj) 7.dp.toPx() else 3.5.dp.toPx()),
                Offset(tx, ty),
                if (maj) 1.2.dp.toPx() else 0.5.dp.toPx()
            )
        }

        events.forEach { ev ->
            val ep = if (totalMs > 0) ((ev.timestampMs - startMs).toFloat() / totalMs).coerceIn(0f, 1f) else 0f
            val ex = w * ep
            val ca = if (ev.smoothedScore > 0.8f) 1f else 0.72f
            val sz = if (ev.smoothedScore > 0.8f) 4.dp.toPx() else 3.dp.toPx()
            drawLine(Crimson.copy(ca), Offset(ex, ty - 16.dp.toPx()), Offset(ex, ty - 4.dp.toPx()), 2.dp.toPx())
            drawCircle(Crimson.copy(ca), sz, Offset(ex, ty - 16.dp.toPx() - sz))
            drawCircle(Crimson.copy(ca * 0.2f), sz * 2.5f, Offset(ex, ty - 16.dp.toPx() - sz))
        }

        val px = w * progress
        drawCircle(Phosphor.copy(dot), 5.5.dp.toPx(), Offset(px, ty + th / 2f))
        drawCircle(Phosphor.copy(dot * 0.15f), 11.dp.toPx(), Offset(px, ty + th / 2f))
        drawLine(Phosphor.copy(dot * 0.35f), Offset(px, ty - 20.dp.toPx()), Offset(px, ty + th + 7.dp.toPx()), 1.dp.toPx())
    }
}

@Composable
fun NeuralThreatRing(
    score: Float, rawScore: Float, predicted5: Float,
    melBands: FloatArray, scanning: Boolean, flagged: Boolean,
    modifier: Modifier = Modifier,
) {
    val inf = rememberInfiniteTransition(label = "qto")
    val ring1  by inf.animateFloat(0f, 360f,   infiniteRepeatable(tween(4200,  easing = LinearEasing)), label = "r1")
    val ring2  by inf.animateFloat(360f, 0f,   infiniteRepeatable(tween(7100,  easing = LinearEasing)), label = "r2")
    val ring3  by inf.animateFloat(0f, 360f,   infiniteRepeatable(tween(12500, easing = LinearEasing)), label = "r3")
    val sweep  by inf.animateFloat(0f, 360f,   infiniteRepeatable(tween(2600,  easing = LinearEasing)), label = "sw")
    val pulse  by inf.animateFloat(0.5f, 1f,   infiniteRepeatable(tween(650,   easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pu")
    val core   by inf.animateFloat(0.65f, 1f,  infiniteRepeatable(tween(1900,  easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "co")
    val pRot   by inf.animateFloat(0f, 360f,   infiniteRepeatable(tween(9000,  easing = LinearEasing)), label = "pr")
    val pRot2  by inf.animateFloat(360f, 0f,   infiniteRepeatable(tween(13000, easing = LinearEasing)), label = "pr2")

    val animScore by animateFloatAsState(score,     tween(500), label = "asc")
    val animRaw   by animateFloatAsState(rawScore,  tween(350), label = "arw")
    val animPred  by animateFloatAsState(predicted5,tween(850), label = "apd")

    val col    = when { score >= .60f -> Crimson; score >= .35f -> Amber; else -> Phosphor }
    val colMid = when { score >= .60f -> CrimsonMid; score >= .35f -> AmberMid; else -> PhosphorMid }
    val colDim = when { score >= .60f -> CrimsonDim; score >= .35f -> AmberDim; else -> PhosphorDim }

    Canvas(modifier) {
        val cx = size.width / 2f; val cy = size.height / 2f
        val R = minOf(cx, cy) - 7.dp.toPx()

        drawHexGrid(cx, cy, R * 0.82f, AbyssStroke)

        listOf(0.26f, 0.42f, 0.58f, 0.74f, 0.91f).forEachIndexed { i, fr ->
            drawCircle(
                if (i == 3) col.copy(0.09f) else AbyssStroke.copy(0.45f),
                R * fr, Offset(cx, cy),
                style = Stroke(if (i == 3) 1.5.dp.toPx() else 0.4.dp.toPx())
            )
        }

        for (a in 0 until 4) {
            val rad = (a * 45f) * (PI / 180f).toFloat()
            drawLine(AbyssStroke.copy(0.4f),
                Offset(cx - cos(rad) * R * 0.93f, cy - sin(rad) * R * 0.93f),
                Offset(cx + cos(rad) * R * 0.93f, cy + sin(rad) * R * 0.93f),
                0.4.dp.toPx())
        }

        repeat(60) { t ->
            val ang = (t * 6f - 90f) * (PI / 180f).toFloat(); val maj = t % 5 == 0
            val tLen = if (maj) 8.dp.toPx() else 4.dp.toPx()
            drawLine(
                if (maj) AbyssStroke2 else AbyssStroke,
                Offset(cx + cos(ang) * (R * 0.97f - tLen), cy + sin(ang) * (R * 0.97f - tLen)),
                Offset(cx + cos(ang) * R * 0.97f, cy + sin(ang) * R * 0.97f),
                if (maj) 1.3.dp.toPx() else 0.5.dp.toPx()
            )
        }

        repeat(8) { i ->
            val startA = ring1 + i * 45f
            drawArc(col.copy(0.14f), startA - 18f, 18f, false,
                Offset(cx - R * 0.96f, cy - R * 0.96f), Size(R * 1.92f, R * 1.92f),
                style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round))
        }
        repeat(4) { i ->
            val startA = ring2 + i * 90f
            drawArc(col.copy(0.10f), startA, 38f, false,
                Offset(cx - R * 0.88f, cy - R * 0.88f), Size(R * 1.76f, R * 1.76f),
                style = Stroke(0.8.dp.toPx(), cap = StrokeCap.Round))
        }
        repeat(3) { i ->
            val startA = ring3 + i * 120f
            drawArc(col.copy(0.09f), startA, 55f, false,
                Offset(cx - R * 0.73f, cy - R * 0.73f), Size(R * 1.46f, R * 1.46f),
                style = Stroke(1.dp.toPx(), cap = StrokeCap.Round))
        }

        if (melBands.isNotEmpty() && scanning) {
            val innerR = R * 0.33f; val outerMax = R * 0.63f
            repeat(64) { i ->
                val melIdx = (i.toFloat() / 64f * melBands.size).toInt().coerceIn(0, melBands.lastIndex)
                val energy = ((melBands[melIdx] + 80f) / 80f).coerceIn(0f, 1f)
                val ang = (i * 360f / 64f - 90f) * (PI / 180f).toFloat()
                val rodLen = (outerMax - innerR) * energy
                val fNorm = i.toFloat() / 64f
                val rodAlpha = (0.35f + energy * 0.65f) * (if (flagged) 0.7f + pulse * 0.3f else 0.85f)
                val rodCol = when {
                    energy > 0.85f   -> Crimson.copy(rodAlpha)
                    fNorm < 0.20f    -> Phosphor.copy(rodAlpha)
                    fNorm < 0.40f    -> Arctic.copy(rodAlpha)
                    fNorm < 0.65f    -> Amber.copy(rodAlpha)
                    else             -> Crimson.copy(rodAlpha * 0.85f)
                }
                if (rodLen > 0.8.dp.toPx()) {
                    drawLine(rodCol,
                        Offset(cx + cos(ang) * innerR, cy + sin(ang) * innerR),
                        Offset(cx + cos(ang) * (innerR + rodLen), cy + sin(ang) * (innerR + rodLen)),
                        2.8.dp.toPx(), cap = StrokeCap.Round)
                }
            }
        }

        if (scanning) {
            repeat(14) { t ->
                val ang = (sweep - t * 12f - 90f) * (PI / 180f).toFloat()
                val alpha = if (t == 0) pulse * 0.78f else 0.042f * (14 - t)
                val endR = R * (0.79f - t * 0.009f)
                drawLine(
                    Brush.linearGradient(listOf(col.copy(0f), col.copy(alpha)), Offset(cx, cy),
                        Offset(cx + cos(ang) * endR, cy + sin(ang) * endR)),
                    Offset(cx, cy), Offset(cx + cos(ang) * endR, cy + sin(ang) * endR),
                    (if (t == 0) 2.8f else 1f).dp.toPx())
            }
        }

        repeat(20) { p ->
            val ang1 = (pRot  + p * 18f) * (PI / 180f).toFloat()
            val ang2 = (pRot2 + p * 22f) * (PI / 180f).toFloat()
            val r1 = R * (0.66f + (p % 4) * 0.04f)
            val r2 = R * (0.70f + (p % 3) * 0.05f)
            val alpha1 = (0.18f + (p % 5) * 0.08f) * (if (flagged) 0.6f + pulse * 0.4f else 0.75f)
            val alpha2 = alpha1 * 0.5f
            drawCircle(col.copy(alpha1), (if (p % 4 == 0) 3.2f else 2f).dp.toPx(),
                Offset(cx + cos(ang1) * r1, cy + sin(ang1) * r1))
            drawCircle(colMid.copy(alpha2), 1.8.dp.toPx(),
                Offset(cx + cos(ang2) * r2, cy + sin(ang2) * r2))
        }

        if (animRaw > 0.01f) {
            drawArc(col.copy(0.22f), -90f, 360f * animRaw, false,
                Offset(cx - R * 0.57f, cy - R * 0.57f), Size(R * 1.14f, R * 1.14f),
                style = Stroke(2.8.dp.toPx(), cap = StrokeCap.Round))
        }

        if (animPred > animScore + 0.03f && scanning) {
            drawArc(col.copy(0.18f), -90f, 360f * animPred, false,
                Offset(cx - R * 0.80f, cy - R * 0.80f), Size(R * 1.60f, R * 1.60f),
                style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f), 0f)))
        }

        val arcR = R * 0.89f
        val arcOff = Offset(cx - arcR, cy - arcR); val arcSz = Size(arcR * 2f, arcR * 2f)
        drawArc(colDim, -90f, 360f, false, arcOff, arcSz, style = Stroke(9.dp.toPx(), cap = StrokeCap.Butt))

        if (animScore > 0.01f) {
            val segs = 72
            val filled = (animScore * segs).toInt().coerceAtLeast(1)
            val segSweep = 360f / segs + 0.4f
            for (s in 0 until filled) {
                val segStart = -90f + s * (360f / segs)
                val segFrac = s.toFloat() / segs
                val segAlpha = if (flagged && scanning) 0.7f + pulse * 0.3f else 0.92f
                val segCol = when {
                    segFrac < 0.35f -> Phosphor.copy(segAlpha)
                    segFrac < 0.60f -> Amber.copy(segAlpha)
                    else            -> Crimson.copy(segAlpha)
                }
                drawArc(segCol, segStart, segSweep, false, arcOff, arcSz,
                    style = Stroke(9.dp.toPx(), cap = StrokeCap.Butt))
            }
        }

        when {
            flagged && scanning -> {
                drawCircle(col.copy(core * 0.13f), R * 0.26f, Offset(cx, cy))
                drawCircle(col.copy(core * 0.28f), R * 0.15f, Offset(cx, cy))
                drawCircle(col.copy(core * 0.55f), R * 0.07f, Offset(cx, cy))
                drawCircle(col.copy(core * 0.90f), R * 0.03f, Offset(cx, cy))
            }
            scanning -> {
                drawCircle(col.copy(core * 0.07f), R * 0.18f, Offset(cx, cy))
                drawCircle(col.copy(core * 0.20f), R * 0.07f, Offset(cx, cy))
            }
        }
    }
}

private fun DrawScope.drawHexGrid(cx: Float, cy: Float, R: Float, color: Color) {
    val hs = R * 0.17f; val hW = hs * 2f; val hH = sqrt(3f) * hs
    for (row in -10..10) for (col in -10..10) {
        val ox = cx + col * hW * 0.75f
        val oy = cy + row * hH + (if (col % 2 != 0) hH / 2f else 0f)
        val dist = sqrt((ox - cx).pow(2) + (oy - cy).pow(2))
        if (dist > R * 0.88f) continue
        val a = (1f - dist / R).coerceIn(0f, 1f) * 0.45f
        for (i in 0..5) {
            val a1 = (i * 60f - 30f) * (PI / 180f).toFloat()
            val a2 = ((i + 1) * 60f - 30f) * (PI / 180f).toFloat()
            drawLine(color.copy(a * 0.55f),
                Offset(ox + cos(a1) * hs, oy + sin(a1) * hs),
                Offset(ox + cos(a2) * hs, oy + sin(a2) * hs),
                0.4.dp.toPx())
        }
    }
}

@Composable
fun BiomarkerPanel(bio: AudioBiomarkers, modifier: Modifier = Modifier) {
    val v0 by animateFloatAsState((bio.spectralCentroid / 8000f).coerceIn(0f, 1f), tween(380), label = "b0")
    val v1 by animateFloatAsState((bio.spectralSpread   / 4000f).coerceIn(0f, 1f), tween(380), label = "b1")
    val v2 by animateFloatAsState(bio.spectralFlux,                                              tween(380), label = "b2")
    val v3 by animateFloatAsState(bio.toneratio,                                                 tween(380), label = "b3")
    val v4 by animateFloatAsState(((bio.energyDb + 96f) / 96f).coerceIn(0f, 1f),               tween(380), label = "b4")
    val v5 by animateFloatAsState((bio.zeroCrossRate / 8000f).coerceIn(0f, 1f),                 tween(380), label = "b5")
    val v6 by animateFloatAsState((bio.f0Estimate / 400f).coerceIn(0f, 1f),                     tween(380), label = "b6")
    val v7 by animateFloatAsState(bio.spectralEntropy,                                           tween(380), label = "b7")

    val vals   = listOf(v0, v1, v2, v3, v4, v5, v6, v7)
    val labels = listOf("CENTROID","SPREAD","FLUX","TONAL","ENERGY","ZCR","F0","ENTROPY")
    val readings = listOf(
        "${bio.spectralCentroid.toInt()}Hz",
        "${bio.spectralSpread.toInt()}Hz",
        "${"%.3f".format(bio.spectralFlux)}",
        "${"%.2f".format(bio.toneratio)}",
        "${bio.energyDb.toInt()}dB",
        "${bio.zeroCrossRate.toInt()}Hz",
        "${bio.f0Estimate.toInt()}Hz",
        "${"%.2f".format(bio.spectralEntropy)}"
    )

    Row(modifier
        .background(AbyssPanel, RoundedCornerShape(4.dp))
        .border(0.5.dp, AbyssStroke, RoundedCornerShape(4.dp))
        .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Canvas(Modifier.size(160.dp)) {
            val cx = size.width / 2f; val cy = size.height / 2f
            val R  = minOf(cx, cy) - 20.dp.toPx()
            val n  = 8

            listOf(0.25f, 0.50f, 0.75f, 1.0f).forEach { fr ->
                drawCircle(
                    if (fr == 0.75f) Amber.copy(0.12f) else AbyssStroke.copy(0.4f),
                    R * fr, Offset(cx, cy),
                    style = Stroke(if (fr == 0.75f) 1.dp.toPx() else 0.4.dp.toPx())
                )
            }

            repeat(n) { i ->
                val ang = (i * 360f / n - 90f) * (PI / 180f).toFloat()
                drawLine(AbyssStroke2.copy(0.6f), Offset(cx, cy),
                    Offset(cx + cos(ang) * R, cy + sin(ang) * R), 0.4.dp.toPx())
            }

            val path = Path()
            vals.forEachIndexed { i, v ->
                val ang = (i * 360f / n - 90f) * (PI / 180f).toFloat()
                val x = cx + cos(ang) * R * v; val y = cy + sin(ang) * R * v
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()

            val maxV = vals.maxOrNull() ?: 0f
            val fillCol = when { maxV >= 0.75f -> Crimson; maxV >= 0.45f -> Amber; else -> Phosphor }
            drawPath(path, fillCol.copy(0.14f))
            drawPath(path, fillCol.copy(0.75f), style = Stroke(1.8.dp.toPx(), join = StrokeJoin.Round))

            vals.forEachIndexed { i, v ->
                val ang = (i * 360f / n - 90f) * (PI / 180f).toFloat()
                val x = cx + cos(ang) * R * v; val y = cy + sin(ang) * R * v
                val dotCol = when { v >= 0.75f -> Crimson; v >= 0.45f -> Amber; else -> Phosphor }
                drawCircle(dotCol, 3.dp.toPx(), Offset(x, y))
                drawCircle(dotCol.copy(0.25f), 6.dp.toPx(), Offset(x, y))

                val lx = cx + cos(ang) * (R + 13.dp.toPx())
                val ly = cy + sin(ang) * (R + 13.dp.toPx())
                drawContext.canvas.nativeCanvas.drawText(
                    labels[i], lx, ly + 4.dp.toPx(),
                    android.graphics.Paint().apply {
                        color = InkMid.toArgb(); textSize = 7.5.sp.toPx()
                        textAlign = android.graphics.Paint.Align.CENTER
                        typeface = android.graphics.Typeface.MONOSPACE; isAntiAlias = true
                    }
                )
            }
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("BIOMARKERS", fontFamily = FontFamily.Monospace, fontSize = 7.sp,
                color = InkMid, letterSpacing = 2.sp)
            Spacer(Modifier.height(2.dp))
            vals.forEachIndexed { i, v ->
                val col = when { v >= 0.75f -> Crimson; v >= 0.45f -> Amber; else -> Phosphor }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(labels[i].take(4), fontFamily = FontFamily.Monospace, fontSize = 6.sp,
                        color = InkMid, modifier = Modifier.width(28.dp))
                    Box(Modifier.weight(1f).height(4.dp)
                        .clip(RoundedCornerShape(2.dp)).background(AbyssStroke)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(v.coerceIn(0f, 1f))
                            .clip(RoundedCornerShape(2.dp)).background(col))
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(readings[i], fontFamily = FontFamily.Monospace, fontSize = 6.sp,
                        color = InkBright, modifier = Modifier.width(42.dp), textAlign = TextAlign.End)
                }
            }
        }
    }
}

@Composable
fun SpectralWaterfall(history: List<FloatArray>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (history.isEmpty()) return@Canvas
        val cw = size.width / history.size.toFloat()
        val bh = size.height / 80f
        history.forEachIndexed { ci, frame ->
            frame.take(80).forEachIndexed { bi, v ->
                drawRect(
                    plasmaColor(((v + 80f) / 80f).coerceIn(0f, 1f)),
                    Offset(ci * cw, size.height - (bi + 1) * bh),
                    Size(cw + 0.6f, bh + 0.6f)
                )
            }
        }
    }
}

private fun plasmaColor(v: Float): Color {
    val (r, g, b, a) = when {
        v < 0.18f -> { val t = v / 0.18f;  floatArrayOf(t * 0.12f, 0f, t * 0.28f, 0.95f) }
        v < 0.36f -> { val t = (v - 0.18f) / 0.18f; floatArrayOf(0.12f + t * 0.48f, t * 0.04f, 0.28f + t * 0.22f, 0.95f) }
        v < 0.54f -> { val t = (v - 0.36f) / 0.18f; floatArrayOf(0.60f + t * 0.20f, 0.04f + t * 0.22f, 0.50f - t * 0.38f, 0.96f) }
        v < 0.72f -> { val t = (v - 0.54f) / 0.18f; floatArrayOf(0.80f + t * 0.14f, 0.26f + t * 0.42f, 0.12f - t * 0.12f, 0.97f) }
        v < 0.90f -> { val t = (v - 0.72f) / 0.18f; floatArrayOf(0.94f + t * 0.06f, 0.68f + t * 0.22f, t * 0.35f, 0.98f) }
        else      -> { val t = (v - 0.90f) / 0.10f; floatArrayOf(1f, 0.90f + t * 0.10f, 0.35f + t * 0.65f, 1f) }
    }
    // Clamp to [0,1] — float arithmetic in ramps can produce tiny negatives
    // (e.g. green = -0.002) which crash with IllegalArgumentException on the sRGB model.
    return Color(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f), a.coerceIn(0f, 1f))
}


@Composable
fun SeismographView(history: List<Float>, threshold: Float = 0.60f) {
    val inf = rememberInfiniteTransition(label = "seis")
    val cursor by inf.animateFloat(0.4f, 1f, infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "sc")

    Canvas(Modifier.fillMaxWidth().height(72.dp)) {
        val w = size.width; val h = size.height
        val pL = 2.dp.toPx(); val pR = 2.dp.toPx()
        val pT = 4.dp.toPx(); val pB = 4.dp.toPx()
        val cW = w - pL - pR; val cH = h - pT - pB

        listOf(0.25f, 0.50f, 0.75f).forEach { fr ->
            val y = pT + (1f - fr) * cH
            drawLine(AbyssStroke.copy(0.4f), Offset(pL, y), Offset(w - pR, y), 0.35.dp.toPx())
        }

        val thrY = pT + (1f - threshold.coerceIn(0f, 1f)) * cH
        drawLine(Amber.copy(0.55f), Offset(pL, thrY), Offset(w - pR, thrY), 0.9.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f))

        val data = if (history.isEmpty()) {
            List(80) { i -> (sin(i * 0.18) * 0.018 + 0.025).toFloat() }
        } else history

        val n = data.size
        val xs = cW / n.toFloat().coerceAtLeast(1f)

        val fillPath = Path()
        fillPath.moveTo(pL, pT + cH)
        data.forEachIndexed { i, v ->
            fillPath.lineTo(pL + i * xs, pT + (1f - v.coerceIn(0f, 1f)) * cH)
        }
        fillPath.lineTo(pL + (n - 1) * xs, pT + cH); fillPath.close()
        val lastV = data.lastOrNull() ?: 0f
        val baseCol = when { lastV >= 0.60f -> Crimson; lastV >= 0.35f -> Amber; else -> Phosphor }
        drawPath(fillPath, baseCol.copy(0.07f))

        for (i in 1 until n) {
            val x1 = pL + (i - 1) * xs; val y1 = pT + (1f - data[i - 1].coerceIn(0f, 1f)) * cH
            val x2 = pL + i * xs;       val y2 = pT + (1f - data[i].coerceIn(0f, 1f)) * cH
            val vc = data[i]
            val segCol = when { vc >= 0.60f -> Crimson; vc >= 0.35f -> Amber; else -> Phosphor }
            drawLine(segCol.copy(0.14f), Offset(x1, y1), Offset(x2, y2), 5.dp.toPx(), cap = StrokeCap.Round)
            drawLine(segCol.copy(0.92f), Offset(x1, y1), Offset(x2, y2), 1.8.dp.toPx(), cap = StrokeCap.Round)
        }

        if (n > 0) {
            val lastX = pL + (n - 1) * xs
            val lastY = pT + (1f - lastV.coerceIn(0f, 1f)) * cH
            drawCircle(baseCol.copy(0.25f), 8.dp.toPx(), Offset(lastX, lastY))
            drawCircle(baseCol.copy(0.5f + cursor * 0.5f), 4.dp.toPx(), Offset(lastX, lastY))
        }
    }
}

@Composable
fun AttributionMatrix(attribution: Attribution?, ema: Float, modifier: Modifier = Modifier) {
    val engines = listOf("ElevenLabs", "TortoiseTS", "VALL-E", "RealTime")
    val inf = rememberInfiniteTransition(label = "am")
    val matchPulse by inf.animateFloat(0.45f, 1f, infiniteRepeatable(tween(580), RepeatMode.Reverse), label = "mp")

    Column(modifier
        .background(AbyssPanel, RoundedCornerShape(4.dp))
        .border(0.5.dp, AbyssStroke, RoundedCornerShape(4.dp))
        .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text("ENGINE FINGERPRINT", fontFamily = FontFamily.Monospace, fontSize = 7.sp,
            color = InkMid, letterSpacing = 2.sp)
        engines.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                row.forEach { engine ->
                    val isMatch = attribution?.engine == engine && ema >= 0.25f
                    val conf    = if (isMatch) (attribution?.confidence ?: 0f) else 0f
                    EngineCell(engine, conf, isMatch, matchPulse, Modifier.weight(1f))
                }
            }
        }
        if (attribution != null && attribution.engine != "Unknown" && ema >= 0.25f) {
            Text(attribution.reason, fontFamily = FontFamily.Monospace, fontSize = 8.sp,
                color = InkBright, letterSpacing = 0.3.sp)
        }
    }
}

@Composable
private fun EngineCell(engine: String, confidence: Float, isMatch: Boolean, pulse: Float, modifier: Modifier = Modifier) {
    val animC by animateFloatAsState(confidence, tween(380), label = "ec_$engine")
    Box(modifier.height(50.dp)
        .background(if (isMatch) CrimsonDim else AbyssRim, RoundedCornerShape(3.dp))
        .border(if (isMatch) 1.dp else 0.5.dp,
            if (isMatch) Amber.copy(pulse) else AbyssStroke,
            RoundedCornerShape(3.dp)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(engine, fontFamily = FontFamily.Monospace, fontSize = 6.5.sp,
                color = if (isMatch) Amber else InkMid, letterSpacing = 0.4.sp,
                fontWeight = if (isMatch) FontWeight.Bold else FontWeight.Normal)
            if (animC > 0.01f) {
                Spacer(Modifier.height(2.dp))
                Text("${(animC * 100).toInt()}%", fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp, color = Amber, fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
fun EnsembleWeightDisplay(
    weights: Map<String, Float>,
    dominantSignal: String,
    conflictFlag: Boolean,
    modifier: Modifier = Modifier
) {
    val inf = rememberInfiniteTransition(label = "ew")
    val confPulse by inf.animateFloat(0.5f, 1f, infiniteRepeatable(tween(400), RepeatMode.Reverse), label = "cf")

    val keys   = listOf("AASIST", "BIO", "ATTR", "CONT")
    val colors = listOf(Crimson, Phosphor, Amber, Arctic)

    Column(modifier
        .background(AbyssPanel, RoundedCornerShape(4.dp))
        .border(0.5.dp, AbyssStroke, RoundedCornerShape(4.dp))
        .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("ENSEMBLE WEIGHTS", fontFamily = FontFamily.Monospace, fontSize = 7.sp,
                color = InkMid, letterSpacing = 2.sp)
            if (conflictFlag) {
                Text("⚡ CONFLICT", fontFamily = FontFamily.Monospace, fontSize = 6.sp,
                    color = Amber.copy(confPulse), letterSpacing = 1.sp)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            keys.forEachIndexed { i, key ->
                val w      = weights[key] ?: (if (key == "AASIST") 0.55f else if (key == "BIO") 0.25f else 0.10f)
                val animW  by animateFloatAsState(w, tween(500), label = "ew_$key")
                val isDom  = dominantSignal == key
                val col    = colors[i]

                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Box(Modifier.fillMaxWidth().height(36.dp)
                        .background(if (isDom) col.copy(0.12f) else AbyssRim, RoundedCornerShape(2.dp))
                        .border(if (isDom) 0.5.dp else 0.dp, col.copy(0.45f), RoundedCornerShape(2.dp)),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Box(Modifier.fillMaxWidth().fillMaxHeight(animW.coerceIn(0f, 1f))
                            .background(
                                Brush.verticalGradient(listOf(col.copy(0.4f), col.copy(0.85f))),
                                RoundedCornerShape(2.dp)))
                    }
                    Text(key, fontFamily = FontFamily.Monospace, fontSize = 5.5.sp,
                        color = if (isDom) col else InkMid, letterSpacing = 0.4.sp)
                    Text("${(w * 100).toInt()}%", fontFamily = FontFamily.Monospace, fontSize = 7.sp,
                        color = if (isDom) col else Ink, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun ProtectionGauge(score: Int, modifier: Modifier = Modifier) {
    val anim by animateFloatAsState(score / 100f, tween(700), label = "pg")
    val inf  = rememberInfiniteTransition(label = "pgi")
    val glow by inf.animateFloat(0.6f, 1f, infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pgg")
    val col  = when { score > 70 -> Phosphor; score > 40 -> Amber; else -> Crimson }

    Canvas(modifier.size(60.dp)) {
        val cx = size.width / 2f; val cy = size.height / 2f
        val R  = minOf(cx, cy) - 5.dp.toPx()

        drawArc(AbyssStroke, -210f, 240f, false,
            Offset(cx - R, cy - R), Size(R * 2, R * 2),
            style = Stroke(4.5.dp.toPx(), cap = StrokeCap.Round))

        if (anim > 0.01f) {
            drawArc(col.copy(0.20f), -210f, 240f * anim, false,
                Offset(cx - R * 1.08f, cy - R * 1.08f), Size(R * 2.16f, R * 2.16f),
                style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            drawArc(col.copy(glow), -210f, 240f * anim, false,
                Offset(cx - R, cy - R), Size(R * 2, R * 2),
                style = Stroke(4.5.dp.toPx(), cap = StrokeCap.Round))
        }

        drawContext.canvas.nativeCanvas.drawText("$score",
            cx, cy + 5.dp.toPx(),
            android.graphics.Paint().apply {
                color = col.toArgb(); textSize = 11.dp.toPx()
                textAlign = android.graphics.Paint.Align.CENTER
                typeface = android.graphics.Typeface.MONOSPACE; isAntiAlias = true
            })
        drawContext.canvas.nativeCanvas.drawText("SHD",
            cx, cy + 13.dp.toPx(),
            android.graphics.Paint().apply {
                color = InkMid.toArgb(); textSize = 6.5.dp.toPx()
                textAlign = android.graphics.Paint.Align.CENTER
                typeface = android.graphics.Typeface.MONOSPACE; isAntiAlias = true
            })
    }
}

@Composable
fun RetinalOscilloscope(
    samples: FloatArray, score: Float,
    events: List<AnomalyEvent>, modifier: Modifier = Modifier,
) {
    val inf  = rememberInfiniteTransition(label = "osc")
    val glow by inf.animateFloat(0.45f, 1f, infiniteRepeatable(tween(680, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "og")
    val col  = when { score >= .60f -> Crimson; score >= .35f -> Amber; else -> Phosphor }
    val spike = events.lastOrNull()?.let { System.currentTimeMillis() - it.timestampMs < 4000L } == true

    Canvas(modifier) {
        val w = size.width; val h = size.height; val mid = h / 2f
        drawLine(AbyssStroke, Offset(0f, mid), Offset(w, mid), 0.5.dp.toPx())
        listOf(0.25f, 0.75f).forEach { drawLine(AbyssStroke.copy(0.3f), Offset(0f, h * it), Offset(w, h * it), 0.3.dp.toPx()) }
        drawLine(AbyssStroke.copy(0.3f), Offset(w / 2f, 0f), Offset(w / 2f, h), 0.3.dp.toPx())
        if (samples.isEmpty()) return@Canvas

        val path = Path()
        samples.forEachIndexed { i, v ->
            val x = i.toFloat() / (samples.size - 1) * w
            val y = mid - v.coerceIn(-1f, 1f) * (mid - 3.dp.toPx())
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        drawPath(path, col.copy(glow * 0.10f), style = Stroke(10.dp.toPx(), cap = StrokeCap.Round))
        drawPath(path, col.copy(glow * 0.35f), style = Stroke(4.dp.toPx(),  cap = StrokeCap.Round))
        drawPath(path, col.copy(0.92f),         style = Stroke(1.2.dp.toPx(), cap = StrokeCap.Round))

        if (spike) {
            val sx = w * 0.78f
            drawRect(Crimson.copy(glow * 0.10f), Offset(sx - 8.dp.toPx(), 0f), Size(16.dp.toPx(), h))
            drawLine(Crimson.copy(glow), Offset(sx, 2.dp.toPx()), Offset(sx, h - 2.dp.toPx()), 1.5.dp.toPx())
            drawCircle(Crimson.copy(glow),       4.5.dp.toPx(), Offset(sx, mid - mid * 0.70f))
            drawCircle(Crimson.copy(glow),       4.5.dp.toPx(), Offset(sx, mid + mid * 0.70f))
            drawCircle(Crimson.copy(glow * 0.2f), 11.dp.toPx(), Offset(sx, mid - mid * 0.70f))
            drawCircle(Crimson.copy(glow * 0.2f), 11.dp.toPx(), Offset(sx, mid + mid * 0.70f))
        }
    }
}

@Composable
fun EscalationBanner(result: EscalationResult, modifier: Modifier = Modifier) {
    val inf   = rememberInfiniteTransition(label = "esb")
    val pulse by inf.animateFloat(0.55f, 1f, infiniteRepeatable(tween(560), RepeatMode.Reverse), label = "ep")

    val (bg, brd, textCol, icon, text) = when (val s = result.state) {
        is EscalationState.Imminent -> Quintuple(CrimsonGhost, Crimson.copy(0.52f), Crimson, "⚠",
            "THREAT IMMINENT · ~${s.windowsAway} WINDOWS · PRED ${(s.predictedScore*100).toInt()}%")
        is EscalationState.Warning  -> Quintuple(AmberDim.copy(0.42f), Amber.copy(0.42f), Amber, "△",
            "RISING THREAT · ~${s.windowsAway} WINDOWS · PRED ${(s.predictedScore*100).toInt()}%")
        EscalationState.Declining   -> Quintuple(PhosphorGhost.copy(0.28f), Phosphor.copy(0.24f), Phosphor, "↓",
            "THREAT DECLINING · SLOPE ${(result.slope*100).toInt()}%/WIN")
        else -> return
    }

    Row(modifier.fillMaxWidth()
        .background(bg, RoundedCornerShape(3.dp))
        .border(0.5.dp, brd, RoundedCornerShape(3.dp))
        .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Canvas(Modifier.size(18.dp)) {
                val cx = size.width / 2f; val cy = size.height / 2f
                val rr = minOf(cx, cy) - 1.dp.toPx()
                drawCircle(textCol.copy(0.12f), rr, Offset(cx, cy))
                drawCircle(textCol.copy(if (result.state is EscalationState.Imminent) pulse * 0.4f else 0.3f), rr, Offset(cx, cy),
                    style = Stroke(1.5.dp.toPx()))
                drawContext.canvas.nativeCanvas.drawText(icon, cx, cy + 5.dp.toPx(),
                    android.graphics.Paint().apply {
                        color = textCol.copy(if (result.state is EscalationState.Imminent) pulse else 1f).toArgb()
                        textSize = 9.dp.toPx(); textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true
                    })
            }
            Text(text, fontFamily = FontFamily.Monospace, fontSize = 8.sp,
                color = textCol, letterSpacing = 0.5.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("PROTECTION", fontFamily = FontFamily.Monospace, fontSize = 6.sp,
                color = InkMid, letterSpacing = 1.sp)
            Text("${result.protectionPct}%", fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                color = when { result.protectionPct > 70 -> Phosphor; result.protectionPct > 40 -> Amber; else -> Crimson })
        }
    }
}

@Composable
fun DataCell(label: String, value: String, valueColor: Color, modifier: Modifier = Modifier) {
    Column(modifier
        .background(AbyssPanel, RoundedCornerShape(4.dp))
        .border(0.5.dp, AbyssStroke, RoundedCornerShape(4.dp))
        .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(label, fontFamily = FontFamily.Monospace, fontSize = 7.sp,
            color = InkMid, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(3.dp))
        Text(value, fontFamily = FontFamily.Monospace, fontSize = 18.sp,
            fontWeight = FontWeight.Black, color = valueColor)
    }
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier
        .background(AbyssPanel, RoundedCornerShape(4.dp))
        .border(0.5.dp, AbyssStroke, RoundedCornerShape(4.dp))
        .padding(10.dp), content = content)
}

@Composable
fun ProbabilityBar(score: Float) {
    val w   by animateFloatAsState(score.coerceIn(0f, 1f), tween(500), label = "pb")
    val col = when { score >= .60f -> Crimson; score >= .35f -> Amber; else -> Phosphor }
    Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(AbyssStroke)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(w.coerceIn(0f, 1f)).clip(RoundedCornerShape(2.dp))
            .background(Brush.horizontalGradient(listOf(col.copy(0.7f), col))))
    }
}

@Composable
fun WaveformView(waveformSamples: FloatArray, events: List<AnomalyEvent>, isScanning: Boolean) {
    RetinalOscilloscope(if (isScanning) waveformSamples else FloatArray(0),
        0f, events, Modifier.fillMaxWidth().height(56.dp))
}

@Composable
fun TinyLabel2(text: String, color: Color) {
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 7.sp,
        color = color, letterSpacing = 1.2.sp)
}

private data class Quintuple<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)
private operator fun <A,B,C,D,E> Quintuple<A,B,C,D,E>.component1() = a
private operator fun <A,B,C,D,E> Quintuple<A,B,C,D,E>.component2() = b
private operator fun <A,B,C,D,E> Quintuple<A,B,C,D,E>.component3() = c
private operator fun <A,B,C,D,E> Quintuple<A,B,C,D,E>.component4() = d
private operator fun <A,B,C,D,E> Quintuple<A,B,C,D,E>.component5() = e