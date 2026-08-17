package ai.prinim.prinyal.ui

import ai.prinim.prinyal.R
import ai.prinim.prinyal.data.Confidence
import ai.prinim.prinyal.data.ItemEntity
import ai.prinim.prinyal.data.ItemState
import ai.prinim.prinyal.data.ItemType
import ai.prinim.prinyal.data.NoteStatus
import ai.prinim.prinyal.data.TopicEntity
import ai.prinim.prinyal.data.TopicSource
import ai.prinim.prinyal.data.Window
import ai.prinim.prinyal.domain.Phrases
import ai.prinim.prinyal.ui.theme.MetaText
import ai.prinim.prinyal.ui.theme.Prinyal
import ai.prinim.prinyal.ui.theme.Radius
import ai.prinim.prinyal.ui.theme.Space
import android.media.MediaPlayer
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * Карточка записи (спека R1.1 §3).
 *
 * Норма: пункты с meta-строками, транскрипт, внизу «Удалить запись».
 * Ошибка ASR: объяснение без метафор, аудио, одна кнопка «Разобрать заново» —
 * действие «повторить» удалено, оно дублировало её же.
 */
@Composable
fun NoteScreen(vm: AppViewModel, noteId: String, onBack: () -> Unit) {
    val entry by vm.note(noteId).collectAsState(initial = null)
    val note = entry?.note

    var editing by remember { mutableStateOf<ItemEntity?>(null) }
    // Пикер раздела: список живых разделов подтягиваем только когда открыли.
    var picking by remember { mutableStateOf(false) }
    var topics by remember { mutableStateOf<List<TopicEntity>>(emptyList()) }
    var topicName by remember { mutableStateOf<String?>(null) }
    // Слово, на котором задержали палец в «Что услышал» — вход в словарь (Д-2).
    var heardWord by remember { mutableStateOf<String?>(null) }
    // Правка транскрипта (спека R1.2 §15). null — покой, иначе черновик.
    var draft by remember(noteId) { mutableStateOf<TextFieldValue?>(null) }

    if (note == null) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text(stringResource(R.string.feed_empty), style = Prinyal.type.body)
        }
        return
    }

    val context = LocalContext.current
    val status = NoteStatus.of(note.status)
    val failed = status == NoteStatus.FAILED_ASR || status == NoteStatus.FAILED_LLM
    val items = entry?.items.orEmpty()
    val degradedText = if (!failed) Phrases.degraded(context, note.degraded) else null

    // Имя раздела читаем по id: держать его копией в заметке значило бы
    // расходиться с `topics` после переименования.
    LaunchedEffect(note.topicId) { topicName = vm.topicName(note.topicId) }

    // Правка — не элемент списка, а отдельная раскладка на весь экран.
    //
    // Раньше поле жило внутри LazyColumn, и от этого шли все симптомы Р-14.6:
    // список и поле спорили за скролл, а курсор при поднятой клавиатуре
    // оказывался за нижним краем — экран выглядел замершим. Спека Д-6 требует
    // обратного: «скроллится поле, не экран».
    val editingDraft = draft
    if (editingDraft != null && !failed) {
        TranscriptEditing(
            items = items,
            value = editingDraft,
            onValue = { draft = it },
            onReparse = {
                vm.saveTranscriptAndReparse(noteId, editingDraft.text)
                draft = null
            },
            onCancel = {
                if (editingDraft.text != note.transcript) vm.showDroppedEdit()
                draft = null
            },
        )
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(
            start = Space.screen, end = Space.screen, top = Space.s, bottom = Space.xxl,
        ),
        verticalArrangement = Arrangement.spacedBy(Space.ml),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (status == NoteStatus.FAILED_LLM) {
            // Речь распозналась, разбор — нет. Аудио цело, повтор осмыслен, и
            // предлагать его надо строкой на месте пунктов, а не отдельным
            // экраном ошибки: запись в порядке, не хватает только разбора.
            item { AudioRow(File(note.audioPath), note.durationMs) }
            item { ParsingFailed(onRetry = { vm.reparse(noteId) }) }
            note.transcript?.takeIf { it.isNotBlank() }?.let { transcript ->
                item { SectionTitle(stringResource(R.string.note_transcript)) }
                item { TranscriptBlock(transcript, items) }
            }
        } else if (failed) {
            // §3: состояние ошибки — объяснение, аудио, одно действие.
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Space.ml)) {
                    Text(
                        text = stringResource(R.string.note_asr_failed_body),
                        style = Prinyal.type.body,
                        color = Prinyal.colors.ink,
                    )
                    AudioRow(File(note.audioPath), note.durationMs)
                    Box(
                        Modifier
                            .background(Prinyal.colors.accentSelf, Radius.pill)
                            .clickable { vm.reparse(noteId) }
                            .padding(horizontal = Space.ml, vertical = Space.sm),
                    ) {
                        Text(
                            text = stringResource(R.string.note_reparse),
                            style = Prinyal.type.label,
                            color = Prinyal.colors.paper,
                        )
                    }
                }
            }
        } else {
            // Чип раздела — тихий, у шапки. Пункты своего топика не имеют:
            // раздел это свойство всей записи (scope 1.0.1 §0).
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AudioRow(File(note.audioPath), note.durationMs)
                    TopicChip(
                        name = topicName ?: stringResource(R.string.topics_loose),
                        source = TopicSource.of(note.topicSource),
                        onClick = { picking = true },
                    )
                }
            }

            // «Собрано» — сразу под шапкой: человек диктовал идею комком именно
            // затем, чтобы получить собранное. Сырец нужен ему как источник и
            // доказательство, а не как чтение (Д-4, ответ на вопрос 3).
            note.bodyMd?.takeIf { it.isNotBlank() }?.let { body ->
                item { MarkdownBody(body) }
            }

            degradedText?.let { text ->
                item { Text(text, style = Prinyal.type.voice, color = Prinyal.colors.accentSelf) }
            }

            if (items.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.note_items)) }
                items(items, key = { it.id }) { item ->
                    ItemCard(
                        item = item,
                        onDone = { vm.markDone(item.id) },
                        onDismiss = { vm.dismiss(item.id) },
                        onEdit = { editing = item },
                    )
                }
            } else if (status == NoteStatus.RECORDED || status == NoteStatus.QUEUED ||
                status == NoteStatus.SENT
            ) {
                // Разбор идёт около минуты — карточка не имеет права выглядеть
                // пустой всё это время (Р-15.2).
                item { ParsingBlock() }
            }

            note.transcript?.takeIf { it.isNotBlank() }?.let { transcript ->
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(top = Space.s),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MetaText(
                            stringResource(R.string.note_transcript),
                            color = Prinyal.colors.inkFaint,
                        )
                        // Вход — строкой у заголовка. Тап по самому тексту остаётся
                        // выделению и копированию: транскрипт читают чаще, чем правят.
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                        // Пока идёт разбор, дописывать нельзя: переразбор пошёл бы
                        // по половине текста. Кнопка не исчезает и не молчит — она
                        // прямо говорит, почему сейчас нельзя (Р-15.1).
                        val busy = status == NoteStatus.RECORDED || status == NoteStatus.QUEUED
                        if (busy) {
                            MetaText(
                                text = stringResource(R.string.note_append_busy),
                                color = Prinyal.colors.inkFaint,
                            )
                        } else {
                        // «Дописать» первым: добавляют чаще, чем чинят (Д-3).
                        MetaText(
                            text = stringResource(R.string.note_append),
                            color = Prinyal.colors.accentSelf,
                            modifier = Modifier.clickable {
                                context.startActivity(
                                    android.content.Intent(
                                        context,
                                        ai.prinim.prinyal.capture.CaptureActivity::class.java,
                                    ).apply {
                                        putExtra(
                                            ai.prinim.prinyal.capture.CaptureActivity.EXTRA_APPEND_TO,
                                            noteId,
                                        )
                                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                )
                            },
                        )
                        }
                        MetaText(
                            text = stringResource(R.string.transcript_edit),
                            color = Prinyal.colors.accentSelf,
                            modifier = Modifier.clickable {
                                // Курсор в конец: чаще всего дописывают хвост (Д-6).
                                draft = TextFieldValue(
                                    text = transcript,
                                    selection = TextRange(transcript.length),
                                )
                            },
                        )
                        }
                    }
                }
                item {
                    TranscriptBlock(
                        transcript = transcript,
                        items = items,
                        onWord = { heardWord = it },
                    )
                }
            }
        }

        // «Удалить запись» внизу, meta-регистр, без заливки; удаляет сразу и
        // возвращает в ленту — снекбар с «вернуть» тот же (§2.2).
        item {
            Column {
                HorizontalDivider(thickness = 1.dp, color = Prinyal.colors.hairline)
                MetaText(
                    text = stringResource(R.string.note_delete_full),
                    color = Prinyal.colors.inkMuted,
                    modifier = Modifier
                        .clickable {
                            vm.deleteNote(noteId)
                            onBack()
                        }
                        .padding(vertical = Space.sm),
                )
            }
        }
    }

    heardWord?.let { word ->
        DictionarySheet(
            source = word,
            context = note.transcript.orEmpty(),
            onSave = {
                vm.addReplacement(word, it)
                heardWord = null
            },
            onDismiss = { heardWord = null },
        )
    }

    if (picking) {
        LaunchedEffect(Unit) { topics = vm.liveTopics() }
        TopicPicker(
            topics = topics,
            currentId = note.topicId,
            onPick = {
                vm.setTopic(noteId, it)
                picking = false
            },
            onCreate = {
                vm.createTopicAndAssign(noteId, it)
                picking = false
            },
            onDismiss = { picking = false },
        )
    }

    editing?.let { item ->
        EditItemSheet(
            item = item,
            onDismiss = { editing = null },
            onSave = { text, type, window, clear ->
                vm.editItem(item.id, text, type, window, clear)
                editing = null
            },
            onBury = {
                vm.buryWithUndo(item.id)
                editing = null
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    MetaText(text, color = Prinyal.colors.inkFaint, modifier = Modifier.padding(top = Space.s))
}

/** Пункт в карточке: без глифа (вариант А), с планом и действиями. */
@Composable
private fun ItemCard(
    item: ItemEntity,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
) {
    val context = LocalContext.current
    val state = ItemState.of(item.state)
    val uncertain = Confidence.of(item.confidence) == Confidence.LOW

    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(vertical = Space.s),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Text(
            text = item.text,
            style = Prinyal.type.itemTitle,
            // Низкая уверенность — приглушённо: продукт не притворяется уверенным.
            color = if (uncertain) Prinyal.colors.inkMuted else Prinyal.colors.ink,
        )
        if (ItemType.of(item.type) == ItemType.TELL) {
            item.who?.let { MetaText(it) }
        }
        Text(
            text = Phrases.plan(context, item),
            style = Prinyal.type.voice,
            color = Prinyal.colors.accentSelf,
        )
        if (uncertain) {
            MetaText(Phrases.uncertainty(context, item).orEmpty())
        }

        if (state !in setOf(ItemState.DONE, ItemState.DISMISSED)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Space.ml),
                modifier = Modifier.padding(top = Space.xs),
            ) {
                Text(
                    text = stringResource(R.string.action_done),
                    style = Prinyal.type.label,
                    color = Prinyal.colors.done,
                    modifier = Modifier.clickable(onClick = onDone),
                )
                Text(
                    text = stringResource(R.string.action_dismiss),
                    style = Prinyal.type.label,
                    color = Prinyal.colors.inkMuted,
                    modifier = Modifier.clickable(onClick = onDismiss),
                )
            }
        }
    }
}

