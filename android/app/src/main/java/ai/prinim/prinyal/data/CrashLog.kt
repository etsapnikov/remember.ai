package ai.prinim.prinyal.data

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

/**
 * Журнал падений (Р-17.1).
 *
 * Заведён после «периодически крашится»: без записи падения разговор о нём —
 * это обмен догадками. Стектрейс живёт в файле рядом с базой, читается в меню
 * разработчика и никуда не уходит: наружу продукт не отправляет ничего, а
 * трейс несёт тексты заметок в сообщениях исключений.
 *
 * Обработчик не глотает падение: он записывает и передаёт дальше системному —
 * приложение обязано упасть так же, как упало бы без нас. Проглоченный краш
 * оставляет процесс в поломанном состоянии, и следующая ошибка будет уже не
 * той, что случилась на самом деле.
 */
class CrashLog(context: Context) {

    private val file = File(context.filesDir, "crashes.log")

    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(thread: Thread, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val record = buildString {
            append("=== ").append(Instant.now()).append(" · ").append(thread.name).append('\n')
            append(trace.take(MAX_TRACE)).append('\n')
        }
        // Пишем в начало: последнее падение — то, о котором спрашивают.
        val kept = runCatching { file.readText() }.getOrDefault("").take(MAX_FILE)
        file.writeText(record + kept)
    }

    /** Последние падения, свежие сверху. Пусто — падений не было. */
    fun records(limit: Int = 5): List<Record> {
        if (!file.exists()) return emptyList()
        val text = runCatching { file.readText() }.getOrNull().orEmpty()
        return text.split("=== ")
            .asSequence()
            .filter { it.isNotBlank() }
            .take(limit)
            .map { chunk ->
                val head = chunk.substringBefore('\n')
                val body = chunk.substringAfter('\n').trim()
                Record(
                    at = runCatching { Instant.parse(head.substringBefore(" · ")) }.getOrNull(),
                    thread = head.substringAfter(" · ", ""),
                    // Первая строка — что упало, вторая наша — где.
                    what = body.lineSequence().firstOrNull().orEmpty(),
                    where = body.lineSequence().firstOrNull { it.contains("ai.prinim.prinyal") }
                        ?.trim()
                        .orEmpty(),
                    trace = body,
                )
            }
            .toList()
    }

    fun clear() {
        runCatching { file.delete() }
    }

    fun path(): String = file.absolutePath

    data class Record(
        val at: Instant?,
        val thread: String,
        val what: String,
        val where: String,
        val trace: String,
    )

    private companion object {
        /** Хвост трейса ниже этого предела не помогает, а файл растит. */
        const val MAX_TRACE = 4_000

        /** Сколько истории держим: файл не должен расти без конца. */
        const val MAX_FILE = 40_000
    }
}
