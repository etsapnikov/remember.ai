package ai.prinim.prinyal.ui

import ai.prinim.prinyal.BuildConfig
import ai.prinim.prinyal.R
import ai.prinim.prinyal.capture.SilenceWindow
import ai.prinim.prinyal.data.Window
import ai.prinim.prinyal.domain.WeeklySummary
import ai.prinim.prinyal.returns.ReturnScheduler
import ai.prinim.prinyal.returns.ReturnDiag
import ai.prinim.prinyal.ui.theme.MetaText
import ai.prinim.prinyal.ui.theme.Prinyal
import ai.prinim.prinyal.ui.theme.Radius
import ai.prinim.prinyal.ui.theme.Space
import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.ZoneId

/**
 * Настройки (спека R1.1 §5). Порядок секций — по частоте использования; заголовок
 * секции никогда не повторяет подпись элемента внутри неё (правило после трёх
 * вычищенных дублей).
 *
 * Секции «Сервер» на виду больше нет: серверный контур жив в коде, но доступен
 * только из «Для разработчика», и в release-сборке секция скрыта целиком.
 */
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val llm by vm.llmEnabled.collectAsState()
    val windows by vm.windows.collectAsState()
    val threshold by vm.silenceThreshold.collectAsState()
    val patience by vm.silencePatience.collectAsState()
    val message by vm.message.collectAsState()
    val notes by vm.feed.collectAsState()

    val scheduler = remember { ReturnScheduler(context) }
    var editingWindow by remember { mutableStateOf<Window?>(null) }

    LazyColumn(
        state = rememberLazyListState(),
        contentPadding = PaddingValues(
            start = Space.screen, end = Space.screen, top = Space.s, bottom = Space.xxl,
        ),
        verticalArrangement = Arrangement.spacedBy(Space.xl),
    ) {
        item {
            Section(stringResource(R.string.settings_windows)) {
                WindowRow(stringResource(R.string.settings_window_morning), windows.morning) {
                    editingWindow = Window.MORNING
                }
                WindowRow(stringResource(R.string.settings_window_day), windows.day) {
                    editingWindow = Window.DAY
                }
                WindowRow(stringResource(R.string.settings_window_evening), windows.evening) {
                    editingWindow = Window.EVENING
                }
                WindowRow(stringResource(R.string.settings_window_weekend), windows.weekend) {
                    editingWindow = Window.WEEKEND
                }
            }
        }

        item {
            Section(stringResource(R.string.settings_capture)) {
                MetaText(stringResource(R.string.set_silence_title))
                SilenceSegments(current = threshold, onSelect = { vm.setSilenceThreshold(it) })

                // Две разные величины и две разные ручки: порог — про шум вокруг,
                // терпение — про то, как человек говорит. Смешивать их в одну
                // значило бы просить настроить микрофон, чтобы получить паузу.
                MetaText(stringResource(R.string.set_patience_title))
                PatienceSegments(current = patience, onSelect = { vm.setSilencePatience(it) })
                Text(
                    text = stringResource(R.string.set_silence_note),
                    style = Prinyal.type.body,
                    color = Prinyal.colors.inkMuted,
                )
            }
        }

        item {
            Section(stringResource(R.string.retro_title)) {
                var loose by remember { mutableStateOf(0) }
                LaunchedEffect(Unit) { loose = vm.looseCountNow() }

                // Счёт и цена — до запуска, а не после: прогон стоит секунд и
                // денег, и решение принимает владелец, а не кнопка.
                Text(
                    // Минуты, а не секунды: «около 688 секунд» — не то, как
                    // человек считает время. 16 с на запись — медиана замера.
                    text = stringResource(
                        R.string.retro_note,
                        loose,
                        ((loose * 16) / 60).coerceAtLeast(1),
                    ),
                    style = Prinyal.type.body,
                    color = Prinyal.colors.inkMuted,
                )
                if (loose > 0) {
                    Box(
                        Modifier
                            .background(Prinyal.colors.accentSelf, Radius.pill)
                            .clickable { vm.retroClassify() }
                            .padding(horizontal = Space.ml, vertical = Space.sm),
                    ) {
                        Text(
                            text = stringResource(R.string.retro_run),
                            style = Prinyal.type.label,
                            color = Prinyal.colors.paper,
                        )
                    }
                }
            }
        }

        item {
            Section(stringResource(R.string.dict_title)) {
                Text(
                    text = stringResource(R.string.dict_note),
                    style = Prinyal.type.body,
                    color = Prinyal.colors.inkMuted,
                )
                val rules by vm.replacements.collectAsState()
                if (rules.isEmpty()) {
                    MetaText(stringResource(R.string.dict_empty), color = Prinyal.colors.inkFaint)
                }
                rules.forEach { rule ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${rule.fromPhrase} → ${rule.toPhrase}",
                            style = Prinyal.type.body,
                            color = Prinyal.colors.ink,
                            modifier = Modifier.weight(1f),
                        )
                        // «Ни разу» — повод убрать правило, поэтому счётчик виден.
                        MetaText(
                            text = if (rule.hits == 0) {
                                stringResource(R.string.dict_never)
                            } else {
                                stringResource(R.string.dict_hits, rule.hits)
                            },
                            color = Prinyal.colors.inkFaint,
                        )
                        Text(
                            text = stringResource(R.string.dict_remove),
                            style = Prinyal.type.label,
                            color = Prinyal.colors.destructiveFg,
                            modifier = Modifier
                                .clickable { vm.removeReplacement(rule.id) }
                                .padding(start = Space.m),
                        )
                    }
                }
            }
        }

        item {
            Section(stringResource(R.string.settings_parsing)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.set_parse_toggle),
                        style = Prinyal.type.body,
                        color = Prinyal.colors.ink,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = llm,
                        onCheckedChange = { vm.setLlmEnabled(it) },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = Prinyal.colors.accentSelf,
                            checkedThumbColor = Prinyal.colors.surface,
                        ),
                    )
                }
                if (!llm) {
                    Text(
                        text = stringResource(R.string.set_parse_off_note),
                        style = Prinyal.type.body,
                        color = Prinyal.colors.inkMuted,
                    )
                }
            }
        }

        item {
            Section(stringResource(R.string.settings_data)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.settings_export),
                        style = Prinyal.type.label,
                        color = Prinyal.colors.accentSelf,
                        modifier = Modifier.clickable {
                            vm.export { file ->
                                vm.showMessage(
                                    context.getString(R.string.settings_export_done, file.name)
                                )
                            }
                        },
                    )
                    MetaText(
                        pluralStringResource(
                            R.plurals.settings_notes_count, notes.size, notes.size,
                        )
                    )
                }
                message?.let { MetaText(it) }
            }
        }

        item {
            Section(stringResource(R.string.settings_system)) {
                val granted = scheduler.canScheduleExact()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !granted) {
                            runCatching {
                                context.startActivity(
                                    Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                )
                            }
                        },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.settings_exact_alarm_row),
                        style = Prinyal.type.body,
                        color = Prinyal.colors.ink,
                    )
                    MetaText(
                        text = stringResource(
                            if (granted) R.string.settings_exact_alarm_granted
                            else R.string.settings_exact_alarm_denied
                        ),
                        color = if (granted) Prinyal.colors.statusOk else Prinyal.colors.statusWarn,
                    )
                }
                if (!granted) {
                    Text(
                        text = stringResource(R.string.settings_exact_alarm_why),
                        style = Prinyal.type.body,
                        color = Prinyal.colors.inkMuted,
                    )
                }

                // Р-8: без исключения из оптимизации батареи прошивка убивает
                // процесс и возвраты молчат — риск PRD §9, подтверждён на Honor.
                val pm = context.getSystemService(android.os.PowerManager::class.java)
                val ignoring = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !ignoring) {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        android.net.Uri.parse("package:" + context.packageName),
                                    )
                                )
                            }
                        },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.settings_battery_row),
                        style = Prinyal.type.body,
                        color = Prinyal.colors.ink,
                    )
                    MetaText(
                        text = stringResource(
                            if (ignoring) R.string.settings_battery_ok
                            else R.string.settings_battery_bad
                        ),
                        color = if (ignoring) Prinyal.colors.statusOk else Prinyal.colors.statusWarn,
                    )
                }
                if (!ignoring) {
                    Text(
                        text = stringResource(R.string.settings_battery_why),
                        style = Prinyal.type.body,
                        color = Prinyal.colors.inkMuted,
                    )
                }
            }
        }

        // «Для разработчика»: свёрнутая строка; в release скрыта целиком (§5).
        if (BuildConfig.DEBUG) {
            item { DeveloperSection(vm, threshold) }
        }
    }

    editingWindow?.let { window ->
        val current = when (window) {
            Window.MORNING, Window.TOMORROW_MORNING -> windows.morning
            Window.DAY -> windows.day
            Window.EVENING -> windows.evening
            Window.WEEKEND -> windows.weekend
        }
        TimeWheelDialog(
            current = current,
            onDismiss = { editingWindow = null },
            onPick = { picked ->
                editingWindow = null
                vm.setWindowValidated(window, picked)
            },
        )
    }
}

