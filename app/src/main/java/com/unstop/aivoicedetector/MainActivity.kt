package com.unstop.aivoicedetector

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

enum class InputMode { LIVE, DEMO, FILE }

class MainActivity : ComponentActivity() {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val busy  = AtomicBoolean(false)

    private val reqAudio = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val reqNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { launchFile(it) }
    }

    private lateinit var capture:     AudioCaptureHelper
    private lateinit var features:    FeatureExtractor
    private lateinit var model:       AASISTClassifier
    private lateinit var engine:      DecisionEngine
    private lateinit var demoMgr:     DemoModeManager
    private lateinit var voiceId:     VoiceContinuityEngine
    private lateinit var attrEngine:  AttributionEngine
    private lateinit var signer:      ForensicSigner
    private lateinit var threatDb:    ThreatIntelManager
    private lateinit var bioExtractor: BiomarkerExtractor

    private var notifTs  = 0L
    private var lastSig  = ""
    private var lastPub  = ""

    // ── observable state ────────────────────────────────────────────────
    private val sEma         = mutableFloatStateOf(0f)
    private val sRaw         = mutableFloatStateOf(0f)
    private val sScanning    = mutableStateOf(false)
    private val sMode        = mutableStateOf(InputMode.LIVE)
    private val sEvents      = mutableStateOf(listOf<AnomalyEvent>())
    private val sAlert       = mutableStateOf(false)
    private val sWave        = mutableStateOf(FloatArray(200))
    private val sHistory     = mutableStateOf(listOf<Float>())
    private val sDrift       = mutableFloatStateOf(0f)
    private val sAttrib      = mutableStateOf<Attribution?>(null)
    private val sMel         = mutableStateOf(FloatArray(80))
    private val sWaterfall   = mutableStateOf(listOf<FloatArray>())
    private val sWindows     = mutableIntStateOf(0)
    private val sThreat      = mutableStateOf(false)
    private val sLatency     = mutableFloatStateOf(0f)
    private val sStartMs     = mutableLongStateOf(0L)
    private val sElapsedMs   = mutableLongStateOf(0L)
    private val sTotalMs     = mutableLongStateOf(0L)
    private val sFileName    = mutableStateOf("")
    private val sFileWin     = mutableIntStateOf(-1)
    private val sTotalWin    = mutableIntStateOf(0)
    private val sAnalysing   = mutableStateOf(false)
    // new: biomarkers + escalation
    private val sBio         = mutableStateOf<AudioBiomarkers?>(null)
    private val sEscalation  = mutableStateOf<EscalationResult?>(null)
    private val sPredicted5  = mutableFloatStateOf(0f)
    private val sProtection  = mutableIntStateOf(100)
    private val sF0History   = mutableStateOf(listOf<Float>())   // pitch over time

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        perms()
        capture    = AudioCaptureHelper(this)
        features   = FeatureExtractor()
        model      = AASISTClassifier(this)
        engine     = DecisionEngine()
        voiceId    = VoiceContinuityEngine()
        attrEngine = AttributionEngine()
        signer     = ForensicSigner(this)
        threatDb   = ThreatIntelManager(this)
        bioExtractor = BiomarkerExtractor()
        demoMgr    = DemoModeManager { buf -> offer(buf) }

        setContent {
            AIVoiceDetectorTheme {
                Surface(Modifier.fillMaxSize(), color = Abyss) { Screen() }
            }
        }
    }

    private fun offer(buf: FloatArray) {
        if (!busy.compareAndSet(false, true)) return
        pipeline(buf)
    }

    private fun goLive() {
        if (sScanning.value) return
        resetState(InputMode.LIVE)
        DetectionService.start(this)
        capture.startCapture { buf -> offer(buf) }
        tickTimer()
    }

    private fun goDemo() {
        if (sScanning.value) return
        resetState(InputMode.DEMO)
        DetectionService.start(this)
        demoMgr.startDemo()
        tickTimer()
    }

    private fun goFile() = pickFile.launch("audio/*")

    private fun launchFile(uri: Uri) {
        if (sScanning.value) return
        val name = contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            c.moveToFirst(); if (i >= 0) c.getString(i) else "audio"
        } ?: "audio"
        sAnalysing.value = true; sFileName.value = name
        scope.launch {
            val result = AudioFileDecoder.decode(this@MainActivity, uri, name)
            if (result == null) { sAnalysing.value = false; return@launch }
            resetState(InputMode.FILE)
            sTotalMs.longValue = result.durationMs; sTotalWin.intValue = result.windows.size
            sFileName.value = result.fileName; sAnalysing.value = false
            result.windows.forEachIndexed { idx, win ->
                if (!sScanning.value) return@forEachIndexed
                sFileWin.intValue = idx
                sElapsedMs.longValue = idx * 500L
                offer(win); delay(120L)
            }
            if (sScanning.value) { 
                sElapsedMs.longValue = result.durationMs
                withContext(Dispatchers.Main) { terminate() } 
            }
        }
    }

    private fun pipeline(buf: FloatArray) {
        scope.launch {
            try {
                if (buf.isNotEmpty()) {
                    val step = (buf.size / 200).coerceAtLeast(1)
                    sWave.value = FloatArray(200) { i -> buf[(i * step).coerceAtMost(buf.size - 1)] }
                }

                // ── Bug #19 fix: SNR gate threshold ──────────────────────────
                // computeSNR() now returns 0–60 dB (was always returning ~10 dB).
                // A signal with SNR < 3 dB is essentially pure noise — skip it.
                val snr = features.computeSNR(buf)
                if (snr < 3f && sMode.value != InputMode.DEMO) {
                    busy.set(false)
                    return@launch
                }

                // ── Bug #4 fix: compute mel ONCE, derive MFCC from it ────────
                // Was: extractMelSpectrogram(buf) + extractMfcc(buf) = 2× mel compute
                val mel  = features.extractMelSpectrogram(buf)
                val mfcc = features.extractMfccFromMel(mel)   // reuses mel, no recompute

                // Aggregate mel bands for ring visualisation
                val nF = (mel.size / 80).coerceAtLeast(1)
                val mb = FloatArray(80) { b ->
                    var s = 0f
                    repeat(nF) { f -> val idx = f * 80 + b; if (idx < mel.size) s += mel[idx] }
                    s / nF
                }
                sMel.value = mb
                sWaterfall.value = sWaterfall.value.toMutableList().also {
                    it.add(mb.copyOf()); if (it.size > 60) it.removeAt(0)
                }

                // ── Bug #20 fix: use bioExtractor class instance ──────────────
                // BiomarkerExtractor is now stateful (tracks prevMelFrame for
                // temporal flux).  Using the object directly would carry stale
                // prevMelFrame across sessions.
                val bio = bioExtractor.extract(buf, mb)
                sBio.value = bio

                sF0History.value = sF0History.value.toMutableList().also {
                    it.add(bio.f0Estimate); if (it.size > 200) it.removeAt(0)
                }

                val known = sMode.value != InputMode.DEMO && threatDb.isThreat(mfcc)
                sThreat.value = known

                val drift = voiceId.process(mfcc)
                sWindows.intValue++

                val raw = when {
                    sMode.value == InputMode.DEMO -> demoMgr.getDemoScore()
                    known -> 0.95f
                    else  -> model.classifyAudio(buf, mel, bio)
                }

                sLatency.floatValue = model.lastLatencyMs
                val ema = engine.processBatch(listOf(raw))

                val esc = ThreatEscalationEngine.analyse(
                    sHistory.value, engine.currentSensitivity.threshold, engine.currentSession)
                sEscalation.value      = esc
                sPredicted5.floatValue = esc.predicted5
                sProtection.intValue   = esc.protectionPct

                val now = System.currentTimeMillis()
                if (now - notifTs > 2000L) {
                    notifTs = now
                    DetectionService.updateNotification(this@MainActivity, ema,
                        if (sMode.value == InputMode.DEMO) "DEMO" else model.inferenceEngine)
                }

                sRaw.floatValue   = raw
                sEma.floatValue   = ema
                sDrift.floatValue = drift.driftPercent

                val newA: Attribution? = when {
                    sMode.value == InputMode.DEMO && ema >= .40f -> demoMgr.currentClipAttribution()
                    sMode.value != InputMode.DEMO && ema >= .30f ->
                        attrEngine.attribute(mel).takeIf { it.engine != "Unknown" }
                    ema < .15f -> null
                    else       -> sAttrib.value
                }
                if (newA != null || ema < .15f) sAttrib.value = newA

                sHistory.value = sHistory.value.toMutableList().also {
                    it.add(ema); if (it.size > 200) it.removeAt(0)
                }

                val evs = engine.currentSession?.events?.toList() ?: emptyList()
                if (evs.size > sEvents.value.size) {
                    sAlert.value = true
                    if (ema > 0.75f) threatDb.reportConfirmedFake(mfcc)
                    launch(Dispatchers.Main) { delay(5500); sAlert.value = false }
                }
                if (drift.alert && !sAlert.value) {
                    sAlert.value = true
                    launch(Dispatchers.Main) { delay(4000); sAlert.value = false }
                }
                sEvents.value = evs

            } finally { busy.set(false) }
        }
    }

    private var timerJob: Job? = null
    private fun tickTimer() {
        sStartMs.longValue = System.currentTimeMillis(); sElapsedMs.longValue = 0L
        timerJob?.cancel()
        timerJob = scope.launch {
            while (sScanning.value) { delay(1000L); sElapsedMs.longValue = System.currentTimeMillis()-sStartMs.longValue }
        }
    }

    private fun resetState(mode: InputMode) {
        sThreat.value=false; lastSig=""; lastPub=""
        engine.startSession(); voiceId.reset(); bioExtractor.reset()
        sHistory.value=listOf(); sAlert.value=false; sAttrib.value=null
        sWindows.intValue=0; sWaterfall.value=listOf(); sBio.value=null
        sEscalation.value=null; sF0History.value=listOf()
        sMode.value=mode; sScanning.value=true
        sStartMs.longValue=System.currentTimeMillis()
        sElapsedMs.longValue=0L; sTotalMs.longValue=0L
        sFileWin.intValue=-1; sTotalWin.intValue=0
    }

    private fun terminate() {
        timerJob?.cancel()
        engine.stopSession(); DetectionService.stop(this)
        sScanning.value=false; sAlert.value=false
        
        when (sMode.value) {
            InputMode.DEMO -> demoMgr.stopDemo()
            InputMode.LIVE -> capture.stopCapture()
            else -> {}
        }

        val s = engine.currentSession ?: return
        scope.launch {
            try {
                lastPub = signer.publicKeyBase64()
                lastSig = signer.signSession(JSONObject().apply {
                    put("t0",s.startTime); put("t1",s.endTime?:0L)
                    put("ev",s.events.size); put("max",s.maxConfidence)
                    put("win",s.totalWindowsAnalyzed); put("pub",lastPub)
                    put("mode",sMode.value.name); put("file",sFileName.value)
                }.toString())
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private val isClosing = AtomicBoolean(false)
    private fun performFinalCleanup() {
        if (!isClosing.compareAndSet(false, true)) return
        terminate()
        scope.cancel(); demoMgr.destroy(); model.close()
    }

    override fun finish() {
        performFinalCleanup()
        super.finish()
    }

    override fun onDestroy() {
        performFinalCleanup()
        super.onDestroy()
    }

    private fun perms() {
        if (ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)
            reqAudio.launch(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT>=Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            reqNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // ════════════════════════════════════════════════════════════════════
    @Composable
    fun Screen() {
        val ema        = sEma.floatValue
        val raw        = sRaw.floatValue
        val scanning   = sScanning.value
        val mode       = sMode.value
        val events     = sEvents.value
        val alertOn    = sAlert.value
        val wave       = sWave.value
        val history    = sHistory.value
        val drift      = sDrift.floatValue
        val attrib     = sAttrib.value
        val mel        = sMel.value
        val waterfall  = sWaterfall.value
        val windows    = sWindows.intValue
        val threatHit  = sThreat.value
        val latency    = sLatency.floatValue
        val thr        = engine.currentSensitivity.threshold
        val flagged    = events.isNotEmpty()
        val startMs    = sStartMs.longValue
        val elapsedMs  = sElapsedMs.longValue
        val totalMs    = sTotalMs.longValue
        val fileName   = sFileName.value
        val fileWin    = sFileWin.intValue
        val totalWin   = sTotalWin.intValue
        val analysing  = sAnalysing.value
        val bio        = sBio.value
        val escalation = sEscalation.value
        val predicted5 = sPredicted5.floatValue
        val protection = sProtection.intValue

        val inf = rememberInfiniteTransition(label="main")
        val blink by inf.animateFloat(0f,1f, infiniteRepeatable(tween(550),RepeatMode.Reverse), label="bl")

        val statusCol = when {
            threatHit||ema>=.60f -> Crimson; ema>=.35f -> Amber; scanning -> Phosphor; else -> Ink
        }
        val fmt = { ms:Long -> val s=ms/1000L; "%02d:%02d".format(s/60,s%60) }
        val dfTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        Box(Modifier.fillMaxSize().background(Abyss).drawBehind {
            var y=0f; while(y<size.height){drawRect(Color(0x05000000),Offset(0f,y),Size(size.width,1.5.dp.toPx()));y+=3.dp.toPx()}
            drawRect(statusCol.copy(.65f),Offset(0f,0f),Size(2.dp.toPx(),size.height))
            drawRect(statusCol.copy(.07f),Offset(0f,0f),Size(22.dp.toPx(),size.height))
        }) {
            Column(Modifier.fillMaxSize().padding(horizontal=12.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment=Alignment.CenterHorizontally) {
                Spacer(Modifier.height(10.dp))

                // ── HEADER ────────────────────────────────────────────────
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Column {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Box(Modifier.size(5.dp).clip(RoundedCornerShape(3.dp))
                                .background(statusCol.copy(if(scanning) .5f+blink*.5f else .3f)))
                            Spacer(Modifier.width(7.dp))
                            Text("VOICESHIELD",fontFamily=FontFamily.Monospace,fontSize=12.sp,
                                fontWeight=FontWeight.Black,color=statusCol,letterSpacing=4.sp)
                        }
                        Text("NEURAL FORENSIC TERMINAL  REV-5.0",
                            fontFamily=FontFamily.Monospace,fontSize=7.sp,color=Ink,letterSpacing=1.5.sp,
                            modifier=Modifier.padding(top=2.dp,start=12.dp))
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp), verticalAlignment=Alignment.CenterVertically) {
                        // protection gauge
                        ProtectionGauge(protection, Modifier.size(50.dp))
                        Box(Modifier.border(.5.dp,Crimson.copy(.5f),RoundedCornerShape(2.dp))
                            .padding(horizontal=5.dp,vertical=3.dp)) {
                            Text("TOP SECRET",fontFamily=FontFamily.Monospace,fontSize=6.sp,
                                color=Crimson.copy(.6f),letterSpacing=2.sp)
                        }
                    }
                }

                // ── STATUS STRIP ──────────────────────────────────────────
                Spacer(Modifier.height(7.dp))
                Row(Modifier.fillMaxWidth()
                    .background(AbyssPanel,RoundedCornerShape(3.dp))
                    .border(.5.dp,AbyssStroke,RoundedCornerShape(3.dp))
                    .padding(horizontal=10.dp,vertical=4.dp),
                    Arrangement.SpaceBetween,Alignment.CenterVertically) {
                    Text(when {
                        analysing  -> "DECODING: $fileName"
                        threatHit  -> "KNOWN VOICEPRINT MATCHED"
                        mode==InputMode.DEMO -> "DEMO · CLIP ${demoMgr.currentClipIndex+1}/5"
                        mode==InputMode.FILE && fileName.isNotEmpty() -> "FILE: ${fileName.take(22)}"
                        scanning   -> "${model.inferenceEngine} · ACTIVE"
                        else       -> "SYSTEM STANDBY"
                    },fontFamily=FontFamily.Monospace,fontSize=8.sp,color=statusCol,letterSpacing=1.sp)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        if (latency>0f&&mode==InputMode.LIVE) TinyL("${latency.toInt()}MS",InkMid)
                        TinyL("W:$windows",InkMid)
                        TinyL("F:${events.size}",if(flagged) Crimson else Ink)
                        if (threatDb.threatCount()>0) TinyL("DB:${threatDb.threatCount()}",Amber)
                    }
                }

                // ── ESCALATION BANNER ─────────────────────────────────────
                escalation?.let { esc ->
                    if (esc.state !is EscalationState.Stable && esc.state !is EscalationState.Insufficient) {
                        Spacer(Modifier.height(6.dp))
                        EscalationBanner(esc, Modifier.fillMaxWidth())
                    }
                }

                // ── TIMELINE ──────────────────────────────────────────────
                if (scanning || engine.currentSession != null) {
                    Spacer(Modifier.height(7.dp))
                    Box(Modifier.fillMaxWidth()
                        .background(AbyssPanel,RoundedCornerShape(4.dp))
                        .border(.5.dp,AbyssStroke,RoundedCornerShape(4.dp))
                        .padding(horizontal=10.dp,vertical=10.dp)) {
                        Column {
                            Row(Modifier.fillMaxWidth(),Arrangement.SpaceBetween,Alignment.CenterVertically) {
                                Row(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically) {
                                    Box(Modifier.background(when(mode){
                                        InputMode.LIVE->PhosphorDim;InputMode.DEMO->ArcticDim;InputMode.FILE->AmberDim
                                    },RoundedCornerShape(2.dp)).padding(horizontal=5.dp,vertical=2.dp)) {
                                        Text(mode.name,fontFamily=FontFamily.Monospace,fontSize=7.sp,
                                            color=when(mode){InputMode.LIVE->Phosphor;InputMode.DEMO->Arctic;InputMode.FILE->Amber},
                                            letterSpacing=2.sp)
                                    }
                                    if (mode==InputMode.FILE&&fileName.isNotEmpty())
                                        Text(fileName.take(18),fontFamily=FontFamily.Monospace,fontSize=7.sp,color=InkMid)
                                }
                                Row(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically) {
                                    Text(fmt(elapsedMs),fontFamily=FontFamily.Monospace,fontSize=11.sp,
                                        fontWeight=FontWeight.Black,color=if(scanning) Phosphor else InkMid)
                                    if (totalMs>0) {
                                        Text("/",fontFamily=FontFamily.Monospace,fontSize=9.sp,color=Ink)
                                        Text(fmt(totalMs),fontFamily=FontFamily.Monospace,fontSize=9.sp,color=InkMid)
                                    } else if (scanning) {
                                        Text("●",fontFamily=FontFamily.Monospace,fontSize=9.sp,color=Phosphor.copy(blink))
                                    }
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            SessionTimelineRuler(startMs,elapsedMs,totalMs,events,fileWin,totalWin,
                                Modifier.fillMaxWidth().height(32.dp))
                            Spacer(Modifier.height(6.dp))
                            Row(Modifier.fillMaxWidth(),Arrangement.SpaceBetween) {
                                TinyL(if(startMs>0) dfTime.format(Date(startMs)) else "--:--:--",InkMid)
                                if (events.isNotEmpty()) TinyL("${events.size} FLAG${if(events.size!=1)"S" else ""}",Crimson.copy(.7f))
                                TinyL(if(scanning) "LIVE" else (engine.currentSession?.endTime?.let{dfTime.format(Date(it))}?:"--:--:--"),InkMid)
                            }
                        }
                    }
                }

                // ── FILE DECODE SPINNER ───────────────────────────────────
                if (analysing) {
                    Spacer(Modifier.height(8.dp))
                    val spin by rememberInfiniteTransition(label="sp").animateFloat(0f,1f,
                        infiniteRepeatable(tween(800,easing=LinearEasing)),label="sp2")
                    Box(Modifier.fillMaxWidth().background(AmberDim,RoundedCornerShape(4.dp))
                        .border(.5.dp,Amber.copy(.3f),RoundedCornerShape(4.dp)).padding(12.dp)) {
                        Column(horizontalAlignment=Alignment.CenterHorizontally,modifier=Modifier.fillMaxWidth()) {
                            TinyL("DECODING AUDIO FILE",Amber)
                            Spacer(Modifier.height(6.dp))
                            Text(listOf("◐","◓","◑","◒")[(spin*4).toInt()%4],fontSize=20.sp,color=Amber)
                            TinyL(fileName.take(30),InkMid)
                        }
                    }
                }

                // ── THREAT RING ───────────────────────────────────────────
                Spacer(Modifier.height(10.dp))
                Box(Modifier.size(300.dp), contentAlignment=Alignment.Center) {
                    NeuralThreatRing(ema, raw, predicted5, mel, scanning, flagged, Modifier.fillMaxSize())
                    Column(horizontalAlignment=Alignment.CenterHorizontally) {
                        val pct=(ema*100).toInt()
                        val col=when{ema>=.60f->Crimson;ema>=.35f->Amber;else->Phosphor}
                        Text(if(pct<10)"0$pct" else "$pct",
                            fontFamily=FontFamily.Monospace,fontSize=58.sp,
                            fontWeight=FontWeight.Black,color=col,letterSpacing=(-2).sp)
                        Text("%  SYNTHETIC",fontFamily=FontFamily.Monospace,
                            fontSize=7.sp,color=col.copy(.55f),letterSpacing=2.sp)
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment=Alignment.CenterVertically,
                            horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                            Box(Modifier.size(4.dp).clip(RoundedCornerShape(2.dp))
                                .background(col.copy(if(scanning).4f+blink*.6f else .3f)))
                            Text(when{
                                threatHit->"KNOWN THREAT";ema>=.60f->"SYNTHETIC";ema>=.35f->"SUSPICIOUS"
                                scanning->"GENUINE";else->"OFFLINE"
                            },fontFamily=FontFamily.Monospace,fontSize=7.sp,color=col,letterSpacing=1.5.sp)
                        }
                        // prediction label
                        if (predicted5 > ema+.05f && scanning) {
                            Spacer(Modifier.height(2.dp))
                            Text("↑ PRED ${(predicted5*100).toInt()}%",
                                fontFamily=FontFamily.Monospace,fontSize=6.sp,
                                color=Amber.copy(.7f),letterSpacing=1.sp)
                        }
                    }
                }

                // ── ATTRIBUTION ───────────────────────────────────────────
                if (attrib!=null&&ema>=.25f) {
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth()
                        .background(CrimsonDim,RoundedCornerShape(4.dp))
                        .border(.5.dp,Crimson.copy(.35f),RoundedCornerShape(4.dp))
                        .padding(horizontal=12.dp,vertical=10.dp),
                        Arrangement.SpaceBetween,Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            TinyL("HEURISTIC ATTRIBUTION",Crimson.copy(.5f))
                            Text(attrib.engine.uppercase(),fontFamily=FontFamily.Monospace,
                                fontSize=15.sp,fontWeight=FontWeight.Black,color=Amber)
                            Text(attrib.reason,fontFamily=FontFamily.Monospace,
                                fontSize=8.sp,color=InkMid,modifier=Modifier.padding(top=2.dp))
                        }
                        Text("${(attrib.confidence*100).toInt()}%",
                            fontFamily=FontFamily.Monospace,fontSize=26.sp,
                            fontWeight=FontWeight.Black,color=Amber)
                    }
                }

                // ── DATA CELLS ────────────────────────────────────────────
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                    DataCell("EMA",     "${(ema*100).toInt()}%",
                        when{ema>=.60f->Crimson;ema>=.35f->Amber;else->Phosphor}, Modifier.weight(1f))
                    DataCell("RAW",     "${(raw*100).toInt()}%", InkMid, Modifier.weight(1f))
                    DataCell("DRIFT",   "${"%.1f".format(drift)}%",
                        when{drift>25f->Crimson;drift>10f->Amber;else->Phosphor}, Modifier.weight(1f))
                    DataCell("FLAGS",   "${events.size}",
                        if(flagged) Crimson else InkMid, Modifier.weight(1f))
                }

                // ── BIOMARKER PANEL ───────────────────────────────────────
                if (bio != null) {
                    Spacer(Modifier.height(5.dp))
                    BiomarkerPanel(bio, Modifier.fillMaxWidth())
                }

                // ── OSCILLOSCOPE ──────────────────────────────────────────
                Spacer(Modifier.height(5.dp))
                Box(Modifier.fillMaxWidth()
                    .background(AbyssPanel,RoundedCornerShape(4.dp))
                    .border(.5.dp,AbyssStroke,RoundedCornerShape(4.dp)).padding(8.dp)) {
                    Column {
                        TinyL("OSCILLOSCOPE · 16kHz PCM",InkMid)
                        Spacer(Modifier.height(4.dp))
                        RetinalOscilloscope(if(scanning) wave else FloatArray(0),
                            ema,events,Modifier.fillMaxWidth().height(70.dp))
                    }
                }

                // ── SPECTRAL WATERFALL ────────────────────────────────────
                if (waterfall.isNotEmpty()) {
                    Spacer(Modifier.height(5.dp))
                    Box(Modifier.fillMaxWidth()
                        .background(AbyssPanel,RoundedCornerShape(4.dp))
                        .border(.5.dp,AbyssStroke,RoundedCornerShape(4.dp)).padding(8.dp)) {
                        Column {
                            TinyL("SPECTRAL WATERFALL · MEL 80-BIN",InkMid)
                            Spacer(Modifier.height(4.dp))
                            SpectralWaterfall(waterfall,Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(3.dp)))
                        }
                    }
                }

                // ── SEISMOGRAPH ───────────────────────────────────────────
                Spacer(Modifier.height(5.dp))
                Box(Modifier.fillMaxWidth()
                    .background(AbyssPanel,RoundedCornerShape(4.dp))
                    .border(.5.dp,AbyssStroke,RoundedCornerShape(4.dp))
                    .padding(start=8.dp,end=8.dp,top=8.dp,bottom=2.dp)) {
                    Column {
                        Row(Modifier.fillMaxWidth(),Arrangement.SpaceBetween) {
                            TinyL("EMA SEISMOGRAPH · THR ${(thr*100).toInt()}%",InkMid)
                            escalation?.let { TinyL("SLOPE ${(it.slope*100).toInt()}%/WIN",
                                if(it.slope>0.01f) Amber else InkMid) }
                        }
                        Spacer(Modifier.height(3.dp))
                        SeismographView(history,thr)
                    }
                }

                // ── ANOMALY LOG ───────────────────────────────────────────
                if (events.isNotEmpty()) {
                    Spacer(Modifier.height(5.dp))
                    Box(Modifier.fillMaxWidth()
                        .background(CrimsonGhost,RoundedCornerShape(4.dp))
                        .border(.5.dp,Crimson.copy(.2f),RoundedCornerShape(4.dp)).padding(8.dp)) {
                        Column {
                            TinyL("ANOMALY LOG  [${events.size} EVENT${if(events.size!=1)"S" else ""}]",Crimson.copy(.6f))
                            Spacer(Modifier.height(6.dp))
                            events.reversed().take(8).forEach { ev ->
                                Row(Modifier.fillMaxWidth().padding(vertical=2.5.dp),Arrangement.SpaceBetween) {
                                    Text("▶ ${ev.timeLabel}",fontFamily=FontFamily.Monospace,fontSize=9.sp,color=InkMid)
                                    val tier=when{ev.smoothedScore>.80f->"CRITICAL";ev.smoothedScore>.60f->"HIGH";else->"MED"}
                                    Text("${(ev.smoothedScore*100).toInt()}%  $tier",
                                        fontFamily=FontFamily.Monospace,fontSize=9.sp,
                                        color=if(ev.smoothedScore>.60f) Crimson else Amber)
                                }
                            }
                        }
                    }
                }

                // ── CONTROLS ──────────────────────────────────────────────
                Spacer(Modifier.height(10.dp))
                if (!scanning&&!analysing) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                        Btn("> LIVE",Phosphor,PhosphorDim,Modifier.weight(1f)){goLive()}
                        Btn("> DEMO",Arctic,  ArcticDim,  Modifier.weight(1f)){goDemo()}
                        Btn("↑ FILE",Amber,   AmberDim,   Modifier.weight(1f)){goFile()}
                    }
                } else if (scanning) {
                    Btn("■  TERMINATE",Crimson,CrimsonDim,Modifier.fillMaxWidth()){terminate()}
                }
                if (!scanning&&engine.currentSession!=null) {
                    Spacer(Modifier.height(5.dp))
                    Btn("↓  EXPORT SIGNED FORENSIC PDF",InkBright,AbyssRim,Modifier.fillMaxWidth()) {
                        ReportGenerator(this@MainActivity).generateAndShareSessionReport(
                            engine.currentSession, lastSig, lastPub,
                            sMode.value.name, sFileName.value,
                            sStartMs.longValue, sElapsedMs.longValue,
                            history, sF0History.value
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            DeepfakeAlertOverlay(sEma.floatValue,sAttrib.value,alertOn){sAlert.value=false}
        }
    }

    @Composable
    private fun Btn(label:String,col:Color,bg:Color,modifier:Modifier,onClick:()->Unit) {
        Box(modifier.height(46.dp).background(bg,RoundedCornerShape(3.dp))
            .border(.5.dp,col.copy(.45f),RoundedCornerShape(3.dp))
            .clickable{onClick()},Alignment.Center) {
            Text(label,fontFamily=FontFamily.Monospace,fontSize=10.sp,
                fontWeight=FontWeight.Bold,color=col,letterSpacing=1.sp,textAlign=TextAlign.Center)
        }
    }

    @Composable
    private fun TinyL(text:String,color:Color) {
        Text(text,fontFamily=FontFamily.Monospace,fontSize=7.sp,color=color,letterSpacing=1.2.sp)
    }
}