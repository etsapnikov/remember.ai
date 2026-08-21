package ai.prinim.prinyal

import ai.prinim.prinyal.data.ItemEntity
import ai.prinim.prinyal.data.ItemState
import ai.prinim.prinyal.data.NoteEntity
import ai.prinim.prinyal.data.NoteWithItems
import ai.prinim.prinyal.domain.FeedView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * Лента после Д-24 и Д-25: один статус на запись, потолок в три пункта,
 * закрытая запись одной строкой, пять слов фильтра.
 */
class FeedViewTest {

    private val zone: ZoneId = ZoneId.of("Europe/Moscow")
    private val now: Instant = Instant.parse("2026-08-19T18:00:00Z")

    private fun note(id: String, atHoursAgo: Long = 1) = NoteEntity(
        id = id,
        createdAt = now.minusSeconds(atHoursAgo * 3600).toEpochMilli(),
        audioPath = "",
        transcript = "речь",
    )

    private fun item(
        id: String,
        state: ItemState = ItemState.PLANNED,
        dueAt: Long? = null,
    ) = ItemEntity(
        id = id,
        noteId = "n",
        type = "do",
        text = "дело $id",
        dueKind = if (dueAt == null) "none" else "exact",
        dueAt = dueAt,
        state = state.wire,
        confidence = "high",
    )

    private fun entry(id: String, vararg items: ItemEntity, hoursAgo: Long = 1) =
        NoteWithItems(note(id, hoursAgo), items.toList())

    @Test
    fun `повторы в «в плане» уезжают под свой заголовок и стоят последними`() {
        // Макеты 10a: вечный пункт рядом с долгом на два дня делает вид, что
        // они одного рода. Он и не долг, и в общий счёт лезть не должен.
        val sections = FeedView.sections(
            listOf(
                entry("n1", item("a")),
                entry(
                    "n2",
                    item("b"),
                    item("c").copy(repeatRule = "weekly:mon"),
                ),
            ),
            FeedView.Filter.PLANNED,
            now,
        )

        val repeating = sections.last()
        assertEquals(FeedView.Section.Kind.REPEATING, repeating.kind)
        assertEquals(listOf("c"), repeating.rows.single().shown.map { it.id })
        // Запись с обоими видами пунктов печатается дважды — и в дне остаются
        // только её обычные дела.
        val plain = sections.dropLast(1).flatMap { it.rows }
        assertEquals(listOf("a", "b"), plain.flatMap { it.shown }.map { it.id })
    }

    @Test
    fun `повтор не попадает в «сделано», даже когда его сделали`() {
        // Сделанный повтор живёт в «вернусь»: он закрылся до следующего раза.
        val done = item("a", state = ItemState.RETURNED).copy(
            repeatRule = "weekly:mon",
            repeatDoneAt = now.toEpochMilli(),
            repeatNextAt = now.plusSeconds(3 * 24 * 3600).toEpochMilli(),
        )
        val sections = FeedView.sections(listOf(entry("n1", done)), FeedView.Filter.DONE, now)
        assertTrue("повтор оказался в «сделано»", sections.isEmpty())
    }

    @Test
    fun `закрытые записи считаются, чтобы лента сказала, куда они делись`() {
        val notes = listOf(
            entry("n1", item("a")),
            entry("n2", item("b", state = ItemState.DONE)),
            // Похороненная лежит в «похороненном», а строка ведёт в «сделано».
            entry("n3", item("c", state = ItemState.EXPIRED)),
            // Запись без пунктов не закрыта — там ещё может быть речь.
            entry("n4"),
        )
        assertEquals(1, FeedView.closedCount(notes, now))
        // Счёт обязан совпадать с тем, что человек увидит после тапа.
        assertEquals(
            FeedView.sections(notes, FeedView.Filter.DONE, now).sumOf { it.rows.size },
            FeedView.closedCount(notes, now),
        )
    }

    @Test
    fun `в ленте печатается не больше трёх пунктов, остальное — остатком`() {
        // Запись из шести дел не имеет права занять экран целиком: лента
        // перестаёт быть лентой.
        val big = entry("n1", *(1..6).map { item("i$it") }.toTypedArray())
        val row = FeedView.sections(listOf(big), FeedView.Filter.ALL, now, zone)
            .single().rows.single()

        assertEquals(FeedView.MAX_ITEMS, row.shown.size)
        assertEquals(3, row.restPlanned)
    }

    @Test
    fun `запись без живого печатается одной строкой`() {
        // Под своим фильтром: в «всё» закрытая запись теперь не показывается
        // вовсе, но там, где её ждут, она обязана быть одной строкой.
        val closed = entry("n1", item("i1", ItemState.DONE), item("i2", ItemState.DONE))
        val row = FeedView.sections(listOf(closed), FeedView.Filter.DONE, now, zone)
            .single().rows.single()

        assertTrue("закрытая запись развёрнута", row.allClosed)
        assertTrue(row.shown.isEmpty())
    }

