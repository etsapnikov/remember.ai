package ai.prinim.prinyal.ui.theme

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Приложение одноязычное: все строки — русские (словарь §4.1). На телефоне с
 * английской системой русские plurals не выбирались («6 записи» вместо
 * «6 записей») — множественные формы Android берёт из локали, а не из файла строк.
 *
 * Поэтому локаль контекста прибита к ru в обеих активити.
 */
object LocaleForce {

    private val RU = Locale("ru")

    fun wrap(base: Context): Context {
        Locale.setDefault(RU)
        val config = Configuration(base.resources.configuration)
        config.setLocale(RU)
        return base.createConfigurationContext(config)
    }
}
