package com.unstop.aivoicedetector

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

data class DecodeResult(
    val fileName: String,
    val durationMs: Long,
    val sampleRate: Int,
    val windows: List<FloatArray>,   // 2s windows at 16kHz each
)

object AudioFileDecoder {

    private const val TARGET_SR = 16000
    private const val WINDOW    = TARGET_SR * 2   // 32000 samples = 2s
    private const val HOP       = TARGET_SR / 2   // 8000 = 0.5s overlap

    suspend fun decode(ctx: Context, uri: Uri, name: String): DecodeResult? =
        withContext(Dispatchers.IO) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(ctx, uri, null)
                val trackIdx = (0 until extractor.trackCount).firstOrNull { i ->
                    extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                        ?.startsWith("audio/") == true
                } ?: return@withContext null

                extractor.selectTrack(trackIdx)
                val fmt      = extractor.getTrackFormat(trackIdx)
                val mime     = fmt.getString(MediaFormat.KEY_MIME)!!
                val srcSR    = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val durUs    = if (fmt.containsKey(MediaFormat.KEY_DURATION))
                    fmt.getLong(MediaFormat.KEY_DURATION) else 0L

                val codec  = MediaCodec.createDecoderByType(mime)
                codec.configure(fmt, null, null, 0)
                codec.start()

                val pcm16k = mutableListOf<Float>()
                val info   = MediaCodec.BufferInfo()
                var eos    = false
                var srcArr = ShortArray(65536)
                var srcLen = 0

                fun appendShort(s: Short) {
                    if (srcLen >= srcArr.size) srcArr = srcArr.copyOf(srcArr.size * 2)
                    srcArr[srcLen++] = s
                }

                while (!eos) {
                    // feed input
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf  = codec.getInputBuffer(inIdx)!!
                        val read = extractor.readSampleData(buf, 0)
                        if (read < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eos = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, read, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                    // drain output
                    var outIdx = codec.dequeueOutputBuffer(info, 10_000)
                    while (outIdx >= 0) {
                        val outBuf = codec.getOutputBuffer(outIdx)!!
                        outBuf.order(ByteOrder.LITTLE_ENDIAN)
                        val shorts = ShortArray(info.size / 2)
                        outBuf.asShortBuffer().get(shorts)
                        // downmix channels → mono
                        var i = 0
                        while (i < shorts.size) {
                            var mono = 0L
                            repeat(channels) { c -> mono += shorts.getOrElse(i + c) { 0 } }
                            appendShort((mono / channels).toShort())
                            i += channels
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        outIdx = codec.dequeueOutputBuffer(info, 0)
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
                codec.stop(); codec.release(); extractor.release()

                // resample srcSR → 16kHz via linear interpolation
                if (srcLen == 0) return@withContext null
                val ratio   = srcSR.toDouble() / TARGET_SR
                val outLen  = (srcLen / ratio).toInt()
                val mono16k = FloatArray(outLen) { j ->
                    val pos   = j * ratio
                    val lo    = pos.toInt().coerceIn(0, srcLen - 1)
                    val hi    = (lo + 1).coerceIn(0, srcLen - 1)
                    val frac  = (pos - lo).toFloat()
                    val sLo   = srcArr[lo].toFloat() / 32768f
                    val sHi   = srcArr[hi].toFloat() / 32768f
                    sLo + frac * (sHi - sLo)
                }

                // normalise peak
                val peak = mono16k.maxOfOrNull { abs(it) }?.takeIf { it > 0f } ?: 1f
                if (peak < 0.9f) mono16k.forEachIndexed { i, v -> mono16k[i] = v / peak * 0.9f }

                // slice into overlapping 2s windows
                val windows = mutableListOf<FloatArray>()
                var start   = 0
                while (start + WINDOW <= mono16k.size) {
                    windows.add(mono16k.copyOfRange(start, start + WINDOW))
                    start += HOP
                }
                if (windows.isEmpty() && mono16k.size > 100) {
                    // short file — zero-pad to 2s
                    val padded = FloatArray(WINDOW)
                    mono16k.copyInto(padded, 0, 0, minOf(mono16k.size, WINDOW))
                    windows.add(padded)
                }

                DecodeResult(name, durUs / 1000L, srcSR, windows)
            } catch (e: Exception) {
                e.printStackTrace()
                extractor.release()
                null
            }
        }
}