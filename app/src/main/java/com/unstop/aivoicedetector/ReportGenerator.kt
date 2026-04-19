package com.unstop.aivoicedetector

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.FileProvider
import com.itextpdf.text.*
import com.itextpdf.text.Font
import com.itextpdf.text.pdf.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

class ReportGenerator(private val context: Context) {

    fun generateAndShareSessionReport(
        sessionLog: SessionLog?,
        signature:  String       = "",
        publicKey:  String       = "",
        mode:       String       = "LIVE",
        fileName:   String       = "",
        startMs:    Long         = 0L,
        endMs:      Long         = 0L,
        emaHistory: List<Float>  = emptyList(),
        f0History:  List<Float>  = emptyList(),
    ) {
        if (sessionLog == null) return
        val dir = File(context.cacheDir, "reports").also { it.mkdirs() }
        val pdf = File(dir, "VoiceShield_${System.currentTimeMillis()}.pdf")

        try {
            val doc = Document(PageSize.A4, 40f, 40f, 60f, 40f)
            PdfWriter.getInstance(doc, FileOutputStream(pdf))
            doc.open()

            val fTitle  = FontFactory.getFont(FontFactory.HELVETICA_BOLD,   22f, BaseColor.BLACK)
            val fHead   = FontFactory.getFont(FontFactory.HELVETICA_BOLD,   13f, BaseColor.BLACK)
            val fHead2  = FontFactory.getFont(FontFactory.HELVETICA_BOLD,   11f, BaseColor.BLACK)
            val fBody   = FontFactory.getFont(FontFactory.HELVETICA,        10f, BaseColor.DARK_GRAY)
            val fMono   = FontFactory.getFont(FontFactory.COURIER,           9f, BaseColor.DARK_GRAY)
            val fMonoSm = FontFactory.getFont(FontFactory.COURIER,           7f, BaseColor.GRAY)
            val fRed    = FontFactory.getFont(FontFactory.HELVETICA_BOLD,   10f, BaseColor.RED)
            val fGreen  = FontFactory.getFont(FontFactory.HELVETICA_BOLD,   10f, BaseColor(0,140,80))
            val fAmber  = FontFactory.getFont(FontFactory.HELVETICA_BOLD,   10f, BaseColor(180,100,0))
            val fGray   = FontFactory.getFont(FontFactory.HELVETICA,         8f, BaseColor.GRAY)
            val fWarn   = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 8f, BaseColor(100,100,100))
            val df      = SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.getDefault())
            val dfT     = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

            // ── 1. CLASSIFIED HEADER ─────────────────────────────────────
            val hdr = PdfPTable(1).apply{setWidthPercentage(100f);spacingAfter=4f}
            hdr.addCell(PdfPCell(Phrase("▌ TOP SECRET — VOICESHIELD NEURAL FORENSIC REPORT  REV-5.0", fHead)).apply{
                backgroundColor=BaseColor(8,10,14);paddingTop=10f;paddingBottom=10f;paddingLeft=14f;border=0})
            doc.add(hdr)

            doc.add(Paragraph("VoiceShield — AI Voice Deepfake Detection Report", fTitle).apply{spacingAfter=2f})
            doc.add(Paragraph("Generated: ${df.format(Date())}  ·  On-device analysis  ·  No audio transmitted", fMono).apply{spacingAfter=14f})

            // ── 2. SESSION IDENTITY ───────────────────────────────────────
            doc.add(sectionHead("SESSION IDENTITY", fHead))
            val idT = twoCol(100f)
            fun row(l:String,v:String,vf:Font=fMono){
                idT.addCell(cell(l,fBody)); idT.addCell(cell(v,vf))}
            row("Input Mode", mode)
            if(fileName.isNotEmpty()) row("Source File", fileName)
            if(startMs>0) row("Session Start", df.format(Date(startMs)))
            val realEnd = sessionLog.endTime ?: System.currentTimeMillis()
            row("Session End", df.format(Date(realEnd)))
            val dur = (realEnd - sessionLog.startTime).coerceAtLeast(0L)
            row("Duration", "${dur/1000L}s  (${dur/60000L}m ${(dur/1000L)%60}s)")
            row("Analysis Engine", engineLabel(mode))
            row("Detection Sensitivity", "MEDIUM — 60% threshold")
            doc.add(idT)

