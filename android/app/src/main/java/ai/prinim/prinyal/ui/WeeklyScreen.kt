package ai.prinim.prinyal.ui

import ai.prinim.prinyal.R
import ai.prinim.prinyal.data.DayEntity
import ai.prinim.prinyal.domain.Dates
import ai.prinim.prinyal.domain.StructureRepair
import ai.prinim.prinyal.domain.WeeklyFacts
import ai.prinim.prinyal.ui.components.Divider
import ai.prinim.prinyal.ui.components.EmptyState
import ai.prinim.prinyal.ui.components.GroupHeader
import ai.prinim.prinyal.ui.components.SurfaceCard
import ai.prinim.prinyal.ui.components.TertiaryButton
import ai.prinim.prinyal.ui.theme.MetaText
import ai.prinim.prinyal.ui.theme.Prinyal
import ai.prinim.prinyal.ui.theme.Radius
import ai.prinim.prinyal.ui.theme.Sizes
import ai.prinim.prinyal.ui.theme.Space
import ai.prinim.prinyal.ui.theme.tap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * «Неделя» 1.4 (scope-1_4_0.md): экран про неделю человека, а не про продукт.
 *
 * До 1.4 здесь стоял вердикт kill-критериев — «Петля буксует», «Мои возвраты
 * чаще всего проходят мимо». Это продукт признавался, что не работает, и не
 * предлагал человеку ничего сделать. У экрана не было глагола. Kill-критерии
 * были правильным вопросом в первую неделю; на 121 записи он отвечен, и
 * вердикт ушёл туда, где ему место с самого начала — в «Для разработчика».
 *
 * Три блока сверху вниз, порядок постоянный:
 *
 *  1. **Итог недели** — прозой, из вечерних ответов. Есть, когда собран.
 *  2. **Наблюдения** — факты, посчитанные кодом (не моделью — см. [WeeklyFacts]).
 *  3. **Разобрать** — живые пункты, принесённые до понедельника, от старого к
 *     новому, десять видимых. Три действия — те же, что у возврата.
 *
 * Разобрал всё — экран говорит «Разобрано». Это единственное место в
 * продукте, где неделя закрывается как действие, а не как календарная граница.
 *
 * Серий («7 дней подряд») здесь нет и не будет: сорванная серия отваживает
 * сильнее, чем собранная мотивирует.
 */
@Composable
fun WeeklyScreen(vm: AppViewModel, onOpenDays: () -> Unit = {}) {
    val facts by vm.weeklyFacts.collectAsState()
    val structure by vm.structure.collectAsState()
    val recap by vm.weekRecap.collectAsState()
    val weekDays by vm.weekDays.collectAsState()
    val hanging by vm.hanging.collectAsState()

    LaunchedEffect(Unit) { vm.loadWeekly() }

    val shown = hanging.take(TRIAGE_VISIBLE)
    val rest = hanging.size - shown.size

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = Space.s, bottom = Space.xxl),
    ) {
        recap?.let { entity ->
            item(key = "recap") {
                Box(Modifier.padding(horizontal = Space.screen, vertical = Space.s)) {
                    WeekRecapBlock(entity.text, weekDays, onOpenDays)
                }
            }
        }

        if (facts.isNotEmpty()) {
            item(key = "facts") {
                Box(Modifier.padding(horizontal = Space.screen, vertical = Space.s)) {
                    SurfaceCard(shape = Radius.cardLarge) {
                        GroupHeader(
                            text = stringResource(R.string.week_facts_heading),
                            divider = false,
                        )
                        // Наблюдений может не быть вовсе — тогда блока нет.
                        // Натянуть факт на пустую неделю значит начать врать в
                        // мелочи, а верят продукту целиком.
                        facts.forEachIndexed { index, fact ->
                            if (index > 0) Box(Modifier.height(Space.s))
                            Text(
                                text = factText(fact),
                                style = Prinyal.type.body,
                                color = Prinyal.colors.inkMuted,
                            )
                        }
                    }
                }
            }
        }

        item(key = "triage-header") {
            GroupHeader(
                text = stringResource(R.string.week_triage_heading),
                modifier = Modifier.padding(horizontal = Space.screen, vertical = Space.s),
            )
        }

        if (hanging.isEmpty()) {
            item(key = "triage-empty") {
                // Два разных «пусто». Нечего разбирать при пустом экране —
                // неделя просто идёт; нечего разбирать под итогом или фактами —
                // это результат, и он назван.
                if (recap == null && facts.isEmpty()) {
                    EmptyState(
                        title = stringResource(R.string.week_empty_title),
                        explain = stringResource(R.string.week_empty_body),
                    )
                } else {
                    EmptyState(
                        title = stringResource(R.string.week_triage_done_title),
                        explain = stringResource(R.string.week_triage_done_body),
                    )
                }
            }
        } else {
            items(shown, key = { "hang-${it.item.id}" }) { row ->
                HangingRow(
                    row = row,
                    onDone = { vm.markDone(row.item.id) },
                    onLater = { vm.later(row.item.id) },
                    onDismiss = { vm.dismiss(row.item.id) },
                )
            }
            if (rest > 0) {
                item(key = "triage-more") {
                    Divider()
                    // Десять видимых, не сорок семь: простыня в воскресенье
                    // вечером — это укор, десять — работа на пять минут, и
                    // она кончается. Список реактивный: закрыл — подъехал следующий.
                    MetaText(
                        text = stringResource(R.string.week_triage_more, rest),
                        color = Prinyal.colors.inkFaint,
                        modifier = Modifier.padding(horizontal = Space.screen, vertical = Space.sm),
                    )
                }
            }
        }

        // Предложение починить структуру (Р-15.12) — последним: это просьба
        // поработать, и открывать ею неделю невежливо. Вопрос о его месте на
        // этом экране открыт (ТЗ дизайнеру 1.4, Д-49).
        structure?.let { offer ->
            item(key = "structure") {
                Box(Modifier.padding(horizontal = Space.screen, vertical = Space.s)) {
                    StructureOffer(vm, offer)
                }
            }
        }
    }
}

