package ai.prinim.prinyal

import ai.prinim.prinyal.asr.AudioDecoder
import ai.prinim.prinyal.capture.UploadWorker
import ai.prinim.prinyal.data.CaptureSource
import ai.prinim.prinyal.llm.DeepSeekClient
import ai.prinim.prinyal.llm.LlmTransport
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import kotlin.math.roundToInt

/**
 * Кор-фича «заметка с возвратом», путь целиком: наговорил → появилось в ленте
 * → возврат назначен.
 *
 * Чем это отличается от остальных тестов. Они проверяют **решения**: правильно
 * ли посчитан срок, верно ли валидатор отбросил мусор. Этот проверяет
 * **сборку**: что воркер собрал вызов из тех данных, из которых должен, и что
 * результат доехал до базы.
 *
 * Разница не теоретическая. Связи между записями не работали три недели — ноль
 * связей на сто двадцать один разбор, — потому что воркер читал заметку из базы
 * **до** распознавания и искал похожих на пустую строку. Промпт, валидатор и
 * отбор были исправны, каждый по отдельности проверен и зелен. Сломано было
 * звено между ними, а его не проверял никто: фикстуры подают кандидатов
 * готовым списком и потому не могут заметить, что в бою их не подаёт никто.
 *
 * Поэтому тест смотрит **в payload**, ушедший модели, а не только в ответ.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureToFeedTest {

    private val app: PrinyalApp get() = ApplicationProvider.getApplicationContext()

    /** Что ушло модели за прогон — по одному payload на вызов. */
    private val sent = mutableListOf<String>()

    /**
     * Речь, «записанная» в файл, и слух, который её оттуда достаёт.
     *
     * Пара швов вместо одного, потому что путь состоит из двух шагов: файл
     * раскодировать в сэмплы, сэмплы услышать словами. Кодируем текст прямо в
     * сэмплы — так тесту не нужен ни словарь соответствий, ни порядок вызовов:
     * что записали в файл, то движок и слышит.
     */
    @Before
    fun wireSpeech() {
        // База живёт в приложении, а приложение переживает отдельный тест.
        // Без явной очистки записи предыдущего теста становятся кандидатами на
        // связь в следующем, и проверка кандидатов проходит по чужим данным.
        Thread { app.db.clearAllTables() }.apply { start(); join() }
        AudioDecoder.decoder = { file ->
            val text = file.readText()
            FloatArray(text.length) { i -> text[i].code / 65_535f }
        }
        app.useAsr { samples ->
            String(CharArray(samples.size) { i -> (samples[i] * 65_535f).roundToInt().toChar() })
        }
    }

    @After
    fun unwireSpeech() {
        AudioDecoder.decoder = null
    }

    private fun answer(body: String) {
        app.useLlm(
            DeepSeekClient(
                apiKey = "test",
                transport = LlmTransport { payload ->
                    sent += payload
                    200 to body
                },
            )
        )
    }

    /** Ответ модели в том виде, в каком его отдаёт API: json внутри строки. */
    private fun reply(json: String): String =
        org.json.JSONObject()
            .put(
                "choices",
                org.json.JSONArray().put(
                    org.json.JSONObject().put(
                        "message",
                        org.json.JSONObject().put("content", json),
                    )
                )
            )
            .toString()

    /**
     * Запись в том виде, в каком её оставляет экран: файл есть, текста нет.
     *
     * Текст появляется **внутри** воркера, из подменённого распознавания — как
     * в бою. Положить транскрипт в базу заранее было бы проще и полностью
     * бесполезно: воркер читает заметку до распознавания, и тест, обошедший
     * этот порядок, обходит вместе с ним всю породу ошибок, которую должен
     * ловить. Проверено: с готовым транскриптом тест не замечает возвращённой
     * поломки связей.
     */
    private suspend fun note(id: String, transcript: String, at: Instant = Instant.parse("2026-08-20T09:00:00Z")): String {
        val audio = File(app.filesDir, "$id.m4a").apply { writeText(transcript) }
        app.repository.createNote(id, audio, 4_000, CaptureSource.WIDGET, at)
        return id
    }

    private suspend fun runWorker(): ListenableWorker.Result =
        TestListenableWorkerBuilder<UploadWorker>(app).build().doWork()

    @Test
    fun `наговорил дело — оно в ленте и возврат назначен`() = runTest {
        answer(
            reply(
                """
                {"note_kind":"task","topic":"Дом","items":[
                  {"type":"do","text":"купить капли","due_kind":"window","window":"evening",
                   "confidence":"high","source_span":"капли"}
                ]}
                """.trimIndent()
            )
        )
        note("n1", "надо купить капли вечером не забыть")

        assertEquals(ListenableWorker.Result.success(), runWorker())

        val items = app.db.items().forNote("n1")
        assertEquals("пункт не доехал до базы", 1, items.size)
        assertEquals("купить капли", items.first().text)

        val returns = app.db.returns().forItem(items.first().id)
        assertTrue("возврат не назначен — фича не работает", returns.isNotEmpty())
    }

    @Test
    fun `в модель уходит распознанный текст, а не пустая заметка`() = runTest {
        answer(reply("""{"note_kind":"task","items":[]}"""))
        note("n1", "созвон с эмилем про мультитул перенесли")

        runWorker()

        assertEquals(1, sent.size)
        assertTrue(
            "транскрипт не доехал до модели: ${sent.first().take(200)}",
            sent.first().contains("мультитул"),
        )
    }

    @Test
    fun `прежние записи уходят в модель кандидатами на связь`() = runTest {
        // Первая запись — та, с которой вторая должна связаться.
        answer(reply("""{"note_kind":"task","topic":"Работа","items":[]}"""))
        note("n1", "начали делать пак контекста по авторизации")
        runWorker()
        sent.clear()

        answer(reply("""{"note_kind":"task","items":[]}"""))
        note("n2", "по авторизации решили что пак собираем вручную")
        runWorker()

        assertEquals(1, sent.size)
        // Ищем строку кандидата целиком, а не «n1» отдельно: в промпте есть
        // нумерованные правила, и «\n1.» содержит «n1» — проверка на голый id
        // проходит всегда и не значит ничего.
        assertTrue(
            "кандидатов в payload нет — связи не заработают никогда, " +
                "как не работали три недели: ${sent.first().takeLast(400)}",
            sent.first().contains("n1 — начали делать пак"),
        )
    }

    @Test
    fun `живые разделы уходят в модель, чтобы она не выдумывала синонимы`() = runTest {
        answer(reply("""{"note_kind":"task","topic":"Работа","items":[]}"""))
        note("n1", "по работе решили релиз в пятницу")
        runWorker()
        sent.clear()

        answer(reply("""{"note_kind":"task","items":[]}"""))
        note("n2", "ещё одна рабочая мысль")
        runWorker()

        assertTrue(
            "раздел «Работа» не ушёл в промпт — модель заведёт «Работу» второй раз",
            sent.first().contains("Работа"),
        )
    }

    @Test
    fun `словарь автозамен уходит в промпт вместе с текстом`() = runTest {
        app.db.replacements().insert(
            ai.prinim.prinyal.data.ReplacementEntity(
                id = "r1", fromPhrase = "гига ам", fromNorm = "гига ам", toPhrase = "GigaAM",
                createdAt = System.currentTimeMillis(), hits = 0,
            )
        )
        answer(reply("""{"note_kind":"task","items":[]}"""))
        note("n1", "посмотреть что там с гига ам")
        runWorker()

        assertTrue(
            "глоссарий не доехал: замена чинит написание, промпт чинит понимание",
            sent.first().contains("GigaAM"),
        )
    }

    @Test
    fun `пустая очередь не будит модель`() = runTest {
        answer(reply("""{"note_kind":"task","items":[]}"""))
        runWorker()
        assertTrue("воркер сходил в модель без единой записи", sent.isEmpty())
    }

    @Test
    fun `запись без файла помечается, но не пропадает`() = runTest {
        answer(reply("""{"note_kind":"task","items":[]}"""))
        val id = note("n1", "текст есть, а файла нет")
        File(app.db.notes().byId(id)!!.audioPath).delete()

        runWorker()

        assertNotNull("заметка исчезла — человек не узнает, что здесь что-то было",
            app.db.notes().byId(id))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `разбор пачки после оффлайна разгребает всю очередь за раз`() = runTest {
        answer(reply("""{"note_kind":"task","items":[]}"""))
        repeat(5) { i -> note("n$i", "запись номер $i из самолётного режима") }

        runWorker()

        assertEquals("разобрана не вся очередь", 5, sent.size)
        assertFalse("в очереди остались записи", app.db.notes().pending().isNotEmpty())
    }

    // ——— Кор-фича «заметка со списком» ———

    @Test
    fun `список остаётся одной записью, а не рассыпается на заметки`() = runTest {
        answer(
            reply(
                """
                {"note_kind":"list","topic":"Дом","items":[
                  {"type":"buy","text":"молоко","due_kind":"none","confidence":"high","source_span":"молоко"},
                  {"type":"buy","text":"хлеб","due_kind":"none","confidence":"high","source_span":"хлеб"},
                  {"type":"buy","text":"яйца","due_kind":"none","confidence":"high","source_span":"яйца"}
                ]}
                """.trimIndent()
            )
        )
        note("n1", "купить молоко хлеб яйца")

        runWorker()

        assertEquals("список расползся по ленте", 1, app.db.notes().all().size)
        assertEquals("пункты списка потерялись", 3, app.db.items().forNote("n1").size)
    }

    @Test
    fun `пункты списка не тянут за собой возвраты`() = runTest {
        answer(
            reply(
                """
                {"note_kind":"list","items":[
                  {"type":"buy","text":"молоко","due_kind":"none","confidence":"high","source_span":"молоко"},
                  {"type":"buy","text":"хлеб","due_kind":"none","confidence":"high","source_span":"хлеб"}
                ]}
                """.trimIndent()
            )
        )
        note("n1", "купить молоко и хлеб")

        runWorker()

        // Список — это то, на что смотрят в магазине, а не то, о чём звонят.
        // Три пункта продуктов, ставших тремя напоминаниями, — способ научить
        // человека выключать уведомления совсем.
        val returns = app.db.items().forNote("n1").flatMap { app.db.returns().forItem(it.id) }
        assertTrue("список назначил ${returns.size} напоминаний", returns.isEmpty())
    }

    // ——— Кор-фича «категоризация в структуру» ———

    @Test
    fun `запись уходит в раздел, названный моделью`() = runTest {
        answer(
            reply(
                """
                {"note_kind":"task","topic":"Работа","items":[
                  {"type":"do","text":"дописать гайдлайн","due_kind":"window","window":"day",
                   "confidence":"high","source_span":"гайдлайн"}
                ]}
                """.trimIndent()
            )
        )
        note("n1", "надо дописать гайдлайн по доменам")

        runWorker()

        val topicId = app.db.notes().byId("n1")?.topicId
        assertNotNull("запись осталась без раздела — структура не собирается", topicId)
        assertEquals("Работа", app.db.topics().byId(topicId!!)?.name)
    }

    @Test
    fun `второй раз тот же раздел не заводится заново`() = runTest {
        // С пунктом, а не пустой: раздел назначается заметке, в которой есть
        // что относить, — пустую относить некуда.
        val work = reply(
            """
            {"note_kind":"task","topic":"Работа","items":[
              {"type":"do","text":"дописать гайдлайн","due_kind":"none",
               "confidence":"high","source_span":"гайдлайн"}
            ]}
            """.trimIndent()
        )
        answer(work)
        note("n1", "первая рабочая запись про гайдлайн")
        runWorker()

        answer(work)
        note("n2", "вторая рабочая запись про гайдлайн")
        runWorker()

        assertEquals(
            "«Работа» завелась дважды — структура рассыпается на синонимы",
            1, app.db.topics().live().count { it.name == "Работа" },
        )
    }
}
