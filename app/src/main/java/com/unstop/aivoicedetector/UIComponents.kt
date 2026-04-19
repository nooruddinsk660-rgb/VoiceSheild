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
import androidx.compose.ui.viewinterop.AndroidView
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import kotlin.math.*

/* ═══════════════════════════════════════════════════════════════
   SESSION TIMELINE RULER
   ═══════════════════════════════════════════════════════════════ */
@Composable
fun SessionTimelineRuler(
    startMs: Long, elapsedMs: Long, totalMs: Long,
    events: List<AnomalyEvent>,
    fileWindow: Int = -1, totalWindows: Int = 0,
    modifier: Modifier = Modifier,
) {
    val inf = rememberInfiniteTransition(label = "tl")
    val dot     by inf.animateFloat(.4f,1f, infiniteRepeatable(tween(800,easing=FastOutSlowInEasing),RepeatMode.Reverse), label="d")
    val shimmer by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2000,easing=LinearEasing)), label="sh")

    val progress = when {
        totalMs > 0                            -> (elapsedMs.toFloat()/totalMs).coerceIn(0f,1f)
        totalWindows > 0 && fileWindow >= 0   -> (fileWindow.toFloat()/totalWindows).coerceIn(0f,1f)
        else -> 0f
    }

    Canvas(modifier) {
        val w=size.width; val h=size.height; val ty=h*.55f; val th=3.dp.toPx()

        drawRect(Brush.horizontalGradient(listOf(
            PhosphorDim.copy(shimmer*.3f+.1f), Phosphor.copy(.15f), PhosphorDim.copy((1f-shimmer)*.3f+.1f)
        ),0f,w), Offset(0f,ty), Size(w,th))

        val fw = w * progress
        drawRect(Brush.horizontalGradient(listOf(Phosphor.copy(.6f),Phosphor),0f,fw.coerceAtLeast(1f)),
            Offset(0f,ty), Size(fw,th))

        repeat(11) { i ->
            val tx=w*i/10f; val maj=i%5==0
            drawLine(if(maj) AbyssStroke2 else AbyssStroke,
                Offset(tx,ty-if(maj)6.dp.toPx() else 3.dp.toPx()), Offset(tx,ty),
                if(maj) 1.dp.toPx() else .5.dp.toPx())
        }

        events.forEach { ev ->
            val ep = when {
                totalMs>0 -> ((ev.timestampMs-startMs).toFloat()/totalMs).coerceIn(0f,1f)
                else -> 0f
            }
            val ex=w*ep; val ca=if(ev.smoothedScore>.8f) 1f else .7f
            drawLine(Crimson.copy(ca), Offset(ex,ty-14.dp.toPx()), Offset(ex,ty-4.dp.toPx()), 2.dp.toPx())
            drawCircle(Crimson.copy(ca), 3.dp.toPx(), Offset(ex,ty-16.dp.toPx()))
        }

        val px=w*progress
        drawCircle(Phosphor.copy(dot), 5.dp.toPx(), Offset(px, ty+th/2f))
        drawLine(Phosphor.copy(dot*.4f), Offset(px,ty-18.dp.toPx()), Offset(px,ty+th+6.dp.toPx()), 1.dp.toPx())
    }
}

/* ═══════════════════════════════════════════════════════════════
   NEURAL THREAT RING — adds prediction ghost arc
   ═══════════════════════════════════════════════════════════════ */
