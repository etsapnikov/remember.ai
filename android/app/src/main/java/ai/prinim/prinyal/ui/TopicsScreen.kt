package ai.prinim.prinyal.ui

import ai.prinim.prinyal.R
import ai.prinim.prinyal.data.TopicOverview
import ai.prinim.prinyal.ui.components.ListRow
import ai.prinim.prinyal.ui.theme.MetaText
import ai.prinim.prinyal.ui.theme.Sizes
import ai.prinim.prinyal.ui.theme.Prinyal
import ai.prinim.prinyal.ui.theme.Space
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * «Разделы» — третья поверхность (Д-1).
 *
 * Имя и счёт, больше ничего: ни иконок, ни цветных меток, ни кнопки «создать».
 * Раздел рождается из речи или из пикера на карточке — «впрок» его завести
 * нельзя, и это главное отличие от менеджера заметок.
 *
 * Пустых разделов в списке не бывает: запрос их не возвращает, потому что
 * раздела без заметок в продукте не существует.
 */
@Composable
fun TopicsScreen(
    vm: AppViewModel,
    onOpen: (String?, String) -> Unit,
    onPeople: () -> Unit = {},
) {
    val topics by vm.topics.collectAsState()
    val loose by vm.looseNotes.collectAsState()
    val people by vm.people.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.ensurePeopleBackfilled() }

    if (topics.isEmpty() && loose == 0 && people.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Column(
                Modifier.padding(horizontal = Space.screen),
                verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                Text(
                    text = stringResource(R.string.topics_empty_title),
                    style = Prinyal.type.itemTitle,
                    color = Prinyal.colors.ink,
                )
                // Ни кнопки «создать раздел», ни объяснения правил: продукт
                // обещает разложить сам, а не просит настроить структуру.
                Text(
                    text = stringResource(R.string.topics_empty_body),
                    style = Prinyal.type.voice,
                    color = Prinyal.colors.inkMuted,
                )
            }
        }
        return
    }

    // Предложение починить структуру (Р-15.12) жило на «Неделе»; с уходом экрана
    // в 1.5 оно осталось бы без дома. Здесь ему место по смыслу (бриф 1.5, Д-49):
    // человек смотрит на разделы — и продукт предлагает разделы.
    val structure by vm.structure.collectAsState()
    LaunchedEffect(Unit) { vm.loadStructure() }

    LazyColumn(
        // Боковые поля несёт строка, а не список: иначе они складывались с
        // полями ListRow в 40, и разделитель обрывался за 20 dp до края.
        contentPadding = PaddingValues(top = Space.s, bottom = Space.xxl),
        modifier = Modifier.fillMaxSize(),
    ) {
        structure?.let { offer ->
            item(key = "structure") {
                Box(Modifier.padding(horizontal = Space.screen, vertical = Space.s)) {
                    StructureOffer(vm, offer)
                }
            }
        }
        // Выборки — наверху, до разделов (Д-26): их всегда две-три, а разделов
        // со временем станет десять, и внизу выборки уезжали бы за край.
        // «Без раздела» при этом обязан оставаться последним.
        //
        // Пустая выборка не показывается вовсе — правило разделов без
        // исключений: пока людей меньше двух, строки «Люди» в списке нет.
        if (people.isNotEmpty()) {
            item {
                val name = stringResource(R.string.topics_people)
                TopicRow(
                    name = name,
                    // Счётчик считает людей, а не записи: он обязан совпадать с
                    // тем, что человек увидит после тапа (Д-26).
                    notes = people.size,
                    liveItems = 0,
                    countsLive = false,
                    unit = pluralStringResource(R.plurals.people_count, people.size, people.size),
                    onClick = { onPeople() },
                )
                HorizontalDivider(thickness = 1.dp, color = Prinyal.colors.hairline)
            }
        }

        // «Решения» — не раздел, а выборка (Р-15.10): решение о релизе остаётся
        // в «Работе», а здесь видна их хронология. Появляется, только когда
        // решения есть: пустая строка обещала бы содержимое, которого нет.
        // Строки «Решения» здесь больше нет (Р-19.2).
        //
        // Выборка собиралась по `note_kind = decision` — то есть по догадке
        // модели о том, что запись «про решение». Раздел из догадки о жанре
        // наполнялся то пусто, то мимо, а решение, произнесённое внутри
        // обычной записи, туда не попадало вовсе. Владелец назвал механику
        // избыточной, и это верно: решения живут там же, где сказаны, и
        // находятся поиском по своим словам.

        items(topics, key = { it.id }) { topic ->
            TopicRow(
                name = topic.name,
                notes = topic.notes,
                liveItems = topic.liveItems,
                onClick = { onOpen(topic.id, topic.name) },
            )
            HorizontalDivider(thickness = 1.dp, color = Prinyal.colors.hairline)
        }

        // «Без раздела» — не раздел, а остаток: всегда внизу и приглушён.
        if (loose > 0) {
            item {
                TopicRow(
                    name = stringResource(R.string.topics_loose),
                    notes = loose,
                    liveItems = 0,
                    // Живые пункты здесь не считаются, поэтому и не заявляются:
                    // «всё закрыто» на этой строке было неправдой — заметка без
                    // раздела прекрасно может ждать своего часа.
                    countsLive = false,
                    muted = true,
                    onClick = { onOpen(null, "") },
                )
            }
        }
    }
}

