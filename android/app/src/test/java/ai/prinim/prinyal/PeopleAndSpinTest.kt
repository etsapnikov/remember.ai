package ai.prinim.prinyal

import ai.prinim.prinyal.asr.AudioDecoder
import ai.prinim.prinyal.capture.InterviewWorker
import ai.prinim.prinyal.capture.PersonTellWorker
import ai.prinim.prinyal.data.CaptureSource
import ai.prinim.prinyal.data.Confidence
import ai.prinim.prinyal.data.DueKind
import ai.prinim.prinyal.data.ItemType
import ai.prinim.prinyal.data.Window
import ai.prinim.prinyal.llm.DeepSeekClient
import ai.prinim.prinyal.llm.LlmTransport
import ai.prinim.prinyal.net.ParseResult
import ai.prinim.prinyal.net.ParsedItem
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
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
import kotlin.math.roundToInt

/**
 * Две кор-фичи, которые роднит одно: у них своя дорога записи, в обход ленты.
 *
 * «Люди»: рассказ про человека пополняет карточку. Раньше «Рассказать»
 * открывало обычный захват, и рассказ про Веру ложился заметкой в «Записи» —
 * с пунктами и возвратами, которых человек не просил. Он добавлял контекст, а
 * получал дело в плане и мусор в ленте.
 *
 * «Покрутить идею»: ответ на вопрос дописывается в тело заметки. Раньше он шёл
 * через обычный разбор и перетряхивал пункты — человек отвечал на вопрос, а у
 * него менялись дела.
 *
 * Обе поломки выглядели одинаково снаружи: продукт делает лишнее там, где его
 * не просили. И обе жили в воркерах, которых не касался ни один тест.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PeopleAndSpinTest {

    private val app: PrinyalApp get() = ApplicationProvider.getApplicationContext()
    private val sent = mutableListOf<String>()

    @Before
    fun setUp() {
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
    fun tearDown() {
        AudioDecoder.decoder = null
    }

    private fun answer(content: String) {
        app.useLlm(
            DeepSeekClient(
                apiKey = "test",
                transport = LlmTransport { payload ->
                    sent += payload
                    200 to org.json.JSONObject()
                        .put(
                            "choices",
                            org.json.JSONArray().put(
                                org.json.JSONObject().put(
                                    "message",
                                    org.json.JSONObject().put("content", content),
                                )
                            )
                        )
                        .toString()
                },
            )
        )
    }

    /** Вопрос в том виде, в каком его отдаёт модель по спеке «Покрутить идею». */
    private fun ask(text: String): String =
        """{"question":{"text":"$text","slot":"outcome","fact_role":"none"}}"""

    private fun speech(name: String, text: String): File =
        File(app.filesDir, "$name.m4a").apply { writeText(text) }

    // ——— Люди ———

    @Test
    fun `рассказ о человеке пополняет карточку`() = runTest {
        answer("""{"facts":["воспитательница Сони","зовут Вера Ивановна"]}""")
        app.db.people().insert(
            ai.prinim.prinyal.data.PersonEntity(
                id = "p1", name = "Вера", nameNorm = "вера",
                firstSeen = System.currentTimeMillis(),
            )
        )
        val audio = speech("tell", "вера это воспитательница сони зовут вера ивановна")

        TestListenableWorkerBuilder<PersonTellWorker>(app)
            .setInputData(workDataOf("person" to "p1", "audio" to audio.absolutePath))
            .build()
            .doWork()

        val facts = app.db.personFacts().forPerson("p1")
        assertTrue("факты не доехали до карточки", facts.isNotEmpty())
    }

    @Test
    fun `рассказ о человеке не заводит заметку в ленте`() = runTest {
        answer("""{"facts":["воспитательница Сони"]}""")
        app.db.people().insert(
            ai.prinim.prinyal.data.PersonEntity(
                id = "p1", name = "Вера", nameNorm = "вера",
                firstSeen = System.currentTimeMillis(),
            )
        )
        val audio = speech("tell", "вера это воспитательница сони")

        TestListenableWorkerBuilder<PersonTellWorker>(app)
            .setInputData(workDataOf("person" to "p1", "audio" to audio.absolutePath))
            .build()
            .doWork()

        assertEquals(
            "рассказ про человека утёк в «Записи» — человек получит дело, о котором не просил",
            0, app.db.notes().all().size,
        )
    }

    @Test
    fun `рассказ можно повторить — карточка копит, а не заменяет`() = runTest {
        app.db.people().insert(
            ai.prinim.prinyal.data.PersonEntity(
                id = "p1", name = "Вера", nameNorm = "вера",
                firstSeen = System.currentTimeMillis(),
            )
        )
        answer("""{"facts":["воспитательница Сони"]}""")
        TestListenableWorkerBuilder<PersonTellWorker>(app)
            .setInputData(workDataOf("person" to "p1",
                "audio" to speech("t1", "вера воспитательница сони").absolutePath))
            .build().doWork()

        answer("""{"facts":["живёт на Мосфильмовской"]}""")
        TestListenableWorkerBuilder<PersonTellWorker>(app)
            .setInputData(workDataOf("person" to "p1",
                "audio" to speech("t2", "вера живёт на мосфильмовской").absolutePath))
            .build().doWork()

        val facts = app.db.personFacts().forPerson("p1").map { it.text }
        assertTrue(
            "второй рассказ затёр первый — «Рассказать» живёт одно нажатие: $facts",
            facts.size >= 2,
        )
    }

    @Test
    fun `аудио рассказа не остаётся на диске`() = runTest {
        answer("""{"facts":["воспитательница Сони"]}""")
        app.db.people().insert(
            ai.prinim.prinyal.data.PersonEntity(
                id = "p1", name = "Вера", nameNorm = "вера",
                firstSeen = System.currentTimeMillis(),
            )
        )
        val audio = speech("tell", "вера воспитательница сони")

        TestListenableWorkerBuilder<PersonTellWorker>(app)
            .setInputData(workDataOf("person" to "p1", "audio" to audio.absolutePath))
            .build().doWork()

        // Рассказ живёт фактами, а не звуком: хранить запись незачем, а
        // накапливать её на телефоне человека — тем более.
        assertTrue("аудио рассказа осталось лежать", !audio.exists())
    }

    // ——— Покрутить идею ———

    /** Заметка-идея с одним пунктом и заданным вопросом. */
    private suspend fun idea(): String {
        val audio = speech("idea", "есть идея сделать курс для родителей одиночек")
        app.repository.createNote("n1", audio, 5_000, CaptureSource.WIDGET, Instant.parse("2026-08-20T09:00:00Z"))
        app.repository.applyParse(
            "n1",
            ParseResult(
                transcript = "есть идея сделать курс для родителей одиночек",
                items = listOf(
                    ParsedItem(
                        ItemType.THOUGHT, "курс для родителей одиночек", null,
                        DueKind.NONE, null, null, Confidence.HIGH, "курс",
                    )
                ),
                topic = null, degraded = null, asrMs = 10, llmMs = 10, llmRetries = 0,
            ),
        )
        return "n1"
    }

    @Test
    fun `ответ на вопрос дописывается в тело и не трогает пункты`() = runTest {
        val noteId = idea()
        val before = app.db.items().forNote(noteId).map { it.id to it.text }

        answer(ask("Кто придёт первым?"))
        app.repository.askNext(noteId)

        // Ответ приходит новым сегментом — так его кладёт дописывание.
        val answerAudio = speech("ans", "первыми придут те кто уже спрашивал про группы")
        app.repository.appendSegment(noteId, answerAudio)

        answer("""{"block":"Первыми придут те, кто уже спрашивал про группы."}""")
        TestListenableWorkerBuilder<InterviewWorker>(app)
            .setInputData(workDataOf("note" to noteId))
            .build().doWork()

        val after = app.db.items().forNote(noteId).map { it.id to it.text }
        assertEquals(
            "ответ на вопрос перетряхнул дела — человек отвечал, а у него менялся план",
            before, after,
        )
        val body = app.db.notes().byId(noteId)?.bodyMd.orEmpty()
        assertTrue("ответ не попал в тело заметки: «$body»", body.contains("спрашивал про группы"))
    }

    @Test
    fun `после ответа сам приходит следующий вопрос`() = runTest {
        val noteId = idea()
        answer(ask("Кто придёт первым?"))
        app.repository.askNext(noteId)
        val first = app.db.questions().forNote(noteId).size

        app.repository.appendSegment(noteId, speech("ans", "первыми придут знакомые"))
        answer(ask("Сколько человек в группе?"))
        TestListenableWorkerBuilder<InterviewWorker>(app)
            .setInputData(workDataOf("note" to noteId))
            .build().doWork()

        assertTrue(
            "круг не замкнулся: человек ответил и остался без следующего вопроса",
            app.db.questions().forNote(noteId).size > first,
        )
    }

    @Test
    fun `промолчал — вопрос остаётся, тело не трогаем`() = runTest {
        val noteId = idea()
        answer(ask("Кто придёт первым?"))
        app.repository.askNext(noteId)
        val bodyBefore = app.db.notes().byId(noteId)?.bodyMd

        app.repository.appendSegment(noteId, speech("silence", ""))
        TestListenableWorkerBuilder<InterviewWorker>(app)
            .setInputData(workDataOf("note" to noteId))
            .build().doWork()

        assertEquals("молчание дописалось в тело", bodyBefore, app.db.notes().byId(noteId)?.bodyMd)
    }
}
