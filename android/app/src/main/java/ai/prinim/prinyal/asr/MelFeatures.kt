package ai.prinim.prinyal.asr

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

/**
 * Log-mel признаки для GigaAM, один в один с обучающим препроцессингом
 * (`gigaam.preprocess.FeatureExtractor`, конфиг `v2_rnnt.yaml`).
 *
 * Расхождение здесь не роняет распознавание, а тихо портит его: модель выдаёт
 * похожий на речь, но неверный текст. Поэтому параметры не «примерно как в
 * torchaudio», а буквально её дефолты, и результат сверяется с золотым образцом
 * из Python в `MelFeaturesTest`.
 *
 * Соответствие torchaudio.transforms.MelSpectrogram:
 *  - n_fft = win_length = 400, hop = 160, center = true, padding = reflect;
 *  - окно Ханна периодическое (не симметричное);
 *  - power = 2.0 (мощность, не амплитуда);
 *  - шкала мел HTK, без slaney-нормализации (torchaudio: norm=None, mel_scale="htk");
 *  - затем натуральный логарифм с clamp(1e-9, 1e9).
 */
object MelFeatures {

    const val SAMPLE_RATE = 16_000
    const val N_FFT = 400
    const val HOP = 160
    const val N_MELS = 64
    private const val CLAMP_MIN = 1e-9

    private val window: DoubleArray = hannPeriodic(N_FFT)
    private val filterbank: Array<DoubleArray> = melFilterbank()

    // n_fft = 400 — не степень двойки. Дополнение нулями до 512 здесь недопустимо:
    // оно меняет шаг бинов с 40 Гц на 31.25 Гц, и мел-фильтры начинают читать
    // чужие частоты. Поэтому честное ДПФ на 400 точек через алгоритм Блуштейна.
    private val dft = Bluestein(N_FFT)

    /**
     * @param samples моно PCM в диапазоне [-1, 1]
     * @return матрица [N_MELS][frames] — та же раскладка, что ждёт вход энкодера
     *   `audio_signal [batch, 64, seq_len]`
     */
    fun logMel(samples: FloatArray): Array<FloatArray> {
        // center = true: сигнал дополняется отражением на половину окна с каждой
        // стороны, поэтому первый кадр центрирован на нулевом отсчёте.
        val pad = N_FFT / 2
        val padded = reflectPad(samples, pad)
        val frames = 1 + (padded.size - N_FFT) / HOP

        val out = Array(N_MELS) { FloatArray(frames) }
        val bins = N_FFT / 2 + 1
        val windowed = DoubleArray(N_FFT)
        val power = DoubleArray(bins)

        for (frame in 0 until frames) {
            val offset = frame * HOP
            for (i in 0 until N_FFT) {
                windowed[i] = padded[offset + i].toDouble() * window[i]
            }
            dft.powerSpectrum(windowed, power)

            for (mel in 0 until N_MELS) {
                val weights = filterbank[mel]
                var acc = 0.0
                for (bin in 0 until bins) {
                    val w = weights[bin]
                    if (w != 0.0) acc += w * power[bin]
                }
                out[mel][frame] = ln(if (acc < CLAMP_MIN) CLAMP_MIN else acc).toFloat()
            }
        }
        return out
    }

    /** torch.hann_window по умолчанию периодическое: делитель N, а не N-1. */
    private fun hannPeriodic(size: Int): DoubleArray =
        DoubleArray(size) { 0.5 - 0.5 * cos(2.0 * PI * it / size) }

    private fun reflectPad(input: FloatArray, pad: Int): FloatArray {
        if (input.size <= 1) return FloatArray(input.size + 2 * pad)
        val out = FloatArray(input.size + 2 * pad)
        System.arraycopy(input, 0, out, pad, input.size)
        for (i in 1..pad) {
            out[pad - i] = input[i.coerceAtMost(input.size - 1)]
            out[pad + input.size - 1 + i] = input[(input.size - 1 - i).coerceAtLeast(0)]
        }
        return out
    }

    // --- мел-шкала HTK ---

    private fun hzToMel(hz: Double): Double = 2595.0 * kotlin.math.log10(1.0 + hz / 700.0)

    private fun melToHz(mel: Double): Double = 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0)

    /**
     * Треугольные фильтры как в torchaudio (norm = None): без деления на ширину
     * полосы. Slaney-нормализация дала бы другой масштаб и другой лог.
     */
    private fun melFilterbank(): Array<DoubleArray> {
        val bins = N_FFT / 2 + 1
        val fMin = 0.0
        val fMax = SAMPLE_RATE / 2.0

        val melMin = hzToMel(fMin)
        val melMax = hzToMel(fMax)
        val points = DoubleArray(N_MELS + 2) { melToHz(melMin + (melMax - melMin) * it / (N_MELS + 1)) }

        val freqs = DoubleArray(bins) { it * SAMPLE_RATE.toDouble() / N_FFT }

        return Array(N_MELS) { mel ->
            val left = points[mel]
            val center = points[mel + 1]
            val right = points[mel + 2]
            DoubleArray(bins) { bin ->
                val f = freqs[bin]
                val rising = (f - left) / (center - left)
                val falling = (right - f) / (right - center)
                val value = minOf(rising, falling)
                if (value > 0.0) value else 0.0
            }
        }
    }

}