/** Строка окна: название слева, время справа mono, тап — колесо (спека §5). */
@Composable
private fun WindowRow(label: String, time: LocalTime, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 44.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Prinyal.type.body, color = Prinyal.colors.ink)
        Text(
            text = "%02d:%02d".format(time.hour, time.minute),
            style = Prinyal.type.meta.copy(fontSize = 16.sp),
            color = Prinyal.colors.ink,
        )
    }
}

/** Колесо времени с шагом 30 минут: прокручиваемый список слотов в диалоге. */
@Composable
private fun TimeWheelDialog(
    current: LocalTime,
    onDismiss: () -> Unit,
    onPick: (LocalTime) -> Unit,
) {
    val slots = remember {
        (5 * 60..23 * 60 step 30).map { LocalTime.of(it / 60, it % 60) }
    }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex =
            slots.indexOfFirst { it >= current }.coerceAtLeast(0).coerceAtLeast(2) - 2,
    )

    Dialog(onDismissRequest = onDismiss) {
        Box(
            Modifier
                .background(Prinyal.colors.surface, Radius.control)
                .padding(vertical = Space.s),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.heightIn(max = 320.dp),
            ) {
                items(slots.size) { index ->
                    val slot = slots[index]
                    val selected = slot == current
                    Text(
                        text = "%02d:%02d".format(slot.hour, slot.minute),
                        style = if (selected) Prinyal.type.itemTitle else Prinyal.type.body,
                        color = if (selected) Prinyal.colors.accentSelf else Prinyal.colors.ink,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(slot) }
                            .padding(horizontal = Space.xl, vertical = Space.s),
                    )
                }
            }
        }
    }
}

