package ai.prinim.prinyal.llm

import ai.prinim.prinyal.data.Confidence
import ai.prinim.prinyal.data.DueKind
import ai.prinim.prinyal.data.ItemType
import ai.prinim.prinyal.data.Window
import ai.prinim.prinyal.net.ParsedItem
import org.json.JSONObject
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Валидация пунктов на устройстве. Принцип недоверия к модели из PRD §4: она
 * предлагает семантику, решения принимает код.
 *
 * Раньше это жило на бэкенде (`app/validate.py`). Когда разбор переехал в
 * приложение, инварианты §3 должны были переехать вместе с ним — иначе они просто
 * исчезли бы, и первый же странный ответ модели попал бы в базу как есть.
 *
 * Главный инвариант: пункт, не прошедший валидацию, не выбрасывается, а падает в
 * `thought / none`. Запись пользователя не теряется никогда.
 */
object ItemValidator {

    const val MAX_TEXT = 120
    private const val MAX_WHO = 40
    private const val MAX_RAW_SPAN = 200

    /** Дальше этого горизонта точное время почти наверняка галлюцинация разбора. */
    private val MAX_HORIZON: Duration = Duration.ofDays(370)

    private val FORMATS = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"),
    )

    data class Result(val items: List<ParsedItem>, val salvaged: Int)

    fun validate(
        raw: List<JSONObject>,
        transcript: String,
        now: LocalDateTime,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Result {
        val items = mutableListOf<ParsedItem>()
        var salvaged = 0

        raw.forEach { json ->
            val (item, wasSalvaged) = validateOne(json, transcript, now, zone)
            if (item != null) {
                items += item
                if (wasSalvaged) salvaged++
            }
        }
        return Result(items, salvaged)
    }

    private fun validateOne(
        json: JSONObject,
        transcript: String,
        now: LocalDateTime,
        zone: ZoneId,
    ): Pair<ParsedItem?, Boolean> {
        val text = sanitize(json.optString("text"))
        // Пункт без текста — не пункт; терять при этом нечего, транскрипт цел.
        if (text.isBlank()) return null to false

        var salvaged = false

        val rawType = json.optString("type").trim().lowercase()
        val type = ItemType.entries.firstOrNull { it.wire == rawType }
        if (type == null) salvaged = true

        val rawConfidence = json.optString("confidence").trim().lowercase()
        val confidence = Confidence.entries.firstOrNull { it.wire == rawConfidence }
        // Неизвестная уверенность — не «high»: продукт не имеет права выглядеть
        // увереннее, чем он есть.
        if (confidence == null) salvaged = true

        var who: String? = null
        val rawWho = json.optString("who").takeIf { !json.isNull("who") && it.isNotBlank() }
        if (rawWho != null) {
            val candidate = sanitize(rawWho).take(MAX_WHO)
            // `who` — только если адресат явно прозвучал в записи (PRD §3).
            who = if (soundsIn(candidate, transcript)) candidate else null
            if (who == null) salvaged = true
        }

        var dueKind = DueKind.entries.firstOrNull { it.wire == json.optString("due_kind").lowercase() }
            ?: DueKind.NONE
        var window = Window.entries.firstOrNull { it.wire == json.optString("window").lowercase() }
        var dueAt: Long? = null

        if (dueKind == DueKind.EXACT) {
            dueAt = parseExact(json, now, zone)
            if (dueAt == null) {
                // Время не разобралось — не выдумываем его, откатываемся в окно.
                dueKind = if (window != null) DueKind.WINDOW else DueKind.NONE
                salvaged = true
            } else {
                window = null
            }
        }
        if (dueKind == DueKind.WINDOW && window == null) {
            dueKind = DueKind.NONE
            salvaged = true
        }
        if (dueKind == DueKind.NONE) window = null

        val finalType = type ?: ItemType.THOUGHT
        // Факт о мире не возвращается в R1 — он копится молча (PRD §11, п. 3).
        if (finalType == ItemType.FACT) {
            dueKind = DueKind.NONE
            window = null
            dueAt = null
        }

        val rawSpan = json.optString("raw_span")
            .takeIf { !json.isNull("raw_span") && it.isNotBlank() }
            ?.let { sanitize(it).take(MAX_RAW_SPAN) }

        return ParsedItem(
            type = finalType,
            text = trimWords(text),
            who = who,
            dueKind = dueKind,
            window = window,
            dueAt = dueAt,
            confidence = confidence ?: Confidence.LOW,
            rawSpan = rawSpan,
        ) to salvaged
    }

    /** Обрезка по слову: обрубленное посреди слова читается как баг, а не как лимит. */
    fun trimWords(text: String, limit: Int = MAX_TEXT): String {
        val normalized = text.split(Regex("\\s+")).filter(String::isNotEmpty).joinToString(" ")
        if (normalized.length <= limit) return normalized
        var cut = normalized.take(limit)
        val space = cut.lastIndexOf(' ')
        if (space >= limit / 2) cut = cut.take(space)
        return cut.trimEnd(' ', ',', '.', ';', ':', '—', '-') + "…"
    }

    /**
     * HTML невозможен по построению — рендер идёт через текст, — но угловые скобки
     * убираем здесь, чтобы они не всплыли в нотификации Android: она HTML понимает.
     */
    fun sanitize(text: String): String =
        text.replace("<", "").replace(">", "").replace("&", "и")
            .split(Regex("\\s+")).filter(String::isNotEmpty).joinToString(" ")

    /**
     * Имя считается прозвучавшим, если его основа есть в транскрипте: модель
     * склоняет («муж» → «мужу»), а транскрипт — сырец.
     */
    private fun soundsIn(who: String, transcript: String): Boolean {
        val haystack = transcript.lowercase()
        return who.lowercase().split(' ').any { word ->
            val stem = if (word.length > 4) word.take(4) else word
            stem.isNotEmpty() && haystack.contains(stem)
        }
    }

    /**
     * Модель отдаёт местное «YYYY-MM-DDTHH:MM», unixtime считает код.
     * Инвариант PRD §3: `due_at` только в будущем.
     */
    private fun parseExact(json: JSONObject, now: LocalDateTime, zone: ZoneId): Long? {
        val raw = listOf("exact_local", "due_local", "due_at")
            .firstNotNullOfOrNull { key ->
                if (json.isNull(key)) null else json.optString(key).takeIf(String::isNotBlank)
            } ?: return null

        val text = raw.trim().replace(' ', 'T')
        val parsed = FORMATS.firstNotNullOfOrNull { format ->
            runCatching { LocalDateTime.parse(text.take(19), format) }.getOrNull()
        } ?: runCatching { LocalDateTime.parse("${text.take(10)}T09:00", FORMATS[1]) }.getOrNull()
        ?: return null

        val seconds = parsed.atZone(zone).toEpochSecond()
        val nowSeconds = now.atZone(zone).toEpochSecond()
        if (seconds <= nowSeconds) return null
        if (seconds - nowSeconds > MAX_HORIZON.seconds) return null
        return seconds
    }

    /**
     * Деградация §6: разбор не случился — вся запись становится одним `thought`.
     * Окно назначает планировщик, здесь только семантика «верну вечером».
     */
    fun fallback(transcript: String): List<ParsedItem> = listOf(
        ParsedItem(
            type = ItemType.THOUGHT,
            text = trimWords(sanitize(transcript)),
            who = null,
            dueKind = DueKind.WINDOW,
            window = Window.EVENING,
            dueAt = null,
            confidence = Confidence.LOW,
            rawSpan = null,
        )
    )
}
