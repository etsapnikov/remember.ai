package ai.prinim.prinyal.ui

import ai.prinim.prinyal.R
import ai.prinim.prinyal.ui.theme.Prinyal
import ai.prinim.prinyal.ui.theme.Space
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Каркас второстепенных экранов. Ни табов, ни бургеров: три места, между которыми
 * ходят редко, — лента, настройки, сводка недели.
 */
@Composable
fun AppScaffold(route: Route, onRoute: (Route) -> Unit) {
    val vm: AppViewModel = viewModel()

    // «Назад» из карточки, настроек и сводки ведёт в ленту. Без этого системный жест
    // закрывает приложение целиком — человек хотел вернуться к списку, а вышел вон.
    BackHandler(enabled = route !is Route.Feed) { onRoute(Route.Feed) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Prinyal.colors.paper)
            .statusBarsPadding(),
    ) {
        TopBar(route = route, onRoute = onRoute)

        Box(Modifier.fillMaxSize()) {
            when (route) {
                is Route.Feed -> FeedScreen(vm, onOpenNote = { onRoute(Route.Note(it)) })
                is Route.Note -> NoteScreen(vm, noteId = route.id, onBack = { onRoute(Route.Feed) })
                is Route.Settings -> SettingsScreen(vm)
                is Route.Weekly -> WeeklyScreen(vm)
            }
        }
    }
}

@Composable
private fun TopBar(route: Route, onRoute: (Route) -> Unit) {
    val title = when (route) {
        is Route.Feed -> stringResource(R.string.feed_title)
        is Route.Note -> stringResource(R.string.feed_title)
        is Route.Settings -> stringResource(R.string.settings_title)
        is Route.Weekly -> stringResource(R.string.weekly_title)
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.screen, vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = Prinyal.type.itemTitle,
            color = Prinyal.colors.ink,
            modifier = Modifier.clickable { onRoute(Route.Feed) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            Text(
                text = stringResource(R.string.weekly_title),
                style = Prinyal.type.meta,
                color = if (route is Route.Weekly) Prinyal.colors.accentSelf else Prinyal.colors.inkFaint,
                modifier = Modifier.clickable { onRoute(Route.Weekly) },
            )
            Text(
                text = stringResource(R.string.settings_title),
                style = Prinyal.type.meta,
                color = if (route is Route.Settings) Prinyal.colors.accentSelf else Prinyal.colors.inkFaint,
                modifier = Modifier.clickable { onRoute(Route.Settings) },
            )
        }
    }
}
