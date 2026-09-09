package ai.prinim.prinyal.ui

import ai.prinim.prinyal.R
import ai.prinim.prinyal.ui.theme.MetaText
import ai.prinim.prinyal.ui.theme.Prinyal
import ai.prinim.prinyal.ui.theme.Touch
import ai.prinim.prinyal.ui.theme.tap
import ai.prinim.prinyal.ui.components.TertiaryButton
import ai.prinim.prinyal.ui.theme.Radius
import ai.prinim.prinyal.ui.theme.Sizes
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    // Корневая поверхность запоминается: после записи человек возвращается
    // туда, где работал, а не в «Записи» по умолчанию.
    LaunchedEffect(route) { vm.rememberRoot(route) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Prinyal.colors.paper)
            .statusBarsPadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            val searchQuery by vm.searchQuery.collectAsState()
            // «Назад» при открытом поиске закрывает поиск, а не приложение.
            BackHandler(enabled = route is Route.Feed && searchQuery != null) {
                vm.closeSearch()
            }
            if (route is Route.Feed && searchQuery != null) {
                SearchBar(
                    query = searchQuery.orEmpty(),
                    onQuery = { vm.setSearchQuery(it) },
                    onCancel = { vm.closeSearch() },
                )
            } else {
                TopBar(route = route, onRoute = onRoute, onSearch = { vm.openSearch() })
            }

            Box(Modifier.fillMaxSize()) {
                when (route) {
                    is Route.Feed -> FeedScreen(vm, onOpenNote = { onRoute(Route.Note(it)) })
                    is Route.Days -> DaysScreen(vm)
                    is Route.Note -> NoteScreen(
                        vm,
                        noteId = route.id,
                        onBack = { onRoute(Route.Feed) },
                        onOpenNote = { onRoute(Route.Note(it)) },
                    )
                    is Route.Settings -> SettingsScreen(vm)
                    // «Неделя» стала доской «Дела» (1.5): маршрут тот же, экран другой.
                    is Route.Weekly -> BoardScreen(vm)
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
                        onPickPack = { name -> onRoute(Route.PackPick(name)) },
                        onBack = { onRoute(Route.People) },
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

        // Снекбар въезжает, а не появляется (полишинг, п. 8): мгновенное
        // появление плашки читается как ошибка — в остальном продукте мгновенно
        // появляются только они. А снекбар несёт «вернуть», и его появление
        // человек обязан заметить, а не обнаружить.
        androidx.compose.animation.AnimatedVisibility(
            visible = undo != null,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = androidx.compose.animation.slideInVertically(
                initialOffsetY = { it },
                animationSpec = androidx.compose.animation.core.tween(
                    ai.prinim.prinyal.ui.theme.Motion.Polish.SNACK_IN_MS,
                    easing = androidx.compose.animation.core.FastOutSlowInEasing,
                ),
            ),
            exit = androidx.compose.animation.slideOutVertically(
                targetOffsetY = { it },
                animationSpec = androidx.compose.animation.core.tween(
                    ai.prinim.prinyal.ui.theme.Motion.Polish.SNACK_OUT_MS,
                ),
            ),
        ) {
        undo?.let { event ->
            UndoSnackbar(
                event = event,
                onExpire = {
                    vm.consumeUndo()
                    vm.purgeDeleted()
                },
                onUndone = { vm.consumeUndo() },
                modifier = Modifier.padding(Space.m),
            )
        }
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
    is AppViewModel.UndoMessage.RepeatStopped ->
        stringResource(R.string.item_repeat_off_done)
    is AppViewModel.UndoMessage.ItemRevived ->
        stringResource(R.string.item_revived_undo).substringBefore(" · ")
    // Перенос на доске: строка называет новый день — «Завтра утром» (спека 1.5 §5.1).
    is AppViewModel.UndoMessage.Moved -> message.label
}

@Composable
private fun TopBar(route: Route, onRoute: (Route) -> Unit, onSearch: () -> Unit) {
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
                .padding(horizontal = Space.screen - Touch.PAD_DP.dp),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            // Путь назад — служебное, значит моно. Высоту цели даёт tap().
            Box(Modifier.height(Sizes.tabBar), contentAlignment = Alignment.CenterStart) {
                MetaText(
                    text = "‹ $parentName",
                    color = Prinyal.colors.inkFaint,
                    modifier = Modifier.tap { onRoute(target) },
                )
            }
            title?.let {
                // Две строки максимум: длинное имя раздела иначе отжимает
                // содержимое экрана вниз, и списка не видно вовсе.
                Text(
                    it,
                    style = Prinyal.type.screenTitle,
                    color = Prinyal.colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(
                        start = Touch.PAD_DP.dp,
                        end = Touch.PAD_DP.dp,
                        bottom = Space.m,
                    ),
                )
            }
        }
        return
    }

    val surfaces = listOf(
        Route.Feed to stringResource(R.string.feed_title),
        Route.Topics to stringResource(R.string.topics_title),
        // «Дни» перед «Неделей»: неделя складывается из дней, и итог читается
        // слева направо (макет 12a).
        Route.Days to stringResource(R.string.days_title),
        Route.Weekly to stringResource(R.string.weekly_title),
    )

    Row(
        Modifier
            .fillMaxWidth()
            .height(Sizes.tabBar)
            .padding(horizontal = Space.screen),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Nav сжимаемый: иконки справа держат свои 44 всегда, слова уступают
        // первыми. До 1.3 «Неделя» и лупа делили остаток поровну — и на узком
        // экране лупа уезжала за край (ТЗ §4).
        Row(
            horizontalArrangement = Arrangement.spacedBy(Sizes.tabGap),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                // weight(1f) один на строку. Их было два — у слов и у распорки
                // между словами и иконками, — и место делилось пополам: «Дни»
                // обрезались до «Д», «Неделя» уезжала за край совсем.
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
        ) {
            surfaces.forEach { (target, label) ->
                val current = target == route
                Box(
                    Modifier
                        .height(Sizes.tabBar)
                        .clickable(onClick = { onRoute(target) }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = if (current) Prinyal.type.tabActive else Prinyal.type.tabInactive,
                        color = if (current) Prinyal.colors.ink else Prinyal.colors.inkFaint,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Лупа стоит только на «Записях» (макет 11a).
            if (route is Route.Feed) {
                NavIcon(glyph = "⌕", onClick = onSearch)
            }
            // Настройки — служебное, поэтому знаком, а не словом.
            NavIcon(glyph = "···", onClick = { onRoute(Route.Settings) })
        }
    }
}

/**
 * Иконка шапки: 44×44, радиус 14, подсветка поверхностью при нажатии (ТЗ §4).
 *
 * До 1.3 это были глифы с горизонтальным паддингом 4: цель выходила около
 * 20×24 — вдвое ниже нормы, и промах по «···» открывал ленту вместо настроек.
 */
@Composable
private fun NavIcon(glyph: String, onClick: () -> Unit) {
    val interaction = androidx.compose.runtime.remember {
        androidx.compose.foundation.interaction.MutableInteractionSource()
    }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .size(Sizes.navIcon)
            .clip(Radius.icon)
            .background(if (pressed) Prinyal.colors.surface else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = glyph, style = Prinyal.type.tabActive, color = Prinyal.colors.inkFaint)
    }
}

/**
 * Поле поиска на месте слов шапки (макет 11a): новой поверхности нет,
 * «отмена» возвращает шапку. Ищем с первого символа, кнопки «искать» нет.
 */
@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, onCancel: () -> Unit) {
    val focus = androidx.compose.runtime.remember { FocusRequester() }
    Row(
        Modifier
            .fillMaxWidth()
            .height(Sizes.tabBar)
            .padding(horizontal = Space.screen),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        // Поле, а не строка текста: 52 с радиусом 16 на поверхности (ТЗ §5).
        Row(
            Modifier
                .weight(1f)
                .height(Sizes.searchField)
                .clip(Radius.field)
                .background(Prinyal.colors.surface)
                .padding(horizontal = Space.s14),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.text.BasicTextField(
                value = query,
                onValueChange = onQuery,
                textStyle = Prinyal.type.itemTitle.copy(color = Prinyal.colors.ink),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Prinyal.colors.accentSelf),
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focus),
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text(
                            stringResource(R.string.search_hint),
                            style = Prinyal.type.itemTitle,
                            color = Prinyal.colors.inkFaint,
                        )
                    }
                    inner()
                },
            )
        }
        TertiaryButton(
            text = stringResource(R.string.search_cancel),
            onClick = onCancel,
            color = Prinyal.colors.inkMuted,
        )
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { focus.requestFocus() }
}
