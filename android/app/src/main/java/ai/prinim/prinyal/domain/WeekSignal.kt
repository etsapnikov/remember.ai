package ai.prinim.prinyal.domain

import ai.prinim.prinyal.data.ItemState
import ai.prinim.prinyal.data.PrinyalDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Сбор сырья для фактов недели (Р-15.9).
 *
 * Всё считается по базе, а не по аналитике: аналитика — это лог событий, она
 * знает, что человек нажал, но не знает, чего это касалось. Факт вроде
 * «закрылось то, что висело с июля» требует связи закрытие → пункт → заметка →
 * когда сказано; такая связь есть только в базе.
 *
 * Закрытия берём из таблицы возвратов. Это **недосчёт**: пункт, закрытый рукой
 * в карточке, возврата не оставляет. Недосчёт здесь предпочтительнее: факт,
 * который нельзя предъявить данными, дороже отсутствующего факта.
 */
class WeekSignal(
    private val db: PrinyalDb,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    suspend fun build(today: LocalDate = LocalDate.now(zone)): WeeklyFacts.Signal =
        withContext(Dispatchers.IO) {
            val from = today.minusDays(WINDOW_DAYS - 1).atStartOfDay(zone).toInstant().toEpochMilli()
            val now = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

            val notes = db.notes().all().filter { it.deletedAt == null }
            val notesById = notes.associateBy { it.id }
            val items = db.items().all()
            val topics = db.topics().live().associate { it.id to it.name }

            // --- разделы недели ---
            val fresh = notes.filter { it.createdAt in from until now }
            val byTopic = fresh
                .filter { it.topicId != null }
                .groupBy { it.topicId!! }

            val moved = byTopic.maxByOrNull { (_, list) -> list.map(::dayOf).distinct().size }
            val repeated = byTopic.maxByOrNull { (_, list) -> list.size }

            // --- закрытия ---
            val returns = db.returns().all()
                .filter { it.firedAt != null && it.firedAt in from until now }
            val itemsById = items.associateBy { it.id }

            fun saidAt(itemId: String): Long? =
                itemsById[itemId]?.noteId?.let { notesById[it]?.createdAt }

            val done = returns.filter { it.action == "done" }
            val closedLongWaiting = done.count { ret ->
                val said = saidAt(ret.itemId) ?: return@count false
                daysBetween(said, ret.firedAt!!) >= WeeklyFacts.LONG_WAIT_DAYS
            }

            // --- самое старое живое ---
            val live = items.filter { ItemState.of(it.state) in LIVE }
            val oldest = live.mapNotNull { notesById[it.noteId]?.createdAt }.minOrNull()

            WeeklyFacts.Signal(
                closedLongWaiting = closedLongWaiting,
                busiestTopic = moved?.key?.let { topics[it] },
                busiestTopicDays = moved?.value?.map(::dayOf)?.distinct()?.size ?: 0,
                repeatedTopic = repeated?.key?.let { topics[it] },
                repeatedTopicNotes = repeated?.value?.size ?: 0,
                oldestWaitingDays = oldest?.let { daysBetween(it, now) } ?: 0,
                closed = done.size,
                dropped = returns.count { it.action == "dismiss" },
            )
        }

    private fun dayOf(note: ai.prinim.prinyal.data.NoteEntity): LocalDate =
        Instant.ofEpochMilli(note.createdAt).atZone(zone).toLocalDate()

    private fun daysBetween(fromMillis: Long, toMillis: Long): Int =
        ((toMillis - fromMillis) / DAY_MS).toInt()

    private companion object {
        const val WINDOW_DAYS = 7L
        const val DAY_MS = 24L * 60 * 60 * 1000
        val LIVE = setOf(ItemState.PLANNED, ItemState.RETURNED, ItemState.SNOOZED)
    }
}