            // ── 3. STATISTICS ─────────────────────────────────────────────
            doc.add(sectionHead("SESSION STATISTICS", fHead))
            val pct = if(sessionLog.totalWindowsAnalyzed>0)
                (sessionLog.flaggedWindows.toFloat()/sessionLog.totalWindowsAnalyzed*100f) else 0f
            val stT = twoCol(100f)
            fun srow(l:String,v:String,vf:Font=fMono){stT.addCell(cell(l,fBody));stT.addCell(cell(v,vf))}
            srow("Total Windows Analysed",  "${sessionLog.totalWindowsAnalyzed}")
            srow("Windows Flagged",          "${sessionLog.flaggedWindows}")
            srow("% Time Synthetic",         "%.1f%%".format(pct), if(pct>30f) fRed else fGreen)
            srow("Peak AI Confidence",       "${(sessionLog.maxConfidence*100).roundToInt()}%",
                if(sessionLog.maxConfidence>.70f) fRed else fBody)
            srow("Anomaly Events",           "${sessionLog.events.size}",
                if(sessionLog.events.isNotEmpty()) fRed else fGreen)
            val threatDensity = if(dur>0) sessionLog.events.size/(dur/60000f) else 0f
            srow("Threat Density",           "${"%.2f".format(threatDensity)} events/min",
                if(threatDensity>2f) fRed else fBody)
            doc.add(stT)

            // ── 4. EMA CONFIDENCE CHART ───────────────────────────────────
            if(emaHistory.isNotEmpty()) {
                doc.add(sectionHead("CONFIDENCE TIMELINE", fHead))
                doc.add(bitmapImage(renderConfChart(emaHistory, .60f), 515f, 120f).apply{spacingAfter=4f})
                doc.add(Paragraph("Dashed orange = 60% threshold. Red zone = flagged. " +
                    "Each datapoint = 2s audio window.", fGray).apply{spacingAfter=14f})
            }

            // ── 5. F0 PITCH CHART ─────────────────────────────────────────
            if(f0History.isNotEmpty()) {
                doc.add(sectionHead("PITCH (F0) TIMELINE", fHead))
                doc.add(bitmapImage(renderF0Chart(f0History), 515f, 100f).apply{spacingAfter=4f})
                doc.add(Paragraph("Fundamental frequency over session. Flat F0 = TTS/vocoder signature. " +
                    "Natural speech shows 50–200Hz variation.", fGray).apply{spacingAfter=14f})
            }

            // ── 6. ANOMALY TIMELINE ───────────────────────────────────────
            if(sessionLog.events.isNotEmpty()) {
                doc.add(sectionHead("ANOMALY TIMELINE", fHead))

                // ASCII bar
                val barW = 88
                val bar  = buildString {
                    append("[")
                    val filled = CharArray(barW){'-'}
                    sessionLog.events.forEach { ev ->
                        val prog = ((ev.timestampMs-sessionLog.startTime).toFloat()/dur.coerceAtLeast(1)*barW)
                            .roundToInt().coerceIn(0,barW-1)
                        filled[prog] = if(ev.smoothedScore>.80f) '█' else '▒'
                    }
                    append(String(filled)); append("]")
                }
                doc.add(Paragraph(bar, fMonoSm).apply{spacingAfter=3f})
                doc.add(Paragraph("█=CRITICAL  ▒=HIGH  -=CLEAR  (proportional to session duration)",
                    fGray).apply{spacingAfter=10f})

                val evT = PdfPTable(5).apply{
                    setWidthPercentage(100f); setWidths(floatArrayOf(1.5f,1f,1.2f,1.2f,2.2f)); spacingAfter=14f}
                listOf("Timestamp","Elapsed","EMA","Raw","Severity").forEach { h ->
                    evT.addCell(PdfPCell(Phrase(h,fHead2)).apply{
                        backgroundColor=BaseColor(215,220,230); paddingBottom=5f})
                }
                sessionLog.events.forEach { ev ->
                    val el  = ev.timestampMs - sessionLog.startTime
                    val sf  = when{ev.smoothedScore>.80f->fRed;ev.smoothedScore>.60f->fAmber;else->fBody}
                    val tier= when{ev.smoothedScore>.80f->"CRITICAL — Strong AI fingerprint"
                        ev.smoothedScore>.60f->"HIGH — Likely synthetic"
                        else->"MEDIUM — Borderline detection"}
                    evT.addCell(cell(ev.timeLabel,fMono)); evT.addCell(cell("+${el/1000}s",fMono))
                    evT.addCell(cell("${(ev.smoothedScore*100).roundToInt()}%",sf))
                    evT.addCell(cell("${(ev.rawScore*100).roundToInt()}%",fBody))
                    evT.addCell(cell(tier,fBody))
                }
                doc.add(evT)
            }

