package ai.prinim.prinyal.ui

import ai.prinim.prinyal.R
import ai.prinim.prinyal.data.PersonOverview
import ai.prinim.prinyal.data.ItemState
import ai.prinim.prinyal.domain.Dates
import ai.prinim.prinyal.ui.theme.MetaText
import ai.prinim.prinyal.ui.theme.Prinyal
import ai.prinim.prinyal.ui.theme.Space
import ai.prinim.prinyal.ui.theme.tap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * «Люди» — выборка, а не раздел (Д-26).
 *
 * Человек не заводится руками и не удаляется: он появляется, когда прозвучал в
 * двух **разных** записях, и исчезает, если записи ушли. Названный дважды в
 * одной мысли — это всё ещё одна мысль.
 *
 * Ни аватаров, ни кружков с буквой, ни кнопки «добавить». Записная книжка —
 * другой продукт.
 */
@Composable
fun PeopleScreen(vm: AppViewModel, onOpen: (String, String) -> Unit) {
    val people by vm.people.collectAsState()

    if (people.isEmpty()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = Space.screen, vertical = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Text(
                text = stringResource(R.string.empty_people_title),
                style = Prinyal.type.itemTitle,
                color = Prinyal.colors.ink,
            )
            Text(
                text = stringResource(R.string.empty_people_body),
                style = Prinyal.type.voice,
                color = Prinyal.colors.inkMuted,
            )
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(
            start = Space.screen, end = Space.screen, top = Space.s, bottom = Space.xxl,
        ),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(people, key = { it.id }) { person ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .tap { onOpen(person.id, person.name) }
                    .padding(vertical = Space.sm),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = person.name,
                    style = Prinyal.type.itemTitle,
                    color = Prinyal.colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).padding(end = Space.s),
                )
                MetaText(
                    text = pluralStringResource(R.plurals.person_notes, person.notes, person.notes),
                    color = Prinyal.colors.inkFaint,
                )
            }
            HorizontalDivider(thickness = 1.dp, color = Prinyal.colors.hairline)
        }
    }
}

/**
 * Карточка человека (Д-27).
 *
 * Норма — **без факта**: за две недели механика доспроса не собрала ни одного,
 * и одиннадцать имён из одиннадцати ничего о себе не рассказали. Поэтому
 * пустого блока «Что известно» здесь нет вовсе — ни заголовка, ни фразы «пока
 * ничего не знаю». Карточка начинается с дел и остаётся полноценным экраном.
 *
 * Порядок блоков — как в контекст-паке: что известно → открытые дела → записи.
 * Пустые блоки не печатаются, порядок остальных не меняется.
 */