@Composable
fun NeuralThreatRing(
    score: Float, rawScore: Float, predicted5: Float,
    melBands: FloatArray, scanning: Boolean, flagged: Boolean,
    modifier: Modifier = Modifier,
) {
    val inf    = rememberInfiniteTransition(label="r")
    val sweep  by inf.animateFloat(0f,360f,  infiniteRepeatable(tween(2800,easing=LinearEasing)), label="sw")
    val pulse  by inf.animateFloat(.55f,1f,  infiniteRepeatable(tween(700,easing=FastOutSlowInEasing),RepeatMode.Reverse), label="pu")
    val breath by inf.animateFloat(.85f,1f,  infiniteRepeatable(tween(2200,easing=FastOutSlowInEasing),RepeatMode.Reverse), label="br")

    val animScore by animateFloatAsState(score,    tween(550), label="sc")
    val animPred  by animateFloatAsState(predicted5,tween(900), label="pr")
    val animRaw   by animateFloatAsState(rawScore, tween(350), label="rw")

    val col   = when { score>=.60f->Crimson; score>=.35f->Amber; else->Phosphor }
    val faint = when { score>=.60f->CrimsonDim; score>=.35f->AmberDim; else->PhosphorDim }

    Canvas(modifier) {
        val cx=size.width/2f; val cy=size.height/2f
        val R=minOf(cx,cy)-6.dp.toPx()

        drawHexGrid(cx,cy,R*.78f,AbyssStroke)
        listOf(.28f,.50f,.70f,.88f).forEach {
            drawCircle(AbyssStroke, R*it, Offset(cx,cy), style=Stroke(.5.dp.toPx())) }
        for (a in listOf(0f,45f,90f,135f)) {
            val rad=a*(PI/180f).toFloat()
            drawLine(AbyssStroke, Offset(cx-cos(rad)*R*.92f,cy-sin(rad)*R*.92f),
                Offset(cx+cos(rad)*R*.92f,cy+sin(rad)*R*.92f), .5.dp.toPx())
        }

        // spectral rods
        if (melBands.isNotEmpty() && scanning) {
            val inn=R*.32f; val out=R*.56f
            repeat(32) { i ->
                val idx=(i.toFloat()/32*melBands.size).toInt().coerceIn(0,melBands.lastIndex)
                val e=((melBands[idx]+80f)/80f).coerceIn(0f,1f)
                val ang=(i*360f/32-90f)*(PI/180f).toFloat()
                drawLine(col.copy((.35f+e*.65f)*(if(flagged) pulse else breath)),
                    Offset(cx+cos(ang)*inn, cy+sin(ang)*inn),
                    Offset(cx+cos(ang)*(inn+(out-inn)*e*breath), cy+sin(ang)*(inn+(out-inn)*e*breath)),
                    2.2.dp.toPx(), cap=StrokeCap.Round)
            }
        }

        // raw inner arc
        if (animRaw>.01f) drawArc(col.copy(.30f),-90f,360f*animRaw,false,
            Offset(cx-R*.62f,cy-R*.62f), Size(R*1.24f,R*1.24f),
            style=Stroke(2.dp.toPx(),cap=StrokeCap.Round))

        // sweep trail
        if (scanning) repeat(9) { t ->
            val ang=(sweep-t*14f-90f)*(PI/180f).toFloat()
            val alpha=if(t==0) pulse*.85f else .06f*(9-t)
            val endR=R*(.74f-t*.015f)
            drawLine(Brush.linearGradient(listOf(col.copy(0f),col.copy(alpha)),
                Offset(cx,cy), Offset(cx+cos(ang)*endR,cy+sin(ang)*endR)),
                Offset(cx,cy), Offset(cx+cos(ang)*endR,cy+sin(ang)*endR),
                (if(t==0) 2f else 1f).dp.toPx())
        }

        // outer threat arc (EMA score)
        val arcR=R*.90f; val thick=7.dp.toPx()
        val aOff=Offset(cx-arcR,cy-arcR); val aSz=Size(arcR*2,arcR*2)
        drawArc(faint,-90f,360f,false,aOff,aSz,style=Stroke(thick,cap=StrokeCap.Butt))
        if (animScore>.01f) drawArc(col.copy(if(flagged&&scanning) pulse else .9f),-90f,
            360f*animScore,false,aOff,aSz,style=Stroke(thick,cap=StrokeCap.Round))

        // PREDICTION GHOST ARC — dashed outline showing where score will be in 5 windows
        if (animPred > animScore+.03f && scanning) {
            val predR=R*.83f
            drawArc(col.copy(.25f),-90f,360f*animPred,false,
                Offset(cx-predR,cy-predR), Size(predR*2,predR*2),
                style=Stroke(2.dp.toPx(), join=StrokeJoin.Round,
                    pathEffect=PathEffect.dashPathEffect(floatArrayOf(6f,5f),0f)))
        }

        // tick crown (60 divisions)
        repeat(60) { t ->
            val ang=(t*6f-90f)*(PI/180f).toFloat(); val maj=t%5==0
            val tLen=if(maj) 8.dp.toPx() else 4.dp.toPx()
            drawLine(AbyssStroke2,
                Offset(cx+cos(ang)*(R*.96f-tLen),cy+sin(ang)*(R*.96f-tLen)),
                Offset(cx+cos(ang)*R*.96f,cy+sin(ang)*R*.96f),
                if(maj) 1.2.dp.toPx() else .5.dp.toPx())
        }

        // center glow on flag
        if (flagged&&scanning) {
            drawCircle(col.copy(pulse*.12f),R*.22f,Offset(cx,cy))
            drawCircle(col.copy(pulse*.25f),R*.10f,Offset(cx,cy))
        }
    }
}

