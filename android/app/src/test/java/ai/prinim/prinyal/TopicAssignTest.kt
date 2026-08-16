package ai.prinim.prinyal

import ai.prinim.prinyal.data.Analytics
import ai.prinim.prinyal.data.CaptureSource
import ai.prinim.prinyal.data.Confidence
import ai.prinim.prinyal.data.DueKind
import ai.prinim.prinyal.data.ItemType
import ai.prinim.prinyal.data.PrinyalDb
import ai.prinim.prinyal.data.Settings
import ai.prinim.prinyal.data.TopicSource
import ai.prinim.prinyal.data.Window
import ai.prinim.prinyal.domain.NoteRepository
import ai.prinim.prinyal.net.ParsedItem
import ai.prinim.prinyal.net.ParseResult
import ai.prinim.prinyal.returns.ReturnScheduler
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant

/**
 * Отнесение заметки к разделу.
 *
 * Проверяем три правила, каждое из которых чинит свою беду: дубли по регистру,
 * вечную правку руками и разрастание разделов до шума.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TopicAssignTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private lateinit var db: PrinyalDb
    private lateinit var repo: NoteRepository

    @Before
    fun setUp() {
        db = PrinyalDb.inMemory(context)
        repo = NoteRepository(
            db = db,
            settings = Settings(context),
            analytics = Analytics(context),
            scheduler = object : ReturnScheduler(context) {
                override fun schedule(returnId: String, at: Instant) = Unit
                override fun cancel(returnId: String) = Unit
                override fun canScheduleExact() = true
            },
        )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun note(): String {
        val id = repo.newId()
        val audio = File(context.cacheDir, "$id.m4a").apply { writeText("x") }
        repo.createNote(id, audio, 3_000, CaptureSource.ICON, Instant.parse("2026-08-16T10:00:00Z"))
        return id
    }

    private fun result(topic: String?) = ParseResult(
        transcript = "надо забор докрасить",
        items = listOf(
            ParsedItem(
                type = ItemType.DO,
                text = "докрасить забор",
                who = null,
                dueKind = DueKind.WINDOW,
                window = Window.WEEKEND,
                dueAt = null,
                confidence = Confidence.HIGH,
                rawSpan = "забор докрасить",
            )
        ),
        topic = topic,
        degraded = null,
        asrMs = 0,
        llmMs = 0,
        llmRetries = 0,
    )

    @Test
    fun `первая запись заводит раздел, вторая переиспользует его`() = runTest {
        val first = note()
        repo.applyParse(first, result("Дача"))

        val second = note()
        // Другой регистр и лишние пробелы — тот же раздел, иначе структура
        // расползается на второй же записи.
        repo.applyParse(second, result("  дача "))

        assertEquals(1, db.topics().live().size)
        assertEquals(
            db.notes().byId(first)!!.topicId,
            db.notes().byId(second)!!.topicId,
        )
    }

    @Test
    fun `ручной раздел переразбором не перезаписывается`() = runTest {
        val id = note()
        repo.applyParse(id, result("Дача"))

        // Человек отнёс сам — дальше машина не трогает.
        db.notes().setTopic(id, null, TopicSource.USER.wire)
        repo.applyParse(id, result("Работа"))

        val note = db.notes().byId(id)!!
        assertNull("ручной выбор потерян", note.topicId)
        assertEquals(TopicSource.USER.wire, note.topicSource)
    }

    @Test
    fun `машинный раздел помечен как машинный`() = runTest {
        val id = note()
        repo.applyParse(id, result("Дача"))

        val note = db.notes().byId(id)!!
        assertNotNull(note.topicId)
        assertEquals(TopicSource.LLM.wire, note.topicSource)
    }

    @Test
    fun `без раздела запись остаётся без раздела, а не в случайном`() = runTest {
        val id = note()
        repo.applyParse(id, result(null))

        assertNull(db.notes().byId(id)!!.topicId)
        assertEquals(0, db.topics().live().size)
    }

    @Test
    fun `упёрлись в потолок — новый раздел не заводится`() = runTest {
        // Без потолка модель заводит новый раздел почти под каждую запись.
        repeat(NoteRepository.MAX_AUTO_TOPICS) { index ->
            repo.applyParse(note(), result("Раздел$index"))
        }
        assertEquals(NoteRepository.MAX_AUTO_TOPICS, db.topics().live().size)

        val extra = note()
        repo.applyParse(extra, result("Двадцать пятый"))

        assertEquals(NoteRepository.MAX_AUTO_TOPICS, db.topics().live().size)
        assertNull("заметка ушла в случайный раздел", db.notes().byId(extra)!!.topicId)
    }
}