/** Сколько висящих пунктов видно сразу (scope-1_4_0.md). */
private const val TRIAGE_VISIBLE = 10

/**
 * Строка разбора: текст пункта, сколько ждёт, три действия.
 *
 * Действия — те же три слова, что у возврата, теми же функциями репозитория.
 * Ничего нового в данных; новое — только место, где это делается пачкой.
 */
@Composable
private fun HangingRow(
    row: AppViewModel.Hanging,
    onDone: () -> Unit,
    onLater: () -> Unit,
    onDismiss: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val days = ChronoUnit.DAYS.between(
        Instant.ofEpochMilli(row.note.createdAt).atZone(zone).toLocalDate(),
        LocalDate.now(zone),
    ).toInt().coerceAtLeast(1)

    Column(Modifier.fillMaxWidth()) {
        Divider()
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.screen)
                .padding(top = Space.s14, bottom = Space.s),
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            Text(
                text = row.item.text,
                style = Prinyal.type.itemTitle,
                color = Prinyal.colors.ink,
            )
            MetaText(
                text = pluralStringResource(R.plurals.week_waiting_days, days, days),
                color = Prinyal.colors.inkFaint,
            )
        }
        Row(
            Modifier.padding(horizontal = Space.screen - Space.sm).padding(bottom = Space.xs),
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            TertiaryButton(
                text = stringResource(R.string.action_done),
                onClick = onDone,
                color = Prinyal.colors.done,
            )
            TertiaryButton(
                text = stringResource(R.string.action_later),
                onClick = onLater,
                color = Prinyal.colors.ink,
            )
            TertiaryButton(
                text = stringResource(R.string.action_dismiss),
                onClick = onDismiss,
                color = Prinyal.colors.inkMuted,
            )
        }
    }
}

/**
 * Слова факта. Здесь только перевод в строку — что рассказывать, решил
 * [WeeklyFacts], и решил по данным.
 */