@Composable
fun PersonScreen(
    vm: AppViewModel,
    personId: String,
    onOpenNote: (String) -> Unit,
    onPickPack: (String) -> Unit = {},
    onBack: () -> Unit = {},
) {
    val notes by vm.notesOfPerson(personId).collectAsState(initial = emptyList())
    val facts by vm.factsOfPerson(personId).collectAsState(initial = emptyList())
    val everyone by vm.allPeople.collectAsState()
    val others = everyone.filter { it.id != personId }
    val people by vm.people.collectAsState()
    val person = people.firstOrNull { it.id == personId }
    val context = androidx.compose.ui.platform.LocalContext.current

    // Счёт живого под именем — по всем незакрытым пунктам его записей, а не
    // только по тем, где он назван адресатом (макет 13a).
    //
    // Прежний счёт был от блока «Открытые дела»: там адресат нужен, потому что
    // блок отвечал на вопрос «что я должен **ему**». Строка под именем отвечает
    // на другой: «сколько тут живого». У Савушкина живой пункт есть, а адресат
    // у него не проставлен — и сегмент пропадал, хотя долг был виден ниже.
    val open = notes.flatMap { it.items }.filter {
        ai.prinim.prinyal.data.ItemState.of(it.state) in LIVE_STATES
    }

    LazyColumn(
        contentPadding = PaddingValues(
            start = Space.screen, end = Space.screen, top = Space.s, bottom = Space.xxl,
        ),
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MetaText(
                    // «3 записи · 2 живых · с 4 августа» (макет 13a).
                    //
                    // Сегмент «живых» заменяет собой блок «Открытые дела»:
                    // долг не теряется, но перестаёт печататься дважды — он
                    // виден там, где родился, внутри своей записи.
                    //
                    // Именно «живых», а не «в плане»: под именем стоит одно
                    // число на все незакрытые пункты, а «в плане» и «вернусь» —
                    // разные состояния фильтра, и складывать их под именем
                    // одного из них нельзя.
                    text = buildList {
                        add(pluralStringResource(R.plurals.person_notes, notes.size, notes.size))
                        if (open.isNotEmpty()) {
                            add(pluralStringResource(R.plurals.person_live, open.size, open.size))
                        }
                        person?.let {
                            add(stringResource(R.string.person_since, Dates.day(it.firstAt)))
                        }
                    }.joinToString(" · "),
                    color = Prinyal.colors.inkFaint,
                    // Строка слева ужимается, а вход в пак — нет: он два слова
                    // и в две строки читается как две команды.
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                MetaText(
                    text = stringResource(R.string.pack_build),
                    color = Prinyal.colors.accentSelf,
                    maxLines = 1,
                    modifier = Modifier.tap {
                        // Черновик пака собирался, но экран выбора никто не
                        // открывал — нажатие уходило в никуда. В разделе
                        // переход делает вызывающий, и здесь так же.
                        val name = person?.name.orEmpty()
                        vm.contextPackForPerson(personId, name)
                        onPickPack(name)
                    },
                )
            }
        }

        // Что известно — слитым абзацем и без заголовка (макет 13a).
        //
        // Список строк читается как анкета: поля, которые надо заполнить.
        // Абзац читается как знание о человеке — и не обещает, что фактов
        // должно быть больше. Заголовка нет: частей на экране две, и они
        // различимы гарнитурой — Spectral для знания, Golos для записей.
        if (facts.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text(
                        // Каждый факт — предложением: «Сестра. Живёт в
                        // Пушкино…». Слитый абзац из строчных читается как
                        // обрывок расшифровки, а не как знание о человеке.
                        text = facts.joinToString(" ") { fact ->
                            fact.text.trim().trimEnd('.')
                                .replaceFirstChar { it.uppercase() } + "."
                        },
                        style = Prinyal.type.voice,
                        color = Prinyal.colors.ink,
                    )
                    // Дата одна, последнего пополнения: дата у каждого факта
                    // превратила бы абзац в журнал.
                    MetaText(
                        stringResource(
                            R.string.person_facts_at,
                            Dates.day(facts.maxOf { it.at }),
                        ),
                        color = Prinyal.colors.inkFaint,
                    )
                }
            }
        }
        // «Рассказать» живёт всегда, а не до первого факта: про человека
        // узнают не один раз, и вход в это не должен исчезать после первого
        // же рассказа (Р-21.3).
        if (person != null) {
            // Второй вход для факта (Д-27): доспрос может не сработать вовсе, и
            // тогда рассказать о человеке негде. Тихая ссылка, не анкета.
            item {
                MetaText(
                    text = stringResource(R.string.person_tell),
                    color = Prinyal.colors.accentSelf,
                    modifier = Modifier.tap { vm.tellAbout(context, person.name, person.id) },
                )
            }
        }

        // Имя приходит из речи, и распознаётся оно с ошибками — значит его
        // надо уметь чинить, а дубль склеивать (Р-25.2–25.4). Действия внизу
        // карточки, приглушённые: это работа с записной книжкой, а не с
        // содержанием, и открывать ею человека незачем.
        person?.let { subject ->
            item {
                PersonActions(
                    name = subject.name,
                    id = subject.id,
                    others = others,
                    onRename = { vm.renamePerson(subject.id, it) },
                    onMerge = { vm.mergePeopleById(subject.id, it) },
                    onDelete = {
                        vm.deletePerson(subject.id)
                        onBack()
                    },
                )
            }
        }

        // Блока «Открытые дела» больше нет (макет 13a): дело печаталось
        // дважды — и в блоке, и внутри записи, откуда оно взялось. Теперь дела
        // показывает сама запись, языком ленты.
        if (notes.isNotEmpty()) {
            items(notes, key = { it.note.id }) { entry ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .tap { onOpenNote(entry.note.id) }
                        .padding(vertical = Space.xs),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    // Строка ленты без пунктов: первые слова и дата. Раздел не
                    // печатается — человек пришёл смотреть человека, а не
                    // структуру.
                    val live = entry.items.filter { ItemState.of(it.state) in LIVE_STATES }
                    val done = entry.items.size - live.size

                    MetaText(
                        text = listOfNotNull(
                            Dates.day(entry.note.createdAt),
                            entry.items.size.takeIf { it > 0 }?.let {
                                pluralStringResource(R.plurals.person_note_items, it, it)
                            },
                        ).joinToString(" · "),
                        color = Prinyal.colors.inkFaint,
                    )
                    Text(
                        text = ai.prinim.prinyal.domain.LinkCandidates.opening(entry.note.transcript),
                        style = Prinyal.type.label,
                        color = Prinyal.colors.ink,
                    )
                    // Живые пункты — языком ленты, закрытые — сводкой. Долг
                    // виден там, где родился, и виден один раз.
                    live.forEach { item ->
                        Column(Modifier.padding(top = Space.xs)) {
                            Text(item.text, style = Prinyal.type.label, color = Prinyal.colors.ink)
                            MetaText(
                                ai.prinim.prinyal.domain.Phrases.plan(context, item),
                                color = Prinyal.colors.accentSelf,
                            )
                        }
                    }
                    if (done > 0) {
                        MetaText(
                            stringResource(R.string.topic_summary_done, done),
                            color = Prinyal.colors.inkFaint,
                            modifier = Modifier.padding(top = Space.xs),
                        )
                    }
                }
            }
        }
    }
}

