package ai.prinim.prinyal

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/**
 * Целость файлов шрифтов.
 *
 * Обрезанная загрузка Golos Text и JetBrains Mono (17 КБ вместо 184) один раз уже
 * доехала до телефона: заголовок валиден, таблицы пусты — Android молча рисует
 * пустоту вместо букв. Ни компилятор, ни тесты композиции этого не видят, поэтому
 * проверяем файлы напрямую.
 */
class FontResourcesTest {

    private val dir = File("src/main/res/font")

    /** Кириллический шрифт с цифрами меньше 50 КБ не бывает. */
    private val minBytes = 50_000L

    @Test
    fun `все шрифты на месте и не обрезаны`() {
        val fonts = dir.listFiles { f -> f.extension == "ttf" }?.sortedBy { it.name }
        assertTrue("каталог шрифтов не найден: ${dir.absolutePath}", fonts != null)
        assertTrue("шрифтов должно быть 4, найдено ${fonts!!.size}", fonts.size == 4)

        fonts.forEach { font ->
            assertTrue(
                "${font.name}: ${font.length()} байт — похоже на обрезанную загрузку",
                font.length() >= minBytes,
            )
            assertTrue("${font.name}: не похож на TrueType", isTrueType(font))
        }
    }

    /** Сигнатура sfnt: 0x00010000 для TrueType или 'true'/'OTTO'. */
    private fun isTrueType(file: File): Boolean =
        RandomAccessFile(file, "r").use { raf ->
            val tag = ByteArray(4)
            raf.readFully(tag)
            val value = ((tag[0].toInt() and 0xFF) shl 24) or
                ((tag[1].toInt() and 0xFF) shl 16) or
                ((tag[2].toInt() and 0xFF) shl 8) or
                (tag[3].toInt() and 0xFF)
            value == 0x00010000 || String(tag) == "true" || String(tag) == "OTTO"
        }
}