@Composable
private fun TopicRow(
    name: String,
    notes: Int,
    liveItems: Int,
    countsLive: Boolean = true,
    /** Своя форма счётчика: у «Людей» считаются люди, а не записи (Д-26). */
    unit: String? = null,
    muted: Boolean = false,
    onClick: () -> Unit,
) {
    val notesText = unit ?: pluralStringResource(R.plurals.topics_notes, notes, notes)
    ListRow(
        title = name,
        // «Живых» — про невыполненные пункты: слово уже есть в речи продукта
        // про возвраты, второго термина заводить незачем. Ноль живых — это
        // «всё закрыто», а не отсутствие данных: раньше сегмент просто пропадал,
        // и строка выглядела недосчитанной.
        counter = if (!countsLive) {
            notesText
        } else if (liveItems > 0) {
            "$notesText · ${pluralStringResource(R.plurals.topics_live, liveItems, liveItems)}"
        } else {
            "$notesText · ${stringResource(R.string.topics_all_closed)}"
        },
        // Раздел — 68, человек — 58 (ТЗ §4). До 1.3 обе строки считали высоту
        // от собственного текста, и «Люди» с «Отдых» на одном экране стояли
        // на разной высоте при одинаковой роли.
        height = if (unit != null) Sizes.rowPerson else Sizes.rowTopic,
        titleColor = if (muted) Prinyal.colors.inkFaint else Prinyal.colors.ink,
        onClick = onClick,
    )
}

/**
 * «В „Идеях" пять записей про маркдаун — выделить раздел?» (Р-15.12).
 *
 * Два действия и ни одного третьего: «потом» здесь означало бы, что продукт
 * спросит снова, а он не спросит — отказ закрывает тему на месяц. Имя раздела
 * подставлено словом, которым группа держится, и его можно поправить: продукт
 * нашёл группу, но как её назвать — знает человек.
 */
@Composable
private fun StructureOffer(vm: AppViewModel, offer: ai.prinim.prinyal.domain.StructureRepair.Offer) {
    val suggested = when (offer) {
        is ai.prinim.prinyal.domain.StructureRepair.Offer.Split -> offer.word
        is ai.prinim.prinyal.domain.StructureRepair.Offer.Gather -> offer.word
    }.replaceFirstChar { it.uppercase() }
    var name by androidx.compose.runtime.remember(offer) { androidx.compose.runtime.mutableStateOf(suggested) }

    val text = when (offer) {
        is ai.prinim.prinyal.domain.StructureRepair.Offer.Split -> pluralStringResource(
            R.plurals.repair_split, offer.noteIds.size, offer.noteIds.size, offer.topicName,
        )
        is ai.prinim.prinyal.domain.StructureRepair.Offer.Gather ->
            pluralStringResource(R.plurals.repair_gather, offer.noteIds.size, offer.noteIds.size)
    }

    ai.prinim.prinyal.ui.components.SurfaceCard(shape = ai.prinim.prinyal.ui.theme.Radius.cardLarge) {
        Text(text, style = Prinyal.type.body, color = Prinyal.colors.ink)
        Box(Modifier.height(Space.sm))
        // Поле без подложки неотличимо от заголовка: человек не догадается, что
        // имя раздела можно поправить, и примет предложенное слово как данность.
        androidx.compose.foundation.text.BasicTextField(
            value = name,
            onValueChange = { name = it.take(24) },
            singleLine = true,
            textStyle = Prinyal.type.itemTitle.copy(color = Prinyal.colors.ink),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Prinyal.colors.accentSelf),
            modifier = Modifier
                .fillMaxWidth()
                .background(Prinyal.colors.paper, ai.prinim.prinyal.ui.theme.Radius.card)
                .padding(horizontal = Space.sm, vertical = Space.s),
        )
        Box(Modifier.height(Space.s))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            ai.prinim.prinyal.ui.components.TertiaryButton(
                text = stringResource(R.string.repair_yes),
                onClick = { vm.acceptStructure(name) },
                enabled = name.isNotBlank(),
                color = if (name.isBlank()) Prinyal.colors.inkFaint else Prinyal.colors.accentSelf,
            )
            ai.prinim.prinyal.ui.components.TertiaryButton(
                text = stringResource(R.string.repair_no),
                onClick = { vm.refuseStructure() },
                color = Prinyal.colors.inkMuted,
            )
        }
    }
}
