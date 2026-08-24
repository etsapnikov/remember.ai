package ai.prinim.prinyal.returns

import ai.prinim.prinyal.PrinyalApp
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Приём алармов «Дней»: вечерний вопрос и сборка итога недели.
 *
 * После срабатывания аларм ставится заново — на завтра или на следующее
 * воскресенье: AlarmManager одноразовый, а вопрос ежедневный.
 */
class DayAskReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = PrinyalApp.of(context.applicationContext)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    DayAsk.ACTION_ASK -> {
                        // Уже ответил сегодня (открыл «Дни» руками и наговорил
                        // раньше вопроса) — не спрашиваем второй раз.
                        val today = java.time.LocalDate.now().toString()
                        if (app.db.days().byDate(today) == null) {
                            DayAsk.showAsk(context)
                        }
                        app.analytics.log("day_asked", mapOf("date" to today))
                    }

                    DayAsk.ACTION_RECAP -> app.repository.buildWeekRecap()?.let {
                        DayAsk.showRecapReady(context)
                    }
                }
            } finally {
                DayAsk.schedule(context, app.settings.bedtimeNow())
                pending.finish()
            }
        }
    }
}