/** Заголовок блока — тот же, что на карточке записи. */
@Composable
private fun SectionTitle(text: String) {
    MetaText(
        text = text,
        color = Prinyal.colors.inkFaint,
        modifier = Modifier.padding(top = Space.s),
    )
}

private val LIVE_STATES = setOf(
    ai.prinim.prinyal.data.ItemState.PLANNED,
    ai.prinim.prinyal.data.ItemState.RETURNED,
    ai.prinim.prinyal.data.ItemState.SNOOZED,
)


/**
 * Работа с записной книжкой (Р-25.2–25.4): имя, дубль, удаление.
 *
 * Внизу карточки и приглушённо — это не про содержание, а про порядок в списке.
 * Открывать карточку человека этими словами было бы неправдой о том, зачем она.
 */
@Composable
private fun PersonActions(
    name: String,
    id: String,
    others: List<ai.prinim.prinyal.data.PersonEntity>,
    onRename: (String) -> Unit,
    onMerge: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var renaming by remember(id) { mutableStateOf<String?>(null) }
    var merging by remember(id) { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth().padding(top = Space.ml),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Prinyal.colors.hairline))

        when {
            renaming != null -> {
                val focus = remember { androidx.compose.ui.focus.FocusRequester() }
                androidx.compose.foundation.text.BasicTextField(
                    value = renaming.orEmpty(),
                    onValueChange = { renaming = it },
                    singleLine = true,
                    textStyle = Prinyal.type.itemTitle.copy(color = Prinyal.colors.ink),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(
                        Prinyal.colors.accentSelf,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Space.s)
                        .focusRequester(focus),
                )
                LaunchedEffect(Unit) { focus.requestFocus() }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.ml)) {
                    MetaText(
                        text = stringResource(R.string.person_save),
                        color = Prinyal.colors.accentSelf,
                        modifier = Modifier.tap {
                            renaming?.let(onRename)
                            renaming = null
                        },
                    )
                    MetaText(
                        text = stringResource(R.string.person_cancel),
                        color = Prinyal.colors.inkMuted,
                        modifier = Modifier.tap { renaming = null },
                    )
                }
            }

            merging -> {
                // Список имён, а не поиск: людей единицы, и выбор из семи строк
                // короче любого поля ввода.
                MetaText(
                    stringResource(R.string.person_merge_pick),
                    color = Prinyal.colors.inkFaint,
                )
                others.forEach { other ->
                    Text(
                        text = other.name,
                        style = Prinyal.type.label,
                        color = Prinyal.colors.ink,
                        modifier = Modifier
                            .fillMaxWidth()
                            .tap {
                                onMerge(other.id)
                                merging = false
                            }
                            .padding(vertical = Space.xs),
                    )
                }
                MetaText(
                    text = stringResource(R.string.person_cancel),
                    color = Prinyal.colors.inkMuted,
                    modifier = Modifier.tap { merging = false },
                )
            }

            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.ml)) {
                    MetaText(
                        text = stringResource(R.string.person_rename),
                        color = Prinyal.colors.inkMuted,
                        maxLines = 1,
                        modifier = Modifier.tap { renaming = name },
                    )
                    if (others.isNotEmpty()) {
                        MetaText(
                            text = stringResource(R.string.person_merge),
                            color = Prinyal.colors.inkMuted,
                            maxLines = 1,
                            modifier = Modifier.tap { merging = true },
                        )
                    }
                }
                // Удаление своей строкой: оно необратимо, и в ряду с правкой
                // читалось бы как равное ей (полишинг, п. 5).
                MetaText(
                    text = stringResource(R.string.person_delete),
                    color = Prinyal.colors.inkMuted,
                    maxLines = 1,
                    modifier = Modifier.tap(onClick = onDelete),
                )
                MetaText(
                    stringResource(R.string.person_delete_hint),
                    color = Prinyal.colors.inkFaint,
                )
            }
        }
    }
}
