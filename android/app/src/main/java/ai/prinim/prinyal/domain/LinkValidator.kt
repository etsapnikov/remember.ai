package ai.prinim.prinyal.domain

import ai.prinim.prinyal.data.Confidence
import ai.prinim.prinyal.data.LinkReason
import org.json.JSONArray

/**
 * Связи, которые модель предложила, — и то, что из них можно принять (Р-15.11).
 *
 * Принцип недоверия PRD §4 здесь особенно уместен: связь между записями человек
 * не заказывал, она появляется сама. Ложная связь хуже отсутствующей — она
 * утверждает, что две мысли про одно, и человек ищет продолжение там, где его
 * нет.
 *
 * Отсюда правила, каждое из своей беды:
 *
 *  - **ссылка только на предложенного кандидата.** Модель вправе промахнуться
 *    id, и тогда связь укажет на чужую заметку — молча и правдоподобно;
 *  - **самоссылка отбрасывается** — «связано само с собой» не связь;
 *  - **дубли схлопываются**: две причины к одной заметке означают, что модель
 *    не выбрала, а перечислила;
 *  - **low не проходит.** У связи нет цены ошибки «переспросить»: она либо есть
 *    на карточке, либо нет;
 *  - **не больше трёх.** Четвёртая связь — это уже не «связано», а лента.
 */
object LinkValidator {

    const val MAX_LINKS = 3

    data class Link(val ref: String, val reason: LinkReason, val confidence: Confidence)

    /**
     * @param raw массив `links` из ответа модели
     * @param candidates id, которые мы сами и предложили
     * @param selfId заметка, которую разбираем
     */
    fun validate(raw: JSONArray?, candidates: Set<String>, selfId: String): List<Link> {
        if (raw == null) return emptyList()
        val seen = mutableSetOf<String>()
        val out = mutableListOf<Link>()

        for (i in 0 until raw.length()) {
            val item = raw.optJSONObject(i) ?: continue
            val ref = item.optString("ref").trim()
            if (ref.isEmpty() || ref == selfId) continue
            if (ref !in candidates) continue
            if (!seen.add(ref)) continue

            val reason = LinkReason.of(item.optString("reason").trim().lowercase()) ?: continue
            val confidence = Confidence.entries
                .firstOrNull { it.wire == item.optString("confidence").trim().lowercase() }
                ?: Confidence.LOW
            // Неуверенная связь не показывается вовсе: у неё нет мягкой формы.
            if (confidence == Confidence.LOW) continue

            out += Link(ref, reason, confidence)
            if (out.size == MAX_LINKS) break
        }
        return out
    }
}
