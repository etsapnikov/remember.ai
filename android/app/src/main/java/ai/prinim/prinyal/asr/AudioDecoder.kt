package ai.prinim.prinyal.asr

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Декодирование записанного AAC/M4A в моно PCM 16 кГц — вход для [MelFeatures].
 *
 * Раньше это делал ffmpeg на сервере; теперь всё происходит на телефоне, поэтому
 * работаем системным кодеком.
 */
object AudioDecoder {

    /** @return сэмплы в диапазоне [-1, 1] на частоте [MelFeatures.SAMPLE_RATE] */
    fun decode(file: File): FloatArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)

        val track = (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                ?.startsWith("audio/") == true
        } ?: run {
            extractor.release()
            return FloatArray(0)
        }

        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val sourceRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val pcm = ArrayList<Short>(sourceRate * 8)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        try {
            while (!outputDone) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIndex >= 0) {
                    val buffer = codec.getOutputBuffer(outIndex)!!
                    val shorts = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                    while (shorts.hasRemaining()) pcm.add(shorts.get())
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            extractor.release()
        }

        val mono = toMono(pcm, channels)
        return resample(mono, sourceRate, MelFeatures.SAMPLE_RATE)
    }

    private fun toMono(samples: List<Short>, channels: Int): FloatArray {
        if (channels <= 1) return FloatArray(samples.size) { samples[it] / 32768f }
        val frames = samples.size / channels
        return FloatArray(frames) { frame ->
            var acc = 0f
            for (ch in 0 until channels) acc += samples[frame * channels + ch] / 32768f
            acc / channels
        }
    }

    /**
     * Линейная интерполяция. Телефон пишет сразу в 16 кГц (см. `Recorder`), так что
     * обычно это тождественное преобразование; ветка нужна на случай, когда прошивка
     * молча выдала другую частоту — тогда без ресемпла модель услышала бы кашу.
     */
    private fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || input.isEmpty()) return input
        val ratio = to.toDouble() / from
        val out = FloatArray((input.size * ratio).toInt())
        for (i in out.indices) {
            val position = i / ratio
            val left = position.toInt()
            val right = (left + 1).coerceAtMost(input.size - 1)
            val fraction = (position - left).toFloat()
            out[i] = input[left] * (1 - fraction) + input[right] * fraction
        }
        return out
    }

    private const val TIMEOUT_US = 10_000L
}