/** Отступ поля от клавиатуры — Д-6. */
private val KEYBOARD_GAP = 12.dp

/** Сколько места отдаём приглушённым пунктам, прежде чем они начнут прокручиваться. */
private val DIMMED_ITEMS_MAX = 168.dp

/**
 * Правка транскрипта (спека R1.2 §15, поведение — Д-6).
 *
 * Голое поле: ни рамки, ни тулбара, ни форматирования — только курсор и
 * подчёркивание акцентом. Предупреждение стоит над кнопкой постоянно, а не
 * всплывает модалкой «Вы уверены?».
 *
 * Подсветка `raw_span` здесь не рисуется вовсе: она указывает на связь «этот кусок
 * речи → этот пункт», и после первой же вставки символа врёт — границы поехали, а
 * пункты ещё старые. Живая, но неверная подсветка хуже её отсутствия.
 */
@Composable
private fun TranscriptEditing(
    items: List<ItemEntity>,
    value: TextFieldValue,
    onValue: (TextFieldValue) -> Unit,
    onReparse: () -> Unit,
    onCancel: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val accent = Prinyal.colors.accentSelf
    val itemsEdited = items.any { it.edited }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    // Системная «назад» из правки — тот же выход, что «Отмена».
    BackHandler(enabled = true) { onCancel() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Space.screen)
            .padding(top = Space.s, bottom = KEYBOARD_GAP),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        // Пункты стоят на месте и приглушены до 35%: видно, что именно
        // пересоберётся. Своя прокрутка нужна на случай длинного списка —
        // она не конфликтует с полем, это отдельная область экрана.
        if (items.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .heightIn(max = DIMMED_ITEMS_MAX)
                    .verticalScroll(rememberScrollState())
                    .graphicsLayer { alpha = 0.35f },
                verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                items.forEach { item ->
                    Text(
                        text = item.text,
                        style = Prinyal.type.itemTitle,
                        color = Prinyal.colors.ink,
                    )
                }
            }
        }

        MetaText(stringResource(R.string.note_transcript), color = Prinyal.colors.inkFaint)

        // weight(1f) даёт полю конечную высоту — и только тогда BasicTextField
        // прокручивает текст внутри себя и держит курсор в видимой части.
        // Без ограничения высоты поле растёт бесконечно, и курсор уезжает вниз.
        BasicTextField(
            value = value,
            onValueChange = onValue,
            textStyle = Prinyal.type.body.copy(color = Prinyal.colors.ink),
            cursorBrush = SolidColor(Prinyal.colors.accentSelf),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .focusRequester(focus)
                .drawBehind {
                    val y = size.height - 1.dp.toPx()
                    drawLine(
                        color = accent,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
                .padding(bottom = Space.s),
        )

        Text(
            text = stringResource(
                if (itemsEdited) R.string.transcript_reparse_warn
                else R.string.transcript_reparse_plain
            ),
            style = Prinyal.type.body,
            color = Prinyal.colors.inkMuted,
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.ml),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .background(Prinyal.colors.accentSelf, Radius.pill)
                    .clickable(onClick = onReparse)
                    .padding(horizontal = Space.ml, vertical = Space.sm),
            ) {
                Text(
                    text = stringResource(R.string.transcript_reparse),
                    style = Prinyal.type.label,
                    color = Prinyal.colors.paper,
                )
            }
            Text(
                text = stringResource(R.string.edit_cancel),
                style = Prinyal.type.label,
                color = Prinyal.colors.inkMuted,
                modifier = Modifier.clickable(onClick = onCancel),
            )
        }
    }
}

