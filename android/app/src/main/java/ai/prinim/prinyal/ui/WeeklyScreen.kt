package ai.prinim.prinyal.ui

import ai.prinim.prinyal.R
import ai.prinim.prinyal.domain.StructureRepair
import ai.prinim.prinyal.domain.WeeklyFacts
import ai.prinim.prinyal.domain.WeeklySummary
import ai.prinim.prinyal.ui.theme.MetaText
import ai.prinim.prinyal.ui.theme.Prinyal
import ai.prinim.prinyal.ui.theme.tap
import ai.prinim.prinyal.ui.theme.Radius
import ai.prinim.prinyal.ui.theme.Space
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material3.Text
import ai.prinim.prinyal.domain.Dates
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/**
 * «Неделя»: что закрыто, и как себя чувствует петля.
 *
 * Экран переписан по Р-13.1. Раньше он выносил «Провал» красным, когда человек
 * ответил меньше чем на половину возвратов. Считалось это как kill-критерий
 * продукта — жива ли петля, — но читалось как оценка человека после трудной
 * недели, и владелец справедливо назвал это жёстким.
 *
 * Теперь здесь три правила:
 *
 *  - **судим петлю, а не человека.** Причина всегда сформулирована как то, что
 *    продукт сделает иначе: не «ты не отвечаешь», а «я приходил не вовремя»;
 *  - **красного вердикта о человеке нет.** `FAIL` на этом экране выглядит так
 *    же, как `WARN`: разница между ними важна для решения о судьбе продукта, а
 *    не для того, кто открыл экран в пятницу вечером;
 *  - **сделанное — числом, без процентов, полос и цели.** Полосы к порогам —
 *    инструмент владельца, они уехали в «Для разработчика».
 *
 * Серий («7 дней подряд») здесь нет и не будет: сорванная серия отваживает
 * сильнее, чем собранная мотивирует.
 */
@Composable
fun WeeklyScreen(vm: AppViewModel, onOpenDays: () -> Unit = {}) {
    val report by vm.weekly.collectAsState()
    val facts by vm.weeklyFacts.collectAsState()
    val structure by vm.structure.collectAsState()

    LaunchedEffect(Unit) { vm.loadWeekly() }

    val data = report
    if (data == null) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            MetaText(stringResource(R.string.week_no_data))
        }
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.screen),
        verticalArrangement = Arrangement.spacedBy(Space.ml),
    ) {
        // Итог недели (Р-18.3) — первым: он про жизнь человека, «За неделю»
        // ниже — про работу продукта. Два блока продукта на одном экране —
        // первый такой случай, разводятся меткой и предметом, не цветом (12e).
        val recap by vm.weekRecap.collectAsState()
        val weekDays by vm.weekDays.collectAsState()
        recap?.let { WeekRecapBlock(it.text, weekDays, onOpenDays) }

        // Экран недели — блок продукта, а не текст на фоне: он рассказывает от
        // своего лица, и это должно быть видно так же, как у «Собрано»
        // (аудит Д-7, п. 6).
        Column(
            Modifier
                .fillMaxWidth()
                .background(Prinyal.colors.wellSurface, Radius.control)
                .padding(Space.m),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            MetaText(stringResource(R.string.week_block_label), color = Prinyal.colors.accentSelf)
            // Вердикт «Пока нечего рассказать» и наблюдения под ним — прямое
            // противоречие: экран говорит, что рассказать нечего, и тут же
            // рассказывает. Пока данных мало, вердикт объясняет пустоту; как
            // только наблюдения появились, объяснять нечего.
            if (facts.isEmpty() || data.verdict != WeeklySummary.Verdict.EARLY) {
                Verdict(data)
            }

            // Сделанное показываем, только когда оно есть. Крупный ноль был
            // единственным числом на экране: продукт большим кеглем сообщал
            // человеку, что тот не сделал ничего.
            if (data.done > 0) Done(data.done)

            // Наблюдения (Р-15.9). Их может не быть вовсе — тогда экран
            // честно короткий. Натянуть факт на пустую неделю значит начать
            // врать в мелочи, а верят продукту целиком.
            facts.forEach { fact ->
                Text(
                    text = factText(fact),
                    style = Prinyal.type.body,
                    color = Prinyal.colors.inkMuted,
                )
            }

            // «Рассказано вечеров: N» — наблюдение без укора (12e). Стоит,
            // только когда вечера были, но на итог их не хватило: при готовом
            // итоге число видно по его источникам.
            val toldEvenings = weekDays.count { !it.transcript.isNullOrBlank() }
            if (recap == null && toldEvenings > 0) {
                Text(
                    text = stringResource(R.string.week_told_evenings) + ": $toldEvenings",
                    style = Prinyal.type.body,
                    color = Prinyal.colors.inkMuted,
                )
            }
        }

        // Предложение починить структуру (Р-15.12). Под фактами, а не над ними:
        // это просьба поработать, и открывать ею неделю невежливо.
        //
        // Блок однажды уже пропадал: переменную собирали, а в разметку не
        // ставили — Kotlin молчит, потому что делегат считается использованным,
        // и фича просто не показывалась.
        structure?.let { offer -> StructureOffer(vm, offer) }

        Box(Modifier.height(Space.xl))
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
}