    @Test
    fun `закрытая запись знает, чем именно закрыта`() {
        // Под фильтром «похоронено» продукт называл похороненное сделанным:
        // счётчики обнулялись у закрытой записи, и шапка выбирала первое слово.
        val buried = entry("n1", item("i1", ItemState.DISMISSED))
        val row = FeedView.sections(listOf(buried), FeedView.Filter.BURIED, now, zone)
            .single().rows.single()
        assertTrue(row.allClosed)
        assertEquals(0, row.restDone)
        assertEquals(1, row.restGone)
    }

    @Test
    fun `закрытая запись не занимает место в ленте`() {
        // «Всё сделано» строкой без текста — ни дела, ни новости. Смотреть
        // закрытое человек приходит фильтром.
        val live = entry("живая", item("i1"))
        val closed = entry("закрытая", item("i2", ItemState.DONE))
        val ids = FeedView.sections(listOf(live, closed), FeedView.Filter.ALL, now, zone)
            .flatMap { it.rows }.map { it.entry.note.id }
        assertEquals(listOf("живая"), ids)

        // А под своим фильтром — на месте.
        assertEquals(
            listOf("закрытая"),
            FeedView.sections(listOf(live, closed), FeedView.Filter.DONE, now, zone)
                .flatMap { it.rows }.map { it.entry.note.id },
        )
    }

    @Test
    fun `запись без пунктов остаётся видна`() {
        // Там ещё может быть речь, которую не разобрали: спрятать её значит
        // потерять сказанное.
        val silent = NoteWithItems(note("немая"), emptyList())
        val ids = FeedView.sections(listOf(silent), FeedView.Filter.ALL, now, zone)
            .flatMap { it.rows }.map { it.entry.note.id }
        assertEquals(listOf("немая"), ids)
    }

    @Test
    fun `фильтр выбрасывает записи, где ничего не подошло`() {
        val live = entry("живая", item("i1"))
        val done = entry("сделанная", item("i2", ItemState.DONE))

        val onlyDone = FeedView.sections(listOf(live, done), FeedView.Filter.DONE, now, zone)
            .flatMap { it.rows }
        assertEquals(listOf("сделанная"), onlyDone.map { it.entry.note.id })

        val onlyBuried = FeedView.sections(listOf(live, done), FeedView.Filter.BURIED, now, zone)
        assertTrue("похоронённого нет, а записи есть", onlyBuried.isEmpty())
    }

    @Test
    fun `шапка знает, сколько пунктов записи прошло фильтр`() {
        // «1 из 4» — иначе человек решит, что остальные пропали.
        val mixed = entry(
            "n1",
            item("i1", ItemState.DONE),
            item("i2"), item("i3"), item("i4"),
        )
        val row = FeedView.sections(listOf(mixed), FeedView.Filter.DONE, now, zone)
            .single().rows.single()
        assertEquals(1, row.matched)
        assertEquals(4, row.total)
        assertTrue(row.filtered)
    }

    @Test
    fun `«вернусь» берёт только то, у чего срок впереди`() {
        val soon = entry("завтра", item("i1", dueAt = now.plusSeconds(20 * 3600).toEpochMilli()))
        val someday = entry("через месяц", item("i2", dueAt = now.plusSeconds(30L * 86400).toEpochMilli()))
        val noDate = entry("без срока", item("i3"))
        val past = entry("прошлое", item("i4", dueAt = now.minusSeconds(3600).toEpochMilli()))

        val sections = FeedView.sections(
            listOf(soon, someday, noDate, past), FeedView.Filter.RETURNING, now, zone,
        )
        val ids = sections.flatMap { it.rows }.map { it.entry.note.id }
        assertEquals(listOf("завтра", "через месяц"), ids)
        assertEquals(
            listOf(FeedView.Section.Kind.TOMORROW, FeedView.Section.Kind.LATER),
            sections.map { it.kind },
        )
    }

    @Test
    fun `закрытое показывается за неделю, старое не тянется`() {
        val fresh = entry("свежая", item("i1", ItemState.DONE), hoursAgo = 24)
        val old = entry("старая", item("i2", ItemState.DONE), hoursAgo = 24 * 30)

        val ids = FeedView.sections(listOf(fresh, old), FeedView.Filter.DONE, now, zone)
            .flatMap { it.rows }.map { it.entry.note.id }
        assertEquals(listOf("свежая"), ids)
    }

    @Test
    fun `записи разложены по дням, сегодня первым`() {
        val today = entry("сегодня", item("i1"), hoursAgo = 1)
        val yesterday = entry("вчера", item("i2"), hoursAgo = 30)

        val sections = FeedView.sections(listOf(today, yesterday), FeedView.Filter.ALL, now, zone)
        assertEquals(FeedView.Section.Kind.TODAY, sections.first().kind)
        assertEquals(FeedView.Section.Kind.DAY, sections[1].kind)
        assertEquals("вчера", sections[1].rows.single().entry.note.id)
    }
}
