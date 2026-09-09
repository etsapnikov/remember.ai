@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package ai.prinim.prinyal.ui

import ai.prinim.prinyal.R
import ai.prinim.prinyal.domain.Dates
import ai.prinim.prinyal.ui.AppViewModel.Board
import ai.prinim.prinyal.ui.AppViewModel.BoardCard
import ai.prinim.prinyal.ui.AppViewModel.BoardColumn
import ai.prinim.prinyal.ui.components.Divider
import ai.prinim.prinyal.ui.components.StatusBadge
import ai.prinim.prinyal.ui.components.BadgeTone
import ai.prinim.prinyal.ui.theme.MetaText
import ai.prinim.prinyal.ui.theme.Motion
import ai.prinim.prinyal.ui.theme.Prinyal
import ai.prinim.prinyal.ui.theme.Radius
import ai.prinim.prinyal.ui.theme.Sizes
import ai.prinim.prinyal.ui.theme.Space
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * «Дела» — доска (1.5, спека «Неделя доской», Д-51…Д-55).
 *
 * Три колонки — Сегодня · Завтра · Позже — и стопка «Входящие» над ними. На
 * данных первого дня стопка — это 45 дел из 47: доска здесь не для того, чтобы
 * *смотреть* распределение, а чтобы *раскладывать*. Поэтому стопка сверху, и
 * перенос — основной жест экрана.
 *
 * Правило продукта «срок назначает речь» здесь сужено, а не отменено: речь —
 * при рождении дела, доска — при пересмотре. Об этом стопка говорит один раз
 * голосом продукта и больше не повторяет.
 *
 * Портрет — пейджер: одна колонка на экран, край соседней 24 dp. Ландшафт — три
 * колонки целиком плюс стопка полосой слева, и только там есть drag: в
 * портрете палец не видит, куда несёт.
 */
