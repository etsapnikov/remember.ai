package ai.prinim.prinyal.ui

import ai.prinim.prinyal.R
import ai.prinim.prinyal.domain.WeeklySummary
import ai.prinim.prinyal.ui.theme.MetaText
import ai.prinim.prinyal.ui.theme.Prinyal
import ai.prinim.prinyal.ui.theme.Space
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * «Неделя» (спека R1.1 §6): вердикт словом, фраза-объяснение, два kill-критерия
 * крупно с полосой к порогу, остальное мелко.
 *
 * Полоса — не график: она показывает только положение относительно порога.
 * Язык без голых процентов: «0 из 12», «обычно 3 пункта в записи».
 */
@Composable
fun WeeklyScreen(vm: AppViewModel) {
    val report by vm.weekly.collectAsState()

    LaunchedEffect(Unit) { vm.loadWeekly() }

    val data = report
    if (data == null) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            MetaText(stringResource(R.string.week_no_data))
        }
        return
    }

    val early = data.verdict == WeeklySummary.Verdict.EARLY

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.screen),
        verticalArrangement = Arrangement.spacedBy(Space.ml),
    ) {
        Verdict(data)

        // Kill-критерии — крупно, с полосой к порогу (в обкатку полосы скрыты).
        KillMetric(
            label = stringResource(R.string.week_metric_returns),
            value = if (data.returnsShown == 0) stringResource(R.string.week_no_data)
            else stringResource(R.string.week_of, data.returnsAnswered, data.returnsShown),
            fraction = data.returnsShare,
            threshold = WeeklySummary.RETURNS_MIN,
            thresholdCaption = stringResource(
                R.string.week_threshold_returns,
                (WeeklySummary.RETURNS_MIN * 100).roundToInt(),
            ),
            muted = early,
        )
        KillMetric(
            label = stringResource(R.string.week_metric_lump),
            value = data.lumpMedian?.let {
                pluralStringResource(
                    R.plurals.week_items_median, it.roundToInt(), it.roundToInt(),
                )
            } ?: stringResource(R.string.week_no_data),
            fraction = data.lumpMedian?.let { it / (WeeklySummary.LUMP_MIN * 2) },
            threshold = 0.5,
            thresholdCaption = stringResource(
                R.string.week_threshold_lump, WeeklySummary.LUMP_MIN.roundToInt(),
            ),
            muted = early,
        )

        // Остальное — мелко, строками.
        SmallMetric(
            stringResource(R.string.week_metric_days),
            stringResource(R.string.week_of, data.daysWithCapture, data.daysWindow),
        )
        SmallMetric(
            stringResource(R.string.week_metric_per_day),
            data.perDayMedian?.toString() ?: stringResource(R.string.week_no_data),
        )
        SmallMetric(
            stringResource(R.string.week_metric_dismissed),
            if (data.returnsAnswered == 0) stringResource(R.string.week_no_data)
            else stringResource(R.string.week_of, data.dismissed, data.returnsAnswered),
        )

        Box(Modifier.height(Space.xl))
    }
}

@Composable
private fun Verdict(report: WeeklySummary.Report) {
    val (text, color) = when (report.verdict) {
        WeeklySummary.Verdict.EARLY ->
            stringResource(R.string.week_verdict_early) to Prinyal.colors.inkMuted
        WeeklySummary.Verdict.ALIVE ->
            stringResource(R.string.week_verdict_alive) to Prinyal.colors.statusOk
        WeeklySummary.Verdict.WARN ->
            stringResource(R.string.week_verdict_warn) to Prinyal.colors.statusWarn
        WeeklySummary.Verdict.FAIL ->
            stringResource(R.string.week_verdict_fail) to Prinyal.colors.destructiveFg
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
 * Kill-метрика: подпись, значение крупно, полоса 4 dp с меткой порога.
 * Метка порога стоит на 60% ширины; заливка масштабируется к ней же — так
 * «на пороге» видно глазом без цифр.
 */
@Composable
private fun KillMetric(
    label: String,
    value: String,
    fraction: Double?,
    threshold: Double,
    thresholdCaption: String,
    muted: Boolean,
) {
    val thresholdAt = 0.6f

    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text(
            text = label,
            style = Prinyal.type.body,
            color = Prinyal.colors.inkMuted,
        )
        Text(
            text = value,
            style = Prinyal.type.metric,
            color = if (muted) Prinyal.colors.inkFaint else Prinyal.colors.ink,
        )

        if (!muted && fraction != null) {
            val ok = fraction >= threshold
            val fill = ((fraction / threshold) * thresholdAt).toFloat().coerceIn(0.02f, 1f)
            val fillColor = if (ok) Prinyal.colors.statusOk else Prinyal.colors.statusWarn
            val track = Prinyal.colors.wellSurface
            val mark = Prinyal.colors.inkFaint

            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp),
            ) {
                val barY = size.height / 2
                drawLine(track, Offset(0f, barY), Offset(size.width, barY), 4.dp.toPx())
                drawLine(fillColor, Offset(0f, barY), Offset(size.width * fill, barY), 4.dp.toPx())
                drawLine(
                    mark,
                    Offset(size.width * thresholdAt, 0f),
                    Offset(size.width * thresholdAt, size.height),
                    2.dp.toPx(),
                )
            }
            MetaText(thresholdCaption, color = Prinyal.colors.inkFaint)
        }
    }
}

@Composable
private fun SmallMetric(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Prinyal.type.body, color = Prinyal.colors.inkMuted)
        Text(value, style = Prinyal.type.label, color = Prinyal.colors.ink)
    }
}

