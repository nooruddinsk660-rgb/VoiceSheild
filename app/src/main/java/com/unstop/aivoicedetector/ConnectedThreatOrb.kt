package com.unstop.aivoicedetector

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.*


// ── Full ThreatOrb — accepts OrbReadState snapshot ───────────────
@Composable
fun ThreatOrb(
    modifier: Modifier = Modifier,
    read:     OrbReadState,
) {
    val textMeasurer = rememberTextMeasurer()

    Canvas(modifier = modifier.aspectRatio(1f)) {
        val cx = size.width  / 2f
        val cy = size.height / 2f
        val R  = size.minDimension * 0.42f

        val wobX = if (read.hasDrift) sin(read.driftWobble * PI.toFloat() / 4f) * 4f else 0f
        val ctr  = Offset(cx + wobX, cy)

        // 1. Outer corona glow
        drawCircle(
            Brush.radialGradient(
                listOf(read.glowColor.copy(read.pulseAlpha * 0.25f), Color.Transparent),
                center = ctr, radius = R * 2.2f,
            ), R * 2.2f, ctr,
        )

        // 2. Ambient base
        drawCircle(
            Brush.radialGradient(
                listOf(GlassTokens.Layer4, GlassTokens.Layer2, GlassTokens.Layer1),
                center = ctr, radius = R,
            ), R, ctr,
        )

        // 3. Diffuse key-light
        val keyOff = Offset(cx + wobX - R * 0.3f, cy - R * 0.3f)
        drawCircle(
            Brush.radialGradient(
                listOf(
                    read.color.copy(GlassTokens.Light.KeyIntensity * read.pulseAlpha * 0.5f),
                    read.color.copy(0.08f),
                    Color.Transparent,
                ),
                center = keyOff, radius = R * 1.2f,
            ), R, ctr,
        )

        // 4. Sphere latitude/longitude mesh
        drawSphereMesh(ctr, R, read.gridPhase, read.color.copy(0.12f))

        // 5. Orbital ring 1 (equatorial, 30° tilt)
        drawOrbitalRing(ctr, R * 1.15f, 30f, read.ringAngle,
            Arctic.copy(0.35f), Arctic.copy(0.08f), 1.2f)

        // 6. Orbital ring 2 (polar, counter-rotate)
        val ring2Off = if (read.hasDrift) -read.ring2Angle + read.driftWobble * 3f
                       else -read.ring2Angle
        drawOrbitalRing(ctr, R * 1.08f, 70f, ring2Off,
            read.rimColor.copy(0.28f), read.rimColor.copy(0.07f), 0.8f)

        // 7. Equatorial scan sweep
        clipPath(Path().also { p ->
            p.addOval(androidx.compose.ui.geometry.Rect(
                ctr.x - R, ctr.y - R, ctr.x + R, ctr.y + R))
        }) {
            val scanY = ctr.y - R + read.scanProgress * R * 2f
            drawRect(read.color.copy(0.22f), Offset(ctr.x - R, scanY - 1f), Size(R * 2f, 2f))
            drawRect(read.color.copy(0.06f), Offset(ctr.x - R, scanY - 18f), Size(R * 2f, 18f))
        }

        // 8. Specular highlight (Blinn-Phong, top-left)
        val spec = Offset(cx + wobX - R * 0.28f, cy - R * 0.30f)
        drawCircle(
            Brush.radialGradient(
                listOf(
                    GlassTokens.Orb.SpecularPeak.copy(0.85f * read.pulseAlpha),
                    GlassTokens.Orb.SpecularMid.copy(0.30f),
                    Color.Transparent,
                ),
                center = spec, radius = R * 0.38f,
            ), R * 0.38f, spec,
        )

        // 9. Rim light (bottom-right, score-colored)
        val rim = Offset(cx + wobX + R * 0.35f, cy + R * 0.35f)
        drawCircle(
            Brush.radialGradient(
                listOf(read.rimColor.copy(read.pulseAlpha * 0.55f), Color.Transparent),
                center = rim, radius = R * 0.70f,
            ), R * 0.70f, rim,
        )

        // 10. Glass sphere boundary
        drawCircle(GlassTokens.StrokeGlass, R, ctr, style = Stroke(1.0f))
        drawArc(
            Color.White.copy(0.15f), 200f, 110f, false,
            Offset(ctr.x - R, ctr.y - R), Size(R * 2f, R * 2f),
            style = Stroke(1.5f),
        )

        // 11. Particle corona
        drawCorona(ctr, R, read.coronaAngle, read.color)

        // 12. Score text
        val pct      = (read.score * 100).toInt()
        val scoreTxt = textMeasurer.measure(
            AnnotatedString("$pct"),
            TextStyle(
                fontFamily    = FontFamily.Monospace,
                fontWeight    = FontWeight.Black,
                fontSize      = (R * 0.52f / density).sp,
                color         = read.color,
                letterSpacing = (-1).sp
            ),
        )
        val labelTxt = textMeasurer.measure(
            AnnotatedString(read.label),
            TextStyle(
                fontFamily    = FontFamily.Monospace,
                fontWeight    = FontWeight.Normal,
                fontSize      = (R * 0.105f / density).sp,
                color         = read.color.copy(0.75f),
                letterSpacing = 2.sp
            ),
        )
        val subTxt = textMeasurer.measure(
            AnnotatedString("SYNTHETIC CONF"),
            TextStyle(
                fontFamily    = FontFamily.Monospace,
                fontWeight    = FontWeight.Normal,
                fontSize      = (R * 0.082f / density).sp,
                color         = InkMid,
                letterSpacing = 1.5.sp
            ),
        )
        drawText(scoreTxt, topLeft = Offset(
            ctr.x - scoreTxt.size.width / 2f, ctr.y - scoreTxt.size.height * 0.55f))
        drawText(labelTxt, topLeft = Offset(
            ctr.x - labelTxt.size.width / 2f, ctr.y + scoreTxt.size.height * 0.35f))
        drawText(subTxt, topLeft = Offset(
            ctr.x - subTxt.size.width / 2f,
            ctr.y + scoreTxt.size.height * 0.35f + labelTxt.size.height * 1.1f))
    }
}

