package ai.prinim.prinyal.ui

import ai.prinim.prinyal.R
import ai.prinim.prinyal.data.PersonOverview
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
) {
    val notes by vm.notesOfPerson(personId).collectAsState(initial = emptyList())
    val people by vm.people.collectAsState()
    val person = people.firstOrNull { it.id == personId }
    val context = androidx.compose.ui.platform.LocalContext.current

    // Открытые дела — только живые пункты, где человек назван адресатом.
    // Закрытые сюда не попадают: для них есть фильтр в ленте.
    val open = notes.flatMap { it.items }.filter {
        ai.prinim.prinyal.data.ItemState.of(it.state) in LIVE_STATES &&
            person != null && !it.who.isNullOrBlank() &&
            ai.prinim.prinyal.domain.PersonIdentity.norm(it.who!!) ==
            ai.prinim.prinyal.domain.PersonIdentity.norm(person.name)
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
                    text = pluralStringResource(
                        R.plurals.person_notes, notes.size, notes.size,
                    ) + (person?.let { " · " + stringResource(R.string.person_since, Dates.day(it.firstAt)) }.orEmpty()),
                    color = Prinyal.colors.inkFaint,
                )
                MetaText(
                    text = stringResource(R.string.pack_build),
                    color = Prinyal.colors.accentSelf,
                    modifier = Modifier.tap {
                        vm.contextPackForPerson(personId, person?.name.orEmpty())
                    },
                )
            }
        }

        // Что известно — только когда известно.
        person?.fact?.takeIf { it.isNotBlank() }?.let { fact ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    MetaText(stringResource(R.string.person_known), color = Prinyal.colors.inkFaint)
                    Text(fact, style = Prinyal.type.voice, color = Prinyal.colors.ink)
                }
            }
        }
        if (person != null && person.fact.isNullOrBlank()) {
            // Второй вход для факта (Д-27): доспрос может не сработать вовсе, и
            // тогда рассказать о человеке негде. Тихая ссылка, не анкета.
            item {
                MetaText(
                    text = stringResource(R.string.person_tell),
                    color = Prinyal.colors.accentSelf,
                    modifier = Modifier.tap { vm.tellAbout(context, person.name) },
                )
            }
        }

        if (open.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.person_open)) }
            items(open, key = { it.id }) { item ->
                Column(Modifier.fillMaxWidth().padding(vertical = Space.xs)) {
                    Text(item.text, style = Prinyal.type.label, color = Prinyal.colors.ink)
                    MetaText(
                        ai.prinim.prinyal.domain.Phrases.plan(context, item),
                        color = Prinyal.colors.accentSelf,
                    )
                }
            }
        }

        if (notes.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.person_notes_title)) }
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
                    Text(
                        text = ai.prinim.prinyal.domain.LinkCandidates.opening(entry.note.transcript),
                        style = Prinyal.type.label,
                        color = Prinyal.colors.ink,
                    )
                    MetaText(Dates.day(entry.note.createdAt), color = Prinyal.colors.inkFaint)
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