/**
 * ДПФ произвольной длины через алгоритм Блуштейна (chirp-z).
 *
 * Свёртка считается БПФ по степени двойки, поэтому 400 точек обходятся втрое
 * дешевле прямого ДПФ — на 90-секундном клипе это 9000 кадров, разница заметна.
 */
internal class Bluestein(private val n: Int) {

    private val convSize = nextPowerOfTwo(2 * n - 1)
    private val fft = Fft(convSize)

    // a[k] = e^{-iπk²/n} — «чирп», которым модулируется вход и выход.
    private val chirpRe = DoubleArray(n)
    private val chirpIm = DoubleArray(n)

    // Ядро свёртки b[k] = e^{+iπk²/n}, уже переведённое в частотную область.
    private val kernelRe = DoubleArray(convSize)
    private val kernelIm = DoubleArray(convSize)

    private val workRe = DoubleArray(convSize)
    private val workIm = DoubleArray(convSize)

    init {
        for (k in 0 until n) {
            // k² mod 2n — иначе k² при больших k теряет точность в double.
            val angle = PI * ((k.toLong() * k) % (2L * n)) / n
            chirpRe[k] = cos(angle)
            chirpIm[k] = -sin(angle)
        }

        for (k in 0 until n) {
            kernelRe[k] = chirpRe[k]
            kernelIm[k] = -chirpIm[k]
            if (k > 0) {
                // Ядро симметрично: b[-k] = b[k], хвост массива — отрицательные индексы.
                kernelRe[convSize - k] = chirpRe[k]
                kernelIm[convSize - k] = -chirpIm[k]
            }
        }
        fft.transform(kernelRe, kernelIm)
    }

    /** Заполняет [power] мощностями первых n/2+1 бинов. */
    fun powerSpectrum(input: DoubleArray, power: DoubleArray) {
        java.util.Arrays.fill(workRe, 0.0)
        java.util.Arrays.fill(workIm, 0.0)

        for (k in 0 until n) {
            workRe[k] = input[k] * chirpRe[k]
            workIm[k] = input[k] * chirpIm[k]
        }

        fft.transform(workRe, workIm)

        for (k in 0 until convSize) {
            val re = workRe[k] * kernelRe[k] - workIm[k] * kernelIm[k]
            val im = workRe[k] * kernelIm[k] + workIm[k] * kernelRe[k]
            workRe[k] = re
            workIm[k] = im
        }

        fft.inverse(workRe, workIm)

        for (k in power.indices) {
            val re = workRe[k] * chirpRe[k] - workIm[k] * chirpIm[k]
            val im = workRe[k] * chirpIm[k] + workIm[k] * chirpRe[k]
            power[k] = re * re + im * im
        }
    }

    private fun nextPowerOfTwo(value: Int): Int {
        var size = 1
        while (size < value) size = size shl 1
        return size
    }
}

/** Итеративный radix-2 БПФ. Аллокаций в горячем цикле нет — буферы переиспользуются. */
internal class Fft(val size: Int) {

    private val cosTable = DoubleArray(size / 2) { cos(2.0 * PI * it / size) }
    private val sinTable = DoubleArray(size / 2) { sin(2.0 * PI * it / size) }

    fun transform(re: DoubleArray, im: DoubleArray) {
        val n = size
        var shift = 1
        while (n shr shift != 1) shift++

        for (i in 0 until n) {
            val j = Integer.reverse(i) ushr (32 - shift)
            if (j > i) {
                var tmp = re[i]; re[i] = re[j]; re[j] = tmp
                tmp = im[i]; im[i] = im[j]; im[j] = tmp
            }
        }

        var half = 1
        while (half < n) {
            val step = n / (half * 2)
            for (start in 0 until n step half * 2) {
                var k = 0
                for (i in start until start + half) {
                    val partner = i + half
                    val wr = cosTable[k]
                    val wi = -sinTable[k]
                    val tr = re[partner] * wr - im[partner] * wi
                    val ti = re[partner] * wi + im[partner] * wr
                    re[partner] = re[i] - tr
                    im[partner] = im[i] - ti
                    re[i] += tr
                    im[i] += ti
                    k += step
                }
            }
            half *= 2
        }
    }

    /** Обратное преобразование через сопряжение: своя ветка кода не нужна. */
    fun inverse(re: DoubleArray, im: DoubleArray) {
        for (i in re.indices) im[i] = -im[i]
        transform(re, im)
        val scale = 1.0 / size
        for (i in re.indices) {
            re[i] *= scale
            im[i] *= -scale
        }
    }
}
