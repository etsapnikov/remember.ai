package ai.prinim.prinyal.ui

import ai.prinim.prinyal.R
import ai.prinim.prinyal.domain.Markdown
import ai.prinim.prinyal.ui.theme.MetaText
import ai.prinim.prinyal.ui.theme.Prinyal
import ai.prinim.prinyal.ui.theme.Radius
import ai.prinim.prinyal.ui.theme.Space
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Блок «Собрано» — тело заметки-идеи в markdown (Д-4).
 *
 * Весь блок набран Spectral, включая заголовки: правило системы «Spectral —
 * реплики продукта» здесь работает буквально. «Собрано» и есть развёрнутая
 * реплика, просто длиной в экран; текст сочинён продуктом от первого слова до
 * последнего, а не сказан человеком.
 *
 * Побочная польза: пункты (Golos) и «Собрано» (Spectral) в одной карточке
 * различимы боковым зрением, без рамок и заливок.
 */
@Composable
fun MarkdownBody(raw: String, modifier: Modifier = Modifier) {
    val blocks = remember(raw) { Markdown.parse(Markdown.sanitize(raw)) }
    if (blocks.isEmpty()) return

    Column(
        modifier
            .fillMaxWidth()
            .background(Prinyal.colors.wellSurface, Radius.control)
            .padding(Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        MetaText(stringResource(R.string.note_collected), color = Prinyal.colors.accentSelf)

        blocks.forEach { block ->
            when (block) {
                is Markdown.Block.Heading -> Text(
                    text = inline(block.text),
                    style = Prinyal.type.voice.copy(
                        fontStyle = FontStyle.Normal,
                        fontWeight = FontWeight.Medium,
                        fontSize = if (block.level == 2) 22.sp else 18.sp,
                    ),
                    color = Prinyal.colors.ink,
                    modifier = Modifier.padding(top = Space.s),
                )

                is Markdown.Block.Paragraph -> Text(
                    text = inline(block.text),
                    style = Prinyal.type.voice.copy(fontStyle = FontStyle.Normal),
                    color = Prinyal.colors.ink,
                )

                // Маркер — тире, не точка: галочки и буллеты продукту запрещены,
                // а тире читается как речь.
                is Markdown.Block.Bullet -> Row(
                    horizontalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    Text(
                        text = "—",
                        style = Prinyal.type.voice.copy(fontStyle = FontStyle.Normal),
                        color = Prinyal.colors.inkFaint,
                    )
                    Text(
                        text = inline(block.text),
                        style = Prinyal.type.voice.copy(fontStyle = FontStyle.Normal),
                        color = Prinyal.colors.ink,
                    )
                }
            }
        }
    }
}

@Composable
private fun inline(line: String) = buildAnnotatedString {
    Markdown.spans(line).forEach { span ->
        val style = SpanStyle(
            fontWeight = if (span.bold) FontWeight.SemiBold else null,
            fontStyle = if (span.italic) FontStyle.Italic else null,
            fontFamily = if (span.code) Prinyal.type.meta.fontFamily else null,
            background = if (span.code) Prinyal.colors.hairline else androidx.compose.ui.graphics.Color.Unspecified,
        )
        withStyle(style) { append(span.text) }
    }
}
