package com.unstop.aivoicedetector

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.*

@Composable
fun DeepfakeAlertOverlay(
    score:       Float,
    attribution: Attribution?,
    isVisible:   Boolean,
    onDismiss:   () -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(isVisible) { if (isVisible) haptic(context) }

    AnimatedVisibility(isVisible, enter = fadeIn(tween(200)), exit = fadeOut(tween(600))) {
        val inf   = rememberInfiniteTransition(label = "alert")
        val pulse by inf.animateFloat(0.70f, 1f,
            infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "p")
        val scan  by inf.animateFloat(0f, 1f,
            infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "sc")
        val stall by inf.animateFloat(0f, 1f,
            infiniteRepeatable(tween(80), RepeatMode.Reverse), label = "st")

        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF000000))
                .drawBehind {
                    // full-screen blood flood
                    drawRect(Crimson.copy(alpha = pulse * 0.18f))
                    // scan line sweeping down
                    val sy = scan * size.height
                    drawRect(Crimson.copy(alpha = 0.35f), Offset(0f, sy), androidx.compose.ui.geometry.Size(size.width, 2.dp.toPx()))
                    drawRect(Crimson.copy(alpha = 0.08f), Offset(0f, sy - 40.dp.toPx()), androidx.compose.ui.geometry.Size(size.width, 40.dp.toPx()))
                    // hex grid overlay — full screen
                    drawFullHex(Crimson.copy(alpha = pulse * 0.08f))
                    // strobing horizontal lines
                    if (stall > 0.5f) {
                        val y1 = (size.height * 0.35f)
                        drawRect(Crimson.copy(0.15f), Offset(0f, y1), androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx()))
                    }
                    // left accent stripe
                    drawRect(Crimson.copy(pulse), Offset(0f, 0f), androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height))
                    drawRect(Crimson.copy(0.08f), Offset(0f, 0f), androidx.compose.ui.geometry.Size(24.dp.toPx(), size.height))
                }
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(horizontal = 32.dp)
            ) {
                // glitch text header
                Text(
                    "⚠ THREAT DETECTED ⚠",
                    fontFamily    = FontFamily.Monospace,
                    fontSize      = 11.sp,
                    color         = Crimson.copy(pulse),
                    letterSpacing = 4.sp,
                    fontWeight    = FontWeight.Bold
                )

                // giant score
                Text(
                    "${(score * 100).toInt()}%",
                    fontFamily    = FontFamily.Monospace,
                    fontSize      = 96.sp,
                    fontWeight    = FontWeight.Black,
                    color         = Crimson.copy(alpha = pulse),
                    letterSpacing = (-4).sp,
                    lineHeight    = 96.sp
                )
                Text(
                    "SYNTHETIC CONFIDENCE",
                    fontFamily    = FontFamily.Monospace,
                    fontSize      = 8.sp,
                    color         = Crimson.copy(0.6f),
                    letterSpacing = 3.sp
                )

                // attribution box
                if (attribution != null && attribution.engine != "Unknown") {
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(CrimsonDim, RoundedCornerShape(3.dp))
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            Text("ENGINE FINGERPRINT",
                                fontFamily = FontFamily.Monospace, fontSize = 7.sp,
                                color = Crimson.copy(0.45f), letterSpacing = 2.sp)
                            Text(attribution.engine.uppercase(),
                                fontFamily = FontFamily.Monospace, fontSize = 18.sp,
                                fontWeight = FontWeight.Black, color = Amber)
                            Text(attribution.reason,
                                fontFamily    = FontFamily.Monospace,
                                fontSize      = 9.sp,
                                color         = InkMid,
                                textAlign     = TextAlign.Center,
                                modifier      = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text("TAP ANYWHERE TO DISMISS",
                    fontFamily = FontFamily.Monospace, fontSize = 7.sp,
                    color = Crimson.copy(pulse * 0.4f), letterSpacing = 2.sp)
            }
        }
    }
}

private fun DrawScope.drawFullHex(color: Color) {
    val hexSize = 28.dp.toPx()
    val hexW    = hexSize * 2f
    val hexH    = kotlin.math.sqrt(3f) * hexSize
    val cols    = (size.width  / (hexW * 0.75f)).toInt() + 2
    val rows    = (size.height / hexH).toInt() + 2
    for (row in -1..rows) for (col in -1..cols) {
        val ox = col * hexW * 0.75f
        val oy = row * hexH + (if (col % 2 != 0) hexH / 2f else 0f)
        for (i in 0..5) {
            val a1 = (i * 60f - 30f) * (Math.PI / 180f).toFloat()
            val a2 = ((i + 1) * 60f - 30f) * (Math.PI / 180f).toFloat()
            drawLine(color,
                Offset(ox + kotlin.math.cos(a1) * hexSize, oy + kotlin.math.sin(a1) * hexSize),
                Offset(ox + kotlin.math.cos(a2) * hexSize, oy + kotlin.math.sin(a2) * hexSize),
                0.5.dp.toPx())
        }
    }
}

@Suppress("DEPRECATION")
private fun haptic(ctx: Context) {
    val p = longArrayOf(0, 350, 80, 150, 80, 350)
    val a = intArrayOf(0, 255, 0, 200, 0, 255)
    try {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                    .defaultVibrator.vibrate(VibrationEffect.createWaveform(p, a, -1))
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                (ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
                    .vibrate(VibrationEffect.createWaveform(p, a, -1))
            else ->
                (ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(p, -1)
        }
    } catch (_: Exception) {}
}