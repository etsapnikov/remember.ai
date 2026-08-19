package ai.prinim.prinyal

import ai.prinim.prinyal.domain.NoteRepository
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Возврат в невозможном будущем.
 *
 * На живой базе нашлись три возврата, назначенных на 58601 год: сборка 1.0.1
 * считала точную дату в секундах, а расписание читало её как миллисекунды.
 * Такой возврат не приходит никогда и при этом выглядит запланированным —
 * худший вид поломки.
 */
class ReturnHorizonTest {

    private val now: Instant = Instant.parse("2026-08-19T12:00:00Z")

    /** То же вычисление, что в rescheduleAll: горизонт от «сейчас». */
    private fun beyondHorizon(scheduledAt: Long): Boolean =
        Instant.ofEpochMilli(scheduledAt)
            .isAfter(now.plus(NoteRepository.MAX_HORIZON_DAYS, ChronoUnit.DAYS))

    @Test
    fun `дата в миллисекундах, прочитанная как секунды, ловится горизонтом`() {
        // 1 790 575 200 000 мс — 28 сентября 2026. Умноженное на тысячу, это
        // 58601 год: ровно то, что лежало в базе.
        val broken = 1_790_575_200_000L * 1000
        assertTrue("сломанная дата прошла", beyondHorizon(broken))
    }

    @Test
    fun `нормальные сроки горизонт не трогает`() {
        listOf(
            now.plusSeconds(60),
            now.plus(30, ChronoUnit.DAYS),
            // Год вперёд — предел здравого смысла, но всё ещё настоящая дата.
            now.plus(365, ChronoUnit.DAYS),
        ).forEach {
            assertTrue("здоровый срок объявлен сломанным: $it", !beyondHorizon(it.toEpochMilli()))
        }
    }
}
