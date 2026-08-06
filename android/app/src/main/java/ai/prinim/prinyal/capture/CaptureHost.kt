package ai.prinim.prinyal.capture

import ai.prinim.prinyal.R
import ai.prinim.prinyal.ui.AppScaffold
import ai.prinim.prinyal.ui.Route
import ai.prinim.prinyal.ui.theme.Gesture
import ai.prinim.prinyal.ui.theme.MetaText
import ai.prinim.prinyal.ui.theme.Motion
import ai.prinim.prinyal.ui.theme.Prinyal
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import ai.prinim.prinyal.ui.theme.Space
import kotlinx.coroutines.launch

/**
 * Переход запись ↔ лента (спека R1.1 §7): не подмена активити, а лист в одном окне.
 *
 * Лента следует за пальцем 1:1; экран записи одновременно уходит вверх на 0,35 от
 * смещения, гаснет до 25% и сжимается до 0,95. Пороги — из токенов `gesture`.
 * Запись при жесте не останавливается: в ленте сверху строка «Идёт запись».
 *
 * Отступление от спеки: отмена жеста едет тем же пресетом, что и долёт
 * (follow-settle) — у AnchoredDraggable один snap-пресет на оба исхода. На 200 мс
 * разницы глазом это не читается; вернёмся, если начнёт.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CaptureHost(
    state: CaptureState,
    hasNotes: Boolean,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onGrant: () -> Unit,
    onFeedOpened: () -> Unit,
) {
    val density = LocalDensity.current
    var heightPx by remember { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()

    val drag = remember(heightPx) {
        AnchoredDraggableState(
            initialValue = SheetValue.Hidden,
            anchors = DraggableAnchors {
                SheetValue.Hidden at heightPx
                SheetValue.Shown at 0f
            },
            positionalThreshold = { _ ->
                with(density) { Gesture.SWIPE_COMMIT_VERTICAL_DP.dp.toPx() }
            },
            velocityThreshold = {
                with(density) { Gesture.SWIPE_COMMIT_VERTICAL_VELOCITY.dp.toPx() }
            },
            snapAnimationSpec = tween(
                Motion.FollowSettle.durationMs,
                easing = Motion.FollowSettle.easing,
            ),
            decayAnimationSpec = androidx.compose.animation.core.exponentialDecay(),
        )
    }

    val sheetOpen = drag.currentValue == SheetValue.Shown
    LaunchedEffect(sheetOpen) {
        if (sheetOpen) onFeedOpened()
    }

    // «Назад» из открытой ленты — та же механика вниз, не выход из приложения.
    BackHandler(enabled = sheetOpen) {
        scope.launch { drag.animateTo(SheetValue.Hidden) }
    }

    // Прокрутка ленты и жест листа договариваются: лист тянется вниз только когда
    // список уже упёрся в верх — стандартный контракт bottom-sheet.
    val nestedScroll = remember(drag) {
        object : NestedScrollConnection {
            override fun onPreScroll(
                available: androidx.compose.ui.geometry.Offset,
                source: NestedScrollSource,
            ): androidx.compose.ui.geometry.Offset {
                val delta = available.y
                return if (delta < 0 && drag.requireOffset() > 0f) {
                    androidx.compose.ui.geometry.Offset(
                        0f, drag.dispatchRawDelta(delta) / 1f,
                    )
                } else {
                    androidx.compose.ui.geometry.Offset.Zero
                }
            }

            override fun onPostScroll(
                consumed: androidx.compose.ui.geometry.Offset,
                available: androidx.compose.ui.geometry.Offset,
                source: NestedScrollSource,
            ): androidx.compose.ui.geometry.Offset {
                val delta = available.y
                return if (delta > 0) {
                    androidx.compose.ui.geometry.Offset(0f, drag.dispatchRawDelta(delta))
                } else {
                    androidx.compose.ui.geometry.Offset.Zero
                }
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                return if (drag.requireOffset() > 0f && drag.requireOffset() < heightPx) {
                    drag.settle(available.y)
                    Velocity(0f, available.y)
                } else {
                    Velocity.Zero
                }
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (drag.requireOffset() > 0f && drag.requireOffset() < heightPx) {
                    drag.settle(available.y)
                }
                return available
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { heightPx = it.height.toFloat() },
    ) {
        val offset = if (heightPx > 0f) drag.requireOffset().coerceIn(0f, heightPx) else heightPx
        val progress = if (heightPx > 0f) 1f - offset / heightPx else 0f

        // Экран записи: уходит вверх, гаснет, чуть сжимается (§7).
        // Жест руками, а не anchoredDraggable: вверх — тянем лист, вниз — отмена
        // записи; одним модификатором AnchoredDraggable оба направления не развести.
        var downAccum by remember { mutableStateOf(0f) }
        var cancelled by remember { mutableStateOf(false) }
        val cancelPx = with(density) { 24.dp.toPx() }

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = -(heightPx - offset) * 0.35f
                    alpha = 1f - progress * 0.75f
                    scaleX = 1f - progress * 0.05f
                    scaleY = 1f - progress * 0.05f
                }
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta ->
                        if (delta > 0 && drag.requireOffset() >= heightPx - 0.5f) {
                            // Лист закрыт, палец идёт вниз — это жест отмены записи.
                            downAccum += delta
                            if (downAccum > cancelPx && state.recording && !cancelled) {
                                cancelled = true
                                onCancel()
                            }
                        } else {
                            drag.dispatchRawDelta(delta)
                        }
                    },
                    onDragStarted = {
                        downAccum = 0f
                        cancelled = false
                    },
                    onDragStopped = { velocity -> drag.settle(velocity) },
                ),
        ) {
            CaptureScreen(
                state = state,
                hasNotes = hasNotes,
                onStop = onStop,
                onCancel = onCancel,
                onGrant = onGrant,
            )
        }

        // Лента-лист: приезжает за пальцем.
        if (progress > 0.001f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationY = offset }
                    .background(Prinyal.colors.paper)
                    .nestedScroll(nestedScroll),
            ) {
                Column(Modifier.fillMaxSize()) {
                    if (state.recording) {
                        RecordingBanner(
                            elapsedMs = state.elapsedMs,
                            onReturn = { scope.launch { drag.animateTo(SheetValue.Hidden) } },
                        )
                    }
                    FeedPane()
                }
            }
        }
    }
}

private enum class SheetValue { Hidden, Shown }

/** «Идёт запись · 0:02» — тап возвращает к кнопке той же механикой (§7). */
@Composable
private fun RecordingBanner(elapsedMs: Long, onReturn: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .background(Prinyal.colors.accentSelfSoft)
            .clickable(onClick = onReturn)
            .padding(horizontal = Space.screen, vertical = Space.s),
    ) {
        MetaText(
            text = "%s · %d:%02d".format(
                stringResource(R.string.feed_recording_now),
                elapsedMs / 60_000,
                (elapsedMs / 1000) % 60,
            ),
            color = Prinyal.colors.accentSelf,
        )
    }
}

/** Лента и её экраны — тот же каркас, что открывается из уведомлений. */
@Composable
private fun FeedPane() {
    var current by remember { mutableStateOf<Route>(Route.Feed) }
    AppScaffold(route = current, onRoute = { current = it })
}
