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
                var route by remember {
                    mutableStateOf<Route>(
                        when {
                            openNoteId != null -> Route.Note(openNoteId)
                            // Голосом собранный пак открывает выбор записей, а
                            // не готовый файл (Д-28).
                            packTopic != null -> Route.PackPick(packTopic)
                            else -> Route.Feed
                        }
                    )
                }
                val vm: AppViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
                androidx.compose.runtime.LaunchedEffect(packTopic) {
                    packTopic?.let { vm.openPackPickByTopic(it) }
                }
                // Следующий вопрос ждёт разбора ответа: спрашивать по старому
                // тексту значит спросить ровно то же самое второй раз.
                androidx.compose.runtime.LaunchedEffect(keepAsking, openNoteId) {
                    if (keepAsking && openNoteId != null) vm.resumeInterview(openNoteId)
                }
                // Пришли извне при уже открытом приложении — ведём туда же,
                // куда повёл бы холодный запуск.
                val next by incoming
                androidx.compose.runtime.LaunchedEffect(next) {
                    val fresh = next ?: return@LaunchedEffect
                    incoming.value = null
                    fresh.getStringExtra(EXTRA_NOTE_ID)?.let { id ->
                        route = Route.Note(id)
                        if (fresh.getBooleanExtra(EXTRA_KEEP_ASKING, false)) {
                            vm.resumeInterview(id)
                        }
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

sealed interface Route {
    data object Feed : Route
    data object Settings : Route
    data object Weekly : Route
    /** Список разделов — третья поверхность (Д-1). */
    data object Topics : Route
    /** Заметки одного раздела. `id == null` — «Без раздела». */
    data class Topic(val id: String?, val name: String) : Route

    /** Список людей и карточка человека (Д-26, Д-27). */
    data object People : Route
    data class Person(val id: String, val name: String) : Route

    /** Выбор записей в пак (Д-28) — экран, а не диалог: список бывает длинным. */
    data class PackPick(val title: String) : Route
    data class Note(val id: String) : Route
}