/** Сегменты «когда останавливать»: слова вместо чисел (спека §5). */
@Composable
private fun SilenceSegments(current: Int, onSelect: (Int) -> Unit) {
    val options = listOf(
        stringResource(R.string.set_silence_quiet) to 600,
        stringResource(R.string.set_silence_normal) to 900,
        stringResource(R.string.set_silence_noisy) to 1_400,
    )
    // Ближайший сегмент к текущему значению — на случай старых настроек слайдером.
    val selected = options.minByOrNull { kotlin.math.abs(it.second - current) }?.second
    Segments(options, selected, onSelect)
}

/** Сегменты «сколько ждать паузу» (R1.3 Р-13.2). */
@Composable
private fun PatienceSegments(
    current: SilenceWindow.Patience,
    onSelect: (SilenceWindow.Patience) -> Unit,
) {
    val options = listOf(
        stringResource(R.string.set_patience_short) to SilenceWindow.Patience.SHORT,
        stringResource(R.string.set_patience_normal) to SilenceWindow.Patience.NORMAL,
        stringResource(R.string.set_patience_long) to SilenceWindow.Patience.LONG,
    )
    Segments(options, current, onSelect)
}

@Composable
private fun <T> Segments(
    options: List<Pair<String, T>>,
    selected: T?,
    onSelect: (T) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        options.forEach { (label, value) ->
            val active = value == selected
            Box(
                Modifier
                    .background(
                        if (active) Prinyal.colors.accentSelfSoft else Prinyal.colors.paper,
                        Radius.pill,
                    )
                    .border(
                        1.dp,
                        if (active) Prinyal.colors.accentSelf else Prinyal.colors.rule,
                        Radius.pill,
                    )
                    .clickable { onSelect(value) }
                    .padding(horizontal = Space.sm, vertical = Space.s),
            ) {
                Text(
                    text = label,
                    style = Prinyal.type.label,
                    color = if (active) Prinyal.colors.accentSelf else Prinyal.colors.inkMuted,
                )
            }
        }
    }
}

