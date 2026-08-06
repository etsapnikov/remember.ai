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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val openNoteId = intent.getStringExtra(EXTRA_NOTE_ID)

        // §6: после исчерпанного backoff очередь разгребается по открытию приложения.
        // Человек открыл ленту посмотреть, почему тихо, — это и есть момент повторить.
        UploadWorker.kick(this)

        setContent {
            PrinyalTheme {
                var route by remember {
                    mutableStateOf<Route>(
                        if (openNoteId != null) Route.Note(openNoteId) else Route.Feed
                    )
                }
                AppScaffold(route = route, onRoute = { route = it })
            }
        }
    }

    companion object {
        const val EXTRA_NOTE_ID = "note_id"
    }
}

sealed interface Route {
    data object Feed : Route
    data object Settings : Route
    data object Weekly : Route
    data class Note(val id: String) : Route
}
