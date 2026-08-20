package ai.prinim.prinyal.ui

import ai.prinim.prinyal.R
import ai.prinim.prinyal.ui.theme.MetaText
import ai.prinim.prinyal.ui.theme.Prinyal
import ai.prinim.prinyal.ui.theme.tap
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
import androidx.compose.ui.text.style.TextOverflow
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
    val shareContext = androidx.compose.ui.platform.LocalContext.current
    val vm: AppViewModel = viewModel()
    val undo by vm.undo.collectAsState()

    // «Назад» из карточки, настроек и сводки ведёт в ленту. Без этого системный жест
    // закрывает приложение целиком — человек хотел вернуться к списку, а вышел вон.
    // «Назад» ведёт на шаг вверх по смыслу, а не всегда в ленту: из раздела —
    // к списку разделов. Без этого человек, ушедший вглубь структуры, вылетал
    // бы к времени одним жестом.
    BackHandler(enabled = route !is Route.Feed) {
        onRoute(
            when (route) {
                is Route.Topic, is Route.People, is Route.PackPick -> Route.Topics
                is Route.Person -> Route.People
                else -> Route.Feed
            }
        )
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
                    is Route.Note -> NoteScreen(
                        vm,
                        noteId = route.id,
                        onBack = { onRoute(Route.Feed) },
                        onOpenNote = { onRoute(Route.Note(it)) },
                    )
                    is Route.Settings -> SettingsScreen(vm)
                    is Route.Weekly -> WeeklyScreen(vm)
                    is Route.Topics -> TopicsScreen(
                        vm,
                        onOpen = { id, name -> onRoute(Route.Topic(id, name)) },
                        onPeople = { onRoute(Route.People) },
                    )
                    is Route.People -> PeopleScreen(
                        vm,
                        onOpen = { id, name -> onRoute(Route.Person(id, name)) },
                    )
                    is Route.Person -> PersonScreen(
                        vm,
                        personId = route.id,
                        onOpenNote = { onRoute(Route.Note(it)) },
                    )
                    is Route.PackPick -> PackPickScreen(
                        vm,
                        onShare = { file ->
                            sharePack(shareContext, file)
                            onRoute(Route.Topics)
                        },
                    )
                    is Route.Topic -> TopicScreen(
                        vm,
                        topicId = route.id,
                        onOpenNote = { onRoute(Route.Note(it)) },
                        onPickPack = { id, title ->
                            vm.openPackPick(id, title)
                            onRoute(Route.PackPick(title))
                        },
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
                    .tap {
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
    is AppViewModel.UndoMessage.RuleRemoved ->
        stringResource(R.string.dict_removed)
}

@Composable
private fun TopBar(route: Route, onRoute: (Route) -> Unit) {
    // Три слова в шапке живут только на трёх корневых поверхностях.
    //
    // Пока шапка была одна на всё, вложенный экран носил чужую навигацию:
    // на экране раздела подсвечены «Разделы», а имени раздела нет вовсе, и
    // выйти можно только системной «назад». Человек открыл «Дачу» и не мог
    // убедиться, что он в «Даче», а не в общем списке (аудит Д-7, п. 1).
    //
    // У вложенного экрана своя шапка: путь назад словом и собственное имя.
    val parent: Pair<Route, String>? = when (route) {
        is Route.Note -> Route.Feed to stringResource(R.string.feed_title)
        is Route.Topic -> Route.Topics to stringResource(R.string.topics_title)
        is Route.People -> Route.Topics to stringResource(R.string.topics_title)
        is Route.Person -> Route.People to stringResource(R.string.topics_people)
        is Route.PackPick -> Route.Topics to stringResource(R.string.topics_title)
        is Route.Settings -> Route.Feed to stringResource(R.string.feed_title)
        else -> null
    }

    if (parent != null) {
        val (target, parentName) = parent
        val title = when (route) {
            is Route.Topic -> route.name.ifBlank { stringResource(R.string.topics_loose) }
            is Route.People -> stringResource(R.string.topics_people)
            is Route.Person -> route.name
            is Route.PackPick -> stringResource(R.string.pack_pick_title)
            is Route.Settings -> stringResource(R.string.settings_title)
            else -> null
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.screen, vertical = Space.m),
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            MetaText(
                text = "‹ $parentName",
                color = Prinyal.colors.inkFaint,
                modifier = Modifier.tap { onRoute(target) },
            )
            title?.let {
                // Две строки максимум: длинное имя раздела иначе отжимает
                // содержимое экрана вниз, и списка не видно вовсе.
                Text(
                    it,
                    style = Prinyal.type.itemTitle,
                    color = Prinyal.colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        return
    }

    val surfaces = listOf(
        Route.Feed to stringResource(R.string.feed_title),
        Route.Topics to stringResource(R.string.topics_title),
        Route.Weekly to stringResource(R.string.weekly_title),
    )

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
                val current = target == route
                Text(
                    text = label,
                    style = if (current) Prinyal.type.itemTitle else Prinyal.type.body,
                    color = if (current) Prinyal.colors.ink else Prinyal.colors.inkFaint,
                    modifier = Modifier.tap { onRoute(target) },
                )
            }
        }
        // Настройки — не поверхность, а служебное, поэтому знаком, а не словом.
        Text(
            text = "···",
            style = Prinyal.type.itemTitle,
            color = Prinyal.colors.inkFaint,
            modifier = Modifier.tap { onRoute(Route.Settings) },
        )
    }
}