@Composable
fun BoardScreen(vm: AppViewModel) {
    val board by vm.board.collectAsState()
    val hintDone by vm.boardHintDone.collectAsState()
    LaunchedEffect(Unit) { vm.loadBoardHint() }

    var editing by remember { mutableStateOf<BoardCard?>(null) }
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp

    if (landscape) {
        LandscapeBoard(vm, board, hintDone, onOpen = { editing = it })
    } else {
        PortraitBoard(vm, board, hintDone, onOpen = { editing = it })
    }

    editing?.let { card ->
        val subtitle = listOfNotNull(
            card.topic,
            Dates.day(card.note.createdAt),
            if (card.at == null) stringResource(R.string.board_no_due) else null,
        ).joinToString(" · ")
        EditItemSheet(
            item = card.item,
            subtitle = subtitle,
            onDismiss = { editing = null },
            onSave = { text, type, window, exactAt, clear ->
                vm.editItem(card.item.id, text, type, window, exactAt, clear)
                editing = null
            },
            onBury = {
                vm.buryWithUndo(card.item.id)
                editing = null
            },
            onDone = {
                vm.markDone(card.item.id)
                editing = null
            },
            onDismissItem = {
                vm.dismiss(card.item.id)
                editing = null
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Портрет
// ---------------------------------------------------------------------------

@Composable
private fun PortraitBoard(
    vm: AppViewModel,
    board: Board,
    hintDone: Boolean,
    onOpen: (BoardCard) -> Unit,
) {
    val pager = rememberPagerState(pageCount = { BoardColumn.entries.size })
    val scope = rememberCoroutineScope()
    var inboxExpanded by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        ColumnHeaders(
            board = board,
            active = BoardColumn.entries[pager.currentPage],
            onPick = { scope.launch { pager.animateScrollToPage(it.ordinal) } },
        )

        // Всё под заголовками — одна прокрутка. Раскрытая стопка из 34 карточек
        // без неё уносила доску за экран: человек видел только входящие, табы
        // переключали невидимый пейджер, а последняя карточка стояла обрезанной
        // краем экрана и читалась как пустая. Доска — страница фиксированной
        // высоты под стопкой: спека говорит «доска уезжает вниз», не «пропадает».
        BoxWithConstraints(Modifier.weight(1f)) {
            val pageHeight = maxHeight
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                if (board.inbox.isNotEmpty()) {
                    InboxStack(
                        cards = board.inbox,
                        hintDone = hintDone,
                        expanded = inboxExpanded,
                        onToggle = { inboxExpanded = !inboxExpanded },
                        onOpen = onOpen,
                        onRight = { vm.moveTomorrow(it.item.id, fromInbox = true) },
                    )
                }

                HorizontalPager(
                    state = pager,
                    modifier = Modifier.height(pageHeight),
                    // Край соседней колонки 24 dp справа (§2): видно, куда тащить.
                    contentPadding = PaddingValues(start = Space.screen, end = Space.screen + PEEK),
                    // Шаг страниц не меньше левого поля: иначе предыдущая колонка
                    // просвечивала слева полоской в 8 dp и читалась как пустая карточка.
                    pageSpacing = Space.screen,
                    beyondViewportPageCount = 1,
                ) { page ->
                    val column = BoardColumn.entries[page]
                    ColumnCards(
                        column = column,
                        cards = board.column(column),
                        onOpen = onOpen,
                        onRight = { card -> swipeRight(vm, column, card) },
                        onLeft = { card -> swipeLeft(vm, column, card) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                // Строка итога 48: «за неделю сделано N», при нуле — ничего (Д-54).
                if (board.doneWeek > 0) {
                    Box(
                        Modifier.fillMaxWidth().height(48.dp).padding(horizontal = Space.screen),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        MetaText(
                            text = stringResource(R.string.board_done_week, board.doneWeek),
                            color = Prinyal.colors.inkFaint,
                        )
                    }
                }
            }
        }
    }
}

/** Свайп вправо — на колонку позже (§5.1). Из «Позже» вправо — некуда. */
private fun swipeRight(vm: AppViewModel, column: BoardColumn, card: BoardCard) = when (column) {
    BoardColumn.TODAY -> vm.moveTomorrow(card.item.id)
    BoardColumn.TOMORROW -> vm.moveAfterTomorrow(card.item.id)
    BoardColumn.LATER -> null
}

/** Свайп влево — на колонку раньше; из «Сегодня» влево — снять срок. */
private fun swipeLeft(vm: AppViewModel, column: BoardColumn, card: BoardCard) = when (column) {
    BoardColumn.TODAY -> vm.moveToInbox(card.item.id)
    BoardColumn.TOMORROW -> vm.moveToday(card.item.id)
    BoardColumn.LATER -> vm.moveTomorrow(card.item.id)
}

private fun canSwipeRight(column: BoardColumn?) = column != BoardColumn.LATER
private fun canSwipeLeft(column: BoardColumn?) = column != null

/** Имя колонки-приёмника для подложки свайпа. */
@Composable
private fun targetLabel(column: BoardColumn?, right: Boolean): String = when {
    column == null -> stringResource(R.string.board_col_tomorrow)
    right && column == BoardColumn.TODAY -> stringResource(R.string.board_col_tomorrow)
    right -> stringResource(R.string.board_col_later)
    column == BoardColumn.TODAY -> stringResource(R.string.board_inbox)
    column == BoardColumn.TOMORROW -> stringResource(R.string.board_col_today)
    else -> stringResource(R.string.board_col_tomorrow)
}

/**
 * Заголовки колонок вторым рядом табов (§2): 52 dp, активная 20 EB с акцентной
 * линией и счётчиком акцентом, неактивные 16 SB служебным.
 */
@Composable
private fun ColumnHeaders(board: Board, active: BoardColumn, onPick: (BoardColumn) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(Sizes.filterRow)
                .padding(horizontal = Space.screen),
            horizontalArrangement = Arrangement.spacedBy(Space.screen),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BoardColumn.entries.forEach { column ->
                val current = column == active
                // Ширина — по содержимому: с fillMaxWidth у линии первый заголовок
                // забирал всю строку, и «Завтра» с «Позже» не рендерились вовсе.
                Column(
                    Modifier
                        .fillMaxHeight()
                        .width(IntrinsicSize.Max)
                        .clickable { onPick(column) },
                    verticalArrangement = Arrangement.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(Space.s),
                    ) {
                        Text(
                            text = columnTitle(column),
                            style = if (current) Prinyal.type.tabActive
                            else Prinyal.type.itemTitle,
                            color = if (current) Prinyal.colors.ink else Prinyal.colors.inkFaint,
                            maxLines = 1,
                        )
                        MetaText(
                            text = board.column(column).size.toString(),
                            color = if (current) Prinyal.colors.accentSelf else Prinyal.colors.inkFaint,
                        )
                    }
                    Box(Modifier.height(Space.s))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(if (current) Prinyal.colors.accentSelf else Color.Transparent)
                    )
                }
            }
        }
        Divider()
    }
}

@Composable
private fun columnTitle(column: BoardColumn): String = when (column) {
    BoardColumn.TODAY -> stringResource(R.string.board_col_today)
    BoardColumn.TOMORROW -> stringResource(R.string.board_col_tomorrow)
    BoardColumn.LATER -> stringResource(R.string.board_col_later)
}

@Composable
private fun emptyLine(column: BoardColumn): String = when (column) {
    BoardColumn.TODAY -> stringResource(R.string.board_empty_today)
    BoardColumn.TOMORROW -> stringResource(R.string.board_empty_tomorrow)
    BoardColumn.LATER -> stringResource(R.string.board_empty_later)
}

/**
 * Стопка «Входящие» (§3): поверхность стопки, три верхние карточки, «ещё N».
 * Пуста — не рендерится вовсе, это решает вызывающий.
 */
@Composable
private fun InboxStack(
    cards: List<BoardCard>,
    hintDone: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpen: (BoardCard) -> Unit,
    onRight: (BoardCard) -> Unit,
) {
    val shown = if (expanded) cards else cards.take(INBOX_VISIBLE)
    val rest = cards.size - shown.size
    Column(
        Modifier
            .fillMaxWidth()
            .background(inboxSurface())
            .padding(start = Space.screen, end = Space.screen, top = Space.m, bottom = Space.s18),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${stringResource(R.string.board_inbox).uppercase()} · ${cards.size}",
                style = Prinyal.type.groupLabel,
                color = Prinyal.colors.inkFaint,
            )
            Text(
                text = stringResource(R.string.board_inbox_hint),
                style = Prinyal.type.metaSmall,
                color = Prinyal.colors.inkFaint,
            )
        }
        if (!hintDone) {
            // Голос продукта — один раз, до первого удачного переноса.
            Text(
                text = stringResource(R.string.board_inbox_voice),
                style = Prinyal.type.voiceSmall,
                color = Prinyal.colors.inkMuted,
                modifier = Modifier.padding(bottom = Space.xs),
            )
        }
        shown.forEach { card ->
            SwipeCard(
                card = card,
                inbox = true,
                column = null,
                onOpen = { onOpen(card) },
                onRight = { onRight(card) },
                onLeft = null,
            )
        }
        if (rest > 0 || expanded) {
            Box(
                Modifier
                    .heightIn(min = Sizes.buttonTertiary)
                    .clip(Radius.control)
                    .clickable(onClick = onToggle)
                    .padding(horizontal = Space.xs),
                contentAlignment = Alignment.CenterStart,
            ) {
                MetaText(
                    text = if (expanded) stringResource(R.string.board_collapse)
                    else stringResource(R.string.board_more, rest),
                    color = Prinyal.colors.inkFaint,
                )
            }
        }
    }
    Divider()
}

