package ai.prinim.prinyal

import ai.prinim.prinyal.domain.StructureRepair
import ai.prinim.prinyal.domain.StructureRepair.Note
import ai.prinim.prinyal.domain.StructureRepair.Offer
import ai.prinim.prinyal.domain.StructureRepair.Topic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Починка структуры. Главное здесь не «находит ли кластер», а «молчит ли, когда
 * находить нечего»: предложение перекроить раскладку — это просьба поработать,
 * и продукт, который просит часто, перестаёт быть помощником.
 */
class StructureRepairTest {

    // Тексты нарочно живые, а не «тема номер N»: на одинаковых по форме записях
    // любой алгоритм похожести ведёт себя не так, как на речи, и тест мерил бы
    // не то. Эти взяты по мотивам настоящего корпуса.
    private val markdown = listOf(
        "надо бы маркдаун поддержать когда заметка парсится",
        "тесты на заметки со списками и маркдауном",
        "в маркдауне не хватает вложенных списков, поправить",
        "маркдаун в карточке идеи выглядит криво на длинных заголовках",
        "проверить, как рендерится маркдаун из ответа модели",
        "маркдаун только для идей, для покупок он лишний",
    )
    private val dacha = listOf(
        "на даче починить забор со стороны дороги",
        "купить рассаду для дачи в выходные",
        "дача: разобрать чердак и вывезти хлам",
        "заказать дрова на дачу до холодов",
        "на даче протекает крыша веранды",
        "посмотреть цены на дачный водопровод",
        "дачу застраховать до конца сезона",
        "скосить траву на даче, пока сухо",
        "привезти на дачу старый холодильник",
        "поставить на даче бак для воды",
    )

    private fun notes(texts: List<String>, prefix: String) =
        texts.mapIndexed { i, text -> Note("$prefix$i", text) }

    private fun about(prefix: String, n: Int, from: Int = 0) =
        (from until from + n).map { Note("$prefix$it", "$prefix запись номер $it про $prefix") }

    private val bigTopic = Topic("t1", "Идеи")

    @Test
    fun `раздел с двумя темами рождает предложение по одной из них`() {
        val notes = notes(markdown, "md") + notes(dacha, "da")
        val split = StructureRepair.offer(listOf(bigTopic to notes), orphans = emptyList())
            as Offer.Split
        assertEquals("t1", split.topicId)
        // Важно не какую из тем выбрали, а что группа однородна: предложить
        // «выделить раздел» из смеси двух тем — хуже, чем промолчать.
        val prefixes = split.noteIds.map { it.take(2) }.toSet()
        assertEquals("группа из двух тем: ${split.noteIds}", 1, prefixes.size)
        assertTrue(split.noteIds.size >= StructureRepair.MIN_CLUSTER)
    }

    @Test
    fun `маленький раздел не трогаем, даже если внутри явная тема`() {
        // Четырнадцать заметок — это ещё не «раздел разросся». Делить его
        // значит создавать работу на ровном месте.
        val notes = notes(markdown, "md") + notes(dacha.take(8), "da")
        assertNull(StructureRepair.offer(listOf(bigTopic to notes), orphans = emptyList()))
    }

    @Test
    fun `однородный большой раздел делить нечем`() {
        val notes = about("дача", 20)
        // Когда все записи похожи одинаково, выделяться нечему — и молчание
        // тут единственный честный ответ.
        assertNull(StructureRepair.offer(listOf(bigTopic to notes), orphans = emptyList()))
    }

    @Test
    fun `сироты собираются в раздел от пяти похожих`() {
        val four = notes(dacha.take(4), "da")
        assertNull(StructureRepair.offer(emptyList(), orphans = four))

        // Пять дачных и три про маркдаун: группа обязана быть однородной.
        // Какую из тем продукт соберёт первой — его дело; собрать смесь он
        // права не имеет, потому что предложит человеку раздел ни о чём.
        val mixed = notes(dacha.take(5), "da") + notes(markdown.take(3), "md")
        val offer = StructureRepair.offer(emptyList(), orphans = mixed) as Offer.Gather
        assertEquals(
            "группа из двух тем: ${offer.noteIds}",
            1,
            offer.noteIds.map { it.take(2) }.toSet().size,
        )
    }

    @Test
    fun `непохожие сироты предложения не рождают`() {
        val motley = listOf(
            Note("a", "лего про космос"),
            Note("b", "оплатить проезд"),
            Note("c", "позвонить маме"),
            Note("d", "купить кефир"),
            Note("e", "починить кран"),
            Note("f", "сдать анализы"),
        )
        assertNull(StructureRepair.offer(emptyList(), orphans = motley))
    }

    @Test
    fun `отказ закрывает именно эту тему, а не всю починку`() {
        val notes = notes(markdown, "md") + notes(dacha, "da")
        val orphans = notes(dacha.take(6), "or")

        val refused = StructureRepair.offer(
            listOf(bigTopic to notes),
            orphans = orphans,
            refused = setOf("t1"),
        )
        // Раздел закрыт — но сироты не при чём, и предложение по ним законно.
        assertTrue("сироты пропали вместе с разделом", refused is Offer.Gather)

        assertNull(
            StructureRepair.offer(
                listOf(bigTopic to notes),
                orphans = orphans,
                refused = setOf("t1", StructureRepair.ORPHANS_KEY),
            )
        )
    }

    @Test
    fun `явная связь собирает то, что слова не собрали`() {
        // Две заметки могут быть про одно, не разделив ни одного слова — ради
        // этого случая и существуют линки (Р-15.11). Проверяем кластер прямо:
        // через offer это утонуло бы в пороге доли раздела.
        // Ни одного общего корня между всеми шестью: слова здесь не соединяют
        // ничего, и всё держится только на связях.
        val notes = listOf(
            Note("a", "починить забор"),
            Note("b", "купить рассаду"),
            Note("c", "вывезти хлам"),
            Note("x1", "совсем иными речами о том же"),
            Note("x2", "а тут вовсе третья формулировка"),
            Note("x3", "и ни единого общего корня"),
        )
        val group = StructureRepair.cluster(
            notes + Note("x0", "совсем иными речами о другом"),
            linked = setOf("x1" to "x2", "x1" to "x3"),
        )
        assertTrue("связанные не собрались: $group", group!!.noteIds.containsAll(listOf("x1", "x2", "x3")))
    }

    @Test
    fun `два предложения в неделю невозможны`() {
        val day = 24L * 60 * 60 * 1000
        val now = 100 * day
        assertTrue("первое предложение", StructureRepair.maySpeak(null, now))
        assertTrue("молчал неделю", StructureRepair.maySpeak(now - 7 * day, now))
        assertTrue(
            "заговорил на шестой день",
            !StructureRepair.maySpeak(now - 6 * day, now),
        )
    }

    @Test
    fun `отказ держится месяц и отпускает`() {
        val day = 24L * 60 * 60 * 1000
        val now = 100 * day
        assertTrue(StructureRepair.refusalHolds(now - 29 * day, now))
        assertTrue(!StructureRepair.refusalHolds(now - 30 * day, now))
        assertTrue("отказа не было", !StructureRepair.refusalHolds(null, now))
    }

    @Test
    fun `пустой вход не роняет проверку`() {
        assertNull(StructureRepair.offer(emptyList(), emptyList()))
        assertNull(StructureRepair.cluster(emptyList()))
    }
}