private fun DrawScope.drawHexGrid(cx:Float,cy:Float,R:Float,color:Color) {
    val hs=R*.18f; val hW=hs*2f; val hH=sqrt(3f)*hs
    for (row in -9..9) for (col in -9..9) {
        val ox=cx+col*hW*.75f; val oy=cy+row*hH+(if(col%2!=0) hH/2f else 0f)
        val dist=sqrt((ox-cx).pow(2)+(oy-cy).pow(2))
        if(dist>R*.85f) continue
        val a=(1f-dist/R).coerceIn(0f,1f)*.5f
        for (i in 0..5) {
            val a1=(i*60f-30f)*(PI/180f).toFloat(); val a2=((i+1)*60f-30f)*(PI/180f).toFloat()
            drawLine(color.copy(a*.6f),
                Offset(ox+cos(a1)*hs,oy+sin(a1)*hs), Offset(ox+cos(a2)*hs,oy+sin(a2)*hs), .4.dp.toPx())
        }
    }
}

/* ═══════════════════════════════════════════════════════════════
   BIOMARKER PANEL — 8 live acoustic features as horizontal bars
   ═══════════════════════════════════════════════════════════════ */
@Composable
fun BiomarkerPanel(bio: AudioBiomarkers, modifier: Modifier = Modifier) {
    Column(modifier
        .background(AbyssPanel, RoundedCornerShape(4.dp))
        .border(.5.dp, AbyssStroke, RoundedCornerShape(4.dp))
        .padding(horizontal=10.dp, vertical=8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        TinyLabel2("ACOUSTIC BIOMARKERS", InkMid)
        Spacer(Modifier.height(2.dp))
        BioRow("CENTROID",    (bio.spectralCentroid / 8000f).coerceIn(0f,1f), "${bio.spectralCentroid.toInt()}Hz", "TTS compresses to 1–2kHz")
        BioRow("SPREAD",      (bio.spectralSpread   / 4000f).coerceIn(0f,1f), "${bio.spectralSpread.toInt()}Hz",   "Narrow=synthetic")
        BioRow("FLUX",        bio.spectralFlux,                                 "${"%.3f".format(bio.spectralFlux)}", "Low=unnaturally smooth")
        BioRow("TONAL",       bio.toneratio,                                    "${"%.2f".format(bio.toneratio)}",   "High=over-periodic")
        BioRow("ENERGY",      ((bio.energyDb + 96f) / 96f).coerceIn(0f,1f),   "${bio.energyDb.toInt()}dBFS",       "")
        BioRow("ZCR",         (bio.zeroCrossRate / 8000f).coerceIn(0f,1f),     "${bio.zeroCrossRate.toInt()}Hz",    "")
        BioRow("F0",          (bio.f0Estimate / 400f).coerceIn(0f,1f),         "${bio.f0Estimate.toInt()}Hz",       "Flat pitch=TTS")
        BioRow("ENTROPY",     bio.spectralEntropy,                               "${"%.2f".format(bio.spectralEntropy)}", "Low=energy concentrated")
    }
}

@Composable
private fun BioRow(label: String, value: Float, display: String, hint: String) {
    val anim by animateFloatAsState(value.coerceIn(0f,1f), tween(400), label=label)
    val col = when { value >= .75f -> Crimson; value >= .45f -> Amber; else -> Phosphor }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontFamily=FontFamily.Monospace, fontSize=7.sp, color=InkMid,
            letterSpacing=1.sp, modifier=Modifier.width(56.dp))
        Box(Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(AbyssStroke)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(anim).clip(RoundedCornerShape(3.dp)).background(col))
        }
        Spacer(Modifier.width(6.dp))
        Text(display, fontFamily=FontFamily.Monospace, fontSize=7.sp, color=InkBright,
            modifier=Modifier.width(60.dp), textAlign=TextAlign.End)
    }
}

