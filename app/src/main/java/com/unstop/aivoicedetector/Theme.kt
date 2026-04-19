package com.unstop.aivoicedetector

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Abyss        = Color(0xFF000000)
val AbyssSurface = Color(0xFF05070A)
val AbyssPanel   = Color(0xFF090C10)
val AbyssRim     = Color(0xFF0F1419)
val AbyssStroke  = Color(0xFF1C2330)
val AbyssStroke2 = Color(0xFF283040)

val Phosphor     = Color(0xFF00FF41)  // military radar green
val PhosphorMid  = Color(0xFF00CC34)
val PhosphorDim  = Color(0xFF004D14)
val PhosphorGhost= Color(0xFF001A08)

val Crimson      = Color(0xFFFF0033)  // pure threat
val CrimsonMid   = Color(0xFFCC0028)
val CrimsonDim   = Color(0xFF330009)
val CrimsonGhost = Color(0xFF1A0005)

val Amber        = Color(0xFFFFAA00)
val AmberMid     = Color(0xFFCC8800)
val AmberDim     = Color(0xFF332200)

val Arctic       = Color(0xFF00CFFF)
val ArcticDim    = Color(0xFF00415A)

val Ink          = Color(0xFF1E2A38)
val InkMid       = Color(0xFF3A4D60)
val InkBright    = Color(0xFF7A9AB5)
val InkWhite     = Color(0xFFB8D0E8)

val Mist         = Color(0x08FFFFFF)
val MistMid      = Color(0x14FFFFFF)
val MistBright   = Color(0x28FFFFFF)

private val BloodNoir = darkColorScheme(
    primary        = Phosphor,
    secondary      = Amber,
    tertiary       = Crimson,
    background     = Abyss,
    surface        = AbyssSurface,
    onPrimary      = Abyss,
    onSecondary    = Abyss,
    onBackground   = InkWhite,
    onSurface      = InkWhite,
    surfaceVariant = AbyssPanel,
    outline        = AbyssStroke,
)

val VSTypography = Typography(
    displayLarge = TextStyle(
        fontFamily    = FontFamily.Monospace,
        fontWeight    = FontWeight.Black,
        fontSize      = 56.sp,
        lineHeight    = 60.sp,
        letterSpacing = (-1).sp,
    ),
    displayMedium = TextStyle(
        fontFamily    = FontFamily.Monospace,
        fontWeight    = FontWeight.Bold,
        fontSize      = 36.sp,
        letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontFamily    = FontFamily.Monospace,
        fontWeight    = FontWeight.Bold,
        fontSize      = 13.sp,
        letterSpacing = 3.sp,
    ),
    titleMedium = TextStyle(
        fontFamily    = FontFamily.Monospace,
        fontWeight    = FontWeight.Normal,
        fontSize      = 10.sp,
        letterSpacing = 2.sp,
        color         = InkMid,
    ),
    bodyMedium = TextStyle(
        fontFamily    = FontFamily.Monospace,
        fontSize      = 11.sp,
        letterSpacing = 0.5.sp,
        color         = InkBright,
    ),
    labelSmall = TextStyle(
        fontFamily    = FontFamily.Monospace,
        fontSize      = 8.sp,
        letterSpacing = 1.5.sp,
        color         = Ink,
    ),
)

@Composable
fun AIVoiceDetectorTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = BloodNoir, typography = VSTypography, content = content)
}

// keep old names compiling
val DarkBg     = Abyss
val SurfaceBg  = AbyssSurface
val NeonCyan   = Arctic
val NeonAmber  = Amber
val NeonRed    = Crimson
val GlassWhite = Mist
val TextDim    = Ink
val TextMid    = InkMid
val TextBright = InkBright