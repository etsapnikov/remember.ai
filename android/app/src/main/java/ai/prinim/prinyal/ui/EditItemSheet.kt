package ai.prinim.prinyal.ui

import ai.prinim.prinyal.R
import ai.prinim.prinyal.data.DueKind
import ai.prinim.prinyal.data.ItemEntity
import ai.prinim.prinyal.data.ItemType
import ai.prinim.prinyal.data.Window
import ai.prinim.prinyal.domain.Phrases
import ai.prinim.prinyal.ui.components.TypeGlyph
import ai.prinim.prinyal.ui.theme.MetaText
import ai.prinim.prinyal.ui.theme.Prinyal
import ai.prinim.prinyal.ui.theme.tap
import ai.prinim.prinyal.ui.components.Divider
import ai.prinim.prinyal.ui.components.SheetButton
import ai.prinim.prinyal.ui.components.TertiaryButton
import ai.prinim.prinyal.ui.theme.Radius
import ai.prinim.prinyal.ui.theme.Sizes
import ai.prinim.prinyal.ui.theme.Space
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

/**
 * Правка пункта (PRD §F-5): тип, окно, текст.
 *
 * Три поля и ничего больше — правка не должна превращаться в форму задачи, иначе
 * продукт становится таск-менеджером (ТЗ UI §1). Правка окна пересчитывает возврат
 * немедленно — это делает репозиторий, здесь только выбор.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditItemSheet(
    item: ItemEntity,
    onDismiss: () -> Unit,
    onSave: (
        text: String?,
        type: ItemType?,
        window: Window?,
        exactAt: Long?,
        clear: Boolean,
    ) -> Unit,
    onBury: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var text by remember { mutableStateOf(TextFieldValue(item.text)) }
    var type by remember { mutableStateOf(ItemType.of(item.type)) }
    // Ручная дата: побеждает окно, потому что человек назвал конкретный день.
    // Дата пункта подхватывается сразу (Р-24.4).
    //
    // Поле начиналось с null, и пункт со сроком «5 октября» открывался так,
    // будто срока нет вовсе: ни окно не выбрано, ни день не показан. Человек
    // видел шит, где на вопрос «когда» нет ответа, и делал единственный
    // возможный вывод — дату поменять нельзя. А её можно, просто чип молчал.
    var exactAt by remember(item.id) {
        mutableStateOf(item.dueAt.takeIf { DueKind.of(item.dueKind) == DueKind.EXACT })
    }
    var pickingDate by remember { mutableStateOf(false) }
    var window by remember {
        mutableStateOf(
            if (DueKind.of(item.dueKind) == DueKind.WINDOW) Window.of(item.window) else null
        )
    }
    var noSchedule by remember { mutableStateOf(DueKind.of(item.dueKind) == DueKind.NONE) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Prinyal.colors.surface,
        shape = Radius.sheet,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(
                    start = Space.screen,
                    end = Space.screen,
                    top = Space.sm,
                    bottom = Space.ml,
                ),
            verticalArrangement = Arrangement.spacedBy(Space.s18),
        ) {
            MetaText(stringResource(R.string.edit_text))
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = Prinyal.type.itemTitle.copy(color = Prinyal.colors.ink),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Prinyal.colors.accentSelf),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Prinyal.colors.rule, Radius.control)
                    .padding(Space.sm),
            )

            MetaText(stringResource(R.string.edit_type))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                ItemType.entries.forEach { candidate ->
                    Chip(
                        selected = candidate == type,
                        onClick = { type = candidate },
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(Space.xs),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            TypeGlyph(
                                candidate,
                                size = 16.dp,
                                color = if (candidate == type) Prinyal.colors.onAccent
                                else Prinyal.colors.inkMuted,
                            )
                            Text(
                                text = Phrases.typeLabel(context, candidate),
                                style = Prinyal.type.label,
                                color = if (candidate == type) Prinyal.colors.onAccent
                                else Prinyal.colors.ink,
                            )
                        }
                    }
                }
            }

            MetaText(stringResource(R.string.edit_when))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Window.entries.forEach { candidate ->
                    Chip(
                        selected = !noSchedule && candidate == window,
                        onClick = {
                            window = candidate
                            noSchedule = false
                        },
                    ) {
                        Text(
                            text = Phrases.windowLabel(context, candidate),
                            style = Prinyal.type.label,
                            color = if (!noSchedule && candidate == window) Prinyal.colors.onAccent
                            else Prinyal.colors.ink,
                        )
                    }
                }
                Chip(selected = noSchedule, onClick = { noSchedule = true; window = null; exactAt = null }) {
                    Text(
                        text = stringResource(R.string.window_none),
                        style = Prinyal.type.label,
                        color = if (noSchedule) Prinyal.colors.onAccent else Prinyal.colors.ink,
                    )
                }

                // Конкретный день — первым уровнем, рядом с окнами (Д-8): это
                // не «расширенная настройка», а такой же ответ на вопрос
                // «когда», просто точный.
                Chip(selected = exactAt != null, onClick = { pickingDate = true }) {
                    Text(
                        text = exactAt?.let { DAY.format(java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault())) }
                            ?: stringResource(R.string.edit_pick_date),
                        style = Prinyal.type.label,
                        color = if (exactAt != null) Prinyal.colors.onAccent else Prinyal.colors.ink,
                    )
                }
            }

            if (pickingDate) {
                val picker = rememberDatePickerState(
                    initialSelectedDateMillis = exactAt ?: System.currentTimeMillis(),
                    // Прошлое не выбирается: возврат в прошлом не сработает, и
                    // предлагать его — обещать то, чего не будет (Р-15.7).
                    selectableDates = object : SelectableDates {
                        override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                            utcTimeMillis >= System.currentTimeMillis() - DAY_MS
                    },
                )
                DatePickerDialog(
                    onDismissRequest = { pickingDate = false },
                    confirmButton = {
                        Text(
                            text = stringResource(R.string.edit_save),
                            style = Prinyal.type.label,
                            color = Prinyal.colors.accentSelf,
                            modifier = Modifier
                                .tap {
                                    picker.selectedDateMillis?.let { day ->
                                        // Полдень выбранного дня: полночь читается
                                        // как «ночью», а окно утра у нас своё.
                                        // Час сохраняется: «5 октября в 18:00»
                                        // при правке дня не должно съезжать на
                                        // утро — человек менял дату, не время.
                                        val keep = exactAt?.let { prev ->
                                            prev - java.time.Instant.ofEpochMilli(prev)
                                                .atZone(java.time.ZoneId.systemDefault())
                                                .toLocalDate()
                                                .atStartOfDay(java.time.ZoneId.systemDefault())
                                                .toInstant().toEpochMilli()
                                        } ?: MORNING_OFFSET_MS
                                        exactAt = day + keep
                                        noSchedule = false
                                        window = null
                                    }
                                    pickingDate = false
                                }
                                .padding(Space.m),
                        )
                    },
                ) {
                    DatePicker(state = picker)
                }
            }

            // Ряд действий (спека R1.1 §4): главное — залитой пилюлей, отмена рядом
            // текстом, деструктивное — отдельной строкой за хайрлайном, чтобы жесты
            // «сохранить» и «похоронить» нельзя было перепутать вслепую.
            Row(
                horizontalArrangement = Arrangement.spacedBy(Space.s),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                SheetButton(
                    text = stringResource(R.string.edit_save),
                    onClick = {
                        onSave(
                            text.text,
                            type,
                            if (noSchedule || exactAt != null) null else window,
                            if (noSchedule) null else exactAt,
                            noSchedule,
                        )
                    },
                )
                TertiaryButton(
                    text = stringResource(R.string.edit_cancel),
                    onClick = onDismiss,
                    color = Prinyal.colors.inkMuted,
                )
            }

            Divider()
            // Похоронить — отдельной строкой моно за разделителем: жесты
            // «сохранить» и «похоронить» нельзя перепутать вслепую.
            TertiaryButton(
                text = stringResource(R.string.item_bury),
                onClick = onBury,
                color = Prinyal.colors.inkMuted,
                mono = true,
            )
        }
    }
}

@Composable
private fun Chip(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    // 44 с радиусом 22 и акцентной заливкой у выбранного (ТЗ §4). Обводка
    // ушла: выбранный чип отличался от невыбранного цветом рамки в один
    // пиксель — на снимке 21 разницу видно только рядом с соседом.
    androidx.compose.foundation.layout.Box(
        Modifier
            .height(Sizes.sheetChip)
            .clip(Radius.chipTall)
            .background(if (selected) Prinyal.colors.accentSelf else Prinyal.colors.paper)
            .clickable(onClick = onClick)
            .padding(horizontal = Space.m),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides
                if (selected) Prinyal.colors.onAccent else Prinyal.colors.ink,
        ) {
            content()
        }
    }
}

/** День выбранной даты показываем коротко: «10 сен». */
private val DAY: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale("ru")) // см. Dates

private const val DAY_MS = 24L * 60 * 60 * 1000
/** Девять утра выбранного дня — то же время, что у даты из речи. */
private const val MORNING_OFFSET_MS = 9L * 60 * 60 * 1000