/* ═══════════════════════════════════════════════════════════════
   ESCALATION BANNER — predictive threat warning strip
   ═══════════════════════════════════════════════════════════════ */
@Composable
fun EscalationBanner(result: EscalationResult, modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label="esc")
    val pulse by inf.animateFloat(.6f,1f, infiniteRepeatable(tween(600),RepeatMode.Reverse), label="ep")

    val (bg, border, textCol, icon, text) = when (val s = result.state) {
        is EscalationState.Imminent -> Quintuple(CrimsonGhost, Crimson.copy(.5f), Crimson, "⚠",
            "THREAT IMMINENT · ~${s.windowsAway} WINDOWS · PRED ${(s.predictedScore*100).toInt()}%")
        is EscalationState.Warning  -> Quintuple(AmberDim.copy(.4f), Amber.copy(.4f), Amber, "△",
            "RISING THREAT · ~${s.windowsAway} WINDOWS · PRED ${(s.predictedScore*100).toInt()}%")
        EscalationState.Declining   -> Quintuple(PhosphorGhost.copy(.3f), Phosphor.copy(.25f), Phosphor, "↓",
            "THREAT DECLINING · SLOPE ${(result.slope*100).toInt()}%/WIN")
        else -> return
    }

    Row(modifier.fillMaxWidth()
        .background(bg, RoundedCornerShape(3.dp))
        .border(.5.dp, border, RoundedCornerShape(3.dp))
        .padding(horizontal=10.dp, vertical=6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(icon, fontSize=12.sp, color=textCol.copy(
                if (result.state is EscalationState.Imminent) pulse else 1f))
            Text(text, fontFamily=FontFamily.Monospace, fontSize=8.sp, color=textCol, letterSpacing=.5.sp)
        }
        Column(horizontalAlignment=Alignment.End) {
            Text("PROTECTION", fontFamily=FontFamily.Monospace, fontSize=6.sp, color=InkMid, letterSpacing=1.sp)
            Text("${result.protectionPct}%", fontFamily=FontFamily.Monospace, fontSize=11.sp,
                fontWeight=FontWeight.Black,
                color=when { result.protectionPct>70->Phosphor; result.protectionPct>40->Amber; else->Crimson })
        }
    }
}

private data class Quintuple<A,B,C,D,E>(val a:A,val b:B,val c:C,val d:D,val e:E)
private operator fun <A,B,C,D,E> Quintuple<A,B,C,D,E>.component1()=a
private operator fun <A,B,C,D,E> Quintuple<A,B,C,D,E>.component2()=b
private operator fun <A,B,C,D,E> Quintuple<A,B,C,D,E>.component3()=c
private operator fun <A,B,C,D,E> Quintuple<A,B,C,D,E>.component4()=d
private operator fun <A,B,C,D,E> Quintuple<A,B,C,D,E>.component5()=e

/* ═══════════════════════════════════════════════════════════════
   PROTECTION SCORE GAUGE — arc showing session health 0-100
   ═══════════════════════════════════════════════════════════════ */
@Composable
fun ProtectionGauge(score: Int, modifier: Modifier = Modifier) {
    val anim by animateFloatAsState(score/100f, tween(700), label="pg")
    val col  = when { score>70->Phosphor; score>40->Amber; else->Crimson }
    Canvas(modifier.size(60.dp)) {
        val cx=size.width/2f; val cy=size.height/2f; val R=minOf(cx,cy)-4.dp.toPx()
        drawArc(AbyssStroke,-210f,240f,false,Offset(cx-R,cy-R),Size(R*2,R*2),style=Stroke(4.dp.toPx(),cap=StrokeCap.Round))
        drawArc(col,-210f,240f*anim,false,Offset(cx-R,cy-R),Size(R*2,R*2),style=Stroke(4.dp.toPx(),cap=StrokeCap.Round))
        drawContext.canvas.nativeCanvas.drawText("$score",cx,cy+5.dp.toPx(),
            android.graphics.Paint().apply {
                color=col.toArgb(); textSize=11.dp.toPx(); textAlign=android.graphics.Paint.Align.CENTER
                typeface=android.graphics.Typeface.MONOSPACE; isAntiAlias=true
            })
    }
}

