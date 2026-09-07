package ai.prinim.prinyal.ui

import ai.prinim.prinyal.R
import ai.prinim.prinyal.domain.Dates
import ai.prinim.prinyal.data.Confidence
import ai.prinim.prinyal.data.ItemEntity
import ai.prinim.prinyal.domain.LinkCandidates
import ai.prinim.prinyal.data.LinkedNote
import ai.prinim.prinyal.data.LinkReason
import ai.prinim.prinyal.data.ItemState
import ai.prinim.prinyal.data.ItemType
import ai.prinim.prinyal.data.NoteKind
import ai.prinim.prinyal.data.NoteStatus
import ai.prinim.prinyal.data.TopicEntity
import ai.prinim.prinyal.data.TopicSource
import ai.prinim.prinyal.data.Window
import ai.prinim.prinyal.domain.Phrases
import ai.prinim.prinyal.ui.components.GroupHeader
import ai.prinim.prinyal.ui.theme.MetaText
import ai.prinim.prinyal.ui.theme.Prinyal
import ai.prinim.prinyal.ui.theme.tap
import ai.prinim.prinyal.ui.theme.Haptics
import ai.prinim.prinyal.ui.theme.Radius
import ai.prinim.prinyal.ui.theme.Space
import android.media.MediaPlayer
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.clip
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
fun NoteScreen(
    vm: AppViewModel,
    noteId: String,
    onBack: () -> Unit,
    onOpenNote: (String) -> Unit = {},
) {
    val entry by vm.note(noteId).collectAsState(initial = null)
    val note = entry?.note
    val linked by vm.linked(noteId).collectAsState(initial = emptyList())
    val lastQuestion by vm.lastQuestion(noteId).collectAsState(initial = null)

    var editing by remember { mutableStateOf<ItemEntity?>(null) }
    // Раскрытие пункта: тап показывает, а меняет — второй жест (Р-15.4).
    var opened by remember { mutableStateOf<ItemEntity?>(null) }
    var openedReturns by remember { mutableStateOf<List<ai.prinim.prinyal.data.ReturnEntity>>(emptyList()) }
    // Пикер раздела: список живых разделов подтягиваем только когда открыли.
    var picking by remember { mutableStateOf(false) }
    var topics by remember { mutableStateOf<List<TopicEntity>>(emptyList()) }
    var topicName by remember { mutableStateOf<String?>(null) }
    // Слово, на котором задержали палец в «Что услышал» — вход в словарь (Д-2).
    var heardWord by remember { mutableStateOf<String?>(null) }
    // Правка транскрипта (спека R1.2 §15). null — покой, иначе черновик.
    var draft by remember(noteId) { mutableStateOf<TextFieldValue?>(null) }
    // Спрашиваем перед переразбором всегда, а не только при закрытых пунктах:
    // правило, которое срабатывает иногда, никто не выучивает (макеты 10d).
    var askReparse by remember(noteId) { mutableStateOf(false) }
    // Разворот сырца. У идеи закрыт по умолчанию, у остальных записей открыт:
    // там транскрипт и есть содержимое.
    // Замысел — это запись, у которой есть «Собрано».
    //
    // Раньше признаков было два: тело собиралось для idea и mixed, а сворачивал
    // сырец и предлагал «Покрутить» только idea. На записи с «Собрано», но
    // видом mixed, выходило нелепое: собранное есть, крутить нечего, сырец
    // развёрнут во весь экран. Один признак вместо двух согласует все три места.
    val hasBody = !entry?.note?.bodyMd.isNullOrBlank()
    var transcriptOpen by remember(noteId, hasBody) { mutableStateOf(!hasBody) }

    if (note == null) {
        // Своё сообщение, а не строка пустой ленты. Карточка теряет запись,
        // когда её удалили из-под открытого экрана, — и «Здесь появятся записи»
        // человек прочтёт как «пропало всё», хотя пропала одна.
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text(stringResource(R.string.note_gone), style = Prinyal.type.body)
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
            //
            // Шапка с датой и статусом добавлена по аудиту Д-7: без неё экран
            // начинался с объяснения, и человек не понимал, какую запись
            // открыл, — а именно это ему и нужно, чтобы решить, жалко её или
            // нет.
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MetaText(noteStamp(note.createdAt), color = Prinyal.colors.inkFaint)
                    MetaText(
                        text = stringResource(R.string.note_status_unheard),
                        color = Prinyal.colors.statusWarn,
                    )
                }
            }
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
                // Шапка карточки: когда записано и к чему отнесено. Раздел
                // правят только здесь — а показывали его до этого только в
                // ленте (аудит Д-7, п. 5).
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MetaText(noteStamp(note.createdAt), color = Prinyal.colors.inkFaint)
                    TopicChip(
                        name = topicName ?: stringResource(R.string.topics_loose),
                        source = TopicSource.of(note.topicSource),
                        // Дата остаётся целой, жмётся раздел: дату читают, а
                        // раздел узнают и по началу имени.
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .padding(start = Space.s),
                        onClick = { picking = true },
                    )
                }
            }

            item { AudioRow(File(note.audioPath), note.durationMs) }

            // «Собрано» — сразу под шапкой: человек диктовал идею комком именно
            // затем, чтобы получить собранное. Сырец нужен ему как источник и
            // доказательство, а не как чтение (Д-4, ответ на вопрос 3).
            note.bodyMd?.takeIf { it.isNotBlank() }?.let { body ->
                item { MarkdownBody(body) }
            }

            // «Покрутить» (Р-15.14) — там же, где «Собрано»: у списка покупок
            // крутить нечего, а кнопка, которая иногда бессмысленна, учит её
            // не замечать.
            // Резюме лупа (спека §7): карточка приклеена к заметке, выше
            // разговора. Отдельным блоком, а не в теле: тело — слова человека,
            // резюме собрано моделью, и граница между ними должна быть видна.
            note.spinSummary?.takeIf { it.isNotBlank() }?.let { summary ->
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(Prinyal.colors.wellSurface, Radius.control)
                            .padding(Space.m),
                        verticalArrangement = Arrangement.spacedBy(Space.sm),
                    ) {
                        MetaText(
                            stringResource(R.string.spin_summary_title),
                            color = Prinyal.colors.accentSelf,
                        )
                        Text(summary, style = Prinyal.type.voice, color = Prinyal.colors.ink)
                    }
                }
            }

            if (hasBody) {
                item {
                    Interview(
                        // Вопрос берётся из базы, а не из памяти вьюмодели:
                        // экран записи убивает задачу, и всё, что жило в
                        // памяти, к возвращению человека уже потеряно.
                        question = lastQuestion?.text,
                        stage = ai.prinim.prinyal.data.InterviewState.of(note.interview),
                        onAsk = { vm.askAboutIdea(noteId) },
                        // Отвечают тем же жестом, каким записывают: ответ
                        // становится сегментом заметки по механике Р-14.3, а не
                        // отдельной сущностью «ответ на вопрос». Флаг «asking»
                        // возвращает человека сюда же, а не на рабочий стол.
                        onAnswer = { openAppend(context, noteId, asking = true) },
                        // «Прекратить» — тихий выход, и ничего больше (Р-21.2).
                        // Оно открывало запись с вопросом про первый шаг: человек
                        // жал «прекратить», а продукт начинал его писать. Выход
                        // не должен ничего просить.
                        onStop = { vm.stopInterview(noteId) },
                        onSkip = { vm.skipQuestion(noteId) },
                        // Превратить разговор в дело — отдельное слово, и только
                        // когда разговор был: предлагать вывод из разговора,
                        // которого не случилось, нечего (макет 13c).
                        onFinish = { openFirstStep(context, noteId) },
                        grown = note.bodyMd?.contains("## Что докрутили") == true,
                    )
                }
            }

            degradedText?.let { text ->
                item { Text(text, style = Prinyal.type.voice, color = Prinyal.colors.accentSelf) }
            }

            if (items.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.note_items)) }
                items(items, key = { it.id }) { item ->
                    // Раскрытый пункт подсвечивается поверхностью с обводкой
                    // акцента (ТЗ §5, экраны 20/41): до 1.3 раскрытие ничем не
                    // отличалось от соседних пунктов, и «Поправить» с «Закрыть»
                    // читались как действия всей записи, а не одного пункта.
                    val expanded = opened?.id == item.id
                    Column(
                        Modifier
                            .padding(
                                horizontal = if (expanded) Space.sm else 0.dp,
                                vertical = if (expanded) Space.xs else 0.dp,
                            )
                            .clip(Radius.card)
                            .then(
                                if (expanded) {
                                    Modifier
                                        .background(Prinyal.colors.surface)
                                        .border(
                                            1.dp,
                                            Prinyal.colors.accentSelf.copy(alpha = 0.35f),
                                            Radius.card,
                                        )
                                } else Modifier
                            )
                    ) {
                    ItemCard(
                        item = item,
                        onDone = { vm.markDone(item.id) },
                        onDismiss = { vm.dismiss(item.id) },
                        onOpen = { opened = if (opened?.id == item.id) null else item },
                    )
                    // Раскрытие живёт под своим пунктом: соседние остаются на
                    // экране, и видно, откуда взялось (аудит Д-7, п. 4).
                    // Раскрытие растёт, а не появляется (полишинг, п. 7): строка
                    // была одна, стала пятью, и всё под ней прыгнуло. Это
                    // единственное место, где мгновенность читается не как
                    // «быстро», а как «непонятно, что раскрылось».
                    androidx.compose.animation.AnimatedVisibility(
                        visible = opened?.id == item.id,
                        enter = androidx.compose.animation.expandVertically(
                            animationSpec = androidx.compose.animation.core.tween(
                                ai.prinim.prinyal.ui.theme.Motion.Polish.EXPAND_MS,
                                easing = androidx.compose.animation.core.FastOutSlowInEasing,
                            ),
                        ) + androidx.compose.animation.fadeIn(
                            animationSpec = androidx.compose.animation.core.tween(
                                ai.prinim.prinyal.ui.theme.Motion.Polish.EXPAND_MS,
                            ),
                        ),
                        exit = androidx.compose.animation.shrinkVertically(
                            animationSpec = androidx.compose.animation.core.tween(
                                ai.prinim.prinyal.ui.theme.Motion.Polish.EXPAND_MS,
                            ),
                        ) + androidx.compose.animation.fadeOut(
                            animationSpec = androidx.compose.animation.core.tween(
                                ai.prinim.prinyal.ui.theme.Motion.Polish.EXPAND_MS,
                            ),
                        ),
                    ) {
                        if (opened?.id == item.id) {
                        LaunchedEffect(item.id) { openedReturns = vm.returnsFor(item.id) }
                        ItemDetail(
                            item = item,
                            returns = openedReturns,
                            rawSpan = item.rawSpan,
                            onEdit = {
                                editing = item
                                opened = null
                            },
                            onStopRepeat = {
                                vm.stopRepeat(item.id)
                                opened = null
                            },
                            onRevive = {
                                vm.reviveItem(item.id)
                                opened = null
                            },
                            noteAllClosed = items.none {
                                ItemState.of(it.state) in setOf(
                                    ItemState.PLANNED, ItemState.RETURNED, ItemState.SNOOZED,
                                )
                            },
                            onDismiss = { opened = null },
                        )
                        }
                    }
                    }
                }
            } else if (status == NoteStatus.RECORDED || status == NoteStatus.QUEUED ||
                status == NoteStatus.SENT
            ) {
                // Разбор идёт около минуты — карточка не имеет права выглядеть
                // пустой всё это время (Р-15.2).
                item { ParsingBlock() }
            }

            // «Связано» (Р-15.11) — под пунктами, а не над ними: связь про
            // соседние записи, а содержание этой — пункты. Наверху блок
            // перехватывал внимание раньше того, ради чего карточку открыли.
            // Блока нет, когда связей нет: пустой заголовок обещал бы, что
            // продукт что-то нашёл, и не нашёл бы ничего.
            if (linked.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.note_linked)) }
                items(linked, key = { it.id }) { link ->
                    LinkRow(link, onClick = { onOpenNote(link.id) })
                }
            }

            note.transcript?.takeIf { it.isNotBlank() }?.let { transcript ->
                item {
                    // Шапка сырца: слева имя блока, справа действия.
                    //
                    // Объяснение «разбираю — дописать можно через минуту» стоит
                    // **отдельной строкой**, а не в ряду с заголовком. В ряду
                    // оно ни во что не помещалось: три текста без ограничения
                    // ширины наезжали друг на друга, и «Поправить» ломалось
                    // пополам. Длинная фраза в SpaceBetween-ряду — это всегда
                    // так: ряд не переносит, он сжимает.
                    val busy = status == NoteStatus.RECORDED || status == NoteStatus.QUEUED
                    // Правки руками в записи — то, что переразбор сотрёт.
                    // Врать в обе стороны нельзя, но и грузить нечем: нет
                    // правок — фраза короче.
                    val handEdited = items.any { it.edited || it.previousText != null }
                    Column(
                        Modifier.fillMaxWidth().padding(top = Space.s),
                        verticalArrangement = Arrangement.spacedBy(Space.xs),
                    ) {
                        // Заголовок отдельной строкой, действия под ним.
                        //
                        // Раньше всё жило одним рядом, и это работало ровно до
                        // третьего действия: «Заново» обрезалось многоточием,
                        // а до него — складывалось в столбик из букв. Ряд из
                        // подписи и трёх слов в 411 dp не помещается, и
                        // подбирать отступы здесь значит ждать четвёртого.
                        // Тот же заголовок группы, что и «Что понял» выше:
                        // два раздела карточки обязаны выглядеть одинаково.
                        GroupHeader(
                            text = stringResource(R.string.note_transcript),
                            divider = false,
                        )
                        // Ряд не равный (макеты 10d): «Дописать» и «Поправить»
                        // добавляют и набраны акцентом, «Разобрать заново»
                        // стирает и стоит отдельной строкой ниже — по весу
                        // рядом с «Удалить», но не в одной строке с ним, чтобы
                        // два разрушительных слова не читались как пара.
                        if (askReparse) {
                            // Подтверждение заменяет ряд на месте: диалога,
                            // затемнения и центрированной плашки в продукте
                            // нет ни одного.
                            MetaText(
                                stringResource(
                                    if (handEdited) R.string.note_reparse_ask
                                    else R.string.note_reparse_ask_clean
                                ),
                                color = Prinyal.colors.ink,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(Space.ml)) {
                                MetaText(
                                    text = stringResource(R.string.note_reparse_go),
                                    color = Prinyal.colors.accentSelf,
                                    maxLines = 1,
                                    modifier = Modifier.tap {
                                        askReparse = false
                                        vm.reparse(noteId)
                                    },
                                )
                                MetaText(
                                    text = stringResource(R.string.note_reparse_cancel),
                                    color = Prinyal.colors.inkMuted,
                                    maxLines = 1,
                                    modifier = Modifier.tap { askReparse = false },
                                )
                            }
                        } else if (!busy) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(Space.ml),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // «Дописать» первым: добавляют чаще, чем чинят (Д-3).
                                MetaText(
                                    text = stringResource(R.string.note_append),
                                    color = Prinyal.colors.accentSelf,
                                    maxLines = 1,
                                    modifier = Modifier.tap { openAppend(context, noteId) },
                                )
                                MetaText(
                                    text = stringResource(R.string.transcript_edit),
                                    color = Prinyal.colors.accentSelf,
                                    maxLines = 1,
                                    modifier = Modifier.tap {
                                        // Курсор в конец: чаще всего дописывают
                                        // хвост (Д-6).
                                        draft = TextFieldValue(
                                            text = transcript,
                                            selection = TextRange(transcript.length),
                                        )
                                    },
                                )
                            }
                            // Переразбор — своей строкой и приглушённый: он
                            // стирает разложенные пункты, а по виду был
                            // неотличим от правки.
                            MetaText(
                                text = stringResource(R.string.note_reparse_short),
                                color = Prinyal.colors.inkMuted,
                                maxLines = 1,
                                modifier = Modifier.tap { askReparse = true },
                            )
                        }

                        // Кнопка не исчезает молча — продукт прямо говорит,
                        // почему сейчас нельзя (Р-15.1).
                        if (busy) {
                            MetaText(
                                text = stringResource(R.string.note_append_busy),
                                color = Prinyal.colors.inkFaint,
                            )
                        }
                    }
                }
                item {
                    // Без подсветки: в покое она заливала две трети абзаца и
                    // читалась как маркер по всему тексту. Подсветка нужна,
                    // когда человек спросил «откуда это» — то есть в раскрытии
                    // пункта (аудит Д-7, п. 12).
                    // У идеи сырец свёрнут: человек диктовал комком именно
                    // затем, чтобы получить «Собрано», а транскрипт нужен ему
                    // как источник и доказательство, а не как чтение (решение
                    // дизайнера к 1.0.2). У остальных записей он и есть ответ.
                    if (transcriptOpen) {
                        TranscriptBlock(
                            transcript = transcript,
                            items = emptyList(),
                            onWord = { heardWord = it },
                        )
                    } else {
                        MetaText(
                            text = stringResource(R.string.note_transcript_show),
                            color = Prinyal.colors.accentSelf,
                            modifier = Modifier.tap { transcriptOpen = true },
                        )
                    }
                }
            }
        }

        // Разбиение — служебная строка и живёт внизу карточки, рядом с
        // «Удалить»: она про устройство записи, а не про её содержание, и
        // над пунктами перехватывала внимание раньше них (решение дизайнера
        // к 1.0.2).
        // Разбиение обязано быть видимым: молча разложить одну речь по двум
        // карточкам — значит потерять человека, который ищет сказанное там, где
        // сказал. Строка служебная, не празднующая (Д-10).
        note.siblingId?.let { siblingId ->
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MetaText(
                        text = stringResource(R.string.note_split_line),
                        color = Prinyal.colors.accentSelf,
                        modifier = Modifier.tap { onOpenNote(siblingId) },
                    )
                    MetaText(
                        text = stringResource(R.string.note_merge),
                        color = Prinyal.colors.inkMuted,
                        modifier = Modifier.tap { vm.mergeSiblings(noteId) },
                    )
                }
            }
        }


        // На экране ошибки содержимого мало, и «Удалить запись» оказывалось
        // прямо под кнопкой «повторить» — рядом с действием, которое человек
        // как раз и собирался нажать (аудит Д-7). Отодвигаем его к низу.
        if (failed) {
            item { Box(Modifier.fillParentMaxHeight(0.3f)) }
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
            onSave = { text, type, window, exactAt, clear ->
                vm.editItem(item.id, text, type, window, exactAt, clear)
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
    // «Что понял» и «Что услышал» — заголовки групп (ТЗ §5, экраны 18–21):
    // моно капсом с разделителем. Тем же кеглем, что и пояснения вокруг, они
    // не отделяли разделы, а участвовали в них.
    GroupHeader(text = text, modifier = Modifier.padding(top = Space.s))
}

/** Пункт в карточке: без глифа (вариант А), с планом и действиями. */
@Composable
private fun ItemCard(
    item: ItemEntity,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
) {
    val context = LocalContext.current
    val state = ItemState.of(item.state)
    val uncertain = Confidence.of(item.confidence) == Confidence.LOW

    Column(
        Modifier
            .fillMaxWidth()
            // Тап раскрывает, а не правит: самый дешёвый жест не должен
            // менять данные — случайное касание не имеет права ничего испортить.
            .clickable(onClick = onOpen)
            .padding(vertical = Space.s),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Text(
            text = item.text,
            style = Prinyal.type.itemTitle,
            // Низкая уверенность — приглушённо: продукт не притворяется уверенным.
            color = if (uncertain) Prinyal.colors.inkMuted else Prinyal.colors.ink,
        )
        // Прежняя формулировка, если пункт уменьшали (Р-15.8). Замена без следа
        // неотличима от подмены: продукт переписал слова человека и обязан
        // показать, какие именно.
        item.previousText?.let { was ->
            MetaText(stringResource(R.string.item_previous, was), color = Prinyal.colors.inkFaint)
        }
        if (ItemType.of(item.type) == ItemType.TELL) {
            item.who?.let { MetaText(it) }
        }
        Text(
            text = Phrases.plan(context, item) +
                // Происхождение — факт, а не событие: пометка не гаснет.
                if (item.fromInterview) " · " + stringResource(R.string.item_from_interview) else "",
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
                // Повтор, сделанный в этот раз, «сделано» больше не
                // предлагает: раз уже засчитан, и второе нажатие удвоило бы
                // историю («всего 9 раз») ничего не изменив. Вернётся он сам.
                if (item.repeatRule == null || item.repeatDoneAt == null) {
                    Text(
                        text = stringResource(R.string.action_done),
                        style = Prinyal.type.label,
                        color = Prinyal.colors.done,
                        modifier = Modifier.tap(onClick = onDone),
                    )
                }
                Text(
                    text = stringResource(R.string.action_dismiss),
                    style = Prinyal.type.label,
                    color = Prinyal.colors.inkMuted,
                    modifier = Modifier.tap(onClick = onDismiss),
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

        GroupHeader(text = stringResource(R.string.note_transcript))

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
                modifier = Modifier.tap(onClick = onCancel),
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
    val context = androidx.compose.ui.platform.LocalContext.current

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
                    wordAt(transcript, at)?.let { word ->
                        // Жест невидимый — подтверждение обязательно (аудит
                        // Д-7): человек не может знать, что слово поймалось,
                        // пока шит не открылся, а открывается он не мгновенно.
                        Haptics.returnAction(context)
                        onWord(word)
                    }
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
            modifier = Modifier.tap(enabled = file.exists()) {
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

/** Когда записано: дата и время служебным моно в шапке карточки. */
private fun noteStamp(millis: Long): String = Dates.dayTime(millis)


/**
 * Строка блока «Связано» (Р-15.11).
 *
 * Причина, начало записи, дата — и ничего больше. Оценки связи («сильная»,
 * проценты уверенности) здесь нет намеренно: человеку важно, о чём та запись, а
 * не насколько машина в себе уверена. Неуверенные связи до экрана не доходят
 * вовсе — их отсекает валидатор.
 */
@Composable
private fun LinkRow(link: LinkedNote, onClick: () -> Unit) {
    val reason = when (LinkReason.of(link.reason)) {
        LinkReason.ANSWERS -> R.string.link_answers
        LinkReason.DISPUTES -> R.string.link_disputes
        else -> R.string.link_continues
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Space.xs),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        MetaText(stringResource(reason), color = Prinyal.colors.accentSelf)
        Text(
            text = LinkCandidates.opening(link.transcript),
            style = Prinyal.type.body,
            color = Prinyal.colors.ink,
        )
        MetaText(noteStamp(link.createdAt), color = Prinyal.colors.inkFaint)
    }
}


/** Вопрос про первый шаг (Р-20.2): тот же экран записи, своя плашка. */
private fun openFirstStep(context: android.content.Context, noteId: String) {
    context.startActivity(
        android.content.Intent(
            context,
            ai.prinim.prinyal.capture.CaptureActivity::class.java,
        ).apply {
            putExtra(ai.prinim.prinyal.capture.CaptureActivity.EXTRA_APPEND_TO, noteId)
            putExtra(ai.prinim.prinyal.capture.CaptureActivity.EXTRA_FIRST_STEP, true)
            addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
            )
        }
    )
}

/** Экран записи в режиме дописывания — общий вход для «Дописать» и ответа. */
private fun openAppend(
    context: android.content.Context,
    noteId: String,
    /** Ответ на вопрос: после квитанции вернуться в заметку и спросить дальше. */
    asking: Boolean = false,
) {
    context.startActivity(
        android.content.Intent(
            context,
            ai.prinim.prinyal.capture.CaptureActivity::class.java,
        ).apply {
            putExtra(ai.prinim.prinyal.capture.CaptureActivity.EXTRA_APPEND_TO, noteId)
            putExtra(ai.prinim.prinyal.capture.CaptureActivity.EXTRA_ASKING, asking)
            // NEW_TASK | CLEAR_TASK — по той же причине, что и у «Дописать»
            // (Р-15.1): интент не должен попасть в умирающую задачу.
            addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
            )
        }
    )
}

/**
 * Режим «покрутить» (Р-15.14).
 *
 * Один вопрос на экране и два выхода: ответить голосом тем же жестом, каким
 * человек и записывает, или закрыть. Списка прошлых вопросов нет — это не
 * переписка, а разговор, у которого есть только текущая реплика.
 */
@Composable
private fun Interview(
    question: String?,
    stage: ai.prinim.prinyal.data.InterviewState,
    /** Был ли хоть один круг: от этого зависит, предлагать ли вывод делом. */
    grown: Boolean,
    onAsk: () -> Unit,
    onAnswer: () -> Unit,
    onStop: () -> Unit,
    onSkip: () -> Unit,
    onFinish: () -> Unit,
) {
    when {
        // Продукт думает: разбирает ответ или сочиняет следующий вопрос.
        // Между кругами кнопки нет — разговор не просит разрешения
        // продолжиться (петля владельца от 24.08).
        stage == ai.prinim.prinyal.data.InterviewState.THINKING -> MetaText(
            text = stringResource(R.string.interview_thinking),
            color = Prinyal.colors.inkFaint,
        )

        stage == ai.prinim.prinyal.data.InterviewState.ASKED && question != null -> Column(
            Modifier
                .fillMaxWidth()
                .background(Prinyal.colors.wellSurface, Radius.control)
                .padding(Space.m),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Text(question, style = Prinyal.type.body, color = Prinyal.colors.ink)
            // Ответ — своей строкой, выход — под ним: три слова в ряд не
            // помещаются, это в продукте проверено трижды.
            MetaText(
                text = stringResource(R.string.interview_answer),
                color = Prinyal.colors.accentSelf,
                maxLines = 1,
                modifier = Modifier.tap(onClick = onAnswer),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Space.ml)) {
                // Пропуск — не выход: вопрос уходит в историю, и приходит
                // следующий. Два пропуска подряд модель понимает как усталость.
                MetaText(
                    text = stringResource(R.string.interview_skip),
                    color = Prinyal.colors.inkMuted,
                    maxLines = 1,
                    modifier = Modifier.tap(onClick = onSkip),
                )
                MetaText(
                    text = stringResource(R.string.interview_stop),
                    color = Prinyal.colors.inkMuted,
                    maxLines = 1,
                    modifier = Modifier.tap(onClick = onStop),
                )
                if (grown) {
                    MetaText(
                        text = stringResource(R.string.interview_to_deed),
                        color = Prinyal.colors.inkMuted,
                        maxLines = 1,
                        modifier = Modifier.tap(onClick = onFinish),
                    )
                }
            }
        }

        else -> MetaText(
            text = stringResource(R.string.interview_start),
            color = Prinyal.colors.accentSelf,
            modifier = Modifier.tap(onClick = onAsk),
        )
    }
}
