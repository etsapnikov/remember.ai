package ai.prinim.prinyal.capture

import ai.prinim.prinyal.R
import ai.prinim.prinyal.ui.components.SheetButton
import ai.prinim.prinyal.ui.components.TertiaryButton
import ai.prinim.prinyal.ui.theme.MetaText
import ai.prinim.prinyal.ui.theme.Prinyal
import ai.prinim.prinyal.ui.theme.Radius
import ai.prinim.prinyal.ui.theme.Space
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Ввод с клавиатуры (1.6): когда говорить нельзя — совещание, транспорт,
 * спящий ребёнок рядом.
 *
 * Шторка, а не отдельный экран: человек всё ещё на экране записи, он просто
 * выбрал другой рот. Текст уходит тем же путём, что речь, — разбор, разделы,
 * люди, возвраты, — и подсказка говорит об этом прямо: «пиши как сказал бы».
 * Ничего не правится и не форматируется: набранное — тот же комок, что и
 * наговоренное, только буквами.
 *
 * Кнопка «Принял» — то же слово, что квитанция после записи: обещание одно.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TypedSheet(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = Prinyal.colors.surface,
        shape = Radius.sheet,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(start = Space.screen, end = Space.screen, top = Space.sm, bottom = Space.ml),
            verticalArrangement = Arrangement.spacedBy(Space.s18),
        ) {
            MetaText(stringResource(R.string.typed_plate), color = Prinyal.colors.inkFaint)

            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp)
                    .background(Prinyal.colors.paper, Radius.control)
                    .padding(Space.m),
            ) {
                if (text.isEmpty()) {
                    Text(
                        text = stringResource(R.string.typed_hint),
                        style = Prinyal.type.voice,
                        color = Prinyal.colors.inkFaint,
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = Prinyal.type.body.copy(color = Prinyal.colors.ink),
                    cursorBrush = SolidColor(Prinyal.colors.accentSelf),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus),
                )
            }
            // Поле само забирает фокус: шторка без клавиатуры выглядит сломанной.
            LaunchedEffect(Unit) { focus.requestFocus() }

            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                SheetButton(
                    text = stringResource(R.string.typed_save),
                    onClick = { if (text.isNotBlank()) onSave(text) },
                )
                TertiaryButton(
                    text = stringResource(R.string.typed_cancel),
                    onClick = onDismiss,
                    color = Prinyal.colors.inkMuted,
                )
            }
        }
    }
}
