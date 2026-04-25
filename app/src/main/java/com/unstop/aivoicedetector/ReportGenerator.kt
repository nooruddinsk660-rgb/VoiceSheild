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

    companion object {
        // Returns a plain text forensic summary string.
        // FinalViewModel then calls ForensicSigner.signSession(report).
        fun generateReport(
            session:         SessionLog?,
            attribution:     Attribution?      = null,
            biomarkers:      AudioBiomarkers?  = null,
            escalation:      EscalationResult? = null,
            pitchResult:     PitchResult?      = null,
            weights:         Map<String, Float> = emptyMap(),
            snrDb:           Float             = 0f,
            inferenceEngine: String            = "MOCK",
        ): String {
            if (session == null) return "NO SESSION"
            val df  = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val dur = ((session.endTime ?: System.currentTimeMillis()) - session.startTime)
                .coerceAtLeast(0L)
            val pct = if (session.totalWindowsAnalyzed > 0)
                (session.flaggedWindows.toFloat() / session.totalWindowsAnalyzed * 100f) else 0f

            return buildString {
                appendLine("VOICESHIELD FORENSIC REPORT  REV-5.0")
                appendLine("Generated: ${df.format(Date())}")
                appendLine("Engine: $inferenceEngine")
                appendLine()
                appendLine("SESSION")
                appendLine("  Start : ${df.format(Date(session.startTime))}")
                appendLine("  End   : ${session.endTime?.let { df.format(Date(it)) } ?: "active"}")
                appendLine("  Dur   : ${dur / 1000}s")
                appendLine()
                appendLine("STATISTICS")
                appendLine("  Windows : ${session.totalWindowsAnalyzed}")
                appendLine("  Flagged : ${session.flaggedWindows}  (${"%3.1f".format(pct)}%)")
                appendLine("  Peak    : ${(session.maxConfidence * 100).toInt()}%")
                appendLine("  Events  : ${session.events.size}")
                appendLine("  SNR     : ${"%4.1f".format(snrDb)} dB")
                appendLine()
                if (attribution != null && attribution.engine != "Unknown") {
                    appendLine("ATTRIBUTION")
                    appendLine("  Engine : ${attribution.engine}")
                    appendLine("  Conf   : ${(attribution.confidence * 100).toInt()}%")
                    appendLine("  Reason : ${attribution.reason}")
                    appendLine()
                }
                if (biomarkers != null) {
                    appendLine("BIOMARKERS")
                    appendLine("  Centroid : ${biomarkers.spectralCentroid.toInt()} Hz")
                    appendLine("  Entropy  : ${"%.2f".format(biomarkers.spectralEntropy)}")
                    appendLine("  Flux     : ${"%.3f".format(biomarkers.spectralFlux)}")
                    appendLine("  F0       : ${biomarkers.f0Estimate.toInt()} Hz")
                    appendLine()
                }
                if (escalation != null) {
                    appendLine("ESCALATION: ${escalation.state::class.simpleName}")
                    appendLine("  Slope   : ${"%.3f".format(escalation.slope)}")
                    appendLine("  Pred5   : ${(escalation.predicted5 * 100).toInt()}%")
                    appendLine("  Protect : ${escalation.protectionPct}%")
                    appendLine()
                }
                if (pitchResult != null) {
                    appendLine("PITCH (YIN)")
                    appendLine("  F0   : ${pitchResult.frequency.toInt()} Hz")
                    appendLine("  Conf : ${(pitchResult.confidence * 100).toInt()}%")
                    appendLine("  Flat : ${pitchResult.isPitchFlat}")
                    appendLine()
                }
                if (weights.isNotEmpty()) {
                    appendLine("ENSEMBLE WEIGHTS")
                    weights.forEach { (k, v) -> appendLine("  $k : ${"%.2f".format(v)}") }
                    appendLine()
                }
                val verdict = if (session.events.isEmpty()) "GENUINE" else
                    "COMPROMISED (${session.events.size} events, peak ${(session.maxConfidence * 100).toInt()}%)"
                appendLine("VERDICT: $verdict")
            }
        }
    }

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

            val hdr = PdfPTable(1).apply { setWidthPercentage(100f); spacingAfter = 4f }
            hdr.addCell(PdfPCell(Phrase("VOICESHIELD NEURAL FORENSIC REPORT  REV-5.0", fHead)).apply {
                backgroundColor = BaseColor(8, 10, 14); paddingTop = 10f; paddingBottom = 10f; paddingLeft = 14f; border = 0
            })
            doc.add(hdr)
            doc.add(Paragraph("VoiceShield — AI Voice Deepfake Detection Report", fTitle).apply { spacingAfter = 2f })
            doc.add(Paragraph("Generated: ${df.format(Date())}  ·  On-device analysis  ·  No audio transmitted", fMono).apply { spacingAfter = 14f })

            doc.add(sH("SESSION IDENTITY", fHead))
            val idT = tC(); fun r(l: String, v: String, vf: Font = fMono) { idT.addCell(c(l, fBody)); idT.addCell(c(v, vf)) }
            r("Input Mode", mode); if (fileName.isNotEmpty()) r("Source File", fileName)
            if (startMs > 0) r("Session Start", df.format(Date(startMs)))
            val realEnd = sessionLog.endTime ?: System.currentTimeMillis()
            r("Session End", df.format(Date(realEnd)))
            val dur = (realEnd - sessionLog.startTime).coerceAtLeast(0L)
            r("Duration", "${dur/1000L}s  (${dur/60000L}m ${(dur/1000L)%60}s)")
            r("Analysis Engine", engineLabel(mode)); r("Detection Sensitivity", "MEDIUM — 60% threshold")
            doc.add(idT)

            doc.add(sH("SESSION STATISTICS", fHead))
            val pct = if (sessionLog.totalWindowsAnalyzed > 0)
                (sessionLog.flaggedWindows.toFloat() / sessionLog.totalWindowsAnalyzed * 100f) else 0f
            val stT = tC()
            fun sr(l: String, v: String, vf: Font = fMono) { stT.addCell(c(l, fBody)); stT.addCell(c(v, vf)) }
            sr("Total Windows Analysed", "${sessionLog.totalWindowsAnalyzed}")
            sr("Windows Flagged",         "${sessionLog.flaggedWindows}")
            sr("% Time Synthetic",        "%.1f%%".format(pct), if (pct > 30f) fRed else fGreen)
            sr("Peak AI Confidence",      "${(sessionLog.maxConfidence * 100).roundToInt()}%",
                if (sessionLog.maxConfidence > .70f) fRed else fBody)
            sr("Anomaly Events",          "${sessionLog.events.size}", if (sessionLog.events.isNotEmpty()) fRed else fGreen)
            val density = if (dur > 0) sessionLog.events.size / (dur / 60000f) else 0f
            sr("Threat Density",          "${"%.2f".format(density)} events/min", if (density > 2f) fRed else fBody)
            doc.add(stT)

            if (emaHistory.isNotEmpty()) {
                doc.add(sH("CONFIDENCE TIMELINE", fHead))
                doc.add(bI(rCC(emaHistory, .60f), 515f, 120f).apply { spacingAfter = 4f })
                doc.add(Paragraph("Dashed orange = 60% threshold. Red zone = flagged. Each datapoint = 2s window.", fGray).apply { spacingAfter = 14f })
            }

            if (f0History.isNotEmpty()) {
                doc.add(sH("PITCH (F0) TIMELINE", fHead))
                doc.add(bI(rFC(f0History), 515f, 100f).apply { spacingAfter = 4f })
                doc.add(Paragraph("Flat F0 = TTS/vocoder signature. Natural speech shows 50–200Hz variation.", fGray).apply { spacingAfter = 14f })
            }

            if (sessionLog.events.isNotEmpty()) {
                doc.add(sH("ANOMALY TIMELINE", fHead))
                val barW = 88
                val bar = buildString {
                    append("["); val filled = CharArray(barW) { '-' }
                    sessionLog.events.forEach { ev ->
                        val prog = ((ev.timestampMs - sessionLog.startTime).toFloat() / dur.coerceAtLeast(1) * barW)
                            .roundToInt().coerceIn(0, barW - 1)
                        filled[prog] = if (ev.smoothedScore > .80f) '█' else '▒'
                    }
                    append(String(filled)); append("]")
                }
                doc.add(Paragraph(bar, fMonoSm).apply { spacingAfter = 3f })
                doc.add(Paragraph("█=CRITICAL  ▒=HIGH  -=CLEAR", fGray).apply { spacingAfter = 10f })
                val evT = PdfPTable(5).apply { setWidthPercentage(100f); setWidths(floatArrayOf(1.5f, 1f, 1.2f, 1.2f, 2.2f)); spacingAfter = 14f }
                listOf("Timestamp", "Elapsed", "EMA", "Raw", "Severity").forEach { h ->
                    evT.addCell(PdfPCell(Phrase(h, fHead2)).apply { backgroundColor = BaseColor(215, 220, 230); paddingBottom = 5f })
                }
                sessionLog.events.forEach { ev ->
                    val el = ev.timestampMs - sessionLog.startTime
                    val sf = when { ev.smoothedScore > .80f -> fRed; ev.smoothedScore > .60f -> fAmber; else -> fBody }
                    val tier = when { ev.smoothedScore > .80f -> "CRITICAL"; ev.smoothedScore > .60f -> "HIGH"; else -> "MEDIUM" }
                    evT.addCell(c(ev.timeLabel, fMono)); evT.addCell(c("+${el/1000}s", fMono))
                    evT.addCell(c("${(ev.smoothedScore*100).roundToInt()}%", sf))
                    evT.addCell(c("${(ev.rawScore*100).roundToInt()}%", fBody))
                    evT.addCell(c(tier, fBody))
                }
                doc.add(evT)
            }

            doc.add(sH("FORENSIC VERDICT", fHead))
            val vT = PdfPTable(1).apply { setWidthPercentage(100f); spacingAfter = 14f }
            val vText = if (sessionLog.events.isEmpty())
                "GENUINE — No synthetic voice anomalies detected across ${sessionLog.totalWindowsAnalyzed} analysis windows."
            else "COMPROMISED — ${sessionLog.events.size} synthetic voice event(s) detected. Peak: ${(sessionLog.maxConfidence*100).roundToInt()}%."
            vT.addCell(PdfPCell(Phrase(vText, if (sessionLog.events.isEmpty()) fGreen else fRed)).apply {
                backgroundColor = if (sessionLog.events.isEmpty()) BaseColor(228, 255, 238) else BaseColor(255, 232, 232)
                paddingTop = 12f; paddingBottom = 12f; paddingLeft = 14f; border = 0
            })
            doc.add(vT)
            doc.add(Paragraph("DISCLAIMER: Vendor attribution is derived from spectral heuristics, not a trained classifier.", fWarn).apply { spacingAfter = 14f })

            if (signature.isNotEmpty()) {
                doc.add(sH("CRYPTOGRAPHIC INTEGRITY", fHead))
                doc.add(Paragraph("RSA-2048, Android Keystore TEE. Signature covers all session metadata.", fBody).apply { spacingAfter = 8f })
                val sigT = PdfPTable(2).apply { setWidthPercentage(100f); spacingAfter = 8f }
                sigT.addCell(c("Algorithm", fBody)); sigT.addCell(c("SHA256withRSA", fMono))
                sigT.addCell(c("Signature", fBody)); sigT.addCell(c(signature.take(80) + "…", fMonoSm))
                if (publicKey.isNotEmpty()) { sigT.addCell(c("Public Key", fBody)); sigT.addCell(c(publicKey.take(80) + "…", fMonoSm)) }
                doc.add(sigT)
            }

            doc.add(Paragraph("VoiceShield  ·  On-device Neural Audio Forensics  ·  No audio transmitted  ·  Rev 5.0",
                FontFactory.getFont(FontFactory.HELVETICA, 8f, BaseColor.GRAY)))
            doc.close()
            share(pdf)
        } catch (e: Exception) { e.printStackTrace() }
    }


    private fun rCC(history: List<Float>, threshold: Float): Bitmap {
        val W = 800; val H = 180; val pad = 20
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.drawColor(android.graphics.Color.argb(255, 9, 11, 16))
        val grid = Paint().apply { color = android.graphics.Color.argb(25, 255, 255, 255); strokeWidth = 1f }
        for (i in 1..4) cv.drawLine(pad.f, (pad + i * (H - 2 * pad) / 5).f, (W - pad).f, (pad + i * (H - 2 * pad) / 5).f, grid)
        val thrY = pad + (1f - threshold) * (H - 2 * pad)
        cv.drawLine(pad.f, thrY, (W - pad).f, thrY, Paint().apply {
            color = android.graphics.Color.argb(180, 255, 170, 0); strokeWidth = 1.5f
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 5f), 0f)
        })
        if (history.isEmpty()) return bmp
        val xs = (W - 2f * pad) / (history.size - 1).coerceAtLeast(1)
        val fp = android.graphics.Path()
        fp.moveTo(pad.f, (H - pad).f)
        history.forEachIndexed { i, v -> fp.lineTo(pad + i * xs, pad + (1f - v.coerceIn(0f, 1f)) * (H - 2 * pad)) }
        fp.lineTo(pad + (history.size - 1) * xs, (H - pad).f); fp.close()
        cv.drawPath(fp, Paint().apply { color = android.graphics.Color.argb(30, 0, 255, 65) })
        val lp = android.graphics.Path()
        val lP = Paint().apply { isAntiAlias = true; strokeWidth = 2.5f; style = Paint.Style.STROKE }
        history.forEachIndexed { i, v ->
            val x = pad + i * xs; val y = pad + (1f - v.coerceIn(0f, 1f)) * (H - 2 * pad)
            lP.color = when { v >= .60f -> android.graphics.Color.rgb(255, 0, 40); v >= .35f -> android.graphics.Color.rgb(255, 170, 0); else -> android.graphics.Color.rgb(0, 255, 65) }
            if (i == 0) lp.moveTo(x, y) else lp.lineTo(x, y)
        }
        cv.drawPath(lp, lP)
        return bmp
    }

    private fun rFC(f0: List<Float>): Bitmap {
        val W = 800; val H = 140; val pad = 20
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.drawColor(android.graphics.Color.argb(255, 9, 11, 16))
        if (f0.isEmpty()) return bmp
        val xs = (W - 2f * pad) / (f0.size - 1).coerceAtLeast(1)
        val p = android.graphics.Path()
        f0.forEachIndexed { i, v ->
            val x = pad + i * xs; val y = pad + (1f - ((v - 80f) / 320f).coerceIn(0f, 1f)) * (H - 2 * pad)
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        val mean = f0.average().toFloat()
        val variance = f0.map { (it - mean).pow(2) }.average().toFloat()
        val col = if (variance < 500f) android.graphics.Color.rgb(255, 100, 0) else android.graphics.Color.rgb(0, 200, 255)
        cv.drawPath(p, Paint().apply { color = col; strokeWidth = 2f; style = Paint.Style.STROKE; isAntiAlias = true })
        cv.drawText(if (variance < 500f) "LOW VARIANCE — TTS SIGNATURE" else "NATURAL VARIATION", (pad + 4).f, (pad + 16).f,
            Paint().apply { color = col; textSize = 16f; isAntiAlias = true; typeface = Typeface.MONOSPACE })
        return bmp
    }

    private fun sH(t: String, f: Font) = Paragraph(t, f).apply { spacingAfter = 6f; spacingBefore = 4f }
    private fun tC() = PdfPTable(2).apply { setWidthPercentage(100f); spacingAfter = 14f }
    private fun c(v: String, f: Font) = PdfPCell(Phrase(v, f)).apply { border = 0; paddingBottom = 5f }
    private fun bI(bmp: Bitmap, w: Float, h: Float): Image {
        val s = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.PNG, 95, s)
        return Image.getInstance(s.toByteArray()).apply { scaleToFit(w, h) }
    }
    private fun engineLabel(mode: String) = when (mode) {
        "DEMO" -> "Scripted simulation (BiomarkerExtractor + EscalationEngine)"
        "FILE" -> "MediaCodec decode → AASIST-INT8 TFLite + full biomarker pipeline"
        else   -> "AASIST-INT8 TFLite (live AudioRecord 16kHz) + biomarker pipeline"
    }
    private fun share(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "VoiceShield Forensic Report")
            putExtra(Intent.EXTRA_TEXT, "On-device AI voice forensic analysis. No audio transmitted.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share Report"))
    }

    private val Int.f get() = this.toFloat()
    private val Float.f get() = this
    private fun Float.pow(n: Float) = Math.pow(this.toDouble(), n.toDouble()).toFloat()
    private fun Float.roundToInt() = Math.round(this)
}