@Composable
private fun Done(count: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text(
            text = stringResource(R.string.week_metric_done),
            style = Prinyal.type.body,
            color = Prinyal.colors.inkMuted,
        )
        Text(
            text = count.toString(),
            style = Prinyal.type.metric,
            color = Prinyal.colors.ink,
        )
    }
}

@Composable
private fun Verdict(report: WeeklySummary.Report) {
    val (text, color) = when (report.verdict) {
        WeeklySummary.Verdict.EARLY ->
            stringResource(R.string.week_verdict_early) to Prinyal.colors.inkMuted
        WeeklySummary.Verdict.ALIVE ->
            stringResource(R.string.week_verdict_alive) to Prinyal.colors.statusOk
        // FAIL и WARN здесь неразличимы намеренно: «Провал» — слово для решения
        // о судьбе продукта, оно живёт в «Для разработчика». Человеку остаётся
        // «буксует» — состояние петли, а не оценка его недели.
        WeeklySummary.Verdict.WARN, WeeklySummary.Verdict.FAIL ->
            stringResource(R.string.week_verdict_stalling) to Prinyal.colors.statusWarn
    }

    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Text(text, style = Prinyal.type.verdict, color = color)
        Text(
            text = phrase(report),
            style = Prinyal.type.body,
            color = Prinyal.colors.inkMuted,
        )
    }
}

@Composable
private fun phrase(report: WeeklySummary.Report): String = when {
    report.verdict == WeeklySummary.Verdict.EARLY -> stringResource(R.string.week_early_note)
    report.problem == WeeklySummary.Problem.DAYS -> stringResource(R.string.week_phrase_days)
    report.problem == WeeklySummary.Problem.RETURNS -> stringResource(R.string.week_phrase_returns)
    report.problem == WeeklySummary.Problem.LUMP -> stringResource(R.string.week_phrase_lump)
    else -> stringResource(R.string.week_phrase_ok)
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

    Column(
        Modifier
            .fillMaxWidth()
            .background(Prinyal.colors.wellSurface, Radius.control)
            .padding(Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Text(text, style = Prinyal.type.body, color = Prinyal.colors.ink)
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
                .background(Prinyal.colors.paper, Radius.control)
                .padding(horizontal = Space.sm, vertical = Space.s),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Space.ml)) {
            Text(
                text = stringResource(R.string.repair_yes),
                style = Prinyal.type.label,
                color = if (name.isBlank()) Prinyal.colors.inkFaint else Prinyal.colors.accentSelf,
                modifier = Modifier.tap(enabled = name.isNotBlank()) {
                    vm.acceptStructure(name)
                },
            )
            Text(
                text = stringResource(R.string.repair_no),
                style = Prinyal.type.label,
                color = Prinyal.colors.inkMuted,
                modifier = Modifier.tap { vm.refuseStructure() },
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
    days: List<ai.prinim.prinyal.data.DayEntity>,
    onOpenDays: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Prinyal.colors.wellSurface, Radius.control)
            .padding(Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        MetaText(stringResource(R.string.week_recap_heading), color = Prinyal.colors.accentSelf)
        Text(text = text, style = Prinyal.type.voice, color = Prinyal.colors.ink)

        val told = days.filter { !it.line.isNullOrBlank() || !it.transcript.isNullOrBlank() }
        if (told.isNotEmpty()) {
            HorizontalDivider(thickness = 1.dp, color = Prinyal.colors.hairline)
            told.forEach { day ->
                // Тап по источнику ведёт в «Дни»: строка обещает, что за ней
                // стоит день, и обещание должно куда-то вести.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Space.m),
                    modifier = Modifier.tap(onClick = onOpenDays),
                ) {
                    MetaText(
                        text = Dates.day(java.time.LocalDate.parse(day.date)),
                        color = Prinyal.colors.inkFaint,
                        maxLines = 1,
                        modifier = Modifier.width(64.dp),
                    )
                    Text(
                        text = day.line ?: day.transcript.orEmpty(),
                        style = Prinyal.type.body.copy(fontSize = 15.sp),
                        color = Prinyal.colors.inkMuted,
                    )
                }
            }
        }
    }
}
