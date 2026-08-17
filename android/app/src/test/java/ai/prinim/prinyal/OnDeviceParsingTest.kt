package ai.prinim.prinyal

import ai.prinim.prinyal.data.Confidence
import ai.prinim.prinyal.data.DueKind
import ai.prinim.prinyal.data.ItemType
import ai.prinim.prinyal.data.Window
import ai.prinim.prinyal.llm.DeepSeekClient
import ai.prinim.prinyal.llm.ItemValidator
import ai.prinim.prinyal.llm.Prompt
import ai.prinim.prinyal.net.IngestOutcome
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Разбор на устройстве: инварианты PRD §3 и деградации §6 переехали с бэкенда
 * в приложение вместе с самим разбором — и должны работать здесь так же.
 *
 * Robolectric нужен не для Android-API, а ради настоящего `org.json`: в обычном
 * JVM-тесте это заглушка из android.jar, и клиент молча не соберёт запрос.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnDeviceParsingTest {

    private val zone: ZoneId = ZoneId.of("Europe/Moscow")
    private val now: LocalDateTime = LocalDateTime.parse("2026-08-04T14:00")
    private val transcript = "капли купить соню к лору записать и мужу сказать что суббота занята"

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun client(retries: Int = 2) = DeepSeekClient(
        apiKey = "test-key",
        baseUrl = server.url("/").toString().trimEnd('/'),
        retries = retries,
    )

    private fun reply(items: String, finish: String = "stop") = MockResponse()
        .setResponseCode(200)
        .setBody(
            """{"choices":[{"message":{"role":"assistant","content":${
                JSONObject.quote("{\"items\":$items}")
            }},"finish_reason":"$finish"}]}"""
        )

    // --- инварианты валидации ---

    private fun validateOne(json: String) = ItemValidator
        .validate(listOf(JSONObject(json)), transcript, now, zone)

    @Test
    fun `неизвестный тип падает в мысль, а не выбрасывается`() {
        val result = validateOne(
            """{"type":"urgent","text":"купить капли","due_kind":"none","confidence":"high"}"""
        )
        assertEquals(ItemType.THOUGHT, result.items[0].type)
        assertEquals("купить капли", result.items[0].text)
        assertEquals(1, result.salvaged)
    }

    @Test
    fun `неизвестная уверенность становится low, а не high`() {
        val result = validateOne(
            """{"type":"do","text":"позвонить","due_kind":"none","confidence":"уверен"}"""
        )
        assertEquals(Confidence.LOW, result.items[0].confidence)
    }

    @Test
    fun `who отбрасывается, если адресат не звучал`() {
        val result = validateOne(
            """{"type":"tell","text":"сказать про субботу","who":"Марина",
                "due_kind":"window","window":"evening","confidence":"high"}"""
        )
        assertNull(result.items[0].who)
    }

    @Test
    fun `who остаётся, если прозвучал в другом падеже`() {
        val result = validateOne(
            """{"type":"tell","text":"сказать что суббота занята","who":"муж",
                "due_kind":"window","window":"evening","confidence":"high"}"""
        )
        assertEquals("муж", result.items[0].who)
    }

    @Test
    fun `время из прошлого не назначается`() {
        val result = validateOne(
            """{"type":"do","text":"позвонить","due_kind":"exact",
                "exact_local":"2026-08-04T09:00","window":"day","confidence":"high"}"""
        )
        assertNull(result.items[0].dueAt)
        assertEquals(DueKind.WINDOW, result.items[0].dueKind)
    }

    @Test
    fun `будущее точное время становится unixtime`() {
        val result = validateOne(
            """{"type":"do","text":"стоматолог","due_kind":"exact",
                "exact_local":"2026-08-07T19:00","confidence":"high"}"""
        )
        // Миллисекунды. Прежде здесь стояли секунды — и тест закреплял ошибку:
        // всё остальное читает dueAt через Instant.ofEpochMilli, то есть явная
        // дата уезжала в январь 1970. Тест с неверной единицей хуже, чем его
        // отсутствие: он делает баг «проверенным».
        val expected = LocalDateTime.parse("2026-08-07T19:00").atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, result.items[0].dueAt)
        assertNull(result.items[0].window)
    }

    @Test
    fun `факт никогда не планируется`() {
        val result = validateOne(
            """{"type":"fact","text":"паспорта в синей коробке","due_kind":"window",
                "window":"evening","confidence":"high"}"""
        )
        val item = result.items[0]
        assertEquals(DueKind.NONE, item.dueKind)
        assertNull(item.window)
        assertNull(item.dueAt)
    }

    @Test
    fun `угловые скобки не доезжают до нотификации`() {
        val result = validateOne(
            """{"type":"do","text":"<b>купить</b> капли","due_kind":"none","confidence":"high"}"""
        )
        assertTrue(result.items[0].text.none { it == '<' || it == '>' })
    }

    @Test
    fun `текст обрезается по слову`() {
        val long = "очень длинная формулировка ".repeat(10)
        val trimmed = ItemValidator.trimWords(long)
        assertTrue(trimmed.length <= ItemValidator.MAX_TEXT + 1)
        assertTrue(trimmed.endsWith("…"))
    }

    // --- клиент и деградации ---

    @Test
    fun `комок разбирается на пункты с разными планами`() {
        server.enqueue(
            reply(
                """[{"type":"buy","text":"купить капли","due_kind":"window","window":"day",
                     "confidence":"high","raw_span":"капли купить"},
                    {"type":"do","text":"записать Соню к лору","due_kind":"window","window":"morning",
                     "confidence":"high","raw_span":"соню к лору записать"},
                    {"type":"tell","text":"сказать мужу про субботу","who":"муж","due_kind":"window",
                     "window":"evening","confidence":"high","raw_span":"мужу сказать"}]"""
            )
        )

        val outcome = client().parse(transcript, now, zone)
        val result = (outcome as IngestOutcome.Ok).result

        assertEquals(3, result.items.size)
        assertEquals(3, result.items.map { it.window }.toSet().size)
        assertEquals("муж", result.items[2].who)
        assertNull(result.degraded)
    }

    @Test
    fun `запрос уходит с рассуждениями и бюджетом под них`() {
        server.enqueue(reply("""[{"type":"do","text":"тест","due_kind":"none","confidence":"high"}]"""))
        client().parse(transcript, now, zone)

        val recorded = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)
            ?: error("запрос до DeepSeek не ушёл")
        val body = JSONObject(recorded.body.readUtf8())
        // Рассуждения не выключаем (Р-13.6): они чинят типы, окна и искажённые
        // распознаванием имена. Бюджет обязан вмещать их вместе с ответом —
        // при 2048 рассуждения съедали его целиком и content приходил пустым.
        assertTrue("рассуждения выключать не нужно", !body.has("thinking"))
        assertTrue("бюджета не хватит на рассуждения", body.getInt("max_tokens") >= 8192)
        assertEquals("json_object", body.getJSONObject("response_format").getString("type"))
        assertEquals(false, body.getBoolean("stream"))
        // Слово «json» в промпте — требование провайдера.
        assertTrue(Prompt.SYSTEM.contains("json"))
    }

    @Test
    fun `упёрлись в потолок токенов — деградируем сразу, без лишних кругов`() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"choices":[{"message":{"content":""},"finish_reason":"length"}]}""")
        )

        val result = (client().parse(transcript, now, zone) as IngestOutcome.Ok).result
        assertEquals("llm_empty", result.degraded)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `пустой content ретраится и может получиться со второго раза`() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"choices":[{"message":{"content":""},"finish_reason":"stop"}]}""")
        )
        server.enqueue(reply("""[{"type":"do","text":"купить капли","due_kind":"none","confidence":"high"}]"""))

        val result = (client().parse(transcript, now, zone) as IngestOutcome.Ok).result
        assertNull(result.degraded)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `кончился баланс — свой код и без ретраев`() {
        server.enqueue(MockResponse().setResponseCode(402))
        val result = (client().parse(transcript, now, zone) as IngestOutcome.Ok).result
        assertEquals("llm_no_balance", result.degraded)
        assertEquals(1, server.requestCount)
        assertEquals(ItemType.THOUGHT, result.items[0].type)
    }

    @Test
    fun `5xx ретраится и уходит в fallback-план, а не в ошибку`() {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503)) }

        val result = (client().parse(transcript, now, zone) as IngestOutcome.Ok).result
        assertEquals("llm_error", result.degraded)
        assertEquals(1, result.items.size)
        assertEquals(ItemType.THOUGHT, result.items[0].type)
        assertEquals(Window.EVENING, result.items[0].window)
        assertEquals(transcript, result.items[0].text)
    }

    @Test
    fun `нет сети — запись ждёт в очереди, а не получает выдуманный план`() {
        // Сервер выключен: до DeepSeek не достучались ни разу.
        server.shutdown()
        val outcome = client(retries = 0).parse(transcript, now, zone)
        assertTrue("получили $outcome", outcome is IngestOutcome.Retryable)
    }

    @Test
    fun `промпт на устройстве той же версии, что на сервере`() {
        // Разные версии означали бы, что два пути разбора дают разные пункты.
        assertEquals("4", Prompt.VERSION)
    }
}
