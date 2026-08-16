package ai.prinim.prinyal.domain

import ai.prinim.prinyal.data.ItemEntity
import ai.prinim.prinyal.data.ItemState
import ai.prinim.prinyal.net.ParsedItem

/**
 * Сверка пунктов после дописывания заметки (Р-14.3) — ядро задачи.
 *
 * Наивный переразбор здесь недопустим: он воскрешает закрытое и плодит дубли.
 * Человек сказал «сделал», пункт ушёл в `done` — и следующая же фраза «и справка
 * не в школе, а в поликлинике» вернула бы его в план, потому что модель разбирает
 * весь текст заново и ничего не знает про состояния.
 *
 * Правила, каждое из своей беды:
 *  - **закрытое неприкосновенно.** `done`, `dismissed`, `expired` не меняются
 *    ничем: ни текстом, ни окном, ни удалением. Воскресшее дело — худшее, что
 *    продукт может сделать с доверием;
 *  - **тронутое рукой машина не трогает.** То же правило, что у топика: правка
 *    руками обязана уменьшать будущие правки, а не воспроизводить их;
 *  - **живое можно уточнить.** `planned` и `snoozed` принимают новый текст и
 *    окно — ради этого дописывание и затевалось;
 *  - **дубль по смыслу отбрасывается.** Новый пункт, повторяющий существующий,
 *    не заводится: модель часто «переоткрывает» то, что уже есть.
 *
 * Логика чистая и без Android: сверка — то место, где ошибка тихая и дорогая,
 * и проверять её надо тестами, а не на живой базе.
 */
object Reconcile {

    /** Что делать с одним пунктом ответа модели. */
    sealed interface Action {
        /** Завести новый пункт. */
        data class Add(val item: ParsedItem) : Action

        /** Обновить существующий: текст и окно поехали. */
        data class Update(val id: String, val item: ParsedItem) : Action
    }

    data class Plan(
        val actions: List<Action>,
        /** Что отбросили и почему — для «Для разработчика». */
        val rejected: List<String>,
    )

    /**
     * @param existing пункты заметки как они есть сейчас
     * @param parsed что вернула модель на полный текст (со всеми сегментами)
     * @param refs id существующего пункта для каждого разобранного, если модель
     *        его указала; по позиции совпадает с [parsed]
     */
    fun plan(
        existing: List<ItemEntity>,
        parsed: List<ParsedItem>,
        refs: List<String?>,
    ): Plan {
        val byId = existing.associateBy { it.id }
        val actions = mutableListOf<Action>()
        val rejected = mutableListOf<String>()
        // Тексты живых пунктов — по ним ловим дубли без `ref`.
        val seen = existing.map { normalize(it.text) }.toMutableSet()

        parsed.forEachIndexed { index, item ->
            val ref = refs.getOrNull(index)
            val target = ref?.let { byId[it] }

            when {
                ref != null && target == null -> {
                    // Модель сослалась на несуществующий пункт: это не повод
                    // заводить новый — она метила в конкретный, а промахнулась.
                    rejected += "неизвестный ref: $ref"
                }

                target != null && ItemState.of(target.state) in CLOSED -> {
                    rejected += "закрытый пункт не трогаем: ${target.text}"
                }

                target != null && target.edited -> {
                    rejected += "правленный рукой пункт не трогаем: ${target.text}"
                }

                target != null -> {
                    actions += Action.Update(target.id, item)
                    seen += normalize(item.text)
                }

                normalize(item.text) in seen -> {
                    rejected += "дубль: ${item.text}"
                }

                else -> {
                    actions += Action.Add(item)
                    seen += normalize(item.text)
                }
            }
        }

        return Plan(actions, rejected)
    }

    /**
     * Сверка не удалась — разбираем только новый сегмент, старое не трогаем.
     *
     * Это осознанный откат в худшее, но безопасное поведение: дубль человек
     * заметит и уберёт, воскресшее закрытое дело — подорвёт доверие ко всей
     * петле.
     */
    fun failed(plan: Plan, parsed: List<ParsedItem>): Boolean =
        parsed.isNotEmpty() && plan.actions.isEmpty() && plan.rejected.size == parsed.size

    private val CLOSED = setOf(ItemState.DONE, ItemState.DISMISSED, ItemState.EXPIRED)

    /** Сравниваем по смыслу, а не по знакам: регистр и пунктуация не важны. */
    private fun normalize(text: String): String =
        text.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), "").replace(Regex("\\s+"), " ").trim()
}