            // ── 7. FORENSIC VERDICT ───────────────────────────────────────
            doc.add(sectionHead("FORENSIC VERDICT", fHead))
            val vText = if(sessionLog.events.isEmpty())
                "GENUINE — No synthetic voice anomalies detected across ${sessionLog.totalWindowsAnalyzed} analysis windows."
            else "COMPROMISED — ${sessionLog.events.size} synthetic voice event(s) detected. Peak confidence: ${(sessionLog.maxConfidence*100).roundToInt()}%."
            val vT = PdfPTable(1).apply{setWidthPercentage(100f);spacingAfter=14f}
            vT.addCell(PdfPCell(Phrase(vText, if(sessionLog.events.isEmpty()) fGreen else fRed)).apply{
                backgroundColor=if(sessionLog.events.isEmpty()) BaseColor(228,255,238) else BaseColor(255,232,232)
                paddingTop=12f;paddingBottom=12f;paddingLeft=14f;border=0})
            doc.add(vT)

            // heuristic caveat
            doc.add(Paragraph("DISCLAIMER: Vendor attribution is derived from spectral heuristics, " +
                "not a trained classifier. Confidence reflects relative heuristic signal strength. " +
                "This report is for forensic reference only.", fWarn).apply{spacingAfter=14f})

            // ── 8. CRYPTOGRAPHIC INTEGRITY ────────────────────────────────
            if(signature.isNotEmpty()) {
                doc.add(sectionHead("CRYPTOGRAPHIC INTEGRITY", fHead))
                doc.add(Paragraph("Signed with RSA-2048 in the device hardware-backed Android Keystore (TEE). " +
                    "The signature covers all session metadata in this report.", fBody).apply{spacingAfter=8f})
                val sigT = PdfPTable(2).apply{setWidthPercentage(100f);spacingAfter=8f}
                sigT.addCell(cell("Algorithm",fBody));    sigT.addCell(cell("SHA256withRSA",fMono))
                sigT.addCell(cell("Signature",fBody));    sigT.addCell(cell(signature.take(80)+"…",fMonoSm))
                if(publicKey.isNotEmpty()) {
                    sigT.addCell(cell("Public Key",fBody)); sigT.addCell(cell(publicKey.take(80)+"…",fMonoSm))
                }
                doc.add(sigT)
            }

