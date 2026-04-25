package com.unstop.aivoicedetector

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.*
import kotlin.math.*


enum class BudgetState {
    ON_TIME,     // all stages < 14 ms
    STRESSED,    // one stage > 14 ms → shed bio recompute
    OVERLOADED,  // total pipeline > 45 ms → drop alternate frames
}

data class PipelineTelemetry(
    val windowId:        Int,
    val totalMs:         Float,
    val droppedFrames:   Int,
    val budgetState:     BudgetState,
    val shed:            Boolean,
    val jitterBufferMs:  Float,    // how long frame sat in jitter buffer
)

enum class RenderPhase {
    SKELETON,      // no data yet — shimmer placeholders
    WARMING,       // first frame received, fading in
    LIVE,          // full fidelity
}

class FrameBudgetTracker {

    private val _telemetry = MutableStateFlow<PipelineTelemetry?>(null)
    val telemetry: StateFlow<PipelineTelemetry?> = _telemetry

    private val _budgetState = MutableStateFlow(BudgetState.ON_TIME)
    val budgetState: StateFlow<BudgetState> = _budgetState

    // Exponential moving average of pipeline latency
    private var emaMs = 0f
    private val alpha = 0.2f
    private var droppedTotal = 0

    fun record(frame: DecisionFrame, droppedSinceLastFrame: Int) {
        val totalMs = frame.totalPipelineNs / 1_000_000f
        emaMs       = alpha * totalMs + (1f - alpha) * emaMs
        droppedTotal += droppedSinceLastFrame

        val budget = when {
            emaMs > 45f -> BudgetState.OVERLOADED
            emaMs > 14f -> BudgetState.STRESSED
            else        -> BudgetState.ON_TIME
        }
        _budgetState.value = budget

        _telemetry.value = PipelineTelemetry(
            windowId       = frame.windowId,
            totalMs        = emaMs,
            droppedFrames  = droppedTotal,
            budgetState    = budget,
            shed           = emaMs > 14f,
            jitterBufferMs = 0f,  // filled by JitterBuffer
        )
    }

    fun reset() {
        emaMs = 0f
        droppedTotal = 0
        _budgetState.value = BudgetState.ON_TIME
        _telemetry.value = null
    }
}

class JitterBuffer(
    private val targetPeriodMs: Long   = 500L,
    private val capacity:       Int    = 3,
    private val scope:          CoroutineScope,
) {
    // Raw pipeline frames land here
    private val inbox = Channel<DecisionFrame>(capacity, BufferOverflow.DROP_OLDEST)

    // Consumer collects from this — periodic, smooth
    private val _smoothed = MutableSharedFlow<DecisionFrame>(replay = 1)
    val smoothed: SharedFlow<DecisionFrame> = _smoothed

    fun start() {
        scope.launch(Dispatchers.Default) {
            while (isActive) {
                val deadline = System.currentTimeMillis() + targetPeriodMs

                // Drain inbox: use LATEST frame in this period (most recent data wins)
                var latest: DecisionFrame? = null
                while (true) {
                    val frame = inbox.tryReceive().getOrNull() ?: break
                    latest = frame
                }

                latest?.let { frame ->
                    val bufferMs = System.currentTimeMillis() - frame.captureMs
                    _smoothed.emit(frame)
                }

                // Wait for remainder of period
                val remaining = deadline - System.currentTimeMillis()
                if (remaining > 0) delay(remaining)
            }
        }
    }

    fun push(frame: DecisionFrame) { inbox.trySend(frame) }
    fun close()                    { inbox.close() }
}

class ProgressiveRenderController {

    private val _phase   = MutableStateFlow(RenderPhase.SKELETON)
    val phase: StateFlow<RenderPhase> = _phase

    private var frameCount = 0

    fun onFrameReceived() {
        frameCount++
        _phase.value = when (frameCount) {
            1    -> RenderPhase.WARMING
            else -> RenderPhase.LIVE
        }
    }

    fun reset() {
        frameCount   = 0
        _phase.value = RenderPhase.SKELETON
    }
}

@Composable
fun Modifier.skeletonShimmer(phase: RenderPhase): Modifier {
    if (phase == RenderPhase.LIVE) return this

    val inf = rememberInfiniteTransition(label = "shimmer")

    // Sweep progress: 0 = left, 1 = right
    val sweep by inf.animateFloat(
        initialValue  = -0.4f,
        targetValue   = 1.4f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label         = "sweep",
    )

    // Dissolve alpha: 1 during SKELETON, fades during WARMING
    val alpha by animateFloatAsState(
        if (phase == RenderPhase.SKELETON) 1f else 0f,
        tween(400), label = "dissolve",
    )

    return this
        .clip(RoundedCornerShape(12.dp))
        .background(AbyssPanel)
        .drawWithContent {
            drawContent()
            // Shimmer: diagonal gradient moving left → right
            val x   = sweep * size.width
            val gradW = size.width * 0.35f
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.Transparent,
                        GlassTokens.StrokeGlass.copy(alpha = 0.6f * alpha),
                        Color.Transparent,
                    ),
                    start = Offset(x - gradW, 0f),
                    end   = Offset(x + gradW, size.height),
                )
            )
        }
}

@Composable
fun SkeletonCard(modifier: Modifier = Modifier, lines: Int = 3, phase: RenderPhase) {
    Column(
        modifier = modifier
            .skeletonShimmer(phase)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(lines) { i ->
            Box(
                Modifier
                    .fillMaxWidth(if (i == lines - 1) 0.6f else 1f)
                    .height(8.dp)
                    .background(
                        AbyssStroke2,
                        RoundedCornerShape(4.dp),
                    )
            )
        }
    }
}

@Composable
fun PerfOverlay(telemetryFlow: StateFlow<PipelineTelemetry?>) {
    val t by telemetryFlow.collectAsState()
    val tel = t ?: return

    val budgetColor = when (tel.budgetState) {
        BudgetState.ON_TIME    -> Phosphor
        BudgetState.STRESSED   -> Amber
        BudgetState.OVERLOADED -> Crimson
    }

    Row(
        Modifier
            .background(AbyssPanel.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        listOf(
            "W#" to "${tel.windowId}",
            "EMA" to "${"%.1f".format(tel.totalMs)} ms",
            "DROP" to "${tel.droppedFrames}",
            "STATE" to tel.budgetState.name,
            "SHED" to if (tel.shed) "ON" else "—",
        ).forEach { (k, v) ->
            androidx.compose.material3.Text(
                "$k $v",
                fontFamily    = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontSize      = androidx.compose.ui.unit.TextUnit(6f, androidx.compose.ui.unit.TextUnitType.Sp),
                color         = if (k == "STATE") budgetColor else InkMid,
                letterSpacing = androidx.compose.ui.unit.TextUnit(1f, androidx.compose.ui.unit.TextUnitType.Sp),
            )
        }
    }
}

suspend fun <T> withBudget(
    budgetMs:   Long    = 14L,
    name:       String  = "op",
    fallback:   T,
    block:      suspend () -> T,
): T = try {
    withTimeout(budgetMs) { block() }
} catch (e: TimeoutCancellationException) {
    android.util.Log.w("PerfGuard", "$name timed out after $budgetMs ms — using fallback")
    fallback
} catch (e: Exception) {
    android.util.Log.e("PerfGuard", "$name threw: ${e.message}")
    fallback
}