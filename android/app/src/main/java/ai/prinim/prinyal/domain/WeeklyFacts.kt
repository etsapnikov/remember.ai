package ai.prinim.prinyal.domain

/**
 * Факты недели (Р-15.9).
 *
 * Экран рассказывает 3–5 наблюдений сухим языком вместо счётчиков: «закрылось
 * то, что висело с июля», «раздел „Продукт" двигался каждый день».
 *
 * **Факты считаются кодом, а не сочиняются моделью** — и это осознанное
 * отступление от буквы ТЗ. Причина: наблюдение о собственной неделе человек не
 * может проверить, кроме как поверив; выдуманный факт здесь дороже отсутствия
 * фактов, потому что подрывает доверие ко всему, что продукт говорит о данных.
 * Считая их сами, мы получаем ещё и проверяемость: каждый факт — это запрос,
 * который либо сходится, либо нет.
 *
 * Модель могла бы формулировать эти же факты красивее. Но красота здесь не
 * стоит риска: правило PRD §4.1 и так требует сухого языка.
 *
 * Порог у каждого факта свой и подобран так, чтобы факт был **новостью**, а не
 * пересказом очевидного: «одна запись за неделю» новостью не является.
 */
object WeeklyFacts {

    /** Сырьё для наблюдений — всё, что нужно посчитать заранее. */
    data class Signal(
        /** Закрыто дел, ждавших дольше [LONG_WAIT_DAYS]. */
        val closedLongWaiting: Int = 0,
        /** Сколько дней подряд двигался самый активный раздел, и какой. */
        val busiestTopic: String? = null,
        val busiestTopicDays: Int = 0,
        /** Раздел, о котором за неделю сказано несколько раз. */
        val repeatedTopic: String? = null,
        val repeatedTopicNotes: Int = 0,
        /** Сколько дней ждёт самое старое живое дело. */
        val oldestWaitingDays: Int = 0,
        /** Сколько дел закрыто за неделю всего. */
        val closed: Int = 0,
        /** Сколько дел человек отменил как ненужные. */
        val dropped: Int = 0,
        /** Сколько дел принёс за неделю и сколько из них ещё висит (Р-20.3). */
        val brought: Int = 0,
        val hanging: Int = 0,
        /** Сколько разговоров об идее кончились делом. */
        val grown: Int = 0,
    )

    /** Меньше — не про «принесённое не пропало», а про пустую неделю. */
    const val KEPT_MIN = 3

    /** Что именно рассказать. Строки живут в strings.xml, здесь только выбор. */
    sealed interface Fact {
        data class ClosedOld(val count: Int) : Fact
        data class TopicMoved(val topic: String, val days: Int) : Fact
        data class TopicRepeated(val topic: String, val notes: Int) : Fact
        data class OldestWaiting(val days: Int) : Fact
        data class Dropped(val count: Int) : Fact

        /**
         * «Из 17 принесённых дел висят четыре — остальные ты закрыл» (Р-20.3).
         *
         * Подкрепляем не число, а факт: **принесённое не пропало**. Доля
         * названа словами, а не процентом: процент человек начнёт держать, а
         * фразу держать нельзя. Ни одна из трёх метрик, которые напрашивались,
         * не годилась — число закрытых оптимизируется дроблением, отношение —
         * недоговариванием, скорость — избеганием трудного.
         */
        data class Kept(val brought: Int, val hanging: Int) : Fact

        /** «Два разговора кончились делом» — только когда они были. */
        data class Grown(val count: Int) : Fact
    }

    const val LONG_WAIT_DAYS = 14
    private const val MOVED_DAYS = 3
    private const val REPEATED_NOTES = 3

    /** Выше этого числа записи об одном — не догадка, а просто активный раздел. */
    private const val REPEATED_MAX = 6
    private const val DROPPED_MIN = 3
    private const val MAX_FACTS = 5

    /**
     * @return от нуля до [MAX_FACTS] наблюдений; пусто — значит рассказывать
     *         нечего, и экран будет честно коротким
     */
    fun facts(signal: Signal): List<Fact> = buildList {
        // Порядок — по ценности для человека: сначала про сдвинувшееся, потом
        // про накопившееся. Про висящее говорим последним: это не упрёк, а
        // сведение, и открывать им неделю незачем.
        if (signal.closedLongWaiting > 0) add(Fact.ClosedOld(signal.closedLongWaiting))
        if (signal.busiestTopic != null && signal.busiestTopicDays >= MOVED_DAYS) {
            add(Fact.TopicMoved(signal.busiestTopic, signal.busiestTopicDays))
        }
        // Про один и тот же раздел два наблюдения подряд — это не два факта, а
        // одно, сказанное дважды: «Продукт двигался пять дней» и «о Продукте
        // три записи» человек прочтёт как повтор.
        val alreadyToldAbout = filterIsInstance<Fact.TopicMoved>().map { it.topic }
        // Сверху тоже есть граница. «Шестнадцать записей об одном — возможно,
        // зреет затея» звучит глупо: шестнадцать записей это не зреющая затея,
        // а рабочий раздел, и про его активность уже сказано выше. Догадка
        // уместна, пока замысел ещё можно не заметить.
        if (signal.repeatedTopic != null &&
            signal.repeatedTopicNotes in REPEATED_NOTES..REPEATED_MAX &&
            signal.repeatedTopic !in alreadyToldAbout
        ) {
            add(Fact.TopicRepeated(signal.repeatedTopic, signal.repeatedTopicNotes))
        }
        if (signal.dropped >= DROPPED_MIN) add(Fact.Dropped(signal.dropped))
        if (signal.oldestWaitingDays >= LONG_WAIT_DAYS) {
            add(Fact.OldestWaiting(signal.oldestWaitingDays))
        }
        // Подкрепление стоит среди наблюдений, а не отдельным блоком: продукт
        // отчитывается о своей работе, а не о качестве человека (макет 13d).
        if (signal.brought >= KEPT_MIN && signal.hanging < signal.brought) {
            add(Fact.Kept(signal.brought, signal.hanging))
        }
        if (signal.grown > 0) add(Fact.Grown(signal.grown))
    }.take(MAX_FACTS)
}