/* ═══════════════════════════════════════════════════════════════
   RETINAL OSCILLOSCOPE
   ═══════════════════════════════════════════════════════════════ */
@Composable
fun RetinalOscilloscope(
    samples: FloatArray, score: Float,
    events: List<AnomalyEvent>, modifier: Modifier = Modifier,
) {
    val inf  = rememberInfiniteTransition(label="osc")
    val glow by inf.animateFloat(.5f,1f, infiniteRepeatable(tween(750,easing=FastOutSlowInEasing),RepeatMode.Reverse), label="g")
    val col  = when { score>=.60f->Crimson; score>=.35f->Amber; else->Phosphor }
    val spike= events.lastOrNull()?.let { System.currentTimeMillis()-it.timestampMs<4000L }==true

    Canvas(modifier) {
        val w=size.width; val h=size.height; val mid=h/2f
        drawLine(AbyssStroke, Offset(0f,mid), Offset(w,mid), .5.dp.toPx())
        listOf(.25f,.75f).forEach { drawLine(AbyssStroke.copy(.35f),Offset(0f,h*it),Offset(w,h*it),.3.dp.toPx()) }
        drawLine(AbyssStroke.copy(.35f),Offset(w/2f,0f),Offset(w/2f,h),.3.dp.toPx())
        if (samples.isEmpty()) return@Canvas
        val path=Path()
        samples.forEachIndexed { i,v ->
            val x=i.toFloat()/(samples.size-1)*w; val y=mid-v.coerceIn(-1f,1f)*(mid-3.dp.toPx())
            if(i==0) path.moveTo(x,y) else path.lineTo(x,y)
        }
        drawPath(path,col.copy(glow*.20f),style=Stroke(6.dp.toPx(),cap=StrokeCap.Round))
        drawPath(path,col.copy(glow*.45f),style=Stroke(2.5.dp.toPx(),cap=StrokeCap.Round))
        drawPath(path,col.copy(.90f),     style=Stroke(1.dp.toPx(),  cap=StrokeCap.Round))
        if (spike) {
            val sx=w*.76f
            drawLine(Crimson.copy(glow),Offset(sx,2.dp.toPx()),Offset(sx,h-2.dp.toPx()),1.5.dp.toPx())
            drawLine(Crimson.copy(glow*.18f),Offset(sx,0f),Offset(sx,h),10.dp.toPx())
            drawCircle(Crimson.copy(glow),4.dp.toPx(),Offset(sx,mid-(mid*.7f)))
            drawCircle(Crimson.copy(glow),4.dp.toPx(),Offset(sx,mid+(mid*.7f)))
            drawCircle(Crimson.copy(glow*.25f),10.dp.toPx(),Offset(sx,mid-(mid*.7f)))
            drawCircle(Crimson.copy(glow*.25f),10.dp.toPx(),Offset(sx,mid+(mid*.7f)))
        }
    }
}

/* ═══════════════════════════════════════════════════════════════
   SPECTRAL WATERFALL
   ═══════════════════════════════════════════════════════════════ */
@Composable
fun SpectralWaterfall(history: List<FloatArray>, modifier: Modifier=Modifier) {
    Canvas(modifier) {
        if (history.isEmpty()) return@Canvas
        val cw=size.width/history.size.toFloat(); val bh=size.height/80f
        history.forEachIndexed { ci,frame ->
            frame.take(80).forEachIndexed { bi,v ->
                drawRect(spectralColor(((v+80f)/80f).coerceIn(0f,1f)),
                    Offset(ci*cw,size.height-(bi+1)*bh), Size(cw+.5f,bh+.5f))
            }
        }
    }
}
private fun spectralColor(v:Float):Color = when {
    v<.33f -> Color(0f, v*3f*.45f, .4f+v*3f*.55f, .9f)
    v<.66f -> Color((v-.33f)*3f*.9f, .35f+(v-.33f)*3f*.3f, .18f, .92f)
    else   -> Color(1f, (.1f-(v-.66f)*.3f).coerceAtLeast(0f), 0f, .96f)
}

