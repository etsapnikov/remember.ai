package ai.prinim.prinyal

import ai.prinim.prinyal.data.Analytics
import ai.prinim.prinyal.data.CaptureSource
import ai.prinim.prinyal.data.Confidence
import ai.prinim.prinyal.data.DueKind
import ai.prinim.prinyal.data.ItemType
import ai.prinim.prinyal.data.PrinyalDb
import ai.prinim.prinyal.data.Settings
import ai.prinim.prinyal.domain.NoteRepository
import ai.prinim.prinyal.net.ParseResult
import ai.prinim.prinyal.net.ParsedItem
import ai.prinim.prinyal.returns.ReturnScheduler
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Точная дата возврата от валидатора до расписания (Р-15.7).
 *
 * Здесь закрыт целый класс ошибок, который уже дважды нас кусал: единица
 * времени. Валидатор отдавал секунды, экран читал миллисекунды — дата уезжала
 * в январь 1970. Починили экран — стало ломаться расписание. Теперь единица
 * одна на всём пути, и проверяется она **сквозным** тестом, а не по месту:
 * тест по месту в прошлый раз как раз и закрепил ошибку.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExactDateTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val zone: ZoneId = ZoneId.systemDefault()

    private lateinit var db: PrinyalDb
    private lateinit var repo: NoteRepository
    private val scheduled = mutableListOf<Instant>()

    @Before
    fun setUp() {
        db = PrinyalDb.inMemory(context)
        scheduled.clear()
        repo = NoteRepository(
            db = db,
            settings = Settings(context),
            analytics = Analytics(context),
            scheduler = object : ReturnScheduler(context) {
                override fun schedule(returnId: String, at: Instant) { scheduled += at }
                override fun cancel(returnId: String) = Unit
                override fun canScheduleExact() = true
            },
        )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun noteWithDate(at: LocalDateTime): String {
        val id = repo.newId()
        val audio = File(context.cacheDir, "$id.m4a").apply { writeText("x") }
        repo.createNote(id, audio, 3_000, CaptureSource.ICON, Instant.now())
        repo.applyParse(
            id,
            ParseResult(
                transcript = "оплатить счёт",
                items = listOf(
                    ParsedItem(
                        type = ItemType.DO,
                        text = "оплатить счёт",
                        who = null,
                        dueKind = DueKind.EXACT,
                        window = null,
                        dueAt = at.atZone(zone).toInstant().toEpochMilli(),
                        confidence = Confidence.HIGH,
                        rawSpan = "оплатить счёт",
                    )
                ),
                degraded = null,
                asrMs = 0,
                llmMs = 0,
                llmRetries = 0,
            ),
        )
        return id
    }

    @Test
    fun `дата доезжает до расписания тем же днём`() = runTest {
        // Сквозная проверка единицы: если где-то на пути секунды спутаны с
        // миллисекундами, день разъедется на десятилетия.
        val target = LocalDateTime.now().plusDays(20).withHour(9).withMinute(0)
        noteWithDate(target)

        assertEquals(1, scheduled.size)
        assertEquals(
            target.toLocalDate(),
            scheduled.single().atZone(zone).toLocalDate(),
        )
    }

    @Test
    fun `ручная дата переживает переразбор`() = runTest {
        // Человек сказал «верну десятого» — модель не вправе с ним спорить.
        // Правило то же, что у топика: тронутое рукой машина не трогает.
        val target = LocalDateTime.now().plusDays(30).withHour(9).withMinute(0)
        val id = noteWithDate(target)

        val item = db.items().forNote(id).single()
        val hand = LocalDateTime.now().plusDays(45).withHour(9).withMinute(0)
        db.items().update(
            item.copy(dueAt = hand.atZone(zone).toInstant().toEpochMilli(), edited = true)
        )

        // Переразбор: модель вернула тот же пункт, но уже без даты.
        repo.applyParse(
            id,
            ParseResult(
                transcript = "оплатить счёт",
                items = listOf(
                    ParsedItem(
                        type = ItemType.DO,
                        text = "оплатить счёт",
                        who = null,
                        dueKind = DueKind.WINDOW,
                        window = ai.prinim.prinyal.data.Window.DAY,
                        dueAt = null,
                        confidence = Confidence.HIGH,
                        rawSpan = "оплатить счёт",
                    )
                ),
                degraded = null,
                asrMs = 0,
                llmMs = 0,
                llmRetries = 0,
            ),
        )

        val after = db.items().forNote(id).single()
        assertEquals(DueKind.EXACT.wire, after.dueKind)
        assertEquals(
            hand.toLocalDate(),
            Instant.ofEpochMilli(after.dueAt!!).atZone(zone).toLocalDate(),
        )
        assertTrue("пометка не переехала — следующий переразбор сотрёт дату", after.edited)
    }

    @Test
    fun `возврат никогда не назначается в прошлое`() = runTest {
        // Возврат в прошлом не «сработает сразу» — он не сработает вовсе или
        // прозвонит ночью.
        val id = repo.newId()
        val audio = File(context.cacheDir, "$id.m4a").apply { writeText("x") }
        repo.createNote(id, audio, 3_000, CaptureSource.ICON, Instant.now())
        repo.applyParse(
            id,
            ParseResult(
                transcript = "старое дело",
                items = listOf(
                    ParsedItem(
                        type = ItemType.DO,
                        text = "старое дело",
                        who = null,
                        dueKind = DueKind.EXACT,
                        window = null,
                        // Запись могла пролежать в очереди дольше, чем срок.
                        dueAt = Instant.now().minusSeconds(3 * 24 * 3600).toEpochMilli(),
                        confidence = Confidence.HIGH,
                        rawSpan = "старое дело",
                    )
                ),
                degraded = null,
                asrMs = 0,
                llmMs = 0,
                llmRetries = 0,
            ),
        )

        assertTrue("возврат не запланирован вовсе", scheduled.isNotEmpty())
        assertTrue(
            "возврат назначен в прошлое: ${scheduled.single()}",
            scheduled.single().isAfter(Instant.now()),
        )
    }
}
