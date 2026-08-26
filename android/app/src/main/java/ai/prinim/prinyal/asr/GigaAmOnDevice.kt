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
 * GigaAM-RNNT v3 e2e на устройстве (ONNX, int8-MatMul).
 *
 * Аудио не покидает телефон вообще: наружу уходит только текст, и только на стадию
 * разбора. Это сильнее исходного контура PRD §7, где звук шёл на свой сервер.
 *
 * До 1.3 здесь стояла v2 с **посимвольным русским алфавитом**: пробел и 32 буквы,
 * захардкоженные списком. Латинской буквы в нём не было физически, поэтому
 * английские термины она записывала на слух кириллицей — «джоп дискрипшен»,
 * «дета сета», «мэджик девять про макс». Половина рабочих заметок владельца
 * состоит ровно из таких слов, и починить это после распознавания значило
 * угадывать, что человек имел в виду.
 *
 * У v3 словарь на 1025 BPE-кусков, среди них 84 латинских, точка, запятая и
 * вопросительный знак. Она пишет «job description» и «memory usage» сама, а
 * заодно расставляет знаки и заглавные, которых раньше не было вовсе.
 *
 * Граф — экспорт официального пакета, сигнатуры зафиксированы:
 *  - encoder: `audio_signal [B, 64, T]`, `length [B] int64` → `encoded [B, 768, T']`,
 *    `encoded_len [B] int32`;
 *  - decoder: `x [B, 1] int64`, `h.1/c.1 [1, B, 320]` → `dec [B, 1, 320]`, `h/c`;
 *  - joint: `enc [B, 768, 1]`, `dec [B, 320, 1]` → `joint [B, 1, 1, 1025]`.
 *
 * Веса переквантованы нами (`scripts/quantize_v3.py`), а не взяты вендорские:
 * те квантуют ещё и свёртки в `ConvInteger`, которого рантайм не умеет.
 */
class GigaAmOnDevice(private val models: File) : Closeable, SpeechToText {

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    private var encoder: OrtSession? = null
    private var decoder: OrtSession? = null
    private var joint: OrtSession? = null

    /** Словарь и blank читаются из файла рядом с весами — вместе они и версия модели. */
    private var labels: Array<String> = emptyArray()
    private var blank: Int = -1

    val isReady: Boolean get() = encoder != null

    fun load() {
        if (encoder != null) return
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(THREADS)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        labels = readVocab(file(VOCAB))
        blank = labels.indexOf(BLANK_TOKEN)
        require(blank >= 0) { "в словаре нет $BLANK_TOKEN" }
        encoder = env.createSession(file(ENCODER).absolutePath, options)
        decoder = env.createSession(file(DECODER).absolutePath, options)
        joint = env.createSession(file(JOINT).absolutePath, options)
        Log.i(TAG, "GigaAM загружен из ${models.absolutePath}: ${labels.size} кусков, blank $blank")
    }

    /**
     * @param samples моно PCM 16 кГц в диапазоне [-1, 1]
     * @return транскрипт с пунктуацией и заглавными, английские термины латиницей
     */
    override fun transcribe(samples: FloatArray): String {
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
        var previous = blank.toLong()

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
                if (token == blank) break

                text.append(labels[token])
                previous = token.toLong()
                h = pendingH
                c = pendingC
                decoderOut = runDecoder(dec, previous, h, c, hidden)
                pendingH = decoderOut.h
                pendingC = decoderOut.c
                emitted++
            }
        }
        return join(text)
    }

    /**
     * Куски → текст. Метка начала слова становится пробелом.
     *
     * Первый кусок фразы тоже приходит с меткой, поэтому строка начинается с
     * пробела — его снимаем, а не оставляем на совести вызывающего.
     */
    private fun join(pieces: CharSequence): String =
        pieces.toString().replace(WORD_START, ' ').trim()

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
                    dec.run(mapOf("x" to x, STATE_H to hi, STATE_C to ci)).use { result ->
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

        /**
         * Имена входов состояния у v3 — `h.1` и `c.1`, а не `hi`/`ci`.
         *
         * Точка с единицей досталась от экспорта torch: так он назвал аргументы
         * LSTM. Имя выглядит как опечатка, поэтому держим его константой с
         * пояснением, чтобы никто не «поправил» его обратно.
         */
        private const val STATE_H = "h.1"
        private const val STATE_C = "c.1"

        private const val ENCODER_DIM = 768
        private const val STATE = 320L
        private const val THREADS = 4
        /** `max_tokens_per_step` из config.json модели. */
        private const val MAX_SYMBOLS_PER_FRAME = 3

        const val VOCAB = "vocab.txt"

        /**
         * Куски склеиваются в слова по метке SentencePiece.
         *
         * `▁` — не подчёркивание, а признак начала слова: «▁job» + «▁desc» +
         * «ription» это «job description», а не «jobdescription». Без замены
         * транскрипт слипся бы в одну строку без пробелов.
         */
        const val WORD_START = '\u2581'

        /** Blank в словаре назван явно; полагаться на «последний индекс» не станем. */
        const val BLANK_TOKEN = "<blk>"

        /**
         * Словарь из файла: строка «кусок индекс», индексы подряд от нуля.
         *
         * Читаем с конца строки: сам кусок может содержать пробел, а индекс —
         * нет, поэтому делим по последнему пробелу, а не по первому.
         */
        fun readVocab(file: File): Array<String> {
            val pieces = HashMap<Int, String>()
            file.forEachLine { line ->
                if (line.isEmpty()) return@forEachLine
                val cut = line.lastIndexOf(' ')
                if (cut <= 0) return@forEachLine
                val index = line.substring(cut + 1).toIntOrNull() ?: return@forEachLine
                pieces[index] = line.substring(0, cut)
            }
            val size = (pieces.keys.maxOrNull() ?: -1) + 1
            return Array(size) { pieces[it] ?: "" }
        }

        fun modelsPresent(dir: File): Boolean =
            listOf(ENCODER, DECODER, JOINT, VOCAB).all { File(dir, it).length() > 0 }
    }
}
