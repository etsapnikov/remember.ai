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
    // «Назад» ведёт на шаг вверх по смыслу, а не всегда в ленту: из раздела —
    // к списку разделов. Без этого человек, ушедший вглубь структуры, вылетал
    // бы к времени одним жестом.
    BackHandler(enabled = route !is Route.Feed) {
        onRoute(if (route is Route.Topic) Route.Topics else Route.Feed)
    }

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
                    is Route.Topics -> TopicsScreen(
                        vm,
                        onOpen = { id, name -> onRoute(Route.Topic(id, name)) },
                    )
                    is Route.Topic -> TopicScreen(
                        vm,
                        topicId = route.id,
                        onOpenNote = { onRoute(Route.Note(it)) },
                    )
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
    is AppViewModel.UndoMessage.EditDropped ->
        stringResource(R.string.transcript_edit_dropped)
}

@Composable
private fun TopBar(route: Route, onRoute: (Route) -> Unit) {
    // Поверхности живут словами в шапке: текущая — заголовком, соседние — тихим
    // текстом рядом (Д-1, вариант А). Жестов взять неоткуда: вертикаль занята
    // захватом, горизонталь — удалением строки, и разводить их по зонам экрана
    // значило бы завести правило, которое человек обязан помнить.
    //
    // Прецедент на будущее: новая поверхность — ещё одно слово, а не новый жест.
    val surfaces = listOf(
        Route.Feed to stringResource(R.string.feed_title),
        Route.Topics to stringResource(R.string.topics_title),
        Route.Weekly to stringResource(R.string.weekly_title),
    )
    // Карточка записи и раздел живут «внутри» своей поверхности — она и подсвечена.
    val active: Route = when (route) {
        is Route.Note -> Route.Feed
        is Route.Topic -> Route.Topics
        else -> route
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.screen, vertical = Space.m),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.m),
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.weight(1f),
        ) {
            surfaces.forEach { (target, label) ->
                val current = target == active
                Text(
                    text = label,
                    style = if (current) Prinyal.type.itemTitle else Prinyal.type.body,
                    color = if (current) Prinyal.colors.ink else Prinyal.colors.inkFaint,
                    modifier = Modifier.clickable { onRoute(target) },
                )
            }
        }
        // Настройки — не поверхность, а служебное, поэтому знаком, а не словом.
        Text(
            text = "···",
            style = Prinyal.type.itemTitle,
            color = if (route is Route.Settings) Prinyal.colors.accentSelf
            else Prinyal.colors.inkFaint,
            modifier = Modifier.clickable { onRoute(Route.Settings) },
        )
    }
}