@Composable
private fun inboxSurface(): Color =
    if (Prinyal.colors.isDark) Color(0xFF1F1814) else Color(0xFFF6EEE3)

/** Колонка доски: карточки столбцом, пусто — одна строка моно без упрёка (§6). */
@Composable
private fun ColumnCards(
    column: BoardColumn,
    cards: List<BoardCard>,
    onOpen: (BoardCard) -> Unit,
    onRight: (BoardCard) -> Unit,
    onLeft: (BoardCard) -> Unit,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
    dimmed: Boolean = false,
    highlighted: Boolean = false,
    onBounds: ((Rect) -> Unit)? = null,
) {
    Column(
        modifier
            .then(
                if (onBounds != null) Modifier.onGloballyPositioned { onBounds(it.boundsInRoot()) }
                else Modifier
            )
            .then(
                if (highlighted) Modifier
                    .clip(Radius.chip)
                    .background(Prinyal.colors.accentSelf.copy(alpha = 0.08f))
                    .border(2.dp, Prinyal.colors.accentSelf.copy(alpha = 0.45f), Radius.chip)
                else Modifier
            ),
    ) {
        header?.invoke()
        if (cards.isEmpty()) {
            MetaText(
                text = emptyLine(column),
                color = Prinyal.colors.inkFaint,
                modifier = Modifier.padding(vertical = Space.m, horizontal = Space.xs),
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(Space.s),
                contentPadding = PaddingValues(top = Space.sm, bottom = Space.ml),
                modifier = Modifier
                    .weight(1f)
                    .alpha(if (dimmed) 0.5f else 1f),
            ) {
                items(cards, key = { it.item.id }) { card ->
                    SwipeCard(
                        card = card,
                        inbox = false,
                        column = column,
                        onOpen = { onOpen(card) },
                        onRight = if (canSwipeRight(column)) ({ onRight(card) }) else null,
                        onLeft = if (canSwipeLeft(column)) ({ onLeft(card) }) else null,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Карточка и свайп
// ---------------------------------------------------------------------------

/**
 * Карточка со свайпом (§5.1). Порог 96 dp или 800 dp/с; до 64 dp карточка
 * идёт за пальцем 1:1, дальше — с сопротивлением 0.6. Повтор не переносится:
 * резиновый откат без действия (Д-54).
 */
@Composable
private fun SwipeCard(
    card: BoardCard,
    inbox: Boolean,
    column: BoardColumn?,
    onOpen: () -> Unit,
    onRight: (() -> Unit)?,
    onLeft: (() -> Unit)?,
    compact: Boolean = false,
) {
    val density = LocalDensity.current
    val offset = remember(card.item.id) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val fixed = card.repeat != null
    val thresholdPx = with(density) { SWIPE_COMMIT_DP.dp.toPx() }
    val softPx = with(density) { SWIPE_SOFT_DP.dp.toPx() }
    val velocityPx = with(density) { SWIPE_VELOCITY_DP_S.dp.toPx() }
    val rubberPx = with(density) { RUBBER_DP.dp.toPx() }
    var widthPx by remember { mutableFloatStateOf(0f) }

    val direction = when {
        offset.value > 0f -> true
        offset.value < 0f -> false
        else -> null
    }
    val labelRight = targetLabel(column, right = true)
    val labelLeft = targetLabel(column, right = false)

    Box(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { widthPx = it.size.width.toFloat() }
            .pointerInput(card.item.id, fixed, onRight == null, onLeft == null) {
                val tracker = VelocityTracker()
                detectHorizontalDragGestures(
                    onDragStart = { tracker.resetTracking() },
                    onHorizontalDrag = { change, delta ->
                        tracker.addPosition(change.uptimeMillis, change.position)
                        val raw = offset.value + delta
                        val allowed = when {
                            fixed -> raw.coerceIn(-rubberPx, rubberPx)
                            raw > 0 && onRight == null -> raw.coerceAtMost(rubberPx)
                            raw < 0 && onLeft == null -> raw.coerceAtLeast(-rubberPx)
                            else -> raw
                        }
                        // Сопротивление после 64 dp: карточка даёт понять, что
                        // порог близко, а не улетает от одного касания.
                        val eased = if (abs(allowed) > softPx) {
                            val over = abs(allowed) - softPx
                            (softPx + over * SWIPE_RESISTANCE) * if (allowed < 0) -1f else 1f
                        } else allowed
                        scope.launch { offset.snapTo(eased) }
                    },
                    onDragEnd = {
                        val v = tracker.calculateVelocity().x
                        val x = offset.value
                        val commitRight = !fixed && onRight != null &&
                            (x >= thresholdPx || (x > softPx / 2 && v >= velocityPx))
                        val commitLeft = !fixed && onLeft != null &&
                            (x <= -thresholdPx || (x < -softPx / 2 && v <= -velocityPx))
                        scope.launch {
                            when {
                                commitRight -> {
                                    offset.animateTo(widthPx, tween(Motion.Polish.SNACK_IN_MS))
                                    onRight?.invoke()
                                    offset.snapTo(0f)
                                }
                                commitLeft -> {
                                    offset.animateTo(-widthPx, tween(Motion.Polish.SNACK_IN_MS))
                                    onLeft?.invoke()
                                    offset.snapTo(0f)
                                }
                                else -> offset.animateTo(0f, tween(Motion.Polish.FILTER_MS))
                            }
                        }
                    },
                    onDragCancel = { scope.launch { offset.animateTo(0f) } },
                )
            },
    ) {
        // Подложка: акцент 12% с именем колонки и стрелкой на вскрывшейся стороне.
        if (direction != null && !fixed) {
            Row(
                Modifier
                    .matchParentSize()
                    .clip(Radius.chip)
                    .background(Prinyal.colors.accentSelf.copy(alpha = if (Prinyal.colors.isDark) 0.16f else 0.12f))
                    .padding(horizontal = Space.m),
                horizontalArrangement = if (direction) Arrangement.Start else Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(horizontalAlignment = if (direction) Alignment.Start else Alignment.End) {
                    MetaText(
                        text = (if (direction) labelRight else labelLeft).uppercase(),
                        color = Prinyal.colors.accentSelf,
                    )
                    MetaText(text = if (direction) "→" else "←", color = Prinyal.colors.accentSelf)
                }
            }
        }
        CardBody(
            card = card,
            inbox = inbox,
            column = column,
            compact = compact,
            modifier = Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .clickable(onClick = onOpen),
        )
    }
}

/** Тело карточки (§4): текст без обрезки, подпись моно, метки. */
@Composable
private fun CardBody(
    card: BoardCard,
    inbox: Boolean,
    column: BoardColumn?,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val zone = ZoneId.systemDefault()
    val caption = buildList {
        if (!inbox && card.at != null && column == BoardColumn.LATER) {
            add(Dates.day(card.at))
        } else {
            card.topic?.let { add(it) }
            add(Dates.day(card.note.createdAt))
        }
    }.joinToString(" · ")

    Column(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(if (compact) Radius.control else Radius.chip)
            .background(if (inbox) Prinyal.colors.paper else Prinyal.colors.surface)
            .then(
                if (inbox) Modifier.border(1.dp, Prinyal.colors.ink.copy(alpha = 0.1f), Radius.chip)
                else Modifier
            )
            .padding(horizontal = if (compact) Space.s14 else Space.m, vertical = if (compact) Space.sm else Space.s14),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Text(
            text = card.item.text,
            style = if (compact) Prinyal.type.hintSecondary.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            else Prinyal.type.itemTitle,
            color = Prinyal.colors.ink,
        )
        MetaText(text = caption, color = Prinyal.colors.inkFaint)
        if (card.repeat != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Space.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusBadge(text = card.repeat.human(), tone = BadgeTone.Muted)
                MetaText(
                    text = stringResource(R.string.board_repeat_fixed),
                    color = Prinyal.colors.inkFaint,
                )
            }
        } else if (card.overdue && column == BoardColumn.TODAY && card.at != null) {
            StatusBadge(
                text = stringResource(
                    R.string.board_overdue_since,
                    Dates.day(Instant.ofEpochMilli(card.at).atZone(zone).toLocalDate()),
                ),
                tone = BadgeTone.Warn,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Ландшафт: три колонки + стопка слева + drag
// ---------------------------------------------------------------------------

@Composable
private fun LandscapeBoard(
    vm: AppViewModel,
    board: Board,
    hintDone: Boolean,
    onOpen: (BoardCard) -> Unit,
) {
    // Drag (§5.3): карточка в руке, колонка под пальцем подсвечена.
    var dragging by remember { mutableStateOf<BoardCard?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var dragOrigin by remember { mutableStateOf(Offset.Zero) }
    val bounds = remember { mutableMapOf<BoardColumn, Rect>() }
    var target by remember { mutableStateOf<BoardColumn?>(null) }

    fun columnAt(point: Offset): BoardColumn? =
        bounds.entries.firstOrNull { it.value.contains(point) }?.key

    Column(Modifier.fillMaxSize()) {
        if (dragging != null) {
            Box(
                Modifier.fillMaxWidth().padding(horizontal = Space.m, vertical = Space.xs),
                contentAlignment = Alignment.CenterEnd,
            ) {
                MetaText(
                    text = stringResource(R.string.board_drag_hint),
                    color = Prinyal.colors.inkFaint,
                )
            }
        } else if (board.doneWeek > 0) {
            Box(
                Modifier.fillMaxWidth().padding(horizontal = Space.m, vertical = Space.xs),
                contentAlignment = Alignment.CenterEnd,
            ) {
                MetaText(
                    text = stringResource(R.string.board_done_week, board.doneWeek),
                    color = Prinyal.colors.inkFaint,
                )
            }
        }

        Row(
            Modifier
                .fillMaxSize()
                .padding(start = Space.m, end = Space.m, bottom = Space.m),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            if (board.inbox.isNotEmpty()) {
                Column(
                    Modifier
                        .width(INBOX_LANDSCAPE_WIDTH)
                        .fillMaxHeight()
                        .clip(Radius.chip)
                        .background(inboxSurface())
                        .padding(Space.sm)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    Text(
                        text = "${stringResource(R.string.board_inbox).uppercase()} · ${board.inbox.size}",
                        style = Prinyal.type.groupLabel,
                        color = Prinyal.colors.inkFaint,
                    )
                    if (!hintDone) {
                        Text(
                            text = stringResource(R.string.board_inbox_voice),
                            style = Prinyal.type.voiceSmall,
                            color = Prinyal.colors.inkMuted,
                        )
                    }
                    board.inbox.forEach { card ->
                        DraggableCard(
                            card = card,
                            inbox = true,
                            inHand = dragging?.item?.id == card.item.id,
                            offset = dragOffset,
                            onOpen = { onOpen(card) },
                            onRight = { vm.moveTomorrow(card.item.id, fromInbox = true) },
                            onDragStart = { origin ->
                                dragging = card
                                dragOrigin = origin
                                dragOffset = Offset.Zero
                                target = null
                            },
                            onDrag = { delta ->
                                dragOffset += delta
                                target = columnAt(dragOrigin + dragOffset)
                            },
                            onDragEnd = {
                                val t = target
                                val c = dragging
                                dragging = null
                                dragOffset = Offset.Zero
                                target = null
                                if (c != null && t != null) drop(vm, c, t, fromInbox = true, onOpen)
                            },
                        )
                    }
                }
            }

            BoardColumn.entries.forEach { column ->
                ColumnCards(
                    column = column,
                    cards = board.column(column),
                    onOpen = onOpen,
                    onRight = { card -> swipeRight(vm, column, card) },
                    onLeft = { card -> swipeLeft(vm, column, card) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    header = {
                        Column(Modifier.fillMaxWidth().padding(horizontal = Space.xs)) {
                            Row(
                                Modifier.height(44.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Space.s),
                            ) {
                                Text(
                                    text = columnTitle(column),
                                    style = Prinyal.type.noteTitle,
                                    color = Prinyal.colors.ink,
                                )
                                MetaText(
                                    text = board.column(column).size.toString(),
                                    color = if (column == BoardColumn.TODAY) Prinyal.colors.accentSelf
                                    else Prinyal.colors.inkFaint,
                                )
                            }
                            // «Сегодня» держит акцентную линию как текущая (§2).
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(if (column == BoardColumn.TODAY) 2.dp else 1.dp)
                                    .background(
                                        if (column == BoardColumn.TODAY) Prinyal.colors.accentSelf
                                        else Prinyal.colors.rule
                                    )
                            )
                        }
                    },
                    dimmed = dragging != null && target != column,
                    highlighted = target == column,
                    onBounds = { bounds[column] = it },
                )
            }
        }
    }
}

/** Отпускание (§5.3): «Сегодня» — окно по времени, «Завтра» — утро +1, «Позже» — шторка. */
private fun drop(
    vm: AppViewModel,
    card: BoardCard,
    target: BoardColumn,
    fromInbox: Boolean,
    onOpen: (BoardCard) -> Unit,
) {
    if (card.repeat != null) return
    when (target) {
        BoardColumn.TODAY -> vm.moveToday(card.item.id, fromInbox)
        BoardColumn.TOMORROW -> vm.moveTomorrow(card.item.id, fromInbox)
        BoardColumn.LATER -> onOpen(card)
    }
}

/**
 * Карточка стопки в ландшафте: long-press поднимает её в руку, на месте
 * остаётся пунктирный плейсхолдер «карточка в руке».
 */
@Composable
private fun DraggableCard(
    card: BoardCard,
    inbox: Boolean,
    inHand: Boolean,
    offset: Offset,
    onOpen: () -> Unit,
    onRight: () -> Unit,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { origin = it.boundsInRoot().center }
            .pointerInput(card.item.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { onDragStart(origin) },
                    onDrag = { _, delta -> onDrag(delta) },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                )
            },
    ) {
        if (inHand) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .border(1.dp, Prinyal.colors.ink.copy(alpha = 0.25f), Radius.chip)
                    .padding(Space.sm),
                contentAlignment = Alignment.Center,
            ) {
                MetaText(text = stringResource(R.string.board_in_hand), color = Prinyal.colors.inkFaint)
            }
            CardBody(
                card = card,
                inbox = inbox,
                column = null,
                compact = true,
                modifier = Modifier
                    .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
                    .graphicsLayer {
                        rotationZ = -2f
                        shadowElevation = 24f
                    },
            )
        } else {
            SwipeCard(
                card = card,
                inbox = inbox,
                column = null,
                onOpen = onOpen,
                onRight = onRight,
                onLeft = null,
                compact = true,
            )
        }
    }
}

private const val INBOX_VISIBLE = 3
private val PEEK: Dp = 24.dp
private val INBOX_LANDSCAPE_WIDTH: Dp = 228.dp
private const val SWIPE_COMMIT_DP = 96
private const val SWIPE_SOFT_DP = 64
private const val SWIPE_VELOCITY_DP_S = 800
private const val SWIPE_RESISTANCE = 0.6f
private const val RUBBER_DP = 24