/* ═══════════════════════════════════════════════════════════════
   SEISMOGRAPH
   ═══════════════════════════════════════════════════════════════ */
@Composable
fun SeismographView(history: List<Float>, threshold: Float=.60f) {
    AndroidView(
        factory = { ctx ->
            LineChart(ctx).apply {
                description.isEnabled=false; legend.isEnabled=false; axisRight.isEnabled=false
                setTouchEnabled(false); setBackgroundColor(android.graphics.Color.TRANSPARENT); setDrawGridBackground(false)
                axisLeft.apply { setDrawLabels(false); setDrawGridLines(true)
                    gridColor=android.graphics.Color.argb(18,255,255,255); gridLineWidth=.4f
                    axisMinimum=0f; axisMaximum=1f; setDrawAxisLine(false) }
                xAxis.apply { setDrawLabels(false); setDrawGridLines(false); setDrawAxisLine(false); position=XAxis.XAxisPosition.BOTTOM }
            }
        },
        update = { chart ->
            chart.axisLeft.removeAllLimitLines()
            chart.axisLeft.addLimitLine(LimitLine(threshold).apply {
                lineColor=android.graphics.Color.argb(120,255,170,0); lineWidth=.8f; enableDashedLine(5f,4f,0f) })
            val entries=ArrayList<Entry>()
            if (history.isNotEmpty()) history.forEachIndexed { i,v -> entries.add(Entry(i.toFloat(),v)) }
            else repeat(80) { i -> entries.add(Entry(i.toFloat(),(kotlin.math.sin(i*.18)*0.018+.02).toFloat())) }
            val last=history.lastOrNull()?:0f
            val hex=when{last>=.60f->"#FF0033";last>=.35f->"#FFAA00";else->"#00FF41"}
            val c=android.graphics.Color.parseColor(hex)
            val ds=LineDataSet(entries,"e").apply {
                color=c; setDrawCircles(false); setDrawValues(false); lineWidth=1.8f
                setDrawFilled(true); fillColor=c; fillAlpha=28; mode=LineDataSet.Mode.CUBIC_BEZIER }
            chart.data=LineData(ds)
            chart.setVisibleXRangeMaximum(80f); chart.moveViewToX(entries.size.toFloat()); chart.invalidate()
        },
        modifier=Modifier.fillMaxWidth().height(72.dp)
    )
}

/* ═══════════════════════════════════════════════════════════════
   PRIMITIVES
   ═══════════════════════════════════════════════════════════════ */
@Composable
fun DataCell(label:String,value:String,valueColor:Color,modifier:Modifier=Modifier) {
    Column(modifier.background(AbyssPanel,RoundedCornerShape(4.dp))
        .border(.5.dp,AbyssStroke,RoundedCornerShape(4.dp))
        .padding(horizontal=10.dp,vertical=8.dp)) {
        Text(label,style=MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(3.dp))
        Text(value,fontFamily=FontFamily.Monospace,fontSize=18.sp,fontWeight=FontWeight.Black,color=valueColor)
    }
}
@Composable
fun GlassCard(modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit) {
    Column(modifier.background(AbyssPanel,RoundedCornerShape(4.dp))
        .border(.5.dp,AbyssStroke,RoundedCornerShape(4.dp)).padding(10.dp),content=content)
}
@Composable
fun ProbabilityBar(score:Float) {
    val w by animateFloatAsState(score.coerceIn(0f,1f),tween(500),label="pb")
    val c=when{score>=.60f->Crimson;score>=.35f->Amber;else->Phosphor}
    Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(AbyssStroke)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(w).clip(RoundedCornerShape(2.dp)).background(c))
    }
}
@Composable fun WaveformView(waveformSamples:FloatArray,events:List<AnomalyEvent>,isScanning:Boolean) {
    RetinalOscilloscope(if(isScanning) waveformSamples else FloatArray(0),0f,events,
        Modifier.fillMaxWidth().height(56.dp))
}

@Composable
fun TinyLabel2(text: String, color: Color) {
    Text(
        text = text,
        fontFamily = FontFamily.Monospace,
        fontSize = 7.sp,
        color = color,
        letterSpacing = 1.2.sp
    )
}