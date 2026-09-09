package ai.prinim.prinyal.ui

import ai.prinim.prinyal.capture.UploadWorker
import ai.prinim.prinyal.ui.theme.PrinyalTheme
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Второстепенная поверхность: лента, карточка записи, настройки, сводка недели.
 *
 * «Кнопка важнее приложения» (ТЗ UI §2, п. 1) — 90% использования это жест записи и
 * возвраты в шторке, поэтому здесь нет ни навигационных изысков, ни онбординга.
 */
class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(ai.prinim.prinyal.ui.theme.LocaleForce.wrap(newBase))
    }

    /**
     * Куда просят открыть — извне, а не по нажатию на экране.
     *
     * Отдельным состоянием, потому что интент приходит **дважды**: первый раз в
     * `onCreate`, дальше — в `onNewIntent`, если приложение уже открыто. Второй
     * случай не обрабатывался вовсе: `launchMode=singleTop` отдаёт интент сюда,
     * а экраны читали его только при создании. Человек жал уведомление
     * возврата при открытом приложении — и оставался там, где стоял, будто
     * нажатия не было.
     */
    private val incoming = mutableStateOf<android.content.Intent?>(null)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incoming.value = intent
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val openNoteId = intent.getStringExtra(EXTRA_NOTE_ID)
        val packTopic = intent.getStringExtra(EXTRA_PACK_TOPIC)
        val keepAsking = intent.getBooleanExtra(EXTRA_KEEP_ASKING, false)

        // §6: после исчерпанного backoff очередь разгребается по открытию приложения.
        // Человек открыл ленту посмотреть, почему тихо, — это и есть момент повторить.
        UploadWorker.kick(this)

        setContent {
            PrinyalTheme {
                var route by androidx.compose.runtime.saveable.rememberSaveable(
                    stateSaver = RouteSaver,
                ) {
                    mutableStateOf<Route>(
                        when {
                            intent.getBooleanExtra(EXTRA_OPEN_WEEKLY, false) -> Route.Weekly
                            openNoteId != null -> Route.Note(openNoteId)
                            // Голосом собранный пак открывает выбор записей, а
                            // не готовый файл (Д-28).
                            packTopic != null -> Route.PackPick(packTopic)
                            else -> Route.Feed
                        }
                    )
                }
                val vm: AppViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
                // Открыто без явной цели — вернуться на последнюю корневую поверхность.
                val explicit = intent.getBooleanExtra(EXTRA_OPEN_WEEKLY, false) ||
                    openNoteId != null || packTopic != null
                var rootLoaded by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    if (!explicit && !rootLoaded) {
                        route = vm.lastRoot()
                        rootLoaded = true
                    }
                }
                androidx.compose.runtime.LaunchedEffect(packTopic) {
                    packTopic?.let { vm.openPackPickByTopic(it) }
                }
                // Ничего будить не нужно: разговор живёт в базе, и экран
                // заметки сам покажет, на какой он стадии. Прежде здесь
                // взводился следующий вопрос — и спрашивал по старому тексту,
                // потому что ответ ещё не был записан.
                // Пришли извне при уже открытом приложении — ведём туда же,
                // куда повёл бы холодный запуск.
                val next by incoming
                androidx.compose.runtime.LaunchedEffect(next) {
                    val fresh = next ?: return@LaunchedEffect
                    incoming.value = null
                    fresh.getStringExtra(EXTRA_NOTE_ID)?.let { id ->
                        route = Route.Note(id)
                        return@LaunchedEffect
                    }
                    if (fresh.getBooleanExtra(EXTRA_OPEN_WEEKLY, false)) {
                        route = Route.Weekly
                        return@LaunchedEffect
                    }
                    fresh.getStringExtra(EXTRA_PACK_TOPIC)?.let { topic ->
                        vm.openPackPickByTopic(topic)
                        route = Route.PackPick(topic)
                    }
                }

                AppScaffold(route = route, onRoute = { route = it })
            }
        }
    }

    companion object {
        /** Тема пака из голосовой команды (Д-28): открываем выбор, а не файл. */
        const val EXTRA_PACK_TOPIC = "pack_topic"

        const val EXTRA_NOTE_ID = "note_id"

        /** «Готов итог недели» (Р-18.3): уведомление ведёт в «Неделю». */
        const val EXTRA_OPEN_WEEKLY = "open_weekly"

        /**
         * Вернулись из ответа на вопрос — разговор продолжается.
         *
         * Флаг живёт здесь, а не во вьюмодели: между вопросом и ответом
         * приложение успевает умереть (экран записи убирает задачу), и любое
         * состояние в памяти к этому моменту уже потеряно.
         */
        const val EXTRA_KEEP_ASKING = "keep_asking"
    }
}

/**
 * Маршрут переживает поворот экрана. Без этого «Дела» в ландшафте открывались
 * лентой: rememberSaveable нужен Saver, а у Route есть параметры.
 */
val RouteSaver: androidx.compose.runtime.saveable.Saver<Route, List<String>> =
    androidx.compose.runtime.saveable.Saver(
        save = { route ->
            when (route) {
                Route.Feed -> listOf("feed")
                Route.Settings -> listOf("settings")
                Route.Weekly -> listOf("weekly")
                Route.Topics -> listOf("topics")
                Route.Days -> listOf("days")
                Route.People -> listOf("people")
                is Route.Topic -> listOf("topic", route.id ?: "", route.name)
                is Route.Person -> listOf("person", route.id, route.name)
                is Route.PackPick -> listOf("pack", route.title)
                is Route.Note -> listOf("note", route.id)
            }
        },
        restore = { parts ->
            when (parts.firstOrNull()) {
                "settings" -> Route.Settings
                "weekly" -> Route.Weekly
                "topics" -> Route.Topics
                "days" -> Route.Days
                "people" -> Route.People
                "topic" -> Route.Topic(parts.getOrNull(1)?.ifEmpty { null }, parts.getOrNull(2).orEmpty())
                "person" -> Route.Person(parts.getOrNull(1).orEmpty(), parts.getOrNull(2).orEmpty())
                "pack" -> Route.PackPick(parts.getOrNull(1).orEmpty())
                "note" -> Route.Note(parts.getOrNull(1).orEmpty())
                else -> Route.Feed
            }
        },
    )

sealed interface Route {
    data object Feed : Route
    data object Settings : Route
    data object Weekly : Route
    /** Список разделов — третья поверхность (Д-1). */
    data object Topics : Route

    /** «Дни» (Р-18.1) — вечерние ответы, четвёртая поверхность. */
    data object Days : Route
    /** Заметки одного раздела. `id == null` — «Без раздела». */
    data class Topic(val id: String?, val name: String) : Route

    /** Список людей и карточка человека (Д-26, Д-27). */
    data object People : Route
    data class Person(val id: String, val name: String) : Route

    /** Выбор записей в пак (Д-28) — экран, а не диалог: список бывает длинным. */
    data class PackPick(val title: String) : Route
    data class Note(val id: String) : Route
}
