package ai.prinim.prinyal.ui

import ai.prinim.prinyal.R
import ai.prinim.prinyal.ui.theme.MetaText
import ai.prinim.prinyal.ui.theme.Prinyal
import ai.prinim.prinyal.ui.theme.Space
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import kotlin.math.roundToInt

/**
 * Сводка недели (PRD §F-9). Приходит сама, считается сама — решение по kill-критериям
 * принимается по числам, записанным заранее в §8, а не по самочувствию.
 */
@Composable
fun WeeklyScreen(vm: AppViewModel) {
    val report by vm.weekly.collectAsState()

    LaunchedEffect(Unit) { vm.loadWeekly() }

    val data = report
    if (data == null) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            MetaText(stringResource(R.string.weekly_title))
        }
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = Space.screen),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Line(stringResource(R.string.weekly_days, data.daysWithCapture, data.daysWindow))
        Line(stringResource(R.string.weekly_per_day, format(data.notesPerDay)))
        Line(stringResource(R.string.weekly_items_median, format(data.itemsPerNoteMedian)))
        Line(stringResource(R.string.weekly_returns_acted, percent(data.returnsActedShare)))
        Line(stringResource(R.string.weekly_dismissed, percent(data.dismissedShare)))

        // Kill-критерий 2: медиана < 1.5 означает, что построен Siri-клон.
        if (data.siriCloneAlarm) {
            Text(
                text = stringResource(R.string.weekly_alarm_low),
                style = Prinyal.type.voice,
                color = Prinyal.colors.accentSelf,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Line(text: String) {
    Text(text, style = Prinyal.type.body, color = Prinyal.colors.ink)
}

private fun format(value: Double): String = "%.1f".format(value).replace('.', ',')

private fun percent(value: Double): Int = (value * 100).roundToInt()
