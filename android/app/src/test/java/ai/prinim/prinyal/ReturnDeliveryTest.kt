package ai.prinim.prinyal

import ai.prinim.prinyal.data.Confidence
import ai.prinim.prinyal.data.DueKind
import ai.prinim.prinyal.data.ItemState
import ai.prinim.prinyal.data.ItemType
import ai.prinim.prinyal.data.Window
import ai.prinim.prinyal.data.CaptureSource
import ai.prinim.prinyal.net.ParseResult
import ai.prinim.prinyal.net.ParsedItem
import ai.prinim.prinyal.returns.ReturnActionReceiver
import ai.prinim.prinyal.returns.ReturnAlarmReceiver
import ai.prinim.prinyal.returns.ReturnScheduler
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant

/**
 * Кор-фича «заметка с возвратом», последний метр: продукт **возвращает**.
 *
 * До сих пор этот метр не проверял никто. Тесты знали, на какое время назначен
 * возврат, — и на этом останавливались. Что аларм дошёл, что уведомление
 * собралось, что нажатие в шторке доехало до базы, не проверялось ни разу, хотя
 * ровно здесь продукт и случается: всё остальное — подготовка к этой минуте.
 *
 * Цена пробела известна. Вечерний вопрос не приходил неделями; иконка на часах
 * была чужой; нажатие на уведомление при открытом приложении не делало ничего.
 * Каждый раз это находил владелец на себе, а не прогон на машине.
 *
 * Ресиверы работают через `goAsync()` и свой поток, поэтому тест ждёт результата
 * опросом, а не спит фиксированную паузу: сон либо тормозит прогон, либо
 * краснеет на медленной машине.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReturnDeliveryTest {

    private val app: PrinyalApp get() = ApplicationProvider.getApplicationContext()

    private val notifications: NotificationManager
        get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Before
    fun clean() {
        Thread { app.db.clearAllTables() }.apply { start(); join() }
        notifications.cancelAll()
    }

    /**
     * Прочитать базу вне тестового диспетчера.
     *
     * `runBlocking` прямо внутри `runTest` замораживает работу, которую ресивер
     * ведёт в своём потоке: проверка ждёт результата, а результат ждёт, пока
     * проверка отпустит поток. Тест при этом не висит, а спокойно исчерпывает
     * таймаут и врёт, что продукт не сработал.
     */
    private fun <T> io(block: suspend () -> T): T {
        var result: Result<T>? = null
        Thread { result = runCatching { kotlinx.coroutines.runBlocking { block() } } }
            .apply { start(); join() }
        return result!!.getOrThrow()
    }

    /** Ждём, пока условие станет верным: ресивер отвечает в своём потоке. */
    private fun eventually(what: String, check: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            // Крутим главный лупер: доставка broadcast идёт через него, и без
            // этого ресивер не начнёт работу вовсе — тест прождёт таймаут и
            // объявит сломанным то, что просто не запускали.
            shadowOf(app.mainLooper).idle()
            Thread.sleep(20)
            if (io(check)) return
        }
        throw AssertionError("не дождались: $what")
    }

    /** Заметка с одним делом и назначенным возвратом — то, что даёт разбор. */
    private fun deed(text: String = "купить капли"): Pair<String, String> {
        val audio = File(app.filesDir, "d.m4a").apply { writeText("x") }
        return io {
        app.repository.createNote("n1", audio, 3_000, CaptureSource.WIDGET, Instant.parse("2026-08-20T09:00:00Z"))
        app.repository.applyParse(
            "n1",
            ParseResult(
                transcript = text,
                items = listOf(
                    ParsedItem(
                        ItemType.DO, text, null, DueKind.WINDOW, Window.EVENING,
                        null, Confidence.HIGH, text,
                    )
                ),
                topic = null, degraded = null, asrMs = 100, llmMs = 100, llmRetries = 0,
            ),
        )
        val item = app.db.items().forNote("n1").first()
        val ret = app.db.returns().forItem(item.id).first()
        item.id to ret.id
        }
    }

    /**
     * Аларм отправляем системе, а не зовём ресивер напрямую.
     *
     * Прямой вызов выглядит проще, но ломается на `goAsync()`: он выдаёт
     * `PendingResult` только доставке от системы, а вызванный руками ресивер
     * получает null и падает в `finally`. Тест на прямом вызове проверял бы
     * конструкцию, которой в бою не бывает.
     */
    private fun fireAlarm(returnId: String) {
        app.sendBroadcast(
            Intent(app, ReturnAlarmReceiver::class.java)
                .setAction(ReturnScheduler.ACTION_FIRE)
                .putExtra(ReturnScheduler.EXTRA_RETURN_ID, returnId),
        )
        shadowOf(app.mainLooper).idle()
    }

    private fun tap(returnId: String, action: String) {
        app.sendBroadcast(
            Intent(app, ReturnActionReceiver::class.java)
                .setAction(action)
                .putExtra(ReturnScheduler.EXTRA_RETURN_ID, returnId),
        )
        shadowOf(app.mainLooper).idle()
    }

    @Test
    fun `настал срок — человек видит напоминание своими словами`() {
        val (_, returnId) = deed("забрать справку из поликлиники")

        fireAlarm(returnId)

        eventually("уведомление показано") {
            shadowOf(notifications).activeNotifications.isNotEmpty()
        }
        val shown = shadowOf(notifications).activeNotifications.first().notification
        val text = shown.extras.getCharSequence("android.text")?.toString().orEmpty() +
            shown.extras.getCharSequence("android.title")?.toString().orEmpty()
        assertTrue("в напоминании нет самого дела: «$text»", text.contains("справку"))
    }

    @Test
    fun `возврат отмечается показанным — второй раз не придёт`() {
        val (_, returnId) = deed()

        fireAlarm(returnId)
        eventually("отметка о показе") { app.db.returns().byId(returnId)?.firedAt != null }

        notifications.cancelAll()
        fireAlarm(returnId)
        Thread.sleep(300)
        assertTrue(
            "показали второй раз — человек получил то же напоминание дважды",
            shadowOf(notifications).activeNotifications.isEmpty(),
        )
    }

    @Test
    fun `нажал «сделано» — дело закрыто`() {
        val (itemId, returnId) = deed()
        fireAlarm(returnId)
        eventually("показано") { app.db.returns().byId(returnId)?.firedAt != null }

        tap(returnId, ReturnActionReceiver.DONE)
        eventually("пункт закрыт") {
            ItemState.of(app.db.items().byId(itemId)?.state) == ItemState.DONE
        }
    }

    @Test
    fun `нажал «позже» — возврат переносится, а не пропадает`() {
        val (itemId, returnId) = deed()
        fireAlarm(returnId)
        eventually("показано") { app.db.returns().byId(returnId)?.firedAt != null }

        tap(returnId, ReturnActionReceiver.LATER)

        eventually("назначен следующий заход") {
            app.db.returns().forItem(itemId).size > 1
        }
    }

    @Test
    fun `свайпнул — тихий второй заход, а не тишина навсегда`() {
        val (itemId, returnId) = deed()
        fireAlarm(returnId)
        eventually("показано") { app.db.returns().byId(returnId)?.firedAt != null }

        tap(returnId, ReturnActionReceiver.IGNORED)

        eventually("второй заход назначен") {
            app.db.returns().forItem(itemId).size > 1
        }
    }

    @Test
    fun `удалённое дело не звонит`() {
        val (itemId, returnId) = deed()
        io { app.repository.dismissItem(itemId, fromReturn = false) }

        fireAlarm(returnId)
        Thread.sleep(300)

        // Показать напоминание о том, что человек уже убрал, — худший вид шума:
        // продукт спорит с решением, которое человек принял минуту назад.
        assertTrue(
            "напомнили о снятом деле",
            shadowOf(notifications).activeNotifications.isEmpty(),
        )
    }

    @Test
    fun `аларм без записи не роняет приложение`() {
        fireAlarm("нет-такого-возврата")
        Thread.sleep(300)
        assertTrue(shadowOf(notifications).activeNotifications.isEmpty())
    }
}
