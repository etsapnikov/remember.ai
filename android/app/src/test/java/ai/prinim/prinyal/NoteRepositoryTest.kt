package ai.prinim.prinyal

import ai.prinim.prinyal.data.Analytics
import ai.prinim.prinyal.data.CaptureSource
import ai.prinim.prinyal.data.Confidence
import ai.prinim.prinyal.data.DueKind
import ai.prinim.prinyal.data.ItemState
import ai.prinim.prinyal.data.ItemType
import ai.prinim.prinyal.data.NoteStatus
import ai.prinim.prinyal.data.PrinyalDb
import ai.prinim.prinyal.data.Settings
import ai.prinim.prinyal.data.Window
import ai.prinim.prinyal.domain.NoteRepository
import ai.prinim.prinyal.net.ParseResult
import ai.prinim.prinyal.net.ParsedItem
import ai.prinim.prinyal.returns.ReturnScheduler
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * Поведение петли вокруг записи: что происходит с пунктами и возвратами при разборе,
 * правке, «позже» и молчании пользователя (PRD §F-5, §F-6).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoteRepositoryTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val zone: ZoneId = ZoneId.of("Europe/Moscow")

    private lateinit var db: PrinyalDb
    private lateinit var repo: NoteRepository
    private lateinit var scheduler: RecordingScheduler

    /** Подменяем только AlarmManager: остальной путь остаётся настоящим. */
    private class RecordingScheduler(context: Context) : ReturnScheduler(context) {
        val scheduled = mutableMapOf<String, Instant>()
        val cancelled = mutableListOf<String>()

        override fun schedule(returnId: String, at: Instant) {
            scheduled[returnId] = at
        }

        override fun cancel(returnId: String) {
            scheduled.remove(returnId)
            cancelled += returnId
        }

        override fun canScheduleExact(): Boolean = true
    }

    @Before
    fun setUp() {
        db = PrinyalDb.inMemory(context)
        scheduler = RecordingScheduler(context)
        repo = NoteRepository(db, Settings(context), Analytics(context), scheduler, zone)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun note(id: String = "n1"): String {
        val audio = File(context.filesDir, "$id.m4a").apply { writeText("x") }
        repo.createNote(id, audio, 4_200, CaptureSource.WIDGET, Instant.parse("2026-08-03T11:00:00Z"))
        return id
    }

    private fun parsed(vararg items: ParsedItem) =
        ParseResult("капли соню к лору мужу про субботу", items.toList(), null, 900, 800, 0)

    private fun item(
        type: ItemType = ItemType.DO,
        text: String = "купить капли",
        window: Window? = Window.EVENING,
        dueKind: DueKind = DueKind.WINDOW,
    ) = ParsedItem(type, text, null, dueKind, window, null, Confidence.HIGH, "капли")

    @Test
    fun `разбор кладёт пункты и ставит возвраты`() = runTest {
        val id = note()
        val items = repo.applyParse(id, parsed(item(), item(text = "соня", window = Window.MORNING)))

        assertEquals(2, items.size)
        assertEquals(NoteStatus.PARSED.wire, db.notes().byId(id)!!.status)
        assertEquals(2, scheduler.scheduled.size)
        assertEquals(2, db.returns().all().size)
    }

    @Test
    fun `пункт без срока возврата не получает`() = runTest {
        val id = note()
        repo.applyParse(id, parsed(item(dueKind = DueKind.NONE, window = null)))

        assertTrue(scheduler.scheduled.isEmpty())
        assertTrue(db.returns().all().isEmpty())
    }

    @Test
    fun `повторный разбор не оставляет хвоста от прошлого`() = runTest {
        val id = note()
        repo.applyParse(id, parsed(item(), item(text = "второй")))
        val firstIds = db.returns().all().map { it.id }

        repo.applyParse(id, parsed(item(text = "единственный")))

        assertEquals(1, db.items().forNote(id).size)
        assertEquals(1, db.returns().all().size)
        // Старые алармы сняты, а не забыты — иначе телефон позвонит по призракам.
        assertTrue(scheduler.cancelled.containsAll(firstIds))
    }

    @Test
    fun `правка окна пересчитывает возврат немедленно`() = runTest {
        val id = note()
        val items = repo.applyParse(id, parsed(item(window = Window.EVENING)))
        val before = db.returns().forItem(items[0].id).single()

        repo.editItem(items[0].id, window = Window.TOMORROW_MORNING)

        val after = db.returns().forItem(items[0].id).single()
        assertTrue(after.scheduledAt != before.scheduledAt)
        assertTrue(scheduler.cancelled.contains(before.id))
        assertTrue(db.items().byId(items[0].id)!!.edited)
    }

    @Test
    fun `не надо снимает возврат и закрывает пункт`() = runTest {
        val id = note()
        val items = repo.applyParse(id, parsed(item()))
        val pending = db.returns().forItem(items[0].id).single()

        repo.dismissItem(items[0].id)

        assertEquals(ItemState.DISMISSED.wire, db.items().byId(items[0].id)!!.state)
        assertTrue(scheduler.cancelled.contains(pending.id))
        assertTrue(db.returns().forItem(items[0].id).isEmpty())
    }

    @Test
    fun `позже ставит следующее окно и называет его`() = runTest {
        val id = note()
        val items = repo.applyParse(id, parsed(item()))

        val at = repo.snooze(items[0].id)

        assertNotNull(at)
        assertTrue(at!!.isAfter(Instant.now()))
        assertEquals(ItemState.SNOOZED.wire, db.items().byId(items[0].id)!!.state)
    }

    @Test
    fun `игнор даёт ровно один второй заход, третьего нет`() = runTest {
        val id = note()
        val items = repo.applyParse(id, parsed(item()))
        val first = db.returns().forItem(items[0].id).single()

        repo.markFired(first.id)
        repo.scheduleSecondAttempt(first.id)

        val second = db.returns().forItem(items[0].id).first { it.id != first.id }
        assertEquals(2, second.attempt)

        // Второй тоже проигнорирован — дальше пункт уходит в expired и не пилит.
        repo.markFired(second.id)
        repo.scheduleSecondAttempt(second.id)

        assertEquals(2, db.returns().forItem(items[0].id).size)
        assertEquals(ItemState.EXPIRED.wire, db.items().byId(items[0].id)!!.state)
    }

    @Test
    fun `сработавший возврат переводит пункт в returned и пишет расхождение`() = runTest {
        val id = note()
        val items = repo.applyParse(id, parsed(item()))
        val entity = db.returns().forItem(items[0].id).single()

        repo.markFired(entity.id)

        assertNotNull(db.returns().byId(entity.id)!!.firedAt)
        assertEquals(ItemState.RETURNED.wire, db.items().byId(items[0].id)!!.state)
    }

    @Test
    fun `ошибка ASR не теряет запись`() = runTest {
        val id = note()
        repo.markFailed(id, "asr_failed")

        val stored = db.notes().byId(id)!!
        assertEquals(NoteStatus.FAILED_ASR.wire, stored.status)
        assertEquals("asr_failed", stored.degraded)
        // Аудио на месте — значит разбор можно перезапустить.
        assertTrue(File(stored.audioPath).exists())
    }

    @Test
    fun `запись сразу попадает в очередь на отправку`() = runTest {
        val id = note()
        assertEquals(1, db.notes().pending().size)
        assertEquals(NoteStatus.RECORDED.wire, db.notes().byId(id)!!.status)
    }
}
