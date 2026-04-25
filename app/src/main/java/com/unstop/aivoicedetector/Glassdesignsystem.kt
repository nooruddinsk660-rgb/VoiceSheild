package com.unstop.aivoicedetector

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.*


object GlassTokens {

    val Layer0  = Color(0x00000000)                 // fully transparent (bg)
    val Layer1  = Color(0x0DFFFFFF)                 // 5%  – deepest glass
    val Layer2  = Color(0x1AFFFFFF)                 // 10% – midground panel
    val Layer3  = Color(0x26FFFFFF)                 // 15% – elevated card
    val Layer4  = Color(0x33FFFFFF)                 // 20% – modal / orb face
    val LayerHover = Color(0x40FFFFFF)              // 25% – hover lift

    val RimSafe   = Color(0x4000FF41)               // phosphor green rim
    val RimWarn   = Color(0x40FFAA00)               // amber rim
    val RimThreat = Color(0x80FF0033)               // crimson rim (stronger for alert)
    val RimInfo   = Color(0x4000CFFF)               // cryo-blue rim

    val ShadowDeep   = Color(0xCC000000)
    val ShadowMid    = Color(0x80000000)
    val ShadowLight  = Color(0x40000000)

    val StrokeGlass  = Color(0x33FFFFFF)            // inner stroke top-left lit
    val StrokeBottom = Color(0x0AFFFFFF)            // inner stroke bottom-right dim

    val ParticleBase  = Color(0x0800CFFF)
    val ParticleHigh  = Color(0x2000CFFF)

    object Orb {
        // Specular highlight (12 o'clock position)
        val SpecularPeak = Color(0xFFE0F8FF)
        val SpecularMid  = Color(0x8000CFFF)
        val SpecularDim  = Color(0x2000CFFF)

        // Safe state (phosphor)
        val SafeCore  = Color(0xFF00FF41)
        val SafeGlow  = Color(0x6000FF41)
        val SafeDeep  = Color(0x1500FF41)

        // Warn state
        val WarnCore  = Color(0xFFFFAA00)
        val WarnGlow  = Color(0x60FFAA00)
        val WarnDeep  = Color(0x15FFAA00)

        // Threat state
        val ThreatCore  = Color(0xFFFF0033)
        val ThreatGlow  = Color(0xA0FF0033)
        val ThreatDeep  = Color(0x30FF0033)

        // Orbital ring (equatorial band)
        val Ring = Color(0x5500CFFF)
        val RingGlow = Color(0x2500CFFF)
    }

    // All sizes in sp — used as constants, actual TextStyle in Theme.kt
    const val DisplaySp  = 48f     // orb score number
    const val HeadSp     = 13f     // panel headers
    const val SubSp      = 10f     // sub-labels
    const val DataSp     = 11f     // live data values
    const val MicroSp    = 7f      // micro-labels / letter-spaced caps

    val RadiusCard   = 16.dp
    val RadiusPanel  = 12.dp
    val RadiusChip   = 6.dp
    val PadCard      = 16.dp
    val PadPanel     = 12.dp

    object Light {
        // Ambient: 8% fill from all directions
        const val AmbientAlpha = 0.08f
        // Key light: top-left at ~315° azimuth
        val KeyDir = Offset(-0.6f, -0.7f)          // normalised direction
        const val KeyIntensity = 0.90f
        // Rim light: bottom-right at ~135°
        val RimDir = Offset(0.7f, 0.6f)
        const val RimIntensity = 0.40f
        // Specular: Blinn-Phong shininess exponent
        const val Shininess = 32f
    }
}

fun Float.toThreatColor(): Color = when {
    this > 0.65f -> Crimson
    this > 0.35f -> Amber
    else         -> Phosphor
}

fun Float.toThreatGlowColor(): Color = when {
    this > 0.65f -> GlassTokens.Orb.ThreatGlow
    this > 0.35f -> GlassTokens.Orb.WarnGlow
    else         -> GlassTokens.Orb.SafeGlow
}

fun Float.toRimColor(): Color = when {
    this > 0.65f -> GlassTokens.RimThreat
    this > 0.35f -> GlassTokens.RimWarn
    else         -> GlassTokens.RimSafe
}