/**
 * Транскрипт-сырец с подсветкой `raw_span`: видно, из какого куска речи вырос пункт.
 * Это и доверие, и отладка промпта.
 */
@Composable
private fun TranscriptBlock(
    transcript: String,
    items: List<ItemEntity>,
    onWord: (String) -> Unit = {},
) {
    val highlight = Prinyal.colors.accentSelfSoft
    val ink = Prinyal.colors.inkMuted

    val annotated: AnnotatedString = remember(transcript, items) {
        val spans = items.mapNotNull { it.rawSpan?.takeIf(String::isNotBlank) }
        buildAnnotatedString {
            var cursor = 0
            val lower = transcript.lowercase()
            val marks = spans.mapNotNull { span ->
                val at = lower.indexOf(span.lowercase())
                if (at >= 0) at to (at + span.length) else null
            }.sortedBy { it.first }

            marks.forEach { (start, end) ->
                if (start < cursor) return@forEach
                append(transcript.substring(cursor, start))
                withStyle(SpanStyle(background = highlight)) {
                    append(transcript.substring(start, end))
                }
                cursor = end
            }
            append(transcript.substring(cursor))
        }
    }

    // Долгий тап по слову открывает правило словаря (Д-2).
    //
    // Режимами, а не зонами: в покое транскрипт — не текстовое поле, системное
    // выделение выключено, долгий тап наш. В режиме правки всё наоборот: поле
    // обычное, работают выделение и копирование, наш жест выключен. Так у
    // каждого жеста ровно один смысл в каждый момент.
    var layout by remember(transcript) { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = annotated,
        style = Prinyal.type.body,
        color = ink,
        onTextLayout = { layout = it },
        modifier = Modifier.pointerInput(transcript) {
            detectTapGestures(
                onLongPress = { offset ->
                    val result = layout ?: return@detectTapGestures
                    val at = result.getOffsetForPosition(offset)
                    wordAt(transcript, at)?.let(onWord)
                },
            )
        },
    )
}

