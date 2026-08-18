package ai.prinim.prinyal

import ai.prinim.prinyal.data.Analytics
import ai.prinim.prinyal.data.CaptureSource
import ai.prinim.prinyal.data.Confidence
import ai.prinim.prinyal.data.DueKind
import ai.prinim.prinyal.data.ItemState
import ai.prinim.prinyal.data.ItemType
import ai.prinim.prinyal.data.PrinyalDb
import ai.prinim.prinyal.data.Settings
import ai.prinim.prinyal.data.Window
import ai.prinim.prinyal.domain.NoteRepository
import ai.prinim.prinyal.net.ParseResult
import ai.prinim.prinyal.net.ParsedItem
import ai.prinim.prinyal.returns.ReturnScheduler
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
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
 * Разбиение записи на две заметки и склейка обратно (Р-15.5).
 *
 * Проверяем не «модель поделила», а что деление и склейка не теряют данные:
 * это операции над корпусом владельца, и ошибка здесь необратима.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SplitMergeTest {

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
        repo.createNote(id, audio, 9_000, CaptureSource.ICON, Instant.parse("2026-08-17T10:00:00Z"))
        return id
    }

    private fun item(text: String) = ParsedItem(
        type = ItemType.DO,
        text = text,
        who = null,
        dueKind = DueKind.WINDOW,
        window = Window.DAY,
        dueAt = null,
        confidence = Confidence.HIGH,
        rawSpan = text,
    )

    private fun result(vararg texts: String, second: ParseResult? = null) = ParseResult(
        transcript = "налоговая и сайт студии",
        items = texts.map(::item),
        second = second,
        degraded = null,
        asrMs = 0,
        llmMs = 0,
        llmRetries = 0,
    )

    @Test
    fun `без second заметка остаётся одна`() = runTest {
        // Случай по умолчанию и самый частый: делить нечего.
        val id = note()
        repo.applyParse(id, result("собрать справки"))

        assertEquals(1, db.notes().feedOnce().size)
        assertNull(db.notes().byId(id)!!.siblingId)
    }

    @Test
    fun `две темы дают две заметки, связанные друг с другом`() = runTest {
        val id = note()
        repo.applyParse(
            id,
            result("собрать справки", second = result("сменить шрифты", "написать тексты")),
        )

        val notes = db.notes().feedOnce()
        assertEquals(2, notes.size)

        val first = db.notes().byId(id)!!
        val secondId = first.siblingId
        assertNotNull("первая половина не знает о второй", secondId)
        assertEquals("связь односторонняя", id, db.notes().byId(secondId!!)!!.siblingId)
        assertEquals(2, db.items().forNote(secondId).size)
    }

    @Test
    fun `склейка возвращает одну заметку и все пункты`() = runTest {
        val id = note()
        repo.applyParse(
            id,
            result("собрать справки", second = result("сменить шрифты", "написать тексты")),
        )
        repo.mergeSiblings(id)

        val notes = db.notes().feedOnce()
        assertEquals(1, notes.size)
        assertEquals(3, db.items().forNote(id).size)
        assertNull(db.notes().byId(id)!!.siblingId)
    }

    @Test
    fun `склейка не переигрывает прожитое`() = runTest {
        val id = note()
        repo.applyParse(id, result("собрать справки", second = result("сменить шрифты")))
        val secondId = db.notes().byId(id)!!.siblingId!!
        val done = db.items().forNote(secondId).single()
        repo.markDone(done.id)

        repo.mergeSiblings(id)

        // Закрытое остаётся закрытым: склейка исправляет разметку, а не жизнь.
        val moved = db.items().forNote(id).first { it.text == "сменить шрифты" }
        assertEquals(ItemState.DONE.wire, moved.state)
    }

    @Test
    fun `склейка с любой половины даёт один результат`() = runTest {
        val id = note()
        repo.applyParse(id, result("собрать справки", second = result("сменить шрифты")))
        val secondId = db.notes().byId(id)!!.siblingId!!

        // Человек мог открыть вторую половину — склейка обязана работать и оттуда.
        repo.mergeSiblings(secondId)

        assertEquals(1, db.notes().feedOnce().size)
        assertEquals(2, db.items().forNote(id).size)
    }
}

/** Лента одним снимком: в тестах поток не нужен. */
private suspend fun ai.prinim.prinyal.data.NoteDao.feedOnce() =
    feed().first()
