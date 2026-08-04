package ai.prinim.prinyal.asr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * GigaAM-RNNT v2 на устройстве (ONNX, int8-MatMul).
 *
 * Аудио не покидает телефон вообще: наружу уходит только текст, и только на стадию
 * разбора. Это сильнее исходного контура PRD §7, где звук шёл на свой сервер.
 *
 * Граф — экспорт официального пакета, сигнатуры зафиксированы:
 *  - encoder: `audio_signal [B, 64, T]`, `length [B] int64` → `encoded [B, 768, T']`,
 *    `encoded_len [B] int32`;
 *  - decoder: `x [B, 1] int64`, `hi/ci [1, B, 320]` → `dec [B, 1, 320]`, `ho/co`;
 *  - joint: `enc [B, 768, 1]`, `dec [B, 320, 1]` → `joint [B, 1, 1, 34]`.
 *
 * Словарь символьный: 33 метки (пробел + 32 буквы), blank — индекс 33. Регистр
 * нижний, пунктуации нет — нормализацию делает стадия разбора.
 */
class GigaAmOnDevice(private val models: File) : Closeable {

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    private var encoder: OrtSession? = null
    private var decoder: OrtSession? = null
    private var joint: OrtSession? = null

    val isReady: Boolean get() = encoder != null

    fun load() {
        if (encoder != null) return
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(THREADS)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        encoder = env.createSession(file(ENCODER).absolutePath, options)
        decoder = env.createSession(file(DECODER).absolutePath, options)
        joint = env.createSession(file(JOINT).absolutePath, options)
        Log.i(TAG, "GigaAM загружен из ${models.absolutePath}")
    }

    /**
     * @param samples моно PCM 16 кГц в диапазоне [-1, 1]
     * @return транскрипт в нижнем регистре без пунктуации
     */
    fun transcribe(samples: FloatArray): String {
        load()
        val enc = encoder ?: return ""
        val dec = decoder ?: return ""
        val joi = joint ?: return ""

        val features = MelFeatures.logMel(samples)
        val frames = features[0].size
        if (frames == 0) return ""

        val flat = FloatArray(MelFeatures.N_MELS * frames)
        for (mel in 0 until MelFeatures.N_MELS) {
            System.arraycopy(features[mel], 0, flat, mel * frames, frames)
        }

        val encoded: Array<FloatArray>
        val encodedFrames: Int

        OnnxTensor.createTensor(
            env, FloatBuffer.wrap(flat), longArrayOf(1, MelFeatures.N_MELS.toLong(), frames.toLong())
        ).use { audio ->
            OnnxTensor.createTensor(
                env, LongBuffer.wrap(longArrayOf(frames.toLong())), longArrayOf(1)
            ).use { length ->
                enc.run(mapOf("audio_signal" to audio, "length" to length)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val out = result.get(0).value as Array<Array<FloatArray>>
                    encoded = out[0]  // [768][T']
                    val lens = result.get(1).value as IntArray
                    encodedFrames = minOf(lens[0], encoded[0].size)
                }
            }
        }

        return decodeGreedy(dec, joi, encoded, encodedFrames)
    }

