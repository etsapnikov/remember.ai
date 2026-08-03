package ai.prinim.prinyal.ui

import ai.prinim.prinyal.R
import ai.prinim.prinyal.capture.Recorder
import ai.prinim.prinyal.data.Window
import ai.prinim.prinyal.returns.ReturnScheduler
import ai.prinim.prinyal.ui.theme.MetaText
import ai.prinim.prinyal.ui.theme.Prinyal
import ai.prinim.prinyal.ui.theme.Radius
import ai.prinim.prinyal.ui.theme.Space
import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.time.LocalTime

/**
 * Настройки (PRD §F-8) — минимум, который нужен, чтобы петля работала и данные
 * не пропали: адрес и токен, окна дня, тумблер разбора, экспорт.
 */
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val url by vm.serverUrl.collectAsState()
    val llm by vm.llmEnabled.collectAsState()
    val windows by vm.windows.collectAsState()
    val threshold by vm.silenceThreshold.collectAsState()
    val health by vm.health.collectAsState()
    val message by vm.message.collectAsState()

    var urlDraft by remember(url) { mutableStateOf(url) }
    var tokenDraft by remember { mutableStateOf(vm.token()) }
    val scheduler = remember { ReturnScheduler(context) }

    LazyColumn(
        contentPadding = PaddingValues(
            start = Space.screen, end = Space.screen, top = Space.s, bottom = Space.xxl,
        ),
        verticalArrangement = Arrangement.spacedBy(Space.ml),
    ) {
        item {
            Section(stringResource(R.string.settings_server)) {
                Field(
                    label = stringResource(R.string.settings_server_url),
                    value = urlDraft,
                    onValue = {
                        urlDraft = it
                        vm.setServerUrl(it)
                    },
                )
                Field(
                    label = stringResource(R.string.settings_token),
                    value = tokenDraft,
                    secret = true,
                    onValue = {
                        tokenDraft = it
                        vm.setToken(it)
                    },
                )
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
                            color = if (it) Prinyal.colors.done else Prinyal.colors.inkMuted,
                        )
                    }
                }
            }
        }

        item {
            Section(stringResource(R.string.settings_windows)) {
                TimeRow(stringResource(R.string.settings_window_morning), windows.morning) {
                    vm.setWindow(Window.MORNING, it)
                }
                TimeRow(stringResource(R.string.settings_window_day), windows.day) {
                    vm.setWindow(Window.DAY, it)
                }
                TimeRow(stringResource(R.string.settings_window_evening), windows.evening) {
                    vm.setWindow(Window.EVENING, it)
                }
                TimeRow(stringResource(R.string.settings_window_weekend), windows.weekend) {
                    vm.setWindow(Window.WEEKEND, it)
                }
            }
        }

        item {
            Section(stringResource(R.string.settings_llm)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.settings_llm),
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
                // Честная надпись, что именно перестаёт работать (F-8).
                if (!llm) {
                    Text(
                        text = stringResource(R.string.settings_llm_off_warning),
                        style = Prinyal.type.body,
                        color = Prinyal.colors.inkMuted,
                    )
                }
            }
        }

        item {
            Section(stringResource(R.string.settings_silence_threshold)) {
                Slider(
                    value = threshold.toFloat(),
                    onValueChange = { vm.setSilenceThreshold(it.toInt()) },
                    valueRange = 200f..4_000f,
                )
                MetaText("$threshold")
            }
        }

        item {
            Section(stringResource(R.string.settings_exact_alarm)) {
                if (!scheduler.canScheduleExact()) {
                    Text(
                        text = stringResource(R.string.settings_exact_alarm_why),
                        style = Prinyal.type.body,
                        color = Prinyal.colors.inkMuted,
                    )
                    Text(
                        text = stringResource(R.string.settings_exact_alarm),
                        style = Prinyal.type.label,
                        color = Prinyal.colors.accentSelf,
                        modifier = Modifier.clickable {
                            runCatching {
                                context.startActivity(
                                    Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                )
                            }
                        },
                    )
                } else {
                    MetaText(stringResource(R.string.settings_check_ok))
                }
            }
        }

        item {
            Section(stringResource(R.string.settings_export)) {
                Text(
                    text = stringResource(R.string.settings_export),
                    style = Prinyal.type.label,
                    color = Prinyal.colors.accentSelf,
                    modifier = Modifier.clickable {
                        vm.export { file ->
                            vm.showMessage(context.getString(R.string.settings_export_done, file.name))
                        }
                    },
                )
                message?.let { MetaText(it) }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
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
            else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Prinyal.colors.rule, Radius.control)
                .padding(Space.sm),
        )
    }
}

/**
 * Время окна ползунком по получасам: точнее не нужно, а выбор из диалога — лишний
 * экран там, где хватает движения пальцем.
 */
@Composable
private fun TimeRow(label: String, time: LocalTime, onChange: (LocalTime) -> Unit) {
    val minutes = time.hour * 60 + time.minute
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = Prinyal.type.body, color = Prinyal.colors.ink)
            MetaText("%02d:%02d".format(time.hour, time.minute))
        }
        Slider(
            value = minutes.toFloat(),
            onValueChange = { onChange(LocalTime.of(it.toInt() / 60, (it.toInt() % 60) / 30 * 30)) },
            valueRange = 5f * 60f..23f * 60f,
            steps = ((23 - 5) * 2) - 1,
        )
    }
}
