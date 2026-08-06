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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay

/**
 * Каркас второстепенных экранов. Ни табов, ни бургеров: три места, между которыми
 * ходят редко, — лента, настройки, сводка недели.
 */
@Composable
fun AppScaffold(route: Route, onRoute: (Route) -> Unit) {
    val vm: AppViewModel = viewModel()
    val undo by vm.undo.collectAsState()

    // «Назад» из карточки, настроек и сводки ведёт в ленту. Без этого системный жест
    // закрывает приложение целиком — человек хотел вернуться к списку, а вышел вон.
    BackHandler(enabled = route !is Route.Feed) { onRoute(Route.Feed) }

    // Выход с экрана — точка невозврата для мягко удалённого (спека §2.2).
    DisposableEffect(Unit) {
        onDispose { vm.purgeDeleted() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Prinyal.colors.paper)
            .statusBarsPadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
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

        undo?.let { event ->
            UndoSnackbar(
                event = event,
                onExpire = {
                    vm.consumeUndo()
                    vm.purgeDeleted()
                },
                onUndone = { vm.consumeUndo() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(Space.m),
            )
        }
    }
}

/**
 * Снекбар undo (спека §2.2): 6 секунд, поверх ленты, «вернуть» восстанавливает.
 * Свой, а не material: у Material-снекбара свои длительности и свой тон.
 */
@Composable
private fun UndoSnackbar(
    event: AppViewModel.UndoEvent,
    onExpire: () -> Unit,
    onUndone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: AppViewModel = viewModel()

    LaunchedEffect(event) {
        delay(6_000)
        onExpire()
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Prinyal.colors.wellSurface,
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 4.dp,
    ) {
        Row(
            Modifier.padding(horizontal = Space.m, vertical = Space.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = undoText(event.message),
                style = Prinyal.type.body,
                color = Prinyal.colors.ink,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.undo),
                style = Prinyal.type.label,
                color = Prinyal.colors.accentSelf,
                modifier = Modifier
                    .clickable {
                        vm.runUndo(event)
                        onUndone()
                    }
                    .padding(Space.s),
            )
        }
    }
}

@Composable
private fun undoText(message: AppViewModel.UndoMessage): String = when (message) {
    is AppViewModel.UndoMessage.NoteDeleted ->
        stringResource(R.string.note_deleted_undo).substringBefore(" · ")
    is AppViewModel.UndoMessage.JunkSwept ->
        pluralStringResource(R.plurals.feed_junk_swept_undo, message.count, message.count)
            .substringBefore(" · ")
    is AppViewModel.UndoMessage.ItemBuried ->
        stringResource(R.string.item_buried_undo).substringBefore(" · ")
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
