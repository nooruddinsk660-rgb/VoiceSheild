package com.unstop.aivoicedetector

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) return

        // Stop any previous dispatcher before starting a new one.
        // Without this, tapping Demo right after Live leaves two capture threads alive.
        stopCapture()

        dispatcher = AudioDispatcherFactory.fromDefaultMicrophone(sampleRate, bufferSize, overlap)

        val audioProcessor = object : AudioProcessor {
            override fun process(audioEvent: AudioEvent): Boolean {
                // Copy the buffer to prevent data corruption during asynchronous processing
                val snapshot = audioEvent.floatBuffer.copyOf()
                onAudioData(snapshot)
                return true
            }

            override fun processingFinished() {}
        }

        dispatcher?.addAudioProcessor(audioProcessor)

        // Run the dispatcher on a background thread
        Thread(dispatcher, "Audio-Capture-Thread").start()
    }

    fun stopCapture() {
        dispatcher?.stop()
        dispatcher = null
    }
}
