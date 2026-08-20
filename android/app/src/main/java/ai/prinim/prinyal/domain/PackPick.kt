package ai.prinim.prinyal.domain

import ai.prinim.prinyal.data.NoteWithItems
import java.time.Instant

/**
 * Что взять в пак (Д-28).
 *
 * До этого «Собрать контекст» собирал молча: из раздела брал **все** записи —
 * для «Работы» это девятнадцать, — а голосом брал всё, где встретились первые
 * четыре буквы темы. Владелец назвал это «наполняется рандомно», и был прав:
 * два разных пути, оба без спроса.
 *
 * Теперь выбор виден до того, как файл ушёл наружу. Продукт предлагает свой
 * набор, человек снимает лишнее.
 */
object PackPick {

    /** Дальше этого записи в пак по умолчанию не идут: пак — про сейчас. */
    const val FRESH_DAYS = 60L

    /**
     * Что отмечено, когда экран открылся.
     *
     * Ровно то, что продукт взял бы молча: свежие записи, в которых есть хоть
     * один пункт. Болтовня без пунктов («кофе кончился») приходит снятой — её
     * и снимали бы руками первой.
     */
    fun preselected(
        notes: List<NoteWithItems>,
        now: Instant = Instant.now(),
    ): Set<String> {
        val edge = now.minusSeconds(FRESH_DAYS * 24 * 60 * 60).toEpochMilli()
        return notes
            .filter { it.note.createdAt >= edge && it.items.isNotEmpty() }
            .map { it.note.id }
            .toSet()
    }

    /**
     * Что уйдёт в пак.
     *
     * Отмеченное **целиком**, а не только видимое под фильтром: иначе фильтр
     * молча урезал бы пак — ровно та болезнь, которую лечим.
     */
    fun chosen(notes: List<NoteWithItems>, picked: Set<String>): List<NoteWithItems> =
        notes.filter { it.note.id in picked }
}
