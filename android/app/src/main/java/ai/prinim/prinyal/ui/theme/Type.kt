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
 * Типографика полишинга 1.3 (ТЗ §3).
 *
 * Три роли, и смешивать их нельзя (приёмка §6): контент — [Content],
 * служебное — [JetBrainsMono], голос продукта — [VoiceFamily] курсивом.
 *
 * Гарнитуры. ТЗ называет Manrope для контента и Literata Italic для голоса; в
 * сборке лежат Golos Text и Spectral — обе с полной кириллицей и той же ролью
 * (грот без засечек / серифный курсив). Подменяются они здесь, двумя
 * значениями, поэтому смена гарнитуры — правка этого файла и добавление ttf,
 * а не обход по экранам.
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
        FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500)),
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
    Font(
        R.font.golos_text,
        FontWeight.ExtraBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(800)),
    ),
)

val JetBrainsMono = FontFamily(
    Font(
        R.font.jetbrains_mono,
        FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400)),
    ),
    Font(
        R.font.jetbrains_mono,
        FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500)),
    ),
)

/** Роль «контент» — на месте Manrope из ТЗ. */
val Content = GolosText

/** Роль «голос продукта» — на месте Literata Italic из ТЗ. */
val VoiceFamily = Spectral

// Компоновка строки без «прижатого» первого ряда — иначе крупный заголовок
// визуально съезжает вверх в своей коробке.
private val EvenLines = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

@Immutable
data class PrinyalTypography(
    /** Квитанция «Принял.» — единственное место этого кегля. */
    val display: TextStyle,
    /** Заголовок экрана: «Настройки», «Люди», имя человека. */
    val screenTitle: TextStyle,
    /** Активный таб. */
    val tabActive: TextStyle,
    /** Неактивный таб. */
    val tabInactive: TextStyle,
    /** Строка списка: раздел, человек, настройка. */
    val listRow: TextStyle,
    /** Заголовок записи в ленте и на карточке. */
    val noteTitle: TextStyle,
    /** Текст пункта. */
    val itemTitle: TextStyle,
    /** Текст дня, расшифровка, длинные абзацы. */
    val body: TextStyle,
    /** Голос продукта: вопросы, пояснения, пустые состояния. Только курсив. */
    val voice: TextStyle,
    /** Голос продукта мелко — пояснение под строкой настройки. */
    val voiceSmall: TextStyle,
    /** Текст кнопки. */
    val label: TextStyle,
    /** Подпись под клавишей записи — первая строка пары. */
    val hint: TextStyle,
    /** Заголовок экрана отказа в микрофоне: 19 SemiBold (ТЗ §5, экран 22). */
    val micTitle: TextStyle,
    /** Вторая строка пары: тише и мельче. */
    val hintSecondary: TextStyle,
    /** Время, счётчики, даты, метки возврата, глоссарий, дев-панель. Только моно. */
    val meta: TextStyle,
    /** Самое мелкое служебное — 12, ниже не спускаемся. */
    val metaSmall: TextStyle,
    /** Заголовок группы: моно 12 капсом с трекингом. */
    val groupLabel: TextStyle,
    /** Вердикт «Недели» — «Петля буксует» одним словом. */
    val verdict: TextStyle,
    /** Значение kill-метрики. */
    val metric: TextStyle,
    /** Таймер записи — единственное место, где время есть содержание экрана. */
    val timer: TextStyle,
)

val PrinyalType = PrinyalTypography(
    display = TextStyle(
        fontFamily = VoiceFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 36.sp,
        letterSpacing = (-0.02).em,
        lineHeight = 1.12.em,
        lineHeightStyle = EvenLines,
    ),
    screenTitle = TextStyle(
        fontFamily = Content,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 28.sp,
        lineHeight = 1.15.em,
        lineHeightStyle = EvenLines,
    ),
    tabActive = TextStyle(
        fontFamily = Content,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 20.sp,
        lineHeight = 1.em,
        lineHeightStyle = EvenLines,
    ),
    tabInactive = TextStyle(
        fontFamily = Content,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 1.em,
        lineHeightStyle = EvenLines,
    ),
    listRow = TextStyle(
        fontFamily = Content,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 1.2.em,
        lineHeightStyle = EvenLines,
    ),
    noteTitle = TextStyle(
        fontFamily = Content,
        fontWeight = FontWeight.Bold,
        fontSize = 17.sp,
        lineHeight = 1.4.em,
        lineHeightStyle = EvenLines,
    ),
    itemTitle = TextStyle(
        fontFamily = Content,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 1.4.em,
        lineHeightStyle = EvenLines,
    ),
    body = TextStyle(
        fontFamily = Content,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 1.5.em,
        lineHeightStyle = EvenLines,
    ),
    voice = TextStyle(
        fontFamily = VoiceFamily,
        fontWeight = FontWeight.Normal,
        fontStyle = FontStyle.Italic,
        fontSize = 17.sp,
        lineHeight = 1.5.em,
        lineHeightStyle = EvenLines,
    ),
    voiceSmall = TextStyle(
        fontFamily = VoiceFamily,
        fontWeight = FontWeight.Normal,
        fontStyle = FontStyle.Italic,
        fontSize = 14.sp,
        lineHeight = 1.45.em,
        lineHeightStyle = EvenLines,
    ),
    label = TextStyle(
        fontFamily = Content,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        lineHeight = 1.2.em,
        lineHeightStyle = EvenLines,
    ),
    hint = TextStyle(
        fontFamily = Content,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 1.4.em,
        lineHeightStyle = EvenLines,
    ),
    micTitle = TextStyle(
        fontFamily = Content,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 1.45.em,
        lineHeightStyle = EvenLines,
    ),
    hintSecondary = TextStyle(
        fontFamily = Content,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 1.4.em,
        lineHeightStyle = EvenLines,
    ),
    meta = TextStyle(
        fontFamily = JetBrainsMono,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = 0.02.em,
        lineHeight = 1.3.em,
        lineHeightStyle = EvenLines,
    ),
    metaSmall = TextStyle(
        fontFamily = JetBrainsMono,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.02.em,
        lineHeight = 1.3.em,
        lineHeightStyle = EvenLines,
    ),
    groupLabel = TextStyle(
        fontFamily = JetBrainsMono,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.1.em,
        lineHeight = 1.em,
        lineHeightStyle = EvenLines,
    ),
    verdict = TextStyle(
        fontFamily = Content,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 28.sp,
        lineHeight = 1.15.em,
        lineHeightStyle = EvenLines,
    ),
    metric = TextStyle(
        fontFamily = Content,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 1.2.em,
        lineHeightStyle = EvenLines,
    ),
    timer = TextStyle(
        fontFamily = JetBrainsMono,
        fontWeight = FontWeight.Medium,
        fontSize = 40.sp,
        letterSpacing = 0.04.em,
        lineHeight = 1.em,
        lineHeightStyle = EvenLines,
    ),
)