/**
 * Слово под пальцем. Границы — по буквам и цифрам: дефис и апостроф внутри
 * имени встречаются, а пробел и точка слово заканчивают.
 */
private fun wordAt(text: String, at: Int): String? {
    if (at !in text.indices) return null
    fun isWord(c: Char) = c.isLetterOrDigit() || c == '-'
    if (!isWord(text[at])) return null
    var start = at
    while (start > 0 && isWord(text[start - 1])) start--
    var end = at
    while (end < text.length - 1 && isWord(text[end + 1])) end++
    return text.substring(start, end + 1).takeIf { it.isNotBlank() }
}

/** Плеер: «послушать · 0:04», тап проигрывает (§3). */
@Composable
private fun AudioRow(file: File, durationMs: Long) {
    var playing by remember { mutableStateOf(false) }
    val player = remember { MediaPlayer() }

    DisposableEffect(Unit) {
        onDispose { runCatching { player.release() } }
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(if (playing) R.string.note_pause else R.string.note_audio_play),
            style = Prinyal.type.label,
            color = if (file.exists()) Prinyal.colors.accentSelf else Prinyal.colors.inkFaint,
            modifier = Modifier.clickable(enabled = file.exists()) {
                runCatching {
                    if (playing) {
                        player.pause()
                        playing = false
                    } else {
                        player.reset()
                        player.setDataSource(file.absolutePath)
                        player.prepare()
                        player.setOnCompletionListener { playing = false }
                        player.start()
                        playing = true
                    }
                }
            },
        )
        MetaText("· %d:%02d".format(durationMs / 60_000, (durationMs / 1000) % 60))
    }
}
