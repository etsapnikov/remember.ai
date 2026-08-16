package ai.prinim.prinyal

import ai.prinim.prinyal.data.Confidence
import ai.prinim.prinyal.data.DueKind
import ai.prinim.prinyal.data.ItemEntity
import ai.prinim.prinyal.data.ItemState
import ai.prinim.prinyal.data.ItemType
import ai.prinim.prinyal.data.Window
import ai.prinim.prinyal.domain.Reconcile
import ai.prinim.prinyal.net.ParsedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сверка после дописывания — место, где ошибка тихая и дорогая.
 *
 * Каждый тест здесь описывает беду, а не функцию: воскресшее закрытое дело,
 * затёртую ручную правку, дубль.
 */
class ReconcileTest {

    private fun existing(
        id: String,
        text: String,
        state: ItemState = ItemState.PLANNED,
        edited: Boolean = false,
    ) = ItemEntity(
        id = id,
        noteId = "n1",
        type = ItemType.DO.wire,
        text = text,
        dueKind = DueKind.WINDOW.wire,
        window = Window.MORNING.wire,
        state = state.wire,
        confidence = Confidence.HIGH.wire,
        edited = edited,
    )

    private fun parsed(text: String, window: Window = Window.DAY) = ParsedItem(
        type = ItemType.DO,
        text = text,
        who = null,
        dueKind = DueKind.WINDOW,
        window = window,
        dueAt = null,
        confidence = Confidence.HIGH,
        rawSpan = text,
    )

    @Test
    fun `закрытое дело не воскресает`() {
        val done = existing("i1", "забрать справку", state = ItemState.DONE)
        val plan = Reconcile.plan(
            existing = listOf(done),
            parsed = listOf(parsed("забрать справку из поликлиники")),
            refs = listOf("i1"),
        )
        assertTrue("закрытый пункт тронут", plan.actions.isEmpty())
        assertTrue(plan.rejected.single().contains("закрытый"))
    }

    @Test
    fun `отменённое и просроченное тоже неприкосновенны`() {
        listOf(ItemState.DISMISSED, ItemState.EXPIRED).forEach { state ->
            val plan = Reconcile.plan(
                existing = listOf(existing("i1", "старое", state = state)),
                parsed = listOf(parsed("новое")),
                refs = listOf("i1"),
            )
            assertTrue("состояние $state не защищено", plan.actions.isEmpty())
        }
    }

    @Test
    fun `живой пункт уточняется — ради этого всё и затевалось`() {
        val plan = Reconcile.plan(
            existing = listOf(existing("i1", "забрать справку из школы")),
            parsed = listOf(parsed("забрать справку из поликлиники", Window.MORNING)),
            refs = listOf("i1"),
        )
        val update = plan.actions.single() as Reconcile.Action.Update
        assertEquals("i1", update.id)
        assertEquals("забрать справку из поликлиники", update.item.text)
    }

    @Test
    fun `правленный рукой пункт машина не переписывает`() {
        val plan = Reconcile.plan(
            existing = listOf(existing("i1", "мой текст", edited = true)),
            parsed = listOf(parsed("текст модели")),
            refs = listOf("i1"),
        )
        assertTrue(plan.actions.isEmpty())
        assertTrue(plan.rejected.single().contains("рукой"))
    }

    @Test
    fun `новый пункт добавляется`() {
        val plan = Reconcile.plan(
            existing = listOf(existing("i1", "забрать справку")),
            parsed = listOf(parsed("записаться на техосмотр")),
            refs = listOf(null),
        )
        assertTrue(plan.actions.single() is Reconcile.Action.Add)
    }

    @Test
    fun `дубль существующего не заводится`() {
        // Модель часто «переоткрывает» то, что уже есть, — регистр и знаки не в счёт.
        val plan = Reconcile.plan(
            existing = listOf(existing("i1", "Забрать справку")),
            parsed = listOf(parsed("забрать справку!")),
            refs = listOf(null),
        )
        assertTrue(plan.actions.isEmpty())
        assertTrue(plan.rejected.single().contains("дубль"))
    }

    @Test
    fun `ссылка на несуществующий пункт не превращается в новый`() {
        // Модель метила в конкретный пункт и промахнулась — заводить новый
        // значило бы удвоить дело.
        val plan = Reconcile.plan(
            existing = listOf(existing("i1", "забрать справку")),
            parsed = listOf(parsed("что-то другое")),
            refs = listOf("нет-такого"),
        )
        assertTrue(plan.actions.isEmpty())
        assertTrue(plan.rejected.single().contains("неизвестный ref"))
    }

    @Test
    fun `дописывание к заметке с закрытым и живым пунктом ведёт себя правильно`() {
        // Приёмка scope 1.0.1 §3 целиком.
        val plan = Reconcile.plan(
            existing = listOf(
                existing("done", "купить капли", state = ItemState.DONE),
                existing("live", "забрать справку из школы"),
            ),
            parsed = listOf(
                parsed("купить капли"),
                parsed("забрать справку из поликлиники"),
                parsed("записаться на техосмотр"),
            ),
            refs = listOf("done", "live", null),
        )
        assertEquals(2, plan.actions.size)
        assertTrue(plan.actions.any { it is Reconcile.Action.Update && it.id == "live" })
        assertTrue(plan.actions.any { it is Reconcile.Action.Add })
        assertTrue(plan.rejected.single().contains("закрытый"))
    }

    @Test
    fun `всё отброшено — считаем сверку неудавшейся`() {
        val parsedItems = listOf(parsed("новое"))
        val plan = Reconcile.plan(
            existing = listOf(existing("i1", "старое", state = ItemState.DONE)),
            parsed = parsedItems,
            refs = listOf("i1"),
        )
        assertTrue(Reconcile.failed(plan, parsedItems))
    }
}
