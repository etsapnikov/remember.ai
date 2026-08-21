package ai.prinim.prinyal.domain

import ai.prinim.prinyal.data.Analytics
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/**
 * Сколько стоил разбор (Р-16.4).
 *
 * Токены — факт, деньги — оценка, и эта разница важнее самой цифры. Токены
 * приходят от провайдера в каждом ответе и складываются точно; цена за миллион
 * вбита в код руками и живёт ровно до следующего изменения прайса. Поэтому
 * экран показывает и то, и другое: по токенам можно пересчитать самому, если
 * цифра денег устареет.
 *
 * Кэшированный вход считается отдельно: у DeepSeek он в разы дешевле обычного,
 * а в разборе его большинство — системный промпт один и тот же на все записи.
 */
object TokenSpend {

    /**
     * Цена за миллион токенов, доллары.
     *
     * Прайс `deepseek-v4-flash` на 21 августа 2026. Провайдер меняет его без
     * предупреждения — это единственное место, где числа надо будет поправить,
     * и потому они здесь, а не размазаны по коду.
     */
    const val IN_FRESH = 0.27
    const val IN_CACHED = 0.028
    const val OUT = 1.10

    data class Spend(
        val calls: Int,
        val cachedIn: Long,
        val freshIn: Long,
        val out: Long,
        /** Расход по видам запросов: разбор, второй заход, пинг-понг, правки. */
        val byKind: Map<String, Double>,
    ) {
        val tokens: Long get() = cachedIn + freshIn + out

        /** Доллары. Оценка, а не счёт: цена вбита в код и могла устареть. */
        val dollars: Double
            get() = (freshIn * IN_FRESH + cachedIn * IN_CACHED + out * OUT) / 1_000_000.0
    }

    /**
     * @param since считать с этого момента; null — за всё время
     */
    fun of(
        events: List<JSONObject>,
        since: Instant? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Spend {
        var calls = 0
        var cached = 0L
        var fresh = 0L
        var out = 0L
        val byKind = mutableMapOf<String, Double>()

        events.forEach { event ->
            if (event.optString("e") != Analytics.LLM_USAGE) return@forEach
            if (since != null && event.optLong("t") < since.toEpochMilli()) return@forEach

            val c = event.optLong("cached_in")
            val f = event.optLong("fresh_in")
            val o = event.optLong("out")
            calls++
            cached += c
            fresh += f
            out += o

            val kind = event.optString("kind").ifBlank { "—" }
            val money = (f * IN_FRESH + c * IN_CACHED + o * OUT) / 1_000_000.0
            byKind[kind] = (byKind[kind] ?: 0.0) + money
        }

        return Spend(calls, cached, fresh, out, byKind)
    }

    /**
     * «$0.0143» — четыре знака: на дневных числах двух не хватает, всё нули.
     *
     * Локаль принудительно английская: доллар с запятой («$0,0143») выглядит
     * опечаткой, а не суммой, — цена приходит от провайдера в его записи.
     */
    fun money(dollars: Double): String =
        "$" + String.format(java.util.Locale.US, "%.4f", dollars)

    /**
     * «12 340» — неразрывный пробел вместо запятой.
     *
     * Запятая в русском тексте читается как дробная часть, а обычный пробел
     * разрешил бы переносу разорвать число между строк.
     */
    fun tokens(count: Long): String =
        count.toString().reversed().chunked(3).joinToString("\u00A0").reversed()
}
