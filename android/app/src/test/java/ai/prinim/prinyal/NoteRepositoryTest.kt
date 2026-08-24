package ai.prinim.prinyal

import ai.prinim.prinyal.data.Analytics
import ai.prinim.prinyal.data.CaptureSource
import ai.prinim.prinyal.data.Confidence
import ai.prinim.prinyal.data.DueKind
import ai.prinim.prinyal.data.InterviewState
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

    private fun parsed(vararg items: ParsedItem, topic: String? = null) =
        ParseResult(
            transcript = "капли соню к лору мужу про субботу",
            items = items.toList(),
            topic = topic,
            degraded = null,
            asrMs = 900,
            llmMs = 800,
            llmRetries = 0,
        )

    private fun item(
        type: ItemType = ItemType.DO,
        text: String = "купить капли",
        window: Window? = Window.EVENING,
        dueKind: DueKind = DueKind.WINDOW,
    ) = ParsedItem(type, text, null, dueKind, window, null, Confidence.HIGH, "капли")

    @Test
    fun `повтор ставит следующий раз и не кончается`() = runTest {
        // Р-16.2. Ценность повтора вся в том, что он переживает срабатывание:
        // напоминание, умирающее после первого раза, — это обычный возврат.
        val id = note()
        val items = repo.applyParse(
            id,
            parsed(item(text = "выложить отчёт", window = null, dueKind = DueKind.NONE)
                .copy(repeat = "weekly:mon")),
        )
        val item = items.single()
        assertEquals("weekly:mon", db.items().byId(item.id)!!.repeatRule)

        val first = db.returns().all().single()
        val firstDay = Instant.ofEpochMilli(first.scheduledAt).atZone(zone).toLocalDate()
        assertEquals("возврат не в понедельник", java.time.DayOfWeek.MONDAY, firstDay.dayOfWeek)

        repo.markFired(first.id)
        val next = db.returns().all().filter { it.id != first.id }.single()
        val nextDay = Instant.ofEpochMilli(next.scheduledAt).atZone(zone).toLocalDate()
        assertEquals(java.time.DayOfWeek.MONDAY, nextDay.dayOfWeek)
        assertTrue("следующий раз обязан быть позже", nextDay.isAfter(firstDay))
        assertTrue("аларм на следующий раз не поставлен", scheduler.scheduled.containsKey(next.id))
    }

    @Test
    fun `молчание не хоронит повторяющийся пункт`() = runTest {
        // Обычный пункт после двух неотвеченных возвратов уходит в expired.
        // Повторяющемуся это противопоказано: он и не рассчитан на ответ.
        val id = note()
        val items = repo.applyParse(
            id,
            parsed(item(text = "показания счётчиков", window = null, dueKind = DueKind.NONE)
                .copy(repeat = "monthly:15")),
        )
        val first = db.returns().all().single()
        repo.markFired(first.id)
        repo.scheduleSecondAttempt(first.id)

        assertEquals(
            "повторяющийся пункт похоронен молчанием",
            ItemState.RETURNED.wire,
            db.items().byId(items.single().id)!!.state,
        )
    }

    @Test
    fun `хватит напоминать оставляет одно дело на ближайший раз`() = runTest {
        // Макеты 10c: «Не повторять» ничего не удаляет. Вечное дело становится
        // обычным, назначенным на тот день, который и так был следующим, —
        // поэтому назначенный возврат обязан пережить отмену.
        val id = note()
        val items = repo.applyParse(
            id,
            parsed(item(text = "выносить мусор", window = null, dueKind = DueKind.NONE)
                .copy(repeat = "weekly:mon")),
        )
        val itemId = items.single().id
        val planned = db.returns().all().single()

        val kept = repo.stopRepeat(itemId)

        assertEquals(Instant.ofEpochMilli(planned.scheduledAt), kept)
        val after = db.items().byId(itemId)!!
        assertNull("правило осталось", after.repeatRule)
        assertEquals("пункт не стал обычным делом с датой", DueKind.EXACT.wire, after.dueKind)
        assertEquals(planned.scheduledAt, after.dueAt)
        assertTrue("ближайший раз потерян", scheduler.scheduled.containsKey(planned.id))
        assertTrue("возврат снят из базы", db.returns().all().any { it.id == planned.id })
    }

    @Test
    fun `вернуть из снекбара возвращает повтор на то же место`() = runTest {
        // Снекбар обещает откат, а не «почти откат»: правило то же, ближайший
        // раз тот же, дата-подделка из отмены снята.
        val id = note()
        val items = repo.applyParse(
            id,
            parsed(item(text = "выносить мусор", window = null, dueKind = DueKind.NONE)
                .copy(repeat = "weekly:mon")),
        )
        val itemId = items.single().id
        val planned = db.returns().all().single().scheduledAt

        repo.stopRepeat(itemId)
        repo.resumeRepeat(itemId, "weekly:mon")

        val after = db.items().byId(itemId)!!
        assertEquals("weekly:mon", after.repeatRule)
        assertEquals(planned, after.repeatNextAt)
        assertEquals("осталась дата от отмены", DueKind.NONE.wire, after.dueKind)
        assertNull(after.dueAt)
        assertEquals("возвратов расплодилось", 1, db.returns().all().size)
    }

    @Test
    fun `разговор кончается делом, а срок берётся только из речи`() = runTest {
        // Р-20.2, макет 13c. Правило §24.5 не делает исключений ради красивой
        // концовки: сказал срок — ставим, не сказал — дело живое без даты.
        val id = note()
        repo.applyParse(id, parsed(item()))
        val before = db.items().forNote(id).size

        val dated = repo.finishInterviewWithStep(
            id,
            ai.prinim.prinyal.llm.DeepSeekClient.FirstStep(
                text = "написать пятерым клиентам",
                dueAt = Instant.parse("2026-08-30T09:00:00Z").toEpochMilli(),
            ),
        )!!
        assertEquals("написать пятерым клиентам", dated.text)
        assertEquals(DueKind.EXACT.wire, dated.dueKind)
        assertTrue("пометка происхождения потеряна", dated.fromInterview)
        assertEquals(before + 1, db.items().forNote(id).size)

        val undated = repo.finishInterviewWithStep(
            id,
            ai.prinim.prinyal.llm.DeepSeekClient.FirstStep("сходить к нотариусу", dueAt = null),
        )!!
        assertEquals("продукт придумал дату", DueKind.NONE.wire, undated.dueKind)
        assertNull(undated.dueAt)
    }

    @Test
    fun `дела не вышло — заметка остаётся идеей`() = runTest {
        // Заставлять человека выдумывать дело ради красивой концовки — худшее,
        // что можно сделать с разговором.
        val id = note()
        repo.applyParse(id, parsed(item()))
        val before = db.items().forNote(id).size

        assertNull(repo.finishInterviewWithStep(id, null))

        assertEquals(before, db.items().forNote(id).size)
        assertEquals(
            ai.prinim.prinyal.data.InterviewState.NONE.wire,
            db.notes().byId(id)!!.interview,
        )
    }

    /** Ответ модели по схеме спеки «Покрутить идею». */
    private fun spinAsk(text: String) = """
        {"note_type":"hypothesis",
         "slots":{"outcome":{"state":"empty","evidence":null},"givens":{"state":"empty","evidence":null},
                  "fork":{"state":"empty","evidence":null},"risks":{"state":"empty","evidence":null},
                  "step":{"state":"empty","evidence":null}},
         "action":"ask",
         "question":{"text":"$text","slot":"outcome","context_fact_id":null,
                     "fact_role":"none","anchor_quote":null},
         "summary":null}
    """.trimIndent().replace("\n", " ")

    @Test
    fun `круг замыкается сам — после ответа приходит следующий вопрос`() = runTest {
        // Спека §2: механика stateless — каждый вызов получает заметку целиком,
        // все пары вопрос-ответ и список заданных вопросов заново.
        val sent = mutableListOf<String>()
        var round = 0
        val llm = ai.prinim.prinyal.llm.DeepSeekClient(
            apiKey = "test",
            transport = { payload ->
                sent += payload
                round++
                // Вопросы разные по существу: похожие валидация отбракует
                // как перефразированный повтор, и это её работа.
                val text = if (round == 1) "Что тут главное?" else "Какой первый шаг и когда?"
                200 to """{"choices":[{"message":{"content":${
                    org.json.JSONObject.quote(spinAsk(text))
                }}}]}"""
            },
        )
        val repoWithLlm = NoteRepository(
            db, Settings(context), Analytics(context), scheduler, zone, llm = { llm },
        )
        val id = note()
        repoWithLlm.applyParse(id, parsed(item()).copy(bodyMd = "## Идея"))

        assertEquals("Что тут главное?", repoWithLlm.askNext(id))
        assertEquals(InterviewState.ASKED.wire, db.notes().byId(id)!!.interview)

        repoWithLlm.appendInterviewRound(id, "Что тут главное?", "ответил вот так")
        assertEquals(InterviewState.THINKING.wire, db.notes().byId(id)!!.interview)
        // Ответ лёг в пару к своему вопросу — иначе следующий вызов его не увидит.
        assertEquals("ответил вот так", db.questions().forNote(id).first().answer)

        assertEquals("Какой первый шаг и когда?", repoWithLlm.askNext(id))
        assertEquals(2, db.questions().forNote(id).size)

        // В промпт ушли и прежний вопрос, и ответ на него: без истории модель
        // задаст тот же вопрос второй раз.
        assertTrue("прежний вопрос не передан", sent.last().contains("Что тут главное"))
        assertTrue("ответ не передан", sent.last().contains("ответил вот так"))
    }

    @Test
    fun `докрученная заметка кончается резюме, а не вопросом`() = runTest {
        // Спека §3: стоп-условие — не отказ, а естественный конец лупа.
        val llm = ai.prinim.prinyal.llm.DeepSeekClient(
            apiKey = "test",
            transport = {
                200 to """{"choices":[{"message":{"content":${
                    org.json.JSONObject.quote(
                        """{"note_type":"plan","slots":{},"action":"summarize","question":null,
                            "summary":{"one_liner":"Уехать вдвоём на неделю",
                                       "next_step":"Завтра посмотреть билеты",
                                       "filled":"Бюджет сто тысяч."}}"""
                    )
                }}}]}"""
            },
        )
        val repoWithLlm = NoteRepository(
            db, Settings(context), Analytics(context), scheduler, zone, llm = { llm },
        )
        val id = note()
        repoWithLlm.applyParse(id, parsed(item()))

        assertNull("вместо резюме пришёл вопрос", repoWithLlm.askNext(id))

        val note = db.notes().byId(id)!!
        assertTrue("резюме не приклеено", note.spinSummary!!.contains("Уехать вдвоём"))
        assertTrue("нет первого шага", note.spinSummary!!.contains("билеты"))
        assertEquals("луп не закрылся", InterviewState.NONE.wire, note.interview)
        assertEquals("резюме завело вопрос", 0, db.questions().forNote(id).size)
    }

    @Test
    fun `модель молчит — берём заготовку, луп не ломается`() = runTest {
        // Спека §5: после двух ретраев — фолбэк из статического словаря.
        val llm = ai.prinim.prinyal.llm.DeepSeekClient(
            apiKey = "test",
            transport = { 200 to """{"choices":[{"message":{"content":""}}]}""" },
        )
        val repoWithLlm = NoteRepository(
            db, Settings(context), Analytics(context), scheduler, zone, llm = { llm },
        )
        val id = note()
        repoWithLlm.applyParse(id, parsed(item()))

        val question = repoWithLlm.askNext(id)
        assertNotNull("луп оборвался вместо заготовки", question)
        assertEquals(InterviewState.ASKED.wire, db.notes().byId(id)!!.interview)
    }

    @Test
    fun `пропуск остаётся в истории и считается`() = runTest {
        // Спека §7: пропуск — сигнал усталости, а не тишина.
        val llm = ai.prinim.prinyal.llm.DeepSeekClient(
            apiKey = "test",
            transport = {
                200 to """{"choices":[{"message":{"content":${
                    org.json.JSONObject.quote(spinAsk("Что тут главное?"))
                }}}]}"""
            },
        )
        val repoWithLlm = NoteRepository(
            db, Settings(context), Analytics(context), scheduler, zone, llm = { llm },
        )
        val id = note()
        repoWithLlm.applyParse(id, parsed(item()))
        repoWithLlm.askNext(id)

        repoWithLlm.skipQuestion(id)

        assertTrue("пропуск не записан", db.questions().forNote(id).single().skipped)
    }

    @Test
    fun `круг пинг-понга растит тело и не трогает пункты`() = runTest {
        // Р-19.1. Главная поломка была здесь: ответ уходил общим разбором, и
        // разговор об идее перетряхивал дела записи — рождались новые пункты,
        // пересобирались старые, запись могла поделиться надвое.
        val id = note()
        val items = repo.applyParse(id, parsed(item(), item(text = "второе"))
            .copy(bodyMd = "## Идея\n\nЧто задумано."))
        val before = db.items().forNote(id).map { it.id to it.text }

        repo.appendInterviewRound(id, "А если убрать вводные?", "останутся сами заметки")

        val body = db.notes().byId(id)!!.bodyMd!!
        assertTrue("замысел потерян:\n$body", "Что задумано." in body)
        assertTrue("вопрос не записан", "А если убрать вводные?" in body)
        assertTrue("ответ не записан", "останутся сами заметки" in body)
        assertEquals("пункты тронуты", before, db.items().forNote(id).map { it.id to it.text })
        // Круг замкнулся — продукт думает над следующим вопросом сам, без
        // кнопки между кругами (петля владельца от 24.08).
        assertEquals(
            "разговор не пошёл на следующий круг",
            ai.prinim.prinyal.data.InterviewState.THINKING.wire,
            db.notes().byId(id)!!.interview,
        )

        // Второй круг встаёт следующим абзацем под тем же заголовком.
        repo.appendInterviewRound(id, "А заголовки?", "заголовки оставляем")
        val second = db.notes().byId(id)!!.bodyMd!!
        assertEquals("заголовков стало больше одного", 1, second.split("## Что докрутили").size - 1)
        assertTrue("первый круг потерян", "останутся сами заметки" in second)
        assertTrue("второй круг не записан", "заголовки оставляем" in second)
        assertEquals(items.size, db.items().forNote(id).size)
    }

    @Test
    fun `без ответов итог разговора не собирается`() = runTest {
        // Р-17.2. Проверено живьём и попало в заметку: на свой же вопрос
        // модель ответила сама, и ответ лёг в «Собрано» как слова человека.
        // Правило держим кодом — промпт тут только пожелание.
        val id = note()
        repo.applyParse(id, parsed(item()).copy(bodyMd = "## Замысел"))

        // Вопросов не задавали — докручивать нечего.
        assertTrue(!repo.answeredAfterAsking(id))

        val asked = System.currentTimeMillis()
        db.questions().insert(
            ai.prinim.prinyal.data.QuestionEntity(
                id = "q1", noteId = id, text = "а как?", askedAt = asked,
            )
        )
        // Спросили, но человек молчит.
        assertTrue("вопрос без ответа сочли ответом", !repo.answeredAfterAsking(id))

        // Сегмент **до** вопроса — это «Дописать», а не ответ на него.
        db.segments().insert(
            ai.prinim.prinyal.data.SegmentEntity(
                id = "s0", noteId = id, seq = 0, audioPath = "", createdAt = asked - 1_000,
            )
        )
        assertTrue("прежнюю дописку сочли ответом", !repo.answeredAfterAsking(id))

        db.segments().insert(
            ai.prinim.prinyal.data.SegmentEntity(
                id = "s1", noteId = id, seq = 1, audioPath = "", createdAt = asked + 1_000,
            )
        )
        assertTrue("ответ не засчитан", repo.answeredAfterAsking(id))
    }

    @Test
    fun `итог разговора дописывается к «Собрано», а не затирает его`() = runTest {
        // Р-16.3. Изначальный замысел человек наговорил один раз и имеет право
        // видеть его нетронутым; что доросло в разговоре — видно отдельно.
        val id = note()
        repo.applyParse(id, parsed(item()).copy(bodyMd = "## Замысел\n\nЧто задумано."))

        assertTrue(repo.appendToBody(id, "- добавилось про сроки"))
        val first = db.notes().byId(id)!!.bodyMd!!
        assertTrue("замысел потерян:\n$first", "Что задумано." in first)
        assertTrue("нет блока разговора:\n$first", "## Что докрутили" in first)

        // Второй «Закончить» заменяет свой же блок, а не громоздит второй:
        // разговор продолжается, итог у него один.
        repo.appendToBody(id, "- добавилось про людей")
        val second = db.notes().byId(id)!!.bodyMd!!
        assertEquals("блоков стало больше одного", 1, second.split("## Что докрутили").size - 1)
        assertTrue("новый итог не записан", "про людей" in second)
        assertTrue("старый итог остался", "про сроки" !in second)
        assertTrue("замысел потерян на втором заходе", "Что задумано." in second)
    }

    @Test
    fun `пустой итог разговора блока не заводит`() = runTest {
        // Модель имеет право вернуть пустую строку: значит, ответы ничего не
        // добавили. Заголовок без содержимого обещал бы то, чего нет.
        val id = note()
        repo.applyParse(id, parsed(item()).copy(bodyMd = "## Замысел"))
        assertTrue(!repo.appendToBody(id, "   "))
        assertEquals("## Замысел", db.notes().byId(id)!!.bodyMd)
    }

    @Test
    fun `сделанный повтор не закрывается, а возвращается`() = runTest {
        // Макеты 10b: зелёное «сделано» означает «насовсем», и повтор им
        // помечать нельзя — он закрылся только до следующего раза.
        val id = note()
        val items = repo.applyParse(
            id,
            parsed(item(text = "выносить мусор", window = null, dueKind = DueKind.NONE)
                .copy(repeat = "weekly:mon")),
        )
        val itemId = items.single().id
        val first = db.returns().all().single()

        repo.markDone(itemId)

        val after = db.items().byId(itemId)!!
        assertEquals("повтор попал в «сделано»", ItemState.RETURNED.wire, after.state)
        assertNotNull("не записан сделанный раз", after.repeatDoneAt)
        assertNotNull("не назначен следующий раз", after.repeatNextAt)
        // Раз остался в истории: без него «всего 9 раз» соврёт.
        assertTrue(
            "сделанный раз не попал в историю",
            db.returns().all().any { it.action == "done" },
        )
        assertTrue(
            "следующий раз не назначен",
            db.returns().all().any { it.id != first.id && it.firedAt == null },
        )
    }

    @Test
    fun `в план возвращает без срока и не трогает слова`() = runTest {
        // Р-18.4. Граница правила «закрытое неприкосновенно»: неприкосновенны
        // слова — текст и источник не меняются; состояние — не слово.
        val id = note()
        val items = repo.applyParse(id, parsed(item(text = "отдать ключи")))
        val itemId = items.single().id
        repo.markDone(itemId)

        val was = repo.reviveItem(itemId)

        assertEquals(ItemState.DONE, was)
        val after = db.items().byId(itemId)!!
        assertEquals(ItemState.PLANNED.wire, after.state)
        assertEquals("вернулся со сроком", DueKind.NONE.wire, after.dueKind)
        assertNull(after.dueAt)
        assertEquals("текст тронут", "отдать ключи", after.text)
        assertNotNull("возврат не попал в историю", after.revivedAt)
        // Расписание не назначено: его в этом продукте назначает только речь.
        assertTrue(
            "возврату назначили аларм",
            db.returns().forItem(itemId).none { it.firedAt == null },
        )
    }

    @Test
    fun `в план не берёт живое и повторы`() = runTest {
        val id = note()
        val items = repo.applyParse(
            id,
            parsed(
                item(text = "живое"),
                item(text = "повтор", window = null, dueKind = DueKind.NONE)
                    .copy(repeat = "weekly:mon"),
            ),
        )
        assertNull("вернул живой пункт", repo.reviveItem(items[0].id))
        repo.markDone(items[1].id)
        // Сделанный повтор и так уходит в «вернусь» — «В план» ему не нужен.
        assertNull("вернул повтор", repo.reviveItem(items[1].id))
    }

    @Test
    fun `откат из снекбара закрывает обратно тем же словом`() = runTest {
        val id = note()
        val itemId = repo.applyParse(id, parsed(item())).single().id
        repo.buryItem(itemId)

        val was = repo.reviveItem(itemId)!!
        repo.unreviveItem(itemId, was)

        val after = db.items().byId(itemId)!!
        assertEquals(ItemState.EXPIRED.wire, after.state)
        assertNull("история сохранила отменённый возврат", after.revivedAt)
    }

    @Test
    fun `итог недели требует трёх дней и не пересобирается`() = runTest {
        // Р-18.3: сводка из двух вечеров — пересказ двух вечеров, а не неделя.
        val monday = java.time.LocalDate.of(2026, 8, 17)
        suspend fun day(offset: Long, text: String) {
            db.days().insert(
                ai.prinim.prinyal.data.DayEntity(
                    date = monday.plusDays(offset).toString(),
                    audioPath = "",
                    transcript = text,
                    createdAt = 1L,
                )
            )
        }
        day(0, "скандал с подрядчиком")
        day(1, "ничего")
        assertNull("итог собрался из двух дней", repo.buildWeekRecap(monday.plusDays(6)))

        day(3, "досидели до сметы")
        // llm в тестах отсутствует (провайдер по умолчанию null) — итога нет,
        // но и записи о нём нет: соберётся, когда модель ответит.
        assertNull(repo.buildWeekRecap(monday.plusDays(6)))
        assertNull(db.weekRecaps().byWeek(monday.toString()))
    }

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
        val stored = db.items().byId(items[0].id)!!

        // Сверяем пересборку, а не метку времени: вечер записи и «завтра утром» могут
        // совпасть по моменту (вечернее окно уже прошло → тоже завтра), и тогда
        // сравнение timestamp'ов проверяло бы календарь, а не поведение.
        assertTrue("возврат должен быть пересобран", after.id != before.id)
        assertTrue("старый аларм должен быть снят", scheduler.cancelled.contains(before.id))
        assertTrue("новый аларм должен быть поставлен", scheduler.scheduled.containsKey(after.id))
        assertEquals(Window.TOMORROW_MORNING.wire, stored.window)
        assertTrue(stored.edited)
    }

    @Test
    fun `правка на «не возвращать» снимает возврат совсем`() = runTest {
        val id = note()
        val items = repo.applyParse(id, parsed(item(window = Window.EVENING)))
        val before = db.returns().forItem(items[0].id).single()

        repo.editItem(items[0].id, clearSchedule = true)

        val stored = db.items().byId(items[0].id)!!
        assertEquals(DueKind.NONE.wire, stored.dueKind)
        assertNull(stored.window)
        assertTrue(db.returns().forItem(items[0].id).isEmpty())
        assertTrue(scheduler.cancelled.contains(before.id))
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

    // --- мягкое удаление (спека R1.1 §2.2) ---

    @Test
    fun `удаление скрывает запись и снимает алармы, undo возвращает всё`() = runTest {
        val id = note()
        val items = repo.applyParse(id, parsed(item()))
        val pending = db.returns().forItem(items[0].id).single()

        repo.softDeleteNote(id)

        assertTrue("аларм должен быть снят", pending.id in scheduler.cancelled)
        assertTrue("из выборок запись ушла", db.notes().all().none { it.id == id })
        assertNotNull("но физически жива до зачистки", db.notes().byId(id))

        repo.restoreNote(id)

        assertTrue("вернулась в выборки", db.notes().all().any { it.id == id })
        assertTrue("аларм переставлен", scheduler.scheduled.containsKey(pending.id))
    }

    @Test
    fun `зачистка после снекбара необратима и убирает аудио`() = runTest {
        val id = note()
        repo.applyParse(id, parsed(item()))
        val audio = File(db.notes().byId(id)!!.audioPath)

        repo.softDeleteNote(id)
        repo.purgeDeleted()

        assertNull(db.notes().byId(id))
        assertTrue("пункты ушли каскадом", db.items().forNote(id).isEmpty())
        assertTrue("аудио удалено", !audio.exists())
    }

    @Test
    fun `групповая уборка забирает только записи без пунктов`() = runTest {
        val junk1 = note("junk1")
        repo.markFailed(junk1, "asr_empty")
        val junk2 = note("junk2")
        repo.markFailed(junk2, "asr_failed")
        val meaningful = note("mean1")
        repo.applyParse(meaningful, parsed(item()))

        val swept = repo.sweepJunk()

        assertEquals(setOf(junk1, junk2), swept.toSet())
        assertTrue(db.notes().all().any { it.id == meaningful })
    }

    @Test
    fun `запись сразу попадает в очередь на отправку`() = runTest {
        val id = note()
        assertEquals(1, db.notes().pending().size)
        assertEquals(NoteStatus.RECORDED.wire, db.notes().byId(id)!!.status)
    }
}