/**
 * Kill-критерии недели: числа, пороги и настоящий вердикт, включая «Провал».
 *
 * Здесь слово «Провал» уместно — это ответ на вопрос «продолжаем ли мы вообще»,
 * а не оценка человека. На «Неделе» его нет намеренно (Р-13.1).
 */
@Composable
private fun KillMetrics(vm: AppViewModel) {
    val report by vm.weekly.collectAsState()

    LaunchedEffect(Unit) { vm.loadWeekly() }

    val data = report ?: return
    val verdict = when (data.verdict) {
        WeeklySummary.Verdict.EARLY -> stringResource(R.string.week_verdict_early)
        WeeklySummary.Verdict.ALIVE -> stringResource(R.string.week_verdict_alive)
        WeeklySummary.Verdict.WARN -> stringResource(R.string.week_verdict_warn)
        WeeklySummary.Verdict.FAIL -> stringResource(R.string.week_verdict_fail)
    }

    val returns = if (data.returnsShown == 0) {
        stringResource(R.string.week_no_data)
    } else {
        stringResource(R.string.week_of, data.returnsAnswered, data.returnsShown)
    }
    val lump = data.lumpMedian?.let { "%.1f".format(it) } ?: stringResource(R.string.week_no_data)

    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        MetaText("${stringResource(R.string.set_dev_kill)}: $verdict")
        MetaText(
            "${stringResource(R.string.week_metric_returns)}: $returns" +
                " · порог ${(WeeklySummary.RETURNS_MIN * 100).toInt()}%",
            color = Prinyal.colors.inkFaint,
        )
        MetaText(
            "${stringResource(R.string.week_metric_lump)}: $lump" +
                " · порог ${WeeklySummary.LUMP_MIN.toInt()}",
            color = Prinyal.colors.inkFaint,
        )
        MetaText(
            "«не надо» ${data.dismissed} · не ответил ${data.missed} · сделал ${data.done}",
            color = Prinyal.colors.inkFaint,
        )
    }
}

/**
 * Что нового в 1.0.1 видно изнутри (Р-14.8).
 *
 * Три числа, по которым понятно, работает ли структура: сколько разделов
 * завелось, что уходит в промпт глоссарием и как прошли последние сверки
 * дописываний. В «Неделю» ничего из этого не добавляем осознанно: тот экран
 * отвечает про петлю, а структура его не касается.
 */
@Composable
private fun VersionState(vm: AppViewModel) {
    val topics by vm.topics.collectAsState()
    val loose by vm.looseNotes.collectAsState()
    val rules by vm.replacements.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        MetaText(stringResource(R.string.set_dev_version))
        MetaText(
            "разделов ${topics.size} · без раздела $loose заметок",
            color = Prinyal.colors.inkFaint,
        )
        MetaText(
            if (rules.isEmpty()) {
                "глоссарий пуст"
            } else {
                "глоссарий: " + rules.take(4).joinToString("; ") {
                    "${it.fromPhrase}→${it.toPhrase}"
                }
            },
            color = Prinyal.colors.inkFaint,
        )
    }
}

