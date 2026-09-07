package ai.prinim.prinyal

import ai.prinim.prinyal.returns.DayAsk
import ai.prinim.prinyal.returns.DayAskReceiver
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Кор-фича «рефлексия по дню»: вечером продукт спрашивает, как прошёл день.
 *
 * Фича, которую владелец однажды не получал неделю подряд. Причина оказалась не
 * в одной ошибке, а в трёх сразу: аларм пересобирался на каждом старте
 * приложения и после 21:30 уезжал на завтра — то есть каждый вечерний деплой
 * отменял вопрос того же вечера; страховки в виде догоняющего воркера у «Дней»
 * не было вовсе; а отметка «сегодня уже спросили» жила в двух местах и не
 * мешала спросить дважды.
 *
 * Ни одно из трёх не ловилось тестами: расписание не проверял никто.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DayReflectionTest {

    private val app: PrinyalApp get() = ApplicationProvider.getApplicationContext()
    private val zone: ZoneId = ZoneId.of("Europe/Moscow")

    private val alarms: AlarmManager get() = app.getSystemService(AlarmManager::class.java)
    private val notifications: NotificationManager
        get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Before
    fun clean() {
        Thread { app.db.clearAllTables() }.apply { start(); join() }
        notifications.cancelAll()
    }

    private fun <T> io(block: suspend () -> T): T {
        var result: Result<T>? = null
        Thread { result = runCatching { kotlinx.coroutines.runBlocking { block() } } }
            .apply { start(); join() }
        return result!!.getOrThrow()
    }

    private fun eventually(what: String, check: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(app.mainLooper).idle()
            Thread.sleep(20)
            if (io(check)) return
        }
        throw AssertionError("не дождались: $what")
    }

    /** Ближайший назначенный вечерний вопрос. */
    private fun askAt(): ZonedDateTime? = shadowOf(alarms).scheduledAlarms
        .minByOrNull { it.triggerAtTime }
        ?.let { ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(it.triggerAtTime), zone) }

    private fun ask() {
        app.sendBroadcast(
            Intent(app, DayAskReceiver::class.java).setAction(DayAsk.ACTION_ASK),
        )
        shadowOf(app.mainLooper).idle()
    }

    @Test
    fun `вопрос назначен на сегодня, если время ещё не прошло`() {
        shadowOf(alarms).scheduledAlarms.clear()
        DayAsk.schedule(app, LocalTime.of(23, 59), zone)

        val at = askAt()
        assertTrue("вопрос не назначен вовсе", at != null)
        assertEquals(
            "вопрос уехал не на сегодня — вечер потерян",
            LocalDate.now(zone), at!!.toLocalDate(),
        )
    }

    @Test
    fun `вопрос переносится на завтра, только если время уже прошло`() {
        shadowOf(alarms).scheduledAlarms.clear()
        DayAsk.schedule(app, LocalTime.of(0, 1), zone)

        // Полночь минуту назад — сегодня спрашивать поздно, завтра в самый раз.
        // Ровно этот перенос и съедал вечер, когда срабатывал не по делу.
        assertEquals(LocalDate.now(zone).plusDays(1), askAt()!!.toLocalDate())
    }

    @Test
    fun `настал вечер — вопрос пришёл`() {
        ask()

        eventually("вопрос показан") {
            shadowOf(notifications).activeNotifications.isNotEmpty()
        }
    }

    @Test
    fun `дважды за вечер не спрашиваем`() {
        ask()
        // Ждём именно отметку, а не картинку в шторке: показ и отметка
        // ставятся рядом, но отметка и есть то, что удержит второй вопрос.
        eventually("первый вопрос отмечен") {
            app.settings.dayAskedOn() == LocalDate.now().toString()
        }
        notifications.cancelAll()

        ask()
        Thread.sleep(400)
        shadowOf(app.mainLooper).idle()

        assertTrue(
            "спросили во второй раз за вечер",
            shadowOf(notifications).activeNotifications.isEmpty(),
        )
    }

    @Test
    fun `уже рассказал про день сам — вечером не дёргаем`() {
        io {
            app.repository.saveDay(
                LocalDate.now().toString(),
                java.io.File(app.filesDir, "day.m4a").apply { writeText("день вышел длинный") },
                5_000,
                java.time.Instant.now(),
            )
        }

        ask()
        Thread.sleep(400)
        shadowOf(app.mainLooper).idle()

        assertTrue(
            "спросили о дне, про который человек уже рассказал",
            shadowOf(notifications).activeNotifications.isEmpty(),
        )
    }

    @Test
    fun `отметка «спросили» переживает перезапуск`() {
        ask()
        eventually("спросили") { app.settings.dayAskedOn() == LocalDate.now().toString() }

        // Отметка общая для аларма и догоняющего воркера. Была бы у каждого
        // своя — вечер приносил бы два одинаковых вопроса подряд.
        assertEquals(LocalDate.now().toString(), io { app.settings.dayAskedOn() })
    }
}