@Composable
private fun factText(fact: WeeklyFacts.Fact): String = when (fact) {
    is WeeklyFacts.Fact.ClosedOld ->
        pluralStringResource(R.plurals.week_fact_closed_old, fact.count, fact.count)
    is WeeklyFacts.Fact.TopicMoved ->
        pluralStringResource(R.plurals.week_fact_topic_moved, fact.days, fact.days, fact.topic)
    is WeeklyFacts.Fact.TopicRepeated ->
        pluralStringResource(R.plurals.week_fact_topic_repeated, fact.notes, fact.notes, fact.topic)
    is WeeklyFacts.Fact.OldestWaiting ->
        pluralStringResource(R.plurals.week_fact_oldest, fact.days, fact.days)
    is WeeklyFacts.Fact.Dropped ->
        pluralStringResource(R.plurals.week_fact_dropped, fact.count, fact.count)
    // Доля названа словами, а не процентом: процент человек начнёт держать.
    is WeeklyFacts.Fact.Kept -> stringResource(
        R.string.week_fact_kept,
        fact.brought,
        pluralStringResource(R.plurals.week_fact_hanging, fact.hanging, fact.hanging),
    )
    is WeeklyFacts.Fact.Grown ->
        pluralStringResource(R.plurals.week_fact_grown, fact.count, fact.count)
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
private fun StructureOffer(vm: AppViewModel, offer: StructureRepair.Offer) {
    val suggested = when (offer) {
        is StructureRepair.Offer.Split -> offer.word
        is StructureRepair.Offer.Gather -> offer.word
    }.replaceFirstChar { it.uppercase() }
    var name by remember(offer) { mutableStateOf(suggested) }

    val text = when (offer) {
        is StructureRepair.Offer.Split -> pluralStringResource(
            R.plurals.repair_split, offer.noteIds.size, offer.noteIds.size, offer.topicName,
        )
        is StructureRepair.Offer.Gather ->
            pluralStringResource(R.plurals.repair_gather, offer.noteIds.size, offer.noteIds.size)
    }

    SurfaceCard(shape = Radius.cardLarge) {
        Text(text, style = Prinyal.type.body, color = Prinyal.colors.ink)
        Box(Modifier.height(Space.sm))
        // Поле без подложки неотличимо от заголовка: человек не догадается, что
        // имя раздела можно поправить, и примет предложенное слово как данность.
        BasicTextField(
            value = name,
            onValueChange = { name = it.take(24) },
            singleLine = true,
            textStyle = Prinyal.type.itemTitle.copy(color = Prinyal.colors.ink),
            cursorBrush = SolidColor(Prinyal.colors.accentSelf),
            modifier = Modifier
                .fillMaxWidth()
                .background(Prinyal.colors.paper, Radius.card)
                .padding(horizontal = Space.sm, vertical = Space.s),
        )
        Box(Modifier.height(Space.s))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            TertiaryButton(
                text = stringResource(R.string.repair_yes),
                onClick = { vm.acceptStructure(name) },
                enabled = name.isNotBlank(),
                color = if (name.isBlank()) Prinyal.colors.inkFaint else Prinyal.colors.accentSelf,
            )
            TertiaryButton(
                text = stringResource(R.string.repair_no),
                onClick = { vm.refuseStructure() },
                color = Prinyal.colors.inkMuted,
            )
        }
    }
}

/**
 * Итог недели (Р-18.3, макет 12e): сводка словами человека и дни-источники.
 *
 * Сводка без источников в этом продукте не ходит: под текстом — все отвеченные
 * дни, теми же строками, что в «Днях», кеглем на ступень ниже.
 */
@Composable
private fun WeekRecapBlock(
    text: String,
    days: List<DayEntity>,
    onOpenDays: () -> Unit,
) {
    SurfaceCard(shape = Radius.cardLarge) {
        GroupHeader(text = stringResource(R.string.week_recap_heading), divider = false)
        Text(text = text, style = Prinyal.type.voice, color = Prinyal.colors.ink)

        val told = days.filter { !it.line.isNullOrBlank() || !it.transcript.isNullOrBlank() }
        if (told.isNotEmpty()) {
            Box(Modifier.height(Space.sm))
            Divider()
            Box(Modifier.height(Space.sm))
            told.forEach { day ->
                // Тап по источнику ведёт в «Дни»: строка обещает, что за ней
                // стоит день, и обещание должно куда-то вести.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Space.m),
                    modifier = Modifier.tap(onClick = onOpenDays),
                ) {
                    MetaText(
                        text = Dates.day(LocalDate.parse(day.date)),
                        color = Prinyal.colors.inkFaint,
                        maxLines = 1,
                        modifier = Modifier.width(Sizes.dayDateColumn),
                    )
                    Text(
                        text = day.line ?: day.transcript.orEmpty(),
                        style = Prinyal.type.hintSecondary,
                        color = Prinyal.colors.inkMuted,
                    )
                }
            }
        }
    }
}
