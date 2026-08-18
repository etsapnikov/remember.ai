@file:OptIn(ExperimentalTextApi::class)

package ai.prinim.prinyal.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import ai.prinim.prinyal.R

/**
 * Типографика из `assets/design-tokens.json`, раздел `type`.
 *
 * Кириллица первична: шрифты выбраны с полным русским набором, кегли проверяются на
 * длинных словах («переоформить», «стоматология»). Минимальный кегль основного
 * текста — 16sp (ТЗ UI §2, п. 6), поэтому масштабирования вниз здесь нет.
 */

val Spectral = FontFamily(
    Font(R.font.spectral_regular, FontWeight.Normal),
    Font(R.font.spectral_italic, FontWeight.Normal, FontStyle.Italic),
)

// Golos Text приходит вариативным файлом: веса берём с оси wght, а не синтетическим
// утолщением — иначе 600 на дешёвом экране мажет кириллицу.
val GolosText = FontFamily(
    Font(
        R.font.golos_text,
        FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400)),
    ),
    Font(
        R.font.golos_text,
        FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600)),
    ),
    Font(
        R.font.golos_text,
        FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700)),
    ),
)

val JetBrainsMono = FontFamily(
    Font(
        R.font.jetbrains_mono,
        FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400)),
    ),
)

// Компоновка строки без «прижатого» первого ряда — иначе крупная квитанция
// visually съезжает вверх в своей коробке.
private val EvenLines = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

@Immutable
data class PrinyalTypography(
    /** Квитанция «Принял.» — единственное место этого кегля. */
    val display: TextStyle,
    /** Реплики продукта: план возврата, причина. Курсив — голос, не текст пользователя. */
    val voice: TextStyle,
    /** Текст айтема. */
    val itemTitle: TextStyle,
    val body: TextStyle,
    /** Кнопки. */
    val label: TextStyle,
    /** Время, статусы, служебное. */
    val meta: TextStyle,
    /** Вердикт «Недели» — «Петля жива» одним словом. */
    val verdict: TextStyle,
    /** Значение kill-метрики. */
    val metric: TextStyle,
    /**
     * Таймер записи. Расширение шкалы: это единственное место, где время —
     * не служебная подпись, а содержание экрана (ТЗ UI §3.2 требует крупную
     * живую индикацию записи). Семейство то же, что у meta, — время моноширинное,
     * иначе цифры скачут по ширине на каждой секунде.
     */
    val timer: TextStyle,
)

val PrinyalType = PrinyalTypography(
    display = TextStyle(
        fontFamily = Spectral,
        // SemiBold, а не Regular: при переходе на систему 1.0 гарнитура
        // сменилась и вес потерялся молча (аудит Д-7). Решение R1.2 §14 —
        // насыщенный, с трекингом −2%.
        fontWeight = FontWeight.SemiBold,
        // 34, не 38: «Запомнил.» на 38 занимало 178 dp и съедало поля на узком
        // экране. Трекинг −2% заодно возвращает точку в ритм после длинного
        // слова (R1.2 §14).
        fontSize = 34.sp,
        letterSpacing = (-0.02).em,
        lineHeight = 1.12.em,
        lineHeightStyle = EvenLines,
    ),
    voice = TextStyle(
        fontFamily = Spectral,
        fontWeight = FontWeight.Normal,
        fontStyle = FontStyle.Italic,
        fontSize = 18.sp,
        lineHeight = 1.45.em,
        lineHeightStyle = EvenLines,
    ),
    itemTitle = TextStyle(
        fontFamily = GolosText,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 1.3.em,
        lineHeightStyle = EvenLines,
    ),
    body = TextStyle(
        fontFamily = GolosText,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 1.5.em,
        lineHeightStyle = EvenLines,
    ),
    label = TextStyle(
        fontFamily = GolosText,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 1.3.em,
        lineHeightStyle = EvenLines,
    ),
    verdict = TextStyle(
        fontFamily = GolosText,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 1.13.em,
        lineHeightStyle = EvenLines,
    ),
    metric = TextStyle(
        fontFamily = GolosText,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 1.15.em,
        lineHeightStyle = EvenLines,
    ),
    timer = TextStyle(
        fontFamily = JetBrainsMono,
        fontWeight = FontWeight.Normal,
        // 22, не 30: крупным таймер был потому, что был единственным признаком
        // жизни. Теперь жизнь показывает кольцо (R1.2 §13).
        fontSize = 22.sp,
        letterSpacing = 0.04.em,
        lineHeight = 1.2.em,
        lineHeightStyle = EvenLines,
    ),
    meta = TextStyle(
        fontFamily = JetBrainsMono,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        letterSpacing = 0.02.em,
        lineHeight = 1.3.em,
        lineHeightStyle = EvenLines,
    ),
)
