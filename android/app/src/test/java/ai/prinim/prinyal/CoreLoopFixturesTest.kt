package ai.prinim.prinyal

import ai.prinim.prinyal.data.DueKind
import ai.prinim.prinyal.llm.DeepSeekClient
import ai.prinim.prinyal.llm.Prompt
import ai.prinim.prinyal.net.IngestOutcome
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Ярус 1 системы автотестов кор-лупа (Р-15.16).
 *
 * Прогоняет фикстурные транскрипты через настоящий клиент разбора, подсовывая
 * **записанные** ответы модели вместо сети. Так проверяется наш код — валидатор,
 * планировщик дат, сборка результата, — а не настроение модели: живая модель
 * даёт разные ответы на один и тот же текст, и тест на ней проверял бы погоду.
 *
 * Плёнка привязана к версии промпта. Промпт поменяли — записи устарели молча, и
 * зелёный прогон означал бы «проверили вчерашний продукт». Поэтому несовпадение
 * версии — падение с прямым указанием, что делать.
 *
 * Перезаписать плёнку: `python3 scripts/record_fixtures.py --force`
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoreLoopFixturesTest {

    private val zone: ZoneId = ZoneId.systemDefault()

    private val root = File("../../fixtures")
    private val notes = File(root, "notes")
    private val replies = File(root, "llm-replies")

    private fun read(file: File) = JSONObject(file.readText())

    /** Транспорт-плёнка: отдаёт записанный ответ, в сеть не ходит. */
    private fun tape(body: String) = DeepSeekClient.let { _ ->
        ai.prinim.prinyal.llm.LlmTransport { 200 to body }
    }

    private fun fixtures(): List<Pair<JSONObject, JSONObject>> {
        val files = notes.listFiles { f -> f.extension == "json" }?.sortedBy { it.name }
        assertNotNull("фикстуры не найдены: ${notes.absolutePath}", files)
        assertTrue("фикстур нет", files!!.isNotEmpty())

        return files.map { file ->
            val fixture = read(file)
            val replyFile = File(replies, "${fixture.getString("id")}.json")
            assertTrue(
                "нет записанного ответа для «${fixture.getString("id")}» — " +
                    "запусти python3 scripts/record_fixtures.py",
                replyFile.isFile,
            )
            val reply = read(replyFile)
            assertEquals(
                "плёнка записана на промпте версии ${reply.optString("prompt_version")}, " +
                    "а в коде версия ${Prompt.VERSION} — перезапиши: " +
                    "python3 scripts/record_fixtures.py --force",
                Prompt.VERSION,
                reply.optString("prompt_version"),
            )
            fixture to reply
        }
    }

    private fun parse(fixture: JSONObject, reply: JSONObject): IngestOutcome {
        val client = DeepSeekClient(
            apiKey = "test",
            transport = tape(reply.getJSONObject("response").toString()),
        )
        return client.parse(
            transcript = fixture.getString("transcript"),
            now = LocalDateTime.parse(fixture.getString("at")),
            zone = zone,
        )
    }

    @Test
    fun `каждая фикстура разбирается без деградации`() {
        fixtures().forEach { (fixture, reply) ->
            val id = fixture.getString("id")
            val outcome = parse(fixture, reply)
            assertTrue("$id: разбор не удался — $outcome", outcome is IngestOutcome.Ok)
            val result = (outcome as IngestOutcome.Ok).result
            assertEquals("$id: деградация на записанном ответе", null, result.degraded)
        }
    }

    @Test
    fun `число пунктов в ожидаемых границах`() {
        // Границы, а не точное число: модель вправе выбрать формулировку, но не
        // вправе потерять пункт или выдумать лишний.
        fixtures().forEach { (fixture, reply) ->
            val id = fixture.getString("id")
            val expect = fixture.getJSONObject("expect")
            val items = ((parse(fixture, reply) as IngestOutcome.Ok)).result.items

            expect.optInt("items_min", -1).takeIf { it >= 0 }?.let { min ->
                assertTrue("$id: пунктов ${items.size}, ждали ≥ $min", items.size >= min)
            }
            expect.optInt("items_max", -1).takeIf { it >= 0 }?.let { max ->
                assertTrue("$id: пунктов ${items.size}, ждали ≤ $max", items.size <= max)
            }
        }
    }

    @Test
    fun `вид записи и раздел определены там, где ожидались`() {
        fixtures().forEach { (fixture, reply) ->
            val id = fixture.getString("id")
            val expect = fixture.getJSONObject("expect")
            val result = (parse(fixture, reply) as IngestOutcome.Ok).result

            expect.optJSONArray("note_kind")?.let { allowed ->
                val wire = result.noteKind?.wire
                assertTrue(
                    "$id: вид записи «$wire», ждали один из ${allowed}",
                    wire != null && wire in allowed.strings(),
                )
            }
            if (expect.optBoolean("has_topic")) {
                assertTrue("$id: раздел не определён", !result.topic.isNullOrBlank())
            }
            if (expect.optBoolean("body_md")) {
                assertTrue("$id: «Собрано» не собрано", !result.bodyMd.isNullOrBlank())
            }
        }
    }

    @Test
    fun `явная дата считается верно, включая перенос на будущий год`() {
        // Год всегда ближайший будущий: «10 августа», сказанное 17 августа,
        // означает следующий год, а не позавчера (Р-15.7).
        fixtures().forEach { (fixture, reply) ->
            val id = fixture.getString("id")
            val expect = fixture.getJSONObject("expect")
            val expectedDate = expect.optString("due_date").takeIf { it.isNotBlank() } ?: return@forEach
            val items = (parse(fixture, reply) as IngestOutcome.Ok).result.items

            val exact = items.firstOrNull { it.dueKind == DueKind.EXACT && it.dueAt != null }
            assertNotNull("$id: пункта с точной датой нет", exact)

            val day = Instant.ofEpochMilli(exact!!.dueAt!!).atZone(zone).toLocalDate().toString()
            assertEquals("$id: дата не та", expectedDate, day)
        }
    }

    @Test
    fun `возврат никогда не планируется в прошлое`() {
        fixtures().forEach { (fixture, reply) ->
            val id = fixture.getString("id")
            val now = LocalDateTime.parse(fixture.getString("at")).atZone(zone).toInstant()
            (parse(fixture, reply) as IngestOutcome.Ok).result.items.forEach { item ->
                item.dueAt?.let { at ->
                    assertTrue(
                        "$id: возврат «${item.text}» назначен в прошлое",
                        Instant.ofEpochMilli(at) >= now,
                    )
                }
            }
        }
    }

    @Test
    fun `люди из речи попадают в сущности`() {
        fixtures().forEach { (fixture, reply) ->
            val id = fixture.getString("id")
            val wanted = fixture.getJSONObject("expect").optJSONArray("entities_any")
                ?.strings() ?: return@forEach
            val found = (parse(fixture, reply) as IngestOutcome.Ok).result.entities
                .map { it.lowercase() }
            assertTrue(
                "$id: ждали кого-то из $wanted, нашли $found",
                wanted.any { name -> found.any { it.startsWith(name.lowercase().take(3)) } },
            )
        }
    }

    @Test
    fun `битый ответ модели роняет разбор, а не проходит молча`() {
        // Мутационная проверка приёмки Р-15.16: если подложить мусор, тест
        // обязан это заметить. Иначе зелёный CI ничего не значит.
        val (fixture, reply) = fixtures().first()
        val broken = JSONObject(reply.getJSONObject("response").toString()).apply {
            getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").put("content", "{\"items\": []}")
        }
        val client = DeepSeekClient(apiKey = "test", transport = tape(broken.toString()))
        val outcome = client.parse(
            transcript = fixture.getString("transcript"),
            now = LocalDateTime.parse(fixture.getString("at")),
            zone = zone,
        )
        val result = (outcome as IngestOutcome.Ok).result
        assertEquals("пустой ответ модели должен помечаться деградацией", "llm_empty", result.degraded)
    }

    private fun JSONArray.strings(): List<String> =
        (0 until length()).map { optString(it) }
}