fun Float.toThreatLabel(): String = when {
    this > 0.65f -> "AI DETECTED"
    this > 0.35f -> "ESCALATING"
    else         -> "AUTHENTIC"
}


/**
 * Core glass panel modifier.
 * Stacks: fill layer → gradient stroke → inner stroke → depth shadow.
 * score param drives rim color transition (Green→Amber→Crimson).
 */
fun Modifier.glassPanel(
    score: Float = 0f,
    layer: Color = GlassTokens.Layer2,
    shape: Shape = RoundedCornerShape(GlassTokens.RadiusPanel),
    strokeWidth: Dp = 0.8.dp,
): Modifier = composed {
    val rimColor = score.toRimColor()
    this
        .clip(shape)
        .background(layer)
        .border(
            width = strokeWidth,
            brush  = Brush.linearGradient(
                colors = listOf(GlassTokens.StrokeGlass, rimColor, GlassTokens.StrokeBottom),
                start  = Offset(0f, 0f),
                end    = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
            ),
            shape  = shape,
        )
        .drawBehind {
            // Multi-layer depth shadow
            drawRect(GlassTokens.ShadowDeep.copy(alpha = 0.20f))
        }
}

/**
 * Elevated glass card — higher layer, stronger rim.
 */
fun Modifier.glassCard(score: Float = 0f): Modifier =
    glassPanel(score, GlassTokens.Layer3, RoundedCornerShape(GlassTokens.RadiusCard), 1.dp)

/**
 * Animated breathing glow border.
 * Pulsates opacity on infinite loop at [period] ms.
 * Wire score for color; triggers critical strobe above 0.65.
 */
fun Modifier.glowBorder(score: Float, period: Int = 2000): Modifier = composed {
    val inf = rememberInfiniteTransition(label = "glow")
    val alpha by inf.animateFloat(
        initialValue = 0.35f,
        targetValue  = if (score > 0.65f) 1.0f else 0.70f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (score > 0.65f) 400 else period, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glowAlpha",
    )
    val color = score.toThreatColor().copy(alpha = alpha * 0.6f)
    drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(color, Color.Transparent),
                center = Offset(size.width / 2f, size.height / 2f),
                radius = size.maxDimension * 0.8f,
            )
        )
    }
}

/**
 * Subtle particle field background — drawn into composable surface.
 * [phase] 0..1 from a rememberInfiniteTransition — caller drives the clock.
 */
fun DrawScope.drawParticleField(phase: Float, score: Float) {
    val baseColor = score.toThreatColor().copy(alpha = 0.06f)
    val count = 40
    for (i in 0 until count) {
        val seed  = i * 7919L
        val rx    = ((seed * 1664525L + 1013904223L) and 0x7FFFFFFF).toFloat() / 0x7FFFFFFF.toFloat()
        val ry    = ((seed * 22695477L + 1L) and 0x7FFFFFFF).toFloat() / 0x7FFFFFFF.toFloat()
        val drift = ((seed * 6364136223846793005L + 1442695040888963407L) and 0x7FFFFFFF).toFloat() /
                    0x7FFFFFFF.toFloat()

        val x   = rx * size.width
        val y   = ((ry + phase * (0.3f + drift * 0.2f)) % 1f) * size.height
        val r   = 1.2f + drift * 2.4f
        val a   = 0.04f + drift * 0.12f
        drawCircle(baseColor.copy(alpha = a), r, Offset(x, y))
    }
}

/**
 * Diagonal scan line overlay — HUD aesthetic.
 * [progress] 0..1 driven by InfiniteTransition.
 */
fun DrawScope.drawScanLine(progress: Float, score: Float) {
    val y   = progress * size.height
    val col = score.toThreatColor()
    drawRect(col.copy(alpha = 0.30f), Offset(0f, y), size = androidx.compose.ui.geometry.Size(size.width, 1.5.dp.toPx()))
    drawRect(col.copy(alpha = 0.07f), Offset(0f, (y - 30.dp.toPx()).coerceAtLeast(0f)),
        size = androidx.compose.ui.geometry.Size(size.width, 30.dp.toPx()))
}

// Re-export for use in other files