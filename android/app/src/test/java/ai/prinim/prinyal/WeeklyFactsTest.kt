package ai.prinim.prinyal

import ai.prinim.prinyal.domain.WeeklyFacts
import ai.prinim.prinyal.domain.WeeklyFacts.Fact
import ai.prinim.prinyal.domain.WeeklyFacts.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Факты недели. Проверяем не красоту формулировок, а меру: пустая неделя не
 * рождает выдуманных наблюдений, а обычная не превращается в сводку из десяти
 * строк.
 */
class WeeklyFactsTest {

    @Test
    fun `пустая неделя не рождает фактов`() {
        // Экран честно короткий — это лучше, чем натянутое наблюдение.
        assertTrue(WeeklyFacts.facts(Signal()).isEmpty())
    }

    @Test
    fun `закрытое из давно висевшего — первый факт`() {
        val facts = WeeklyFacts.facts(Signal(closedLongWaiting = 2, closed = 5))
        assertEquals(Fact.ClosedOld(2), facts.first())
    }

    @Test
    fun `раздел попадает в факты, только когда двигался несколько дней`() {
        // Два дня — не новость: так выглядит любая пара записей подряд.
        assertTrue(
            WeeklyFacts.facts(Signal(busiestTopic = "Продукт", busiestTopicDays = 2)).isEmpty()
        )
        assertEquals(
            Fact.TopicMoved("Продукт", 4),
            WeeklyFacts.facts(Signal(busiestTopic = "Продукт", busiestTopicDays = 4)).single(),
        )
    }

    @Test
    fun `повтор темы отмечается от трёх записей`() {
        assertTrue(
            WeeklyFacts.facts(Signal(repeatedTopic = "Дача", repeatedTopicNotes = 2)).isEmpty()
        )
        assertEquals(
            Fact.TopicRepeated("Дача", 3),
            WeeklyFacts.facts(Signal(repeatedTopic = "Дача", repeatedTopicNotes = 3)).single(),
        )
    }

    @Test
    fun `висящее дело упоминается последним, а не открывает неделю`() {
        val facts = WeeklyFacts.facts(
            Signal(
                closedLongWaiting = 1,
                busiestTopic = "Продукт",
                busiestTopicDays = 5,
                oldestWaitingDays = 30,
            )
        )
        // Это сведение, а не упрёк: открывать им неделю незачем.
        assertTrue(facts.last() is Fact.OldestWaiting)
    }

    @Test
    fun `наблюдений не больше пяти`() {
        val facts = WeeklyFacts.facts(
            Signal(
                closedLongWaiting = 3,
                busiestTopic = "Продукт",
                busiestTopicDays = 7,
                repeatedTopic = "Дача",
                repeatedTopicNotes = 4,
                oldestWaitingDays = 40,
                closed = 9,
                dropped = 4,
            )
        )
        assertTrue("фактов ${facts.size}", facts.size in 1..5)
    }

    @Test
    fun `догадка о зреющей затее не лепится к большому разделу`() {
        // «Шестнадцать записей об одном — возможно, зреет затея» звучит глупо:
        // это не зреющая затея, а рабочий раздел. Найдено на живом корпусе.
        assertTrue(
            WeeklyFacts.facts(Signal(repeatedTopic = "Работа", repeatedTopicNotes = 16)).isEmpty()
        )
        assertEquals(
            Fact.TopicRepeated("Дача", 4),
            WeeklyFacts.facts(Signal(repeatedTopic = "Дача", repeatedTopicNotes = 4)).single(),
        )
    }

    @Test
    fun `единичный отказ фактом не становится`() {
        // Один «не надо» — обычное дело; три подряд уже говорят, что продукт
        // предлагает не то.
        assertTrue(WeeklyFacts.facts(Signal(dropped = 1)).isEmpty())
        assertTrue(WeeklyFacts.facts(Signal(dropped = 3)).isNotEmpty())
    }

    @Test
    fun `двадцать разных недель не дают ни лишних фактов, ни пустоты из ничего`() {
        // Двадцать недель из ТЗ — это про меру на разнообразии, а не про
        // конкретные числа. Проверяем два инварианта, которые обязаны держаться
        // на любых данных: пусто на пустой неделе, не больше пяти на любой.
        var everyEmpty = true
        for (n in 0 until 20) {
            val signal = Signal(
                closedLongWaiting = n % 4,
                busiestTopic = if (n % 3 == 0) "Продукт" else null,
                busiestTopicDays = n % 8,
                repeatedTopic = if (n % 5 == 0) "Дача" else null,
                repeatedTopicNotes = n % 6,
                oldestWaitingDays = (n * 7) % 45,
                closed = n,
                dropped = n % 5,
            )
            val facts = WeeklyFacts.facts(signal)
            assertTrue("неделя $n дала ${facts.size} фактов", facts.size <= 5)
            if (facts.isNotEmpty()) everyEmpty = false

            // Ни один факт не выдуман: у каждого есть опора в сигнале.
            facts.forEach { fact ->
                when (fact) {
                    is Fact.ClosedOld -> assertEquals(signal.closedLongWaiting, fact.count)
                    is Fact.TopicMoved -> assertEquals(signal.busiestTopicDays, fact.days)
                    is Fact.TopicRepeated -> assertEquals(signal.repeatedTopicNotes, fact.notes)
                    is Fact.OldestWaiting -> assertEquals(signal.oldestWaitingDays, fact.days)
                    is Fact.Dropped -> assertEquals(signal.dropped, fact.count)
                    is Fact.Kept -> {
                        assertEquals(signal.brought, fact.brought)
                        assertEquals(signal.hanging, fact.hanging)
                        // Подкрепление печатается только когда есть чем: доля
                        // «всё висит» — не новость про то, что не пропало.
                        assertTrue(fact.hanging < fact.brought)
                    }
                    is Fact.Grown -> assertEquals(signal.grown, fact.count)
                }
            }
        }
        assertTrue("на двадцати неделях не нашлось ни одного факта", !everyEmpty)
    }

    @Test
    fun `в словах фактов нет похвалы и эмодзи`() {
        // Стоп-лист из ТЗ. Проверяем сами строки, а не выдачу: похвала заводится
        // при редактировании текстов, и поймать её надо там же.
        val xml = File("src/main/res/values/strings.xml").readText()
        val facts = Regex("""<plurals name="week_fact_[\s\S]*?</plurals>""")
            .findAll(xml).joinToString(" ") { it.value }
        assertTrue("строки фактов не найдены", facts.isNotEmpty())

        listOf("молодец", "отлично", "супер", "здорово", "так держать", "горжусь", "!")
            .forEach { banned ->
                assertTrue("похвала в строках: $banned", !facts.lowercase().contains(banned))
            }
        val emoji = Regex("[\\uD83C-\\uDBFF\\uDC00-\\uDFFF\\u2600-\\u27BF]")
        assertTrue("эмодзи в строках фактов", !emoji.containsMatchIn(facts))
    }
}