    /**
     * Жадный RNNT-декод. На каждом кадре энкодера тянем символы, пока не выпадет
     * blank или не упрёмся в потолок символов на кадр — без потолка модель на
     * «залипшем» состоянии уходит в бесконечный цикл.
     */
    private fun decodeGreedy(
        dec: OrtSession,
        joi: OrtSession,
        encoded: Array<FloatArray>,
        frames: Int,
    ): String {
        val hidden = longArrayOf(1, 1, STATE)
        var h = FloatArray(STATE.toInt())
        var c = FloatArray(STATE.toInt())
        var previous = BLANK.toLong()

        val text = StringBuilder()
        val frameBuffer = FloatArray(ENCODER_DIM)

        // Первый прогон декодера от blank задаёт стартовое состояние.
        var decoderOut = runDecoder(dec, previous, h, c, hidden)
        var pendingH = decoderOut.h
        var pendingC = decoderOut.c

        for (t in 0 until frames) {
            for (mel in 0 until ENCODER_DIM) {
                frameBuffer[mel] = encoded[mel][t]
            }

            var emitted = 0
            while (emitted < MAX_SYMBOLS_PER_FRAME) {
                val token = runJoint(joi, frameBuffer, decoderOut.state)
                if (token == BLANK) break

                text.append(LABELS[token])
                previous = token.toLong()
                h = pendingH
                c = pendingC
                decoderOut = runDecoder(dec, previous, h, c, hidden)
                pendingH = decoderOut.h
                pendingC = decoderOut.c
                emitted++
            }
        }
        return text.toString().trim()
    }

    private class DecoderOut(val state: FloatArray, val h: FloatArray, val c: FloatArray)

    private fun runDecoder(
        dec: OrtSession,
        token: Long,
        h: FloatArray,
        c: FloatArray,
        hiddenShape: LongArray,
    ): DecoderOut {
        OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(token)), longArrayOf(1, 1)).use { x ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(h), hiddenShape).use { hi ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(c), hiddenShape).use { ci ->
                    dec.run(mapOf("x" to x, "hi" to hi, "ci" to ci)).use { result ->
                        @Suppress("UNCHECKED_CAST")
                        val out = result.get(0).value as Array<Array<FloatArray>>
                        @Suppress("UNCHECKED_CAST")
                        val ho = result.get(1).value as Array<Array<FloatArray>>
                        @Suppress("UNCHECKED_CAST")
                        val co = result.get(2).value as Array<Array<FloatArray>>
                        return DecoderOut(out[0][0].copyOf(), ho[0][0].copyOf(), co[0][0].copyOf())
                    }
                }
            }
        }
    }

    private fun runJoint(joi: OrtSession, frame: FloatArray, decState: FloatArray): Int {
        OnnxTensor.createTensor(
            env, FloatBuffer.wrap(frame), longArrayOf(1, ENCODER_DIM.toLong(), 1)
        ).use { enc ->
            OnnxTensor.createTensor(
                env, FloatBuffer.wrap(decState), longArrayOf(1, STATE, 1)
            ).use { dec ->
                joi.run(mapOf("enc" to enc, "dec" to dec)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val logits = result.get(0).value as Array<Array<Array<FloatArray>>>
                    val scores = logits[0][0][0]
                    var best = 0
                    for (i in scores.indices) if (scores[i] > scores[best]) best = i
                    return best
                }
            }
        }
    }

    private fun file(name: String) = File(models, name)

    override fun close() {
        runCatching { encoder?.close() }
        runCatching { decoder?.close() }
        runCatching { joint?.close() }
        encoder = null
        decoder = null
        joint = null
    }

    companion object {
        private const val TAG = "PrinyalAsr"

        const val ENCODER = "encoder.onnx"
        const val DECODER = "decoder.onnx"
        const val JOINT = "joint.onnx"

        private const val ENCODER_DIM = 768
        private const val STATE = 320L
        private const val THREADS = 4
        private const val MAX_SYMBOLS_PER_FRAME = 10

        /** Словарь из `v2_rnnt.yaml`: пробел плюс 32 буквы; blank — следом за ними. */
        val LABELS = arrayOf(
            " ", "а", "б", "в", "г", "д", "е", "ж", "з", "и", "й", "к", "л", "м", "н",
            "о", "п", "р", "с", "т", "у", "ф", "х", "ц", "ч", "ш", "щ", "ъ", "ы", "ь",
            "э", "ю", "я",
        )
        const val BLANK = 33

        fun modelsPresent(dir: File): Boolean =
            listOf(ENCODER, DECODER, JOINT).all { File(dir, it).length() > 0 }
    }
}