// ── Mini orb (header) — also takes OrbReadState ──────────────────
@Composable
fun ThreatOrbMini(modifier: Modifier = Modifier, read: OrbReadState) {
    ThreatOrb(modifier.size(48.dp), read)
}

// ── Convenience overload: takes StateFlow directly (demo/test use) ─
@Composable
fun ThreatOrbConnected(
    modifier:  Modifier = Modifier,
    orbFlow:   StateFlow<OrbState>,
    size:      androidx.compose.ui.unit.Dp = 190.dp,
) {
    val params by rememberOrbRenderParams(orbFlow)
    val anim    = rememberOrbAnimationState()
    OrbAnimationDriver(anim, params)
    val read    = rememberOrbReadState(anim, params)
    ThreatOrb(modifier.size(size), read)
}

// ── Canvas helpers (same 3D math as before) ───────────────────────

private fun DrawScope.drawSphereMesh(center: Offset, R: Float, phase: Float, color: Color) {
    val latCount = 6; val lonCount = 8
    for (i in 1 until latCount) {
        val phi  = PI.toFloat() * i / latCount
        val y    = center.y - R * cos(phi)
        val rLat = R * sin(phi)
        if (rLat < 2f) continue
        drawOval(color, Offset(center.x - rLat, y - rLat * 0.3f),
            Size(rLat * 2f, rLat * 0.6f), style = Stroke(0.5f))
    }
    for (j in 0 until lonCount) {
        val theta = (PI * 2f * j / lonCount + phase * PI * 2f).toFloat()
        val cosT  = cos(theta)
        val rx    = R * 0.5f; val ry = R
        val cx2   = center.x + R * 0.5f * cosT
        drawOval(
            color.copy(alpha = color.alpha * (0.4f + 0.6f * abs(cosT))),
            Offset(cx2 - rx, center.y - ry),
            Size(rx * 2f, ry * 2f),
            style = Stroke(0.4f),
        )
    }
}

private fun DrawScope.drawOrbitalRing(
    center: Offset, radius: Float, tiltDeg: Float, rotateDeg: Float,
    color: Color, glowColor: Color, strokeWidth: Float,
) {
    val ry = radius * sin(tiltDeg * PI.toFloat() / 180f)
    withTransform({ rotate(rotateDeg % 360f, center) }) {
        drawOval(glowColor, Offset(center.x - radius, center.y - ry),
            Size(radius * 2f, ry * 2f), style = Stroke(strokeWidth * 4f))
        drawOval(color, Offset(center.x - radius, center.y - ry),
            Size(radius * 2f, ry * 2f), style = Stroke(strokeWidth))
        val rotR = (rotateDeg * 3f * PI.toFloat() / 180f)
        drawCircle(color.copy(0.9f), 2.5f, Offset(
            center.x + radius * cos(rotR), center.y + ry * sin(rotR)))
    }
}

private fun DrawScope.drawCorona(center: Offset, R: Float, baseAngle: Float, color: Color) {
    val count = 12
    for (i in 0 until count) {
        val angle  = (baseAngle + i * (360f / count)) * PI.toFloat() / 180f
        val radius = R * (1.28f + 0.06f * sin(angle * 2.5f))
        val dotSize = if (i % 3 == 0) 2.8f else 1.4f
        val alpha   = if (i % 3 == 0) 0.7f else 0.35f
        drawCircle(color.copy(alpha), dotSize, Offset(
            center.x + radius * cos(angle), center.y + radius * sin(angle)))
    }
}