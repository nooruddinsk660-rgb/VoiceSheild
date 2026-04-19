package com.unstop.aivoicedetector

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.app.ActivityCompat
import be.tarsos.dsp.AudioDispatcher
import be.tarsos.dsp.AudioEvent
import be.tarsos.dsp.AudioProcessor
import be.tarsos.dsp.io.android.AudioDispatcherFactory

class AudioCaptureHelper(private val context: Context) {

    private var dispatcher: AudioDispatcher? = null

    // Required for AASIST: 16kHz
    private val sampleRate = 16000
    // 2-second window at 16kHz
    private val bufferSize = 16000 * 2 
    // 0.5s overlap
    private val overlap = 16000 / 2 

    fun startCapture(onAudioData: (FloatArray) -> Unit) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        // TarsosDSP AudioDispatcher using Android microphone
        dispatcher = AudioDispatcherFactory.fromDefaultMicrophone(sampleRate, bufferSize, overlap)

        val audioProcessor = object : AudioProcessor {
            override fun process(audioEvent: AudioEvent): Boolean {
                val floatBuffer = audioEvent.floatBuffer
                // Send out 2s sliding window buffer of size 32000
                onAudioData(floatBuffer)
                return true
            }

            override fun processingFinished() {
                // Handle pipeline close
            }
        }

        dispatcher?.addAudioProcessor(audioProcessor)

        // Run the dispatcher on a background thread
        Thread(dispatcher, "Audio-Capture-Thread").start()
    }

    fun stopCapture() {
        dispatcher?.stop()
    }
}
