package ai.prinim.prinyal.domain

import ai.prinim.prinyal.R
import ai.prinim.prinyal.data.Confidence
import ai.prinim.prinyal.data.DueKind
import ai.prinim.prinyal.data.ItemState
import ai.prinim.prinyal.data.ItemEntity
import ai.prinim.prinyal.data.ItemType
import ai.prinim.prinyal.data.Window
import android.content.Context
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Перевод состояний в строки словаря §4.1.
 *
 * Ни одна строка не собирается в UI и ни одна не приходит с бэкенда: бэкенд отдаёт
 * коды, здесь они становятся словами. Это то, что удерживает словарь единственным.
 */
object Phrases {

    private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /** План возврата: «верну в 19:30» / «напомню завтра утром» / «просто сохраню». */

    fun plan(
        context: Context,
        item: ItemEntity,
        zone: ZoneId = ZoneId.systemDefault(),
        /** Заголовок «ПОВТОРЯЮТСЯ» уже сказал «в плане» — не повторяемся. */
        bare: Boolean = false,
    ): String {
        // Повтор перебивает срок: у повторяющегося пункта «вернусь 25 авг»
        // сообщает про один раз из многих и потому врёт про суть.
        Repeat.of(item.repeatRule)?.let { rule ->
            val done = item.repeatDoneAt
            val next = item.repeatNextAt
            // Сделанный повтор говорит про раз, а не про порядок: он закрылся
            // до следующего числа, и это ровно то, что человеку надо знать.
            if (done != null && next != null && ItemState.of(item.state) == ItemState.RETURNED) {
                return context.getString(
                    R.string.plan_repeat_done,
                    Dates.whenWas(done, zone = zone),
                    Dates.day(next, zone),
                )
            }
            return if (bare) rule.human() else context.getString(R.string.plan_repeat, rule.human())
        }
        return when (DueKind.of(item.dueKind)) {
            DueKind.EXACT -> {
                // Миллисекунды: третье место, где жила та же ошибка единиц.
                // Здесь она была особенно тихой — «вернусь в 03:00» выглядит
                // просто странным временем, а не датой из 1970 года.
                val at = item.dueAt?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), zone) }
                when {
                    at == null -> context.getString(R.string.plan_none)
                    // Сегодня важно время, в другой день — сам день: «вернусь
                    // 10 сен» отвечает на вопрос человека, «вернусь в 09:00»
                    // через три недели — нет (Д-8).
                    at.toLocalDate() == LocalDateTime.now(zone).toLocalDate() ->
                        context.getString(R.string.plan_exact, at.format(HHMM))
                    else -> context.getString(R.string.plan_exact_date, Dates.day(at))
                }
            }
            DueKind.WINDOW -> when (Window.of(item.window)) {
                Window.MORNING -> context.getString(R.string.plan_window_morning)
                Window.DAY -> context.getString(R.string.plan_window_day)
                Window.EVENING -> context.getString(R.string.plan_window_evening)
                Window.TOMORROW_MORNING -> context.getString(R.string.plan_window_tomorrow_morning)
                Window.WEEKEND -> context.getString(R.string.plan_window_weekend)
                null -> context.getString(R.string.plan_none)
            }
            DueKind.NONE -> context.getString(R.string.plan_none)
        }
    }

    /** Пометка низкой уверенности. Продукт никогда не выглядит увереннее, чем он есть. */
    fun uncertainty(context: Context, item: ItemEntity): String? =
        if (Confidence.of(item.confidence) == Confidence.LOW) {
            context.getString(R.string.plan_low_confidence)
        } else {
            null
        }

    /** Причина возврата — у каждого действия машины видна причина (ТЗ UI §2, п. 4). */
    fun reason(
        context: Context,
        item: ItemEntity,
        attempt: Int,
        firedAt: Instant,
        windows: Scheduler.Windows,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        if (attempt >= 2) return context.getString(R.string.reason_second_try)
        if (DueKind.of(item.dueKind) == DueKind.EXACT) {
            return context.getString(R.string.reason_exact)
        }
        return when (Scheduler.windowOf(firedAt, windows, zone) ?: Window.of(item.window)) {
            Window.MORNING, Window.TOMORROW_MORNING -> context.getString(R.string.reason_morning)
            Window.DAY -> context.getString(R.string.reason_day)
            Window.EVENING -> context.getString(R.string.reason_evening)
            Window.WEEKEND -> context.getString(R.string.reason_weekend)
            null -> context.getString(R.string.reason_day)
        }
    }

    /** Подтверждение «позже»: код назвал момент — покажем какой. */
    fun laterConfirmation(context: Context, at: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
        val local = LocalDateTime.ofInstant(at, zone)
        val now = LocalDateTime.now(zone)
        val words = when {
            local.toLocalDate() != now.toLocalDate() && local.hour < 12 ->
                context.getString(R.string.window_tomorrow_morning)
            local.hour < 12 -> context.getString(R.string.window_morning)
            local.hour < 17 -> context.getString(R.string.window_day)
            else -> context.getString(R.string.window_evening)
        }
        return context.getString(R.string.action_later_confirmed, words)
    }

    /**
     * Деградации §6 → строки словаря. Ни одной метафоры: в шторке смысл читается
     * за секунду или не читается вовсе.
     */
    fun degraded(context: Context, code: String?): String? = when (code) {
        null -> null
        "llm_no_balance" -> context.getString(R.string.error_no_balance)
        "llm_disabled" -> context.getString(R.string.error_llm_off)
        "asr_failed" -> context.getString(R.string.error_asr_failed)
        "asr_empty" -> context.getString(R.string.error_asr_empty)
        "no_server", "not_configured" -> context.getString(R.string.error_no_server)
        // llm_empty / llm_error / llm_timeout и всё незнакомое — один честный текст.
        else -> context.getString(R.string.error_parse_failed)
    }

    fun typeLabel(context: Context, type: ItemType): String = context.getString(
        when (type) {
            ItemType.BUY -> R.string.type_buy
            ItemType.DO -> R.string.type_do
            ItemType.TELL -> R.string.type_tell
            ItemType.DATE -> R.string.type_date
            ItemType.THOUGHT -> R.string.type_thought
            ItemType.FACT -> R.string.type_fact
            ItemType.DECISION -> R.string.type_decision
        }
    )

    fun windowLabel(context: Context, window: Window?): String = context.getString(
        when (window) {
            Window.MORNING -> R.string.window_morning
            Window.DAY -> R.string.window_day
            Window.EVENING -> R.string.window_evening
            Window.TOMORROW_MORNING -> R.string.window_tomorrow_morning
            Window.WEEKEND -> R.string.window_weekend
            null -> R.string.window_none
        }
    )

    /** Часть дня для сводки «разобрал 3 записи за утро». */
    fun partOfDay(context: Context, at: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
        val hour = LocalDateTime.ofInstant(at, zone).hour
        return context.getString(
            when {
                hour in 5..11 -> R.string.part_of_day_morning
                hour in 12..17 -> R.string.part_of_day_day
                hour in 18..22 -> R.string.part_of_day_evening
                else -> R.string.part_of_day_night
            }
        )
    }
}
