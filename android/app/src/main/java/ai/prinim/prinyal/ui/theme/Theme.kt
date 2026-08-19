package ai.prinim.prinyal.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Тема продукта. Material 3 взят как основа, но характер — свой (ТЗ UI §5):
 * динамический цвет системы сознательно не используется, иначе семантика акцента
 * «это он мне принёс» разъезжается от обоев пользователя.
 */

val LocalPrinyalColors = staticCompositionLocalOf { LightColors }
val LocalPrinyalType = staticCompositionLocalOf { PrinyalType }

object Prinyal {
    val colors: PrinyalColors
        @Composable @ReadOnlyComposable get() = LocalPrinyalColors.current

    val type: PrinyalTypography
        @Composable @ReadOnlyComposable get() = LocalPrinyalType.current

    /** Пластик клавиши — свой на каждую тему (аудит Д-7, п. 10). */
    val key: KeyPalette
        @Composable @ReadOnlyComposable get() = LocalKeyColors.current
}

val LocalKeyColors = staticCompositionLocalOf { DarkKeyColors }

@Composable
fun PrinyalTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (dark) DarkColors else LightColors
    val keys = if (dark) DarkKeyColors else LightKeyColors

    // Material-схема нужна ripple, скроллбарам и системным компонентам. Заполняем её
    // своими цветами, чтобы ни один экран не мог случайно показать чужой синий.
    val material = if (dark) {
        darkColorScheme(
            primary = colors.accentSelf,
            onPrimary = colors.paper,
            background = colors.paper,
            onBackground = colors.ink,
            surface = colors.surface,
            onSurface = colors.ink,
            surfaceVariant = colors.wellSurface,
            onSurfaceVariant = colors.inkMuted,
            outline = colors.rule,
            error = colors.record,
            onError = Color.White,
        )
    } else {
        lightColorScheme(
            primary = colors.accentSelf,
            onPrimary = colors.surface,
            background = colors.paper,
            onBackground = colors.ink,
            surface = colors.surface,
            onSurface = colors.ink,
            surfaceVariant = colors.wellSurface,
            onSurfaceVariant = colors.inkMuted,
            outline = colors.rule,
            error = colors.record,
            onError = Color.White,
        )
    }

    CompositionLocalProvider(
        LocalPrinyalColors provides colors,
        LocalKeyColors provides keys,
        LocalPrinyalType provides PrinyalType,
        LocalTextStyle provides PrinyalType.body.copy(color = colors.ink),
    ) {
        MaterialTheme(colorScheme = material, content = content)
    }
}

/** Реплика продукта: курсив Spectral в акцентном цвете «сделал сам». */
@Composable
fun VoiceText(text: String, modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
    Text(
        text = text,
        style = Prinyal.type.voice,
        color = Prinyal.colors.accentSelf,
        modifier = modifier,
    )
}

/** Служебная строка: время, статус, причина в мета-регистре. */
@Composable
fun MetaText(
    text: String,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    color: Color? = null,
    /**
     * Ограничение строк. Служебные подписи чаще всего и оказываются той
     * стороной ряда, которую сжимают, — а без предела текст в `Row` не
     * переносится, он наезжает на соседа.
     */
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = text,
        style = Prinyal.type.meta,
        color = color ?: Prinyal.colors.inkFaint,
        maxLines = maxLines,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