/**
 * Судьба последних возвратов: план → аларм → показ → ответ.
 *
 * Ради этой таблицы всё и заводилось. Она отвечает на вопрос, который три
 * захода решался догадками: аларм не сработал — или сработал, но уведомление
 * не дошло. Пока ответа нет, чинить нечего.
 */
@Composable
private fun ReturnTrace() {
    val context = LocalContext.current
    val traces = remember { ReturnDiag.recent(context) }
    val time = remember { DateTimeFormatter.ofPattern("d MMM HH:mm") }
    val zone = remember { ZoneId.systemDefault() }

    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        MetaText(stringResource(R.string.set_dev_returns))

        if (traces.isEmpty()) {
            MetaText(stringResource(R.string.set_dev_returns_empty), color = Prinyal.colors.inkFaint)
            return@Column
        }

        traces.forEach { trace ->
            val planned = trace.plannedAt?.atZone(zone)?.format(time) ?: "—"
            // Слова, а не галочки: важно не «сколько шагов пройдено», а где встал.
            val state = when {
                trace.answeredAt != null -> "ответил"
                trace.viaCatchup -> "показан страховкой"
                trace.shownAt != null -> "показан алармом"
                trace.alarmAt != null -> "аларм был, показа нет"
                trace.note != null -> trace.note
                else -> "аларм не сработал"
            }
            val late = trace.lateMs?.let { " · опоздал на ${it / 60_000} мин" }.orEmpty()
            MetaText("$planned · $state$late", color = Prinyal.colors.inkFaint)
        }
    }
}

/** Серверный контур и сырые числа — только для отладки. */
@Composable
private fun DeveloperSection(vm: AppViewModel, threshold: Int) {
    var expanded by remember { mutableStateOf(false) }
    val url by vm.serverUrl.collectAsState()
    val health by vm.health.collectAsState()
    var urlDraft by remember(url) { mutableStateOf(url) }
    var tokenDraft by remember { mutableStateOf(vm.token()) }

    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            MetaText(stringResource(R.string.set_dev_section), color = Prinyal.colors.inkFaint)
            MetaText(if (expanded) "▴" else "▾", color = Prinyal.colors.inkFaint)
        }

        if (expanded) {
            HorizontalDivider(thickness = 1.dp, color = Prinyal.colors.hairline)

            // Kill-критерии PRD §8. Живут здесь, а не на «Неделе»: по ним
            // принимается решение о судьбе продукта, и читает их владелец в
            // роли заказчика, а не человек в роли пользователя.
            KillMetrics(vm)
            VersionState(vm)
            ReturnTrace()

            Field(stringResource(R.string.settings_server_url), urlDraft) {
                urlDraft = it
                vm.setServerUrl(it)
            }
            Field(stringResource(R.string.settings_token), tokenDraft, secret = true) {
                tokenDraft = it
                vm.setToken(it)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(Space.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.settings_check),
                    style = Prinyal.type.label,
                    color = Prinyal.colors.accentSelf,
                    modifier = Modifier.clickable { vm.checkHealth() },
                )
                health?.let {
                    MetaText(
                        text = stringResource(
                            if (it) R.string.settings_check_ok else R.string.settings_check_fail
                        ),
                        color = if (it) Prinyal.colors.statusOk else Prinyal.colors.statusWarn,
                    )
                }
            }
            MetaText("${stringResource(R.string.settings_silence_threshold)}: $threshold")
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        MetaText(title, color = Prinyal.colors.inkFaint)
        content()
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    secret: Boolean = false,
    onValue: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        MetaText(label)
        BasicTextField(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            textStyle = Prinyal.type.body.copy(color = Prinyal.colors.ink),
            cursorBrush = SolidColor(Prinyal.colors.accentSelf),
            visualTransformation = if (secret) PasswordVisualTransformation()
            else VisualTransformation.None,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Prinyal.colors.rule, Radius.control)
                .padding(Space.sm),
        )
    }
}