            doc.add(Paragraph("VoiceShield  ·  On-device Neural Audio Forensics  ·  No audio transmitted  ·  Rev 5.0",
                FontFactory.getFont(FontFactory.HELVETICA, 8f, BaseColor.GRAY)))
            doc.close()
            share(pdf)
        } catch(e:Exception){e.printStackTrace()}
    }

    // ── CHART RENDERERS ───────────────────────────────────────────────────

    private fun renderConfChart(history: List<Float>, threshold: Float): Bitmap {
        val W=800;val H=180;val pad=20
        val bmp=Bitmap.createBitmap(W,H,Bitmap.Config.ARGB_8888)
        val cv =Canvas(bmp)
        cv.drawColor(android.graphics.Color.argb(255,9,11,16))
        val grid=Paint().apply{color=android.graphics.Color.argb(25,255,255,255);strokeWidth=1f}
        for(i in 1..4) cv.drawLine(pad.f,(pad+i*(H-2*pad)/5).f,(W-pad).f,(pad+i*(H-2*pad)/5).f,grid)
        val thrY=pad+(1f-threshold)*(H-2*pad)
        cv.drawLine(pad.f,thrY,(W-pad).f,thrY,Paint().apply{
            color=android.graphics.Color.argb(180,255,170,0);strokeWidth=1.5f
            pathEffect=android.graphics.DashPathEffect(floatArrayOf(8f,5f),0f)})
        if(history.isEmpty()) return bmp
        val xs=(W-2f*pad)/(history.size-1).coerceAtLeast(1)
        // red zone
        val rp=android.graphics.Path(); var inR=false
        history.forEachIndexed{i,v->val x=pad+i*xs;val y=pad+(1f-v.coerceIn(0f,1f))*(H-2*pad)
            if(v>=threshold){if(!inR){rp.moveTo(x,thrY);inR=true};rp.lineTo(x,y)}
            else if(inR){rp.lineTo(x,thrY);rp.close();inR=false}}
        if(inR){rp.lineTo(pad+(history.size-1)*xs,thrY);rp.close()}
        cv.drawPath(rp,Paint().apply{color=android.graphics.Color.argb(45,255,0,40)})
        // fill
        val fp=android.graphics.Path()
        fp.moveTo(pad.f,(H-pad).f)
        history.forEachIndexed{i,v->fp.lineTo(pad+i*xs,pad+(1f-v.coerceIn(0f,1f))*(H-2*pad))}
        fp.lineTo(pad+(history.size-1)*xs,(H-pad).f);fp.close()
        cv.drawPath(fp,Paint().apply{color=android.graphics.Color.argb(30,0,255,65)})
        // line
        val lp=android.graphics.Path(); val lPaint=Paint().apply{isAntiAlias=true;strokeWidth=2.5f;style=Paint.Style.STROKE}
        history.forEachIndexed{i,v->val x=pad+i*xs;val y=pad+(1f-v.coerceIn(0f,1f))*(H-2*pad)
            lPaint.color=when{v>=.60f->android.graphics.Color.rgb(255,0,40);v>=.35f->android.graphics.Color.rgb(255,170,0);else->android.graphics.Color.rgb(0,255,65)}
            if(i==0) lp.moveTo(x,y) else lp.lineTo(x,y)}
        cv.drawPath(lp,lPaint)
        val tp=Paint().apply{color=android.graphics.Color.argb(120,180,200,220);textSize=18f;isAntiAlias=true;typeface=Typeface.MONOSPACE}
        cv.drawText("100%",0f,(pad+8).f,tp); cv.drawText("0%",0f,(H-pad+8).f,tp)
        return bmp
    }

    private fun renderF0Chart(f0: List<Float>): Bitmap {
        val W=800;val H=140;val pad=20
        val bmp=Bitmap.createBitmap(W,H,Bitmap.Config.ARGB_8888)
        val cv =Canvas(bmp)
        cv.drawColor(android.graphics.Color.argb(255,9,11,16))
        val grid=Paint().apply{color=android.graphics.Color.argb(20,255,255,255);strokeWidth=1f}
        listOf(100f,200f,300f).forEach{hz->
            val y=pad+(1f-((hz-80f)/320f))*(H-2*pad)
            cv.drawLine(pad.f,y,(W-pad).f,y,grid)
        }
        if(f0.isEmpty()) return bmp
        val xs=(W-2f*pad)/(f0.size-1).coerceAtLeast(1)
        val p=android.graphics.Path()
        f0.forEachIndexed{i,v->
            val x=pad+i*xs
            val norm=((v-80f)/320f).coerceIn(0f,1f)
            val y=pad+(1f-norm)*(H-2*pad)
            if(i==0) p.moveTo(x,y) else p.lineTo(x,y)
        }
        // compute variance — low = TTS
        val mean=f0.average().toFloat()
        val variance=f0.map{(it-mean).pow(2)}.average().toFloat()
        val isTts = variance<500f
        val lineColor=if(isTts) android.graphics.Color.rgb(255,100,0) else android.graphics.Color.rgb(0,200,255)
        cv.drawPath(p,Paint().apply{color=lineColor;strokeWidth=2f;style=Paint.Style.STROKE;isAntiAlias=true})
        // annotation
        cv.drawText(if(isTts)"LOW VARIANCE — TTS SIGNATURE" else "NATURAL VARIATION", (pad+4).f, (pad+16).f,
            Paint().apply{color=lineColor;textSize=16f;isAntiAlias=true;typeface=Typeface.MONOSPACE})
        val tp=Paint().apply{color=android.graphics.Color.argb(100,180,200,220);textSize=14f;isAntiAlias=true;typeface=Typeface.MONOSPACE}
        listOf(100f to "100Hz",200f to "200Hz",300f to "300Hz").forEach{(hz,label)->
            cv.drawText(label,0f,pad+(1f-(hz-80f)/320f)*(H-2*pad)+4f,tp)
        }
        return bmp
    }

    // ── HELPERS ───────────────────────────────────────────────────────────

    private fun sectionHead(text:String,font:Font) = Paragraph(text,font).apply{spacingAfter=6f;spacingBefore=4f}
    private fun twoCol(pct:Float=100f) = PdfPTable(2).apply{setWidthPercentage(pct);spacingAfter=14f}
    private fun cell(v:String,f:Font) = PdfPCell(Phrase(v,f)).apply{border=0;paddingBottom=5f}
    private fun bitmapImage(bmp:Bitmap,w:Float,h:Float): Image {
        val s=ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.PNG,95,s)
        return Image.getInstance(s.toByteArray()).apply{scaleToFit(w,h)}
    }
    private fun engineLabel(mode:String) = when(mode){
        "DEMO"->"Scripted simulation (BiomarkerExtractor + EscalationEngine)"
        "FILE"->"MediaCodec decode → AASIST-INT8 TFLite + full biomarker pipeline"
        else  ->"AASIST-INT8 TFLite (live AudioRecord 16kHz) + biomarker pipeline"
    }
    private fun share(file:File) {
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",file)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{
            type="application/pdf";putExtra(Intent.EXTRA_STREAM,uri)
            putExtra(Intent.EXTRA_SUBJECT,"VoiceShield Forensic Report")
            putExtra(Intent.EXTRA_TEXT,"On-device AI voice forensic analysis. No audio transmitted.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        },"Share Report"))
    }

    private val Int.f get() = this.toFloat()
    private val Float.f get() = this
}

private val SessionLog.currentSensitivity: Float get() = 0.60f
private fun Float.pow(n:Float) = Math.pow(this.toDouble(),n.toDouble()).toFloat()
private fun Float.roundToInt() = Math.round(this)
private fun Double.roundToInt() = Math.round(this).toInt()